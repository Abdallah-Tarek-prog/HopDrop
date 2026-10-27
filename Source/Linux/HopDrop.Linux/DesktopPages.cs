using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Platform.Storage;
using Avalonia.Styling;
using Avalonia.Threading;
using HopDrop.Core;
using HopDrop.Linux.Platform;

namespace HopDrop.Linux;

internal sealed partial class MainWindow
{
    private sealed record HotspotCredentials(string Ssid, string Key);
    private readonly JsonStore<HotspotCredentials?> _hotspotCredentials;
    private readonly OutgoingQueue _queue;
    private readonly DesktopIntegration _integration;
    private readonly string _dataDirectory;
    private readonly LinuxHotspot _hotspot = new();
    private readonly LinuxBluetooth _bluetooth = new();
    private readonly DispatcherTimer _services = new() { Interval = TimeSpan.FromSeconds(1) };
    private string? _ownClipboard, _lastClipboard;
    private bool _clipboardArmed, _polling, _radioBusy;
    private int _serviceTicks;
    internal bool AgentsEnabled => _saved.Agents;
    private static bool CanWatchClipboard => string.IsNullOrEmpty(Environment.GetEnvironmentVariable("WAYLAND_DISPLAY")) &&
        !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("DISPLAY"));

    internal void ShowPage(string page)
    {
        _page = page; BuildShell(); Show(); WindowState = WindowState.Normal; Activate();
    }
    internal async Task HandleLaunchAsync(string[] args)
    {
        try
        {
            int send = Array.IndexOf(args, "--send");
            if (send >= 0)
            {
                _paths.AddRange(args.Skip(send + 1).Where(p => File.Exists(p) || Directory.Exists(p)).Select(Path.GetFullPath));
                ShowPage("send");
            }
            int pair = Array.IndexOf(args, "--pair");
            if (pair >= 0 && pair + 1 < args.Length)
            { ShowPage("devices"); await _peer.PairQrAsync(args[pair + 1]); RenderPage(); }
        }
        catch (Exception error) { await Alert(L.Get("error"), L.Error(error)); }
    }
    private void ApplyTheme()
    {
        if (Application.Current is not { } app) return;
        app.RequestedThemeVariant = _saved.Theme switch { "light" => ThemeVariant.Light, "dark" => ThemeVariant.Dark, _ => ThemeVariant.Default };
    }
    private void StartServices()
    {
        _services.Tick += async (_, _) =>
        {
            if (_polling) return;
            _polling = true;
            try
            {
                if (++_serviceTicks % 5 == 0 && _peer.DirectLink is not null && !_radioBusy)
                {
                    if (await _hotspot.RefreshAsync() is null) { _peer.DirectLink = null; await _hotspot.StopAsync(); SetStatus(L.Get("hotspot_stopped")); }
                }
                if (!_saved.ShareCopiedText || !CanWatchClipboard || Clipboard is null) { _clipboardArmed = false; return; }
                var formats = await Clipboard.GetDataFormatsAsync();
                bool privateContent = formats.Any(f => IsPrivateClipboardFormat(f.Identifier));
                if (privateContent) return;
                string? text = await Clipboard.TryGetTextAsync();
                if (!_clipboardArmed) { _lastClipboard = text; _clipboardArmed = true; return; }
                if (!ClipboardSharePolicy.ShouldShare(true, text, _ownClipboard, _lastClipboard, false)) return;
                _lastClipboard = text;
                var devices = await _peer.DevicesAsync();
                await Task.WhenAll(devices.Where(d => d.Platform == "android" && _peer.Nearby.Any(n => n.Id == d.Id)).Select(async device =>
                {
                    try { await _peer.SendTextAsync(device.Id, text!, clipboard: true); }
                    catch (Exception error) { Debug.WriteLine(error); }
                }));
            }
            catch (Exception error) { Debug.WriteLine(error); }
            finally { _polling = false; }
        };
        _services.Start();
    }
    internal static bool IsPrivateClipboardFormat(string identifier) =>
        identifier.Contains("password", StringComparison.OrdinalIgnoreCase) ||
        identifier.Contains("ExcludeClipboardContentFromMonitorProcessing", StringComparison.OrdinalIgnoreCase) ||
        identifier.Contains("Clipboard Viewer Ignore", StringComparison.OrdinalIgnoreCase) ||
        identifier.Contains("org.nspasteboard.ConcealedType", StringComparison.OrdinalIgnoreCase);
    internal async Task StopServicesAsync()
    {
        _services.Stop(); _peer.DirectLink = null;
        await _hotspot.StopAsync();
    }
    private void AddQueue(Panel host)
    {
        if (_queue.Entries.Count == 0) return;
        host.Children.Add(Line()); Section(host, L.Get("queue_title"), L.Get("queue_note"));
        Run(async () =>
        {
            var devices = await _peer.DevicesAsync();
            foreach (var entry in _queue.Entries)
            {
                var row = Column();
                string name = devices.FirstOrDefault(d => d.Id == entry.DeviceId) is { } device ? device.Alias ?? device.Name : entry.DeviceId[..8];
                row.Children.Add(Text(L.Get("queue_item", name, entry.Files is { } files ? L.Files(files.Count) : L.Get("text")), 14, true));
                row.Children.Add(Text(L.Get(_queue.IsSending(entry.Id) ? "progress" : entry.Interrupted ? "queue_interrupted" : "queued"), 12, color: Muted));
                var actions = Row();
                actions.Children.Add(Action(L.Get("cancel"), () => Run(() => _queue.CancelAsync(entry.Id))));
                if (entry.Interrupted && !_queue.IsSending(entry.Id)) actions.Children.Add(Action(L.Get("retry"), () => Run(async () =>
                {
                    if (await Confirm(L.Get("retry"), L.Get("retry_note"))) await _queue.RetryInterruptedAsync(entry.Id);
                })));
                row.Children.Add(actions); host.Children.Add(row);
            }
        });
    }
    private void ShowTransfers()
    {
        var dialog = Dialog(L.Get("progress"), 530, 430);
        var list = Column();
        void Refresh()
        {
            list.Children.Clear();
            foreach (var progress in _progress.Values.ToArray())
            {
                list.Children.Add(Text(progress.PeerName + " · " + progress.File, 14, true));
                list.Children.Add(new ProgressBar { IsIndeterminate = progress.Percent < 0, Value = Math.Max(0, progress.Percent), Height = 8 });
                string state = progress.WaitingForApproval ? L.Get("waiting") : progress.Reconnecting ? L.Get("reconnecting") :
                    L.Get("transfer_stats", progress.FileNumber, progress.FileCount, (progress.BytesPerSecond / 1024).ToString("N0", L.Culture));
                list.Children.Add(Text(state, 12, color: Muted));
                list.Children.Add(Action(L.Get("cancel"), () => _peer.CancelTransfer(progress.TransferId)));
            }
            if (_progress.Count == 0) list.Children.Add(Text(L.Get("done")));
        }
        var timer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(400) };
        timer.Tick += (_, _) => Refresh(); dialog.Closed += (_, _) => timer.Stop();
        dialog.Content = new Border { Padding = new Thickness(24), Child = new ScrollViewer { Content = list } };
        Refresh(); timer.Start(); dialog.Show(this);
    }
    private void Preference(Panel host, string key, bool current, Func<LinuxPreferences, bool, LinuxPreferences> change,
        Func<bool, Task>? apply = null)
    {
        var check = new CheckBox { Content = L.Get(key), IsChecked = current };
        check.Click += (_, _) => Run(async () =>
        {
            bool enabled = check.IsChecked == true;
            try
            {
                if (apply is not null) await apply(enabled);
                _saved = await _preferences.UpdateAsync(p => change(p, enabled));
            }
            catch { check.IsChecked = current; throw; }
        });
        host.Children.Add(check);
    }
    private void AddSettings(Panel host)
    {
        host.Children.Add(Line()); Section(host, L.Get("appearance"));
        var themes = new ComboBox { Width = 190, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (string value in new[] { "system", "light", "dark" })
        {
            var item = new ComboBoxItem { Content = L.Get(value), Tag = value }; themes.Items.Add(item);
            if (value == _saved.Theme) themes.SelectedItem = item;
        }
        themes.SelectionChanged += (_, _) =>
        {
            if (themes.SelectedItem is ComboBoxItem { Tag: string value } && value != _saved.Theme) Run(async () =>
            { _saved = await _preferences.UpdateAsync(p => p with { Theme = value }); ApplyTheme(); BuildShell(); });
        };
        host.Children.Add(themes);
        host.Children.Add(Line()); Section(host, L.Get("startup"));
        Preference(host, "start_session", _saved.StartWithSession, (p, v) => p with { StartWithSession = v }, value =>
        { _integration.SetAutostart(value || _saved.BackgroundReceive, !value); return Task.CompletedTask; });
        Preference(host, "background_receive", _saved.BackgroundReceive, (p, v) => p with { BackgroundReceive = v }, value =>
        { _integration.SetAutostart(value || _saved.StartWithSession, !_saved.StartWithSession); return Task.CompletedTask; });
        host.Children.Add(Text(L.Get("background_note"), 12, color: Muted));
        Preference(host, "file_manager", _saved.FileManagerIntegration, (p, v) => p with { FileManagerIntegration = v }, value =>
        { _integration.SetFileManager(value); return Task.CompletedTask; });
        host.Children.Add(Text(L.Get("file_manager_note"), 12, color: Muted));
        host.Children.Add(Line()); Section(host, L.Get("clipboard_title"));
        Preference(host, "clipboard_receive", _saved.PutReceivedTextOnClipboard, (p, v) => p with { PutReceivedTextOnClipboard = v });
        if (CanWatchClipboard)
        {
            Preference(host, "clipboard_share", _saved.ShareCopiedText, (p, v) => p with { ShareCopiedText = v }, async enabled =>
            {
                if (enabled && !await Confirm(L.Get("clipboard_title"), L.Get("clipboard_warning"))) throw new HopDropException("cancelled", "Cancelled");
                _clipboardArmed = false;
            });
        }
        host.Children.Add(Text(L.Get(CanWatchClipboard ? "clipboard_warning" : "clipboard_wayland"), 12, color: Muted));
        host.Children.Add(Line()); Section(host, L.Get("agents_title"), L.Get("agents_note"));
        Preference(host, "agents_enable", _saved.Agents, (p, v) => p with { Agents = v });
        var command = DesktopIntegration.HostCommand(_dataDirectory);
        string config = JsonSerializer.Serialize(new { mcpServers = new { hopdrop = new { command = command[0], args = command.Skip(1).Append("--mcp").ToArray() } } }, new JsonSerializerOptions { WriteIndented = true });
        host.Children.Add(new TextBox { Text = config, IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, FlowDirection = FlowDirection.LeftToRight, FontSize = 12 });
        host.Children.Add(Action(L.Get("copy_config"), () => Run(async () => { if (Clipboard is not null) { _ownClipboard = config; await Clipboard.SetTextAsync(config); } })));
        host.Children.Add(Line()); AddBackupSettings(host);
        host.Children.Add(Line()); Section(host, L.Get("direct_link"), L.Get("hotspot_note"));
        var hotspotStatus = Text(L.Get("checking_hardware"), 12, color: Muted); host.Children.Add(hotspotStatus);
        Run(async () =>
        {
            var capability = await _hotspot.ProbeAsync();
            hotspotStatus.Text = _peer.DirectLink is not null ? L.Get("hotspot_active") : capability.Available ? L.Get("hotspot_ready") : RadioFailure(capability.Reason);
        });
        host.Children.Add(Action(L.Get(_peer.DirectLink is null ? "hotspot_start" : "hotspot_stop"), () => Run(ToggleHotspotAsync)));
        host.Children.Add(Line()); Section(host, L.Get("bluetooth"), L.Get("bluetooth_note"));
        host.Children.Add(Action(L.Get("bluetooth_receive"), () => Run(async () =>
        {
            var capability = await _bluetooth.ProbeAsync();
            if (!capability.NativeReceiveAvailable) { await Alert(L.Get("bluetooth"), L.Get("bluetooth_receive_unavailable")); return; }
            if (await Confirm(L.Get("bluetooth"), L.Get("bluetooth_receive_note")))
            {
                var opened = _bluetooth.OpenNativeReceiveAgent();
                if (opened.Opened) SetStatus(L.Get("bluetooth_receive_note"));
                else await Alert(L.Get("bluetooth"), RadioFailure(opened.Message));
            }
        })));
        host.Children.Add(Line()); Section(host, L.Get("updates"), L.Get("updates_note"));
        host.Children.Add(Action(L.Get("release_page"), () => OpenUrl("https://github.com/Abdallah-Tarek-prog/HopDrop/releases")));
    }
    private void AddBackupSettings(Panel host)
    {
        Section(host, L.Get("backups"), L.Get("backup_note"));
        host.Children.Add(new TextBlock { Text = _peer.BackupDirectory, FlowDirection = FlowDirection.LeftToRight, FontSize = 12, Foreground = Brush(Muted) });
        var actions = Row();
        actions.Children.Add(Action(L.Get("change"), () => Run(async () =>
        {
            var folders = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions { Title = L.Get("backups") });
            if (folders.Count > 0) { await _peer.SetBackupDirectoryAsync(folders[0].Path.LocalPath); RenderPage(); }
        })));
        actions.Children.Add(Action(L.Get("open"), () => OpenPath(_peer.BackupDirectory))); host.Children.Add(actions);
        Run(async () =>
        {
            foreach (var backup in (await _peer.BackupsAsync()).OrderByDescending(b => b.LastRun))
            {
                var item = Column(); item.Children.Add(Text(L.Get("backup_item", backup.DeviceName, backup.Folder), 14, true));
                item.Children.Add(Text(L.Files(backup.FileCount) + " · " + backup.LastRun.ToLocalTime().ToString("g", L.Culture), 12, color: Muted));
                var buttons = Row();
                string location = backup.Location ?? _peer.BackupDirectory;
                buttons.Children.Add(Action(L.Get("open"), () => OpenPath(location)));
                buttons.Children.Add(Action(L.Get("forget"), () => Run(async () =>
                {
                    if (await Confirm(L.Get("forget"), L.Get("forget_note"))) { await _peer.ForgetBackupAsync(backup.DeviceId, backup.Folder, backup.Location); RenderPage(); }
                })));
                item.Children.Add(buttons); host.Children.Add(item);
            }
        });
    }
    private async Task ToggleHotspotAsync()
    {
        if (_radioBusy) return;
        _radioBusy = true;
        try
        {
            if (_peer.DirectLink is not null)
            {
                _peer.DirectLink = null; string? error = await _hotspot.StopAsync();
                if (error is not null) await Alert(L.Get("error"), RadioFailure(error));
            }
            else
            {
                var capability = await _hotspot.ProbeAsync();
                if (!capability.Available) { await Alert(L.Get("direct_link"), RadioFailure(capability.Reason)); return; }
                if (!await Confirm(L.Get("hotspot_start"), L.Get(capability.DisconnectsCurrentWifi ? "hotspot_disconnect" : "hotspot_confirm"))) return;
                var credentials = await _hotspotCredentials.ReadAsync();
                if (credentials is null || credentials.Ssid != "HopDrop-" + _peer.Id[..6] || credentials.Key.Length != 20)
                {
                    credentials = new("HopDrop-" + _peer.Id[..6], Convert.ToHexString(RandomNumberGenerator.GetBytes(10)).ToLowerInvariant());
                    await _hotspotCredentials.UpdateAsync(_ => credentials);
                }
                if (OperatingSystem.IsLinux()) File.SetUnixFileMode(Path.Combine(_dataDirectory, "hotspot.json"), UnixFileMode.UserRead | UnixFileMode.UserWrite);
                var result = await _hotspot.StartAsync(credentials.Ssid, credentials.Key, capability.DisconnectsCurrentWifi);
                if (!result.Success) { await Alert(L.Get("direct_link"), RadioFailure(result.Error ?? "")); return; }
                _peer.DirectLink = result.Link; ShowPage("devices"); ShowQr();
            }
            RenderPage();
        }
        finally { _radioBusy = false; }
    }
    private static string RadioFailure(string reason) => L.Language == "ar" ? L.Get("radio_unavailable") : reason;
    private async Task SendBluetoothAsync()
    {
        if (_paths.Count == 0 || _paths.Any(p => !File.Exists(p))) { await Alert(L.Get("bluetooth"), L.Get("bluetooth_files_only")); return; }
        var devices = await _bluetooth.ListPairedDevicesAsync();
        if (devices.Error is not null) { await Alert(L.Get("bluetooth"), RadioFailure(devices.Error)); return; }
        if (devices.Devices.Count == 0) { await Alert(L.Get("bluetooth"), L.Get("bluetooth_pair_first")); return; }
        var dialog = Dialog(L.Get("bluetooth_send"), 480, 300);
        var content = Column(); content.Children.Add(Text(L.Get("bluetooth_choose")));
        var picker = new ComboBox { HorizontalAlignment = HorizontalAlignment.Stretch };
        foreach (var device in devices.Devices) picker.Items.Add(new ComboBoxItem { Content = device.Name, Tag = device.Address });
        content.Children.Add(picker); var buttons = Row();
        buttons.Children.Add(Action(L.Get("cancel"), () => dialog.Close(null)));
        buttons.Children.Add(Action(L.Get("send"), () => { if (picker.SelectedItem is ComboBoxItem { Tag: string address }) dialog.Close(address); }, true));
        content.Children.Add(buttons); dialog.Content = new Border { Padding = new Thickness(24), Child = content };
        string? target = await dialog.ShowDialog<string?>(this); if (target is null) return;
        using var cancel = new CancellationTokenSource();
        var progress = Dialog(L.Get("bluetooth_send"), 450, 200); var status = Text(L.Get("progress"));
        var body = Column(); body.Children.Add(status); body.Children.Add(Action(L.Get("cancel"), () => cancel.Cancel()));
        progress.Content = new Border { Padding = new Thickness(24), Child = body };
        progress.Closing += (_, _) => cancel.Cancel(); progress.Show(this);
        var files = _paths.ToArray();
        try
        {
            foreach (string file in files)
            {
                status.Text = Path.GetFileName(file);
                var result = await _bluetooth.SendFileAsync(target, file, stop: cancel.Token);
                if (result.Delivery != BluetoothDelivery.Complete)
                { progress.Close(); await Alert(L.Get("bluetooth"), L.Get(result.Delivery == BluetoothDelivery.Unconfirmed ? "bluetooth_unconfirmed" : "bluetooth_failed")); return; }
                await _peer.RecordBluetoothSentAsync([file]); _paths.Remove(file);
            }
            SetStatus(L.Get("done"));
        }
        finally { progress.Close(); RenderPage(); }
    }
    private async Task ShowTextAsync(string value)
    {
        var dialog = Dialog(L.Get("text"), 520, 360);
        var content = Column();
        content.Children.Add(new TextBox { Text = value, IsReadOnly = true, AcceptsReturn = true, TextWrapping = TextWrapping.Wrap, MaxHeight = 230 });
        var actions = Row();
        actions.Children.Add(Action(L.Get("copy"), () => Run(async () => { if (Clipboard is not null) { _ownClipboard = value; await Clipboard.SetTextAsync(value); } })));
        if (Uri.TryCreate(value.Trim(), UriKind.Absolute, out var uri) && uri.Scheme is "http" or "https")
            actions.Children.Add(Action(L.Get("open_link"), () => OpenUrl(uri.AbsoluteUri)));
        actions.Children.Add(Action(L.Get("confirm"), () => dialog.Close())); content.Children.Add(actions);
        dialog.Content = new Border { Padding = new Thickness(24), Child = content }; await dialog.ShowDialog(this);
    }
    private static void OpenUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme is not ("http" or "https")) return;
        try { Process.Start(new ProcessStartInfo("xdg-open") { UseShellExecute = false, ArgumentList = { uri.AbsoluteUri } }); }
        catch (Exception error) { Debug.WriteLine(error); }
    }
}
