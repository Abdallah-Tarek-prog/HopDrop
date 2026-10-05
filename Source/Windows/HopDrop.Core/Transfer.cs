using System.Buffers;
using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading.Channels;

namespace HopDrop.Core;

/// <summary>A file to send. <paramref name="Folder"/> is where it sits inside a sent folder ("Photos/2024"), or null for a loose file.</summary>
/// <param name="Folder">"/"-separated place inside a sent folder ("Photos/2024"), or null for a loose file.</param>
/// <param name="Root">The sent folder itself (full path), so a whole folder can be shown and removed as one item.</param>
public sealed record SendItem(string Path, string? Folder = null, string? Root = null)
{
    /// <summary>
    /// Files as they are, and folders as every file inside them with its place in the folder. Hidden and system files,
    /// and links to other places (which could loop or lead outside the folder), are left out.
    /// </summary>
    public static List<SendItem> From(IEnumerable<string> paths)
    {
        var items = new List<SendItem>();
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var options = new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true, AttributesToSkip = FileAttributes.Hidden | FileAttributes.System | FileAttributes.ReparsePoint };
        foreach (string raw in paths)
        {
            string path = System.IO.Path.GetFullPath(raw);
            if (File.Exists(path)) { if (seen.Add(path)) items.Add(new SendItem(path)); continue; }
            if (!Directory.Exists(path)) continue;
            string folder = System.IO.Path.TrimEndingDirectorySeparator(path);
            string top = System.IO.Path.GetFileName(folder);
            if (top.Length == 0) top = folder.TrimEnd(':', '\\', '/');
            foreach (string file in Directory.EnumerateFiles(folder, "*", options))
            {
                if (!seen.Add(file)) continue;
                string relative = System.IO.Path.GetRelativePath(folder, System.IO.Path.GetDirectoryName(file)!);
                items.Add(new SendItem(file, relative == "." ? top : top + "/" + relative.Replace('\\', '/'), folder));
            }
        }
        return items;
    }
}

public sealed partial class HopDropPeer
{
    /// <param name="Index">Position in this offer.</param>
    /// <param name="Folder">"/"-separated folder inside a sent folder, or null.</param>
    /// <param name="Key">Position in the whole transfer; differs from <paramref name="Index"/> when a resumed offer leaves out files already saved.</param>
    private sealed record OfferedFile(int Index, string Name, long Size, string? Folder = null, int Key = 0);
    /// <summary>Activity keeps this many entries; older ones are dropped when a new one is added.</summary>
    private const int HistoryLimit = 500;
    /// <summary>Each Activity entry keeps at most this many file names (and the real count).</summary>
    private const int HistoryFiles = 1000;
    /// <summary>A receiver takes at most this many files in one transfer; bigger selections go in several.</summary>
    private const int MaxFilesPerTransfer = 10000;
    /// <summary>File entries in one offer message stay under this many bytes (control messages are capped at 64 KiB).</summary>
    private const int OfferBudget = 56_000;
    /// <summary>After a dropped connection the sender tries again this many times, waiting 2, 4 and 8 s.</summary>
    private const int Reconnects = 3;
    /// <summary>How long a receiver keeps a half-received transfer for the sender to come back.</summary>
    private static readonly TimeSpan KeepInterrupted = TimeSpan.FromMinutes(2);
    private readonly ConcurrentDictionary<(string Peer, Guid Id), Interrupted> _interrupted = new();

    /// <summary>What a receiver keeps of an incoming transfer whose connection dropped, so the sender can continue it.</summary>
    private sealed class Interrupted
    {
        public required string PeerName;
        public required string Folder;
        public required List<(OfferedFile File, string Path, string SavedAs)> Completed;
        public required Dictionary<string, string> Folders;
        public required Stopwatch Clock;
        public required int Offered;
        public long Bytes;
        public OfferedFile? Partial;
        public string? PartialTemp;
        public FileStream? PartialOutput;
        public IncrementalHash? PartialHash;
        public long PartialWritten;
        public bool Trusted;
        public readonly CancellationTokenSource Expiry = new();
        public void DropPartial()
        {
            PartialOutput?.Dispose(); PartialHash?.Dispose();
            try { if (PartialTemp is not null && File.Exists(PartialTemp)) File.Delete(PartialTemp); } catch (IOException) { } catch (UnauthorizedAccessException) { }
            PartialOutput = null; PartialHash = null; PartialTemp = null; Partial = null;
        }
    }
    /// <summary>What a sender knows about its transfer across reconnects.</summary>
    private sealed class Outgoing
    {
        public required string?[] SavedAs;
        public long Done;
        public string Folder = "";
    }

    private static string FolderLabel(string path)
    {
        string trimmed = Path.TrimEndingDirectorySeparator(path);
        string parent = Path.GetFileName(Path.GetDirectoryName(trimmed)) ?? "";
        return parent.Length == 0 ? Path.GetFileName(trimmed) : Path.Combine(parent, Path.GetFileName(trimmed));
    }
    private static (IPAddress Address, int Port) ParseEndpoint(string target, int fallbackPort)
    {
        string[] parts = target.Split(':', 2);
        if (!IPAddress.TryParse(parts[0], out var address) || address.AddressFamily != AddressFamily.InterNetwork) throw new ArgumentException("Expected IPv4[:port] or device id");
        int port = parts.Length == 2 ? int.Parse(parts[1]) : fallbackPort;
        if (port is < 1 or > 65535) throw new ArgumentOutOfRangeException(nameof(target));
        return (address, port);
    }
    /// <summary>A folder path made safe to recreate: "/"-separated, every part a valid Windows name, no "..", at most 32 levels. Null for none.</summary>
    internal static string? CleanFolder(string? folder)
    {
        if (string.IsNullOrWhiteSpace(folder)) return null;
        var parts = folder.Split('/', '\\').Where(p => p.Length > 0 && p != ".").Select(p => p == ".." ? "_" : FileNames.Sanitize(p)).Take(32).ToArray();
        return parts.Length == 0 ? null : string.Join('/', parts);
    }
    private static Dictionary<string, object> Entry(OfferedFile f)
    {
        var entry = new Dictionary<string, object> { ["i"] = f.Index, ["name"] = f.Name, ["size"] = f.Size };
        if (f.Folder is not null) entry["path"] = f.Folder;
        if (f.Key != f.Index) entry["k"] = f.Key;
        return entry;
    }
    private static int EntrySize(OfferedFile f) => JsonSerializer.SerializeToUtf8Bytes(Entry(f)).Length + 1;

    public Task<TransferResult> SendAsync(string target, IReadOnlyList<string> paths, CancellationToken ct = default) =>
        SendAsync(target, paths.Select(p => new SendItem(p)).ToList(), ct);
    /// <summary>Sends files and folders. A very large selection to a device that can't take it in one transfer goes in several.</summary>
    public async Task<TransferResult> SendAsync(string target, IReadOnlyList<SendItem> items, CancellationToken ct = default)
    {
        if (items.Count == 0) throw new ArgumentException("At least one file required", nameof(items));
        var saved = new List<string>();
        TransferResult? last = null;
        for (int start = 0; start < items.Count;)
        {
            var (result, taken) = await SendBatchAsync(target, items, start, ct);
            saved.AddRange(result.SavedPaths); start += taken; last = result;
        }
        return items.Count == last!.Offered ? last : last with { SavedPaths = saved, Offered = items.Count };
    }
    private async Task<(TransferResult Result, int Taken)> SendBatchAsync(string target, IReadOnlyList<SendItem> items, int start, CancellationToken ct)
    {
        string? alias = null;
        PairedDevice? device = null;
        (IPAddress Address, int Port)? endpoint = null;
        if (Identity.IsId(target))
        {
            device = (await DevicesAsync(ct)).FirstOrDefault(d => d.Id == target) ?? throw new HopDropException("not_paired", "Device is not paired");
            alias = device.Alias;
        }
        else endpoint = ParseEndpoint(target, 7410);
        async Task<Connection> ConnectTargetAsync(CancellationToken token)
        {
            if (device is not null) return await ConnectKnownAsync((await DevicesAsync(token)).FirstOrDefault(d => d.Id == device.Id) ?? device, token);
            var (address, port) = endpoint!.Value;
            var c = await ConnectAsync(address, port, null, token);
            if (!(await DevicesAsync(token)).Any(d => d.Id == c.Id)) { c.Dispose(); throw new HopDropException("not_paired", "Device is not paired"); }
            try { await SaveDeviceAsync(c.Id, c.Name, c.Platform, address, token, port); } catch { c.Dispose(); throw; }
            return c;
        }
        Connection? connection = await ConnectTargetAsync(ct);
        string peerId = connection.Id, peerName = alias ?? connection.Name;
        bool folders = connection.Features.Contains("folders"), pages = connection.Features.Contains("pages"), resume = connection.Features.Contains("resume");
        // As many files as this receiver takes in one transfer: one offer message for older receivers, up to 10,000 with "pages".
        var files = new List<OfferedFile>(); var paths = new List<string>();
        // Activity lists files from a sent folder by their place in it ("Photos/2024/a.jpg"), so the folder shows as one item.
        var shown = new List<string>();
        int budget = 0;
        try
        {
            for (int k = start; k < items.Count && files.Count < MaxFilesPerTransfer; k++)
            {
                var file = new OfferedFile(files.Count, Path.GetFileName(items[k].Path), new FileInfo(items[k].Path).Length, folders ? CleanFolder(items[k].Folder) : null, files.Count);
                int size = EntrySize(file);
                if (!pages && files.Count > 0 && budget + size > OfferBudget) break;
                budget += size; files.Add(file); paths.Add(items[k].Path);
                shown.Add(items[k].Folder is { } folder ? folder + "/" + Path.GetFileName(items[k].Path) : items[k].Path);
            }
        }
        catch { connection.Dispose(); throw; }
        long total = files.Any(f => f.Size < 0) ? -1 : files.Sum(f => f.Size);
        Guid id = Guid.NewGuid();
        using var transferStop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _transfers[id] = transferStop;
        var state = new Outgoing { SavedAs = new string?[files.Count] };
        var clock = Stopwatch.StartNew(); var meter = new TransferMeter(total, clock.Elapsed);
        TransferResult Result(string? error) => new(id, peerId, false, state.SavedAs.Where(s => s is not null).Select(s => s!).ToList(), state.Folder, error)
        { PeerName = peerName, Offered = files.Count, Bytes = state.Done, Duration = clock.Elapsed };
        try
        {
            for (int attempt = 0; ; attempt++)
            {
                try
                {
                    connection ??= await ConnectTargetAsync(transferStop.Token);
                    await SendFilesAsync(connection, id, files, paths, state, peerName, total, meter, clock, transferStop.Token);
                    break;
                }
                catch (Exception e) when (resume && attempt < Reconnects && !transferStop.IsCancellationRequested && Retryable(e))
                {
                    // The connection dropped (Wi-Fi blip, phone switching networks): reconnect and continue where it stopped.
                    connection?.Dispose(); connection = null;
                    Progress?.Invoke(new(id, peerId, peerName, false, "", 0, files.Count, state.Done, total, 0, null) { Reconnecting = true });
                    await Task.Delay(TimeSpan.FromSeconds(2 << attempt), transferStop.Token);
                }
            }
        }
        catch (OperationCanceledException) when (transferStop.IsCancellationRequested)
        {
            if (connection is not null) try { await Messages.WriteAsync(connection.Stream, new { type = "cancel" }); } catch { }
            TransferFinished?.Invoke(Result("cancelled"));
            try { await AddHistoryAsync(peerId, "sent", "cancelled", shown, state.Folder); } catch { }
            throw new HopDropException("cancelled", "Transfer cancelled");
        }
        catch (Exception e)
        {
            string code = e is HopDropException ae ? ae.Code : e is TimeoutException ? "timeout" : "io_error";
            TransferFinished?.Invoke(Result(code));
            try { await AddHistoryAsync(peerId, "sent", code, shown, state.Folder); } catch { }
            if (e is HopDropException) throw;
            throw new HopDropException(code, e.Message);
        }
        finally { connection?.Dispose(); _transfers.TryRemove(id, out _); }
        // Outside the try: the files arrived, so a failure to write Activity mustn't report the transfer as failed.
        var result = Result(null);
        TransferFinished?.Invoke(result);
        try { await AddHistoryAsync(peerId, "sent", null, shown, state.Folder); } catch (Exception e) { Error?.Invoke("Activity: " + e.Message); }
        return (result, files.Count);
    }
    /// <summary>A dropped or stalled connection (worth reconnecting), as opposed to an answer from the receiver or a local file problem.</summary>
    /// <remarks>Network failures arrive as end-of-stream or as an IOException wrapping the socket error; a full disk or locked file is a plain IOException and is not retried.</remarks>
    private static bool Retryable(Exception e) => e switch
    {
        HopDropException a => a.Code == "timeout",
        TimeoutException or SocketException or EndOfStreamException or OperationCanceledException => true,
        IOException { InnerException: SocketException or IOException } => true,
        _ => false
    };
    /// <summary>
    /// One connection's worth of a transfer: offers the files the receiver hasn't confirmed yet, continues a half-sent one
    /// from where the receiver says it stopped, and records each confirmed file in <paramref name="state"/>.
    /// </summary>
    private async Task SendFilesAsync(Connection connection, Guid id, List<OfferedFile> files, List<string> paths, Outgoing state, string peerName, long total,
        TransferMeter meter, Stopwatch clock, CancellationToken ct)
    {
        var pending = Enumerable.Range(0, files.Count).Where(i => state.SavedAs[i] is null).ToArray();
        var offered = pending.Select((original, j) => files[original] with { Index = j }).ToArray();
        long offeredTotal = offered.Any(f => f.Size < 0) ? -1 : offered.Sum(f => f.Size);
        await WriteOfferAsync(connection, id, offered, offeredTotal, ct);
        // A receiver that asks its user first says "pending" (only to senders that list "consent") and may take up to two minutes.
        bool consent = connection.Features.Contains("consent");
        var skip = new HashSet<int>(); int partialIndex = -1; long partialOffset = 0;
        while (true)
        {
            using var reply = await ReadControlAsync(connection.Stream, ct, consent ? 130 : 30);
            Messages.ThrowIfError(reply.RootElement);
            string type = Messages.Type(reply.RootElement);
            if (type == "pending" && consent)
            {
                Progress?.Invoke(new(id, connection.Id, peerName, false, offered[0].Name, 0, files.Count, state.Done, total, 0, null) { WaitingForApproval = true });
                continue;
            }
            if (type != "accept") throw new HopDropException("protocol_error", "Expected accept");
            state.Folder = Messages.String(reply.RootElement, "folder");
            if (reply.RootElement.TryGetProperty("resume", out var resume) && resume.ValueKind == JsonValueKind.Object)
            {
                if (resume.TryGetProperty("done", out var doneList) && doneList.ValueKind == JsonValueKind.Array)
                    foreach (var item in doneList.EnumerateArray())
                    {
                        int j = checked((int)Messages.Long(item, "i"));
                        if (j < 0 || j >= offered.Length || !skip.Add(j)) throw new HopDropException("protocol_error", "Invalid resume");
                        state.SavedAs[pending[j]] = Messages.String(item, "savedAs");
                        state.Done += Math.Max(0, offered[j].Size);
                    }
                if (resume.TryGetProperty("i", out var partial))
                {
                    partialIndex = partial.GetInt32(); partialOffset = Messages.Long(resume, "offset");
                    if (partialIndex < 0 || partialIndex >= offered.Length || skip.Contains(partialIndex) || partialOffset < 0
                        || offered[partialIndex].Size >= 0 && partialOffset > offered[partialIndex].Size) throw new HopDropException("protocol_error", "Invalid resume");
                }
            }
            break;
        }
        var replies = Channel.CreateUnbounded<JsonElement>(new UnboundedChannelOptions { SingleReader = true, SingleWriter = true });
        using var readerStop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        Task reader = Task.Run(async () =>
        {
            try
            {
                while (!readerStop.IsCancellationRequested)
                {
                    using var doc = await Messages.ReadAsync(connection.Stream, readerStop.Token);
                    var message = doc.RootElement.Clone();
                    await replies.Writer.WriteAsync(message, readerStop.Token);
                    if (Messages.Type(message) is "error" or "cancel" or "done_ok") break;
                }
                replies.Writer.TryComplete();
            }
            catch (Exception e) { replies.Writer.TryComplete(e); }
        }, CancellationToken.None);
        // File data is read straight into the frame (after its 5-byte header), so each chunk is copied once and sent in one write.
        byte[] frame = ArrayPool<byte>.Shared.Rent(5 + Framing.MaxData);
        try
        {
            for (int j = 0; j < offered.Length; j++)
            {
                if (skip.Contains(j)) continue;
                var file = offered[j];
                long offset = j == partialIndex ? partialOffset : 0;
                ReportProgress(meter, true, id, connection.Id, peerName, false, file.Name, pending[j] + 1, files.Count, state.Done, total, clock);
                await Messages.WriteAsync(connection.Stream, offset > 0 ? new { type = "file", i = j, offset } : new { type = "file", i = j }, ct);
                await using var input = new FileStream(paths[pending[j]], FileMode.Open, FileAccess.Read, FileShare.Read, 0, FileOptions.Asynchronous | FileOptions.SequentialScan);
                using var hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                // Continuing a half-sent file: the receiver already has these bytes, but the checksum covers the whole file.
                for (long skipped = 0; skipped < offset;)
                {
                    int count = await input.ReadAsync(frame.AsMemory(5, (int)Math.Min(Framing.MaxData, offset - skipped)), ct);
                    if (count == 0) throw new HopDropException("io_error", "File is shorter than before");
                    hash.AppendData(frame, 5, count); skipped += count;
                }
                state.Done += offset;
                while (true)
                {
                    int count = await input.ReadAsync(frame.AsMemory(5, Framing.MaxData), ct);
                    if (count == 0) break;
                    if (replies.Reader.TryRead(out var early)) ThrowReply(early, "file_ok");
                    hash.AppendData(frame, 5, count);
                    await Framing.WriteDataAsync(connection.Stream, frame, count, ct);
                    state.Done += count;
                    ReportProgress(meter, false, id, connection.Id, peerName, false, file.Name, pending[j] + 1, files.Count, state.Done, total, clock);
                }
                await Messages.WriteAsync(connection.Stream, new { type = "file_end", i = j, sha256 = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant() }, ct);
                var reply = await replies.Reader.ReadAsync(ct).AsTask().WaitAsync(TimeSpan.FromSeconds(30), ct); ThrowReply(reply, "file_ok");
                if (Messages.Long(reply, "i") != j) throw new HopDropException("protocol_error", "Wrong file index");
                state.SavedAs[pending[j]] = Messages.String(reply, "savedAs");
            }
            await Messages.WriteAsync(connection.Stream, new { type = "done" }, ct);
            var final = await replies.Reader.ReadAsync(ct).AsTask().WaitAsync(TimeSpan.FromSeconds(30), ct); ThrowReply(final, "done_ok");
            ReportProgress(meter, true, id, connection.Id, peerName, false, files[^1].Name, files.Count, files.Count, state.Done, total, clock);
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(frame);
            readerStop.Cancel(); try { await reader; } catch (OperationCanceledException) { }
        }
    }
    /// <summary>Sends the offer, split over "offer_more" messages when the list doesn't fit in one (receivers that list "pages").</summary>
    private static async Task WriteOfferAsync(Connection connection, Guid id, OfferedFile[] offered, long total, CancellationToken ct)
    {
        var pages = new List<List<Dictionary<string, object>>> { new() };
        int size = 0;
        foreach (var file in offered)
        {
            int entry = EntrySize(file);
            if (size + entry > OfferBudget && pages[^1].Count > 0) { pages.Add([]); size = 0; }
            pages[^1].Add(Entry(file)); size += entry;
        }
        await Messages.WriteAsync(connection.Stream, new { type = "offer", transferId = id, count = offered.Length, totalBytes = total, files = pages[0], more = pages.Count > 1 }, ct);
        for (int p = 1; p < pages.Count; p++)
            await Messages.WriteAsync(connection.Stream, new { type = "offer_more", files = pages[p], more = p < pages.Count - 1 }, ct);
    }
    private static void ThrowReply(JsonElement reply, string expected)
    {
        Messages.ThrowIfError(reply);
        if (Messages.Type(reply) == "cancel") throw new HopDropException("cancelled", "Peer cancelled transfer");
        if (Messages.Type(reply) != expected) throw new HopDropException("protocol_error", $"Expected {expected}");
    }
    private void ReportProgress(TransferMeter meter, bool force, Guid id, string peer, string peerName, bool incoming, string file, int number, int count, long done, long total, Stopwatch clock)
    {
        var now = clock.Elapsed;
        meter.Update(done, now);
        if (!meter.Due(now) && !force) return;
        Progress?.Invoke(new(id, peer, peerName, incoming, file, number, count, done, total, meter.Speed(done, now), meter.Left(done, now)));
    }
    /// <summary>
    /// Asks the user whether to take an offer. Returns the read of the sender's next message, started while waiting:
    /// the sender stays silent until it gets an answer, so anything arriving earlier means it cancelled or went away.
    /// Throws when the user declines or doesn't answer in time.
    /// </summary>
    private async Task<Task<JsonDocument>> AskAsync(Stream stream, Guid id, string peerId, string peerName, List<OfferedFile> files, long total, bool senderWaits, CancellationToken ct)
    {
        // Senders from before "consent" give up after 30 s without an answer, so they get a shorter window.
        var window = senderWaits ? TimeSpan.FromSeconds(120) : TimeSpan.FromSeconds(25);
        var request = new IncomingOffer(id, peerId, peerName, files.Select(f => f.Folder is null ? f.Name : f.Folder + "/" + f.Name).ToList(), total, DateTimeOffset.UtcNow + window);
        try
        {
            if (senderWaits) await Messages.WriteAsync(stream, new { type = "pending" }, ct);
            var next = Messages.ReadAsync(stream, ct);
            _ = next.ContinueWith(t => _ = t.Exception, TaskContinuationOptions.OnlyOnFaulted);
            OfferReceived?.Invoke(request);
            using var wait = CancellationTokenSource.CreateLinkedTokenSource(ct);
            var first = await Task.WhenAny(request.Answer, next, Task.Delay(window, wait.Token));
            wait.Cancel();
            ct.ThrowIfCancellationRequested();
            if (first == next) throw new HopDropException("cancelled", "The sender cancelled");
            if (first != request.Answer || !await request.Answer) throw new HopDropException("declined", "The files weren't accepted");
            return next;
        }
        finally { request.Close(); }
    }
    /// <summary>Reads the offered files, including the "offer_more" pages that follow when the list didn't fit in one message.</summary>
    private static async Task<List<OfferedFile>> ReadOfferedFilesAsync(Stream stream, JsonElement offer, int count, Func<Stream, CancellationToken, Task<JsonDocument>> read, CancellationToken ct)
    {
        var files = new List<OfferedFile>(count);
        void Add(JsonElement list)
        {
            if (list.ValueKind != JsonValueKind.Array) throw new HopDropException("protocol_error", "Invalid offer");
            foreach (var item in list.EnumerateArray())
            {
                int index = checked((int)Messages.Long(item, "i")); long size = Messages.Long(item, "size");
                if (index != files.Count || size < -1 || files.Count >= count) throw new HopDropException("protocol_error", "Invalid file offer");
                string? folder = item.TryGetProperty("path", out var path) && path.ValueKind == JsonValueKind.String ? CleanFolder(path.GetString()) : null;
                int key = item.TryGetProperty("k", out var k) && k.TryGetInt32(out int n) ? n : index;
                if (key < 0 || key >= MaxFilesPerTransfer) throw new HopDropException("protocol_error", "Invalid file offer");
                files.Add(new OfferedFile(index, FileNames.Sanitize(Messages.String(item, "name")), size, folder, key));
            }
        }
        if (!offer.TryGetProperty("files", out var first)) throw new HopDropException("protocol_error", "Invalid offer");
        Add(first);
        bool more = offer.TryGetProperty("more", out var flag) && flag.ValueKind == JsonValueKind.True;
        while (more)
        {
            using var page = await read(stream, ct);
            if (Messages.Type(page.RootElement) != "offer_more") throw new HopDropException("protocol_error", "Expected offer_more");
            Add(page.RootElement.TryGetProperty("files", out var list) ? list : default);
            more = page.RootElement.TryGetProperty("more", out var next) && next.ValueKind == JsonValueKind.True;
        }
        if (files.Count != count) throw new HopDropException("protocol_error", "Invalid offer");
        return files;
    }
    /// <summary>The folder a received file goes into: its sent folder, recreated under the receive folder. A top folder that already exists gets a new name ("Photos (1)").</summary>
    private static string TargetFolder(string dir, string? folder, Dictionary<string, string> tops)
    {
        if (folder is null) return dir;
        string[] parts = folder.Split('/');
        if (!tops.TryGetValue(parts[0], out var top))
        {
            top = FileNames.Unique(parts[0], n => Directory.Exists(Path.Combine(dir, n)) || File.Exists(Path.Combine(dir, n)) || tops.ContainsValue(n));
            tops[parts[0]] = top;
        }
        string target = Path.Combine([dir, top, .. parts[1..]]);
        Directory.CreateDirectory(target);
        return target;
    }
    private async Task ReceiveAsync(Stream stream, JsonElement offer, string peerId, string peerName, bool trusted, IReadOnlySet<string> senderFeatures, CancellationToken ct)
    {
        if (!Guid.TryParse(Messages.String(offer, "transferId"), out var id)) throw new HopDropException("protocol_error", "Invalid transfer id");
        int count = checked((int)Messages.Long(offer, "count"));
        long total = Messages.Long(offer, "totalBytes");
        if (count < 1 || count > MaxFilesPerTransfer || total < -1) throw new HopDropException("protocol_error", "Invalid offer");
        var files = await ReadOfferedFilesAsync(stream, offer, count, (s, token) => ReadControlAsync(s, token), ct);
        if (total >= 0 && files.Any(f => f.Size < 0)) throw new HopDropException("protocol_error", "Invalid total size");
        if (total >= 0 && files.Sum(f => f.Size) != total) throw new HopDropException("protocol_error", "Invalid total size");
        bool canResume = senderFeatures.Contains("resume");
        // The same transfer coming back after a dropped connection: skip what's saved, continue the half-received file.
        _interrupted.TryRemove((peerId, id), out var earlier);
        if (earlier is not null) { earlier.Expiry.Cancel(); trusted = earlier.Trusted; }
        string dir = ReceiveDirectory;
        Directory.CreateDirectory(dir);
        if (earlier is null)
        {
            long free = _options.FreeSpace?.Invoke(dir) ?? new DriveInfo(Path.GetPathRoot(Path.GetFullPath(dir))!).AvailableFreeSpace;
            if (total >= 0 && free < total + 16L * 1024 * 1024) throw new HopDropException("no_space", "Not enough free space");
        }
        string folder = earlier?.Folder ?? FolderLabel(dir);
        Task<JsonDocument>? pending = null;
        if (earlier is null && AskBeforeReceiving && !trusted && OfferReceived is not null)
            pending = await AskAsync(stream, id, peerId, peerName, files, total, senderFeatures.Contains("consent"), ct);
        var completed = earlier?.Completed ?? [];
        var tops = earlier?.Folders ?? [];
        var already = new Dictionary<int, string>();
        int partialIndex = -1;
        if (earlier is not null)
        {
            var unmatched = completed.ToList();
            for (int j = 0; j < files.Count; j++)
            {
                var f = files[j];
                int match = unmatched.FindIndex(c => c.File.Key == f.Key && c.File.Name == f.Name && c.File.Size == f.Size && c.File.Folder == f.Folder);
                if (match >= 0) { already[j] = unmatched[match].SavedAs; unmatched.RemoveAt(match); }
                else if (partialIndex < 0 && earlier.Partial is { } p && p.Key == f.Key && p.Name == f.Name && p.Size == f.Size && p.Folder == f.Folder) partialIndex = j;
            }
            if (partialIndex < 0) earlier.DropPartial();
        }
        object accept = earlier is null ? new { type = "accept", folder }
            : partialIndex >= 0
                ? new { type = "accept", folder, resume = new { done = already.Select(a => new { i = a.Key, savedAs = a.Value }).ToArray(), i = partialIndex, offset = earlier.PartialWritten } }
                : new { type = "accept", folder, resume = new { done = already.Select(a => new { i = a.Key, savedAs = a.Value }).ToArray() } };
        await Messages.WriteAsync(stream, accept, ct);
        var clock = earlier?.Clock ?? Stopwatch.StartNew();
        long done = earlier?.Bytes ?? 0;
        var meter = new TransferMeter(total, clock.Elapsed);
        int offered = earlier?.Offered ?? files.Count;
        TransferResult Result(string? error) => new(id, peerId, true, completed.Select(c => c.Path).ToList(), folder, error)
        { PeerName = peerName, Offered = offered, Bytes = done, Duration = clock.Elapsed, Trusted = trusted };
        using var transferStop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _transfers[id] = transferStop;
        using var reader = new FrameReader(stream);
        OfferedFile? current = null; string? temp = null; FileStream? output = null; IncrementalHash? hash = null; long written = 0;
        bool suspended = false;
        try
        {
            for (int i = 0; i < files.Count; i++)
            {
                if (already.ContainsKey(i)) continue;
                var file = files[i];
                ReportProgress(meter, true, id, peerId, peerName, true, file.Name, completed.Count + 1, offered, done, total, clock);
                JsonDocument startDocument;
                if (pending is not null)
                {
                    try { startDocument = await pending.WaitAsync(TimeSpan.FromSeconds(30), transferStop.Token); }
                    catch (TimeoutException) { throw new HopDropException("timeout", "Sender timed out"); }
                    pending = null;
                }
                else startDocument = await ReadControlAsync(stream, transferStop.Token);
                long offset;
                using (var start = startDocument)
                {
                    if (Messages.Type(start.RootElement) == "cancel") throw new HopDropException("cancelled", "Sender cancelled");
                    if (Messages.Type(start.RootElement) != "file" || Messages.Long(start.RootElement, "i") != i) throw new HopDropException("protocol_error", "Expected file");
                    offset = start.RootElement.TryGetProperty("offset", out var o) && o.TryGetInt64(out long n) ? n : 0;
                }
                current = file;
                if (i == partialIndex && earlier!.PartialOutput is not null && offset == earlier.PartialWritten && offset > 0)
                {
                    temp = earlier.PartialTemp; output = earlier.PartialOutput; hash = earlier.PartialHash; written = offset;
                    earlier.PartialOutput = null; earlier.PartialHash = null; earlier.PartialTemp = null;
                }
                else
                {
                    if (offset != 0) throw new HopDropException("protocol_error", "Unexpected offset");
                    if (i == partialIndex) earlier!.DropPartial();
                    string target;
                    await _namingGate.WaitAsync(transferStop.Token);
                    try { target = TargetFolder(dir, file.Folder, tops); } finally { _namingGate.Release(); }
                    temp = Path.Combine(target, ".hopdrop-" + Guid.NewGuid().ToString("N") + ".part");
                    output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 0, FileOptions.Asynchronous);
                    hash = IncrementalHash.CreateHash(HashAlgorithmName.SHA256);
                    written = 0;
                }
                if (output is null || hash is null) throw new HopDropException("io_error", "Couldn't continue the file");
                while (true)
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(transferStop.Token); timeout.CancelAfter(TimeSpan.FromSeconds(30));
                    (byte Kind, ReadOnlyMemory<byte> Payload) frame;
                    try { frame = await reader.ReadAsync(timeout.Token); }
                    catch (OperationCanceledException) when (!transferStop.IsCancellationRequested) { throw new HopDropException("timeout", "Transfer idle timeout"); }
                    if (frame.Kind == 2)
                    {
                        if (file.Size >= 0 && written + frame.Payload.Length > file.Size) throw new HopDropException("io_error", "File exceeds offered size");
                        hash.AppendData(frame.Payload.Span);
                        await output.WriteAsync(frame.Payload, transferStop.Token);
                        written += frame.Payload.Length; done += frame.Payload.Length;
                        ReportProgress(meter, false, id, peerId, peerName, true, file.Name, completed.Count + 1, offered, done, total, clock);
                        continue;
                    }
                    using var end = JsonDocument.Parse(frame.Payload.ToArray());
                    if (Messages.Type(end.RootElement) == "cancel") throw new HopDropException("cancelled", "Sender cancelled");
                    if (Messages.Type(end.RootElement) != "file_end" || Messages.Long(end.RootElement, "i") != i) throw new HopDropException("protocol_error", "Expected file_end");
                    if (file.Size >= 0 && written != file.Size) throw new HopDropException("io_error", "File size differs");
                    string actual = Convert.ToHexString(hash.GetHashAndReset()).ToLowerInvariant();
                    if (!string.Equals(Messages.String(end.RootElement, "sha256"), actual, StringComparison.OrdinalIgnoreCase)) throw new HopDropException("checksum_mismatch", "File checksum differs");
                    break;
                }
                await output.FlushAsync(transferStop.Token);
                await output.DisposeAsync(); output = null; hash.Dispose(); hash = null;
                // Marked before it gets its real name, so the file never appears without the mark.
                if (_options.MarkReceivedFiles && !trusted && !ZoneMark.Write(temp!)) Error?.Invoke("Couldn't mark a received file as downloaded (the receive folder's drive doesn't support it)");
                string finalPath, savedAs;
                await _namingGate.WaitAsync(transferStop.Token);
                try
                {
                    string target = Path.GetDirectoryName(temp)!;
                    string finalName = FileNames.Unique(file.Name, candidate => File.Exists(Path.Combine(target, candidate)) || completed.Any(c => string.Equals(c.Path, Path.Combine(target, candidate), StringComparison.OrdinalIgnoreCase)));
                    finalPath = Path.Combine(target, finalName);
                    File.Move(temp!, finalPath); temp = null;
                    savedAs = Path.GetRelativePath(dir, finalPath).Replace('\\', '/');
                }
                finally { _namingGate.Release(); }
                completed.Add((file, finalPath, savedAs)); current = null;
                await Messages.WriteAsync(stream, new { type = "file_ok", i, savedAs }, transferStop.Token);
            }
            using var doneMessage = await ReadControlAsync(stream, transferStop.Token);
            if (Messages.Type(doneMessage.RootElement) != "done") throw new HopDropException("protocol_error", "Expected done");
            await Messages.WriteAsync(stream, new { type = "done_ok", saved = completed.Count }, transferStop.Token);
        }
        catch (OperationCanceledException) when (transferStop.IsCancellationRequested && !ct.IsCancellationRequested)
        {
            TransferFinished?.Invoke(Result("cancelled"));
            try { await AddHistoryAsync(peerId, "received", "cancelled", completed.Select(c => c.Path).ToList(), folder); } catch { }
            try { await Messages.WriteAsync(stream, new { type = "cancel" }, ct); } catch { }
            return;
        }
        catch (Exception e) when (canResume && !ct.IsCancellationRequested && Retryable(e))
        {
            // The connection dropped: keep what arrived (and the open half-received file) for the sender to continue.
            var kept = new Interrupted
            {
                PeerName = peerName, Folder = folder, Completed = completed, Folders = tops, Clock = clock, Offered = offered, Bytes = done, Trusted = trusted,
                Partial = output is null ? null : current, PartialTemp = output is null ? null : temp, PartialOutput = output, PartialHash = output is null ? null : hash, PartialWritten = written
            };
            if (output is null) hash?.Dispose();
            output = null; hash = null; temp = kept.PartialTemp is null ? temp : null;
            _interrupted[(peerId, id)] = kept;
            suspended = true;
            Progress?.Invoke(new(id, peerId, peerName, true, current?.Name ?? "", completed.Count + 1, offered, done, total, 0, null) { Reconnecting = true });
            _ = ExpireAsync((peerId, id), kept);
        }
        catch (Exception e)
        {
            string code = e is HopDropException ae ? ae.Code : e is TimeoutException ? "timeout" : "io_error";
            TransferFinished?.Invoke(Result(code));
            try { await AddHistoryAsync(peerId, "received", code, completed.Select(c => c.Path).ToList(), folder); } catch { }
            throw;
        }
        finally
        {
            if (output is not null) await output.DisposeAsync();
            hash?.Dispose();
            if (temp is not null && File.Exists(temp)) File.Delete(temp);
            _transfers.TryRemove(id, out _);
        }
        if (suspended) return;
        // Outside the try: the files are saved, so a failure to write Activity mustn't report the transfer as failed.
        TransferFinished?.Invoke(Result(null));
        try { await AddHistoryAsync(peerId, "received", null, completed.Select(c => c.Path).ToList(), folder); } catch (Exception e) { Error?.Invoke("Activity: " + e.Message); }
    }
    /// <summary>Gives up on an interrupted incoming transfer the sender didn't come back for.</summary>
    private async Task ExpireAsync((string Peer, Guid Id) key, Interrupted kept)
    {
        try { await Task.Delay(KeepInterrupted, kept.Expiry.Token); }
        catch (OperationCanceledException) { return; }
        if (!_interrupted.TryRemove(new KeyValuePair<(string, Guid), Interrupted>(key, kept))) return;
        kept.DropPartial();
        var result = new TransferResult(key.Id, key.Peer, true, kept.Completed.Select(c => c.Path).ToList(), kept.Folder, "disconnected")
        { PeerName = kept.PeerName, Offered = kept.Offered, Bytes = kept.Bytes, Duration = kept.Clock.Elapsed, Trusted = kept.Trusted };
        TransferFinished?.Invoke(result);
        try { await AddHistoryAsync(key.Peer, "received", "disconnected", result.SavedPaths.ToList(), kept.Folder); } catch { }
    }
    private async Task AddHistoryAsync(string peer, string direction, string? result, IEnumerable<string> files, string folder)
    {
        // A folder can hold thousands of files: keep the first ones and the real count, so Activity stays small and quick.
        var all = files.ToList();
        var list = all.Count > HistoryFiles ? all.GetRange(0, HistoryFiles) : all;
        await _history.UpdateAsync(old =>
        {
            old.Add(new HistoryEntry(DateTimeOffset.UtcNow, peer, direction, result ?? "ok", list, folder, all.Count > HistoryFiles ? all.Count : null));
            if (old.Count > HistoryLimit) old.RemoveRange(0, old.Count - HistoryLimit);
            return old;
        });
    }
}
