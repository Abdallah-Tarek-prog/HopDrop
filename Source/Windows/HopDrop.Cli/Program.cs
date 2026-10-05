using System.Net;
using HopDrop.Core;

var arguments = args.ToList();
string? Take(string option)
{
    int index = arguments.IndexOf(option);
    if (index < 0) return null;
    if (index + 1 >= arguments.Count) throw new ArgumentException($"Missing value for {option}");
    string value = arguments[index + 1]; arguments.RemoveRange(index, 2); return value;
}
bool Flag(string option) { bool found = arguments.Remove(option); return found; }
try
{
    string? data = Take("--data"), portText = Take("--port"), name = Take("--name"), receive = Take("--receive-dir");
    bool autoSas = Flag("--auto-confirm-sas") || Flag("--auto-confirm");
    bool showQr = Flag("--show-qr");
    string? qrAddress = Take("--qr-address");
    string? ask = Take("--ask");
    // Testing aid: the first connection of a send drops after this many bytes, like a lost Wi-Fi link.
    long dropAfter = Take("--drop-after") is string drop ? long.Parse(drop) : -1;
    if (ask is not (null or "accept" or "decline")) throw new ArgumentException("--ask takes accept or decline");
    int port = portText is null ? 7410 : int.Parse(portText);
    if (port is < 0 or > 65535) throw new ArgumentException("Invalid port");
    if (arguments.Count == 0) throw new ArgumentException("Expected command");
    string command = arguments[0]; arguments.RemoveAt(0);
    var defaults = HopDropPaths.Default;
    var paths = new HopDropPaths(data ?? defaults.DataDirectory, data is null ? defaults.LogDirectory : Path.Combine(data, "logs"), receive);
    int connections = 0;
    await using var peer = await HopDropPeer.OpenAsync(new PeerOptions(paths, port, name)
    {
        ConnectionFilter = dropAfter < 0 ? null : network => Interlocked.Increment(ref connections) == 1 ? new DroppingStream(network, dropAfter) : network
    });
    peer.IncomingPairing += request =>
    {
        Console.WriteLine($"Pairing code with {request.PeerName}: {request.Code}");
        if (autoSas) request.Match();
        else _ = Task.Run(() => { Console.Write("Match? [y/N] "); if (Console.ReadLine()?.Trim().Equals("y", StringComparison.OrdinalIgnoreCase) == true) request.Match(); else request.Cancel(); });
    };
    if (ask is not null)
    {
        await peer.SetAskBeforeReceivingAsync(true);
        peer.OfferReceived += offer =>
        {
            Console.WriteLine($"{offer.PeerName} offers {offer.Files.Count} file(s); answering {ask}");
            if (ask == "accept") offer.Accept(); else offer.Decline();
        };
    }
    peer.PairedDevicesChanged += devices => Console.WriteLine($"Paired devices: {string.Join(", ", devices.Select(d => d.Name + " (" + d.Id[..8] + ")"))}");
    peer.TransferFinished += result => Console.WriteLine(result.ErrorCode is null
        ? $"{(result.Incoming ? "Received" : "Sent")} {result.SavedPaths.Count} {(result.SavedPaths.Count == 1 ? "file" : "files")}; folder {result.ReceiveFolderDisplayName}; paths {string.Join(", ", result.SavedPaths)}"
        : $"Transfer failed: {result.ErrorCode}");
    peer.Error += message => Console.Error.WriteLine($"Error: {message}");
    switch (command)
    {
        case "serve":
        case "qr":
            await peer.StartAsync();
            Console.WriteLine($"Listening on 0.0.0.0:{peer.Port} as {peer.Name} ({peer.Id})");
            var addresses = qrAddress is null ? null : new[] { IPAddress.Parse(qrAddress) };
            if (command == "qr" || showQr)
                Console.WriteLine($"Pairing URI: {peer.CreateQrUri(addresses)}");
            using (var stop = new CancellationTokenSource())
            {
                Console.CancelKeyPress += (_, e) => { e.Cancel = true; stop.Cancel(); };
                Task refresh = command == "qr" || showQr ? Task.Run(async () =>
                {
                    while (!stop.IsCancellationRequested)
                    {
                        try { await Task.Delay(TimeSpan.FromMinutes(5), stop.Token); }
                        catch (OperationCanceledException) { break; }
                        Console.WriteLine($"Pairing URI: {peer.CreateQrUri(addresses)}");
                    }
                }) : Task.CompletedTask;
                try { await Task.Delay(Timeout.InfiniteTimeSpan, stop.Token); } catch (OperationCanceledException) { }
                await refresh;
                peer.CloseQr();
            }
            break;
        case "pair-qr":
            if (arguments.Count != 1) throw new ArgumentException("pair-qr <uri>");
            await peer.PairQrAsync(arguments[0]); Console.WriteLine("Paired by QR"); break;
        case "pair-sas":
            if (arguments.Count != 1) throw new ArgumentException("pair-sas <ip[:port]>");
            var sasAddress = Parse(arguments[0], 7410);
            string code = await peer.PairSasAsync(sasAddress.Address, sasAddress.Port);
            Console.WriteLine($"Paired by number match: {code[..3]} {code[3..]}"); break;
        case "send":
            if (arguments.Count < 2) throw new ArgumentException("send <deviceId|ip[:port]> <files...>");
            var sent = await peer.SendAsync(arguments[0], SendItem.From(arguments.Skip(1)));
            Console.WriteLine($"Saved in {sent.ReceiveFolderDisplayName}: {string.Join(", ", sent.SavedPaths)}"); break;
        case "devices":
            foreach (var device in await peer.DevicesAsync()) Console.WriteLine($"{device.Id} {device.Name} {device.Platform} {string.Join(',', device.LastAddresses)}:{device.Port}");
            break;
        case "nearby":
            await peer.StartAsync(); await peer.QueryNearbyAsync(); await Task.Delay(1500);
            foreach (var device in peer.Nearby) Console.WriteLine($"{device.Id} {device.Name} {device.Address}:{device.Port}");
            break;
        case "unpair":
            if (arguments.Count != 1) throw new ArgumentException("unpair <deviceId>");
            await peer.UnpairAsync(arguments[0]); Console.WriteLine("Unpaired"); break;
        default: throw new ArgumentException($"Unknown command: {command}");
    }
    return 0;
}
catch (Exception e)
{
    Console.Error.WriteLine(e is HopDropException ae ? $"{ae.Code}: {ae.Message}" : e.InnerException is null ? e.Message : $"{e.Message} {e.InnerException.Message}");
    return 1;
}
static (IPAddress Address, int Port) Parse(string value, int defaultPort)
{
    var parts = value.Split(':', 2);
    if (!IPAddress.TryParse(parts[0], out var address) || address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork) throw new ArgumentException("Expected IPv4[:port]");
    int port = parts.Length == 2 ? int.Parse(parts[1]) : defaultPort;
    if (port is < 1 or > 65535) throw new ArgumentException("Invalid port");
    return (address, port);
}

/// <summary>Testing aid for --drop-after: writes stop after a limit, as if the Wi-Fi link went away.</summary>
sealed class DroppingStream(Stream inner, long limit) : Stream
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
        if (_written + buffer.Length > limit) { inner.Dispose(); throw new IOException("Connection dropped", new System.Net.Sockets.SocketException(10054)); }
        await inner.WriteAsync(buffer, ct); _written += buffer.Length;
    }
    protected override void Dispose(bool disposing) { if (disposing) inner.Dispose(); base.Dispose(disposing); }
}
