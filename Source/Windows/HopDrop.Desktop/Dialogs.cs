using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using HopDrop.Core;
using Wpf.Ui.Controls;
using Button = Wpf.Ui.Controls.Button;
using TextBox = Wpf.Ui.Controls.TextBox;
using TextBlock = System.Windows.Controls.TextBlock;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;

namespace HopDrop.Desktop;

public sealed class NumberMatchDialog : FluentWindow
{
    public NumberMatchDialog(PairingRequest request)
    {
        Title = "Pair device"; Width = 430; Height = 360; ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        var body = new StackPanel { Margin = new Thickness(30), HorizontalAlignment = HorizontalAlignment.Center };
        body.Children.Add(new TextBlock { Text = "Pair with " + request.PeerName, FontSize = 24, FontWeight = FontWeights.SemiBold, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 12, 0, 12) });
        body.Children.Add(new TextBlock { Text = request.Code, FontSize = 49, FontWeight = FontWeights.Bold, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 0, 0, 16) });
        body.Children.Add(new TextBlock { Text = "Does " + request.PeerName + " show the same number?", TextAlignment = TextAlignment.Center, TextWrapping = TextWrapping.Wrap, FontSize = 15, Margin = new Thickness(0, 0, 0, 26) });
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Center };
        var yes = new Button { Content = "They match", Appearance = ControlAppearance.Primary, Foreground = System.Windows.Media.Brushes.White, Padding = new Thickness(22, 10, 22, 10), Margin = new Thickness(0, 0, 12, 0) };
        yes.Click += (_, _) => { request.Match(); DialogResult = true; Close(); };
        var no = new Button { Content = "Cancel", Padding = new Thickness(22, 10, 22, 10) };
        no.Click += (_, _) => { request.Cancel(); DialogResult = false; Close(); };
        buttons.Children.Add(yes); buttons.Children.Add(no); body.Children.Add(buttons);
        Content = body; Closed += (_, _) => request.Cancel();
        Motion.AttachWindow(this); Motion.ScaleIn(body);
    }
}

public sealed class InputDialog : FluentWindow
{
    private readonly TextBox _value;
    public string Value => _value.Text.Trim();
    public InputDialog(string title, string description, string initial)
    {
        Title = title; Width = 440; Height = 245; ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        var body = new StackPanel { Margin = new Thickness(24) };
        body.Children.Add(new TextBlock { Text = description, FontSize = 15, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 14) });
        _value = new TextBox { Text = initial, MaxLength = 80, Margin = new Thickness(0, 0, 0, 20) }; body.Children.Add(_value);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right };
        var save = new Button { Content = "Continue", Appearance = ControlAppearance.Primary, Margin = new Thickness(0, 0, 8, 0), Padding = new Thickness(18, 8, 18, 8) };
        save.Click += (_, _) => { if (Value.Length > 0) DialogResult = true; };
        var cancel = new Button { Content = "Cancel", Padding = new Thickness(18, 8, 18, 8) }; cancel.Click += (_, _) => DialogResult = false;
        buttons.Children.Add(save); buttons.Children.Add(cancel); body.Children.Add(buttons); Content = body;
        Loaded += (_, _) => { _value.Focus(); _value.SelectAll(); };
    }
}

/// <summary>The pairing QR code at a large size, for phones whose camera can't read the small one. Click or Esc closes it.</summary>
public sealed class QrZoomWindow : FluentWindow
{
    public QrZoomWindow(System.Windows.Controls.Image code)
    {
        Title = "Pairing code"; Width = 560; Height = 640; ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        var body = new StackPanel { Margin = new Thickness(24, 12, 24, 24), HorizontalAlignment = HorizontalAlignment.Center };
        code.Width = 460; code.Height = 460;
        body.Children.Add(new Border { Child = code, Background = Brushes.White, CornerRadius = new CornerRadius(16), Padding = new Thickness(6) });
        body.Children.Add(new TextBlock { Text = "On the phone: Devices → Pair device → Scan a QR code", FontSize = 17, FontWeight = FontWeights.SemiBold, TextAlignment = TextAlignment.Center, Margin = new Thickness(0, 18, 0, 4) });
        body.Children.Add(new TextBlock { Text = "Click anywhere or press Esc to close.", FontSize = 13, Opacity = 0.7, TextAlignment = TextAlignment.Center });
        Content = body;
        MouseLeftButtonUp += (_, _) => Close();
        KeyDown += (_, e) => { if (e.Key == System.Windows.Input.Key.Escape) Close(); };
        Motion.AttachWindow(this); Motion.ScaleIn(body);
    }
}

/// <summary>Type or paste text to send; it arrives as a .txt file named after its first line.</summary>
public sealed class TextNoteDialog : FluentWindow
{
    private readonly TextBox _value;
    public string Text => _value.Text;
    public TextNoteDialog()
    {
        Title = "Send text"; Width = 520; Height = 400; ResizeMode = ResizeMode.NoResize; WindowStartupLocation = WindowStartupLocation.CenterOwner;
        var body = new DockPanel { Margin = new Thickness(24) };
        var help = new TextBlock { Text = "Type or paste text. It arrives as a .txt file.", FontSize = 15, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 0, 0, 12) };
        DockPanel.SetDock(help, Dock.Top); body.Children.Add(help);
        var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
        var add = new Button { Content = "Add", Appearance = ControlAppearance.Primary, Margin = new Thickness(0, 0, 8, 0), Padding = new Thickness(18, 8, 18, 8), IsEnabled = false };
        add.Click += (_, _) => DialogResult = true;
        var cancel = new Button { Content = "Cancel", Padding = new Thickness(18, 8, 18, 8) }; cancel.Click += (_, _) => DialogResult = false;
        buttons.Children.Add(add); buttons.Children.Add(cancel);
        DockPanel.SetDock(buttons, Dock.Bottom); body.Children.Add(buttons);
        _value = new TextBox { AcceptsReturn = true, AcceptsTab = true, TextWrapping = TextWrapping.Wrap, VerticalScrollBarVisibility = ScrollBarVisibility.Auto,
            VerticalContentAlignment = VerticalAlignment.Top, PlaceholderText = "Type or paste text" };
        _value.TextChanged += (_, _) => add.IsEnabled = _value.Text.Trim().Length > 0;
        body.Children.Add(_value);
        Content = body;
        Loaded += (_, _) => _value.Focus();
    }
}
