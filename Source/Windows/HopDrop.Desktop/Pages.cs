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
    private Button? _sendMany;
    private readonly HashSet<string> _selectedDevices = [];
    private string? _sampleQueued;
    private readonly Dictionary<string, (TextBlock Presence, System.Windows.Shapes.Ellipse Dot, TextBlock Status, ProgressBar Bar, Button Send)> _deviceRows = [];

    internal FrameworkElement BuildSendPage()
    {
        var page = Ui.Page(L.T("Send"), L.T("PickFilesThenChooseAPairedDeviceEverythingStaysOnYourLocalNetwork"));
        var dropContent = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 8, 0, 8) };
        var badge = Ui.Badge(SymbolRegular.ArrowUpload24, Palette.OnPrimaryBg, Palette.PrimaryBg, 56); badge.HorizontalAlignment = HorizontalAlignment.Center;
        dropContent.Children.Add(badge);
        var dropTitle = Ui.Label(L.T("DropFilesOrFoldersHere"), 18, true); dropTitle.HorizontalAlignment = HorizontalAlignment.Center; dropTitle.Margin = new Thickness(0, 10, 0, 2);
        dropContent.Children.Add(dropTitle);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 12, 0, 0) };
        buttons.Children.Add(Ui.Button(L.T("BrowseFiles"), Browse, true, SymbolRegular.DocumentAdd24));
        buttons.Children.Add(Ui.Button(L.T("AddAFolder"), BrowseFolder, false, SymbolRegular.FolderAdd24));
        buttons.Children.Add(Ui.Button(L.T("Text"), WriteText, false, SymbolRegular.TextDescription24));
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
        var headerText = new StackPanel(); headerText.Children.Add(Ui.Label(L.T("FilesToSend"), 16, true));
        _fileSummary = Ui.Subtle(""); headerText.Children.Add(_fileSummary); header.Children.Add(headerText);
        _clearFiles = Ui.Button(L.T("ClearAll"), () => { _files.Clear(); _sizes.Clear(); RefreshFiles(); }, icon: SymbolRegular.Delete24); _clearFiles.Margin = new Thickness(0);
        Grid.SetColumn(_clearFiles, 1); header.Children.Add(_clearFiles);
        files.Children.Add(header);
        _fileHost = new StackPanel { Margin = new Thickness(0, 8, 0, 0) }; files.Children.Add(_fileHost);
        page.Children.Add(Ui.Card(files, 18, 4));
        RefreshFiles();

        page.Children.Add(Ui.Section(L.T("SendTo")));
        _sendMany = Ui.Button(L.T("SendTo2Devices"), () =>
        {
            foreach (var device in _devices.Where(d => _selectedDevices.Contains(d.Id)).ToArray()) _ = SendToAsync(device);
        }, true, SymbolRegular.Send24);
        _sendMany.Visibility = Visibility.Collapsed; page.Children.Add(_sendMany);
        _deviceHost = new StackPanel(); page.Children.Add(_deviceHost); FillSendDevices();

        page.Children.Add(Ui.Section(L.T("OtherWays")));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Bluetooth24, L.T("SendWithBluetooth"),
            L.T("RarelyNeededPhonesReachThisPCDirectlyEvenWithoutASharedWiFiOpensW"),
            Ui.Button(L.T("SendViaBluetooth"), BluetoothWizard.Send, icon: SymbolRegular.Bluetooth24)));
        return Ui.Scroll(page);
    }
    private void Browse()
    {
        var picker = new OpenFileDialog { Multiselect = true, Title = L.T("ChooseFilesToSend") };
        if (picker.ShowDialog(this) == true) AddFiles(picker.FileNames);
    }
    private void BrowseFolder()
    {
        var picker = new OpenFolderDialog { Multiselect = true, Title = L.T("ChooseFoldersToSend") };
        if (picker.ShowDialog(this) == true) AddFiles(picker.FolderNames);
    }
    private async void WriteText()
    {
        if (_devices.Count == 0) { Navigate("devices"); return; }
        var dialog = new TextNoteDialog(_devices, ClipboardShare.ReadText()) { Owner = this };
        if (dialog.ShowDialog() != true) return;
        if (dialog.DeviceId is null) return;
        try { await App.Instance.Queue.SendTextAsync(dialog.DeviceId, dialog.Text); }
        catch (Exception ex) { ShowError(Platform.ExplainException(ex, _devices.First(d => d.Id == dialog.DeviceId).Name)); }
    }
    /// <summary>The folder shown under a picked item; --render-pages pictures show a neutral sample location, not where the samples were written.</summary>
    private static string ShownFolder(string? folder) => App.SnapshotMode ? @"C:\Users\Sam\Desktop" : folder ?? "";
    private void AddFiles(IEnumerable<string> paths)
    {
        int previousCount = _files.Count;
        try
        {
            var queued = _files.Select(f => f.Path).ToHashSet(StringComparer.OrdinalIgnoreCase);
            _files.AddRange(SendItem.From(paths).Where(item => queued.Add(item.Path)));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { ShowError(L.T("HopDropCouldnTReadThatFolder") + ex.Message); }
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
        return folders == 0 ? Fmt.Files(loose) : loose == 0 ? Fmt.Folders(folders) : L.T("0And1", Fmt.Folders(folders), Fmt.Files(loose));
    }
    private void RefreshFiles(IReadOnlySet<string>? added = null)
    {
        if (_fileHost is null || _fileSummary is null) return;
        _fileHost.Children.Clear();
        long total = _files.Sum(item => Math.Max(0, SizeOf(item.Path)));
        _fileSummary.Text = _files.Count == 0 ? L.T("NothingSelectedYet") : $"{SelectionSummary()} · {Fmt.Size(total)}";
        if (_clearFiles is not null) _clearFiles.Visibility = _files.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        if (_files.Count == 0) _fileHost.Children.Add(Ui.Subtle(L.T("DropFilesOrFoldersAboveOrClickBrowseYouCanAddAsManyAsYouLike")));
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
            var remove = Ui.IconButton(SymbolRegular.Dismiss24, L.T("RemoveFolder") + name, () =>
            {
                _files.RemoveAll(item => string.Equals(item.Root, root, StringComparison.OrdinalIgnoreCase));
                RefreshFiles();
            });
            var row = Ui.ListRow(Ui.Badge(SymbolRegular.Folder24, Palette.Amber, Palette.AmberBg, 36), name,
                L.T("Folder012", Fmt.Files(folder.Count()), Fmt.Size(size), ShownFolder(Path.GetDirectoryName(Path.TrimEndingDirectorySeparator(root)))), remove);
            row.ToolTip = root;
            _fileHost.Children.Add(row);
            Appear(row, added is not null && folder.Any(item => added.Contains(item.Path)));
        }
        var loose = _files.Where(item => item.Root is null).ToArray();
        foreach (var item in loose.Take(200))
        {
            string path = item.Path;
            var remove = Ui.IconButton(SymbolRegular.Dismiss24, L.T("Remove") + Path.GetFileName(path), () => { _files.Remove(item); RefreshFiles(); });
            var row = Ui.ListRow(Ui.Badge(Ui.FileIcon(path), Palette.OnPrimaryBg, Palette.PrimaryBg, 36), Path.GetFileName(path), $"{Fmt.Size(SizeOf(path))} · {ShownFolder(Path.GetDirectoryName(path))}", remove);
            row.ToolTip = path;
            _fileHost.Children.Add(row);
            Appear(row, added?.Contains(path) == true);
        }
        if (loose.Length > 200) _fileHost.Children.Add(Ui.Subtle(L.T("And0More", Fmt.Files(loose.Length - 200))));
        UpdateSendDevices();
    }
    private void FillSendDevices()
    {
        if (_deviceHost is null) return;
        _deviceHost.Children.Clear(); _deviceRows.Clear();
        var devices = _peer is null ? SampleDevices() : _devices;
        if (devices.Count == 0)
        {
            _deviceHost.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.PhoneLaptop24, L.T("NoPairedDevicesYet"),
                L.T("PairOnceInDevicesScanAQRCodeWithYourPhoneAfterThatSendingIsOneCli"),
                Ui.Button(L.T("PairADevice"), () => Navigate("devices"), true))));
            return;
        }
        foreach (var device in devices.OrderBy(d => d.Alias ?? d.Name))
        {
            var badge = Ui.Badge(device.Platform == "android" ? SymbolRegular.Phone24 : SymbolRegular.Laptop24, Palette.OnPrimaryBg, Palette.PrimaryBg, 44);
            var send = Ui.Button(L.T("Send"), () =>
            {
                if (_peer is not null && App.Instance.Queue.Has(device.Id)) App.Instance.Queue.Cancel(device.Id);
                else _ = SendToAsync(device);
            }, true, SymbolRegular.Send24); send.Margin = new Thickness(0);
            var row = Ui.ListRow(badge, device.Alias ?? device.Name, null, send);
            if (devices.Count >= 2)
            {
                var check = new System.Windows.Controls.CheckBox { IsChecked = _selectedDevices.Contains(device.Id), ToolTip = L.T("Select") + (device.Alias ?? device.Name), Margin = new Thickness(8, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center };
                check.Checked += (_, _) => { _selectedDevices.Add(device.Id); UpdateMany(); };
                check.Unchecked += (_, _) => { _selectedDevices.Remove(device.Id); UpdateMany(); };
                ((StackPanel)row.Children[2]).Children.Insert(0, check);
            }
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
                if (_peer is not null && App.Instance.Queue.Has(device.Id)) return;
                _ = SendToAsync(device);
            };
            _deviceHost.Children.Add(card);
        }
        UpdateSendDevices();
    }
    private void UpdateMany()
    {
        if (_sendMany is null) return;
        _sendMany.Visibility = _selectedDevices.Count >= 2 ? Visibility.Visible : Visibility.Collapsed;
        _sendMany.Content = L.T("SendTo0Devices", _selectedDevices.Count);
        _sendMany.IsEnabled = _files.Count > 0;
    }
    /// <summary>Updates online state and live progress under each device, in place.</summary>
    private void UpdateSendDevices()
    {
        foreach (var (id, row) in _deviceRows)
        {
            var device = _devices.FirstOrDefault(d => d.Id == id);
            bool online = _peer?.Nearby.Any(n => n.Id == id) == true;
            row.Presence.Text = online ? L.T("Online") : device is null ? L.T("Offline") : L.T("OfflineSeen0", Fmt.Ago(device.LastSeen));
            row.Presence.Foreground = online ? Palette.Success : Palette.Muted; row.Dot.Fill = row.Presence.Foreground;
            var item = Outgoing(id);
            bool busy = item is not null && !item.Finished;
            bool queued = _peer is not null && App.Instance.Queue.Has(id) || App.SnapshotMode && _sampleQueued == id;
            row.Send.Content = queued ? L.T("Cancel") : L.T("Send");
            row.Send.IsEnabled = queued || _files.Count > 0 && !busy;
            row.Send.ToolTip = !queued && _files.Count == 0 ? L.T("AddFilesFirst") : null;
            row.Bar.Visibility = busy ? Visibility.Visible : Visibility.Collapsed;
            row.Status.Visibility = item is null && !queued ? Visibility.Collapsed : Visibility.Visible;
            if (queued) { row.Presence.Text = L.T("OfflineSendsWhenItSBack"); row.Presence.Foreground = Palette.Muted; row.Dot.Fill = Palette.Muted; row.Status.Text = SendQueue.WaitingHint(device); row.Status.Foreground = Palette.Muted; row.Bar.Visibility = Visibility.Collapsed; continue; }
            if (item is null) continue;
            if (item.Failure is not null) { row.Status.Text = item.Failure; row.Status.Foreground = Palette.Danger; }
            else if (item.Result is TransferResult r)
            {
                row.Status.Text = r.ErrorCode is null ? L.T("Sent01In2", Fmt.Files(r.SavedPaths.Count), Fmt.Size(r.Bytes), Fmt.Duration(r.Duration)) : Platform.ExplainError(r.ErrorCode, item.PeerName);
                row.Status.Foreground = r.ErrorCode is null ? Palette.Success : Palette.Danger;
            }
            else if (item.Progress is TransferProgress { Reconnecting: true })
            {
                row.Status.Text = L.T("ConnectionDroppedReconnecting"); row.Status.Foreground = Palette.Muted; row.Bar.IsIndeterminate = true;
            }
            else if (item.Progress is TransferProgress { WaitingForApproval: true })
            {
                row.Status.Text = L.T("WaitingFor0ToAccept", item.PeerName); row.Status.Foreground = Palette.Muted; row.Bar.IsIndeterminate = true;
            }
            else if (item.Progress is TransferProgress p)
            {
                row.Status.Text = Fmt.Amount(p.BytesDone, p.TotalBytes) + (p.Eta is null ? "" : L.T("0Left", Fmt.Duration(p.Eta.Value)));
                row.Status.Foreground = Palette.Muted;
                row.Bar.IsIndeterminate = p.Percent < 0; if (p.Percent >= 0) Motion.SmoothValue(row.Bar, p.BytesDone * 100.0 / p.TotalBytes);
            }
            else { row.Status.Text = L.T("Connecting"); row.Status.Foreground = Palette.Muted; row.Bar.IsIndeterminate = true; }
        }
        UpdateMany();
    }
    private async Task SendToAsync(PairedDevice device)
    {
        if (_peer is null) return;
        if (_files.Count == 0) { ShowError(L.T("AddAtLeastOneFileFirst")); return; }
        if (App.Instance.Queue.Has(device.Id)) return;
        var current = Outgoing(device.Id);
        if (current is not null && !current.Finished) return;
        Connecting(device); UpdateSendDevices();
        try { await App.Instance.Queue.SendFilesAsync(device.Id, _files.ToList()); }
        catch (Exception ex)
        {
            string name = device.Alias ?? device.Name;
            if (_live.ContainsKey("pending:" + device.Id))
            {
                string reason = Platform.ExplainException(ex, name);
                FailedToStart(device, reason);
            }
        }
        finally { UpdateSendDevices(); }
    }

    // ================= Devices =================
    private DispatcherTimer? _qrTimer;
    private DateTimeOffset _qrExpiry;
    private string? _qrUri;
    internal bool HasPairingCode => _qrUri is not null;
    private StackPanel? _qrHost;
    private string? _qrSuccessName;
    private StackPanel? _pairedHost;
    private StackPanel? _nearbyHost;
    private string _nearbyShown = "";

    internal FrameworkElement BuildDevicesPage()
    {
        var page = Ui.Page(L.T("Devices"), L.T("PairOnceAndDevicesRecogniseEachOtherFromThenOnInBothDirections"));
        page.Children.Add(Ui.Section(L.T("ThisComputer")));
        var thisPc = new StackPanel();
        var pcChips = new StackPanel { Orientation = Orientation.Horizontal };
        pcChips.Children.Add(Ui.Chip(L.T("Ready"), Palette.Success, Palette.SuccessBg));
        var linkState = _peer is null ? DirectLinkState.On : App.Instance.Link.State;
        string linkLabel = linkState switch { DirectLinkState.On => L.T("DirectLinkOn"), DirectLinkState.Off => L.T("DirectLinkOff"), DirectLinkState.Unavailable => App.Instance.Link.Reason, DirectLinkState.Starting => L.T("DirectLinkStarting"), _ => L.T("DirectLinkWaiting") };
        pcChips.Children.Add(Ui.Chip(linkLabel, linkState == DirectLinkState.On ? Palette.Success : Palette.Muted, linkState == DirectLinkState.On ? Palette.SuccessBg : Palette.PrimaryBg));
        thisPc.Children.Add(Ui.ListRow(Ui.Badge(SymbolRegular.Laptop24, Palette.OnPrimaryBg, Palette.PrimaryBg, 44), _peer?.Name ?? "HopDrop Laptop",
            L.T("ReadyToReceiveFromPairedDevicesWhileHopDropIsRunningAlsoFromTheTr"), pcChips));
        var addresses = _peer is null ? [new NetworkAddresses.LocalAddress(IPAddress.Parse("192.168.1.5"), L.T("WiFi"))] : NetworkAddresses.Describe();
        if (addresses.Count == 0) thisPc.Children.Add(Ui.Colored(L.T("NotConnectedToALocalNetworkJoinWiFiOrTurnOnWindowsMobileHotspot"), Palette.Danger));
        var addressRow = new WrapPanel { Margin = new Thickness(58, 6, 0, 0) };
        foreach (var address in addresses)
        {
            var chip = new StackPanel { Orientation = Orientation.Horizontal };
            chip.Children.Add(new TextBlock { Text = address.Kind + "  ", FontSize = 13, Foreground = Palette.Muted, VerticalAlignment = VerticalAlignment.Center });
            chip.Children.Add(new TextBlock { Text = address.Address.ToString(), FontSize = 14, FontWeight = FontWeights.SemiBold, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) });
            var copy = Ui.IconButton(SymbolRegular.Copy24, L.T("Copy") + address.Address, () => { App.Instance.PutClipboardText(address.Address.ToString()); });
            chip.Children.Add(copy);
            addressRow.Children.Add(new Border { Child = chip, Background = Palette.SurfaceAlt, CornerRadius = new CornerRadius(8), Padding = new Thickness(10, 2, 2, 2), Margin = new Thickness(0, 0, 8, 6) });
        }
        thisPc.Children.Add(addressRow);
        page.Children.Add(Ui.Card(thisPc));

        page.Children.Add(Ui.Section(L.T("PairAPhone")));
        _qrHost = new StackPanel(); page.Children.Add(Ui.Card(_qrHost));
        if (_qrSuccessName is not null) ShowQrSuccess(); else ShowQr();
        if (_devices.Count == 0)
        {
            var pairSection = page.Children[4]; var pairCard = page.Children[5];
            page.Children.Remove(pairCard); page.Children.Remove(pairSection);
            page.Children.Insert(2, pairSection); page.Children.Insert(3, pairCard);
        }

        var pairedHeader = new Grid(); pairedHeader.ColumnDefinitions.Add(new ColumnDefinition()); pairedHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        pairedHeader.Children.Add(Ui.Section(L.T("PairedDevices")));
        page.Children.Add(pairedHeader);
        _pairedHost = new StackPanel(); page.Children.Add(_pairedHost);
        page.Children.Add(Ui.Subtle(L.T("PairingIsPermanentPairedDevicesStayPairedUntilYouRemoveThemHereOr")));

        var nearbyHeader = new Grid(); nearbyHeader.ColumnDefinitions.Add(new ColumnDefinition()); nearbyHeader.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        nearbyHeader.Children.Add(Ui.Section(L.T("NearbyDevices")));
        var nearbyActions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, 10) };
        nearbyActions.Children.Add(Ui.Button(L.T("Refresh"), () => _ = _peer?.QueryNearbyAsync(), icon: SymbolRegular.ArrowClockwise24));
        var ip = Ui.Button(L.T("PairByIPAddress"), AddByIp, icon: SymbolRegular.Globe24); ip.Margin = new Thickness(0); nearbyActions.Children.Add(ip);
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
        if (devices.Count == 0) { _pairedHost.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.PhoneLaptop24, L.T("NoPairedDevicesYet"), L.T("ScanTheQRCodeAboveWithHopDropOnYourPhoneOrPickANearbyDeviceBelow")))); return; }
        foreach (var d in devices.OrderBy(d => d.Alias ?? d.Name))
        {
            bool online = _peer is null || _peer.Nearby.Any(n => n.Id == d.Id);
            var rename = Ui.Button(L.T("Rename"), async () =>
            {
                string? alias = Prompt(L.T("RenameDevice"), L.T("ChooseANameForThisDeviceOnThisPC"), d.Alias ?? d.Name);
                if (alias is null || _peer is null) return;
                if (alias.Length > 40) { ShowError(L.T("UseAtMost40Characters")); return; }
                await _peer.SetAliasAsync(d.Id, alias);
            }, icon: SymbolRegular.Rename24);
            var remove = Ui.Button(L.T("Remove_c3812fc4"), async () =>
            {
                if (_peer is null) return;
                var answer = System.Windows.MessageBox.Show(this, L.T("Remove0YouLlNeedToPairAgainToSendFilesBetweenTheseDevices", d.Alias ?? d.Name), L.T("RemoveDevice"), System.Windows.MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (answer == System.Windows.MessageBoxResult.OK) await _peer.UnpairAsync(d.Id);
            }, icon: SymbolRegular.Delete24);
            remove.Margin = new Thickness(0);
            string subtitle = L.T("0Paired1MMMDYyyy", Fmt.PlatformName(d.Platform), d.PairedAt.LocalDateTime) + (d.LastAddresses.Count > 0 ? $" · {d.LastAddresses[0]}" : "");
            var row = Ui.ListRow(Ui.Badge(d.Platform == "android" ? SymbolRegular.Phone24 : SymbolRegular.Laptop24, Palette.OnPrimaryBg, Palette.PrimaryBg, 44), d.Alias ?? d.Name, subtitle, rename, remove);
            Ui.TextOf(row).Children.Add(Ui.Status(online ? L.T("OnlineNow") : L.T("OfflineSeen0", Fmt.Ago(d.LastSeen)), online ? Palette.Success : Palette.Muted));
            var trust = new ToggleSwitch
            {
                IsChecked = d.Trusted, Content = L.T("TrustFilesFromThisDevice"), Margin = new Thickness(0, 8, 0, 0),
                ToolTip = L.T("TrustedFilesArriveWithoutAskingAndOpenWithoutWindowsDownloadedFil")
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
            _nearbyHost.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.Search24, L.T("Searching"),
                L.T("NearbyDevicesNeedTheSameWiFiThePairingQRAboveWorksWithoutItYouCan"))));

            return;
        }
        foreach (var n in nearby.Where(n => n.Name.Length > 0 || _devices.Any(d => d.Id == n.Id)).OrderBy(n => n.Name))
        {
            bool paired = _devices.Any(d => d.Id == n.Id);
            UIElement action = paired ? Ui.Chip(L.T("Paired"), Palette.Success, Palette.SuccessBg) : Ui.Button(L.T("Pair"), () => _ = PairSasAsync(n.Address, n.Port), true, SymbolRegular.Link24);
            var badge = Ui.Badge(n.Platform == "android" ? SymbolRegular.Phone24 : SymbolRegular.Laptop24, paired ? Palette.Success : Palette.OnPrimaryBg, paired ? Palette.SuccessBg : Palette.PrimaryBg, 44);
            string shown = n.Name.Length > 0 ? n.Name : _devices.First(d => d.Id == n.Id) is var known ? known.Alias ?? known.Name : "";
            var row = Ui.ListRow(badge, shown, $"{Fmt.PlatformName(n.Platform)} · {n.Address}" + (paired ? L.T("ReadyToSend") : L.T("PairByComparingA6DigitNumber")), action);
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
            Cursor = System.Windows.Input.Cursors.Hand, ToolTip = L.T("ClickToShowABiggerCode") };
        frame.MouseLeftButtonUp += (_, _) => new QrZoomWindow(QrImage(shownUri), _peer?.DirectLink is not null || App.SnapshotMode) { Owner = this }.ShowDialog();
        row.Children.Add(frame);
        var info = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        info.Children.Add(Ui.Label(L.T("ScanWithHopDropOnYourPhone"), 18, true));
        info.Children.Add(Ui.Subtle(L.T("OnYourPhoneHopDropPairScanClickTheCodeToMakeItBigger")));
        if (_peer?.DirectLink is not null || App.SnapshotMode) info.Children.Add(Ui.Subtle(L.T("YourPhoneDoesnTNeedToBeOnTheSameWiFi")));
        var timer = Ui.Label("", 14, true); timer.Margin = new Thickness(0, 14, 0, 4); info.Children.Add(timer);
        info.Children.Add(Ui.Subtle(L.T("EachCodeWorksOnceANewOneAppearsEvery5Minutes")));
        Grid.SetColumn(info, 1); row.Children.Add(info); _qrHost.Children.Add(row);
        void Tick()
        {
            var left = _qrExpiry - DateTimeOffset.UtcNow;
            if (left <= TimeSpan.Zero) { _qrUri = null; ShowQr(); } else timer.Text = L.T("CodeValidFor0100", left.Minutes, left.Seconds);
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
        var again = Ui.Button(L.T("PairAnotherDevice"), () => { _qrSuccessName = null; ShowQr(); }, true);
        var success = Ui.ListRow(Ui.Badge(SymbolRegular.CheckmarkCircle24, Palette.Success, Palette.SuccessBg, 44),
            L.T("PairedWith") + _qrSuccessName, L.T("YouCanSendFilesBothWaysNowNoCodesNeededNextTime"), again);
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
        var image = new Image { Source = new DrawingImage(drawing), Stretch = Stretch.Uniform, FlowDirection = FlowDirection.LeftToRight };
        RenderOptions.SetEdgeMode(image, EdgeMode.Aliased);
        return image;
    }
    private void AddByIp()
    {
        string? value = Prompt(L.T("PairByIPAddress"), L.T("TypeTheOtherDeviceSAddressOnAPhoneItSUnderHopDropDevicesThisPhone"), "192.168.1.10");
        if (value is null) return;
        var parts = value.Trim().Split(':', 2);
        if (!IPAddress.TryParse(parts[0], out var address) || address.AddressFamily != System.Net.Sockets.AddressFamily.InterNetwork || !NetworkAddresses.IsPrivateOrLoopback(address) || parts.Length == 2 && !int.TryParse(parts[1], out _))
        { ShowError(L.T("EnterALocalAddressLike192168123")); return; }
        int port = parts.Length == 2 ? int.Parse(parts[1]) : 7410;
        if (port is < 1 or > 65535) { ShowError(L.T("PortMustBeBetween1And65535")); return; }
        _ = PairSasAsync(address, port);
    }
    private async Task PairSasAsync(IPAddress address, int port)
    {
        if (_peer is null) return;
        try { await _peer.PairSasAsync(address, port); }
        catch (Exception ex) { ShowError(Platform.ExplainException(ex, L.T("TheDevice"))); }
    }

    // ================= Activity =================
    internal FrameworkElement BuildActivityPage()
    {
        var page = Ui.Page(L.T("Activity"), L.T("EverythingYouSentAndReceivedNewestFirst"));
        var entries = _peer is null ? SampleHistory() : _history;
        if (entries.Count > 0)
        {
            var clear = Ui.Button(L.T("ClearList"), async () =>
            {
                if (_peer is null) return;
                var answer = System.Windows.MessageBox.Show(this, L.T("ClearTheActivityListReceivedFilesStayWhereTheyAre"), L.T("ClearActivity"), System.Windows.MessageBoxButton.OKCancel, MessageBoxImage.Question);
                if (answer != System.Windows.MessageBoxResult.OK) return;
                await _peer.ClearHistoryAsync(); _history = []; RenderCurrent();
            }, icon: SymbolRegular.Delete24);
            clear.HorizontalAlignment = HorizontalAlignment.Left; clear.Margin = new Thickness(0, 4, 0, 8);
            page.Children.Add(clear);
        }
        if (entries.Count == 0) page.Children.Add(Ui.Card(Ui.EmptyState(SymbolRegular.History24, L.T("NoTransfersYet"), L.T("FilesYouSendAndReceiveWillAppearHere"), Ui.Button(L.T("SendFiles"), () => Navigate("send"), true))));
        string day = "";
        foreach (var h in entries.OrderByDescending(x => x.At).Take(200))
        {
            string currentDay = h.At.LocalDateTime.ToString(L.Arabic ? "dddd، d MMMM" : "dddd, MMM d", L.Culture);
            if (currentDay != day) { day = currentDay; var t = Ui.Subtle(day, 13); t.FontWeight = FontWeights.SemiBold; t.Margin = new Thickness(0, 12, 0, 6); page.Children.Add(t); }
            var device = _devices.FirstOrDefault(d => d.Id == h.PeerId);
            string peer = h.PeerId == "bluetooth" ? L.T("Bluetooth") : device?.Alias ?? device?.Name ?? L.T("ARemovedDevice");
            bool received = h.Direction == "received", ok = h.Result == "ok", cancelled = h.Result == "cancelled";
            var badge = Ui.Badge(ok ? (received ? SymbolRegular.ArrowDownload24 : SymbolRegular.ArrowUpload24) : SymbolRegular.ErrorCircle24,
                ok ? (received ? Palette.Success : Palette.OnPrimaryBg) : cancelled ? Palette.Amber : Palette.Danger,
                ok ? (received ? Palette.SuccessBg : Palette.PrimaryBg) : cancelled ? Palette.AmberBg : Palette.DangerBg, 40);
            if (h.Text is { } textValue)
            {
                var textActions = new List<UIElement> { Ui.Button(L.T("Copy_e21f935f"), () => App.Instance.PutClipboardText(textValue), icon: SymbolRegular.Copy24) };
                if (Platform.IsWebLink(textValue)) textActions.Add(Ui.Button(L.T("OpenLink"), () => Platform.OpenLink(textValue), icon: SymbolRegular.Open24));
                var textRow = Ui.ListRow(badge, received ? L.T("TextFrom", peer) : L.T("TextTo", peer), h.At.LocalDateTime.ToString("t", L.Culture) + " · " + L.Transport(h.Transport), textActions.ToArray());
                var preview = Ui.Subtle(textValue); preview.TextWrapping = TextWrapping.Wrap; preview.TextTrimming = TextTrimming.CharacterEllipsis;
                preview.MaxHeight = 54;
                Ui.TextOf(textRow).Children.Add(preview);
                page.Children.Add(Ui.Card(textRow, 14, 6));
                continue;
            }
            string title = ok ? (received ? L.T("ReceivedFilesFrom", Fmt.Files(h.FileCount), peer) : L.T("SentFilesTo", Fmt.Files(h.FileCount), peer))
                : cancelled ? (received ? L.T("ReceivingCancelled", peer) : L.T("SendingCancelled", peer))
                : received ? L.T("ReceivingFrom0Stopped", peer) : L.T("CouldnTSendTo0", peer);
            if (!ok && h.Files.Count > 0) title += L.T("0Done", h.FileCount);
            string result = ok ? (h.Folder is null ? "" : L.T("SavedIn0", h.Folder)) : cancelled ? L.T("Cancelled") : $" · {Platform.ExplainError(h.Result, peer)}";
            var actions = new List<UIElement>();
            bool exists = App.SnapshotMode || h.Files.Count > 0 && File.Exists(h.Files[0]);
            if (received && h.Files.Count == 1 && exists) actions.Add(Ui.Button(L.T("Open"), () => Platform.OpenFile(h.Files[0]), icon: SymbolRegular.Open24));
            if (received && h.Files.Count > 0 && exists) { var show = Ui.Button(L.T("ShowInFolder"), () => Platform.ShowInFolder(h.Files[0]), icon: SymbolRegular.FolderOpen24); show.Margin = new Thickness(0); actions.Add(show); }
            var row = Ui.ListRow(badge, title, h.At.LocalDateTime.ToString("t", L.Culture) + result + " · " + L.Transport(h.Transport), actions.ToArray());
            if (h.Files.Count > 0)
            {
                var names = Ui.Subtle(Fmt.Names(h.Files, received ? _peer?.ReceiveDirectory : null, 6)); names.FlowDirection = FlowDirection.LeftToRight;
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
        var page = Ui.Page(L.T("Settings"), L.T("HowHopDropLooksWhereFilesGoAndHowItRunsOnThisPC"));

        page.Children.Add(Ui.Section(L.T("General")));
        var language = new System.Windows.Controls.ComboBox { MinWidth = 210, ItemsSource = new[] { "English", "العربية" }, FlowDirection = FlowDirection.LeftToRight };
        language.SelectedIndex = Preferences.Language == "ar" ? 1 : 0;
        language.SelectionChanged += (_, _) =>
        {
            if (language.SelectedIndex is not (0 or 1)) return;
            string selected = language.SelectedIndex == 1 ? "ar" : "en";
            if (selected != Preferences.Language) ChangeLanguage(selected);
        };
        page.Children.Add(Ui.SettingRow(SymbolRegular.LocalLanguage24, L.T("Language"), L.T("LanguageDescription"), language));
        var name = new TextBox { Text = _peer?.Name ?? "HopDrop Laptop", MaxLength = 40, Width = 220, Margin = new Thickness(0, 0, 8, 0) };
        var nameBox = new StackPanel { Orientation = Orientation.Horizontal };
        nameBox.Children.Add(name);
        nameBox.Children.Add(Ui.Button(L.T("Save"), async () =>
        {
            string value = name.Text.Trim();
            if (value.Length is < 1 or > 40) { ShowError(L.T("Use140Characters")); return; }
            if (_peer is not null) { await _peer.SetNameAsync(value); Platform.Notify(L.T("NameSaved"), L.T("OtherDevicesNowSeeThisPCAs0", value)); }
        }, true));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Laptop24, L.T("ThisPCSName"), L.T("ShownToPhonesAndOtherComputers"), nameBox));
        var theme = new System.Windows.Controls.ComboBox { MinWidth = 210, ItemsSource = new[] { L.T("UseWindowsSetting"), L.T("Light"), L.T("Dark") } };
        theme.SelectedIndex = Preferences.Theme switch { "light" => 1, "dark" => 2, _ => 0 };
        theme.SelectionChanged += (_, _) => { Preferences.Theme = theme.SelectedIndex switch { 1 => "light", 2 => "dark", _ => "system" }; ApplyTheme(); RenderCurrent(); RenderLive(); };
        page.Children.Add(Ui.SettingRow(SymbolRegular.DarkTheme24, L.T("Appearance"), L.T("LightDarkOrFollowWindows"), theme));

        page.Children.Add(Ui.Section(L.T("Receiving")));
        string folderPath = _peer?.ReceiveDirectory ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Downloads", "HopDrop");
        var folderActions = new StackPanel { Orientation = Orientation.Horizontal };
        folderActions.Children.Add(Ui.Button(L.T("Change"), async () =>
        {
            var dialog = new OpenFolderDialog { Title = L.T("ChooseWhereHopDropSavesReceivedFiles"), InitialDirectory = _peer?.ReceiveDirectory };
            if (dialog.ShowDialog(this) == true && _peer is not null) { await _peer.SetReceiveDirectoryAsync(dialog.FolderName); RenderCurrent(); }
        }, icon: SymbolRegular.FolderSwap24));
        folderActions.Children.Add(Ui.Button(L.T("Open"), () => { Directory.CreateDirectory(folderPath); Platform.OpenFolder(folderPath); }, icon: SymbolRegular.FolderOpen24));
        var reset = Ui.Button(L.T("Default"), async () => { if (_peer is not null) { await _peer.SetReceiveDirectoryAsync(HopDropPaths.Default.DefaultReceiveDirectory); RenderCurrent(); } }, icon: SymbolRegular.ArrowReset24);
        reset.Margin = new Thickness(0); folderActions.Children.Add(reset);
        var path = Ui.Label(folderPath, 13, true); path.FlowDirection = FlowDirection.LeftToRight; path.TextTrimming = TextTrimming.CharacterEllipsis; path.TextWrapping = TextWrapping.NoWrap; path.ToolTip = folderPath;
        var folderInfo = new StackPanel(); folderInfo.Children.Add(path);
        if (ZoneMark.Supported(folderPath) == false)
            folderInfo.Children.Add(Ui.Colored(L.T("ThisDriveCanTMarkFilesAsDownloadedSoWindowsWonTWarnBeforeOpeningF"), Palette.Amber));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Folder24, L.T("ReceiveFolder"), L.T("FilesFromPhonesAndOtherPCsAreSavedHere"), folderActions, folderInfo));
        page.Children.Add(Ui.Section(L.T("Backups")));
        string backupPath = _peer?.BackupDirectory ?? HopDropPaths.Default.DefaultBackupDirectory;
        var backupActions = new StackPanel { Orientation = Orientation.Horizontal };
        backupActions.Children.Add(Ui.Button(L.T("Change"), async () =>
        {
            var dialog = new OpenFolderDialog { Title = L.T("ChooseWhereHopDropSavesBackups"), InitialDirectory = backupPath };
            if (dialog.ShowDialog(this) == true && _peer is not null) { await _peer.SetBackupDirectoryAsync(dialog.FolderName); RenderCurrent(); }
        }, icon: SymbolRegular.FolderSwap24));
        backupActions.Children.Add(Ui.Button(L.T("Open"), () => { Directory.CreateDirectory(backupPath); Platform.OpenFolder(backupPath); }, icon: SymbolRegular.FolderOpen24));
        backupActions.Children.Add(Ui.Button(L.T("Default"), async () => { if (_peer is not null) { await _peer.SetBackupDirectoryAsync(HopDropPaths.Default.DefaultBackupDirectory); RenderCurrent(); } }));
        page.Children.Add(Ui.SettingRow(SymbolRegular.Folder24, L.T("SaveBackupsIn"), backupPath, backupActions));
        var backupList = new StackPanel();
        page.Children.Add(backupList);
        if (_peer is not null) _ = ShowBackupsAsync(_peer, backupPath, backupList);
        async Task ShowBackupsAsync(HopDropPeer peer, string root, StackPanel host)
        {
            try
            {
                var entries = await peer.BackupsAsync();
                if (entries.Count == 0) { host.Children.Add(Ui.Subtle(L.T("NoBackupsReceivedYet"))); return; }
                foreach (var entry in entries.OrderByDescending(x => x.LastRun))
                {
                    string place = entry.Location ?? Path.Combine(root, FileNames.Sanitize(entry.DeviceName), FileNames.Sanitize(entry.Folder));
                    var buttons = new StackPanel { Orientation = Orientation.Horizontal };
                    buttons.Children.Add(Ui.Button(L.T("OpenFolder"), () => { if (Directory.Exists(place)) Platform.OpenFolder(place); else ShowError(L.T("BackupFolderIsnTAvailable")); }));
                    buttons.Children.Add(Ui.Button(L.T("ForgetEntry"), async () =>
                    {
                        await peer.ForgetBackupAsync(entry.DeviceId, entry.Folder, entry.Location);
                        RenderCurrent();
                    }));
                    host.Children.Add(Ui.SettingRow(SymbolRegular.Folder24, entry.DeviceName + " · " + entry.Folder,
                        L.T("BackupLastRun", Fmt.Files(entry.FileCount), entry.LastRun.ToLocalTime()), buttons));
                }
            }
            catch (Exception e) { host.Children.Add(Ui.Subtle(L.T("CouldnTLoadBackups") + e.Message)); }
        }
        var ask = new ToggleSwitch { IsChecked = _peer?.AskBeforeReceiving == true };
        ask.Checked += async (_, _) => { if (_peer is not null) await _peer.SetAskBeforeReceivingAsync(true); };
        ask.Unchecked += async (_, _) => { if (_peer is not null) await _peer.SetAskBeforeReceivingAsync(false); };
        page.Children.Add(Ui.SettingRow(SymbolRegular.ShieldQuestion24, L.T("AskBeforeReceiving"),
            L.T("ANotificationAsksYouToAcceptOrDeclineFilesFromPairedDevicesDevice"), ask));
        var tray = new ToggleSwitch { IsChecked = Preferences.CloseToTray };
        tray.Checked += (_, _) => Preferences.CloseToTray = true; tray.Unchecked += (_, _) => Preferences.CloseToTray = false;
        page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowMinimize24, L.T("CloseToTheTray"),
            L.T("ClosingTheWindowKeepsHopDropSIconInTheTrayNextToTheClockSoPhonesC"), tray));
        var background = new ToggleSwitch { IsChecked = Preferences.BackgroundReceive };
        background.Checked += (_, _) => Platform.SetBackgroundReceive(true); background.Unchecked += (_, _) => Platform.SetBackgroundReceive(false);
        page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowDownload24, L.T("ReceiveInTheBackground"),
            L.T("PhonesCanSendToThisPCEvenAfterYouQuitHopDropAndAfterARestartHopDr") +
            L.T("ToStopItOpenHopDropAndChooseQuitAndStopReceivingInTheTrayMenu"), background));
        var run = new ToggleSwitch { IsChecked = Platform.StartWithWindows };
        run.Checked += (_, _) => Platform.SetStartWithWindows(true); run.Unchecked += (_, _) => Platform.SetStartWithWindows(false);
        page.Children.Add(Ui.SettingRow(SymbolRegular.Power24, L.T("StartWithWindows"),
            L.T("HopDropStartsHiddenInTheTrayWhenYouSignInSoThisPCCanReceiveWithou"), run));

        var directSwitch = new ToggleSwitch { IsChecked = Preferences.DirectLink };
        directSwitch.Checked += (_, _) => { Preferences.DirectLink = true; if (_peer is not null) App.Instance.Link.Refresh(); };
        directSwitch.Unchecked += (_, _) => { Preferences.DirectLink = false; if (_peer is not null) App.Instance.Link.Refresh(); };
        string directStatus = _peer is null ? "On · DIRECT-HopDrop-AB12 · 192.168.137.1" : App.Instance.Link.State switch
        {
            DirectLinkState.On => L.T("On") + App.Instance.Link.Ssid + " · " + App.Instance.Link.Address,
            DirectLinkState.Off => L.T("Off"), DirectLinkState.Waiting => L.T("WaitingForAPairedPhoneOrPairing"),
            DirectLinkState.Starting => L.T("Starting"), _ => App.Instance.Link.Reason
        };
        var directInfo = new StackPanel(); directInfo.Children.Add(Ui.Subtle(directStatus));
        if (!Store.Packaged && Preferences.DirectLink && Platform.FirewallStatus() is { Public: false })
        {
            directInfo.Children.Add(Ui.Colored(L.T("WindowsFirewallMayBlockPhonesOnThisDirectLink"), Palette.Amber));
            directInfo.Children.Add(Ui.Button(L.T("AllowDirectLink"), () => Platform.AllowFirewall(true), icon: SymbolRegular.ShieldCheckmark24));
        }
        page.Children.Add(Ui.SettingRow(SymbolRegular.Link24, L.T("DirectConnection"),
            L.T("PhonesCanReachThisPCWithoutASharedWiFiNeighboursWiFiListsShowAPro") + (_peer is null ? "DIRECT-HopDrop-AB12" : Preferences.DirectSsid) + ".", directSwitch, directInfo));

        page.Children.Add(Ui.Section(L.T("Clipboard")));
        var receiveText = new ToggleSwitch { IsChecked = Preferences.PutReceivedTextOnClipboard };
        receiveText.Checked += (_, _) => Preferences.PutReceivedTextOnClipboard = true;
        receiveText.Unchecked += (_, _) => Preferences.PutReceivedTextOnClipboard = false;
        page.Children.Add(Ui.SettingRow(SymbolRegular.Copy24, L.T("PutTextFromMyDevicesOnTheClipboard"),
            L.T("TextCopiedOnYourPhoneIsReadyToPasteHere"), receiveText));
        var shareText = new ToggleSwitch { IsChecked = Preferences.ShareCopiedText };
        shareText.Checked += (_, _) => Preferences.ShareCopiedText = true;
        shareText.Unchecked += (_, _) => Preferences.ShareCopiedText = false;
        page.Children.Add(Ui.SettingRow(SymbolRegular.Share24, L.T("ShareWhatICopy"),
            L.T("TextYouCopyHereGoesToYourPairedPhonesThatAreOnline"), shareText));

        page.Children.Add(Ui.Section(L.T("Privacy")));
        var visibility = new System.Windows.Controls.ComboBox { MinWidth = 210, ItemsSource = new[] { L.T("EveryoneOnTheNetwork"), L.T("OnlyMyPairedDevices") } };
        visibility.SelectedIndex = _peer?.Visibility == "paired" ? 1 : 0;
        visibility.SelectionChanged += async (_, _) => { if (_peer is not null) await _peer.SetVisibilityAsync(visibility.SelectedIndex == 1 ? "paired" : "everyone"); };
        page.Children.Add(Ui.SettingRow(SymbolRegular.EyeOff24, L.T("WhoCanSeeThisPCSName"),
            L.T("HopDropTellsDevicesOnTheSameNetworkThatThisPCIsHereOnlyMyPairedDe") +
            L.T("ExceptWhileTheDevicesPageIsOpenForPairingPairedDevicesStillFindAn"), visibility));

        page.Children.Add(Ui.Section(L.T("WindowsIntegration")));
        if (Store.Packaged)
            page.Children.Add(Ui.SettingRow(SymbolRegular.Share24, L.T("ExplorerShareMenu"), L.T("RightClickFilesInFileExplorerShareHopDropTheyOpenHereReadyToSend"), null));
        else
        {
            var sendTo = new ToggleSwitch { IsChecked = Platform.SendToInstalled };
            sendTo.Checked += (_, _) => Platform.SetSendTo(true); sendTo.Unchecked += (_, _) => Platform.SetSendTo(false);
            page.Children.Add(Ui.SettingRow(SymbolRegular.Share24, L.T("ExplorerSendToMenu"), L.T("RightClickFilesInFileExplorerSendToHopDrop"), sendTo));
        }
        var firewall = Platform.FirewallStatus();
        var publicCheck = new System.Windows.Controls.CheckBox { Content = L.T("AlsoOnPublicNetworksSomeHotspotsAreMarkedPublic"), Margin = new Thickness(0, 0, 0, 0) };
        string firewallText = firewall is null ? L.T("CouldnTReadWindowsFirewallIfPhonesCanTFindThisPCClickAllow")
            : firewall.Value.Private && firewall.Value.Public ? L.T("AllowedOnPrivateAndPublicNetworks")
            : firewall.Value.Private ? L.T("AllowedOnPrivateNetworksPublicNetworksMayBlockPhones")
            : L.T("WindowsFirewallMayBlockPhonesFromReachingHopDropClickAllowNeedsAd");
        var firewallRow = Ui.SettingRow(SymbolRegular.Shield24, L.T("WindowsFirewall"), firewallText,
            Ui.Button(L.T("AllowHopDrop"), () => Platform.AllowFirewall(publicCheck.IsChecked == true), firewall is not { Private: true }, SymbolRegular.ShieldCheckmark24), publicCheck);
        page.Children.Add(firewallRow);

        page.Children.Add(Ui.Section(L.T("AIAgents")));
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
                bool ok = App.Instance.PutClipboardText(text);
                copied.Text = ok ? L.T("Copied") + where : L.T("CouldnTUseTheClipboardTryAgain");
                copied.Foreground = ok ? Palette.Success : Palette.Danger;
                copied.Visibility = Visibility.Visible;
            }
            var buttons = new WrapPanel { Margin = new Thickness(0, 4, 0, 6) };
            buttons.Children.Add(Ui.Button(L.T("CopyForClaudeCode"), () => Copy($"claude mcp add --scope user hopdrop -- \"{exe}\" --mcp{extra}", L.T("PasteItInATerminalAndPressEnter")), icon: SymbolRegular.Copy24));
            buttons.Children.Add(Ui.Button(L.T("CopyForCodex"), () => Copy($"codex mcp add hopdrop -- \"{exe}\" --mcp{extra}", L.T("PasteItInATerminalAndPressEnter")), icon: SymbolRegular.Copy24));
            buttons.Children.Add(Ui.Button(L.T("CopyMCPSettingsJSON"), () => Copy(json, L.T("PasteItIntoTheAgentSMCPSettingsClaudeDesktopCursorAndOthers")), icon: SymbolRegular.Copy24));
            agentSetup.Children.Add(buttons);
            agentSetup.Children.Add(copied);
            agentSetup.Children.Add(Ui.Subtle(L.T("ThenAskYourAgentThingsLikeSendReportPdfToMyPhoneOrWaitForThePhoto")));
            if (!Updates.Installed && !Store.Packaged && !App.SnapshotMode)
                agentSetup.Children.Add(Ui.Colored(L.T("ThisCopyOfHopDropIsnTInstalledIfYouMoveHopDropExeCopyTheSetupAgai"), Palette.Amber));
        }
        agents.Checked += (_, _) => { Preferences.AgentsEnabled = true; ShowAgentSetup(); };
        agents.Unchecked += (_, _) => { Preferences.AgentsEnabled = false; ShowAgentSetup(); };
        ShowAgentSetup();
        page.Children.Add(Ui.SettingRow(SymbolRegular.Bot24, L.T("LetAIAgentsUseHopDrop"),
            L.T("AIAssistantsOnThisPCClaudeCodexCursorAndOthersThatSupportMCPCanLi") +
            L.T("TheyCanTPairNewDevicesChangeSettingsOrAcceptFilesForYouAndYouSeeT"), agents, agentSetup));

        page.Children.Add(Ui.Section(L.T("Bluetooth")));
        var bluetoothButtons = new StackPanel { Orientation = Orientation.Horizontal };
        bluetoothButtons.Children.Add(Ui.Button(L.T("Receive"), ReceiveBluetooth, true, SymbolRegular.ArrowDownload24));
        var sendBluetooth = Ui.Button(L.T("Send"), BluetoothWizard.Send, icon: SymbolRegular.ArrowUpload24); sendBluetooth.Margin = new Thickness(0);
        bluetoothButtons.Children.Add(sendBluetooth);
        page.Children.Add(Ui.SettingRow(SymbolRegular.Bluetooth24, L.T("BluetoothFileTransfer"),
            L.T("ForWhenThereSNoSharedNetworkReceiveHopDropOpensWindowsBluetoothWi") +
            L.T("ThePhoneMustBePairedWithThisPCInWindowsBluetoothSettingsMuchSlowe"), bluetoothButtons));

        page.Children.Add(Ui.Section(L.T("About")));
        string id = (_peer?.Id ?? new string('a', 64))[..8].ToUpperInvariant();
        page.Children.Add(Ui.SettingRow(SymbolRegular.Info24, "HopDrop " + typeof(MainWindow).Assembly.GetName().Version?.ToString(3),
            L.T("PrivateFileTransferOnYourOwnNetworkNoCloudNoAccountsDeviceID01", id[..4], id[4..]), null));
        if (Updates.Installed)
        {
            UIElement action = Updates.ReadyVersion is not null
                ? Ui.Button(L.T("RestartToUpdate"), Updates.RestartNow, true, SymbolRegular.ArrowSync24)
                : Ui.Button(L.T("CheckNow"), async () => await Updates.CheckAsync(true), icon: SymbolRegular.ArrowSync24);
            page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowSync24, L.T("Updates"),
                L.T("HopDropChecksOnceADayAndInstallsUpdatesWhenYouReNotUsingIt"), action,
                Updates.Status.Length > 0 ? Ui.Subtle(Updates.Status) : null));
        }
        else if (Store.Packaged) page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowSync24, L.T("Updates"), L.T("TheMicrosoftStoreKeepsHopDropUpToDate"), null));
        else page.Children.Add(Ui.SettingRow(SymbolRegular.ArrowSync24, L.T("Updates"),
            L.T("ThisCopyRunsWithoutBeingInstalledSoItDoesnTUpdateItselfInstallHop"), null));
        return Ui.Scroll(page);
    }
}
