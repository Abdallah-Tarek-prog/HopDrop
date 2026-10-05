using System.Collections.Concurrent;
using System.Diagnostics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text.Json;
using System.Threading.Channels;

namespace HopDrop.Core;

/// <summary>A live snapshot of one transfer. <see cref="TotalBytes"/> is -1 and <see cref="Eta"/> null when unknown.</summary>
public sealed record TransferProgress(Guid TransferId, string PeerId, string PeerName, bool Incoming, string File, int FileNumber, int FileCount, long BytesDone, long TotalBytes, double BytesPerSecond, TimeSpan? Eta)
{
    public int Percent => TotalBytes > 0 ? (int)Math.Min(100, BytesDone * 100 / TotalBytes) : -1;
    /// <summary>Sending: the receiver is asking its user whether to take the files.</summary>
    public bool WaitingForApproval { get; init; }
    /// <summary>The connection dropped; the sender is reconnecting (or, receiving, HopDrop waits for it to come back).</summary>
    public bool Reconnecting { get; init; }
}
/// <summary>How a transfer ended. <see cref="SavedPaths"/> holds the files that completed (full paths when receiving, saved names when sending).</summary>
public sealed record TransferResult(Guid TransferId, string PeerId, bool Incoming, IReadOnlyList<string> SavedPaths, string ReceiveFolderDisplayName, string? ErrorCode)
{
    public string PeerName { get; init; } = "";
    /// <summary>Received from a device the user trusts (files carry no "downloaded" mark).</summary>
    public bool Trusted { get; init; }
    public int Offered { get; init; }
    public long Bytes { get; init; }
    public TimeSpan Duration { get; init; }
}
public sealed class PairingRequest(string peerName, string code)
{
    private readonly TaskCompletionSource<bool> _answer = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public string PeerName { get; } = peerName;
    public string Code { get; } = code[..3] + " " + code[3..];
    public void Match() => _answer.TrySetResult(true);
    public void Cancel() => _answer.TrySetResult(false);
    internal Task<bool> Answer => _answer.Task;
}
/// <summary>Files offered by a device that isn't trusted while "Ask before receiving" is on. Call <see cref="Accept"/> or <see cref="Decline"/>.</summary>
public sealed class IncomingOffer
{
    private readonly TaskCompletionSource<bool> _answer = new(TaskCreationOptions.RunContinuationsAsynchronously);
    private readonly TaskCompletionSource _closed = new(TaskCreationOptions.RunContinuationsAsynchronously);
    internal IncomingOffer(Guid transferId, string peerId, string peerName, IReadOnlyList<string> files, long totalBytes, DateTimeOffset deadline)
    { TransferId = transferId; PeerId = peerId; PeerName = peerName; Files = files; TotalBytes = totalBytes; Deadline = deadline; }
    public Guid TransferId { get; }
    public string PeerId { get; }
    public string PeerName { get; }
    public IReadOnlyList<string> Files { get; }
    /// <summary>-1 when the sender doesn't know.</summary>
    public long TotalBytes { get; }
    /// <summary>After this the offer is declined automatically.</summary>
    public DateTimeOffset Deadline { get; }
    public void Accept() => _answer.TrySetResult(true);
    public void Decline() => _answer.TrySetResult(false);
    /// <summary>Completes once the request is over (answered, timed out, or the sender gave up), so a prompt can close.</summary>
    public Task Closed => _closed.Task;
    internal Task<bool> Answer => _answer.Task;
    internal void Close() { _answer.TrySetResult(false); _closed.TrySetResult(); }
}
public sealed record PeerOptions(HopDropPaths Paths, int Port = 7410, string? Name = null, Func<string, long>? FreeSpace = null)
{
    /// <summary>Mark files from devices that aren't trusted with Windows' "downloaded" mark (on by default on Windows).</summary>
    public bool MarkReceivedFiles { get; init; } = OperatingSystem.IsWindows();
    /// <summary>Tests only: wraps each outgoing connection's network stream (to simulate a dropped connection).</summary>
    internal Func<Stream, Stream>? ConnectionFilter { get; init; }
    /// <summary>Tests only: the protocol extensions this peer advertises (to act like an older version).</summary>
    internal IReadOnlyList<string>? Features { get; init; }
}

public sealed partial class HopDropPeer : IAsyncDisposable
{
    private readonly PeerOptions _options;
    private readonly JsonStore<AppSettings> _settings;
    private readonly JsonStore<List<PairedDevice>> _devices;
    private readonly JsonStore<List<HistoryEntry>> _history;
    private readonly OneTimeToken _token = new();
    private readonly SemaphoreSlim _sasGate = new(1, 1);
    private readonly SemaphoreSlim _namingGate = new(1, 1);
    private readonly Queue<DateTimeOffset> _sasAttempts = new();
    private readonly ConcurrentDictionary<Guid, CancellationTokenSource> _transfers = new();
    private TcpListener? _listener;
    private CancellationTokenSource? _serverStop;
    private Task? _acceptTask;
    /// <summary>Connections being handled; each removes itself when done so a long-running receiver doesn't keep them all.</summary>
    private readonly ConcurrentDictionary<Task, bool> _handlers = new();
    private int _connections;
    /// <summary>More simultaneous connections than this are dropped at once, so a flood from the network can't exhaust the receiver.</summary>
    private const int MaxConnections = 32;
    /// <summary>Protocol extensions this build understands; peers use one only when both list it in their hello (absent = baseline v2).</summary>
    internal static readonly string[] Features = ["consent", "folders", "pages", "resume"];
    public const string AppVersion = "1.0.0";
    private volatile bool _foreground, _pairingVisible;
    private DiscoveryService? _discovery;
    private AppSettings _current;
    public Identity Identity { get; }
    public string Id => Identity.Id;
    public int Port => ((IPEndPoint?)_listener?.LocalEndpoint)?.Port ?? _options.Port;
    public string Name => _current.Name;
    public string ReceiveDirectory => _current.ReceiveDirectory;
    public bool AskBeforeReceiving => _current.AskBeforeReceiving;
    /// <summary>"everyone" or "paired".</summary>
    public string Visibility => _current.Visibility;
    public event Action<TransferProgress>? Progress;
    public event Action<TransferResult>? TransferFinished;
    public event Action<PairingRequest>? IncomingPairing;
    public event Action<IReadOnlyList<PairedDevice>>? PairedDevicesChanged;
    public event Action<IReadOnlyList<NearbyDevice>>? NearbyDevicesChanged;
    public event Action<string>? Error;
    /// <summary>A device that isn't trusted wants to send files and "Ask before receiving" is on. Without a handler the files are accepted.</summary>
    public event Action<IncomingOffer>? OfferReceived;

    private HopDropPeer(PeerOptions options, AppSettings current, Identity identity)
    {
        _options = options;
        Identity = identity;
        _settings = new(options.Paths.SettingsPath, new AppSettings(options.Name ?? Environment.MachineName, options.Paths.DefaultReceiveDirectory));
        _devices = new(options.Paths.DevicesPath, []);
        _history = new(options.Paths.HistoryPath, []);
        _current = current;
    }
    public static async Task<HopDropPeer> OpenAsync(PeerOptions options, CancellationToken ct = default)
    {
        var settings = new JsonStore<AppSettings>(options.Paths.SettingsPath,
            new AppSettings(options.Name ?? Environment.MachineName, options.Paths.DefaultReceiveDirectory));
        var current = await settings.ReadAsync(ct);
        if (options.Name is not null) current = current with { Name = options.Name };
        if (options.Paths.ReceiveDirectory is not null) current = current with { ReceiveDirectory = options.Paths.DefaultReceiveDirectory };
        if (options.Name is not null || options.Paths.ReceiveDirectory is not null) current = await settings.UpdateAsync(_ => current, ct);
        var identity = await Task.Run(() => Identity.LoadOrCreate(options.Paths), ct);
        return new HopDropPeer(options, current, identity);
    }
    public async Task SetReceiveDirectoryAsync(string path, CancellationToken ct = default)
    { _current = await _settings.UpdateAsync(s => s with { ReceiveDirectory = Path.GetFullPath(path) }, ct); }
    public async Task SetAskBeforeReceivingAsync(bool ask, CancellationToken ct = default)
    { _current = await _settings.UpdateAsync(s => s with { AskBeforeReceiving = ask }, ct); }
    public async Task SetVisibilityAsync(string visibility, CancellationToken ct = default)
    {
        if (visibility is not ("everyone" or "paired")) throw new ArgumentException("Visibility must be everyone or paired", nameof(visibility));
        _current = await _settings.UpdateAsync(s => s with { Visibility = visibility }, ct);
        _discovery?.AnnounceSoon();
    }
    /// <summary>The UI is on screen: discovery announces every 5 s instead of every 15 s, and asks who is around.</summary>
    public void SetForeground(bool foreground)
    {
        if (_foreground == foreground) return;
        _foreground = foreground;
        if (foreground) _ = QueryNearbyAsync();
    }
    /// <summary>The pairing screen is open: a device hidden from unpaired devices shows its name until it closes.</summary>
    public void SetPairingVisible(bool visible)
    {
        if (_pairingVisible == visible) return;
        _pairingVisible = visible;
        _discovery?.AnnounceSoon();
    }
    private bool Hidden => _current.Visibility == "paired" && !_pairingVisible;
    public async Task SetNameAsync(string name, CancellationToken ct = default)
    { if (name.Length is < 1 or > 40) throw new ArgumentException("Name must be 1..40 characters"); _current = await _settings.UpdateAsync(s => s with { Name = name }, ct); }
    public Task<List<PairedDevice>> DevicesAsync(CancellationToken ct = default) => _devices.ReadAsync(ct);
    public async Task SetTrustedAsync(string id, bool trusted, CancellationToken ct = default)
    {
        var list = await _devices.UpdateAsync(old =>
        {
            int index = old.FindIndex(d => d.Id == id);
            if (index < 0) throw new ArgumentException("Device not paired", nameof(id));
            old[index] = old[index] with { Trusted = trusted };
            return old;
        }, ct);
        PairedDevicesChanged?.Invoke(list);
    }
    public async Task SetAliasAsync(string id, string? alias, CancellationToken ct = default)
    {
        if (alias is not null && alias.Length is < 1 or > 40) throw new ArgumentException("Alias must be 1..40 characters");
        var list = await _devices.UpdateAsync(old =>
        {
            int index = old.FindIndex(d => d.Id == id);
            if (index < 0) throw new ArgumentException("Device not paired", nameof(id));
            old[index] = old[index] with { Alias = alias };
            return old;
        }, ct);
        PairedDevicesChanged?.Invoke(list);
    }
    public Task<List<HistoryEntry>> HistoryAsync(CancellationToken ct = default) => _history.ReadAsync(ct);
    public Task ClearHistoryAsync(CancellationToken ct = default) => _history.UpdateAsync(_ => [], ct);
    /// <summary>Short display name of the receive folder, e.g. "Downloads\HopDrop".</summary>
    public string ReceiveFolderLabel => FolderLabel(ReceiveDirectory);
    /// <summary>Records files that arrived outside HopDrop's own protocol (Windows' Bluetooth wizard).</summary>
    public Task RecordBluetoothAsync(IReadOnlyList<string> files) => AddHistoryAsync("bluetooth", "received", null, files.ToList(), ReceiveFolderLabel);
    public IReadOnlyList<NearbyDevice> Nearby => _discovery?.Nearby ?? [];
    public Task QueryNearbyAsync(CancellationToken ct = default) => _discovery?.QueryAsync(ct) ?? Task.CompletedTask;
    public async Task StartAsync(CancellationToken ct = default)
    {
        if (_listener is not null) return;
        _serverStop = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _listener = new TcpListener(IPAddress.Any, _options.Port);
        _listener.Start();
        _acceptTask = AcceptLoopAsync(_serverStop.Token);
        try
        {
            _discovery = new DiscoveryService(Id, () => Hidden ? "" : Name, () => Port, () => _foreground);
            _discovery.NearbyChanged += items => NearbyDevicesChanged?.Invoke(items);
            _discovery.Error += message => Error?.Invoke(message);
            _discovery.Start();
        }
        catch (SocketException e) { Error?.Invoke($"Discovery unavailable: {e.Message}"); }
        await Task.CompletedTask;
    }
    public string CreateQrUri(IEnumerable<IPAddress>? addresses = null)
    {
        var list = (addresses ?? NetworkAddresses.QrAddresses()).ToArray();
        if (list.Length == 0) list = [IPAddress.Loopback];
        return new PairUri(Id, Name, "windows", Port, list, TokenCodec.Decode(_token.Issue())).ToString();
    }
    public void CloseQr() => _token.Invalidate();
    public void CancelTransfer(Guid id) { if (_transfers.TryGetValue(id, out var cts)) cts.Cancel(); }
    public async Task UnpairAsync(string id, CancellationToken ct = default)
    {
        var device = (await DevicesAsync(ct)).FirstOrDefault(d => d.Id == id);
        if (device is null) return;
        try { using var connection = await ConnectKnownAsync(device, ct); await Messages.WriteAsync(connection.Stream, new { type = "unpair" }, ct); using var reply = await ReadControlAsync(connection.Stream, ct); Messages.ThrowIfError(reply.RootElement); }
        catch (Exception e) when (e is SocketException or IOException or AuthenticationException or HopDropException or OperationCanceledException) { Error?.Invoke($"Unpair notification: {e.Message}"); }
        await RemoveDeviceAsync(id, ct);
    }
    private async Task SaveDeviceAsync(string id, string name, string platform, IPAddress address, CancellationToken ct, int port = 7410)
    {
        var list = await _devices.UpdateAsync(old =>
        {
            var existing = old.FirstOrDefault(d => d.Id == id);
            old.RemoveAll(d => d.Id == id);
            var addresses = existing?.LastAddresses ?? [];
            addresses.Remove(address.ToString()); addresses.Insert(0, address.ToString());
            old.Add(new PairedDevice(id, name, platform, existing?.PairedAt ?? DateTimeOffset.UtcNow, addresses, DateTimeOffset.UtcNow, existing?.Alias, port, existing?.Trusted ?? false));
            return old;
        }, ct);
        PairedDevicesChanged?.Invoke(list);
    }
    private async Task RemoveDeviceAsync(string id, CancellationToken ct)
    { var list = await _devices.UpdateAsync(old => { old.RemoveAll(d => d.Id == id); return old; }, ct); PairedDevicesChanged?.Invoke(list); }
    private async Task AcceptLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var client = await _listener!.AcceptTcpClientAsync(ct);
                if (!NetworkAddresses.IsPrivateOrLoopback(((IPEndPoint)client.Client.RemoteEndPoint!).Address)) { client.Dispose(); continue; }
                if (Interlocked.Increment(ref _connections) > MaxConnections) { Interlocked.Decrement(ref _connections); client.Dispose(); continue; }
                var handler = HandleClientAsync(client, ct);
                _handlers.TryAdd(handler, true);
                _ = handler.ContinueWith(done => { _handlers.TryRemove(done, out _); Interlocked.Decrement(ref _connections); }, TaskScheduler.Default);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (ObjectDisposedException) when (ct.IsCancellationRequested) { break; }
            catch (Exception e) { Error?.Invoke(e.Message); }
        }
    }
    private async Task HandleClientAsync(TcpClient client, CancellationToken stop)
    {
        using (client)
        using (var ssl = new SslStream(client.GetStream(), false, (_, _, _, _) => true))
        {
            try
            {
                using var handshake = CancellationTokenSource.CreateLinkedTokenSource(stop); handshake.CancelAfter(TimeSpan.FromSeconds(30));
                await ssl.AuthenticateAsServerAsync(new SslServerAuthenticationOptions
                { ServerCertificate = Identity.Certificate, ClientCertificateRequired = true, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13 }, handshake.Token);
                var cert = ssl.RemoteCertificate is null ? throw new HopDropException("identity_mismatch", "Missing client certificate") : new X509Certificate2(ssl.RemoteCertificate);
                using (cert)
                {
                    string peerId = Identity.Fingerprint(cert);
                    using var hello = await ReadControlAsync(ssl, stop);
                    var h = ValidateHello(hello.RootElement, peerId, _options.Features ?? Features);
                    var known = (await DevicesAsync(stop)).FirstOrDefault(d => d.Id == peerId);
                    bool paired = known is not null;
                    await SendHelloAsync(ssl, paired, stop);
                    using var request = await ReadControlAsync(ssl, stop);
                    string type = Messages.Type(request.RootElement);
                    if (!paired && type is "ping" or "offer" or "unpair") throw new HopDropException("not_paired", "Pair this device first");
                    var address = ((IPEndPoint)client.Client.RemoteEndPoint!).Address;
                    switch (type)
                    {
                        case "ping": await Messages.WriteAsync(ssl, new { type = "pong" }, stop); break;
                        case "unpair": await RemoveDeviceAsync(peerId, stop); await Messages.WriteAsync(ssl, new { type = "ok" }, stop); break;
                        case "pair_qr":
                            string outcome = _token.Consume(Messages.String(request.RootElement, "token"));
                            if (outcome != "pair_ok") throw new HopDropException(outcome, "QR code expired or invalid");
                            await SaveDeviceAsync(peerId, h.Name, h.Platform, address, stop);
                            await Messages.WriteAsync(ssl, new { type = "pair_ok" }, stop); break;
                        case "pair_sas": await RespondSasAsync(ssl, peerId, h.Name, h.Platform, address, stop); break;
                        case "offer":
                            await ReceiveAsync(ssl, request.RootElement, peerId, known?.Alias ?? h.Name, known?.Trusted == true, h.Features, stop); break;
                        default: throw new HopDropException("protocol_error", "Unknown request");
                    }
                }
            }
            catch (HopDropException e)
            { try { await Messages.WriteAsync(ssl, new { type = "error", code = e.Code, message = e.Message }, stop); } catch { } Error?.Invoke($"{e.Code}: {e.Message}"); }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { }
            catch (Exception e) when (e is not OperationCanceledException || !stop.IsCancellationRequested)
            {
                string code = e is JsonException ? "protocol_error" : e is TimeoutException ? "timeout" : "io_error";
                try { await Messages.WriteAsync(ssl, new { type = "error", code, message = e.Message }, stop); } catch { }
                Error?.Invoke(e.InnerException is null ? e.Message : $"{e.Message} {e.InnerException.Message}");
            }
        }
    }
    private static (string Name, string Platform, IReadOnlySet<string> Features) ValidateHello(JsonElement obj, string certId, IReadOnlyList<string> supported)
    {
        if (Messages.Type(obj) != "hello") throw new HopDropException("protocol_error", "Expected hello");
        if (Messages.Long(obj, "proto") != 2) throw new HopDropException("unsupported_version", "Protocol version 2 required");
        if (Messages.String(obj, "id") != certId) throw new HopDropException("identity_mismatch", "Hello id differs from certificate");
        string name = Messages.String(obj, "name"), platform = Messages.String(obj, "platform");
        if (name.Length is < 1 or > 40 || platform is not ("windows" or "android")) throw new HopDropException("protocol_error", "Invalid hello");
        var features = new HashSet<string>();
        if (obj.TryGetProperty("features", out var list) && list.ValueKind == JsonValueKind.Array)
            foreach (var item in list.EnumerateArray().Take(32))
                if (item.ValueKind == JsonValueKind.String && supported.Contains(item.GetString())) features.Add(item.GetString()!);
        return (name, platform, features);
    }
    private Task SendHelloAsync(Stream stream, bool paired, CancellationToken ct) => Messages.WriteAsync(stream,
        new { type = "hello", proto = 2, id = Id, name = Name, platform = "windows", app = AppVersion, paired, features = _options.Features ?? Features }, ct);
    private static async Task<JsonDocument> ReadControlAsync(Stream stream, CancellationToken ct, int seconds = 30)
    { using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(seconds)); try { return await Messages.ReadAsync(stream, timeout.Token); } catch (OperationCanceledException) when (!ct.IsCancellationRequested) { throw new HopDropException("timeout", "Peer timed out"); } }
    private sealed class Connection : IDisposable
    {
        private readonly TcpClient _client;
        public SslStream Stream { get; }
        public string Id { get; }
        public string Name { get; }
        public string Platform { get; }
        public IPAddress Address { get; }
        /// <summary>Extensions both sides support.</summary>
        public IReadOnlySet<string> Features { get; }
        public Connection(TcpClient client, SslStream stream, string id, string name, string platform, IPAddress address, IReadOnlySet<string> features)
        { _client = client; Stream = stream; Id = id; Name = name; Platform = platform; Address = address; Features = features; }
        public void Dispose() { Stream.Dispose(); _client.Dispose(); }
    }
    private async Task<Connection> ConnectAsync(IPAddress address, int port, string? expectedId, CancellationToken ct)
    {
        if (!NetworkAddresses.IsPrivateOrLoopback(address)) throw new HopDropException("protocol_error", "Address is not local");
        var client = new TcpClient(address.AddressFamily);
        try
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(5));
            await client.ConnectAsync(address, port, timeout.Token);
            var network = _options.ConnectionFilter?.Invoke(client.GetStream()) ?? client.GetStream();
            var ssl = new SslStream(network, false, (_, _, _, _) => true, (_, _, _, _, _) => Identity.Certificate);
            try
            {
                await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
                { TargetHost = "HopDrop", ClientCertificates = new X509CertificateCollection { Identity.Certificate }, EnabledSslProtocols = SslProtocols.Tls12 | SslProtocols.Tls13 }, timeout.Token);
                var cert = ssl.RemoteCertificate is null ? throw new HopDropException("identity_mismatch", "Missing server certificate") : new X509Certificate2(ssl.RemoteCertificate);
                using (cert)
                {
                    string id = Identity.Fingerprint(cert);
                    bool paired = (await DevicesAsync(ct)).Any(d => d.Id == id);
                    await SendHelloAsync(ssl, paired, ct);
                    using var doc = await ReadControlAsync(ssl, ct);
                    var h = ValidateHello(doc.RootElement, id, _options.Features ?? Features);
                    if (expectedId is not null && id != expectedId) throw new HopDropException("identity_mismatch", "This isn't the device you paired with");
                    return new(client, ssl, id, h.Name, h.Platform, address, h.Features);
                }
            }
            catch { ssl.Dispose(); throw; }
        }
        catch { client.Dispose(); throw; }
    }
    private async Task<Connection> ConnectAnyAsync(IEnumerable<(IPAddress Address, int Port)> endpoints, string expectedId, CancellationToken ct)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(ct);
        var pending = endpoints.Distinct().Select(endpoint => ConnectAsync(endpoint.Address, endpoint.Port, expectedId, linked.Token)).ToList();
        Exception? last = null;
        while (pending.Count > 0)
        {
            var task = await Task.WhenAny(pending); pending.Remove(task);
            try
            {
                var winner = await task; linked.Cancel();
                foreach (var other in pending) _ = other.ContinueWith(t => { if (t.Status == TaskStatus.RanToCompletion) t.Result.Dispose(); }, TaskScheduler.Default);
                return winner;
            }
            catch (Exception e) { last = e; }
        }
        throw last ?? new HopDropException("io_error", "No reachable address");
    }
    private async Task<Connection> ConnectKnownAsync(PairedDevice device, CancellationToken ct)
    {
        var nearby = Nearby.FirstOrDefault(n => n.Id == device.Id);
        IEnumerable<(IPAddress Address, int Port)> discovered = nearby is null ? [] : [(nearby.Address, nearby.Port)];
        var endpoints = discovered.Concat(device.LastAddresses.Select(address => (IPAddress.Parse(address), device.Port)));
        var connection = await ConnectAnyAsync(endpoints, device.Id, ct);
        try { await SaveDeviceAsync(connection.Id, connection.Name, connection.Platform, connection.Address, ct, nearby?.Address.Equals(connection.Address) == true ? nearby.Port : device.Port); }
        catch { connection.Dispose(); throw; }
        return connection;
    }
    public async Task PairQrAsync(string uri, CancellationToken ct = default)
    {
        var qr = PairUri.Parse(uri);
        using var connection = await ConnectAnyAsync(qr.Addresses.Select(address => (address, qr.Port)), qr.Id, ct);
        await Messages.WriteAsync(connection.Stream, new { type = "pair_qr", token = TokenCodec.Encode(qr.Token) }, ct);
        using var doc = await ReadControlAsync(connection.Stream, ct); Messages.ThrowIfError(doc.RootElement);
        if (Messages.Type(doc.RootElement) != "pair_ok") throw new HopDropException("protocol_error", "Expected pair_ok");
        await SaveDeviceAsync(connection.Id, connection.Name, connection.Platform, connection.Address, ct, qr.Port);
    }
    private async Task<bool> ConfirmSasAsync(string name, string code, CancellationToken ct)
    {
        var request = new PairingRequest(name, code); IncomingPairing?.Invoke(request);
        try { return await request.Answer.WaitAsync(TimeSpan.FromSeconds(120), ct); }
        catch (TimeoutException) { return false; }
    }
    public async Task<string> PairSasAsync(IPAddress address, int port, CancellationToken ct = default)
    {
        using var connection = await ConnectAsync(address, port, null, ct);
        var stream = connection.Stream;
        await Messages.WriteAsync(stream, new { type = "pair_sas" }, ct);
        using var commitDoc = await ReadControlAsync(stream, ct); Messages.ThrowIfError(commitDoc.RootElement);
        if (Messages.Type(commitDoc.RootElement) != "sas_commit") throw new HopDropException("protocol_error", "Expected commitment");
        string commit = Messages.String(commitDoc.RootElement, "c");
        byte[] nI = RandomNumberGenerator.GetBytes(32);
        await Messages.WriteAsync(stream, new { type = "sas_nonce", n = Convert.ToHexString(nI).ToLowerInvariant() }, ct);
        using var revealDoc = await ReadControlAsync(stream, ct); Messages.ThrowIfError(revealDoc.RootElement);
        if (Messages.Type(revealDoc.RootElement) != "sas_reveal") throw new HopDropException("protocol_error", "Expected reveal");
        byte[] nR = ReadHex(Messages.String(revealDoc.RootElement, "n"));
        if (nR.Length != 32 || !Sas.CommitMatches(commit, Convert.FromHexString(connection.Id), Convert.FromHexString(Id), nR)) throw new HopDropException("protocol_error", "SAS commitment mismatch");
        string code = Sas.Code(Convert.FromHexString(Id), Convert.FromHexString(connection.Id), nI, nR);
        Task<JsonDocument> peerConfirm = ReadControlAsync(stream, ct, 130);
        bool local = await ConfirmSasAsync(connection.Name, code, ct);
        await Messages.WriteAsync(stream, new { type = "sas_confirm", ok = local }, ct);
        using var confirmDoc = await peerConfirm; Messages.ThrowIfError(confirmDoc.RootElement);
        if (Messages.Type(confirmDoc.RootElement) != "sas_confirm" || !Messages.Bool(confirmDoc.RootElement, "ok") || !local) throw new HopDropException("user_declined", "Pairing cancelled");
        await SaveDeviceAsync(connection.Id, connection.Name, connection.Platform, address, ct, port);
        return code;
    }
    private static byte[] ReadHex(string text) { try { return Convert.FromHexString(text); } catch (FormatException) { throw new HopDropException("protocol_error", "Invalid hex"); } }
    private async Task RespondSasAsync(Stream stream, string peerId, string peerName, string platform, IPAddress address, CancellationToken ct)
    {
        lock (_sasAttempts)
        {
            while (_sasAttempts.Count > 0 && _sasAttempts.Peek() < DateTimeOffset.UtcNow.AddMinutes(-10)) _sasAttempts.Dequeue();
            if (_sasAttempts.Count >= 5) throw new HopDropException("busy", "Too many pairing attempts");
            _sasAttempts.Enqueue(DateTimeOffset.UtcNow);
        }
        if (!await _sasGate.WaitAsync(0, ct)) throw new HopDropException("busy", "Pairing already in progress");
        try
        {
            byte[] nR = RandomNumberGenerator.GetBytes(32);
            await Messages.WriteAsync(stream, new { type = "sas_commit", c = Sas.Commit(Convert.FromHexString(Id), Convert.FromHexString(peerId), nR) }, ct);
            using var nonceDoc = await ReadControlAsync(stream, ct);
            if (Messages.Type(nonceDoc.RootElement) != "sas_nonce") throw new HopDropException("protocol_error", "Expected nonce");
            byte[] nI = ReadHex(Messages.String(nonceDoc.RootElement, "n"));
            if (nI.Length != 32) throw new HopDropException("protocol_error", "Invalid nonce");
            await Messages.WriteAsync(stream, new { type = "sas_reveal", n = Convert.ToHexString(nR).ToLowerInvariant() }, ct);
            string code = Sas.Code(Convert.FromHexString(peerId), Convert.FromHexString(Id), nI, nR);
            Task<JsonDocument> peerConfirm = ReadControlAsync(stream, ct, 130);
            bool local = await ConfirmSasAsync(peerName, code, ct);
            await Messages.WriteAsync(stream, new { type = "sas_confirm", ok = local }, ct);
            using var peerDoc = await peerConfirm;
            if (Messages.Type(peerDoc.RootElement) != "sas_confirm" || !Messages.Bool(peerDoc.RootElement, "ok") || !local) throw new HopDropException("user_declined", "Pairing cancelled");
            await SaveDeviceAsync(peerId, peerName, platform, address, ct);
        }
        finally { _sasGate.Release(); }
    }
    public async ValueTask DisposeAsync()
    {
        _serverStop?.Cancel(); _listener?.Stop();
        if (_acceptTask is not null) { try { await _acceptTask; } catch (OperationCanceledException) { } }
        foreach (var c in _transfers.Values) c.Cancel();
        await Task.WhenAll(_handlers.Keys.ToArray());
        if (_discovery is not null) await _discovery.DisposeAsync();
        _serverStop?.Dispose(); _sasGate.Dispose(); _namingGate.Dispose(); Identity.Dispose();
    }
}
