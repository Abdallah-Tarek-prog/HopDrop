using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Effects;
using Wpf.Ui.Controls;
using Color = System.Windows.Media.Color;
using TextBlock = System.Windows.Controls.TextBlock;
using Orientation = System.Windows.Controls.Orientation;
using KeyEventArgs = System.Windows.Input.KeyEventArgs;
using FontFamily = System.Windows.Media.FontFamily;
using Cursors = System.Windows.Input.Cursors;

namespace HopDrop.Desktop;

/// <summary>
/// The tray icon's right-click menu, drawn like Windows 11's own menus: rounded, soft shadow, light or dark to match
/// HopDrop's Appearance setting, a grey status line on top. It closes when it loses focus, on Esc, or after a choice.
/// Arrow keys move between items, Enter picks one.
/// </summary>
internal sealed class TrayMenu : Window
{
    internal sealed record Item(string Text, SymbolRegular Icon, Action Run, bool Separator = false);

    private readonly List<Border> _rows = [];
    private readonly List<Item> _items = [];
    private int _focused = -1;
    private bool _closing;
    private readonly SolidColorBrush _hover;

    private static bool UseDark()
    {
        string theme = Preferences.Theme;
        if (theme != "system") return theme == "dark";
        using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("AppsUseLightTheme") is int light && light == 0;
    }
    private static SolidColorBrush Solid(uint rgb) => new(Color.FromRgb((byte)(rgb >> 16), (byte)(rgb >> 8), (byte)rgb));

    public TrayMenu(string status, IReadOnlyList<Item> items)
    {
        bool dark = UseDark();
        var background = Solid(dark ? 0x2C2C2C : 0xF9F9F9u);
        var border = Solid(dark ? 0x454545 : 0xE0E0E0u);
        var text = Solid(dark ? 0xFFFFFF : 0x1A1A1Au);
        var muted = Solid(dark ? 0xA0A0A0 : 0x616161u);
        var line = Solid(dark ? 0x3D3D3D : 0xE5E5E5u);
        _hover = Solid(dark ? 0x3A3A3A : 0xEAEAEAu);

        WindowStyle = WindowStyle.None; AllowsTransparency = true; Background = Brushes.Transparent;
        ResizeMode = ResizeMode.NoResize; ShowInTaskbar = false; Topmost = true; ShowActivated = true;
        SizeToContent = SizeToContent.WidthAndHeight; Title = "HopDrop";
        FontFamily = new FontFamily("Segoe UI Variable Text, Segoe UI"); FontSize = 14;

        var list = new StackPanel { MinWidth = 236 };
        var header = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(12, 6, 12, 6) };
        header.Children.Add(new System.Windows.Controls.Image
        {
            Source = System.Windows.Media.Imaging.BitmapFrame.Create(new Uri("pack://application:,,,/Assets/HopDrop.ico")),
            Width = 16, Height = 16, Margin = new Thickness(0, 0, 10, 0), VerticalAlignment = VerticalAlignment.Center
        });
        header.Children.Add(new TextBlock { Text = status, Foreground = muted, FontSize = 12, VerticalAlignment = VerticalAlignment.Center });
        list.Children.Add(header);
        foreach (var item in items)
        {
            if (item.Separator) { list.Children.Add(new Border { Height = 1, Background = line, Margin = new Thickness(-4, 4, -4, 4) }); continue; }
            var row = new Border { CornerRadius = new CornerRadius(4), Background = Brushes.Transparent, Padding = new Thickness(12, 0, 12, 0), Height = 34, Cursor = Cursors.Arrow };
            var content = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
            content.Children.Add(new SymbolIcon { Symbol = item.Icon, FontSize = 16, Foreground = muted, Margin = new Thickness(0, 0, 12, 0), VerticalAlignment = VerticalAlignment.Center });
            content.Children.Add(new TextBlock { Text = item.Text, Foreground = text, VerticalAlignment = VerticalAlignment.Center });
            row.Child = content;
            int index = _rows.Count;
            row.MouseEnter += (_, _) => Focus(index);
            row.MouseLeave += (_, _) => { if (_focused == index) Focus(-1); };
            row.MouseLeftButtonUp += (_, _) => Choose(index);
            _rows.Add(row); _items.Add(item);
            list.Children.Add(row);
        }
        // Room around the card for its shadow; the window itself is transparent.
        Content = new Border
        {
            Margin = new Thickness(14), Padding = new Thickness(4), CornerRadius = new CornerRadius(8),
            Background = background, BorderBrush = border, BorderThickness = new Thickness(1), Child = list,
            Effect = new DropShadowEffect { BlurRadius = 18, ShadowDepth = 4, Direction = 270, Opacity = dark ? 0.5 : 0.18, Color = Colors.Black }
        };
        Deactivated += (_, _) => SafeClose();
        PreviewKeyDown += OnKey;
    }

    private void Focus(int index)
    {
        if (_focused >= 0) _rows[_focused].Background = Brushes.Transparent;
        _focused = index;
        if (index >= 0) _rows[index].Background = _hover;
    }
    private void OnKey(object sender, KeyEventArgs e)
    {
        switch (e.Key)
        {
            case Key.Escape: SafeClose(); break;
            case Key.Down: Focus((_focused + 1) % _rows.Count); break;
            case Key.Up: Focus(_focused <= 0 ? _rows.Count - 1 : _focused - 1); break;
            case Key.Enter or Key.Space when _focused >= 0: Choose(_focused); break;
            default: return;
        }
        e.Handled = true;
    }
    private void Choose(int index)
    {
        var run = _items[index].Run;
        SafeClose();
        Dispatcher.InvokeAsync(run);
    }
    private void SafeClose()
    {
        if (_closing) return;
        _closing = true;
        Close();
    }

    /// <summary>Opens the menu next to the mouse (in screen pixels), kept inside that screen's work area.</summary>
    public void ShowAt(System.Drawing.Point cursor)
    {
        Left = -32000; Top = -32000;
        Show();
        UpdateLayout();
        var fromDevice = PresentationSource.FromVisual(this)!.CompositionTarget!.TransformFromDevice;
        var area = System.Windows.Forms.Screen.FromPoint(cursor).WorkingArea;
        var topLeft = fromDevice.Transform(new Point(area.Left, area.Top));
        var bottomRight = fromDevice.Transform(new Point(area.Right, area.Bottom));
        var at = fromDevice.Transform(new Point(cursor.X, cursor.Y));
        // The card sits 14 px inside the window (shadow room), so line the card, not the window, up with the mouse.
        double width = ActualWidth, height = ActualHeight, inset = 14;
        double left = at.X - inset, top = at.Y - height + inset;
        if (left + width - inset > bottomRight.X) left = at.X - width + inset;
        if (top + inset < topLeft.Y) top = at.Y - inset;
        Left = Math.Max(topLeft.X - inset, Math.Min(left, bottomRight.X - width + inset));
        Top = Math.Max(topLeft.Y - inset, Math.Min(top, bottomRight.Y - height + inset));
        Activate();
        Keyboard.Focus(this);
    }
}
