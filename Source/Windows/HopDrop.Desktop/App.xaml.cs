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
    internal static MainWindow? WindowInstance { get; private set; }
    internal static HopDropPeer Peer => ((App)Current)._peer!;
    internal static bool SnapshotMode { get; private set; }
    internal static string DataDirectory { get; private set; } = "";

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        try
        {
            var args = Program.Arguments.ToList();
            string? render = Take(args, "--render-pages");
            SnapshotMode = render is not null;
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
                if (autoConfirm) _peer.IncomingPairing += request =>
                {
                    File.WriteAllText(e2eReady + ".sas", request.Code);
                    request.Match();
                };
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(e2eReady))!);
                await File.WriteAllTextAsync(e2eReady, System.Text.Json.JsonSerializer.Serialize(new { _peer.Id, _peer.Port, Qr = _peer.CreateQrUri([System.Net.IPAddress.Loopback]), Store.Packaged }));
                return;
            }
            AttachPeer(_peer);
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
                    if (args.Contains("--e2e-send")) _ = HandleE2eSendAsync(args);
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
    private async Task HandleE2eSendAsync(string[] forwarded)
    {
        var args = forwarded.ToList();
        string? resultPath = Take(args, "--e2e-result");
        int send = args.IndexOf("--e2e-send");
        if (resultPath is null || send < 0 || send + 2 >= args.Count || _peer is null) return;
        try
        {
            var result = await _peer.SendAsync(args[send + 1], args.Skip(send + 2).ToArray());
            await File.WriteAllTextAsync(resultPath, "PASS " + result.SavedPaths.Count);
        }
        catch (Exception ex) { await File.WriteAllTextAsync(resultPath, "FAIL " + ex.Message); }
    }
    protected override void OnExit(ExitEventArgs e)
    {
        _pipeStop?.Cancel();
        _mutex?.Dispose();
        base.OnExit(e);
    }
}
