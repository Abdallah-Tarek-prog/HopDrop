using System.Diagnostics;
using System.Windows.Threading;
using HopDrop.Core;
using Wpf.Ui.Controls;

namespace HopDrop.Desktop;

/// <summary>
/// Everything that works without the window: the tray icon, Bluetooth receiving, "Ask before receiving" prompts,
/// notifications, and opening and freeing the window. A closed window is freed after a minute, so HopDrop waiting
/// in the tray costs about as little memory as the hidden background receiver.
/// </summary>
public partial class App
{
    private System.Windows.Forms.NotifyIcon? _tray;
    private BluetoothWizard? _bluetooth;
    private readonly Dictionary<Guid, TransferProgress> _active = [];
    private readonly Dictionary<Guid, IncomingOffer> _offers = [];
    private DispatcherTimer? _release;
    private static readonly TimeSpan ReleaseAfter = TimeSpan.FromSeconds(60);
    internal static App Instance => (App)Current;
    /// <summary>A transfer is running or a question is waiting: not a moment to restart for an update.</summary>
    internal bool Busy => _active.Count > 0 || _offers.Count > 0;
    /// <summary>How to start HopDrop again in the same state (after an update): window, tray only, or hidden.</summary>
    internal static string[] RestartArguments()
    {
        var args = new List<string>();
        if (WindowInstance is not { IsVisible: true }) args.Add(Instance._tray is not null ? "--minimized" : "--background");
        if (!string.Equals(DataDirectory, HopDropPaths.Default.DataDirectory, StringComparison.OrdinalIgnoreCase)) { args.Add("--data"); args.Add(DataDirectory); }
        return [.. args];
    }

    private void AttachPeer(HopDropPeer peer)
    {
        _bluetooth = new BluetoothWizard(() => peer.ReceiveDirectory, files => Dispatcher.InvokeAsync(() => OnBluetoothReceived(files)),
            message => Dispatcher.InvokeAsync(() => Platform.Notify("Bluetooth", message)));
        peer.IncomingPairing += request => Dispatcher.InvokeAsync(async () => (await EnsureWindowAsync()).OnIncomingPairing(request));
        peer.OfferReceived += offer => Dispatcher.InvokeAsync(() => OnOffer(offer));
        peer.PairedDevicesChanged += list => Dispatcher.InvokeAsync(() => WindowInstance?.OnPairedChanged(list));
        peer.NearbyDevicesChanged += _ => Dispatcher.InvokeAsync(() => WindowInstance?.OnNearbyChanged());
        peer.Progress += p => Dispatcher.InvokeAsync(() => OnProgress(p));
        peer.TransferFinished += r => Dispatcher.InvokeAsync(() => OnFinished(r));
        peer.Error += message => Debug.WriteLine(message);
    }

    // ---- Transfers ----
    private void OnProgress(TransferProgress p)
    {
        _active[p.TransferId] = p;
        Updates.Activity();
        UpdateTrayText();
        if (WindowInstance is not null) WindowInstance.OnProgress(p);
        else if (p.Incoming) Platform.ProgressToast(p);
    }
    private void OnFinished(TransferResult r)
    {
        _active.Remove(r.TransferId);
        Updates.Activity();
        UpdateTrayText();
        if (WindowInstance is not null) WindowInstance.OnTransferFinished(r);
        else Platform.TransferToast(r, r.PeerName.Length > 0 ? r.PeerName : "a device", _peer?.ReceiveDirectory);
    }

    // ---- Ask before receiving ----
    private void OnOffer(IncomingOffer offer)
    {
        _offers[offer.TransferId] = offer;
        Platform.OfferToast(offer);
        _ = offer.Closed.ContinueWith(_ => Dispatcher.InvokeAsync(() =>
        {
            _offers.Remove(offer.TransferId);
            Platform.ClearOfferToast(offer.TransferId);
        }), TaskScheduler.Default);
    }

    // ---- Bluetooth ----
    internal void ReceiveBluetooth()
    {
        if (_bluetooth is null) return;
        _bluetooth.Receive();
        Platform.Notify("Waiting for Bluetooth files", $"Send from your phone with Bluetooth and pick this PC. Files are saved in {_peer?.ReceiveFolderLabel}.");
    }
    private async void OnBluetoothReceived(IReadOnlyList<string> files)
    {
        if (_peer is null) return;
        try { await _peer.RecordBluetoothAsync(files); } catch (Exception e) { Debug.WriteLine(e); }
        Platform.BluetoothToast(files, _peer.ReceiveFolderLabel);
        WindowInstance?.OnHistoryChanged();
    }

    // ---- Notification clicks ----
    private void HandleToast(string argument)
    {
        if (argument.StartsWith("action=")) argument = Uri.UnescapeDataString(argument[7..].Split('&')[0]);
        if (argument.StartsWith("accept:") && Guid.TryParse(argument[7..], out var accept)) { if (_offers.TryGetValue(accept, out var offer)) offer.Accept(); }
        else if (argument.StartsWith("decline:") && Guid.TryParse(argument[8..], out var decline)) { if (_offers.TryGetValue(decline, out var offer)) offer.Decline(); }
        else if (argument.StartsWith("cancel:") && Guid.TryParse(argument[7..], out var id)) _peer?.CancelTransfer(id);
        else if (argument.StartsWith("folder:")) Platform.OpenFolder(argument[7..]);
        else if (argument.StartsWith("select:")) Platform.ShowInFolder(argument[7..]);
        else if (argument.StartsWith("file:")) Platform.OpenFile(argument[5..]);
        else _ = OpenAsync([]);
    }

    // ---- Tray ----
    private void ShowTray()
    {
        if (_tray is not null || SnapshotMode) return;
        _tray = new System.Windows.Forms.NotifyIcon { Icon = System.Drawing.Icon.ExtractAssociatedIcon(Environment.ProcessPath!), Visible = true };
        _tray.MouseClick += (_, e) => Dispatcher.Invoke(() =>
        {
            if (e.Button == System.Windows.Forms.MouseButtons.Left) _ = OpenAsync([]);
            else if (e.Button == System.Windows.Forms.MouseButtons.Right) ShowTrayMenu();
        });
        UpdateTrayText();
    }
    /// <summary>The right-click menu (TrayMenu): HopDrop's status on top, then what can be done from the tray.</summary>
    private void ShowTrayMenu() => new TrayMenu(TrayStatus(), TrayMenuItems()).ShowAt(System.Windows.Forms.Cursor.Position);
    internal List<TrayMenu.Item> TrayMenuItems()
    {
        bool keepsReceiving = Preferences.BackgroundReceive;
        var items = new List<TrayMenu.Item>
        {
            new("Open HopDrop", SymbolRegular.Open24, () => _ = OpenAsync([])),
            new("Send files…", SymbolRegular.Send24, () => _ = OpenAsync(["--page", "send", "--browse"])),
            new("Pair a phone", SymbolRegular.QrCode24, () => _ = OpenAsync(["--page", "devices"])),
            new("", default, () => { }, Separator: true),
            new("Receive via Bluetooth", SymbolRegular.Bluetooth24, ReceiveBluetooth),
            new("Send via Bluetooth", SymbolRegular.BluetoothConnected24, BluetoothWizard.Send),
            new("", default, () => { }, Separator: true),
        };
        if (keepsReceiving) items.Add(new("Close HopDrop (keep receiving)", SymbolRegular.Dismiss24, () => Quit(true)));
        items.Add(new(keepsReceiving ? "Quit and stop receiving" : "Quit HopDrop", SymbolRegular.Power24, () => Quit(false)));
        return items;
    }
    private void HideTray()
    {
        if (_tray is null) return;
        _tray.Visible = false; _tray.Dispose(); _tray = null;
    }
    private void UpdateTrayText()
    {
        if (_tray is null) return;
        string text = "HopDrop · " + TrayStatus();
        _tray.Text = text.Length > 63 ? text[..63] : text;
    }
    private string TrayStatus()
    {
        var moving = _active.Values.Where(p => !p.WaitingForApproval).ToList();
        return moving.Count == 0 ? "Ready to receive"
            : string.Join(" · ", moving.Select(p => $"{(p.Incoming ? "Receiving" : "Sending")} {(p.Percent < 0 ? "" : p.Percent + "% ")}{(p.Incoming ? "from" : "to")} {p.PeerName}"));
    }

    // ---- Window ----
    /// <summary>The window, created on demand; the tray icon comes with it.</summary>
    private async Task<MainWindow> EnsureWindowAsync()
    {
        _release?.Stop();
        if (WindowInstance is not null) return WindowInstance;
        ShowTray();
        var devices = await _peer!.DevicesAsync();
        var history = await _peer.HistoryAsync();
        return WindowInstance ??= new MainWindow(_peer, devices, history);
    }
    private async Task OpenAsync(IReadOnlyList<string> args)
    {
        var window = await EnsureWindowAsync();
        window.HandleArguments(args);
    }
    /// <summary>"Close to the tray": hide now; if it stays closed for a minute, free the window's memory.</summary>
    internal void HideToTray()
    {
        WindowInstance?.Hide();
        if (_release is null)
        {
            _release = new DispatcherTimer { Interval = ReleaseAfter };
            _release.Tick += (_, _) => ReleaseWindow();
        }
        _release.Stop(); _release.Start();
    }
    private void ReleaseWindow()
    {
        _release?.Stop();
        var window = WindowInstance;
        if (window is null || window.IsVisible) return;
        WindowInstance = null;
        window.Detach();
        window.Close();
        _ = Dispatcher.InvokeAsync(Platform.TrimMemory, DispatcherPriority.ApplicationIdle);
    }
    /// <summary>Closes the window. With <paramref name="keepReceiving"/> the process stays as a hidden receiver (no tray icon); otherwise HopDrop exits.</summary>
    internal static async void Quit(bool keepReceiving)
    {
        var app = Instance;
        app._release?.Stop();
        var window = WindowInstance;
        WindowInstance = null;
        // Deferred: this can run inside the window's own Closing handler, where Close() throws.
        if (window is not null) { window.Detach(); _ = app.Dispatcher.InvokeAsync(window.Close); }
        app.HideTray();
        if (keepReceiving)
        {
            if (!Preferences.BackgroundNoticeShown)
            {
                Preferences.BackgroundNoticeShown = true;
                Platform.Notify("HopDrop is still receiving", "Phones can keep sending to this PC. To stop, open HopDrop and use Settings or Quit in the tray menu.");
            }
            _ = app.Dispatcher.InvokeAsync(Platform.TrimMemory, DispatcherPriority.ApplicationIdle);
            return;
        }
        app._bluetooth?.Stop();
        foreach (var offer in app._offers.Values.ToList()) offer.Decline();
        if (app._peer is not null) await app._peer.DisposeAsync();
        app.Shutdown();
    }
}
