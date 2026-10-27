using System.Diagnostics;
using System.Net;
using System.Net.NetworkInformation;
using HopDrop.Core;
using Windows.Devices.WiFiDirect;
using Windows.Security.Credentials;

namespace HopDrop.Desktop;

internal enum DirectLinkState { Off, Waiting, Starting, On, Unavailable }

/// <summary>The private Wi-Fi network is owned by App, so it stays up in the tray and hidden receiver.</summary>
internal sealed class DirectLink : IDisposable
{
    private readonly HopDropPeer _peer;
    private readonly System.Windows.Threading.Dispatcher _dispatcher = App.Instance.Dispatcher;
    private WiFiDirectAdvertisementPublisher? _publisher;
    private bool _pairedPhone, _pairing, _disposed;
    private int _generation, _pollGeneration;
    private HashSet<string> _upBefore = [];
    private string _ssid = "", _key = "";
    private static string CannotCreate => L.T("ThisPCSWiFiCanTCreateADirectLink");
    private System.Windows.Threading.DispatcherTimer? _retry;
    private int _failures;
    public DirectLinkState State { get; private set; } = DirectLinkState.Waiting;
    public string Reason { get; private set; } = "";
    public string LastError { get; private set; } = "";
    public string? Address { get; private set; }
    public string Ssid => _ssid;
    public event Action? Changed;

    public DirectLink(HopDropPeer peer)
    {
        _peer = peer;
        State = Preferences.DirectLink ? DirectLinkState.Waiting : DirectLinkState.Off;
        try
        {
            NetworkChange.NetworkAvailabilityChanged += NetworkChanged;
            NetworkChange.NetworkAddressChanged += NetworkAddressChanged;
        }
        catch (Exception ex) { Debug.WriteLine(ex); }
    }
    public void SetPairedPhones(IReadOnlyList<PairedDevice> devices)
    {
        bool paired = devices.Any(d => d.Platform.Equals("android", StringComparison.OrdinalIgnoreCase));
        if (_pairedPhone == paired) return;
        _pairedPhone = paired;
        Refresh();
    }
    public void SetPairingVisible(bool visible) { if (_pairing == visible) return; _pairing = visible; Refresh(); }
    public void Refresh()
    {
        if (_disposed) return;
        try
        {
            bool wanted = Preferences.DirectLink && (_pairedPhone || _pairing);
            if (!wanted)
            {
                var next = Preferences.DirectLink ? DirectLinkState.Waiting : DirectLinkState.Off;
                if (State == next && _publisher is null && _peer.DirectLink is null) return;
                Stop(); _failures = 0; Set(next); return;
            }
            if (State is DirectLinkState.On or DirectLinkState.Starting) return;
            _retry?.Stop(); Start();
        }
        catch (Exception ex) { Fail(ex); }
    }
    private void Start()
    {
        ++_generation;
        try
        {
            Set(DirectLinkState.Starting);
            _ssid = Preferences.DirectSsid; _key = Preferences.DirectKey;
            _upBefore = DirectAddresses().Select(a => a.Adapter + ":" + a.Address).ToHashSet();
            var publisher = new WiFiDirectAdvertisementPublisher();
            publisher.Advertisement.IsAutonomousGroupOwnerEnabled = true;
            publisher.Advertisement.LegacySettings.IsEnabled = true;
            publisher.Advertisement.LegacySettings.Ssid = _ssid;
            publisher.Advertisement.LegacySettings.Passphrase = new PasswordCredential { Password = _key };
            publisher.StatusChanged += StatusChanged;
            _publisher = publisher;
            publisher.Start();
        }
        catch (Exception ex) { Fail(ex); }
    }
    private async Task FindAddressAsync(int generation)
    {
        for (int i = 0; i < 20 && generation == _generation && !_disposed; i++)
        {
            try
            {
                var address = DirectAddresses().FirstOrDefault(a => !_upBefore.Contains(a.Adapter + ":" + a.Address)).Address;
                if (address is not null)
                {
                    if (generation != _generation || _disposed) return;
                    Address = address.ToString();
                    _peer.DirectLink = new DirectLinkInfo(_ssid, _key, Address);
                    Set(DirectLinkState.On);
                    return;
                }
            }
            catch (Exception ex) { Debug.WriteLine(ex); }
            await Task.Delay(500);
        }
        if (generation == _generation && !_disposed) Fail(CannotCreate);
    }
    private static IEnumerable<(string Adapter, IPAddress Address)> DirectAddresses() => NetworkInterface.GetAllNetworkInterfaces()
        .Where(n => n.OperationalStatus == OperationalStatus.Up &&
            (n.Description.Contains(L.T("WiFiDirect"), StringComparison.OrdinalIgnoreCase) || n.Name.Contains(L.T("WiFiDirect"), StringComparison.OrdinalIgnoreCase)))
        .SelectMany(n => n.GetIPProperties().UnicastAddresses.Select(a => (Adapter: n.Id, a.Address)))
        .Where(a => a.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address) && NetworkAddresses.IsPrivateOrLoopback(a.Address));
    private void StatusChanged(WiFiDirectAdvertisementPublisher sender, WiFiDirectAdvertisementPublisherStatusChangedEventArgs args)
    {
        Post(() =>
        {
            if (_disposed || sender != _publisher) return;
            if (args.Status == WiFiDirectAdvertisementPublisherStatus.Started && _pollGeneration != _generation)
            { _pollGeneration = _generation; _ = FindAddressAsync(_generation); }
            else if (args.Status == WiFiDirectAdvertisementPublisherStatus.Aborted)
            {
                LastError = args.Error.ToString();
                // Windows' Mobile hotspot takes Wi-Fi Direct for itself; phones can join that hotspot instead.
                Fail(args.Error switch
                {
                    WiFiDirectError.RadioNotAvailable => L.T("WiFiIsOff"),
                    WiFiDirectError.ResourceInUse => L.T("MobileHotspotOrAnotherAppIsUsingThisPCSWiFiDirect"),
                    _ => CannotCreate,
                });
            }
            else if (args.Status == WiFiDirectAdvertisementPublisherStatus.Stopped) Fail(CannotCreate);
        });
    }
    private void Fail(Exception ex)
    {
        Debug.WriteLine(ex);
        LastError = ex.Message; Fail(CannotCreate);
    }
    private void Fail(string reason)
    {
        Stop(); Reason = reason; Set(DirectLinkState.Unavailable);
        _retry ??= new System.Windows.Threading.DispatcherTimer();
        _retry.Interval = TimeSpan.FromSeconds(Math.Min(600, 30 * (1 << Math.Min(_failures++, 5))));
        _retry.Tick -= Retry;
        _retry.Tick += Retry;
        _retry.Start();
    }
    private void Retry(object? sender, EventArgs e) { _retry?.Stop(); Refresh(); }
    private void Stop()
    {
        ++_generation;
        _retry?.Stop();
        _peer.DirectLink = null; Address = null;
        if (_publisher is null) return;
        var publisher = _publisher; _publisher = null;
        try { publisher.StatusChanged -= StatusChanged; publisher.Stop(); } catch (Exception ex) { Debug.WriteLine(ex); }
    }
    private void Set(DirectLinkState state)
    {
        State = state;
        try { Changed?.Invoke(); } catch (Exception ex) { Debug.WriteLine(ex); }
    }
    private void Post(Action action)
    {
        if (_disposed || _dispatcher.HasShutdownStarted) return;
        try { _ = _dispatcher.InvokeAsync(() => { if (!_disposed) try { action(); } catch (Exception ex) { Fail(ex); } }); }
        catch (Exception ex) { Debug.WriteLine(ex); }
    }
    private void NetworkChanged(object? sender, NetworkAvailabilityEventArgs e) => Post(RetryNow);
    private void NetworkAddressChanged(object? sender, EventArgs e) => Post(RetryNow);
    private void RetryNow()
    {
        _failures = 0;
        if (State == DirectLinkState.On && !DirectAddresses().Any(a => a.Address.ToString() == Address)) { Fail(CannotCreate); return; }
        if (State == DirectLinkState.Unavailable) { _retry?.Stop(); Refresh(); }
    }
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { NetworkChange.NetworkAvailabilityChanged -= NetworkChanged; NetworkChange.NetworkAddressChanged -= NetworkAddressChanged; }
        catch (Exception ex) { Debug.WriteLine(ex); }
        Stop();
    }
}
