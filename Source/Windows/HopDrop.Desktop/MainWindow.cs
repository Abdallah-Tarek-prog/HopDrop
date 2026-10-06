using System.Diagnostics;
using System.Net;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using HopDrop.Core;
using Wpf.Ui.Appearance;
using Wpf.Ui.Controls;
using Button = Wpf.Ui.Controls.Button;
using TextBlock = System.Windows.Controls.TextBlock;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Color = System.Windows.Media.Color;
using Size = System.Windows.Size;

namespace HopDrop.Desktop;

public sealed partial class MainWindow : FluentWindow
{
    private readonly HopDropPeer? _peer;
    private readonly NavigationView _navigation;
    /// <summary>What's queued on the Send page: files, and the files of added folders with their place in the folder.</summary>
    private readonly List<SendItem> _files = [];
    private List<PairedDevice> _devices = [];
    private List<HistoryEntry> _history = [];
    private bool _quit;
    internal bool IsDetached => _quit;
    private readonly DispatcherTimer? _tick;
    private readonly ThemeChangedEvent _themeChanged;
    private readonly Action _updatesChanged;
    internal HopPage? CurrentPage { get; set; }

    public MainWindow(HopDropPeer? peer, List<PairedDevice>? devices = null, List<HistoryEntry>? history = null)
    {
        _peer = peer;
        _devices = devices ?? (peer is null ? SampleDevices() : []);
        _history = history ?? [];
        Title = "HopDrop";
        WindowBackdropType = WindowBackdropType.None;
        var work = SystemParameters.WorkArea;
        Width = Math.Min(1120, work.Width * 0.9); Height = Math.Min(800, work.Height * 0.92);
        MinWidth = 760; MinHeight = 540;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        var shell = new Grid();
        shell.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        shell.RowDefinitions.Add(new RowDefinition());
        shell.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        Content = shell;
        var logo = BitmapFrame.Create(new Uri("pack://application:,,,/Assets/HopDrop.ico"));
        Icon = logo;
        shell.Children.Add(new Wpf.Ui.Controls.TitleBar { Title = "HopDrop", Height = 44, Icon = new ImageIcon { Source = logo } });
        _navigation = new NavigationView
        {
            PaneDisplayMode = NavigationViewPaneDisplayMode.Left, IsPaneOpen = true, OpenPaneLength = 220,
            Transition = Wpf.Ui.Animations.Transition.None,
            IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed
        };
        _navigation.MenuItems.Add(new NavigationViewItem("Send", SymbolRegular.Send24, typeof(SendPage)));
        _navigation.MenuItems.Add(new NavigationViewItem("Devices", SymbolRegular.PhoneLaptop24, typeof(DevicesPage)));
        _navigation.MenuItems.Add(new NavigationViewItem("Activity", SymbolRegular.History24, typeof(ActivityPage)));
        _navigation.FooterMenuItems.Add(new NavigationViewItem("Settings", SymbolRegular.Settings24, typeof(SettingsPage)));
        Grid.SetRow(_navigation, 1); shell.Children.Add(_navigation);
        _liveHost = new StackPanel();
        _liveBar = new Border { Child = _liveHost, Padding = new Thickness(16, 10, 16, 12), Visibility = Visibility.Collapsed, BorderThickness = new Thickness(0, 1, 0, 0), ClipToBounds = true };
        _liveBar.SetResourceReference(Border.BackgroundProperty, "LayerFillColorDefaultBrush");
        _liveBar.SetResourceReference(Border.BorderBrushProperty, "CardStrokeColorDefaultBrush");
        Grid.SetRow(_liveBar, 2); shell.Children.Add(_liveBar);
        Motion.AttachWindow(this);
        Loaded += (_, _) =>
        {
            ApplyTheme();
            _navigation.Navigate(typeof(SendPage));
        };
        _themeChanged = (_, _) => Dispatcher.InvokeAsync(() => { ApplyBrandAccent(); RenderCurrent(); RenderLive(); });
        ApplicationThemeManager.Changed += _themeChanged;
        _updatesChanged = () => Dispatcher.InvokeAsync(() => { if (On("settings")) RenderCurrent(); });
        Updates.Changed += _updatesChanged;
        Closing += (_, e) =>
        {
            if (_quit || App.SnapshotMode) return;
            e.Cancel = true;
            if (Preferences.CloseToTray) App.Instance.HideToTray();
            else App.Quit(Preferences.BackgroundReceive);
        };
        if (peer is not null)
        {
            // Peer events, the tray icon and Bluetooth live in App, which also works while no window exists.
            _tick = new DispatcherTimer { Interval = TimeSpan.FromSeconds(5) };
            _tick.Tick += (_, _) => { RenderLive(); OnNearbyChanged(); };
            // On screen: refresh every 5 s and announce often. Hidden: no refreshing, slower announcements, no pairing QR.
            IsVisibleChanged += (_, _) => OnShownOrHidden();
            StateChanged += (_, _) => OnShownOrHidden();
        }
    }
    private void OnShownOrHidden()
    {
        bool shown = IsVisible && WindowState != WindowState.Minimized;
        _peer?.SetForeground(shown);
        _peer?.SetPairingVisible(shown && On("devices"));
        if (shown) { _tick?.Start(); RenderLive(); }
        else { _tick?.Stop(); if (!IsVisible) StopQr(); }
    }

    internal void Navigate(string page)
    {
        _navigation.Navigate(page switch { "devices" => typeof(DevicesPage), "activity" => typeof(ActivityPage), "settings" => typeof(SettingsPage), _ => typeof(SendPage) });
        if (page == "devices") _ = _peer?.QueryNearbyAsync();
    }
    /// <summary>Rebuilds the visible page (after a theme change or a data change that affects its layout).</summary>
    internal void RenderCurrent() { if (CurrentPage is not null) CurrentPage.Content = Build(CurrentPage.Key); }
    internal FrameworkElement Build(string key)
    {
        _peer?.SetPairingVisible(key == "devices" && IsVisible);
        return key switch
        {
            "devices" => BuildDevicesPage(), "activity" => BuildActivityPage(), "settings" => BuildSettingsPage(), _ => BuildSendPage()
        };
    }
    private bool On(string key) => CurrentPage?.Key == key;

    internal void OpenWindow()
    {
        Show(); if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;
        if (On("devices")) RenderCurrent();
        Activate(); Topmost = true; Topmost = false; Focus();
    }
    internal void HandleArguments(IReadOnlyList<string> args)
    {
        int index = args.ToList().IndexOf("--send");
        if (index >= 0)
        {
            AddFiles(args.Skip(index + 1));
            OpenWindow(); Navigate("send");
        }
        else if (args.Contains("--pair")) { OpenWindow(); _qrSuccessName = null; Navigate("devices"); }
        else if (args.Contains("--receive-bluetooth")) ReceiveBluetooth();
        else if (args.Contains("--page"))
        {
            OpenWindow(); Navigate(args[args.ToList().IndexOf("--page") + 1]);
            if (args.Contains("--browse")) Dispatcher.InvokeAsync(Browse, DispatcherPriority.Background);
        }
        else if (!args.Contains("--minimized")) OpenWindow();
    }

    // ---- Theme ----
    internal void ApplyTheme()
    {
        string theme = Preferences.Theme;
        if (theme == "system")
        {
            SystemThemeWatcher.Watch(this, WindowBackdropType.None, updateAccents: false);
            ApplicationThemeManager.ApplySystemTheme(false);
        }
        else
        {
            SystemThemeWatcher.UnWatch(this);
            ApplicationThemeManager.Apply(theme == "dark" ? ApplicationTheme.Dark : ApplicationTheme.Light, WindowBackdropType.None, updateAccent: false);
        }
        ApplyBrandAccent();
        // A solid themed background: Mica is see-through and renders washed out on some PCs and in screenshots.
        SetResourceReference(BackgroundProperty, "ApplicationBackgroundBrush");
    }
    private static void ApplyBrandAccent()
    {
        Color brand = Palette.Brand;
        ApplicationAccentColorManager.Apply(brand, brand, brand, brand);
        var resources = System.Windows.Application.Current.Resources;
        resources["TextOnAccentFillColorPrimary"] = Colors.White;
        resources["NavigationViewSelectionIndicatorForeground"] = new SolidColorBrush(Palette.Dark ? Color.FromRgb(0xFF, 0xB6, 0x93) : brand);
    }

    /// <summary>Lets the window close for good: drops every timer and hook that would keep it alive.</summary>
    internal void Detach()
    {
        _quit = true; StopQr(); _tick?.Stop();
        Motion.StopTree(this);
        _peer?.SetForeground(false); _peer?.SetPairingVisible(false);
        ApplicationThemeManager.Changed -= _themeChanged;
        Updates.Changed -= _updatesChanged;
        try { SystemThemeWatcher.UnWatch(this); } catch (InvalidOperationException) { }
    }

    // ---- Peer events ----
    internal async void OnPairedChanged(IReadOnlyList<PairedDevice> list)
    {
        var added = list.FirstOrDefault(device => _devices.All(old => old.Id != device.Id));
        _devices = list.ToList();
        if (_qrHost is not null && added is not null) { _qrSuccessName = added.Alias ?? added.Name; StopQr(); ShowQrSuccess(); }
        if (_peer is not null) _history = await _peer.HistoryAsync();
        if (On("send")) FillSendDevices();
        if (On("devices")) { FillPaired(); FillNearby(); }
    }
    internal void OnNearbyChanged()
    {
        if (On("devices")) { FillPaired(); FillNearby(); }
        if (On("send")) UpdateSendDevices();
    }
    internal void OnIncomingPairing(PairingRequest request) => Dispatcher.InvokeAsync(() =>
    {
        OpenWindow(); Platform.PairingToast(request.PeerName);
        new NumberMatchDialog(request) { Owner = this }.ShowDialog();
    });
    /// <summary>Activity changed outside a transfer (Bluetooth arrivals).</summary>
    internal async void OnHistoryChanged()
    {
        if (_peer is null) return;
        try { _history = await _peer.HistoryAsync(); } catch (Exception e) { Debug.WriteLine(e); }
        if (On("activity")) RenderCurrent();
    }
    internal void ReceiveBluetooth() => App.Instance.ReceiveBluetooth();

    // ---- Live transfers bar ----
    private sealed class LiveItem
    {
        public required string Key;
        public required string PeerId;
        public required string PeerName;
        public bool Incoming;
        public Guid? TransferId;
        public TransferProgress? Progress;
        public TransferResult? Result;
        public string? Failure;
        public DateTime Updated = DateTime.UtcNow;
        public bool Finished => Result is not null || Failure is not null;
    }
    private sealed class LiveRow
    {
        public required Border Root;
        public required Border Badge;
        public required TextBlock Title, Detail, Amount, Speed;
        public required ProgressBar Bar;
        public required Button Cancel;
        public bool Finished, Removing;
    }
    private readonly Dictionary<string, LiveItem> _live = [];
    private readonly Dictionary<string, LiveRow> _liveRows = [];
    private readonly StackPanel _liveHost;
    private readonly Border _liveBar;
    private double _liveBarHeight;
    private static readonly TimeSpan KeepFinished = TimeSpan.FromSeconds(8);

    internal void OnProgress(TransferProgress p)
    {
        if (!p.Incoming) _live.Remove("pending:" + p.PeerId);
        string key = p.TransferId.ToString();
        if (!_live.TryGetValue(key, out var item))
            _live[key] = item = new LiveItem { Key = key, PeerId = p.PeerId, PeerName = p.PeerName, Incoming = p.Incoming, TransferId = p.TransferId };
        item.Progress = p; item.Updated = DateTime.UtcNow;
        RenderLive();
        if (On("send") && !p.Incoming) UpdateSendDevices();
        if (p.Incoming && (!IsVisible || WindowState == WindowState.Minimized || !IsActive)) Platform.ProgressToast(p);
    }
    internal async void OnTransferFinished(TransferResult r)
    {
        string name = r.PeerName.Length > 0 ? r.PeerName : _devices.FirstOrDefault(d => d.Id == r.PeerId)?.Name ?? "a device";
        if (!r.Incoming) _live.Remove("pending:" + r.PeerId);
        string key = r.TransferId.ToString();
        if (!_live.TryGetValue(key, out var item))
            _live[key] = item = new LiveItem { Key = key, PeerId = r.PeerId, PeerName = name, Incoming = r.Incoming, TransferId = r.TransferId };
        item.Result = r; item.Updated = DateTime.UtcNow;
        RenderLive();
        if (On("send")) UpdateSendDevices();
        if (r.Incoming || !IsActive) Platform.TransferToast(r, name, _peer?.ReceiveDirectory);
        await Task.Delay(150);
        if (_peer is not null) _history = await _peer.HistoryAsync();
        if (On("activity")) RenderCurrent();
    }
    private void Connecting(PairedDevice device)
    {
        _live["pending:" + device.Id] = new LiveItem { Key = "pending:" + device.Id, PeerId = device.Id, PeerName = device.Alias ?? device.Name };
        RenderLive();
    }
    private void FailedToStart(PairedDevice device, string reason)
    {
        _live["pending:" + device.Id] = new LiveItem { Key = "pending:" + device.Id, PeerId = device.Id, PeerName = device.Alias ?? device.Name, Failure = reason };
        RenderLive();
    }
    private LiveItem? Outgoing(string deviceId) =>
        _live.Values.Where(i => !i.Incoming && i.PeerId == deviceId).OrderBy(i => i.Updated).LastOrDefault();

    private void RenderLive()
    {
        var now = DateTime.UtcNow;
        foreach (var stale in _live.Values.Where(i => i.Finished && now - i.Updated > KeepFinished).Select(i => i.Key).ToList()) _live.Remove(stale);
        foreach (var gone in _liveRows.Keys.Except(_live.Keys).ToList())
        {
            var row = _liveRows[gone];
            if (row.Removing) continue;
            row.Removing = true; row.Root.IsHitTestVisible = false;
            Motion.Collapse(row.Root, () => { _liveHost.Children.Remove(row.Root); _liveRows.Remove(gone); });
        }
        foreach (var item in _live.Values.OrderBy(i => i.Updated))
        {
            _liveRows.TryGetValue(item.Key, out var row);
            if (row is { Removing: true }) { Motion.StopTree(row.Root); row = null; }
            if (row is null)
            {
                row = CreateLiveRow(item); _liveRows[item.Key] = row; _liveHost.Children.Add(row.Root);
                Motion.SlideUp(row.Root);
            }
            BindLiveRow(row, item);
        }
        ResizeLiveBar();
    }
    private void ResizeLiveBar()
    {
        double from = _liveBar.Visibility == Visibility.Visible ? _liveBar.ActualHeight : 0;
        double height = 0;
        if (_live.Count > 0)
        {
            _liveBar.Visibility = Visibility.Visible;
            double width = Math.Max(1, (_liveBar.ActualWidth > 0 ? _liveBar.ActualWidth : ActualWidth) - 32);
            foreach (var row in _liveRows.Values.Where(r => !r.Removing))
            {
                row.Root.Child.Measure(new Size(width, double.PositiveInfinity));
                height += row.Root.Child.DesiredSize.Height;
            }
            height += _liveBar.Padding.Top + _liveBar.Padding.Bottom + _liveBar.BorderThickness.Top;
        }
        if (Math.Abs(height - _liveBarHeight) < 0.5) return;
        _liveBarHeight = height;
        Motion.Height(_liveBar, from, height, () =>
        {
            _liveBar.Height = double.NaN;
            if (_live.Count == 0) _liveBar.Visibility = Visibility.Collapsed;
        });
    }
    private LiveRow CreateLiveRow(LiveItem item)
    {
        var grid = new Grid { Margin = new Thickness(0, 4, 0, 4), MaxWidth = 1100 };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var badge = Ui.Badge(SymbolRegular.ArrowUpload24, Palette.OnPrimaryBg, Palette.PrimaryBg, 40);
        grid.Children.Add(badge);
        var middle = new StackPanel { Margin = new Thickness(14, 0, 14, 0), VerticalAlignment = VerticalAlignment.Center };
        var top = new Grid(); top.ColumnDefinitions.Add(new ColumnDefinition()); top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var title = Ui.Label("", 14, true); title.TextTrimming = TextTrimming.CharacterEllipsis; title.TextWrapping = TextWrapping.NoWrap;
        var amount = Ui.Label("", 13); amount.HorizontalAlignment = HorizontalAlignment.Right; Grid.SetColumn(amount, 1);
        top.Children.Add(title); top.Children.Add(amount); middle.Children.Add(top);
        var bar = Ui.Bar(); bar.Margin = new Thickness(0, 6, 0, 4); middle.Children.Add(bar);
        var bottom = new Grid(); bottom.ColumnDefinitions.Add(new ColumnDefinition()); bottom.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var detail = Ui.Subtle(""); detail.TextTrimming = TextTrimming.CharacterEllipsis; detail.TextWrapping = TextWrapping.NoWrap;
        var speed = Ui.Subtle(""); speed.HorizontalAlignment = HorizontalAlignment.Right; Grid.SetColumn(speed, 1);
        bottom.Children.Add(detail); bottom.Children.Add(speed); middle.Children.Add(bottom);
        Grid.SetColumn(middle, 1); grid.Children.Add(middle);
        var cancel = Ui.Button("Cancel", () => { if (item.TransferId is Guid id) _peer?.CancelTransfer(id); }, icon: SymbolRegular.Dismiss24);
        cancel.Margin = new Thickness(0); cancel.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(cancel, 2); grid.Children.Add(cancel);
        return new LiveRow { Root = new Border { Child = grid, ClipToBounds = true }, Badge = badge, Title = title, Detail = detail, Amount = amount, Speed = speed, Bar = bar, Cancel = cancel };
    }
    private static void Restyle(Border badge, SymbolRegular icon, Brush fg, Brush bg)
    {
        badge.Background = bg;
        if (badge.Child is SymbolIcon symbol) { symbol.Symbol = icon; symbol.Foreground = fg; }
    }
    private void BindLiveRow(LiveRow row, LiveItem item)
    {
        bool active = !item.Finished;
        if (item.Finished && !row.Finished) Motion.Pop(row.Badge);
        row.Finished = item.Finished;
        row.Cancel.Visibility = active && item.TransferId is not null ? Visibility.Visible : Visibility.Collapsed;
        row.Bar.Visibility = active ? Visibility.Visible : Visibility.Collapsed;
        row.Speed.Text = ""; row.Amount.Text = "";
        if (item.Failure is not null)
        {
            Restyle(row.Badge, SymbolRegular.ErrorCircle24, Palette.Danger, Palette.DangerBg);
            row.Title.Text = $"Couldn't send to {item.PeerName}"; row.Detail.Text = item.Failure; return;
        }
        if (item.Result is TransferResult r)
        {
            bool ok = r.ErrorCode is null;
            Restyle(row.Badge, ok ? SymbolRegular.CheckmarkCircle24 : SymbolRegular.ErrorCircle24, ok ? Palette.Success : Palette.Danger, ok ? Palette.SuccessBg : Palette.DangerBg);
            row.Title.Text = ok ? $"{(r.Incoming ? "Received" : "Sent")} {Fmt.Files(r.SavedPaths.Count)} {(r.Incoming ? "from" : "to")} {item.PeerName}"
                : $"{(r.Incoming ? "Receiving from" : "Sending to")} {item.PeerName} stopped";
            row.Detail.Text = ok ? $"{Fmt.Names(r.SavedPaths)} · saved in {r.ReceiveFolderDisplayName}" : Platform.ExplainError(r.ErrorCode, item.PeerName);
            row.Amount.Text = ok ? $"{Fmt.Size(r.Bytes)} in {Fmt.Duration(r.Duration)}" : $"{r.SavedPaths.Count} of {r.Offered} files done";
            return;
        }
        Restyle(row.Badge, item.Incoming ? SymbolRegular.ArrowDownload24 : SymbolRegular.ArrowUpload24, Palette.OnPrimaryBg, Palette.PrimaryBg);
        if (item.Progress is not TransferProgress p)
        {
            row.Title.Text = $"Connecting to {item.PeerName}…"; row.Detail.Text = $"Getting ready to send {SelectionSummary()}";
            row.Bar.IsIndeterminate = true; return;
        }
        if (p.Reconnecting)
        {
            row.Title.Text = p.Incoming ? $"Waiting for {item.PeerName} to reconnect…" : $"Reconnecting to {item.PeerName}…";
            row.Detail.Text = "The connection dropped. HopDrop continues where it stopped.";
            row.Bar.IsIndeterminate = true; return;
        }
        if (p.WaitingForApproval)
        {
            row.Title.Text = $"Waiting for {item.PeerName} to accept…";
            row.Detail.Text = $"{Fmt.Files(p.FileCount)} will start sending once they accept on {item.PeerName}.";
            row.Bar.IsIndeterminate = true; return;
        }
        row.Title.Text = $"{(p.Incoming ? "Receiving" : "Sending")} {Fmt.Files(p.FileCount)} {(p.Incoming ? "from" : "to")} {item.PeerName}";
        row.Detail.Text = p.FileCount == 1 ? p.File : $"{p.File} · {p.FileNumber} of {p.FileCount}";
        row.Bar.IsIndeterminate = p.Percent < 0;
        if (p.Percent >= 0) Motion.SmoothValue(row.Bar, p.BytesDone * 100.0 / p.TotalBytes);
        row.Amount.Text = Fmt.Amount(p.BytesDone, p.TotalBytes) + (p.Percent >= 0 ? $"  ·  {p.Percent}%" : "");
        row.Speed.Text = Fmt.Speed(p.BytesPerSecond) + (p.Eta is null ? "" : $" · {Fmt.Duration(p.Eta.Value)} left");
    }

    // ---- Helpers ----
    private static List<PairedDevice> SampleDevices() => [new(new string('b', 64), "Realme 6 Pro", "android", DateTimeOffset.Now.AddDays(-3), ["192.168.1.7"], DateTimeOffset.Now)];
    private static List<HistoryEntry> SampleHistory() =>
    [
        new(DateTimeOffset.Now.AddMinutes(-14), new string('b', 64), "received", "ok", ["Holiday photo.jpg", "Notes.pdf"], "Downloads\\HopDrop"),
        new(DateTimeOffset.Now.AddHours(-2), new string('b', 64), "sent", "ok", ["Project proposal.pdf"], "Download/HopDrop"),
        new(DateTimeOffset.Now.AddHours(-3), new string('b', 64), "sent", "timeout", [], "Download/HopDrop")
    ];
    internal async Task RenderPagesAsync(string folder)
    {
        Directory.CreateDirectory(folder);
        ShowInTaskbar = false; Left = -32000; Top = -32000; Width = 1100; Height = 780; Show();
        await Task.Delay(800);
        // A sample folder and file in the Send list, so its rows show up in the pictures.
        string samples = Path.Combine(folder, "sample");
        Directory.CreateDirectory(Path.Combine(samples, "Holiday photos", "Day 2"));
        File.WriteAllBytes(Path.Combine(samples, "Holiday photos", "beach.jpg"), new byte[2_400_000]);
        File.WriteAllBytes(Path.Combine(samples, "Holiday photos", "Day 2", "boat.jpg"), new byte[3_100_000]);
        File.WriteAllBytes(Path.Combine(samples, "Report.pdf"), new byte[420_000]);
        AddFiles([Path.Combine(samples, "Holiday photos"), Path.Combine(samples, "Report.pdf")]);
        _live["sample"] = new LiveItem { Key = "sample", PeerId = new string('b', 64), PeerName = "Realme 6 Pro", Incoming = true, TransferId = Guid.NewGuid(),
            Progress = new(Guid.NewGuid(), new string('b', 64), "Realme 6 Pro", true, "VID_20260929.mp4", 2, 3, 432_013_312, 1_717_986_918, 12_400_000, TimeSpan.FromSeconds(104)) };
        foreach (var theme in new[] { "light", "dark" })
        {
            Preferences.Theme = theme; ApplyTheme(); await Task.Delay(300);
            RenderLive();
            foreach (string page in new[] { "send", "devices", "activity", "settings" })
            {
                Navigate(page); await Task.Delay(700); RenderCurrent(); await Task.Delay(300);
                SaveVisual(this, Path.Combine(folder, $"{page}-{theme}.png"));
                if (page == "settings" && Descendants<TextBlock>(this).FirstOrDefault(t => t.Text == "AI agents") is { } agents)
                {
                    DependencyObject? up = agents;
                    while (up is not null and not ScrollViewer) up = VisualTreeHelper.GetParent(up);
                    if (up is ScrollViewer scroller) scroller.ScrollToVerticalOffset(scroller.VerticalOffset + agents.TranslatePoint(new Point(0, 0), scroller).Y - 16);
                    await Task.Delay(400);
                    SaveVisual(this, Path.Combine(folder, $"settings-agents-{theme}.png"));
                }
            }
            var sample = new NumberMatchDialog(new PairingRequest("Realme 6 Pro", "428962")) { Owner = this, Left = -32000, Top = -32000, ShowInTaskbar = false };
            sample.Show(); await Task.Delay(300);
            SaveVisual(sample, Path.Combine(folder, $"number-match-{theme}.png")); sample.Close();
            var note = new TextNoteDialog { Owner = this, Left = -32000, Top = -32000, ShowInTaskbar = false };
            note.Show(); await Task.Delay(300);
            SaveVisual(note, Path.Combine(folder, $"text-dialog-{theme}.png")); note.Close();
            var menu = new TrayMenu("Ready to receive", App.Instance.TrayMenuItems()) { Left = -32000, Top = -32000, ShowActivated = false };
            menu.Show(); await Task.Delay(300);
            SaveVisual(menu, Path.Combine(folder, $"tray-menu-{theme}.png")); menu.Close();
        }
        Close();
    }
    private static IEnumerable<T> Descendants<T>(DependencyObject parent) where T : DependencyObject
    {
        for (int i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T match) yield return match;
            foreach (var deeper in Descendants<T>(child)) yield return deeper;
        }
    }
    private static void SaveVisual(FrameworkElement visual, string path)
    {
        visual.UpdateLayout();
        int width = Math.Max(1, (int)visual.ActualWidth), height = Math.Max(1, (int)visual.ActualHeight);
        var bitmap = new RenderTargetBitmap(width, height, 96, 96, PixelFormats.Pbgra32); bitmap.Render(visual);
        var encoder = new PngBitmapEncoder(); encoder.Frames.Add(BitmapFrame.Create(bitmap));
        using var file = File.Create(path); encoder.Save(file);
    }
    private void ShowError(string message) => System.Windows.MessageBox.Show(this, message, "HopDrop", System.Windows.MessageBoxButton.OK, MessageBoxImage.Information);
    private string? Prompt(string title, string description, string initial)
    {
        var dialog = new InputDialog(title, description, initial) { Owner = this };
        return dialog.ShowDialog() == true ? dialog.Value : null;
    }
}

/// <summary>A navigation page whose content is (re)built from the window every time it is shown.</summary>
public abstract class HopPage : Page
{
    public string Key { get; }
    protected HopPage(string key)
    {
        Key = key;
        Loaded += (_, _) =>
        {
            var window = App.WindowInstance!; window.CurrentPage = this;
            // No fade or slide: switching pages should feel instant, not flash.
            Content = window.Build(key);
        };
    }
}
public sealed class SendPage() : HopPage("send");
public sealed class DevicesPage() : HopPage("devices");
public sealed class ActivityPage() : HopPage("activity");
public sealed class SettingsPage() : HopPage("settings");
