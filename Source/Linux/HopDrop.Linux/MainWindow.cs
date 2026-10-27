using System.Diagnostics;
using System.Net;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.Primitives;
using Avalonia.Input;
using Avalonia.Input.Platform;
using Avalonia.Layout;
using Avalonia.Media;
using Avalonia.Media.Imaging;
using Avalonia.Platform.Storage;
using Avalonia.Threading;
using HopDrop.Core;
using QRCoder;

namespace HopDrop.Linux;

internal sealed partial class MainWindow : Window
{
    private readonly HopDropPeer _peer;
    private readonly JsonStore<LinuxPreferences> _preferences;
    private volatile LinuxPreferences _saved;
    private string _page = "send";
    private readonly List<string> _paths = [];
    private readonly Dictionary<Guid, TransferProgress> _progress = [];
    private readonly HashSet<string> _selectedDevices = [];
    private TextBox? _message;
    private Border? _pageHost;
    private ScrollViewer? _scroll;
    private TextBlock? _status;
    private StackPanel? _transferHost;
    private readonly Bitmap _logo;
    private static bool Dark => Application.Current?.ActualThemeVariant == Avalonia.Styling.ThemeVariant.Dark;
    // Dark values match HopDrop.Desktop.Palette so both desktop apps keep the same visual identity.
    private static Color Orange => Color.Parse(Dark ? "#FF7A2E" : "#C2410C");
    private static Color Ink => Color.Parse(Dark ? "#F3F5F8" : "#2D2926");
    private static Color Muted => Color.Parse(Dark ? "#B9C1CF" : "#68615B");
    private static Color Paper => Color.Parse(Dark ? "#202020" : "#FAF8F4");
    private static Color Rule => Color.Parse(Dark ? "#2D3542" : "#E8E1D9");
    private static Color Surface => Color.Parse(Dark ? "#2B2B2B" : "#F1ECE5");
    private static Color Accent => Color.Parse(Dark ? "#3E2216" : "#FCE3D6");
    private static Color OnAccent => Color.Parse(Dark ? "#24150D" : "#FFFFFF");

    public MainWindow(HopDropPeer peer, JsonStore<LinuxPreferences> preferences, LinuxPreferences saved,
        OutgoingQueue queue, DesktopIntegration integration, string dataDirectory, bool preview)
    {
        _peer = peer; _preferences = preferences; _saved = saved;
        _queue = queue; _integration = integration; _dataDirectory = dataDirectory;
        _hotspotCredentials = new(Path.Combine(dataDirectory, "hotspot.json"), null);
        ApplyTheme();
        Title = "HopDrop"; Width = 1020; Height = 700; MinWidth = 760; MinHeight = 560;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        Icon = new WindowIcon(Avalonia.Platform.AssetLoader.Open(new Uri("avares://HopDrop.Linux/Assets/HopDrop.ico")));
        using (var logo = Avalonia.Platform.AssetLoader.Open(new Uri("avares://HopDrop.Linux/Assets/HopDrop.png")))
            _logo = new Bitmap(logo);
        Closed += (_, _) => _logo.Dispose();
        Background = Brush(Paper);
        Closing += (_, e) =>
        {
            if ((_saved.CloseToTray || _saved.BackgroundReceive) && !App.Quitting) { e.Cancel = true; Hide(); _peer.SetForeground(false); }
            else if (!App.Quitting) { e.Cancel = true; Dispatcher.UIThread.Post(App.Quit); }
        };
        Opened += (_, _) => _peer.SetForeground(true);
        Activated += (_, _) => _peer.SetForeground(true);
        _peer.NearbyDevicesChanged += _ => Dispatcher.UIThread.Post(() => { if (_page is "devices" or "send") RenderPage(); });
        _peer.PairedDevicesChanged += _ => Dispatcher.UIThread.Post(() => RenderPage());
        _peer.HistoryChanged += () => Dispatcher.UIThread.Post(() => { if (_page == "activity") RenderPage(); });
        _peer.Progress += p => Dispatcher.UIThread.Post(() => { _progress[p.TransferId] = p; RenderProgress(); });
        _peer.TransferFinished += r => Dispatcher.UIThread.Post(() =>
        {
            _progress.Remove(r.TransferId); RenderProgress();
            if (_queue.TakeSuppressed(r.TransferId)) { SetStatus(L.Get("queued")); return; }
            SetStatus(r.ErrorCode is null ? L.Get("done") : L.Error(r.ErrorCode));
            Notify(r.ErrorCode is null ? L.Get("done") : L.Get("error"), r.PeerName);
            if (_page == "activity") RenderPage();
        });
        _peer.IncomingPairing += request => Dispatcher.UIThread.Post(async () =>
        {
            if (await Confirm(L.Get("pair_request"), request.PeerName + "\n\n" + L.Get("match_code") + "\n" + request.Code)) request.Match();
            else request.Cancel();
        });
        _peer.OfferReceived += offer => Dispatcher.UIThread.Post(async () =>
        {
            string files = L.Files(offer.Files.Count);
            if (await Confirm(L.Get("offer_title"), L.Get("offer_message", offer.PeerName, files) + "\n" + string.Join("\n", offer.Files.Take(5)), offer.Closed)) offer.Accept();
            else offer.Decline();
        });
        _peer.TextReceived += message => Dispatcher.UIThread.Post(async () =>
        {
            if (message.Clipboard && _saved.PutReceivedTextOnClipboard && Clipboard is not null)
            { _ownClipboard = message.Text; await Clipboard.SetTextAsync(message.Text); }
            Notify(L.Get("text_received", message.PeerName), message.Text.Length > 100 ? message.Text[..100] : message.Text);
            SetStatus(L.Get("text_received", message.PeerName));
            if (_page == "activity") RenderPage();
        });
        _peer.Error += error => Dispatcher.UIThread.Post(() => SetStatus(L.Get("error_network")));
        _queue.Changed += () => Dispatcher.UIThread.Post(() => { if (_page == "send") RenderPage(); });
        _queue.Failed += code => Dispatcher.UIThread.Post(() => SetStatus(L.Error(code)));
        ActualThemeVariantChanged += (_, _) => { Background = Brush(Paper); BuildShell(); };
        BuildShell();
        if (!preview) StartServices();
    }

    private static SolidColorBrush Brush(Color color) => new(color);
    private static TextBlock Text(string value, int size = 14, bool bold = false, Color? color = null) => new()
    {
        Text = value, FontSize = size, FontWeight = bold ? FontWeight.SemiBold : FontWeight.Normal,
        Foreground = Brush(color ?? Ink), TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center
    };
    private static Button Action(string label, Action click, bool primary = false)
    {
        var button = new Button { Content = label, Padding = new Thickness(16, 8), Margin = new Thickness(0, 0, 8, 0),
            Background = primary ? Brush(Orange) : Brushes.Transparent, Foreground = primary ? Brush(OnAccent) : Brush(Ink),
            BorderBrush = primary ? Brush(Orange) : Brush(Rule), BorderThickness = new Thickness(1), MinHeight = 38 };
        button.Click += (_, _) => click();
        return button;
    }
    private static Border Line() => new() { Height = 1, Background = Brush(Rule), Margin = new Thickness(0, 12) };
    private static StackPanel Column() => new() { Spacing = 10 };
    private static StackPanel Row() => new() { Orientation = Orientation.Horizontal, Spacing = 8 };
    private static void Section(Panel host, string heading, string? detail = null)
    {
        host.Children.Add(Text(heading, 21, true));
        if (detail is not null) host.Children.Add(Text(detail, 13, color: Muted));
    }
    private void BuildShell()
    {
        FlowDirection = L.Language == "ar" ? FlowDirection.RightToLeft : FlowDirection.LeftToRight;
        var grid = new Grid { ColumnDefinitions = new ColumnDefinitions("188,*"), RowDefinitions = new RowDefinitions("*,Auto") };
        Background = Brush(Paper);
        var sidebar = new StackPanel { Background = Brush(Surface), Spacing = 5, Margin = new Thickness(0) };
        var brand = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 10, Margin = new Thickness(20, 28, 12, 30) };
        brand.Children.Add(new Image { Width = 32, Height = 32, Source = _logo, Stretch = Stretch.Uniform, FlowDirection = FlowDirection.LeftToRight });
        brand.Children.Add(Text("HopDrop", 22, true));
        sidebar.Children.Add(brand);
        foreach (string page in new[] { "send", "devices", "activity", "settings" })
        {
            string selected = page;
            var button = new Button { Content = L.Get(page), HorizontalContentAlignment = HorizontalAlignment.Stretch,
                Background = selected == _page ? Brush(Accent) : Brushes.Transparent,
                Foreground = selected == _page ? Brush(Orange) : Brush(Ink), BorderThickness = new Thickness(0),
                FontSize = 15, FontWeight = selected == _page ? FontWeight.SemiBold : FontWeight.Normal,
                Padding = new Thickness(20, 12), Margin = new Thickness(8, 0), CornerRadius = new CornerRadius(6) };
            button.Click += (_, _) => { _page = selected; BuildShell(); };
            sidebar.Children.Add(button);
        }
        sidebar.Children.Add(new Border { Height = 22 });
        sidebar.Children.Add(new Border { Margin = new Thickness(18), Child = Text(L.Get("capability"), 11, color: Muted) });
        Grid.SetColumn(sidebar, 0); Grid.SetRow(sidebar, 0); grid.Children.Add(sidebar);
        var main = new Grid { Margin = new Thickness(36, 28, 36, 24), RowDefinitions = new RowDefinitions("Auto,Auto,*") };
        var top = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto") };
        top.Children.Add(Text(L.Get(_page), 30, true));
        top.Children.Add(new Border { Child = Text(L.Get("ready"), 12, true, Orange), Background = Brush(Accent), Padding = new Thickness(12, 7), CornerRadius = new CornerRadius(14), [Grid.ColumnProperty] = 1 });
        main.Children.Add(top);
        var separator = Line(); Grid.SetRow(separator, 1); main.Children.Add(separator);
        _pageHost = new Border();
        _scroll = new ScrollViewer { Content = _pageHost, VerticalScrollBarVisibility = ScrollBarVisibility.Auto };
        Grid.SetRow(_scroll, 2); main.Children.Add(_scroll);
        Grid.SetColumn(main, 1); Grid.SetRow(main, 0); grid.Children.Add(main);
        var footer = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Background = Brush(Surface), MinHeight = 50 };
        _status = Text(L.Get("ready"), 12, color: Muted); _status.Margin = new Thickness(22, 8);
        footer.Children.Add(_status);
        _transferHost = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(12, 5) };
        Grid.SetColumn(_transferHost, 1); footer.Children.Add(_transferHost);
        Grid.SetRow(footer, 1); Grid.SetColumnSpan(footer, 2); grid.Children.Add(footer);
        Content = grid;
        RenderPage(); RenderProgress();
    }
    private void SetStatus(string status) { if (_status is not null) _status.Text = status; }
    internal async Task RenderPreviewAsync(string directory)
    {
        Directory.CreateDirectory(directory);
        foreach (string page in new[] { "send", "devices", "activity", "settings" })
        {
            _page = page;
            BuildShell();
            await Task.Delay(300);
            using var bitmap = new RenderTargetBitmap(new PixelSize((int)ClientSize.Width, (int)ClientSize.Height));
            bitmap.Render(this);
            bitmap.Save(Path.Combine(directory, page + ".png"), PngBitmapEncoderOptions.Default);
            if (page == "settings" && _scroll is not null)
            {
                for (int i = 1; i <= 5; i++)
                {
                    _scroll.Offset = new Vector(0, i * 500);
                    await Task.Delay(100);
                    using var section = new RenderTargetBitmap(new PixelSize((int)ClientSize.Width, (int)ClientSize.Height));
                    section.Render(this); section.Save(Path.Combine(directory, "settings-" + i + ".png"), PngBitmapEncoderOptions.Default);
                }
            }
        }
    }
    private void RenderPage()
    {
        if (_pageHost is null) return;
        _pageHost.Child = _page switch
        {
            "send" => SendPage(), "devices" => DevicesPage(), "activity" => ActivityPage(), _ => SettingsPage()
        };
    }
    private void RenderProgress()
    {
        if (_transferHost is null) return;
        _transferHost.Children.Clear();
        foreach (var p in _progress.Values.Take(1))
        {
        string state = p.WaitingForApproval ? L.Get("waiting") : p.Reconnecting ? L.Get("reconnecting") : p.Percent >= 0 ? $"{p.Percent}%" : L.Get("progress");
        _transferHost.Children.Add(Text($"{p.PeerName} · {state}", 12));
        _transferHost.Children.Add(new ProgressBar { Width = 112, Height = 6, IsIndeterminate = p.Percent < 0, Value = Math.Max(0, p.Percent), Margin = new Thickness(8, 0), VerticalAlignment = VerticalAlignment.Center });
        _transferHost.Children.Add(Action(L.Get("cancel"), () => _peer.CancelTransfer(p.TransferId)));
        }
        if (_progress.Count > 0) _transferHost.Children.Add(Action(L.Get("details"), ShowTransfers));
    }
    private async void Run(Func<Task> work)
    {
        try { await work(); }
        catch (Exception e) { string message = L.Error(e); SetStatus(message); await Alert(L.Get("error"), message); }
    }
    private Control SendPage()
    {
        var host = Column();
        host.Spacing = 8;
        Section(host, L.Get("send_title"), L.Get("send_intro"));
        var actions = Row();
        actions.Children.Add(Action(L.Get("files"), () => Run(async () =>
        {
            var picked = await StorageProvider.OpenFilePickerAsync(new FilePickerOpenOptions { Title = L.Get("files"), AllowMultiple = true });
            _paths.AddRange(picked.Select(p => p.Path.LocalPath)); RenderPage();
        }), true));
        actions.Children.Add(Action(L.Get("folder"), () => Run(async () =>
        {
            var picked = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions { Title = L.Get("folder"), AllowMultiple = false });
            _paths.AddRange(picked.Select(p => p.Path.LocalPath)); RenderPage();
        })));
        host.Children.Add(actions);
        var drop = new Border { BorderBrush = Brush(Rule), BorderThickness = new Thickness(1), CornerRadius = new CornerRadius(7),
            Padding = new Thickness(18), Background = Brush(Surface), Child = Text(L.Get("drop"), 13, color: Muted) };
        DragDrop.SetAllowDrop(drop, true);
        drop.AddHandler(DragDrop.DragOverEvent, (_, e) => e.DragEffects = e.DataTransfer.Formats.Contains(DataFormat.File) ? DragDropEffects.Copy : DragDropEffects.None);
        drop.AddHandler(DragDrop.DropEvent, (_, e) =>
        {
            if (e.DataTransfer.TryGetFiles() is { } files)
            {
                _paths.AddRange(files.Select(f => f.Path.LocalPath)); RenderPage();
            }
        });
        host.Children.Add(drop);
        if (_paths.Count > 0)
        {
            var selected = Row(); selected.Children.Add(Text(L.Get("selected") + $" · {_paths.Count}", 15, true));
            selected.Children.Add(Action(L.Get("clear"), () => { _paths.Clear(); RenderPage(); })); host.Children.Add(selected);
            foreach (string path in _paths.Take(8).ToArray())
            {
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 3) };
                row.Children.Add(Text(Path.GetFileName(Path.TrimEndingDirectorySeparator(path)), 13));
                var remove = Action(L.Get("remove"), () => { _paths.Remove(path); RenderPage(); });
                Grid.SetColumn(remove, 1); row.Children.Add(remove); host.Children.Add(row);
            }
            if (_paths.Count > 8) host.Children.Add(Text($"+ {_paths.Count - 8}", 12, color: Muted));
        }
        else host.Children.Add(Text(L.Get("nothing_selected"), 12, color: Muted));
        host.Children.Add(Line());
        host.Children.Add(Text(L.Get("message"), 15, true));
        string message = _message?.Text ?? "";
        _message = new TextBox { PlaceholderText = L.Get("message_hint"), Text = message, AcceptsReturn = true, MinHeight = 56, TextWrapping = TextWrapping.Wrap };
        host.Children.Add(_message);
        host.Children.Add(Action(L.Get("paste_clipboard"), () => Run(async () =>
        {
            if (Clipboard is not null) _message.Text = await Clipboard.TryGetTextAsync() ?? "";
        })));
        host.Children.Add(Line());
        host.Children.Add(Text(L.Get("send_to"), 15, true));
        var devices = Column(); host.Children.Add(devices);
        Run(async () =>
        {
            var paired = await _peer.DevicesAsync();
            devices.Children.Clear();
            foreach (var device in paired)
            {
                var item = new CheckBox { Content = device.Alias ?? device.Name, IsChecked = _selectedDevices.Contains(device.Id) };
                item.Click += (_, _) => { if (item.IsChecked == true) _selectedDevices.Add(device.Id); else _selectedDevices.Remove(device.Id); };
                devices.Children.Add(item);
            }
            if (paired.Count == 0) host.Children.Add(Text(L.Get("no_paired"), 12, color: Muted));
        });
        host.Children.Add(Action(L.Get("send_now"), () => Run(async () =>
        {
            var ids = _selectedDevices.ToArray();
            if (ids.Length == 0) { await Alert(L.Get("send_to"), L.Get("choose_device")); return; }
            var text = _message?.Text ?? "";
            var files = SendItem.From(_paths);
            if (files.Count == 0 && text.Length == 0) { await Alert(L.Get("selected"), L.Get("nothing_selected")); return; }
            _paths.Clear(); _message!.Text = "";
            var outcomes = await Task.WhenAll(ids.Select(async id =>
            {
                SendSubmission? result = null;
                if (files.Count > 0) result = await _queue.SendFilesAsync(id, files);
                if (text.Length > 0)
                {
                    var textResult = await _queue.SendTextAsync(id, text);
                    if (result is null || result.Status == "sent") result = textResult;
                }
                return result!;
            }));
            SetStatus(outcomes.Any(r => r.Status == "failed") ? L.Get("failed") : outcomes.Any(r => r.Status == "queued") ? L.Get("queued") : L.Get("done"));
            RenderPage();
        }), true));
        AddQueue(host);
        host.Children.Add(Line());
        host.Children.Add(Action(L.Get("bluetooth_send"), () => Run(SendBluetoothAsync)));
        return host;
    }

    private Control DevicesPage()
    {
        var host = Column();
        Section(host, L.Get("my_device"), _peer.Name);
        host.Children.Add(Text(L.Get("id") + ": " + _peer.Id, 11, color: Muted));
        var addresses = NetworkAddresses.Describe();
        if (addresses.Count == 0) host.Children.Add(Text(L.Get("no_address"), 13, color: Muted));
        else foreach (var address in addresses)
        {
            var row = Row(); row.Children.Add(Text(address.Kind + " · ", 13));
            row.Children.Add(new TextBlock { Text = address.Address.ToString(), FontSize = 13, FlowDirection = FlowDirection.LeftToRight, Foreground = Brush(Muted) });
            host.Children.Add(row);
        }
        host.Children.Add(Line());
        Section(host, L.Get("pair_title"), L.Get("connection_note"));
        var pair = Row();
        pair.Children.Add(Action(L.Get("show_qr"), ShowQr, true));
        pair.Children.Add(Action(L.Get("pair_link"), () => Run(async () =>
        {
            string? uri = await Prompt(L.Get("pair_link"), L.Get("pair_link_hint"));
            if (uri is null) return;
            await _peer.PairQrAsync(uri.Trim()); SetStatus(L.Get("paired")); RenderPage();
        })));
        pair.Children.Add(Action(L.Get("pair_address"), () => Run(async () =>
        {
            string? input = await Prompt(L.Get("pair_address"), L.Get("address_hint"));
            if (input is null) return;
            var pieces = input.Trim().Split(':', 2);
            if (!IPAddress.TryParse(pieces[0], out var ip) || ip.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork ||
                (pieces.Length == 2 && !int.TryParse(pieces[1], out _))) { await Alert(L.Get("error"), L.Get("invalid_address")); return; }
            int port = pieces.Length == 2 ? int.Parse(pieces[1]) : 7410;
            if (port is < 1 or > 65535) { await Alert(L.Get("error"), L.Get("invalid_address")); return; }
            await _peer.PairSasAsync(ip, port); SetStatus(L.Get("paired")); RenderPage();
        })));
        host.Children.Add(pair);
        host.Children.Add(Line());
        var nearbyTitle = Row(); nearbyTitle.Children.Add(Text(L.Get("nearby"), 21, true));
        nearbyTitle.Children.Add(Action(L.Get("refresh"), () => Run(() => _peer.QueryNearbyAsync())));
        host.Children.Add(nearbyTitle);
        foreach (var nearby in _peer.Nearby.Where(d => !string.IsNullOrWhiteSpace(d.Name)))
        {
            var device = nearby;
            var row = Row(); row.Children.Add(Text($"{device.Name} · {device.Address}", 13));
            row.Children.Add(Action(L.Get("pair"), () => Run(async () => { await _peer.PairSasAsync(device.Address, device.Port); RenderPage(); })));
            host.Children.Add(row);
        }
        host.Children.Add(Line());
        host.Children.Add(Text(L.Get("paired"), 21, true));
        Run(async () =>
        {
            var paired = await _peer.DevicesAsync();
            if (paired.Count == 0) host.Children.Add(Text(L.Get("no_paired"), 13, color: Muted));
            foreach (var device in paired.OrderBy(d => d.Alias ?? d.Name))
            {
                var item = Column();
                item.Children.Add(Text((device.Alias ?? device.Name) + " · " + device.Platform, 16, true));
                item.Children.Add(Text(L.Get("last_seen") + ": " + device.LastSeen.ToLocalTime().ToString("g"), 12, color: Muted));
                var controls = Row();
                var trust = new CheckBox { Content = L.Get("trust"), IsChecked = device.Trusted, VerticalAlignment = VerticalAlignment.Center };
                trust.Click += (_, _) => Run(() => _peer.SetTrustedAsync(device.Id, trust.IsChecked == true));
                controls.Children.Add(trust);
                controls.Children.Add(Action(L.Get("rename"), () => Run(async () =>
                {
                    string? alias = await Prompt(L.Get("rename"), device.Alias ?? device.Name, false);
                    if (alias is not null) await _peer.SetAliasAsync(device.Id, string.IsNullOrWhiteSpace(alias) ? null : alias.Trim());
                })));
                controls.Children.Add(Action(L.Get("send"), () => { _selectedDevices.Clear(); _selectedDevices.Add(device.Id); ShowPage("send"); }));
                controls.Children.Add(Action(L.Get("unpair"), () => Run(async () =>
                {
                    if (await Confirm(L.Get("unpair"), device.Alias ?? device.Name)) await _peer.UnpairAsync(device.Id);
                })));
                item.Children.Add(controls); item.Children.Add(Line()); host.Children.Add(item);
            }
        });
        return host;
    }
    private Control ActivityPage()
    {
        var host = Column();
        Section(host, L.Get("activity"));
        host.Children.Add(Action(L.Get("clear_history"), () => Run(async () =>
        {
            if (await Confirm(L.Get("clear_history"), L.Get("clear_history_note"))) { await _peer.ClearHistoryAsync(); RenderPage(); }
        })));
        Run(async () =>
        {
            var history = (await _peer.HistoryAsync()).OrderByDescending(h => h.At).ToArray();
            var devices = await _peer.DevicesAsync();
            if (history.Length == 0) { host.Children.Add(Text(L.Get("history_empty"), 14, color: Muted)); return; }
            DateOnly? day = null;
            foreach (var entry in history.Take(100))
            {
                var local = entry.At.ToLocalTime();
                var entryDay = DateOnly.FromDateTime(local.DateTime);
                if (day != entryDay) { day = entryDay; host.Children.Add(Text(local.ToString("D", L.Culture), 16, true, Orange)); }
                var peer = devices.FirstOrDefault(d => d.Id == entry.PeerId);
                string name = peer?.Alias ?? peer?.Name ?? (entry.PeerId == "bluetooth" ? L.Get("bluetooth") : entry.PeerId[..Math.Min(8, entry.PeerId.Length)]);
                var row = new Grid { ColumnDefinitions = new ColumnDefinitions("*,Auto"), Margin = new Thickness(0, 8) };
                var info = Column();
                string direction = entry.Direction == "sent" ? L.Get("sent") : L.Get("received");
                string subject = entry.Text is not null ? L.Get("text") : L.Files(entry.FileCount);
                info.Children.Add(Text(direction + " · " + subject + " · " + name, 14, true));
                string method = entry.Transport ?? TransferTransport.Unknown;
                string methodLabel = method switch { TransferTransport.LocalNetwork => L.Get("local_network"), TransferTransport.DirectLink => L.Get("direct_link"), TransferTransport.Bluetooth => L.Get("bluetooth"), TransferTransport.Mixed => L.Get("mixed"), _ => L.Get("unknown") };
                info.Children.Add(Text(local.ToString("t", L.Culture) + " · " + L.Get("method") + ": " + methodLabel + (entry.Result == "ok" ? "" : " · " + L.Get("failed")), 12, color: Muted));
                row.Children.Add(info);
                if (entry.Text is not null)
                {
                    var value = entry.Text;
                    var actions = Row();
                    actions.Children.Add(Action(L.Get("open"), () => Run(() => ShowTextAsync(value))));
                    actions.Children.Add(Action(L.Get("copy"), () => Run(async () => { if (Clipboard is not null) { _ownClipboard = value; await Clipboard.SetTextAsync(value); } })));
                    Grid.SetColumn(actions, 1); row.Children.Add(actions);
                }
                else if (entry.Direction == "received" && entry.Files.Count > 0)
                {
                    string path = entry.Files.FirstOrDefault(File.Exists) ?? entry.Files[0];
                    var actions = Row();
                    if (File.Exists(path)) actions.Children.Add(Action(L.Get("open"), () => OpenPath(path)));
                    actions.Children.Add(Action(L.Get("show_folder"), () => OpenPath(File.Exists(path) ? Path.GetDirectoryName(path)! : _peer.ReceiveDirectory)));
                    Grid.SetColumn(actions, 1); row.Children.Add(actions);
                }
                host.Children.Add(row); host.Children.Add(Line());
            }
        });
        return host;
    }

    private Control SettingsPage()
    {
        var host = Column();
        Section(host, L.Get("language"), L.Get("language_intro"));
        var language = new ComboBox { Width = 190, HorizontalAlignment = HorizontalAlignment.Left };
        var en = new ComboBoxItem { Content = L.Get("english"), Tag = "en" };
        var ar = new ComboBoxItem { Content = L.Get("arabic"), Tag = "ar" };
        language.Items.Add(en); language.Items.Add(ar); language.SelectedItem = L.Language == "ar" ? ar : en;
        language.SelectionChanged += (_, _) =>
        {
            if (language.SelectedItem is not ComboBoxItem { Tag: string chosen } || chosen == L.Language) return;
            Run(async () =>
            {
                _saved = await _preferences.UpdateAsync(p => p with { Language = chosen });
                L.Language = chosen; BuildShell();
            });
        };
        host.Children.Add(language);
        host.Children.Add(Line());
        Section(host, L.Get("my_device"));
        var name = new TextBox { Text = _peer.Name, Width = 310, HorizontalAlignment = HorizontalAlignment.Left };
        host.Children.Add(Text(L.Get("name"), 13)); host.Children.Add(name);
        host.Children.Add(Action(L.Get("save"), () => Run(async () => { await _peer.SetNameAsync(name.Text?.Trim() ?? ""); SetStatus(L.Get("save")); })));
        host.Children.Add(Line());
        Section(host, L.Get("receive_folder"));
        host.Children.Add(new TextBlock { Text = _peer.ReceiveDirectory, FlowDirection = FlowDirection.LeftToRight, FontSize = 12, Foreground = Brush(Muted) });
        var receive = Row();
        receive.Children.Add(Action(L.Get("change"), () => Run(async () =>
        {
            var picked = await StorageProvider.OpenFolderPickerAsync(new FolderPickerOpenOptions { Title = L.Get("receive_folder"), AllowMultiple = false });
            if (picked.Count == 0) return;
            await _peer.SetReceiveDirectoryAsync(picked[0].Path.LocalPath); RenderPage();
        })));
        receive.Children.Add(Action(L.Get("open"), () => OpenPath(_peer.ReceiveDirectory)));
        receive.Children.Add(Action(L.Get("default"), () => Run(async () =>
        {
            await _peer.SetReceiveDirectoryAsync(HopDropPaths.Default.DefaultReceiveDirectory); RenderPage();
        })));
        host.Children.Add(receive);
        var ask = new CheckBox { Content = L.Get("ask"), IsChecked = _peer.AskBeforeReceiving, Margin = new Thickness(0, 10, 0, 0) };
        ask.Click += (_, _) => Run(() => _peer.SetAskBeforeReceivingAsync(ask.IsChecked == true)); host.Children.Add(ask);
        var visible = new CheckBox { Content = L.Get("visibility"), IsChecked = _peer.Visibility == "everyone" };
        visible.Click += (_, _) => Run(() => _peer.SetVisibilityAsync(visible.IsChecked == true ? "everyone" : "paired")); host.Children.Add(visible);
        host.Children.Add(Line());
        var background = new CheckBox { Content = L.Get("close_tray"), IsChecked = _saved.CloseToTray };
        background.Click += (_, _) => Run(async () =>
        {
            _saved = await _preferences.UpdateAsync(p => p with { CloseToTray = background.IsChecked == true });
        });
        host.Children.Add(background); host.Children.Add(Text(L.Get("close_tray_note"), 12, color: Muted));
        host.Children.Add(Action(L.Get("quit"), App.Quit));
        AddSettings(host);
        return host;
    }

    private void ShowQr()
    {
        var dialog = Dialog(L.Get("show_qr"), 480, 660);
        var content = Column();
        content.Children.Add(Text(L.Get("pair_intro"), 14));
        var image = new Image { Width = 280, Height = 280, HorizontalAlignment = HorizontalAlignment.Center };
        content.Children.Add(image);
        var link = new TextBox { IsReadOnly = true, TextWrapping = TextWrapping.Wrap, MinHeight = 80, FlowDirection = FlowDirection.LeftToRight };
        content.Children.Add(link);
        var actions = Row();
        void Refresh()
        {
            string uri = _peer.CreateQrUri();
            link.Text = uri;
            using var qr = QRCodeGenerator.GenerateQrCode(uri, QRCodeGenerator.ECCLevel.Q);
            var bytes = new PngByteQRCode(qr).GetGraphic(5);
            image.Source = new Bitmap(new MemoryStream(bytes));
        }
        actions.Children.Add(Action(L.Get("copy"), () => Run(async () => { if (Clipboard is not null) { _ownClipboard = link.Text; await Clipboard.SetTextAsync(link.Text); } })));
        actions.Children.Add(Action(L.Get("new_code"), Refresh));
        content.Children.Add(actions);
        content.Children.Add(Text(L.Get("qr_expires"), 12, color: Muted));
        dialog.Content = new Border { Padding = new Thickness(24), Child = content };
        dialog.Closed += (_, _) => { _peer.CloseQr(); _peer.SetPairingVisible(false); };
        _peer.SetPairingVisible(true); Refresh(); dialog.Show(this);
    }

    private Window Dialog(string title, int width, int height) => new()
    {
        Title = title, Width = width, Height = height, MinWidth = width, MaxWidth = width,
        WindowStartupLocation = WindowStartupLocation.CenterOwner, Background = Brush(Paper),
        Icon = Icon, FlowDirection = L.Language == "ar" ? FlowDirection.RightToLeft : FlowDirection.LeftToRight
    };
    private async Task<bool> Confirm(string title, string message, Task? closed = null)
    {
        Show(); WindowState = WindowState.Normal; Activate();
        var dialog = Dialog(title, 440, 230);
        var content = new Grid { RowDefinitions = new RowDefinitions("*,Auto") };
        content.Children.Add(new ScrollViewer { Content = Text(message, 15) });
        var buttons = Row();
        buttons.Children.Add(Action(L.Get("decline"), () => dialog.Close(false)));
        buttons.Children.Add(Action(L.Get("confirm"), () => dialog.Close(true), true));
        Grid.SetRow(buttons, 1); buttons.Margin = new Thickness(0, 12, 0, 0); content.Children.Add(buttons);
        dialog.Content = new Border { Padding = new Thickness(24), Child = content };
        var answer = dialog.ShowDialog<bool>(this);
        if (closed is not null) _ = closed.ContinueWith(_ => Dispatcher.UIThread.Post(() => { if (dialog.IsVisible) dialog.Close(false); }), TaskScheduler.Default);
        return await answer;
    }
    private async Task<string?> Prompt(string title, string hint, bool address = true)
    {
        var dialog = Dialog(title, 480, 190);
        var content = Column();
        var input = new TextBox { PlaceholderText = hint, FlowDirection = address ? FlowDirection.LeftToRight : FlowDirection };
        content.Children.Add(input);
        var buttons = Row(); buttons.Children.Add(Action(L.Get("cancel"), () => dialog.Close(null)));
        buttons.Children.Add(Action(L.Get(address ? "pair" : "save"), () => dialog.Close(input.Text), true));
        content.Children.Add(buttons); dialog.Content = new Border { Padding = new Thickness(24), Child = content };
        return await dialog.ShowDialog<string?>(this);
    }
    private async Task Alert(string title, string message)
    {
        var dialog = Dialog(title, 460, 210);
        var content = Column(); content.Children.Add(Text(message, 14));
        content.Children.Add(Action(L.Get("confirm"), () => dialog.Close()));
        dialog.Content = new Border { Padding = new Thickness(24), Child = content };
        await dialog.ShowDialog(this);
    }
    private static void OpenPath(string path)
    {
        if (!File.Exists(path) && !Directory.Exists(path)) return;
        try { Process.Start(new ProcessStartInfo("xdg-open") { UseShellExecute = false, ArgumentList = { path } }); }
        catch (Exception) { }
    }
    private static void Notify(string title, string message)
    {
        try { Process.Start(new ProcessStartInfo("notify-send") { UseShellExecute = false, CreateNoWindow = true, ArgumentList = { "--app-name=HopDrop", "--", title, message } }); }
        catch (Exception) { }
    }
}
