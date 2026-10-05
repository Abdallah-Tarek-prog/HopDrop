using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Automation;
using HopDrop.Core;

namespace HopDrop.Desktop;

/// <summary>
/// Drives Windows' own Bluetooth File Transfer wizard (fsquirt.exe), which has no command-line switch for
/// "receive". HopDrop presses "Receive files" for the user, and when a file arrives it presses Finish and
/// moves the file from the wizard's folder into HopDrop's receive folder. It then waits for the next file
/// until the user closes the wizard. If any step can't be automated, the wizard is simply left to the user.
/// Control ids below are the wizard's own (stable across Windows languages). The wizard's file list doesn't expose
/// its items to UI Automation, so the received files are the ones that appear in its save folder after Finish.
/// </summary>
internal sealed class BluetoothWizard
{
    private const string ReceiveOption = "11002", SendOption = "11003", Location = "11026", Finish = "finishbutton";
    private static readonly TimeSpan IdleLimit = TimeSpan.FromMinutes(15);
    private readonly Func<string> _receiveDirectory;
    private readonly Action<IReadOnlyList<string>> _received;
    private readonly Action<string> _notice;
    private CancellationTokenSource? _session;

    public BluetoothWizard(Func<string> receiveDirectory, Action<IReadOnlyList<string>> received, Action<string> notice)
    { _receiveDirectory = receiveDirectory; _received = received; _notice = notice; }

    public bool Receiving => _session is { IsCancellationRequested: false };

    /// <summary>Starts (or restarts) a receive session: the wizard waits for a phone to send over Bluetooth.</summary>
    public void Receive()
    {
        _session?.Cancel();
        var session = _session = new CancellationTokenSource();
        _ = Task.Run(() =>
        {
            try { ReceiveLoop(session.Token); }
            catch (Exception e) { Log("receive loop failed: " + e); _notice("Bluetooth receiving stopped. Click Finish in the Bluetooth window if a file is waiting."); }
        });
    }

    /// <summary>Appends to %LOCALAPPDATA%\HopDrop\logs\bluetooth.log (no file contents, just steps).</summary>
    private static void Log(string line)
    {
        try
        {
            string dir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HopDrop", "logs");
            Directory.CreateDirectory(dir);
            File.AppendAllText(Path.Combine(dir, "bluetooth.log"), $"{DateTime.Now:yyyy-MM-dd HH:mm:ss} {line}{Environment.NewLine}");
        }
        catch (Exception) { }
    }

    public void Stop() => _session?.Cancel();

    /// <summary>Opens the wizard on its "send" page; Windows then asks which device and files.</summary>
    public static void Send() => _ = Task.Run(() =>
    {
        try { using var p = Process.Start(new ProcessStartInfo("fsquirt.exe") { UseShellExecute = true })!; Press(WaitForWindow(p.Id, TimeSpan.FromSeconds(10)), SendOption); }
        catch (Exception e) { Debug.WriteLine(e); }
    });

    private void ReceiveLoop(CancellationToken stop)
    {
        var started = DateTime.UtcNow;
        while (!stop.IsCancellationRequested && DateTime.UtcNow - started < IdleLimit)
        {
            Process wizard;
            var waitingSince = DateTime.UtcNow.AddSeconds(-2);
            try { wizard = Process.Start(new ProcessStartInfo("fsquirt.exe") { UseShellExecute = true })!; }
            catch (Exception) { _notice("Windows Bluetooth isn't available on this PC. Turn on Bluetooth in Windows Settings."); break; }
            using (wizard)
            {
                var window = WaitForWindow(wizard.Id, TimeSpan.FromSeconds(10));
                Log($"wizard pid {wizard.Id}, window {(window is null ? "not found" : window.Current.NativeWindowHandle.ToString())}");
                if (window is null || !Press(window, ReceiveOption))
                {
                    _notice("Click \"Receive files\" in the Bluetooth window, then send from your phone.");
                    break;
                }
                var outcome = WaitForFiles(wizard, window, waitingSince, stop);
                if (outcome is null) { if (!wizard.HasExited) try { wizard.CloseMainWindow(); } catch { } break; }
                if (outcome.Count > 0) { _received(outcome); started = DateTime.UtcNow; }
                else break;
            }
        }
        _session = null;
    }

    /// <summary>Waits for the "Save the received file" page, finishes it and moves the files. Null = user closed the wizard or stop requested.</summary>
    private IReadOnlyList<string>? WaitForFiles(Process wizard, AutomationElement window, DateTime waitingSince, CancellationToken stop)
    {
        while (!stop.IsCancellationRequested && !wizard.HasExited)
        {
            Thread.Sleep(500);
            AutomationElement? location, finish;
            try { location = Find(window, Location); finish = Find(window, Finish); }
            catch (ElementNotAvailableException) { return null; }
            if (location is null || finish is null) continue;
            string folder = SaveFolder(window, location);
            Log($"save page: folder '{folder}'");
            if (!Directory.Exists(folder)) { _notice("A Bluetooth file arrived. Click Finish in the Bluetooth window to save it."); return []; }
            if (!Press(window, Finish)) { _notice("Click Finish in the Bluetooth window to save the file."); return []; }
            wizard.WaitForExit(15000);
            var arrived = Arrived(folder, waitingSince);
            Log($"finish pressed; exited {wizard.HasExited}; {arrived.Count} new file(s)");
            if (arrived.Count == 0) _notice($"The Bluetooth file was saved in {folder}.");
            return MoveIntoReceiveFolder(arrived);
        }
        Log($"wait ended: stop {stop.IsCancellationRequested}, exited {wizard.HasExited}");
        return null;
    }

    /// <summary>
    /// The folder shown on the save page. Windows fills the path in a few seconds after the page appears (until
    /// then the control's accessible name is its label, "Location:"), so wait up to 15 s for a real folder; the
    /// wizard's default is the user's Documents folder, used if the path never shows up.
    /// </summary>
    private static string SaveFolder(AutomationElement window, AutomationElement location)
    {
        for (int attempt = 0; attempt < 60; attempt++)
        {
            string folder = "";
            try { folder = location.Current.Name; } catch (ElementNotAvailableException) { }
            if (Path.IsPathRooted(folder) && Directory.Exists(folder)) return folder;
            Thread.Sleep(250);
            location = Find(window, Location) ?? location;
        }
        return Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
    }

    /// <summary>Files in the wizard's save folder written since this receive session started waiting.</summary>
    private static List<string> Arrived(string folder, DateTime since)
    {
        for (int attempt = 0; attempt < 12; attempt++)
        {
            var files = new DirectoryInfo(folder).EnumerateFiles()
                .Where(f => f.LastWriteTimeUtc >= since && !f.Name.Equals("desktop.ini", StringComparison.OrdinalIgnoreCase) && (f.Attributes & FileAttributes.Hidden) == 0)
                .Select(f => f.FullName).ToList();
            if (files.Count > 0) return files;
            Thread.Sleep(250);
        }
        return [];
    }

    private IReadOnlyList<string> MoveIntoReceiveFolder(List<string> sources)
    {
        string target = _receiveDirectory();
        Directory.CreateDirectory(target);
        var moved = new List<string>();
        foreach (string source in sources)
        {
            string clean = FileNames.Sanitize(Path.GetFileName(source));
            string final = Path.Combine(target, FileNames.Unique(clean, n => File.Exists(Path.Combine(target, n))));
            // Bluetooth has no device identity to trust, so arrivals always get Windows' "downloaded" mark.
            try { File.Move(source, final); ZoneMark.Write(final); moved.Add(final); }
            catch (IOException) { ZoneMark.Write(source); moved.Add(source); }
            catch (UnauthorizedAccessException) { moved.Add(source); }
        }
        return moved;
    }

    private static AutomationElement? WaitForWindow(int processId, TimeSpan timeout)
    {
        var until = DateTime.UtcNow + timeout;
        var condition = new PropertyCondition(AutomationElement.ProcessIdProperty, processId);
        while (DateTime.UtcNow < until)
        {
            var window = AutomationElement.RootElement.FindFirst(TreeScope.Children, condition);
            if (window is not null && Find(window, ReceiveOption) is not null) return window;
            Thread.Sleep(200);
        }
        return null;
    }

    private static AutomationElement? Find(AutomationElement window, string id) =>
        window.FindFirst(TreeScope.Descendants, new PropertyCondition(AutomationElement.AutomationIdProperty, id));

    private static bool Press(AutomationElement? window, string id)
    {
        if (window is null) return false;
        var element = Find(window, id);
        if (element is null) return false;
        if (element.TryGetCurrentPattern(InvokePattern.Pattern, out var invoke)) { ((InvokePattern)invoke).Invoke(); return true; }
        var handle = new IntPtr(element.Current.NativeWindowHandle);
        if (handle == IntPtr.Zero) return false;
        SendMessage(handle, 0x00F5 /* BM_CLICK */, IntPtr.Zero, IntPtr.Zero);
        return true;
    }

    [DllImport("user32.dll")] private static extern IntPtr SendMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
}
