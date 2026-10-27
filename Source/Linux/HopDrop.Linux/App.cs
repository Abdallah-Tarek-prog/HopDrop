using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Media;
using Avalonia.Platform;
using Avalonia.Themes.Fluent;
using Avalonia.Styling;
using Avalonia.Threading;
using HopDrop.Agents;
using HopDrop.Core;
using System.IO.Pipes;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace HopDrop.Linux;

public sealed class App : Application
{
    internal static MainWindow? Window { get; private set; }
    internal static bool Quitting { get; private set; }
    private HopDropPeer? _peer;
    private AgentService? _agents;
    private OutgoingQueue? _queue;
    private TrayIcon? _tray;
    private Mutex? _instance;
    private readonly CancellationTokenSource _stop = new();
    public override void Initialize()
    {
        var theme = new FluentTheme();
        theme.Palettes[ThemeVariant.Light] = new ColorPaletteResources { Accent = Color.Parse("#C2410C") };
        theme.Palettes[ThemeVariant.Dark] = new ColorPaletteResources
        { Accent = Color.Parse("#FF7A2E"), RegionColor = Color.Parse("#202020") };
        Styles.Add(theme);
    }

    public override async void OnFrameworkInitializationCompleted()
    {
        if (ApplicationLifetime is not IClassicDesktopStyleApplicationLifetime desktop) return;
        desktop.ShutdownMode = ShutdownMode.OnExplicitShutdown;
        desktop.Exit += (_, _) => { _stop.Cancel(); _tray?.Dispose(); _instance?.Dispose(); };
        var loading = new Window { Title = "HopDrop", Width = 420, Height = 180,
            Icon = Logo(), Content = new TextBlock { Text = "HopDrop", FontSize = 25, Margin = new Thickness(28) } };
        loading.Closing += (_, _) => { if (Window is null) Quit(); };
        desktop.MainWindow = loading;
        loading.Show();
        try
        {
            string? data = Program.Option("--data"), preview = Program.Option("--render-preview");
            if (preview is not null && data is null) throw new ArgumentException("Preview requires an isolated profile.");
            var paths = data is null ? HopDropPaths.Default : new HopDropPaths(Path.GetFullPath(data), Path.Combine(Path.GetFullPath(data), "logs"), Program.Option("--receive-dir"));
            string instanceName = "hopdrop-" + Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(Path.GetFullPath(paths.DataDirectory)))).ToLowerInvariant()[..24];
            _instance = new Mutex(true, instanceName, out bool first);
            if (!first)
            {
                await using var client = new NamedPipeClientStream(".", instanceName, PipeDirection.Out, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await client.ConnectAsync(5000);
                await client.WriteAsync(Encoding.UTF8.GetBytes(JsonSerializer.Serialize(Program.Arguments) + "\n"));
                desktop.Shutdown(); return;
            }
            var preferences = new JsonStore<LinuxPreferences>(Path.Combine(Path.GetDirectoryName(paths.SettingsPath)!, "linux.json"), new());
            var saved = await preferences.ReadAsync();
            L.Language = saved.Language == "ar" ? "ar" : "en";
            int port = Program.Option("--port") is { } number && int.TryParse(number, out int value) && value is >= 0 and <= 65535 ? value : 7410;
            _peer = await HopDropPeer.OpenAsync(new PeerOptions(paths, port, preview is null ? null : "HopDrop Linux"));
            if (preview is null) await _peer.StartAsync();
            var integration = new DesktopIntegration(data is null ? null : paths.DataDirectory);
            _queue = new OutgoingQueue(_peer, paths.DataDirectory);
            await _queue.InitializeAsync();
            Window = new MainWindow(_peer, preferences, saved, _queue, integration, paths.DataDirectory, preview is not null);
            desktop.MainWindow = Window;
            if (preview is null)
            {
                SetupTray();
                _agents = new AgentService(_peer, paths.DataDirectory, () => Window.AgentsEnabled);
                _agents.Start(_stop.Token);
                _ = ListenAsync(instanceName, _stop.Token);
            }
            Window.Show(); loading.Close();
            if (preview is not null) { await Window.RenderPreviewAsync(Path.GetFullPath(preview)); Quit(); }
            else
            {
                await Window.HandleLaunchAsync(Program.Arguments);
                if (Program.Arguments.Contains("--background") && _tray is not null) { Window.Hide(); _peer.SetForeground(false); }
                else if (Program.Arguments.Contains("--minimized")) { Window.WindowState = WindowState.Minimized; _peer.SetForeground(false); }
            }
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(error);
            loading.Content = new TextBlock { Text = L.Error(error), FlowDirection = L.Language == "ar" ? FlowDirection.RightToLeft : FlowDirection.LeftToRight, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(28) };
        }
        base.OnFrameworkInitializationCompleted();
    }
    private static WindowIcon Logo() => new(AssetLoader.Open(new Uri("avares://HopDrop.Linux/Assets/HopDrop.ico")));
    private async Task ListenAsync(string name, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested)
        {
            try
            {
                await using var server = new NamedPipeServerStream(name, PipeDirection.In, 1, PipeTransmissionMode.Byte, PipeOptions.Asynchronous | PipeOptions.CurrentUserOnly);
                await server.WaitForConnectionAsync(stop);
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(stop); timeout.CancelAfter(TimeSpan.FromSeconds(5));
                var buffer = new byte[64 * 1024]; int count = 0;
                while (count < buffer.Length)
                {
                    int n = await server.ReadAsync(buffer.AsMemory(count), timeout.Token);
                    if (n == 0) break;
                    count += n;
                    if (buffer[count - 1] == '\n') break;
                }
                if (count == buffer.Length) continue;
                string[]? args = JsonSerializer.Deserialize<string[]>(buffer.AsSpan(0, count));
                if (args is not null) Dispatcher.UIThread.Post(async () =>
                {
                    if (Window is null) return;
                    Window.Show(); Window.WindowState = WindowState.Normal; Window.Activate();
                    await Window.HandleLaunchAsync(args);
                });
            }
            catch (OperationCanceledException) when (stop.IsCancellationRequested) { break; }
            catch (Exception error) when (error is IOException or JsonException or OperationCanceledException) { }
        }
    }
    private void SetupTray()
    {
        try
        {
            var menu = new NativeMenu();
            foreach (string page in new[] { "send", "activity", "devices", "settings" })
            {
                var item = new NativeMenuItem(L.Get(page));
                item.Click += (_, _) => Window?.ShowPage(page);
                menu.Items.Add(item);
            }
            var quit = new NativeMenuItem(L.Get("quit")); quit.Click += (_, _) => Quit(); menu.Items.Add(quit);
            _tray = new TrayIcon { Icon = Logo(), ToolTipText = "HopDrop", Menu = menu };
            TrayIcon.SetIcons(this, new TrayIcons { _tray });
        }
        catch { /* Some desktops do not supply a tray; the visible window remains available. */ }
    }
    internal static async void Quit()
    {
        if (Quitting) return;
        Quitting = true;
        if (Current is App app)
        {
            app._stop.Cancel();
            if (Window is not null) await Window.StopServicesAsync();
            if (app._agents is not null) await app._agents.DisposeAsync();
            if (app._queue is not null) await app._queue.DisposeAsync();
            if (app._peer is not null) await app._peer.DisposeAsync();
            if (app.ApplicationLifetime is IClassicDesktopStyleApplicationLifetime desktop) desktop.Shutdown();
        }
    }
}
