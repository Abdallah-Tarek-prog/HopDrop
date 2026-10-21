using System.IO.Pipes;
using System.Security.Cryptography;
using System.Text;
using System.Windows;
using HopDrop.Core;
using Microsoft.Toolkit.Uwp.Notifications;

namespace HopDrop.Desktop;

public partial class App : System.Windows.Application
{
    private Mutex? _mutex;
    private CancellationTokenSource? _pipeStop;
    private HopDropPeer? _peer;
    private string? _pendingToast;
    private bool _e2eMode;
    internal SendQueue Queue { get; private set; } = null!;
    internal DirectLink Link { get; private set; } = null!;
    private ClipboardShare? _clipboardShare;
    internal static MainWindow? WindowInstance { get; private set; }
    internal static HopDropPeer Peer => ((App)Current)._peer!;
    internal static bool SnapshotMode { get; private set; }
    /// <summary>--render-scale 1.25 draws the --render-pages pictures as on a 125 % display (sharp, for Store screenshots).</summary>
    internal static double RenderScale { get; private set; } = 1;
    internal static string DataDirectory { get; private set; } = "";

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            var args = Program.Arguments.ToList();
            string? render = Take(args, "--render-pages");
            SnapshotMode = render is not null;
            if (Take(args, "--render-scale") is { } scale) RenderScale = double.Parse(scale, System.Globalization.CultureInfo.InvariantCulture);
            string? data = Take(args, "--data");
            DataDirectory = Path.GetFullPath(data ?? HopDropPaths.Default.DataDirectory);
            // Store version started at sign-in: the tray, or the hidden receiver when only "Receive in the background" is on.
            int signin = args.IndexOf("--signin");
            if (signin >= 0) args[signin] = Preferences.BackgroundReceive && !Preferences.StartWithWindows ? "--background" : "--minimized";
            var forwarded = Program.Arguments.Select(a => a == "--signin" ? args[signin] : a).ToArray();
            string? portText = Take(args, "--port");
            string? receive = Take(args, "--receive-dir");
            bool minimized = args.Remove("--minimized");
            bool background = args.Remove("--background");
            string? e2eReady = Take(args, "--e2e-serve");
            bool autoConfirm = args.Remove("--e2e-auto-confirm");
            string? e2eLink = e2eReady is null ? null : Take(args, "--e2e-link");
            if (SnapshotMode)
            {
                WindowInstance = new MainWindow(null);
                await WindowInstance.RenderPagesAsync(Path.GetFullPath(render!));
                Shutdown();
                return;
            }
            ToastNotificationManagerCompat.OnActivated += toast => Dispatcher.InvokeAsync(() =>
            {
                if (_peer is null) _pendingToast = toast.Argument;
                else HandleToast(toast.Argument);
            });
            string key = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(DataDirectory.ToUpperInvariant())))[..24];
            string pipe = "HopDrop-" + key;
            _mutex = new Mutex(true, "Local\\" + pipe, out bool first);
            if (!first)
            {
                await ForwardAsync(pipe, forwarded);
                Shutdown();
                return;
            }
            _pipeStop = new CancellationTokenSource();
            var defaults = HopDropPaths.Default;
            var paths = new HopDropPaths(DataDirectory, data is null ? defaults.LogDirectory : Path.Combine(DataDirectory, "logs"), receive);
            _peer = await HopDropPeer.OpenAsync(new PeerOptions(paths, portText is null ? 7410 : int.Parse(portText)));
            await _peer.StartAsync();
            _ = ListenAsync(pipe, _pipeStop.Token);
            AgentBridge.Start(_peer, DataDirectory, _pipeStop.Token);
            if (e2eReady is not null)
            {
                _e2eMode = true;
                Queue = new SendQueue(_peer, DataDirectory);
                Link = new DirectLink(_peer);
                _peer.DeviceContacted += id => Dispatcher.InvokeAsync(() => Queue.Retry(id));
                _peer.NearbyDevicesChanged += _ => Dispatcher.InvokeAsync(Queue.RetryNearby);
                if (autoConfirm) _peer.IncomingPairing += request =>
                {
                    File.WriteAllText(e2eReady + ".sas", request.Code);
                    request.Match();
                };
                WindowInstance = new MainWindow(_peer, await _peer.DevicesAsync(), await _peer.HistoryAsync())
                { ShowActivated = false, ShowInTaskbar = false, Opacity = 0 };
                WindowInstance.Show();
                await Dispatcher.InvokeAsync(() => { }, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                string? firstPage = WindowInstance.CurrentPage?.Key;
                bool hasCode = WindowInstance.HasPairingCode;
                WindowInstance.Hide();
                if (firstPage is null) throw new InvalidOperationException("First page did not load");
                if (e2eLink is not null)
                {
                    var parts = e2eLink.Split(',');
                    if (parts.Length != 3 || new DirectLinkInfo(parts[0], parts[1], parts[2]) is not { Valid: true } fake)
                        throw new ArgumentException("--e2e-link takes ssid,key,ip");
                    _peer.DirectLink = fake;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(e2eReady))!);
                await File.WriteAllTextAsync(e2eReady, System.Text.Json.JsonSerializer.Serialize(new { _peer.Id, _peer.Port,
                    Qr = _peer.CreateQrUri([System.Net.IPAddress.Loopback]), FirstPage = firstPage, HasPairingCode = hasCode, Store.Packaged }));
                return;
            }
            Queue = new SendQueue(_peer, DataDirectory);
            Queue.Changed += () => Dispatcher.InvokeAsync(() => WindowInstance?.OnQueueChanged());
            Link = new DirectLink(_peer);
            Link.Changed += () => Dispatcher.InvokeAsync(() => WindowInstance?.OnDirectLinkChanged());
            AttachPeer(_peer);
            _pairedDevices = await _peer.DevicesAsync();
            Link.SetPairedPhones(_pairedDevices);
            _clipboardShare = new ClipboardShare();
            Queue.RetryNearby();
            if (Updates.Installed) { Platform.RepairSignIn(); Updates.Start(); }
            if (_pendingToast is not null) { HandleToast(_pendingToast); _pendingToast = null; }
            if (background || minimized && !args.Contains("--send"))
            {
                // Hidden receiver (--background: no tray icon either) or tray only (--minimized): no window until someone opens HopDrop.
                if (!background) ShowTray();
                _ = Dispatcher.InvokeAsync(Platform.TrimMemory, System.Windows.Threading.DispatcherPriority.ApplicationIdle);
                return;
            }
            await OpenAsync(args);
        }
        catch (Exception ex)
        {
            if (e.Args.Contains("--render-pages"))
            {
                string folder = e.Args[Array.IndexOf(e.Args, "--render-pages") + 1];
                Directory.CreateDirectory(folder);
                File.WriteAllText(Path.Combine(folder, "error.txt"), ex.ToString());
                Shutdown(1);
                return;
            }
            if (e.Args.Contains("--e2e-serve"))
            {
                string ready = e.Args[Array.IndexOf(e.Args, "--e2e-serve") + 1];
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(ready))!);
                File.WriteAllText(ready + ".error", ex.ToString());
                Shutdown(1);
                return;
            }
            if (e.Args.Contains("--e2e-result"))
            {
                string path = e.Args[Array.IndexOf(e.Args, "--e2e-result") + 1];
                File.WriteAllText(path, "FAIL " + ex);
                Shutdown(1);
                return;
            }
            System.Windows.MessageBox.Show(ex.Message, "HopDrop couldn't start", System.Windows.MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(1);
        }
    }
    private static string? Take(List<string> args, string key)
    {
        int i = args.IndexOf(key);
        if (i < 0) return null;
        if (i + 1 >= args.Count) throw new ArgumentException($"Missing value for {key}");
        string value = args[i + 1]; args.RemoveRange(i, 2); return value;
    }
    private static async Task ForwardAsync(string pipe, string[] args)
    {
        using var client = new NamedPipeClientStream(".", pipe, PipeDirection.Out, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
        await client.ConnectAsync(5000);
        await using var writer = new StreamWriter(client, Encoding.UTF8);
        await writer.WriteLineAsync(System.Text.Json.JsonSerializer.Serialize(args));
    }
    private async Task ListenAsync(string pipe, CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            try
            {
                using var server = new NamedPipeServerStream(pipe, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(ct);
                using var reader = new StreamReader(server, Encoding.UTF8);
                string? line = await reader.ReadLineAsync(ct);
                if (line is not null)
                {
                    var args = System.Text.Json.JsonSerializer.Deserialize<string[]>(line) ?? [];
                    if ((_e2eMode || Store.LocalTest) && args.Any(a => a.StartsWith("--e2e-", StringComparison.Ordinal))) _ = HandleE2eAsync(args);
                    else if (args.Contains("--receive-bluetooth")) await Dispatcher.InvokeAsync(ReceiveBluetooth);
                    else if (args.Contains("--background")) { }
                    else if (args.Contains("--minimized") && !args.Contains("--send")) await Dispatcher.InvokeAsync(ShowTray);
                    else await Dispatcher.InvokeAsync(() => OpenAsync(args)).Task.Unwrap();
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { break; }
            catch (Exception ex) { System.Diagnostics.Debug.WriteLine(ex); }
        }
    }
    private async Task HandleE2eAsync(string[] forwarded)
    {
        var args = forwarded.ToList();
        string? resultPath = Take(args, "--e2e-result");
        Take(args, "--data");
        int send = args.IndexOf("--e2e-send");
        if (resultPath is null || _peer is null || !(_e2eMode || Store.LocalTest)) return;
        try
        {
            if (args.Remove("--e2e-qr"))
            {
                await File.WriteAllTextAsync(resultPath, _peer.CreateQrUri([System.Net.IPAddress.Loopback]));
                return;
            }
            if (Take(args, "--e2e-cancel") is string cancel)
            {
                Queue.Cancel(cancel);
                await File.WriteAllTextAsync(resultPath, Queue.Has(cancel) ? "FAIL still queued" : "PASS cancelled");
                return;
            }
            if (Take(args, "--e2e-text") is string textTarget)
            {
                if (args.Count != 1) throw new ArgumentException("--e2e-text takes a device and text");
                await _peer.SendTextAsync(textTarget, args[0]);
                await File.WriteAllTextAsync(resultPath, "PASS text");
                return;
            }
            if (Take(args, "--e2e-multi") is string targets)
            {
                var items = SendItem.From(args);
                var ids = targets.Split(',', StringSplitOptions.RemoveEmptyEntries);
                if (ids.Length < 2 || items.Count == 0) throw new ArgumentException("--e2e-multi takes device ids and files");
                await Task.WhenAll(ids.Select(id => Queue.SendFilesAsync(id, items)));
                await File.WriteAllTextAsync(resultPath, "PASS " + ids.Length);
                return;
            }
            if (send < 0 || send + 2 >= args.Count) throw new ArgumentException("--e2e-send takes a device and files");
            // --e2e-queue: through the "sends when it's back" queue, like the Send button (QUEUED when the device is away).
            if (args.Contains("--e2e-queue"))
            {
                var items = SendItem.From(args.Skip(send + 2).Where(a => a != "--e2e-queue"));
                await Queue.SendFilesAsync(args[send + 1], items);
                await File.WriteAllTextAsync(resultPath, Queue.Has(args[send + 1]) ? "QUEUED" : "PASS " + items.Count);
                return;
            }
            var result = await _peer.SendAsync(args[send + 1], args.Skip(send + 2).ToArray());
            await File.WriteAllTextAsync(resultPath, "PASS " + result.SavedPaths.Count);
        }
        catch (Exception ex) { await File.WriteAllTextAsync(resultPath, "FAIL " + ex.Message); }
    }
    protected override void OnExit(ExitEventArgs e)
    {
        if (_peer is not null)
        {
            var peer = _peer;
            _peer = null;
            // Session shutdown gives the peer one second to announce its exit.
            try { Task.Run(async () => await peer.DisposeAsync()).Wait(TimeSpan.FromSeconds(1)); }
            catch (Exception) { }
        }
        _clipboardShare?.Dispose();
        if (Link is not null) Link.Dispose();
        if (Queue is not null) Queue.Dispose();
        _pipeStop?.Cancel();
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
