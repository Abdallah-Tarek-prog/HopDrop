using System.Diagnostics;
using Windows.ApplicationModel;
using Windows.ApplicationModel.Activation;

namespace HopDrop.Desktop;

/// <summary>
/// The Microsoft Store version (an MSIX package, see Source/Windows/Store). It is the same app; what differs is how
/// Windows starts it and what Windows does for it: the package manifest adds HopDrop to File Explorer's Share menu,
/// opens the firewall at install time, starts it at sign-in through a startup task (instead of the Run registry key),
/// gives agents the "hopdrop.exe" command, and the Store installs updates (instead of Velopack).
/// </summary>
internal static class Store
{
    /// <summary>The startup task declared in AppxManifest.xml.</summary>
    private const string StartupTaskId = "HopDropStartup";

    /// <summary>Running from an installed MSIX package (Store or a test install).</summary>
    public static bool Packaged { get; } = Detect();

    /// <summary>The "hopdrop.exe" command the package adds (packaged executables can't be started by their path).</summary>
    public static string Alias => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Microsoft", "WindowsApps", "hopdrop.exe");

    private static bool Detect()
    {
        try { return Package.Current.Id is not null; }
        catch (InvalidOperationException) { return false; }
        catch (Exception e) when (e.HResult == unchecked((int)0x80073D54)) { return false; } // APPMODEL_ERROR_NO_PACKAGE
    }

    /// <summary>
    /// Turns how Windows started the package into HopDrop's usual arguments: Explorer's Share menu → "--send &lt;files&gt;",
    /// the sign-in startup task → "--signin" (the app then starts in the tray or as the hidden receiver, as Settings say).
    /// </summary>
    public static string[] Arguments(string[] args)
    {
        if (!Packaged) return args;
        try
        {
            var activated = AppInstance.GetActivatedEventArgs();
            if (activated is ShareTargetActivatedEventArgs share)
            {
                var operation = share.ShareOperation;
                var items = Task.Run(async () => await operation.Data.GetStorageItemsAsync()).GetAwaiter().GetResult();
                var paths = items.Select(item => item.Path).Where(path => !string.IsNullOrEmpty(path)).ToList();
                operation.ReportCompleted();
                return paths.Count == 0 ? args : [.. args, "--send", .. paths];
            }
            if (activated?.Kind == ActivationKind.StartupTask) return [.. args, "--signin"];
        }
        catch (Exception e) { Debug.WriteLine(e); }
        return args;
    }

    /// <summary>Signs in with Windows when "Start with Windows" or "Receive in the background" is on. Null, or why it couldn't.</summary>
    public static async Task<string?> UpdateStartupAsync()
    {
        try
        {
            var task = await StartupTask.GetAsync(StartupTaskId);
            bool wanted = Preferences.StartWithWindows || Preferences.BackgroundReceive;
            if (!wanted) { if (task.State is StartupTaskState.Enabled) task.Disable(); return null; }
            if (task.State is StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy) return null;
            var state = await task.RequestEnableAsync();
            return state switch
            {
                StartupTaskState.Enabled or StartupTaskState.EnabledByPolicy => null,
                StartupTaskState.DisabledByUser => "Windows has HopDrop turned off at sign-in. Turn it on in Task Manager → Startup apps.",
                _ => "Windows doesn't allow HopDrop to start at sign-in on this PC."
            };
        }
        catch (Exception e) { Debug.WriteLine(e); return "HopDrop couldn't set itself to start at sign-in."; }
    }
}
