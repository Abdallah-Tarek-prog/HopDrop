using System.Collections.Concurrent;
using System.IO.Pipes;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using HopDrop.Core;

namespace HopDrop.Agents;

/// <summary>
/// Lets AI agents on this PC (Claude, Codex, Cursor… through MCP, or scripts through "HopDrop.exe agent") use the running
/// HopDrop: list the paired devices, send files and folders to them, and find files that arrived. Agents can't pair
/// devices, change settings or accept files. Off until the user turns on Settings → AI agents; only this OS user
/// can connect. Agents talk to the running app (window, tray or hidden receiver), so the user sees what they send.
/// </summary>
public sealed class AgentService : IAsyncDisposable
{
    private const string Off = "AI agent access is off. Turn it on in HopDrop → Settings → AI agents.";
    private readonly HopDropPeer _peer;
    private readonly string _dataDirectory;
    private readonly Func<bool> _enabled;
    private readonly ConcurrentDictionary<Guid, AgentSend> _sends = new();
    private readonly ConcurrentDictionary<Guid, TransferProgress> _outgoing = new();
    private readonly AsyncLocal<Guid?> _currentSend = new();
    private CancellationTokenSource? _stop;
    private Task? _accept;

    public AgentService(HopDropPeer peer, string dataDirectory, Func<bool> enabled)
    {
        _peer = peer;
        _dataDirectory = dataDirectory;
        _enabled = enabled;
    }

    private sealed class AgentSend
    {
        public required Guid Id { get; init; }
        public required PairedDevice Device { get; init; }
        public required int Files { get; init; }
        public Task<TransferResult> Task { get; set; } = null!;
    }

    /// <summary>The agents' pipe for HopDrop's data folder (one HopDrop runs per data folder).</summary>
    public static string PipeName(string dataDirectory) =>
        "HopDrop-agent-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(OperatingSystem.IsWindows()
            ? Path.GetFullPath(dataDirectory).ToUpperInvariant() : Path.GetFullPath(dataDirectory))))[..24];

    public void Start(CancellationToken stop)
    {
        if (_accept is not null) throw new InvalidOperationException("Agent service already started.");
        _stop = CancellationTokenSource.CreateLinkedTokenSource(stop);
        _peer.Progress += OnProgress;
        _accept = Task.WhenAll(Enumerable.Range(0, 8).Select(_ => AcceptAsync(PipeName(_dataDirectory), _stop.Token)));
    }

    private void OnProgress(TransferProgress progress)
    {
        if (!progress.Incoming && _currentSend.Value is { } id) _outgoing[id] = progress;
    }

    public async ValueTask DisposeAsync()
    {
        _stop?.Cancel();
        _peer.Progress -= OnProgress;
        if (_accept is not null) await _accept;
        _stop?.Dispose();
    }

    private async Task AcceptAsync(string name, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            NamedPipeServerStream? server = null;
            try
            {
                server = new NamedPipeServerStream(name, PipeDirection.InOut, NamedPipeServerStream.MaxAllowedServerInstances, PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(stop);
                await ServeAsync(server, stop);
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
            catch (Exception e) { System.Diagnostics.Debug.WriteLine(e); try { await Task.Delay(1000, stop); } catch (OperationCanceledException) { break; } }
            finally { server?.Dispose(); }
        }
    }

    /// <summary>One request per connection: a line of JSON in, a line of JSON out.</summary>
    private async Task ServeAsync(NamedPipeServerStream pipe, CancellationToken stop)
    {
        await using var _ = pipe;
        try
        {
            using var reader = new StreamReader(pipe, new UTF8Encoding(false), false, 4096, true);
            using var requestStop = CancellationTokenSource.CreateLinkedTokenSource(stop);
            requestStop.CancelAfter(TimeSpan.FromSeconds(30));
            JsonObject reply;
            try
            {
                string? line = await ReadLineAsync(reader, 64 * 1024, requestStop.Token);
                if (line is null) return;
                reply = await HandleAsync(JsonNode.Parse(line)?.AsObject() ?? throw new AgentException("Empty request."), stop);
                reply["ok"] = true;
            }
            catch (AgentException e) { reply = new JsonObject { ["ok"] = false, ["error"] = e.Message }; }
            catch (Exception e) when (e is JsonException or InvalidOperationException or ArgumentException or FormatException) { reply = new JsonObject { ["ok"] = false, ["error"] = "HopDrop couldn't read that request." }; }
            catch (Exception e) { reply = new JsonObject { ["ok"] = false, ["error"] = "HopDrop couldn't complete the request: " + e.Message }; }
            byte[] bytes = Encoding.UTF8.GetBytes(reply.ToJsonString() + "\n");
            await pipe.WriteAsync(bytes, stop);
            await pipe.FlushAsync(stop);
        }
        catch (Exception e) when (e is IOException or OperationCanceledException or ObjectDisposedException) { }
    }

    internal static async Task<string?> ReadLineAsync(TextReader reader, int maxChars, CancellationToken stop)
    {
        var text = new StringBuilder();
        char[] buffer = new char[1];
        while (text.Length <= maxChars)
        {
            int read = await reader.ReadAsync(buffer.AsMemory(), stop);
            if (read == 0) return text.Length == 0 ? null : text.ToString();
            if (buffer[0] == '\n') return text.ToString().TrimEnd('\r');
            text.Append(buffer[0]);
        }
        throw new AgentException("Request is too large.");
    }

    private async Task<JsonObject> HandleAsync(JsonObject request, CancellationToken stop)
    {
        string op = request["op"]?.GetValue<string>() ?? "";
        if (op == "hello") return new JsonObject { ["version"] = Version, ["enabled"] = _enabled() };
        if (!_enabled()) throw new AgentException(Off);
        return op switch
        {
            "devices" => await DevicesAsync(_peer, stop),
            "send" => await SendAsync(_peer, request, stop),
            "status" => Status(request),
            "recent" => await RecentAsync(_peer, request, stop),
            "wait" => await WaitAsync(_peer, request, stop),
            _ => throw new AgentException($"Unknown request \"{op}\".")
        };
    }

    public static string Version => HopDropPeer.AppVersion;

    private static async Task<JsonObject> DevicesAsync(HopDropPeer peer, CancellationToken stop)
    {
        var devices = await peer.DevicesAsync(stop);
        // Ask who's around now, so "online" is current even when HopDrop has been idle in the background.
        try { await peer.QueryNearbyAsync(stop); await Task.Delay(800, stop); } catch (Exception e) when (e is not OperationCanceledException) { }
        var nearby = peer.Nearby;
        var list = new JsonArray();
        foreach (var d in devices.OrderBy(d => d.Alias ?? d.Name, StringComparer.OrdinalIgnoreCase))
            list.Add(new JsonObject
            {
                ["name"] = d.Alias ?? d.Name, ["id"] = d.Id, ["platform"] = d.Platform,
                ["online"] = nearby.Any(n => n.Id == d.Id), ["trusted"] = d.Trusted, ["lastSeen"] = d.LastSeen.ToString("o")
            });
        return new JsonObject { ["thisComputer"] = peer.Name, ["receiveFolder"] = peer.ReceiveDirectory, ["devices"] = list };
    }

    private async Task<JsonObject> SendAsync(HopDropPeer peer, JsonObject request, CancellationToken stop)
    {
        var device = await ResolveAsync(peer, request["device"]?.GetValue<string>(), stop);
        var paths = request["paths"]?.AsArray().Select(p => p?.GetValue<string>() ?? "").Where(p => p.Length > 0).ToList() ?? [];
        if (paths.Count == 0) throw new AgentException("Give at least one file or folder path.");
        foreach (string path in paths)
        {
            if (!Path.IsPathFullyQualified(path)) throw new AgentException($"Use a full path (like C:\\Users\\me\\report.pdf), not \"{path}\".");
            if (!File.Exists(path) && !Directory.Exists(path)) throw new AgentException($"Not found: {path}");
        }
        List<SendItem> items;
        try { items = SendItem.From(paths); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { throw new AgentException("HopDrop couldn't read a folder: " + e.Message); }
        if (items.Count == 0) throw new AgentException("Nothing to send: the folders are empty (hidden and system files are left out).");
        try { foreach (var item in items) _ = new FileInfo(item.Path).Length; }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException) { throw new AgentException("HopDrop couldn't read a file: " + e.Message); }
        var job = new AgentSend { Id = Guid.NewGuid(), Device = device, Files = items.Count };
        job.Task = Task.Run(async () =>
        {
            _currentSend.Value = job.Id;
            try { return await peer.SendAsync(device.Id, items); }
            finally { _currentSend.Value = null; _outgoing.TryRemove(job.Id, out _); }
        });
        _sends[job.Id] = job;
        int wait = Math.Clamp(request["wait"]?.GetValue<int>() ?? 50, 0, 600);
        try { await Task.WhenAny(job.Task, Task.Delay(TimeSpan.FromSeconds(wait), stop)); } catch (OperationCanceledException) { }
        return Describe(job);
    }

    private JsonObject Status(JsonObject request)
    {
        string? id = request["id"]?.GetValue<string>();
        if (id is null || !Guid.TryParse(id, out var key) || !_sends.TryGetValue(key, out var job)) throw new AgentException("No transfer with that id. It may be from before HopDrop restarted.");
        return Describe(job);
    }

    private JsonObject Describe(AgentSend job)
    {
        string name = job.Device.Alias ?? job.Device.Name;
        var reply = new JsonObject { ["transferId"] = job.Id.ToString(), ["device"] = name };
        if (!job.Task.IsCompleted)
        {
            reply["status"] = "sending";
            if (_outgoing.TryGetValue(job.Id, out var p))
            {
                if (p.WaitingForApproval) reply["note"] = $"Waiting for someone to accept on {name}.";
                else if (p.Reconnecting) reply["note"] = "The connection dropped; HopDrop is reconnecting and will continue where it stopped.";
                reply["percent"] = p.Percent; reply["bytesDone"] = p.BytesDone; reply["totalBytes"] = p.TotalBytes;
                if (p.Eta is { } eta) reply["secondsLeft"] = (int)eta.TotalSeconds;
            }
            else reply["note"] = $"Connecting to {name}…";
            reply["files"] = job.Files;
            return reply;
        }
        if (job.Task.IsCompletedSuccessfully && job.Task.Result.ErrorCode is null && job.Task.Result.SavedPaths.Count == job.Files)
        {
            var r = job.Task.Result;
            reply["status"] = "sent"; reply["files"] = r.SavedPaths.Count; reply["bytes"] = r.Bytes;
            reply["savedIn"] = $"{r.ReceiveFolderDisplayName} on {name}"; reply["seconds"] = Math.Round(r.Duration.TotalSeconds, 1);
            return reply;
        }
        var error = job.Task.Exception?.GetBaseException();
        reply["status"] = "failed";
        reply["error"] = error is null ? job.Task.IsCompletedSuccessfully ? "The receiver saved only part of the transfer." : "The transfer was cancelled."
            : Explain(error, name);
        return reply;
    }

    private static string Explain(Exception error, string name) => error switch
    {
        HopDropException h => h.Code switch
        {
            "declined" => $"{name} declined the files.",
            "disconnected" => $"{name} disconnected before the transfer finished.",
            "not_paired" => $"{name} is no longer paired. Pair it again in HopDrop.",
            "identity_mismatch" => $"{name}'s identity changed. Pair it again in HopDrop.",
            "no_space" => $"{name} does not have enough space.",
            "cancelled" => "The transfer was cancelled.",
            "timeout" => $"{name} stopped responding.",
            _ => $"Transfer failed ({h.Code})."
        },
        TimeoutException or System.Net.Sockets.SocketException => $"Cannot reach {name}. Open HopDrop there and check the network.",
        IOException or UnauthorizedAccessException => "HopDrop could not read or send a file. Check its permissions.",
        _ => "Transfer failed: " + error.Message
    };

    private static async Task<JsonObject> RecentAsync(HopDropPeer peer, JsonObject request, CancellationToken stop)
    {
        int limit = Math.Clamp(request["limit"]?.GetValue<int>() ?? 10, 1, 50);
        string direction = request["direction"]?.GetValue<string>() ?? "all";
        if (direction is not ("all" or "sent" or "received")) throw new AgentException("Direction must be sent, received, or all.");
        var devices = await peer.DevicesAsync(stop);
        var list = new JsonArray();
        foreach (var h in (await peer.HistoryAsync(stop)).Where(h => direction == "all" || h.Direction == direction).OrderByDescending(h => h.At).Take(limit))
        {
            var device = devices.FirstOrDefault(d => d.Id == h.PeerId);
            list.Add(new JsonObject
            {
                ["at"] = h.At.ToLocalTime().ToString("o"), ["direction"] = h.Direction,
                ["device"] = h.PeerId == "bluetooth" ? "Bluetooth" : device?.Alias ?? device?.Name ?? "a removed device",
                ["result"] = h.Result,
                ["fileCount"] = h.FileCount, ["files"] = new JsonArray(h.Files.Take(200).Select(f => (JsonNode?)f).ToArray()),
                ["savedIn"] = h.Folder, ["transport"] = h.Transport ?? TransferTransport.Unknown,
                ["text"] = h.Text
            });
        }
        return new JsonObject { ["receiveFolder"] = peer.ReceiveDirectory, ["transfers"] = list };
    }

    private static async Task<JsonObject> WaitAsync(HopDropPeer peer, JsonObject request, CancellationToken stop)
    {
        int timeout = Math.Clamp(request["timeout"]?.GetValue<int>() ?? 50, 1, 900);
        string? from = request["device"]?.GetValue<string>();
        string? fromId = string.IsNullOrWhiteSpace(from) ? null : (await ResolveAsync(peer, from, stop)).Id;
        var arrived = new TaskCompletionSource<TransferResult>(TaskCreationOptions.RunContinuationsAsynchronously);
        void Finished(TransferResult r) { if (r.Incoming && r.SavedPaths.Count > 0 && (fromId is null || r.PeerId == fromId)) arrived.TrySetResult(r); }
        peer.TransferFinished += Finished;
        try
        {
            try { await Task.WhenAny(arrived.Task, Task.Delay(TimeSpan.FromSeconds(timeout), stop)); } catch (OperationCanceledException) { }
            if (!arrived.Task.IsCompleted) return new JsonObject { ["status"] = "nothing yet", ["note"] = $"No files arrived in {timeout} s. Call again to keep waiting." };
            var r = arrived.Task.Result;
            return new JsonObject
            {
                ["status"] = r.ErrorCode is null ? "received" : "partly received", ["device"] = r.PeerName, ["fileCount"] = r.SavedPaths.Count, ["bytes"] = r.Bytes,
                ["files"] = new JsonArray(r.SavedPaths.Take(500).Select(f => (JsonNode?)f).ToArray()), ["receiveFolder"] = peer.ReceiveDirectory
            };
        }
        finally { peer.TransferFinished -= Finished; }
    }

    /// <summary>A paired device by id or name (exact, then partial); with no name, the only paired device.</summary>
    private static async Task<PairedDevice> ResolveAsync(HopDropPeer peer, string? wanted, CancellationToken stop)
    {
        var devices = await peer.DevicesAsync(stop);
        if (devices.Count == 0) throw new AgentException("No devices are paired yet. The user pairs a device once in HopDrop → Devices.");
        string Names() => string.Join(", ", devices.Select(d => d.Alias ?? d.Name));
        if (string.IsNullOrWhiteSpace(wanted))
            return devices.Count == 1 ? devices[0] : throw new AgentException($"Say which device: {Names()}.");
        wanted = wanted.Trim();
        var exact = devices.Where(d => d.Id.Equals(wanted, StringComparison.OrdinalIgnoreCase) || string.Equals(d.Alias ?? d.Name, wanted, StringComparison.OrdinalIgnoreCase)
            || d.Name.Equals(wanted, StringComparison.OrdinalIgnoreCase)).ToList();
        if (exact.Count == 1) return exact[0];
        var partial = devices.Where(d => (d.Alias ?? d.Name).Contains(wanted, StringComparison.OrdinalIgnoreCase) || d.Name.Contains(wanted, StringComparison.OrdinalIgnoreCase)).ToList();
        if (partial.Count == 1) return partial[0];
        throw new AgentException(partial.Count > 1 || exact.Count > 1 ? $"\"{wanted}\" matches several devices: {Names()}. Use the full name." : $"No paired device called \"{wanted}\". Paired: {Names()}.");
    }
}

internal sealed class AgentException(string message) : Exception(message);

/// <summary>The agent side: connects to the running HopDrop for a data folder (starting it in the tray if needed).</summary>
