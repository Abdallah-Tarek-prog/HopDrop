using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using HopDrop.Core;
using Xunit;

namespace HopDrop.Tests;

public class PeerTests
{
    [Fact]
    public async Task OpenLoadsSettingsAndCanRenamePairedDevice()
    {
        string root = Scratch();
        await using (var first = await Peer(root))
        {
            await first.SetNameAsync("Saved name");
            await first.SetReceiveDirectoryAsync(Path.Combine(root, "chosen"));
        }
        await using var second = await HopDropPeer.OpenAsync(new PeerOptions(new HopDropPaths(Path.Combine(root, "data"), Path.Combine(root, "logs")), 0));
        Assert.Equal("Saved name", second.Name);
        Assert.Equal(Path.Combine(root, "chosen"), second.ReceiveDirectory);
        await Assert.ThrowsAsync<ArgumentException>(() => second.SetAliasAsync(new string('a', 64), "Alias"));
        string id = new('a', 64);
        var store = new JsonStore<List<PairedDevice>>(Path.Combine(root, "data", "devices.json"), []);
        await store.UpdateAsync(_ => [new PairedDevice(id, "Phone", "android", DateTimeOffset.UtcNow, [], DateTimeOffset.UtcNow)]);
        await second.SetAliasAsync(id, "My phone");
        Assert.Equal("My phone", Assert.Single(await second.DevicesAsync()).Alias);
    }
    private static string Scratch()
    {
        string root = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory, "../../../../.test-scratch"));
        string path = Path.Combine(root, Guid.NewGuid().ToString("N")); Directory.CreateDirectory(path); return path;
    }
    private static Task<HopDropPeer> Peer(string root, Func<string, long>? free = null) => HopDropPeer.OpenAsync(new PeerOptions(
        new HopDropPaths(Path.Combine(root, "data"), Path.Combine(root, "logs"), Path.Combine(root, "receive")), 0, "Test peer", free));
    private static async Task PairAsync(HopDropPeer shower, HopDropPeer scanner)
    { await shower.StartAsync(); await scanner.PairQrAsync(shower.CreateQrUri([IPAddress.Loopback])); }
    private static async Task<(TcpClient Client, SslStream Stream)> RawAsync(HopDropPeer server, Identity clientIdentity, string? helloId = null)
    {
        var client = new TcpClient(); await client.ConnectAsync(IPAddress.Loopback, server.Port);
        var stream = new SslStream(client.GetStream(), false, (_, _, _, _) => true, (_, _, _, _, _) => clientIdentity.Certificate);
        await stream.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
        { TargetHost = "HopDrop", ClientCertificates = new X509CertificateCollection { clientIdentity.Certificate }, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13 });
        await Messages.WriteAsync(stream, new { type = "hello", proto = 2, id = helloId ?? clientIdentity.Id, name = "Raw", platform = "windows", app = "2.0.0", paired = false });
        return (client, stream);
    }
    [Fact]
    public async Task UnpairedPingIsRejected()
    {
        await using var server = await Peer(Scratch()); await server.StartAsync();
        using var identity = Identity.LoadOrCreate(new HopDropPaths(Path.Combine(Scratch(), "data"), Scratch()));
        var (client, stream) = await RawAsync(server, identity);
        using (client) using (stream)
        {
            using var hello = await Messages.ReadAsync(stream); Assert.Equal("hello", Messages.Type(hello.RootElement));
            await Messages.WriteAsync(stream, new { type = "ping" });
            using var error = await Messages.ReadAsync(stream);
            Assert.Equal("not_paired", Messages.String(error.RootElement, "code"));
        }
    }
    [Fact]
    public async Task HelloIdentityMismatchIsRejected()
    {
        await using var server = await Peer(Scratch()); await server.StartAsync();
        using var identity = Identity.LoadOrCreate(new HopDropPaths(Path.Combine(Scratch(), "data"), Scratch()));
        var (client, stream) = await RawAsync(server, identity, new string('0', 64));
        using (client) using (stream)
        {
            using var error = await Messages.ReadAsync(stream);
            Assert.Equal("identity_mismatch", Messages.String(error.RootElement, "code"));
        }
    }
    [Fact]
    public async Task QrPinningRefusesDifferentCertificate()
    {
        await using var expected = await Peer(Scratch()); await expected.StartAsync();
        await using var impostor = await Peer(Scratch()); await impostor.StartAsync();
        await using var scanner = await Peer(Scratch());
        var qr = PairUri.Parse(expected.CreateQrUri([IPAddress.Loopback])) with { Port = impostor.Port };
        var error = await Assert.ThrowsAsync<HopDropException>(() => scanner.PairQrAsync(qr.ToString()));
        Assert.Equal("identity_mismatch", error.Code);
    }
    [Fact]
    public async Task SasPairsWithSameCodeOnBothEnds()
    {
        await using var a = await Peer(Scratch()); await using var b = await Peer(Scratch());
        string? responderCode = null;
        a.IncomingPairing += request => { responderCode = request.Code; request.Match(); };
        b.IncomingPairing += request => request.Match();
        await a.StartAsync();
        string initiatorCode = await b.PairSasAsync(IPAddress.Loopback, a.Port);
        Assert.Equal(responderCode, initiatorCode[..3] + " " + initiatorCode[3..]);
        Assert.Single(await a.DevicesAsync()); Assert.Single(await b.DevicesAsync());
    }
    [Fact]
    public async Task TransferSeveralFilesAndRememberPairing()
    {
        string aRoot = Scratch(), bRoot = Scratch();
        await using var a = await Peer(aRoot); await using var b = await Peer(bRoot);
        await PairAsync(a, b); await b.StartAsync();
        string input = Path.Combine(bRoot, "input"); Directory.CreateDirectory(input);
        var paths = new List<string>();
        foreach (string name in new[] { "same.txt", "ØªÙ‚Ø±ÙŠØ± ðŸ“.txt", "empty.bin", "random.bin" })
        {
            string path = Path.Combine(input, name); paths.Add(path);
            if (name == "random.bin")
            {
                await using var output = File.Create(path);
                byte[] block = new byte[1024 * 1024];
                for (int i = 0; i < 50; i++) { RandomNumberGenerator.Fill(block); await output.WriteAsync(block); }
            }
            else await File.WriteAllTextAsync(path, name == "empty.bin" ? "" : name);
        }
        var sendPaths = new[] { paths[0], paths[0], paths[1], paths[2], paths[3] };
        var sent = await b.SendAsync($"127.0.0.1:{a.Port}", sendPaths);
        Assert.Equal(5, sent.SavedPaths.Count);
        Assert.Equal(sendPaths, Assert.Single(await b.HistoryAsync()).Files);
        string[] expected = { "same.txt", "same (1).txt", "ØªÙ‚Ø±ÙŠØ± ðŸ“.txt", "empty.bin", "random.bin" };
        foreach (string name in expected) Assert.True(File.Exists(Path.Combine(a.ReceiveDirectory, name)));
        for (int i = 0; i < expected.Length; i++) Assert.Equal(SHA256.HashData(File.ReadAllBytes(sendPaths[i])), SHA256.HashData(File.ReadAllBytes(Path.Combine(a.ReceiveDirectory, expected[i]))));
        var reverse = await a.SendAsync($"127.0.0.1:{b.Port}", [paths[2], paths[1]]);
        Assert.Equal(2, reverse.SavedPaths.Count);
        await using var restarted = await Peer(bRoot);
        Assert.Single(await restarted.DevicesAsync());
        var again = await restarted.SendAsync($"127.0.0.1:{a.Port}", [paths[2]]);
        Assert.Single(again.SavedPaths);
    }
    [Theory]
    [InlineData(false, "checksum_mismatch")]
    [InlineData(true, "cancelled")]
    public async Task BadChecksumAndCancelDeletePartial(bool cancel, string expected)
    {
        await using var receiver = await Peer(Scratch()); await using var sender = await Peer(Scratch());
        await PairAsync(receiver, sender);
        var (client, stream) = await RawAsync(receiver, sender.Identity);
        using (client) using (stream)
        {
            using var hello = await Messages.ReadAsync(stream);
            var files = cancel ? new[] { new { i = 0, name = "complete.bin", size = 512 }, new { i = 1, name = "partial.bin", size = 1024 } }
                : [new { i = 0, name = "partial.bin", size = 1024 }];
            await Messages.WriteAsync(stream, new { type = "offer", transferId = Guid.NewGuid(), count = files.Length, totalBytes = cancel ? 1536 : 1024,
                files });
            using var accept = await Messages.ReadAsync(stream); Assert.Equal("accept", Messages.Type(accept.RootElement));
            if (cancel)
            {
                await Messages.WriteAsync(stream, new { type = "file", i = 0 });
                await Framing.WriteAsync(stream, 2, new byte[512]);
                await Messages.WriteAsync(stream, new { type = "file_end", i = 0, sha256 = Convert.ToHexString(SHA256.HashData(new byte[512])).ToLowerInvariant() });
                using var completed = await Messages.ReadAsync(stream); Assert.Equal("file_ok", Messages.Type(completed.RootElement));
            }
            await Messages.WriteAsync(stream, new { type = "file", i = cancel ? 1 : 0 });
            await Framing.WriteAsync(stream, 2, new byte[512]);
            if (cancel) await Messages.WriteAsync(stream, new { type = "cancel" });
            else
            {
                await Framing.WriteAsync(stream, 2, new byte[512]);
                await Messages.WriteAsync(stream, new { type = "file_end", i = 0, sha256 = new string('0', 64) });
            }
            using var error = await Messages.ReadAsync(stream); Assert.Equal(expected, Messages.String(error.RootElement, "code"));
        }
        Assert.Equal(cancel ? new[] { "complete.bin" } : [], Directory.GetFiles(receiver.ReceiveDirectory).Select(Path.GetFileName).ToArray());
    }
    [Fact]
    public async Task UppercaseChecksumFromWireIsAccepted()
    {
        await using var receiver = await Peer(Scratch()); await using var sender = await Peer(Scratch());
        await PairAsync(receiver, sender);
        var (client, stream) = await RawAsync(receiver, sender.Identity);
        using (client) using (stream)
        {
            using var hello = await Messages.ReadAsync(stream);
            byte[] data = [1, 2, 3, 4];
            await Messages.WriteAsync(stream, new { type = "offer", transferId = Guid.NewGuid(), count = 1, totalBytes = 4,
                files = new[] { new { i = 0, name = "upper.bin", size = 4 } } });
            using var accept = await Messages.ReadAsync(stream); Assert.Equal("accept", Messages.Type(accept.RootElement));
            await Messages.WriteAsync(stream, new { type = "file", i = 0 });
            await Framing.WriteAsync(stream, 2, data);
            await Messages.WriteAsync(stream, new { type = "file_end", i = 0, sha256 = Convert.ToHexString(SHA256.HashData(data)) });
            using var fileOk = await Messages.ReadAsync(stream); Assert.Equal("file_ok", Messages.Type(fileOk.RootElement));
            await Messages.WriteAsync(stream, new { type = "done" });
            using var done = await Messages.ReadAsync(stream); Assert.Equal("done_ok", Messages.Type(done.RootElement));
            Assert.Equal(data, await File.ReadAllBytesAsync(Path.Combine(receiver.ReceiveDirectory, "upper.bin")));
        }
    }
    [Fact]
    public async Task NoSpaceCanBeInjected()
    {
        await using var receiver = await Peer(Scratch(), _ => 0); await using var sender = await Peer(Scratch());
        await PairAsync(receiver, sender);
        string path = Path.Combine(Scratch(), "tiny.bin"); await File.WriteAllBytesAsync(path, [1]);
        var error = await Assert.ThrowsAsync<HopDropException>(() => sender.SendAsync($"127.0.0.1:{receiver.Port}", [path]));
        Assert.Equal("no_space", error.Code);
    }
    [Fact]
    public async Task FilesFromUntrustedDevicesAreMarkedAndTrustedOnesAreNot()
    {
        string aRoot = Scratch(), bRoot = Scratch();
        await using var a = await Peer(aRoot); await using var b = await Peer(bRoot);
        await PairAsync(a, b);
        Assert.True(ZoneMark.Supported(a.ReceiveDirectory) != false, "test folder must be on NTFS");
        string input = Path.Combine(bRoot, "setup.exe"); await File.WriteAllTextAsync(input, "pretend program");
        var first = await b.SendAsync($"127.0.0.1:{a.Port}", [input]);
        Assert.True(ZoneMark.Has(Path.Combine(a.ReceiveDirectory, "setup.exe")));
        Assert.False((await a.HistoryAsync()).Count == 0);
        await a.SetTrustedAsync(b.Id, true);
        Assert.True(Assert.Single(await a.DevicesAsync()).Trusted);
        bool trustedResult = false; a.TransferFinished += r => trustedResult = r.Trusted;
        await b.SendAsync($"127.0.0.1:{a.Port}", [input]);
        Assert.False(ZoneMark.Has(Path.Combine(a.ReceiveDirectory, "setup (1).exe")));
        Assert.True(trustedResult);
        await b.PairQrAsync((await Task.FromResult(a.CreateQrUri([IPAddress.Loopback]))));
        Assert.True(Assert.Single(await a.DevicesAsync()).Trusted);
    }
    [Fact]
    public async Task AskBeforeReceivingDeclinesAcceptsAndSkipsTrustedDevices()
    {
        string aRoot = Scratch(), bRoot = Scratch();
        await using var a = await Peer(aRoot); await using var b = await Peer(bRoot);
        await PairAsync(a, b);
        await a.SetAskBeforeReceivingAsync(true);
        bool accept = false; IncomingOffer? seen = null;
        a.OfferReceived += offer => { seen = offer; if (accept) offer.Accept(); else offer.Decline(); };
        bool waited = false; b.Progress += p => waited |= p.WaitingForApproval;
        string input = Path.Combine(bRoot, "note.txt"); await File.WriteAllTextAsync(input, "hello");
        var declined = await Assert.ThrowsAsync<HopDropException>(() => b.SendAsync($"127.0.0.1:{a.Port}", [input]));
        Assert.Equal("declined", declined.Code);
        Assert.True(waited);
        Assert.NotNull(seen);
        Assert.Equal(["note.txt"], seen!.Files);
        Assert.Equal(5, seen.TotalBytes);
        await seen.Closed.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.False(File.Exists(Path.Combine(a.ReceiveDirectory, "note.txt")));
        accept = true;
        Assert.Single((await b.SendAsync($"127.0.0.1:{a.Port}", [input])).SavedPaths);
        Assert.True(File.Exists(Path.Combine(a.ReceiveDirectory, "note.txt")));
        seen = null;
        await a.SetTrustedAsync(b.Id, true);
        Assert.Single((await b.SendAsync($"127.0.0.1:{a.Port}", [input])).SavedPaths);
        Assert.Null(seen);
    }
    [Fact]
    public async Task SenderCancellingWhileReceiverAsksClosesThePrompt()
    {
        string aRoot = Scratch(), bRoot = Scratch();
        await using var a = await Peer(aRoot); await using var b = await Peer(bRoot);
        await PairAsync(a, b);
        await a.SetAskBeforeReceivingAsync(true);
        var asked = new TaskCompletionSource<IncomingOffer>(TaskCreationOptions.RunContinuationsAsynchronously);
        a.OfferReceived += offer => asked.TrySetResult(offer);
        string input = Path.Combine(bRoot, "note.txt"); await File.WriteAllTextAsync(input, "hello");
        using var stop = new CancellationTokenSource();
        var send = b.SendAsync($"127.0.0.1:{a.Port}", [input], stop.Token);
        var offer = await asked.Task.WaitAsync(TimeSpan.FromSeconds(10));
        stop.Cancel();
        var error = await Assert.ThrowsAsync<HopDropException>(() => send);
        Assert.Equal("cancelled", error.Code);
        await offer.Closed.WaitAsync(TimeSpan.FromSeconds(10));
        Assert.False(File.Exists(Path.Combine(a.ReceiveDirectory, "note.txt")));
    }
    [Fact]
    public async Task FoldersArriveWithTheirStructureAndDontMerge()
    {
        string aRoot = Scratch(), bRoot = Scratch();
        await using var a = await Peer(aRoot); await using var b = await Peer(bRoot);
        await PairAsync(a, b);
        string photos = Path.Combine(bRoot, "Photos"); Directory.CreateDirectory(Path.Combine(photos, "2024"));
        File.WriteAllText(Path.Combine(photos, "cover.jpg"), "cover"); File.WriteAllText(Path.Combine(photos, "2024", "beach.jpg"), "beach");
        File.WriteAllText(Path.Combine(bRoot, "loose.txt"), "loose");
        SendItem[] items = [new(Path.Combine(photos, "cover.jpg"), "Photos"), new(Path.Combine(photos, "2024", "beach.jpg"), "Photos/2024"), new(Path.Combine(bRoot, "loose.txt"))];
        var first = await b.SendAsync($"127.0.0.1:{a.Port}", items);
        Assert.Equal(["Photos/cover.jpg", "Photos/2024/beach.jpg", "loose.txt"], first.SavedPaths);
        Assert.Equal("beach", File.ReadAllText(Path.Combine(a.ReceiveDirectory, "Photos", "2024", "beach.jpg")));
        await b.SendAsync($"127.0.0.1:{a.Port}", items);
        Assert.Equal("beach", File.ReadAllText(Path.Combine(a.ReceiveDirectory, "Photos (1)", "2024", "beach.jpg")));
        Assert.True(File.Exists(Path.Combine(a.ReceiveDirectory, "loose (1).txt")));
    }
    [Fact]
    public async Task ReceivedFoldersStayInsideTheReceiveFolder()
    {
        await using var receiver = await Peer(Scratch()); await using var sender = await Peer(Scratch());
        await PairAsync(receiver, sender);
        var (client, stream) = await RawAsync(receiver, sender.Identity);
        using (client) using (stream)
        {
            using var hello = await Messages.ReadAsync(stream);
            byte[] data = [7];
            await Messages.WriteAsync(stream, new { type = "offer", transferId = Guid.NewGuid(), count = 1, totalBytes = 1,
                files = new[] { new { i = 0, name = "evil.txt", size = 1, path = "../../C:/Windows/..\\escape" } } });
            using var accept = await Messages.ReadAsync(stream); Assert.Equal("accept", Messages.Type(accept.RootElement));
            await Messages.WriteAsync(stream, new { type = "file", i = 0 });
            await Framing.WriteAsync(stream, 2, data);
            await Messages.WriteAsync(stream, new { type = "file_end", i = 0, sha256 = Convert.ToHexString(SHA256.HashData(data)) });
            using var fileOk = await Messages.ReadAsync(stream);
            string savedAs = Messages.String(fileOk.RootElement, "savedAs");
            Assert.DoesNotContain("..", savedAs);
            string full = Path.GetFullPath(Path.Combine(receiver.ReceiveDirectory, savedAs));
            Assert.StartsWith(Path.GetFullPath(receiver.ReceiveDirectory) + Path.DirectorySeparatorChar, full);
            Assert.True(File.Exists(full));
        }
    }
    [Fact]
    public async Task LargeSelectionsGoInOneTransferAndOlderReceiversGetSeveral()
    {
        string aRoot = Scratch(), bRoot = Scratch(), cRoot = Scratch();
        await using var a = await Peer(aRoot); await using var b = await Peer(bRoot);
        await using var c = await HopDropPeer.OpenAsync(new PeerOptions(new HopDropPaths(Path.Combine(cRoot, "data"), Path.Combine(cRoot, "logs"), Path.Combine(cRoot, "receive")), 0, "Older peer") { Features = [] });
        await PairAsync(a, b); await PairAsync(c, b);
        string dir = Path.Combine(bRoot, "many"); Directory.CreateDirectory(dir);
        var paths = Enumerable.Range(0, 1500).Select(i =>
        {
            string path = Path.Combine(dir, $"a-rather-long-file-name-that-fills-the-offer-quickly-{i:0000}.txt");
            File.WriteAllText(path, i.ToString()); return path;
        }).ToList();
        int transfers = 0; a.TransferFinished += _ => Interlocked.Increment(ref transfers);
        var result = await b.SendAsync($"127.0.0.1:{a.Port}", paths);
        Assert.Equal(1500, result.SavedPaths.Count);
        Assert.Equal(1, transfers);
        int older = 0; c.TransferFinished += _ => Interlocked.Increment(ref older);
        var split = await b.SendAsync($"127.0.0.1:{c.Port}", paths);
        Assert.Equal(1500, split.SavedPaths.Count);
        Assert.True(older > 1, $"expected several transfers, got {older}");
        Assert.Equal(1500, Directory.GetFiles(c.ReceiveDirectory).Length);
    }
    [Fact]
    public async Task DroppedConnectionResumesWithoutResendingOrDuplicating()
    {
        string aRoot = Scratch(), bRoot = Scratch();
        long sent = 0; int connections = 0;
        Stream Filter(Stream network) => new TestStream(network, Interlocked.Increment(ref connections) == 2 ? 6_000_000 : long.MaxValue, count => Interlocked.Add(ref sent, count));
        await using var a = await Peer(aRoot);
        await using var b = await HopDropPeer.OpenAsync(new PeerOptions(new HopDropPaths(Path.Combine(bRoot, "data"), Path.Combine(bRoot, "logs"), Path.Combine(bRoot, "receive")), 0, "Sender") { ConnectionFilter = Filter });
        await PairAsync(a, b);
        Interlocked.Exchange(ref sent, 0);
        string big = Path.Combine(bRoot, "big.bin"); byte[] data = RandomNumberGenerator.GetBytes(16 * 1024 * 1024); File.WriteAllBytes(big, data);
        string small = Path.Combine(bRoot, "small.txt"); File.WriteAllText(small, "same name twice");
        bool reconnected = false; b.Progress += p => reconnected |= p.Reconnecting;
        var finished = new List<TransferResult>(); a.TransferFinished += r => { lock (finished) finished.Add(r); };
        var result = await b.SendAsync($"127.0.0.1:{a.Port}", [small, big, small]);
        Assert.True(reconnected);
        Assert.Equal(3, result.SavedPaths.Count);
        Assert.Equal(data, File.ReadAllBytes(Path.Combine(a.ReceiveDirectory, "big.bin")));
        Assert.Equal(["big.bin", "small (1).txt", "small.txt"], Directory.GetFiles(a.ReceiveDirectory).Select(f => Path.GetFileName(f)!).Order().ToArray());
        Assert.True(sent < data.Length * 13L / 10, $"sent {sent} bytes for {data.Length}: the file was resent instead of resumed");
        var outcome = Assert.Single(finished);
        Assert.Null(outcome.ErrorCode);
        Assert.Equal(3, outcome.SavedPaths.Count);
    }
    /// <summary>Counts bytes written and, past a limit, drops the connection like a lost Wi-Fi link.</summary>
    private sealed class TestStream(Stream inner, long dropAfter, Action<long> written) : Stream
    {
        private long _written;
        public override bool CanRead => inner.CanRead; public override bool CanSeek => false; public override bool CanWrite => inner.CanWrite;
        public override long Length => throw new NotSupportedException(); public override long Position { get => throw new NotSupportedException(); set => throw new NotSupportedException(); }
        public override void Flush() => inner.Flush();
        public override Task FlushAsync(CancellationToken ct) => inner.FlushAsync(ct);
        public override int Read(byte[] buffer, int offset, int count) => inner.Read(buffer, offset, count);
        public override ValueTask<int> ReadAsync(Memory<byte> buffer, CancellationToken ct = default) => inner.ReadAsync(buffer, ct);
        public override long Seek(long offset, SeekOrigin origin) => throw new NotSupportedException();
        public override void SetLength(long value) => throw new NotSupportedException();
        public override void Write(byte[] buffer, int offset, int count) => WriteAsync(buffer.AsMemory(offset, count)).AsTask().GetAwaiter().GetResult();
        public override async ValueTask WriteAsync(ReadOnlyMemory<byte> buffer, CancellationToken ct = default)
        {
            if (_written + buffer.Length > dropAfter) { inner.Dispose(); throw new IOException("Connection dropped", new SocketException(10054)); }
            await inner.WriteAsync(buffer, ct);
            _written += buffer.Length; written(buffer.Length);
        }
        protected override void Dispose(bool disposing) { if (disposing) inner.Dispose(); base.Dispose(disposing); }
    }
    [Fact]
    public async Task ActivityKeepsTheNewest500Entries()
    {
        string root = Scratch();
        await using var peer = await Peer(root);
        var store = new JsonStore<List<HistoryEntry>>(Path.Combine(root, "data", "history.json"), []);
        await store.UpdateAsync(_ => Enumerable.Range(0, 600).Select(i => new HistoryEntry(DateTimeOffset.UtcNow, "bluetooth", "received", "ok", [$"old{i}.txt"], null)).ToList());
        await peer.RecordBluetoothAsync(["new.txt"]);
        var history = await peer.HistoryAsync();
        Assert.Equal(500, history.Count);
        Assert.Equal("old101.txt", history[0].Files[0]);
        Assert.Equal("new.txt", history[^1].Files[0]);
    }
}
