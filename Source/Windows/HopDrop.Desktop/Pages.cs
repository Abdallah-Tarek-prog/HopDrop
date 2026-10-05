using System.Net;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using HopDrop.Core;
using Microsoft.Win32;
using QRCoder;
using Wpf.Ui.Controls;
using Button = Wpf.Ui.Controls.Button;
using TextBox = Wpf.Ui.Controls.TextBox;
using TextBlock = System.Windows.Controls.TextBlock;
using Image = System.Windows.Controls.Image;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using DataFormats = System.Windows.DataFormats;
using DragDropEffects = System.Windows.DragDropEffects;
using OpenFileDialog = Microsoft.Win32.OpenFileDialog;

namespace HopDrop.Desktop;

public sealed partial class MainWindow
{
    // ================= Send =================
    private TextBlock? _fileSummary;
    private StackPanel? _fileHost;
    private Button? _clearFiles;
    private StackPanel? _deviceHost;
    private readonly Dictionary<string, (TextBlock Presence, System.Windows.Shapes.Ellipse Dot, TextBlock Status, ProgressBar Bar, Button Send)> _deviceRows = [];

    internal FrameworkElement BuildSendPage()
    {
        var page = Ui.Page("Send", "Pick files, then choose a paired device. Everything stays on your local network.");
        var dropContent = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 8) };
        var badge = Ui.Badge(SymbolRegular.ArrowUpload24, Palette.OnPrimaryBg, Palette.PrimaryBg, 56); badge.HorizontalAlignment = HorizontalAlignment.Center;
        dropContent.Children.Add(badge);
        var dropTitle = Ui.Label("Drop files or folders here", 18, true); dropTitle.HorizontalAlignment = HorizontalAlignment.Center; dropTitle.Margin = new Thickness(0, 10, 0, 2);
        dropContent.Children.Add(dropTitle);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 0) };
        buttons.Children.Add(Ui.Button("Browse files", Browse, true, SymbolRegular.DocumentAdd24));
        buttons.Children.Add(Ui.Button("Add a folder", BrowseFolder, false, SymbolRegular.FolderAdd24));
        dropContent.Children.Add(buttons);
        var dashColor = new SolidColorBrush(Palette.Primary.Color) { Opacity = 0.7 };
        var dropBackground = new SolidColorBrush(Palette.PrimaryBg.Color) { Opacity = 0 };
        var dashed = new Border
        {
            Child = dropContent, Padding = new Thickness(20, 18, 20, 18), CornerRadius = new CornerRadius(12), BorderThickness = new Thickness(1.5), AllowDrop = true,
            BorderBrush = new DrawingBrush { Viewport = new Rect(0, 0, 12, 12), ViewportUnits = BrushMappingMode.Absolute, TileMode = TileMode.Tile,
                Drawing = new GeometryDrawing(dashColor, null, new GeometryGroup { Children = { new RectangleGeometry(new Rect(0, 0, 6, 6)), new RectangleGeometry(new Rect(6, 6, 6, 6)) } }) },
            Background = dropBackground
        };
        bool dragging = false;
        void HighlightDrop(bool active)
        {
            if (dragging == active) return;
            dragging = active;
            Motion.Highlight(dashed, dashColor, active ? 1 : 0.7);
            Motion.Highlight(dashed, dropBackground, active ? 1 : 0);
            Motion.Scale(dashed, active ? 1.02 : 1);
        }
        dashed.DragOver += (_, e) =>
        {
            bool accepts = e.Data.GetDataPresent(DataFormats.FileDrop);
            e.Effects = accepts ? DragDropEffects.Copy : DragDropEffects.None; e.Handled = true;
            HighlightDrop(accepts);
        };
        dashed.DragLeave += (_, _) => HighlightDrop(false);
        dashed.Drop += (_, e) => { HighlightDrop(false); if (e.Data.GetData(DataFormats.FileDrop) is string[] paths) AddFiles(paths); };
        dashed.IsVisibleChanged += (_, _) => { if (!dashed.IsVisible) HighlightDrop(false); };
        page.Children.Add(Ui.Card(dashed, 12, 12));

        var files = new StackPanel();
        var header = new Grid(); header.ColumnDefinitions.Add(new ColumnDefinition()); header.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var headerText = new StackPanel(); headerText.Children.Add(Ui.Label("Files to send", 16, true));
        _fileSummary = Ui.Subtle(""); headerText.Children.Add(_fileSummary); header.Children.Add(headerText);
        _clearFiles = Ui.Button("Clear all", () => { _files.Clear(); _sizes.Clear(); RefreshFiles(); }, icon: SymbolRegular.Delete24); _clearFiles.Margin = new Thickness(0);
        Grid.SetColumn(_clearFiles, 1); header.Children.Add(_clearFiles);
        files.Children.Add(header);
        _fileHost = new StackPanel { Margin = new Thickness(0, 8, 0, 0) }; files.Children.Add(_fileHost);
        page.Children.Add(Ui.Card(files, 18, 4));
        RefreshFiles();

        page.Children.Add(Ui.Section("Send to"));
        _deviceHost = new StackPanel(); page.Children.Add(_deviceHost); FillSendDevices();

        page.Children.Add(Ui.Section("No shared Wi-Fi?"));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Bluetooth24, "Send with Bluetooth",
            "Opens Windows' Bluetooth window. Slower than Wi-Fi; the phone accepts the file in its Bluetooth notification.",
            Ui.Button("Send via Bluetooth", BluetoothWizard.Send, icon: SymbolRegular.Bluetooth24)));
        return Ui.Scroll(page);
    }
    private void Browse()
    {
        var picker = new OpenFileDialog { Multiselect = true, Title = "Choose files to send" };
        if (picker.ShowDialog(this) == true) AddFiles(picker.FileNames);
    }
    private void BrowseFolder()
    {
        var picker = new OpenFolderDialog { Multiselect = true, Title = "Choose folders to send" };
        if (picker.ShowDialog(this) == true) AddFiles(picker.FolderNames);
    }
    private void AddFiles(IEnumerable<string> paths)
    {
        int previousCount = _files.Count;
        try
        {
            var queued = _files.Select(f => f.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
            _files.AddRange(SendItem.From(paths).Where(item => queued.Add(item.Path)));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { ShowError("HopDrop couldn't read that folder: " + ex.Message); }
        RefreshFiles(_files.Skip(previousCount).Select(item => item.Path).ToHashSet(StringComparer.OrdinalIgnoreCase));
    }
    /// <summary>Sizes of the files in the Send list, read once when they're first shown.</summary>
    private readonly Dictionary<string, long> _sizes = new(StringComparer.OrdinalIgnoreCase);
    private long SizeOf(string path)
    {
        if (_sizes.TryGetValue(path, out long size)) return size;
        try { size = new FileInfo(path).Length; } catch { size = -1; }
        return _sizes[path] = size;
    }
    /// <summary>"3 files", "1 folder", "2 folders and 3 files".</summary>
    private string SelectionSummary()
    {
        int folders = _files.Where(item => item.Root is not null).Select(item => item.Root!).Distinct(StringComparer.OrdinalIgnoreCase).Count();
        int loose = _files.Count(item => item.Root is null);
        return folders == 0 ? Fmt.Files(loose) : loose == 0 ? Fmt.Folders(folders) : $"{Fmt.Folders(folders)} and {Fmt.Files(loose)}";
    }
    private void RefreshFiles(IReadOnlySet<string>? added = null)
    {
        if (_fileHost is null || _fileSummary is null) return;
        _fileHost.Children.Clear();
        long total = _files.Sum(item => Math.Max(0, SizeOf(item.Path)));
        _fileSummary.Text = _files.Count == 0 ? "Nothing selected yet" : $"{SelectionSummary()} · {Fmt.Size(total)}";
        if (_clearFiles is not null) _clearFiles.Visibility = _files.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        if (_files.Count == 0) _fileHost.Children.Add(Ui.Subtle("Drop files or folders above, or click Browse. You can add as many as you like."));
        int addedIndex = 0;
        void Appear(FrameworkElement row, bool isNew)
        {
            if (!isNew) return;
            Motion.FadeIn(row, addedIndex < 12 ? addedIndex * 20 : 0);
            addedIndex++;
        }
        // A sent folder is one row: it arrives as a folder, with everything inside it.
        foreach (var folder in _files.Where(item => item.Root is not null).GroupBy(item => item.Root!, StringComparer.OrdinalIgnoreCase).ToArray())
        {
            string root = folder.Key;
            string name = Path.GetFileName(Path.TrimEndingDirectorySeparator(root)) is { Length: > 0 } leaf ? leaf : root;
            long size = folder.Sum(item => Math.Max(0, SizeOf(item.Path)));
            var remove = Ui.IconButton(SymbolRegular.Dismiss24, "Remove folder " + name, () =>
            {
                _files.RemoveAll(item => string.Equals(item.Root, root, StringComparison.OrdinalIgnoreCase));
                RefreshFiles();
            });
            var row = Ui.ListRow(Ui.Badge(SymbolRegular.Folder24, Palette.Amber, Palette.AmberBg, 36), name,
                $"Folder · {Fmt.Files(folder.Count())} · {Fmt.Size(size)} · {Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(root)) ?? ""}", remove);
            row.ToolTip = root;
            _fileHost.Children.Add(row);
            Appear(row, added is not null && folder.Any(item => added.Contains(item.Path)));
        }
        var loose = _files.Where(item => item.Root is null).ToArray();
        foreach (var item in loose.Take(200))
        {
            string path = item.Path;
            var remove = Ui.IconButton(SymbolRegular.Dismiss24, "Remove " + Path.GetFileName(path), () => { _files.Remove(item); RefreshFiles(); });
            var row = Ui.ListRow(Ui.Badge(Ui.FileIcon(path), Palette.OnPrimaryBg, Palette.PrimaryBg, 36), Path.GetFileName(path), $"{Fmt.Size(SizeOf(path))} · {Path.GetDirectoryName(path) ?? ""}", remove);
            row.ToolTip = path;
            _fileHost.Children.Add(row);
            Appear(row, added?.Contains(path) == true);
        }
        if (loose.Length > 200) _fileHost.Children.Add(Ui.Subtle($"…and {Fmt.Files(loose.Length - 200)} more"));
        UpdateSendDevices();
    }
    private void FillSendDevices()
    {
        if (_deviceHost is null) return;
        _deviceHost.Children.Clear(); _deviceRows.Clear();
        var devices = _peer is null ? SampleDevices() : _devices;
        if (devices.Count == 0)
        {
            _deviceHost.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.PhoneLaptop24, "No paired devices yet",
                "Pair once in Devices (scan a QR code with your phone). After that, sending is one click, both ways.",
                Ui.Button("Pair a device", () => Navigate("devices"), true))));
            return;
        }
        foreach (var device in devices.OrderBy(d => d.Alias ?? d.Name))
        {
            var badge = Ui.Badge(device.Platform == "android" ? SymbolRegular.Phone24 : SymbolRegular.Laptop24, Palette.OnPrimaryBg, Palette.PrimaryBg, 44);
            var send = Ui.Button("Send", () => _ = SendToAsync(device), true, SymbolRegular.Send24); send.Margin = new Thickness(0);
            var row = Ui.ListRow(badge, device.Alias ?? device.Name, null, send);
            var text = Ui.TextOf(row);
            var presence = Ui.Status("", Palette.Muted); text.Children.Add(presence);
            var status = Ui.Subtle(""); status.Visibility = Visibility.Collapsed; text.Children.Add(status);
            var bar = Ui.Bar(); bar.Visibility = Visibility.Collapsed; text.Children.Add(bar);
            _deviceRows[device.Id] = ((TextBlock)presence.Children[1], (System.Windows.Shapes.Ellipse)presence.Children[0], status, bar, send);
            var card = Motion.Hover(Ui.Card(row, 14, 8)); card.Cursor = System.Windows.Input.Cursors.Hand;
            card.MouseLeftButtonUp += (_, e) =>
            {
                for (DependencyObject? current = e.OriginalSource as DependencyObject; current is not null; current = VisualTreeHelper.GetParent(current))
                    if (current is System.Windows.Controls.Primitives.ButtonBase) return;
                _ = SendToAsync(device);
            };
            _deviceHost.Children.Add(card);
        }
        UpdateSendDevices();
    }
    /// <summary>Updates online state and live progress under each device, in place.</summary>
    private void UpdateSendDevices()
    {
        foreach (var (id, row) in _deviceRows)
        {
            var device = _devices.FirstOrDefault(d => d.Id == id);
            bool online = _peer?.Nearby.Any(n => n.Id == id) == true;
            row.Presence.Text = online ? "Online" : device is null ? "Offline" : $"Offline · seen {Fmt.Ago(device.LastSeen)}";
            row.Presence.Foreground = online ? Palette.Success : Palette.Muted; row.Dot.Fill = row.Presence.Foreground;
            var item = Outgoing(id);
            bool busy = item is not null && !item.Finished;
            row.Send.IsEnabled = _files.Count > 0 && !busy;
            row.Send.ToolTip = _files.Count == 0 ? "Add files first" : null;
            row.Bar.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
            row.Status.Visibility = item is null ? Visibility.Collapsed : Visibility.Visible;
            if (item is null) continue;
            if (item.Failure is not null) { row.Status.Text = item.Failure; row.Status.Foreground = Palette.Danger; }
            else if (item.Result is TransferResult r)
            {
                row.Status.Text = r.ErrorCode is null ? $"Sent {Fmt.Files(r.SavedPaths.Count)} · {Fmt.Size(r.Bytes)} in {Fmt.Duration(r.Duration)}" : Platform.ExplainError(r.ErrorCode, item.PeerName);
                row.Status.Foreground = r.ErrorCode is null ? Palette.Success : Palette.Danger;
            }
            else if (item.Progress is TransferProgress { Reconnecting: true })
            {
                row.Status.Text = "Connection dropped · reconnecting…"; row.Status.Foreground = Palette.Muted; row.Bar.IsIndeterminate = true;
            }
            else if (item.Progress is TransferProgress { WaitingForApproval: true })
            {
                row.Status.Text = $"Waiting for {item.PeerName} to accept…"; row.Status.Foreground = Palette.Muted; row.Bar.IsIndeterminate = true;
            }
            else if (item.Progress is TransferProgress p)
            {
                row.Status.Text = Fmt.Amount(p.BytesDone, p.TotalBytes) + (p.Eta is null ? "" : $" · {Fmt.Duration(p.Eta.Value)} left");
                row.Status.Foreground = Palette.Muted;
                row.Bar.IsIndeterminate = p.Percent < 0; if (p.Percent >= 0) Motion.SmoothValue(row.Bar, p.BytesDone * 100.0 / p.TotalBytes);
            }
            else { row.Status.Text = "Connecting…"; row.Status.Foreground = Palette.Muted; row.Bar.IsIndeterminate = true; }
        }
    }
    private async Task SendToAsync(PairedDevice device)
    {
        if (_peer is null) return;
        if (_files.Count == 0) { ShowError("Add at least one file first."); return; }
        var current = Outgoing(device.Id);
        if (current is not null && !current.Finished) return;
        Connecting(device); UpdateSendDevices();
        try { await _peer.SendAsync(device.Id, _files.ToList()); }
        catch (Exception ex)
        {
            string name = device.Alias ?? device.Name;
            if (_live.ContainsKey("pending:" + device.Id))
            {
                string reason = Platform.ExplainException(ex, name);
                FailedToStart(device, reason);
                if (!IsActive) Platform.Notify($"Couldn't send to {name}", reason);
            }
        }
        finally { UpdateSendDevices(); }
    }

    // ================= Devices =================
    private DispatcherTimer? _qrTimer;
    private DateTimeOffset _qrExpiry;
    private string? _qrUri;
    private StackPanel? _qrHost;
    private string? _qrSuccessName;
    private StackPanel? _pairedHost;
    private StackPanel? _nearbyHost;
    private string _nearbyShown = "";

    internal FrameworkElement BuildDevicesPage()
    {
        var page = Ui.Page("Devices", "Pair once, and devices recognise each other from then on, in both directions.");
        page.Children.Add(Ui.Section("This computer"));
        var thisPc = new StackPanel();
        thisPc.Children.Add(Ui.ListRow(Ui.Badge(SymbolRegular.Laptop24, Palette.OnPrimaryBg, Palette.PrimaryBg, 44), _peer?.Name ?? "HopDrop Laptop",
            "Ready to receive from paired devices while HopDrop is running (also from the tray).",
            Ui.Chip("Ready", Palette.Success, Palette.SuccessBg)));
        var addresses = _peer is null ? [new NetworkAddresses.LocalAddress(IPAddress.Parse("192.168.1.5"), "Wi-Fi")] : NetworkAddresses.Describe();
        if (addresses.Count == 0) thisPc.Children.Add(Ui.Colored("Not connected to a local network. Join Wi-Fi, or turn on Windows Mobile hotspot.", Palette.Danger));
        var addressRow = new WrapPanel { Margin = new Thickness(58, 6, 0, 0) };
        foreach (var address in addresses)
        {
            var chip = new StackPanel { Orientation = Orientation.Horizontal };
            chip.Children.Add(new TextBlock { Text = address.Kind + "  ", FontSize = 13, Foreground = Palette.Muted, VerticalAlignment = VerticalAlignment.Center });
            chip.Children.Add(new TextBlock { Text = address.Address.ToString(), FontSize = 14, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) });
            var copy = Ui.IconButton(SymbolRegular.Copy24, "Copy " + address.Address, () => { System.Windows.Clipboard.SetText(address.Address.ToString()); });
            chip.Children.Add(copy);
            addressRow.Children.Add(new Border { Child = chip, Background = Palette.SurfaceAlt, CornerRadius = new CornerRadius(8), Padding = new Thickness(10, 2, 2, 2), Margin = new Thickness(0, 0, 8, 6) });
        }
        thisPc.Children.Add(addressRow);
        page.Children.Add(Ui.Card(thisPc));

        page.Children.Add(Ui.Section("Pair a phone"));
        _qrHost = new StackPanel(); page.Children.Add(Ui.Card(_qrHost));
        if (_qrSuccessName is not null) ShowQrSuccess(); else ShowQr();

        var pairedHeader = new Grid(); pairedHeader.ColumnDefinitions.Add(new ColumnDefinition()); pairedHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        pairedHeader.Children.Add(Ui.Section("Paired devices"));
        page.Children.Add(pairedHeader);
        _pairedHost = new StackPanel(); page.Children.Add(_pairedHost);
        page.Children.Add(Ui.Subtle("Pairing is permanent. Paired devices stay paired until you remove them here or on the other device."));

        var nearbyHeader = new Grid(); nearbyHeader.ColumnDefinitions.Add(new ColumnDefinition()); nearbyHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        nearbyHeader.Children.Add(Ui.Section("Nearby devices"));
        var nearbyActions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 10) };
        nearbyActions.Children.Add(Ui.Button("Refresh", () => _ = _peer?.QueryNearbyAsync(), icon: SymbolRegular.ArrowClockwise24));
        var ip = Ui.Button("Pair by IP address", AddByIp, icon: SymbolRegular.Globe24); ip.Margin = new Thickness(0); nearbyActions.Children.Add(ip);
        Grid.SetColumn(nearbyActions, 1); nearbyHeader.Children.Add(nearbyActions);
        page.Children.Add(nearbyHeader);
        _nearbyHost = new StackPanel(); page.Children.Add(_nearbyHost);
        _nearbyShown = "";
        FillPaired(); FillNearby();
        return Ui.Scroll(page);
    }
    private void FillPaired()
    {
        if (_pairedHost is null) return;
        _pairedHost.Children.Clear();
        var devices = _peer is null ? SampleDevices() : _devices;
        if (devices.Count == 0) { _pairedHost.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.PhoneLaptop24, "No paired devices yet", "Scan the QR code above with HopDrop on your phone, or pick a nearby device below."))); return; }
        foreach (var d in devices.OrderBy(d => d.Alias ?? d.Name))
        {
            bool online = _peer is null || _peer.Nearby.Any(n => n.Id == d.Id);
            var rename = Ui.Button("Rename", async () =>
            {
                string? alias = Prompt("Rename device", "Choose a name for this device on this PC", d.Alias ?? d.Name);
                if (alias is null || _peer is null) return;
                if (alias.Length > 40) { ShowError("Use at most 40 characters."); return; }
                await _peer.SetAliasAsync(d.Id, alias);
            }, icon: SymbolRegular.Rename24);
            var remove = Ui.Button("Remove", async () =>
            {
                if (_peer is null) return;
                var answer = System.Windows.MessageBox.Show(this, $"Remove {d.Alias ?? d.Name}? You'll need to pair again to send files between these devices.", "Remove device", System.Windows.MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (answer == System.Windows.MessageBoxResult.OK) await _peer.UnpairAsync(d.Id);
            }, icon: SymbolRegular.Delete24);
            remove.Margin = new Thickness(0);
            string subtitle = $"{(d.Platform == "android" ? "Android" : "Windows")} · paired {d.PairedAt.LocalDateTime:MMM d, yyyy}" + (d.LastAddresses.Count > 0 ? $" · {d.LastAddresses[0]}" : "");
            var row = Ui.ListRow(Ui.Badge(d.Platform == "android" ? SymbolRegular.Phone24 : SymbolRegular.Laptop24, Palette.OnPrimaryBg, Palette.PrimaryBg, 44), d.Alias ?? d.Name, subtitle, rename, remove);
            Ui.TextOf(row).Children.Add(Ui.Status(online ? "Online now" : $"Offline · seen {Fmt.Ago(d.LastSeen)}", online ? Palette.Success : Palette.Muted));
            var trust = new ToggleSwitch
            {
                IsChecked = d.Trusted, Content = "Trust files from this device", Margin = new Thickness(0, 8, 0, 0),
                ToolTip = "Trusted: files arrive without asking and open without Windows' \"downloaded file\" warnings. Only for devices you own."
            };
            trust.Checked += async (_, _) => { if (_peer is not null) await _peer.SetTrustedAsync(d.Id, true); };
            trust.Unchecked += async (_, _) => { if (_peer is not null) await _peer.SetTrustedAsync(d.Id, false); };
            Ui.TextOf(row).Children.Add(trust);
            _pairedHost.Children.Add(Motion.Hover(Ui.Card(row, 14, 8)));
        }
    }
    private void FillNearby()
    {
        if (_nearbyHost is null) return;
        var nearby = _peer?.Nearby.ToList() ?? [new NearbyDevice(new string('c', 64), "Pixel 8", "android", 7410, IPAddress.Parse("192.168.1.9"), DateTimeOffset.Now, true)];
        string state = string.Join(";", nearby.Select(n => n.Id + n.Address + n.Name)) + "|" + string.Join(",", _devices.Select(d => d.Id));
        if (state == _nearbyShown) return;
        _nearbyShown = state;
        _nearbyHost.Children.Clear();
        if (nearby.Count == 0)
        {
            _nearbyHost.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.Search24, "Searching…",
                "Open HopDrop on the other device. Both must be on the same Wi-Fi, or one on the other's hotspot. " +
                "Still nothing? Use Pair by IP address — the phone shows its address under Devices → This phone.")));
            return;
        }
        foreach (var n in nearby.Where(n => n.Name.Length > 0 || _devices.Any(d => d.Id == n.Id)).OrderBy(n => n.Name))
        {
            bool paired = _devices.Any(d => d.Id == n.Id);
            UIElement action = paired ? Ui.Chip("Paired", Palette.Success, Palette.SuccessBg) : Ui.Button("Pair", () => _ = PairSasAsync(n.Address, n.Port), true, SymbolRegular.Link24);
            var badge = Ui.Badge(n.Platform == "android" ? SymbolRegular.Phone24 : SymbolRegular.Laptop24, paired ? Palette.Success : Palette.OnPrimaryBg, paired ? Palette.SuccessBg : Palette.PrimaryBg, 44);
            string shown = n.Name.Length > 0 ? n.Name : _devices.First(d => d.Id == n.Id) is var known ? known.Alias ?? known.Name : "";
            var row = Ui.ListRow(badge, shown, $"{(n.Platform == "android" ? "Android" : "Windows")} · {n.Address}" + (paired ? " · ready to send" : " · pair by comparing a 6-digit number"), action);
            _nearbyHost.Children.Add(Motion.Hover(Ui.Card(row, 14, 8)));
        }
    }
    private void ShowQr()
    {
        if (_qrHost is null) return;
        _qrHost.Children.Clear(); _qrTimer?.Stop();
        if (_qrUri is null || DateTimeOffset.UtcNow >= _qrExpiry)
        {
            _qrUri = _peer?.CreateQrUri() ?? new PairUri(new string('a', 64), "HopDrop Laptop", "windows", 7410, [IPAddress.Parse("192.168.1.5")], new byte[16]).ToString();
            _qrExpiry = DateTimeOffset.UtcNow.AddMinutes(5);
        }
        var row = new Grid(); row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto }); row.ColumnDefinitions.Add(new ColumnDefinition());
        var image = QrImage(_qrUri); image.Width = 232; image.Height = 232;
        string shownUri = _qrUri;
        var frame = new Border { Child = image, Background = Brushes.White, CornerRadius = new CornerRadius(12), Padding = new Thickness(4), Margin = new Thickness(0, 0, 24, 0), VerticalAlignment = VerticalAlignment.Center,
            Cursor = System.Windows.Input.Cursors.Hand, ToolTip = "Click to show a bigger code" };
        frame.MouseLeftButtonUp += (_, _) => new QrZoomWindow(QrImage(shownUri)) { Owner = this }.ShowDialog();
        row.Children.Add(frame);
        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        info.Children.Add(Ui.Label("Scan with HopDrop on your phone", 18, true));
        info.Children.Add(Ui.Subtle("On the phone: Devices → Scan QR. Pairing is saved on both devices; you only do this once. Hard to scan? Click the code to make it bigger."));
        var timer = Ui.Label("", 14, true); timer.Margin = new Thickness(0, 14, 0, 4); info.Children.Add(timer);
        info.Children.Add(Ui.Subtle("The code works once and expires after 5 minutes; a new one appears automatically."));
        Grid.SetColumn(info, 1); row.Children.Add(info); _qrHost.Children.Add(row);
        void Tick()
        {
            var left = _qrExpiry - DateTimeOffset.UtcNow;
            if (left <= TimeSpan.Zero) { _qrUri = null; ShowQr(); } else timer.Text = $"Code valid for {left.Minutes}:{left.Seconds:00}";
        }
        Tick();
        _qrTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _qrTimer.Tick += (_, _) => { if (!On("devices")) { StopQr(); return; } Tick(); };
        if (!App.SnapshotMode) _qrTimer.Start();
    }
    private void StopQr() { _qrTimer?.Stop(); _peer?.CloseQr(); _qrUri = null; }
    private void ShowQrSuccess()
    {
        if (_qrHost is null) return;
        _qrHost.Children.Clear();
        var again = Ui.Button("Pair another device", () => { _qrSuccessName = null; ShowQr(); }, true);
        var success = Ui.ListRow(Ui.Badge(SymbolRegular.CheckmarkCircle24, Palette.Success, Palette.SuccessBg, 44),
            "Paired with " + _qrSuccessName, "You can send files both ways now. No codes needed next time.", again);
        _qrHost.Children.Add(success); Motion.ScaleIn(success);
    }
    /// <summary>The pairing code as sharp vector squares (crisp at any size and display scaling), with its white quiet zone.
    /// Medium error correction: about a fifth fewer, bigger squares than the old "Q" level, which phones read more easily off a screen.</summary>
    internal static Image QrImage(string uri)
    {
        using var generator = new QRCodeGenerator(); using var code = generator.CreateQrCode(uri, QRCodeGenerator.ECCLevel.M);
        var modules = code.ModuleMatrix; // includes a 4-module quiet zone on every side
        var dark = new StreamGeometry();
        using (var context = dark.Open())
            for (int y = 0; y < modules.Count; y++)
                for (int x = 0; x < modules[y].Length; x++)
                {
                    if (!modules[y][x]) continue;
                    int start = x;
                    while (x + 1 < modules[y].Length && modules[y][x + 1]) x++;
                    context.BeginFigure(new Point(start, y), true, true);
                    context.PolyLineTo([new Point(x + 1, y), new Point(x + 1, y + 1), new Point(start, y + 1)], false, false);
                }
        dark.Freeze();
        var drawing = new DrawingGroup();
        drawing.Children.Add(new GeometryDrawing(Brushes.White, null, new RectangleGeometry(new Rect(0, 0, modules.Count, modules.Count))));
        drawing.Children.Add(new GeometryDrawing(Brushes.Black, null, dark));
        drawing.Freeze();
        var image = new Image { Source = new DrawingImage(drawing), Stretch = Stretch.Uniform };
        RenderOptions.SetEdgeMode(image, EdgeMode.Aliased);
        return image;
    }
    private void AddByIp()
    {
        string? value = Prompt("Pair by IP address", "Type the other device's address. On a phone it's under HopDrop → Devices → This phone.", "192.168.1.10");
        if (value is null) return;
        var parts = value.Trim().Split(':', 2);
        if (!IPAddress.TryParse(parts[0], out var address) || address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork || !NetworkAddresses.IsPrivateOrLoopback(address) || parts.Length == 2 && !int.TryParse(parts[1], out _))
        { ShowError("Enter a local address like 192.168.1.23"); return; }
        int port = parts.Length == 2 ? int.Parse(parts[1]) : 7410;
        if (port is < 1 or > 65535) { ShowError("Port must be between 1 and 65535."); return; }
        _ = PairSasAsync(address, port);
    }
    private async Task PairSasAsync(IPAddress address, int port)
    {
        if (_peer is null) return;
        try { await _peer.PairSasAsync(address, port); }
        catch (Exception ex) { ShowError(Platform.ExplainException(ex, "the device")); }
    }

    // ================= Activity =================
    internal FrameworkElement BuildActivityPage()
    {
        var page = Ui.Page("Activity", "Everything you sent and received, newest first.");
        var entries = _peer is null ? SampleHistory() : _history;
        if (entries.Count > 0)
        {
            var clear = Ui.Button("Clear list", async () =>
            {
                if (_peer is null) return;
                var answer = System.Windows.MessageBox.Show(this, "Clear the activity list? Received files stay where they are.", "Clear activity", System.Windows.MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (answer != System.Windows.MessageBoxResult.OK) return;
                await _peer.ClearHistoryAsync(); _history = []; RenderCurrent();
            }, icon: SymbolRegular.Delete24);
            clear.HorizontalAlignment = HorizontalAlignment.Left; clear.Margin = new Thickness(0, 4, 0, 8);
            page.Children.Add(clear);
        }
        if (entries.Count == 0) page.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.History24, "No transfers yet", "Files you send and receive will appear here.", Ui.Button("Send files", () => Navigate("send"), true))));
        string day = "";
        foreach (var h in entries.OrderByDescending(x => x.At).Take(200))
        {
            string currentDay = h.At.LocalDateTime.ToString("dddd, MMM d");
            if (currentDay != day) { day = currentDay; var t = Ui.Subtle(day, 13); t.FontWeight = FontWeights.SemiBold; t.Margin = new Thickness(0, 12, 0, 6); page.Children.Add(t); }
            var device = _devices.FirstOrDefault(d => d.Id == h.PeerId);
            string peer = h.PeerId == "bluetooth" ? "Bluetooth" : device?.Alias ?? device?.Name ?? "a removed device";
            bool received = h.Direction == "received", ok = h.Result == "ok", cancelled = h.Result == "cancelled";
            var badge = Ui.Badge(ok ? (received ? SymbolRegular.ArrowDownload24 : SymbolRegular.ArrowUpload24) : SymbolRegular.ErrorCircle24,
                ok ? (received ? Palette.Success : Palette.OnPrimaryBg) : cancelled ? Palette.Amber : Palette.Danger,
                ok ? (received ? Palette.SuccessBg : Palette.PrimaryBg) : cancelled ? Palette.AmberBg : Palette.DangerBg, 40);
            string title = ok ? $"{(received ? "Received" : "Sent")} {Fmt.Files(h.FileCount)} {(received ? "from" : "to")} {peer}"
                : cancelled ? $"{(received ? "Receiving from" : "Sending to")} {peer} was cancelled"
                : received ? $"Receiving from {peer} stopped" : $"Couldn't send to {peer}";
            if (!ok && h.Files.Count > 0) title += $" ({h.FileCount} done)";
            string result = ok ? (h.Folder is null ? "" : $" · saved in {h.Folder}") : cancelled ? " · cancelled" : $" · {Platform.ExplainError(h.Result, peer)}";
            var actions = new List<UIElement>();
            bool exists = App.SnapshotMode || h.Files.Count > 0 && File.Exists(h.Files[0]);
            if (received && h.Files.Count == 1 && exists) actions.Add(Ui.Button("Open", () => Platform.OpenFile(h.Files[0]), icon: SymbolRegular.Open24));
            if (received && h.Files.Count > 0 && exists) { var show = Ui.Button("Show in folder", () => Platform.ShowInFolder(h.Files[0]), icon: SymbolRegular.FolderOpen24); show.Margin = new Thickness(0); actions.Add(show); }
            var row = Ui.ListRow(badge, title, $"{h.At.LocalDateTime:t}{result}", actions.ToArray());
            if (h.Files.Count > 0)
            {
                var names = Ui.Subtle(Fmt.Names(h.Files, received ? _peer?.ReceiveDirectory : null, 6));
                names.TextTrimming = TextTrimming.CharacterEllipsis; names.MaxHeight = 40; Ui.TextOf(row).Children.Add(names);
            }
            if (!ok && !cancelled) ((TextBlock)Ui.TextOf(row).Children[1]).Foreground = Palette.Danger;
            page.Children.Add(Ui.Card(row, 14, 6));
        }
        return Ui.Scroll(page);
    }

    // ================= Settings =================
    internal FrameworkElement BuildSettingsPage()
    {
        var page = Ui.Page("Settings", "How HopDrop looks, where files go, and how it runs on this PC.");

        page.Children.Add(Ui.Section("General"));
        var name = new TextBox { Text = _peer?.Name ?? "HopDrop Laptop", MaxLength = 40, Width = 220, Margin = new Thickness(0, 0, 8, 0) };
        var nameBox = new StackPanel { Orientation = Orientation.Horizontal };
        nameBox.Children.Add(name);
        nameBox.Children.Add(Ui.Button("Save", async () =>
        {
            string value = name.Text.Trim();
            if (value.Length is < 1 or > 40) { ShowError("Use 1–40 characters."); return; }
            if (_peer is not null) { await _peer.SetNameAsync(value); Platform.Notify("Name saved", $"Other devices now see this PC as {value}."); }
        }, true));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Laptop24, "This PC's name", "Shown to phones and other computers.", nameBox));
        var theme = new System.Windows.Controls.ComboBox { MinWidth = 210, ItemsSource = new[] { "Use Windows setting", "Light", "Dark" } };
        theme.SelectedIndex = Preferences.Theme switch { "light" => 1, "dark" => 2, _ => 0 };
        theme.SelectionChanged += (_, _) => { Preferences.Theme = theme.SelectedIndex switch { 1 => "light", 2 => "dark", _ => "system" }; ApplyTheme(); RenderCurrent(); RenderLive(); };
        page.Children.Add(Ui.SettingRow(SymbolRegular.DarkTheme24, "Appearance", "Light, dark, or follow Windows.", theme));

        page.Children.Add(Ui.Section("Receiving"));
        string folderPath = _peer?.ReceiveDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "HopDrop");
        var folderActions = new StackPanel { Orientation = Orientation.Horizontal };
        folderActions.Children.Add(Ui.Button("Change…", async () =>
        {
            var dialog = new OpenFolderDialog { Title = "Choose where HopDrop saves received files", InitialDirectory = _peer?.ReceiveDirectory };
            if (dialog.ShowDialog(this) == true && _peer is not null) { await _peer.SetReceiveDirectoryAsync(dialog.FolderName); RenderCurrent(); }
        }, icon: SymbolRegular.FolderSwap24));
        folderActions.Children.Add(Ui.Button("Open", () => { Directory.CreateDirectory(folderPath); Platform.OpenFolder(folderPath); }, icon: SymbolRegular.FolderOpen24));
        var reset = Ui.Button("Default", async () => { if (_peer is not null) { await _peer.SetReceiveDirectoryAsync(HopDropPaths.Default.DefaultReceiveDirectory); RenderCurrent(); } }, icon: SymbolRegular.ArrowReset24);
        reset.Margin = new Thickness(0); folderActions.Children.Add(reset);
        var path = Ui.Label(folderPath, 13, true); path.TextTrimming = TextTrimming.CharacterEllipsis; path.TextWrapping = TextWrapping.NoWrap; path.ToolTip = folderPath;
        var folderInfo = new StackPanel(); folderInfo.Children.Add(path);
        if (ZoneMark.Supported(folderPath) == false)
            folderInfo.Children.Add(Ui.Colored("This drive can't mark files as downloaded, so Windows won't warn before opening files from devices you haven't trusted.", Palette.Amber));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Folder24, "Receive folder", "Files from phones and other PCs are saved here.", folderActions, folderInfo));
        var ask = new ToggleSwitch { IsChecked = _peer?.AskBeforeReceiving == true };
        ask.Checked += async (_, _) => { if (_peer is not null) await _peer.SetAskBeforeReceivingAsync(true); };
        ask.Unchecked += async (_, _) => { if (_peer is not null) await _peer.SetAskBeforeReceivingAsync(false); };
        page.Children.Add(Ui.SettingRow(SymbolRegular.ShieldQuestion24, "Ask before receiving",
            "A notification asks you to accept or decline files from paired devices. Devices you trust (Devices page) skip the question.", ask));
        var tray = new ToggleSwitch { IsChecked = Preferences.CloseToTray };
        tray.Checked += (_, _) => Preferences.CloseToTray = true; tray.Unchecked += (_, _) => Preferences.CloseToTray = false;
        page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowMinimize24, "Close to the tray",
            "Closing the window keeps HopDrop's icon in the tray (next to the clock) so phones can still send. Quit from the tray icon.", tray));
        var background = new ToggleSwitch { IsChecked = Preferences.BackgroundReceive };
        background.Checked += (_, _) => Platform.SetBackgroundReceive(true); background.Unchecked += (_, _) => Platform.SetBackgroundReceive(false);
        page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowDownload24, "Receive in the background",
            "Phones can send to this PC even after you quit HopDrop, and after a restart. HopDrop keeps a small hidden receiver running (no window, no tray icon) and notifies you when files arrive. " +
            "To stop it, open HopDrop and choose \"Quit and stop receiving\" in the tray menu.", background));
        var run = new ToggleSwitch { IsChecked = Platform.StartWithWindows };
        run.Checked += (_, _) => Platform.SetStartWithWindows(true); run.Unchecked += (_, _) => Platform.SetStartWithWindows(false);
        page.Children.Add(Ui.SettingRow(SymbolRegular.Power24, "Start with Windows",
            "HopDrop starts hidden in the tray when you sign in, so this PC can receive without opening it.", run));

        page.Children.Add(Ui.Section("Privacy"));
        var visibility = new System.Windows.Controls.ComboBox { MinWidth = 210, ItemsSource = new[] { "Everyone on the network", "Only my paired devices" } };
        visibility.SelectedIndex = _peer?.Visibility == "paired" ? 1 : 0;
        visibility.SelectionChanged += async (_, _) => { if (_peer is not null) await _peer.SetVisibilityAsync(visibility.SelectedIndex == 1 ? "paired" : "everyone"); };
        page.Children.Add(Ui.SettingRow(SymbolRegular.EyeOff24, "Who can see this PC's name",
            "HopDrop tells devices on the same network that this PC is here. \"Only my paired devices\" hides the name from everyone else, " +
            "except while the Devices page is open for pairing. Paired devices still find and reach this PC.", visibility));

        page.Children.Add(Ui.Section("Windows integration"));
        if (Store.Packaged)
            page.Children.Add(Ui.SettingRow(SymbolRegular.Share24, "Explorer Share menu", "Right-click files in File Explorer → Share → HopDrop. They open here, ready to send.", null));
        else
        {
            var sendTo = new ToggleSwitch { IsChecked = Platform.SendToInstalled };
            sendTo.Checked += (_, _) => Platform.SetSendTo(true); sendTo.Unchecked += (_, _) => Platform.SetSendTo(false);
            page.Children.Add(Ui.SettingRow(SymbolRegular.Share24, "Explorer \"Send to\" menu", "Right-click files in File Explorer → Send to → HopDrop.", sendTo));
        }
        var firewall = Platform.FirewallStatus();
        var publicCheck = new System.Windows.Controls.CheckBox { Content = "Also on public networks (some hotspots are marked public)", Margin = new Thickness(0, 0, 0, 0) };
        string firewallText = firewall is null ? "Couldn't read Windows Firewall. If phones can't find this PC, click Allow."
            : firewall.Value.Private && firewall.Value.Public ? "Allowed on private and public networks."
            : firewall.Value.Private ? "Allowed on private networks. Public networks may block phones."
            : "Windows Firewall may block phones from reaching HopDrop. Click Allow (needs admin).";
        var firewallRow = Ui.SettingRow(SymbolRegular.Shield24, "Windows Firewall", firewallText,
            Ui.Button("Allow HopDrop", () => Platform.AllowFirewall(publicCheck.IsChecked == true), firewall is not { Private: true }, SymbolRegular.ShieldCheckmark24), publicCheck);
        page.Children.Add(firewallRow);

        page.Children.Add(Ui.Section("AI agents"));
        var agents = new ToggleSwitch { IsChecked = Preferences.AgentsEnabled };
        var agentSetup = new StackPanel();
        void ShowAgentSetup()
        {
            agentSetup.Children.Clear();
            if (!Preferences.AgentsEnabled) return;
            // The Store version's executable can't be started by its path; its "hopdrop.exe" command can.
            string exe = Store.Packaged ? Store.Alias : Environment.ProcessPath ?? "HopDrop.exe";
            bool customData = !string.Equals(App.DataDirectory, HopDropPaths.Default.DataDirectory, StringComparison.OrdinalIgnoreCase);
            string extra = customData ? $" --data \"{App.DataDirectory}\"" : "";
            var args = new List<string> { "--mcp" };
            if (customData) args.AddRange(["--data", App.DataDirectory]);
            string json = System.Text.Json.JsonSerializer.Serialize(new { mcpServers = new { hopdrop = new { command = exe, args } } }, new System.Text.Json.JsonSerializerOptions { WriteIndented = true });
            var copied = Ui.Subtle(""); copied.Visibility = Visibility.Collapsed;
            void Copy(string text, string where)
            {
                try { System.Windows.Clipboard.SetText(text); copied.Text = "Copied. " + where; copied.Foreground = Palette.Success; }
                catch (System.Runtime.InteropServices.ExternalException) { copied.Text = "Couldn't use the clipboard. Try again."; copied.Foreground = Palette.Danger; }
                copied.Visibility = Visibility.Visible;
            }
            var buttons = new WrapPanel { Margin = new Thickness(0, 4, 0, 6) };
            buttons.Children.Add(Ui.Button("Copy for Claude Code", () => Copy($"claude mcp add --scope user hopdrop -- \"{exe}\" --mcp{extra}", "Paste it in a terminal and press Enter."), icon: SymbolRegular.Copy24));
            buttons.Children.Add(Ui.Button("Copy for Codex", () => Copy($"codex mcp add hopdrop -- \"{exe}\" --mcp{extra}", "Paste it in a terminal and press Enter."), icon: SymbolRegular.Copy24));
            buttons.Children.Add(Ui.Button("Copy MCP settings (JSON)", () => Copy(json, "Paste it into the agent's MCP settings (Claude Desktop, Cursor and others)."), icon: SymbolRegular.Copy24));
            agentSetup.Children.Add(buttons);
            agentSetup.Children.Add(copied);
            agentSetup.Children.Add(Ui.Subtle("Then ask your agent things like \"send report.pdf to my phone\" or \"wait for the photo I send from my phone\". Scripts and agents without MCP can run: HopDrop.exe agent help"));
            if (!Updates.Installed && !Store.Packaged && !App.SnapshotMode)
                agentSetup.Children.Add(Ui.Colored("This copy of HopDrop isn't installed. If you move HopDrop.exe, copy the setup again.", Palette.Amber));
        }
        agents.Checked += (_, _) => { Preferences.AgentsEnabled = true; ShowAgentSetup(); };
        agents.Unchecked += (_, _) => { Preferences.AgentsEnabled = false; ShowAgentSetup(); };
        ShowAgentSetup();
        page.Children.Add(Ui.SettingRow(SymbolRegular.Bot24, "Let AI agents use HopDrop",
            "AI assistants on this PC (Claude, Codex, Cursor and others that support MCP) can list your paired devices, send files and folders to them, and find files you received. " +
            "They can't pair new devices, change settings or accept files for you, and you see their transfers here like your own.", agents, agentSetup));

        page.Children.Add(Ui.Section("Bluetooth"));
        var bluetoothButtons = new StackPanel { Orientation = Orientation.Horizontal };
        bluetoothButtons.Children.Add(Ui.Button("Receive", ReceiveBluetooth, true, SymbolRegular.ArrowDownload24));
        var sendBluetooth = Ui.Button("Send", BluetoothWizard.Send, icon: SymbolRegular.ArrowUpload24); sendBluetooth.Margin = new Thickness(0);
        bluetoothButtons.Children.Add(sendBluetooth);
        page.Children.Add(Ui.SettingRow(SymbolRegular.Bluetooth24, "Bluetooth file transfer",
            "For when there's no shared network. Receive: HopDrop opens Windows' Bluetooth window in receive mode and moves arriving files into your receive folder. " +
            "The phone must be paired with this PC in Windows Bluetooth settings. Much slower than Wi-Fi.", bluetoothButtons));

        page.Children.Add(Ui.Section("About"));
        string id = (_peer?.Id ?? new string('a', 64))[..8].ToUpperInvariant();
        page.Children.Add(Ui.SettingRow(SymbolRegular.Info24, "HopDrop " + typeof(MainWindow).Assembly.GetName().Version?.ToString(3),
            $"Private file transfer on your own network. No cloud, no accounts. Device ID {id[..4]} {id[4..]}.", null));
        if (Updates.Installed)
        {
            UIElement action = Updates.ReadyVersion is not null
                ? Ui.Button("Restart to update", Updates.RestartNow, true, SymbolRegular.ArrowSync24)
                : Ui.Button("Check now", async () => await Updates.CheckAsync(true), icon: SymbolRegular.ArrowSync24);
            page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowSync24, "Updates",
                "HopDrop checks once a day and installs updates when you're not using it.", action,
                Updates.Status.Length > 0 ? Ui.Subtle(Updates.Status) : null));
        }
        else if (Store.Packaged) page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowSync24, "Updates", "The Microsoft Store keeps HopDrop up to date.", null));
        else page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowSync24, "Updates",
            "This copy runs without being installed, so it doesn't update itself. Install HopDrop with its Setup file to get automatic updates.", null));
        return Ui.Scroll(page);
    }
}
