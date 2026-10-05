using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text.Json;

namespace HopDrop.Core;

public static class NetworkAddresses
{
    public sealed record LocalAddress(IPAddress Address, string Kind);
    private sealed record Adapter(string Name, string Description, NetworkInterfaceType Type, bool Virtual, bool Gateway, IReadOnlyList<(IPAddress Address, IPAddress? Mask)> Ipv4);
    private static Adapter[]? _adapters;
    private static long _readAt;
    static NetworkAddresses()
    {
        NetworkChange.NetworkAddressChanged += (_, _) => _adapters = null;
        NetworkChange.NetworkAvailabilityChanged += (_, _) => _adapters = null;
    }
    public static bool IsPrivateOrLoopback(IPAddress address)
    {
        if (IPAddress.IsLoopback(address)) return true;
        byte[] b = address.GetAddressBytes();
        return b.Length == 4 && (b[0] == 10 || b[0] == 172 && b[1] is >= 16 and <= 31 || b[0] == 192 && b[1] == 168 || b[0] == 169 && b[1] == 254);
    }
    /// <summary>
    /// The PC's usable adapters, read once and reused until Windows reports a network change (or two minutes pass):
    /// listing adapters is the expensive part of discovery, which needs them every few seconds.
    /// </summary>
    private static Adapter[] Adapters
    {
        get
        {
            var cached = _adapters;
            if (cached is not null && Environment.TickCount64 - Interlocked.Read(ref _readAt) < 120_000) return cached;
            var fresh = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up && n.NetworkInterfaceType is not (NetworkInterfaceType.Loopback or NetworkInterfaceType.Tunnel or NetworkInterfaceType.Ppp or NetworkInterfaceType.Wwanpp or NetworkInterfaceType.Wwanpp2))
                .Select(n =>
                {
                    var properties = n.GetIPProperties();
                    var ipv4 = properties.UnicastAddresses
                        .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address) && IsPrivateOrLoopback(a.Address))
                        .Select(a => (a.Address, (IPAddress?)a.IPv4Mask)).ToList();
                    return new Adapter(n.Name, n.Description, n.NetworkInterfaceType, IsVirtual(n.Name, n.Description),
                        properties.GatewayAddresses.Any(g => g.Address.AddressFamily == AddressFamily.InterNetwork), ipv4);
                }).ToArray();
            Interlocked.Exchange(ref _readAt, Environment.TickCount64);
            _adapters = fresh;
            return fresh;
        }
    }
    /// <summary>Hyper-V, WSL, Docker and VM adapters: reachable only from this PC, so never shown and put last in QR codes.</summary>
    public static bool IsVirtual(NetworkInterface n) => IsVirtual(n.Name, n.Description);
    private static bool IsVirtual(string name, string description) =>
        System.Text.RegularExpressions.Regex.IsMatch(name + " " + description, "Hyper-V|vEthernet|VMware|VirtualBox|WSL|Docker|Npcap", System.Text.RegularExpressions.RegexOptions.IgnoreCase);
    /// <summary>All private IPv4 addresses, best first: real adapters before virtual ones, networks with a gateway first, then Wi-Fi/Ethernet.</summary>
    public static IEnumerable<IPAddress> PrivateIpv4() => Adapters
        .OrderBy(a => a.Virtual)
        .ThenByDescending(a => a.Gateway)
        .ThenBy(a => a.Type is NetworkInterfaceType.Wireless80211 or NetworkInterfaceType.Ethernet ? 0 : 1)
        .SelectMany(a => a.Ipv4.Select(x => x.Address));
    /// <summary>Addresses other devices can use, labelled for people: Wi-Fi, Ethernet, Mobile hotspot, USB tethering.</summary>
    public static IReadOnlyList<LocalAddress> Describe() => Adapters.Where(a => !a.Virtual)
        .OrderByDescending(a => a.Gateway)
        .SelectMany(a => a.Ipv4.Select(x => new LocalAddress(x.Address, Kind(a, x.Address)))).ToList();
    private static string Kind(Adapter n, IPAddress a)
    {
        string text = n.Name + " " + n.Description;
        if (text.Contains("Wi-Fi Direct", StringComparison.OrdinalIgnoreCase) || a.ToString().StartsWith("192.168.137.")) return "Mobile hotspot";
        if (text.Contains("Remote NDIS", StringComparison.OrdinalIgnoreCase)) return "USB tethering";
        if (n.Type == NetworkInterfaceType.Wireless80211) return "Wi-Fi";
        if (text.Contains("Bluetooth", StringComparison.OrdinalIgnoreCase)) return "Bluetooth network";
        return n.Type == NetworkInterfaceType.Ethernet ? "Ethernet" : "Local network";
    }
    /// <summary>Addresses for a pairing QR code: real adapters only, unless the PC has nothing else.</summary>
    public static IEnumerable<IPAddress> QrAddresses()
    {
        var real = Describe().Select(a => a.Address).ToList();
        return real.Count > 0 ? real : PrivateIpv4();
    }
    public static IEnumerable<IPAddress> DirectedBroadcasts() => Adapters
        .SelectMany(a => a.Ipv4)
        .Where(x => x.Mask is not null)
        .Select(x => new IPAddress(x.Address.GetAddressBytes().Zip(x.Mask!.GetAddressBytes(), (b, m) => (byte)(b | ~m)).ToArray()));
}

public sealed record DiscoveryMessage(int Hopdrop, string Type, string Id, string Name, string Platform, int Port, bool Receive)
{
    public static DiscoveryMessage Parse(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length > 1200) throw new HopDropException("protocol_error", "Discovery message too large");
        try
        {
            using var doc = JsonDocument.Parse(bytes.ToArray()); var o = doc.RootElement;
            var message = new DiscoveryMessage(checked((int)Messages.Long(o, "hopdrop")), Messages.Type(o), Messages.String(o, "id"),
                Messages.String(o, "name"), Messages.String(o, "pl"), checked((int)Messages.Long(o, "p")), Messages.Bool(o, "rx"));
            if (message.Hopdrop != 2 || message.Type is not ("announce" or "query") || !Identity.IsId(message.Id)
                || message.Name.Length > 40 || message.Platform is not ("windows" or "android") || message.Port is < 1 or > 65535)
                throw new HopDropException("protocol_error", "Invalid discovery message");
            return message;
        }
        catch (JsonException e) { throw new HopDropException("protocol_error", e.Message); }
    }
    public byte[] Encode() => JsonSerializer.SerializeToUtf8Bytes(new { hopdrop = Hopdrop, type = Type, id = Id, name = Name, pl = Platform, p = Port, rx = Receive });
}
/// <param name="Name">Empty when the device hides its name from devices it isn't paired with.</param>
public sealed record NearbyDevice(string Id, string Name, string Platform, int Port, IPAddress Address, DateTimeOffset LastSeen, bool Receive);
public sealed class NearbyTable(TimeProvider? clock = null)
{
    /// <summary>A device that hasn't announced itself for this long is no longer shown as nearby (three missed 15 s announcements).</summary>
    public static readonly TimeSpan Expiry = TimeSpan.FromSeconds(45);
    private readonly TimeProvider _clock = clock ?? TimeProvider.System;
    private readonly Dictionary<string, NearbyDevice> _items = [];
    public IReadOnlyList<NearbyDevice> Items { get { lock (_items) return _items.Values.ToArray(); } }
    public bool Update(DiscoveryMessage message, IPAddress address, string ownId)
    {
        if (message.Id == ownId || !NetworkAddresses.IsPrivateOrLoopback(address)) return false;
        lock (_items)
        {
            bool first = !_items.TryGetValue(message.Id, out var old);
            bool changed = first || old!.Name != message.Name || old.Platform != message.Platform || old.Port != message.Port ||
                !old.Address.Equals(address) || old.Receive != message.Receive;
            _items[message.Id] = new(message.Id, message.Name, message.Platform, message.Port, address, _clock.GetUtcNow(), message.Receive);
            return changed;
        }
    }
    public bool Expire()
    {
        lock (_items)
        {
            bool changed = false;
            foreach (string id in _items.Where(item => _clock.GetUtcNow() - item.Value.LastSeen >= Expiry).Select(item => item.Key).ToArray())
                changed |= _items.Remove(id);
            return changed;
        }
    }
}

/// <param name="name">This device's name, or "" to stay hidden from devices it isn't paired with.</param>
/// <param name="foreground">True while HopDrop is on screen: announce every 5 s; otherwise every 15 s.</param>
public sealed class DiscoveryService(string id, Func<string> name, Func<int> port, Func<bool>? foreground = null) : IAsyncDisposable
{
    private readonly SemaphoreSlim _wake = new(0, 1);
    private static readonly IPAddress Multicast = IPAddress.Parse("239.255.74.10");
    private readonly NearbyTable _table = new();
    private UdpClient? _udp;
    private CancellationTokenSource? _stop;
    private Task? _receive;
    private Task? _announce;
    private readonly SemaphoreSlim _sendGate = new(1, 1);
    public IReadOnlyList<NearbyDevice> Nearby => _table.Items;
    public event Action<IReadOnlyList<NearbyDevice>>? NearbyChanged;
    public event Action<string>? Error;
    public void Start()
    {
        if (_udp is not null) return;
        var udp = new UdpClient(AddressFamily.InterNetwork);
        try
        {
            udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            udp.Client.Bind(new IPEndPoint(IPAddress.Any, 7410));
            udp.EnableBroadcast = true;
            udp.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastTimeToLive, 1);
            foreach (var address in NetworkAddresses.PrivateIpv4())
            { try { udp.JoinMulticastGroup(Multicast, address); } catch (SocketException) { } }
        }
        catch { udp.Dispose(); throw; }
        _udp = udp; _stop = new();
        NetworkChange.NetworkAddressChanged += OnNetworkChanged;
        _receive = ReceiveLoopAsync(_stop.Token);
        _announce = AnnounceLoopAsync(_stop.Token);
    }
    private async void OnNetworkChanged(object? sender, EventArgs args)
    {
        if (_udp is null || _stop is null || _stop.IsCancellationRequested) return;
        try
        {
            foreach (var address in NetworkAddresses.PrivateIpv4())
            { try { _udp.JoinMulticastGroup(Multicast, address); } catch (SocketException) { } }
            await BroadcastAsync("announce", _stop.Token);
        }
        catch (Exception e) when (e is not OperationCanceledException) { Error?.Invoke(e.Message); }
    }
    public Task QueryAsync(CancellationToken ct = default) => BroadcastAsync("query", ct);
    /// <summary>Announces now instead of waiting for the next round (after a name or visibility change).</summary>
    public void AnnounceSoon() { if (_wake.CurrentCount == 0) try { _wake.Release(); } catch (SemaphoreFullException) { } }
    private async Task AnnounceLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try { await BroadcastAsync("announce", ct); if (_table.Expire()) NearbyChanged?.Invoke(_table.Items); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception e) { Error?.Invoke(e.Message); }
            try { await _wake.WaitAsync(foreground?.Invoke() != false ? TimeSpan.FromSeconds(5) : TimeSpan.FromSeconds(15), ct); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
        }
    }
    private async Task BroadcastAsync(string type, CancellationToken ct)
    {
        byte[] bytes = new DiscoveryMessage(2, type, id, name(), "windows", port(), true).Encode();
        await _sendGate.WaitAsync(ct);
        try
        {
            foreach (var address in NetworkAddresses.PrivateIpv4())
            {
                try
                {
                    _udp!.Client.SetSocketOption(SocketOptionLevel.IP, SocketOptionName.MulticastInterface, address.GetAddressBytes());
                    await _udp.SendAsync(bytes, new IPEndPoint(Multicast, 7410), ct);
                }
                catch (SocketException e) { Error?.Invoke(e.Message); }
            }
            foreach (var target in new[] { IPAddress.Broadcast }.Concat(NetworkAddresses.DirectedBroadcasts()).Distinct())
            { try { await _udp!.SendAsync(bytes, new IPEndPoint(target, 7410), ct); } catch (SocketException e) { Error?.Invoke(e.Message); } }
        }
        finally { _sendGate.Release(); }
    }
    private async Task ReceiveLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var packet = await _udp!.ReceiveAsync(ct);
                var message = DiscoveryMessage.Parse(packet.Buffer);
                bool first = !_table.Items.Any(item => item.Id == message.Id);
                bool changed = _table.Update(message, packet.RemoteEndPoint.Address, id);
                if (message.Id == id) continue;
                if (changed) NearbyChanged?.Invoke(_table.Items);
                if (message.Type == "query" || first)
                {
                    byte[] reply = new DiscoveryMessage(2, "announce", id, name(), "windows", port(), true).Encode();
                    await _udp.SendAsync(reply, packet.RemoteEndPoint, ct);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (ObjectDisposedException) when (ct.IsCancellationRequested) { break; }
            catch (HopDropException) { }
            catch (Exception e) { Error?.Invoke(e.Message); }
        }
    }
    public async ValueTask DisposeAsync()
    {
        _stop?.Cancel(); _udp?.Dispose();
        NetworkChange.NetworkAddressChanged -= OnNetworkChanged;
        if (_receive is not null) await _receive;
        if (_announce is not null) await _announce;
        _stop?.Dispose();
        _sendGate.Dispose();
        _wake.Dispose();
    }
}
