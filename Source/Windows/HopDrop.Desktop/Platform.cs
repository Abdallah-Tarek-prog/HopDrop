using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using HopDrop.Core;
using Microsoft.Toolkit.Uwp.Notifications;
using Microsoft.Win32;

namespace HopDrop.Desktop;

/// <summary>Desktop-only preferences in %APPDATA%\HopDrop\desktop.json.</summary>
internal static class Preferences
{
    private static string PathName => Path.Combine(App.DataDirectory, "desktop.json");
    private static DesktopSettings? _cache;
    private static DesktopSettings Current
    {
        get
        {
            if (_cache is not null) return _cache;
            try { _cache = JsonSerializer.Deserialize<DesktopSettings>(File.ReadAllText(PathName)); }
            catch (IOException) { } catch (JsonException) { } catch (UnauthorizedAccessException) { }
            return _cache ??= new DesktopSettings(true, "system");
        }
    }
    private static void Save(DesktopSettings value)
    {
        _cache = value;
        if (App.SnapshotMode) return; // --render-pages flips the theme; never write that to the real preferences.
        Directory.CreateDirectory(App.DataDirectory);
        File.WriteAllText(PathName, JsonSerializer.Serialize(value));
    }
    /// <summary>Closing the window keeps HopDrop running in the tray (and receiving).</summary>
    public static bool CloseToTray { get => Current.CloseToTray; set => Save(Current with { CloseToTray = value }); }
    /// <summary>"system", "light" or "dark".</summary>
    public static string Theme { get => Current.Theme ?? "system"; set => Save(Current with { Theme = value }); }
    /// <summary>Quitting leaves a hidden receiver running (no window, no tray icon); it also starts hidden at sign-in.</summary>
    public static bool BackgroundReceive { get => Current.BackgroundReceive; set => Save(Current with { BackgroundReceive = value }); }
    /// <summary>The one-time "still receiving" notice has been shown.</summary>
    public static bool BackgroundNoticeShown { get => Current.BackgroundNoticeShown; set => Save(Current with { BackgroundNoticeShown = value }); }
    /// <summary>AI agents on this PC may use HopDrop (see <see cref="AgentBridge"/>). Off until the user turns it on.</summary>
    public static bool AgentsEnabled { get => Current.Agents; set => Save(Current with { Agents = value }); }
    /// <summary>Store version only: "Start with Windows" (the installer version keeps this in its sign-in entry).</summary>
    public static bool StartWithWindows { get => Current.StartWithWindows; set => Save(Current with { StartWithWindows = value }); }
    private sealed record DesktopSettings(bool CloseToTray, string? Theme, bool BackgroundReceive = false, bool BackgroundNoticeShown = false, bool Agents = false,
        bool StartWithWindows = false);
}

internal static class Platform
{
    private static string Exe => Environment.ProcessPath ?? throw new InvalidOperationException("Executable path unavailable");
    private static string Suffix => string.Equals(App.DataDirectory, HopDropPaths.Default.DataDirectory, StringComparison.OrdinalIgnoreCase)
        ? "" : "-" + Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(App.DataDirectory.ToUpperInvariant())))[..8];
    private static string RunName => "HopDrop" + Suffix;
    /// <summary>Explorer shows the shortcut's file name in the Send to menu.</summary>
    private static string SendToPath => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "HopDrop" + Suffix + ".lnk");
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    /// <summary>HopDrop starts in the tray at sign-in (the sign-in entry says --minimized; --background means the hidden receiver only).</summary>
    public static bool StartWithWindows
    {
        get
        {
            if (Store.Packaged) return Preferences.StartWithWindows;
            using var key = Registry.CurrentUser.OpenSubKey(RunKey); return key?.GetValue(RunName) is string command && command.Contains("--minimized");
        }
    }
    public static void SetStartWithWindows(bool enabled)
    {
        if (Store.Packaged) { Preferences.StartWithWindows = enabled; _ = ApplyStoreStartupAsync(); return; }
        SetSignIn(enabled ? "--minimized" : Preferences.BackgroundReceive ? "--background" : null);
    }
    public static void SetBackgroundReceive(bool enabled)
    {
        Preferences.BackgroundReceive = enabled;
        if (Store.Packaged) { _ = ApplyStoreStartupAsync(); return; }
        if (!StartWithWindows) SetSignIn(enabled ? "--background" : null);
    }
    /// <summary>The Store version signs in through its startup task; Windows may refuse (turned off in Task Manager).</summary>
    private static async Task ApplyStoreStartupAsync()
    {
        if (await Store.UpdateStartupAsync() is { } problem) Notify("HopDrop can't start at sign-in", problem);
    }
    private static void SetSignIn(string? mode)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey, true);
        if (mode is not null) key.SetValue(RunName, $"\"{Exe}\" {mode} --data \"{App.DataDirectory}\""); else key.DeleteValue(RunName, false);
    }
    /// <summary>The installed copy owns the sign-in entry: if it still starts an older HopDrop.exe (a portable copy), point it here.</summary>
    public static void RepairSignIn()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            if (key?.GetValue(RunName) is not string command || command.Contains($"\"{Exe}\"", StringComparison.OrdinalIgnoreCase)) return;
            string? mode = command.Contains("--minimized") ? "--minimized" : command.Contains("--background") ? "--background" : null;
            if (mode is not null) SetSignIn(mode);
        }
        catch (Exception e) { Debug.WriteLine(e); }
    }
    /// <summary>Uninstall: removes what this copy added to Windows for the user (sign-in entry, "Send to" shortcut, notifications). Firewall rules need admin and stay.</summary>
    public static void RemoveFromWindows()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey, true);
            if (key is not null)
                foreach (string name in key.GetValueNames())
                    if (name.StartsWith("HopDrop", StringComparison.OrdinalIgnoreCase) && key.GetValue(name) is string command && command.Contains(Exe, StringComparison.OrdinalIgnoreCase))
                        key.DeleteValue(name, false);
        }
        catch (Exception e) { Debug.WriteLine(e); }
        try
        {
            Type shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host unavailable");
            dynamic shell = Activator.CreateInstance(shellType)!;
            foreach (string link in Directory.GetFiles(Environment.GetFolderPath(Environment.SpecialFolder.SendTo), "*.lnk")
                .Where(l => Path.GetFileName(l).StartsWith("HopDrop", StringComparison.OrdinalIgnoreCase) || Path.GetFileName(l).StartsWith("HopDrop", StringComparison.OrdinalIgnoreCase)))
            {
                dynamic shortcut = shell.CreateShortcut(link);
                if (string.Equals((string)shortcut.TargetPath, Exe, StringComparison.OrdinalIgnoreCase)) File.Delete(link);
                Marshal.FinalReleaseComObject(shortcut);
            }
            Marshal.FinalReleaseComObject(shell);
        }
        catch (Exception e) { Debug.WriteLine(e); }
        try { ToastNotificationManagerCompat.Uninstall(); } catch (Exception e) { Debug.WriteLine(e); }
    }
    [DllImport("kernel32.dll")] private static extern bool SetProcessWorkingSetSize(IntPtr process, nint minimum, nint maximum);
    /// <summary>Returns the closed window's memory to Windows so the hidden receiver stays small.</summary>
    public static void TrimMemory()
    {
        GC.Collect(GC.MaxGeneration, GCCollectionMode.Aggressive, true, true);
        GC.WaitForPendingFinalizers();
        using var self = Process.GetCurrentProcess();
        SetProcessWorkingSetSize(self.Handle, -1, -1);
    }
    public static bool SendToInstalled => File.Exists(SendToPath);
    public static void SetSendTo(bool enabled)
    {
        if (!enabled) { if (File.Exists(SendToPath)) File.Delete(SendToPath); return; }
        Type shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new InvalidOperationException("Windows Script Host unavailable");
        dynamic shell = Activator.CreateInstance(shellType)!;
        dynamic shortcut = shell.CreateShortcut(SendToPath);
        shortcut.TargetPath = Exe; shortcut.Arguments = "--data \"" + App.DataDirectory + "\" --send";
        shortcut.IconLocation = Exe + ",0"; shortcut.Description = "Send files with HopDrop"; shortcut.Save();
        Marshal.FinalReleaseComObject(shortcut); Marshal.FinalReleaseComObject(shell);
    }
    public static void OpenFolder(string path)
    {
        if (Directory.Exists(path)) Process.Start(new ProcessStartInfo("explorer.exe", $"\"{path}\"") { UseShellExecute = true });
    }
    public static void OpenFile(string path)
    {
        if (File.Exists(path)) Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }
    public static void ShowInFolder(string path)
    {
        if (File.Exists(path)) Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
    }

    /// <summary>Whether Windows Firewall lets phones reach HopDrop (TCP and UDP 7410) on private and public networks. Null = unknown.</summary>
    public static (bool Private, bool Public)? FirewallStatus()
    {
        try
        {
            Type type = Type.GetTypeFromProgID("HNetCfg.FwPolicy2") ?? throw new InvalidOperationException();
            dynamic policy = Activator.CreateInstance(type)!;
            bool tcpPrivate = false, udpPrivate = false, tcpPublic = false, udpPublic = false;
            foreach (dynamic rule in policy.Rules)
            {
                try
                {
                    if (!(bool)rule.Enabled || (int)rule.Direction != 1 || (int)rule.Action != 1) continue;
                    string application = (string?)rule.ApplicationName ?? "";
                    if (application.Length > 0 && !string.Equals(application, Exe, StringComparison.OrdinalIgnoreCase)) continue;
                    if (!string.IsNullOrEmpty((string?)rule.ServiceName)) continue;
                    string ports = (string?)rule.LocalPorts ?? "";
                    bool anyPort = application.Length > 0 && (ports.Length == 0 || ports == "*");
                    if (!anyPort && !ports.Split(',').Any(part => part.Trim() == "7410" || part.Trim() == "*")) continue;
                    int profiles = (int)rule.Profiles, protocol = (int)rule.Protocol;
                    if ((profiles & 2) != 0 && (protocol is 6 or 256)) tcpPrivate = true;
                    if ((profiles & 2) != 0 && (protocol is 17 or 256)) udpPrivate = true;
                    if ((profiles & 4) != 0 && (protocol is 6 or 256)) tcpPublic = true;
                    if ((profiles & 4) != 0 && (protocol is 17 or 256)) udpPublic = true;
                }
                catch (Exception) { }
            }
            Marshal.FinalReleaseComObject(policy);
            return (tcpPrivate && udpPrivate, tcpPublic && udpPublic);
        }
        catch { return null; }
    }
    /// <summary>
    /// Lets HopDrop itself (not every program) receive on port 7410, only from devices on the same local network.
    /// Replaces the older rules that opened the port to any program, and any "HopDrop" rules Windows created on its own.
    /// </summary>
    public static void AllowFirewall(bool includePublic)
    {
        string profile = includePublic ? "private,public" : "private";
        string Add(string protocol) => $"netsh advfirewall firewall add rule name=\"HopDrop\" dir=in action=allow program=\"{Exe}\" protocol={protocol} localport=7410 remoteip=localsubnet profile={profile}";
        string command = "/c netsh advfirewall firewall delete rule name=\"HopDrop TCP 7410\" & netsh advfirewall firewall delete rule name=\"HopDrop UDP 7410\" & "
            + "netsh advfirewall firewall delete rule name=\"HopDrop\" & " + Add("TCP") + " & " + Add("UDP");
        Process.Start(new ProcessStartInfo("cmd.exe", command) { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden });
    }

    public static void Notify(string title, string detail)
    {
        try { new ToastContentBuilder().AddText(title).AddText(detail).Show(); }
        catch (Exception e) { Debug.WriteLine(e); }
    }
    public static void PairingToast(string name)
    {
        try { new ToastContentBuilder().AddText($"{name} wants to pair").AddText("Compare the 6-digit number on both screens.").AddButton(new ToastButton().SetContent("Show number").AddArgument("action", "pair")).Show(); }
        catch (Exception e) { Debug.WriteLine(e); }
    }

    // ---- Live progress toast (shown for incoming transfers while the window is hidden) ----
    private const string ProgressGroup = "transfers";
    private static readonly Dictionary<Guid, (DateTime Updated, uint Sequence)> ProgressToasts = [];
    public static void ProgressToast(TransferProgress p)
    {
        try
        {
            string tag = p.TransferId.ToString("N")[..16];
            if (!ProgressToasts.TryGetValue(p.TransferId, out var state))
            {
                ProgressToasts[p.TransferId] = (DateTime.UtcNow, 1);
                new ToastContentBuilder()
                    .AddText($"{(p.Incoming ? "Receiving" : "Sending")} {Fmt.Files(p.FileCount)} {(p.Incoming ? "from" : "to")} {p.PeerName}")
                    .AddVisualChild(new AdaptiveProgressBar
                    {
                        Title = new BindableString("file"), Value = new BindableProgressBarValue("value"),
                        ValueStringOverride = new BindableString("amount"), Status = new BindableString("status")
                    })
                    .AddButton(new ToastButton().SetContent("Cancel").AddArgument("action", "cancel:" + p.TransferId))
                    .Show(toast => { toast.Tag = tag; toast.Group = ProgressGroup; toast.Data = ProgressData(p, 1); });
                return;
            }
            if (DateTime.UtcNow - state.Updated < TimeSpan.FromSeconds(1)) return;
            uint sequence = state.Sequence + 1;
            ProgressToasts[p.TransferId] = (DateTime.UtcNow, sequence);
            ToastNotificationManagerCompat.CreateToastNotifier().Update(ProgressData(p, sequence), tag, ProgressGroup);
        }
        catch (Exception e) { Debug.WriteLine(e); }
    }
    private static Windows.UI.Notifications.NotificationData ProgressData(TransferProgress p, uint sequence)
    {
        var data = new Windows.UI.Notifications.NotificationData { SequenceNumber = sequence };
        data.Values["file"] = p.FileCount == 1 ? p.File : $"{p.File} ({p.FileNumber} of {p.FileCount})";
        data.Values["value"] = p.Percent < 0 ? "indeterminate" : (p.Percent / 100.0).ToString(System.Globalization.CultureInfo.InvariantCulture);
        data.Values["amount"] = p.Percent < 0 ? Fmt.Size(p.BytesDone) : $"{p.Percent}%";
        data.Values["status"] = p.Reconnecting ? $"Connection dropped · waiting for {p.PeerName} to reconnect…"
            : $"{Fmt.Amount(p.BytesDone, p.TotalBytes)} · {Fmt.Speed(p.BytesPerSecond)}{(p.Eta is null ? "" : $" · {Fmt.Duration(p.Eta.Value)} left")}";
        return data;
    }
    private static void ClearProgressToast(Guid id)
    {
        if (!ProgressToasts.Remove(id)) return;
        try { ToastNotificationManagerCompat.History.Remove(id.ToString("N")[..16], ProgressGroup); } catch (Exception e) { Debug.WriteLine(e); }
    }

    public static string ExplainError(string? code, string name) => code switch
    {
        "declined" => $"{name} didn't accept the files.",
        "disconnected" => $"{name} disconnected and didn't come back. Send again to get the rest.",
        "not_paired" => $"{name} doesn't know this PC any more. Pair again.",
        "identity_mismatch" => "This isn't the device you paired with. Pair again.",
        "bad_token" => "That QR code can't be used. Show a new one and scan it again.",
        "token_expired" => "That QR code expired. Show a new one and scan it again.",
        "busy" => $"{name} is busy pairing. Try again shortly.",
        "rejected" or "user_declined" => "Pairing was cancelled.",
        "cancelled" => "The transfer was cancelled.",
        "no_space" => $"{name} doesn't have enough free space.",
        "checksum_mismatch" => "A file changed during the transfer. Try sending it again.",
        "io_error" => "The transfer stopped while reading or saving a file.",
        "protocol_error" => $"HopDrop couldn't understand the response from {name}. Update HopDrop on both devices.",
        "unsupported_version" => "Update HopDrop on both devices, then try again.",
        "timeout" => $"{name} stopped responding. Check that it's still on the same network.",
        _ => "Something went wrong. Please try again."
    };
    public static string ExplainException(Exception error, string name)
    {
        if (error is HopDropException protocol) return ExplainError(protocol.Code, name);
        if (error is TimeoutException or System.Net.Sockets.SocketException)
            return $"Can't reach {name}. Make sure HopDrop is open on it and both devices are on the same Wi-Fi or hotspot.";
        if (error is System.Security.Authentication.AuthenticationException)
            return $"Couldn't make a secure connection to {name}.";
        if (error is IOException or UnauthorizedAccessException)
            return "HopDrop couldn't read or save a file. Check its permission and free space.";
        return "Something went wrong. Please try again.";
    }

    /// <summary>File types that run code when opened. From devices that aren't trusted, the "files arrived" toast offers no one-click Open for these.</summary>
    private static readonly HashSet<string> Runnable = new(StringComparer.OrdinalIgnoreCase)
    { ".exe", ".msi", ".msix", ".msixbundle", ".appx", ".appxbundle", ".bat", ".cmd", ".com", ".scr", ".pif", ".cpl", ".msc", ".ps1", ".psm1",
      ".vbs", ".vbe", ".js", ".jse", ".wsf", ".wsh", ".hta", ".lnk", ".url", ".reg", ".jar", ".dll", ".sys", ".iso", ".img", ".vhd", ".vhdx", ".application", ".appref-ms" };
    /// <param name="root">The receive folder, so a received folder is named once ("Photos folder") instead of file by file.</param>
    public static void TransferToast(TransferResult result, string name, string? root = null)
    {
        ClearProgressToast(result.TransferId);
        bool ok = result.ErrorCode is null;
        int count = result.SavedPaths.Count;
        string title = ok
            ? $"{(result.Incoming ? "Received" : "Sent")} {Fmt.Files(count)} {(result.Incoming ? "from" : "to")} {name}"
            : result.ErrorCode == "cancelled" ? $"{(result.Incoming ? "Receiving from" : "Sending to")} {name} was cancelled"
            : result.Incoming ? $"Receiving from {name} stopped" : $"Couldn't finish sending to {name}";
        string line = ok ? $"{Fmt.Names(result.SavedPaths, result.Incoming ? root : null)} · {Fmt.Size(result.Bytes)}" : ExplainError(result.ErrorCode, name);
        string where = ok
            ? $"Saved in {result.ReceiveFolderDisplayName}{(result.Incoming ? "" : " on " + name)}"
            : $"{count} of {result.Offered} files {(result.Incoming ? "were saved" : "arrived")}";
        try
        {
            var toast = new ToastContentBuilder().AddText(title).AddText(line).AddText(where);
            if (result.Incoming && count > 0)
            {
                string first = result.SavedPaths[0], folder = CommonFolder(result.SavedPaths);
                // Clicking the notification itself shows the files in their folder.
                toast.AddArgument("action", count == 1 ? "select:" + first : "folder:" + folder);
                if (count == 1 && (result.Trusted || !Runnable.Contains(Path.GetExtension(first))))
                    toast.AddButton(new ToastButton().SetContent("Open").AddArgument("action", "file:" + first));
                toast.AddButton(new ToastButton().SetContent("Open folder").AddArgument("action", count == 1 ? "select:" + first : "folder:" + folder));
            }
            toast.Show();
        }
        catch (Exception e) { Debug.WriteLine(e); }
    }
    /// <summary>The deepest folder holding all these files: for a received folder, the folder itself.</summary>
    private static string CommonFolder(IReadOnlyList<string> paths)
    {
        string folder = Path.GetDirectoryName(paths[0])!;
        while (paths.Any(p => !p.StartsWith(folder + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) && Path.GetDirectoryName(folder) is { } parent) folder = parent;
        return folder;
    }
    /// <summary>"Ask before receiving": stays on screen until answered; it expires with the offer.</summary>
    public static void OfferToast(IncomingOffer offer)
    {
        try
        {
            string size = offer.TotalBytes >= 0 ? " · " + Fmt.Size(offer.TotalBytes) : "";
            new ToastContentBuilder()
                .AddText($"{offer.PeerName} wants to send you {Fmt.Files(offer.Files.Count)}")
                .AddText(Fmt.Names(offer.Files) + size)
                .AddText("Accept to save them in your receive folder.")
                .AddArgument("action", "open")
                .AddButton(new ToastButton().SetContent("Accept").AddArgument("action", "accept:" + offer.TransferId))
                .AddButton(new ToastButton().SetContent("Decline").AddArgument("action", "decline:" + offer.TransferId))
                .SetToastScenario(ToastScenario.Reminder)
                .Show(toast => { toast.Tag = OfferTag(offer.TransferId); toast.Group = "offers"; toast.ExpirationTime = offer.Deadline; });
        }
        catch (Exception e) { Debug.WriteLine(e); }
    }
    public static void ClearOfferToast(Guid id)
    {
        try { ToastNotificationManagerCompat.History.Remove(OfferTag(id), "offers"); } catch (Exception e) { Debug.WriteLine(e); }
    }
    private static string OfferTag(Guid id) => "o" + id.ToString("N")[..15];
    public static void BluetoothToast(IReadOnlyList<string> files, string folderLabel)
    {
        try
        {
            var toast = new ToastContentBuilder().AddText($"Received {Fmt.Files(files.Count)} via Bluetooth").AddText(Fmt.Names(files)).AddText("Saved in " + folderLabel);
            if (files.Count == 1) toast.AddButton(new ToastButton().SetContent("Open").AddArgument("action", "file:" + files[0]));
            toast.AddButton(new ToastButton().SetContent("Show in folder").AddArgument("action", "select:" + files[0]));
            toast.Show();
        }
        catch (Exception e) { Debug.WriteLine(e); }
    }
}
