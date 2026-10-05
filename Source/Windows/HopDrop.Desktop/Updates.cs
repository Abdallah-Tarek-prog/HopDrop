using System.Diagnostics;
using System.Windows.Threading;
using Velopack;
using Velopack.Sources;

namespace HopDrop.Desktop;

/// <summary>
/// Automatic updates for the installed app (Setup.exe; the portable .exe doesn't update itself). HopDrop checks GitHub
/// Releases a minute after starting and then daily, downloads quietly, and installs the update when nobody is using it:
/// on the next start, from Settings, or while it waits in the tray or in the background with nothing transferring.
/// </summary>
internal static class Updates
{
    /// <summary>Where releases are published (a public repository's GitHub Releases).</summary>
    private const string Feed = "https://github.com/Abdallah-Tarek-prog/HopDrop";
    private static readonly TimeSpan CheckEvery = TimeSpan.FromHours(24), QuietFor = TimeSpan.FromMinutes(10);
    private static UpdateManager? _manager;
    private static DispatcherTimer? _timer;
    private static DateTime _lastActivity = DateTime.UtcNow;
    private static bool _checking;
    /// <summary>"" while nothing has happened; otherwise a short status for Settings.</summary>
    public static string Status { get; private set; } = "";
    public static string? ReadyVersion { get; private set; }
    public static bool Installed => Manager?.IsInstalled == true;
    public static event Action? Changed;

    private static UpdateManager? Manager
    {
        get
        {
            if (_manager is not null) return _manager;
            try { _manager = new UpdateManager(new GithubSource(Feed, null, false)); }
            catch (Exception e) { Debug.WriteLine(e); }
            return _manager;
        }
    }
    /// <summary>Starts the schedule: first check after a minute, then daily.</summary>
    public static void Start()
    {
        if (!Installed || _timer is not null) return;
        if (Manager?.UpdatePendingRestart is { } pending) { ReadyVersion = pending.Version.ToString(); Status = $"Version {ReadyVersion} is ready."; }
        _timer = new DispatcherTimer { Interval = TimeSpan.FromMinutes(1) };
        _timer.Tick += async (_, _) => { _timer.Interval = CheckEvery; await CheckAsync(false); };
        _timer.Start();
        var idle = new DispatcherTimer { Interval = TimeSpan.FromMinutes(5) };
        idle.Tick += (_, _) => ApplyIfQuiet();
        idle.Start();
    }
    /// <summary>Something was sent or received: don't restart for an update for a while.</summary>
    public static void Activity() => _lastActivity = DateTime.UtcNow;
    /// <summary>Checks now; when <paramref name="manual"/>, also reports "no update" and errors in <see cref="Status"/>.</summary>
    public static async Task CheckAsync(bool manual)
    {
        var manager = Manager;
        if (manager is null || !manager.IsInstalled || _checking) return;
        _checking = true;
        try
        {
            if (manual) { Status = "Checking for updates…"; Changed?.Invoke(); }
            var update = await manager.CheckForUpdatesAsync();
            if (update is null) { if (manual) Status = "HopDrop is up to date."; return; }
            Status = $"Downloading version {update.TargetFullRelease.Version}…"; Changed?.Invoke();
            await manager.DownloadUpdatesAsync(update);
            ReadyVersion = update.TargetFullRelease.Version.ToString();
            Status = $"Version {ReadyVersion} is ready.";
            Platform.Notify("HopDrop update ready", $"Version {ReadyVersion} installs the next time HopDrop restarts, or now from Settings.");
        }
        catch (Exception e)
        {
            Debug.WriteLine(e);
            if (manual) Status = "Couldn't check for updates. Check the internet connection and try again.";
        }
        finally { _checking = false; Changed?.Invoke(); }
    }
    /// <summary>Installs the downloaded update now and starts HopDrop again the same way it runs now.</summary>
    public static void RestartNow()
    {
        var pending = Manager?.UpdatePendingRestart;
        if (pending is null) return;
        Manager!.ApplyUpdatesAndRestart(pending, App.RestartArguments());
    }
    /// <summary>In the tray or the background, with no window, transfer or question open for a while: update without bothering anyone.</summary>
    private static void ApplyIfQuiet()
    {
        if (ReadyVersion is null || App.WindowInstance is not null || App.Instance.Busy || DateTime.UtcNow - _lastActivity < QuietFor) return;
        RestartNow();
    }
}
