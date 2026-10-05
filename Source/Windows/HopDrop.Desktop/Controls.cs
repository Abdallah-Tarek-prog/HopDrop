using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Animation;
using Wpf.Ui.Appearance;
using Wpf.Ui.Controls;
using Button = Wpf.Ui.Controls.Button;
using Card = Wpf.Ui.Controls.Card;
using TextBlock = System.Windows.Controls.TextBlock;
using Orientation = System.Windows.Controls.Orientation;
using HorizontalAlignment = System.Windows.HorizontalAlignment;
using Color = System.Windows.Media.Color;

namespace HopDrop.Desktop;

/// <summary>HopDrop's semantic colors for the current theme (same tokens as the Android app).</summary>
internal static class Palette
{
    public static bool Dark => ApplicationThemeManager.GetAppTheme() == ApplicationTheme.Dark;
    public static Color Blue => Color.FromRgb(37, 99, 235);
    private static SolidColorBrush Brush(uint light, uint dark)
    {
        uint v = Dark ? dark : light;
        var brush = new SolidColorBrush(Color.FromRgb((byte)(v >> 16), (byte)(v >> 8), (byte)v)); brush.Freeze(); return brush;
    }
    public static SolidColorBrush Primary => Brush(0x2563EB, 0x7AA2FF);
    public static SolidColorBrush PrimaryBg => Brush(0xE1EAFE, 0x1D2F57);
    public static SolidColorBrush OnPrimaryBg => Brush(0x1D3F9E, 0xC9D8FF);
    public static SolidColorBrush Success => Brush(0x15803D, 0x4ADE80);
    public static SolidColorBrush SuccessBg => Brush(0xDCF5E4, 0x123222);
    public static SolidColorBrush Danger => Brush(0xC62828, 0xF87171);
    public static SolidColorBrush DangerBg => Brush(0xFCE4E4, 0x3A1A1E);
    public static SolidColorBrush Amber => Brush(0x8A5300, 0xFCD68A);
    public static SolidColorBrush AmberBg => Brush(0xFDF1D8, 0x3A2C11);
    public static SolidColorBrush Muted => Brush(0x56647A, 0x9CA9BE);
    public static SolidColorBrush Border => Brush(0xDFE5EF, 0x2E3A52);
    public static SolidColorBrush Surface => Brush(0xF4F6FB, 0x0F1626);
    public static SolidColorBrush CardBg => Brush(0xFFFFFF, 0x172033);
    public static SolidColorBrush SurfaceAlt => Brush(0xEEF2F8, 0x1D2940);
}

/// <summary>Short, one-shot motion. Clocks belong to their element and are removed on completion or hiding.</summary>
internal static class Motion
{
    public static bool Disabled => App.SnapshotMode || !SystemParameters.ClientAreaAnimation;
    private static bool Enabled(FrameworkElement element) => !Disabled && element.IsVisible &&
        Window.GetWindow(element) is { IsVisible: true, WindowState: not WindowState.Minimized } window &&
        window is not MainWindow { IsDetached: true } && window.Owner is not MainWindow { IsDetached: true };
    private static IEasingFunction Ease() => new CubicEase { EasingMode = EasingMode.EaseOut };

    private sealed class Running
    {
        public required IAnimatable Target;
        public required DependencyProperty Property;
        public required AnimationClock Clock;
        public required EventHandler Finished;
        public Action? Complete;
    }
    private static readonly DependencyProperty ClocksProperty = DependencyProperty.RegisterAttached(
        "Clocks", typeof(List<Running>), typeof(Motion), new PropertyMetadata(null));

    private static void Finish(List<Running> clocks, Running running, bool complete = true)
    {
        if (!clocks.Remove(running)) return;
        running.Clock.Completed -= running.Finished;
        running.Target.ApplyAnimationClock(running.Property, null);
        running.Clock.Controller?.Remove();
        if (complete) running.Complete?.Invoke();
    }
    private static void Stop(FrameworkElement element)
    {
        if (element.GetValue(ClocksProperty) is List<Running> clocks)
            foreach (var running in clocks.ToArray()) Finish(clocks, running);
    }
    public static void StopTree(DependencyObject root)
    {
        // Completion may remove a collapsing row, so collect children before stopping its clocks.
        var children = Enumerable.Range(0, VisualTreeHelper.GetChildrenCount(root))
            .Select(i => VisualTreeHelper.GetChild(root, i)).ToArray();
        if (root is FrameworkElement element) Stop(element);
        foreach (var child in children) StopTree(child);
        if (root is Window window)
            foreach (Window owned in window.OwnedWindows) StopTree(owned);
    }
    public static void AttachWindow(Window window)
    {
        void StopWhenHidden()
        {
            if (window.IsVisible && window.WindowState != WindowState.Minimized) return;
            StopTree(window);
        }
        window.IsVisibleChanged += (_, _) => StopWhenHidden();
        window.StateChanged += (_, _) => StopWhenHidden();
        window.Closed += (_, _) => StopTree(window);
    }
    private static void Run(FrameworkElement owner, IAnimatable target, DependencyProperty property,
        object baseValue, AnimationTimeline animation, Action? complete = null)
    {
        var clocks = owner.GetValue(ClocksProperty) as List<Running>;
        if (clocks is not null)
            foreach (var old in clocks.Where(c => c.Target == target && c.Property == property).ToArray()) Finish(clocks, old, false);
        ((DependencyObject)target).SetValue(property, baseValue);
        if (!Enabled(owner)) { complete?.Invoke(); return; }
        if (clocks is null)
        {
            clocks = [];
            owner.SetValue(ClocksProperty, clocks);
            owner.Unloaded += (_, _) => Stop(owner);
            owner.IsVisibleChanged += (_, _) => { if (!owner.IsVisible) Stop(owner); };
        }
        animation.FillBehavior = FillBehavior.Stop;
        var clock = (AnimationClock)animation.CreateClock(true);
        Running? running = null;
        EventHandler finished = (_, _) => Finish(clocks, running!);
        running = new Running { Target = target, Property = property, Clock = clock, Finished = finished, Complete = complete };
        clocks.Add(running);
        clock.Completed += finished;
        target.ApplyAnimationClock(property, clock, HandoffBehavior.SnapshotAndReplace);
    }
    private static void Double(FrameworkElement owner, IAnimatable target, DependencyProperty property,
        double from, double to, int duration = 180, Action? complete = null) =>
        Run(owner, target, property, to, new DoubleAnimation(from, to, TimeSpan.FromMilliseconds(duration))
        { EasingFunction = Ease() }, complete);

    private static void WhenLoaded(FrameworkElement element, Action action)
    {
        if (element.IsLoaded) { action(); return; }
        RoutedEventHandler? loaded = null;
        loaded = (_, _) => { element.Loaded -= loaded; action(); };
        element.Loaded += loaded;
    }
    public static void FadeIn(FrameworkElement element, int delay = 0) => WhenLoaded(element, () =>
    {
        // Keep staggered rows hidden during their delay; the final base value is restored even on unload.
        Run(element, element, UIElement.OpacityProperty, 0d,
            new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(180))
            { EasingFunction = Ease(), BeginTime = TimeSpan.FromMilliseconds(delay) }, () => element.Opacity = 1);
    });
    public static void SlideUp(FrameworkElement element) => WhenLoaded(element, () =>
    {
        var move = new TranslateTransform(); element.RenderTransform = move;
        Double(element, element, UIElement.OpacityProperty, 0, 1, 200);
        Double(element, move, TranslateTransform.YProperty, 12, 0, 200);
    });
    public static void Scale(FrameworkElement element, double value, int duration = 140)
    {
        if (element.RenderTransform is not ScaleTransform scale)
        {
            scale = new ScaleTransform(); element.RenderTransform = scale;
            element.RenderTransformOrigin = new Point(0.5, 0.5);
        }
        Double(element, scale, ScaleTransform.ScaleXProperty, scale.ScaleX, value, duration);
        Double(element, scale, ScaleTransform.ScaleYProperty, scale.ScaleY, value, duration);
    }
    public static void ScaleIn(FrameworkElement element) => WhenLoaded(element, () =>
    {
        element.RenderTransform = new ScaleTransform(0.96, 0.96);
        Double(element, element, UIElement.OpacityProperty, 0, 1, 200);
        Scale(element, 1, 200);
    });
    public static void Pop(FrameworkElement element) => WhenLoaded(element, () =>
    {
        var scale = new ScaleTransform(); element.RenderTransform = scale;
        element.RenderTransformOrigin = new Point(0.5, 0.5);
        foreach (var property in new[] { ScaleTransform.ScaleXProperty, ScaleTransform.ScaleYProperty })
            Run(element, scale, property, 1d, new DoubleAnimationUsingKeyFrames
            {
                Duration = TimeSpan.FromMilliseconds(220),
                KeyFrames =
                {
                    new DiscreteDoubleKeyFrame(0.8, KeyTime.FromTimeSpan(TimeSpan.Zero)),
                    new EasingDoubleKeyFrame(1.05, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(140)), Ease()),
                    new EasingDoubleKeyFrame(1, KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(220)), Ease())
                }
            });
    });
    public static void SmoothValue(ProgressBar bar, double value)
    {
        value = Math.Clamp(value, bar.Minimum, bar.Maximum);
        if (Enabled(bar) && (double)bar.GetAnimationBaseValue(RangeBase.ValueProperty) == value) return;
        Double(bar, bar, RangeBase.ValueProperty, bar.Value, value, 160);
    }
    public static void Height(FrameworkElement element, double from, double to, Action complete) =>
        Double(element, element, FrameworkElement.HeightProperty, from, to, 200, complete: complete);
    public static void Collapse(FrameworkElement element, Action complete)
    {
        Double(element, element, UIElement.OpacityProperty, element.Opacity, 0, 160);
        Height(element, element.ActualHeight, 0, complete);
    }
    public static void Highlight(FrameworkElement element, SolidColorBrush brush, double opacity) =>
        Double(element, brush, Brush.OpacityProperty, brush.Opacity, opacity, 140);
    public static Card Hover(Card card)
    {
        WhenLoaded(card, () =>
        {
            var move = new TranslateTransform(); card.RenderTransform = move;
            var normal = (card.Background as SolidColorBrush)?.Color ?? Palette.CardBg.Color;
            var background = new SolidColorBrush(normal); card.Background = background;
            void SetHover(bool hover)
            {
                Double(card, move, TranslateTransform.YProperty, move.Y, hover ? -2 : 0, 140);
                var color = hover ? Palette.SurfaceAlt.Color : normal;
                Run(card, background, SolidColorBrush.ColorProperty, color,
                    new ColorAnimation(background.Color, color, TimeSpan.FromMilliseconds(140)) { EasingFunction = Ease() });
            }
            card.MouseEnter += (_, _) => SetHover(true);
            card.MouseLeave += (_, _) => SetHover(false);
            card.IsVisibleChanged += (_, _) => { if (!card.IsVisible) SetHover(false); };
        });
        return card;
    }
}

/// <summary>Code-built building blocks so every page has the same spacing, type scale and states.</summary>
internal static class Ui
{
    public static TextBlock Label(string text, double size = 14, bool bold = false) => new()
    { Text = text, FontSize = size, FontWeight = bold ? FontWeights.SemiBold : FontWeights.Normal, TextWrapping = TextWrapping.Wrap, VerticalAlignment = VerticalAlignment.Center };
    public static TextBlock Subtle(string text, double size = 13) => new()
    { Text = text, FontSize = size, Foreground = Palette.Muted, TextWrapping = TextWrapping.Wrap, Margin = new Thickness(0, 2, 0, 0) };
    public static TextBlock Section(string text) { var t = Label(text, 18, true); t.Margin = new Thickness(0, 22, 0, 10); return t; }
    public static TextBlock Colored(string text, Brush brush, double size = 13, bool bold = false) { var t = Label(text, size, bold); t.Foreground = brush; return t; }

    /// <summary>A page: title, one-line purpose, then content, centred with a readable maximum width.</summary>
    public static StackPanel Page(string title, string subtitle)
    {
        var panel = new StackPanel { Margin = new Thickness(32, 20, 32, 32), MaxWidth = 980 };
        panel.Children.Add(Label(title, 30, true));
        var sub = Subtle(subtitle, 14); sub.Margin = new Thickness(0, 2, 0, 8); panel.Children.Add(sub);
        return panel;
    }
    /// <summary>
    /// Pages are scrolled by the NavigationView's own DynamicScrollViewer. Never wrap a page in another ScrollViewer:
    /// the inner one grows to the full content height, can't scroll, and swallows mouse-wheel and touchpad input.
    /// </summary>
    public static FrameworkElement Scroll(FrameworkElement child) => child;

    public static Card Card(UIElement content, double padding = 18, double bottom = 10) =>
        new() { Content = content, Padding = new Thickness(padding), Margin = new Thickness(0, 0, 0, bottom) };

    /// <summary>Windows 11 Settings-style row: icon, title and description on the left, a control on the right.</summary>
    public static Card SettingRow(SymbolRegular icon, string title, string description, UIElement? control, UIElement? below = null)
    {
        var grid = new Grid();
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(44) });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(new SymbolIcon(icon) { FontSize = 22, VerticalAlignment = VerticalAlignment.Center, HorizontalAlignment = HorizontalAlignment.Left });
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 16, 0) };
        text.Children.Add(Label(title, 14, true)); text.Children.Add(Subtle(description));
        Grid.SetColumn(text, 1); grid.Children.Add(text);
        if (control is FrameworkElement element) { element.VerticalAlignment = VerticalAlignment.Center; Grid.SetColumn(element, 2); grid.Children.Add(element); }
        if (below is null) return Motion.Hover(Card(grid, 16, 4));
        var stack = new StackPanel(); stack.Children.Add(grid);
        if (below is FrameworkElement b) b.Margin = new Thickness(44, 12, 0, 0);
        stack.Children.Add(below);
        return Motion.Hover(Card(stack, 16, 4));
    }

    public static Button Button(string text, Action action, bool primary = false, SymbolRegular? icon = null)
    {
        var b = new Button
        {
            Content = text, Padding = new Thickness(14, 7, 14, 7), Margin = new Thickness(0, 0, 8, 0), MinHeight = 34,
            Appearance = primary ? ControlAppearance.Primary : ControlAppearance.Secondary
        };
        if (icon is not null) b.Icon = new SymbolIcon(icon.Value);
        if (primary) b.Foreground = Brushes.White;
        b.Click += (_, _) => action(); return b;
    }
    public static Button IconButton(SymbolRegular icon, string tooltip, Action action)
    {
        var b = new Button { Icon = new SymbolIcon(icon), Appearance = ControlAppearance.Transparent, Padding = new Thickness(8), ToolTip = tooltip, MinWidth = 36, MinHeight = 36 };
        System.Windows.Automation.AutomationProperties.SetName(b, tooltip);
        b.Click += (_, _) => action(); return b;
    }

    /// <summary>An icon inside a tinted circle.</summary>
    public static Border Badge(SymbolRegular icon, Brush foreground, Brush background, double size = 40) => new()
    {
        Width = size, Height = size, CornerRadius = new CornerRadius(size / 2), Background = background, VerticalAlignment = VerticalAlignment.Center,
        Child = new SymbolIcon(icon) { FontSize = size * 0.5, Foreground = foreground, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center }
    };
    public static Border Chip(string text, Brush foreground, Brush background) => new()
    {
        CornerRadius = new CornerRadius(10), Background = background, Padding = new Thickness(10, 3, 10, 3), VerticalAlignment = VerticalAlignment.Center,
        Child = new TextBlock { Text = text, FontSize = 12, FontWeight = FontWeights.SemiBold, Foreground = foreground }
    };
    public static StackPanel Status(string text, Brush color)
    {
        var line = new StackPanel { Orientation = Orientation.Horizontal, Margin = new Thickness(0, 3, 0, 0) };
        line.Children.Add(new System.Windows.Shapes.Ellipse { Width = 8, Height = 8, Fill = color, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 6, 0) });
        line.Children.Add(new TextBlock { Text = text, FontSize = 13, Foreground = color, VerticalAlignment = VerticalAlignment.Center });
        return line;
    }

    /// <summary>Row inside a card: leading badge, title/subtitle, trailing actions.</summary>
    public static Grid ListRow(UIElement leading, string title, string? subtitle, params UIElement[] trailing)
    {
        var grid = new Grid { Margin = new Thickness(0, 4, 0, 4) };
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.ColumnDefinitions.Add(new ColumnDefinition());
        grid.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        grid.Children.Add(leading);
        var text = new StackPanel { VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(14, 0, 12, 0) };
        var name = Label(title, 15, true); name.TextTrimming = TextTrimming.CharacterEllipsis; name.TextWrapping = TextWrapping.NoWrap;
        text.Children.Add(name);
        if (subtitle is not null) text.Children.Add(Subtle(subtitle));
        Grid.SetColumn(text, 1); grid.Children.Add(text);
        var actions = new StackPanel { Orientation = Orientation.Horizontal, VerticalAlignment = VerticalAlignment.Center };
        foreach (var t in trailing) actions.Children.Add(t);
        Grid.SetColumn(actions, 2); grid.Children.Add(actions);
        return grid;
    }
    public static StackPanel TextOf(Grid row) => (StackPanel)row.Children[1];

    public static Border EmptyState(SymbolRegular icon, string title, string detail, UIElement? action = null)
    {
        var stack = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center, Margin = new Thickness(0, 14, 0, 14) };
        var badge = Badge(icon, Palette.OnPrimaryBg, Palette.PrimaryBg, 52); badge.HorizontalAlignment = HorizontalAlignment.Center; stack.Children.Add(badge);
        var t = Label(title, 16, true); t.HorizontalAlignment = HorizontalAlignment.Center; t.TextAlignment = TextAlignment.Center; t.Margin = new Thickness(0, 10, 0, 0); stack.Children.Add(t);
        var d = Subtle(detail); d.HorizontalAlignment = HorizontalAlignment.Center; d.TextAlignment = TextAlignment.Center; d.MaxWidth = 460; stack.Children.Add(d);
        if (action is FrameworkElement a) { a.HorizontalAlignment = HorizontalAlignment.Center; a.Margin = new Thickness(0, 12, 0, 0); stack.Children.Add(a); }
        return new Border { Child = stack };
    }
    public static ProgressBar Bar() => new() { Height = 6, Minimum = 0, Maximum = 100, Margin = new Thickness(0, 8, 0, 4), Foreground = new SolidColorBrush(Palette.Blue) };

    public static SymbolRegular FileIcon(string name) => Path.GetExtension(name).ToLowerInvariant() switch
    {
        ".jpg" or ".jpeg" or ".png" or ".gif" or ".webp" or ".heic" or ".bmp" => SymbolRegular.Image24,
        ".mp4" or ".mov" or ".mkv" or ".avi" or ".webm" => SymbolRegular.Video24,
        ".mp3" or ".m4a" or ".wav" or ".flac" or ".ogg" => SymbolRegular.MusicNote224,
        ".zip" or ".rar" or ".7z" or ".tar" or ".gz" => SymbolRegular.FolderZip24,
        ".pdf" or ".doc" or ".docx" or ".txt" or ".xls" or ".xlsx" or ".ppt" or ".pptx" or ".md" or ".csv" => SymbolRegular.DocumentText24,
        ".apk" or ".exe" or ".msi" => SymbolRegular.AppGeneric24,
        _ => SymbolRegular.Document24
    };
}

/// <summary>Human-readable sizes, speeds and durations (same wording as the Android app).</summary>
internal static class Fmt
{
    public static string Size(long bytes) => bytes < 0 ? "Unknown size" : bytes < 1024 ? $"{bytes} B" : bytes < 1024 * 1024 ? $"{bytes / 1024d:0.0} KB"
        : bytes < 1024L * 1024 * 1024 ? $"{bytes / 1048576d:0.0} MB" : $"{bytes / 1073741824d:0.00} GB";
    public static string Speed(double bytesPerSecond) => Size((long)bytesPerSecond) + "/s";
    public static string Duration(TimeSpan time)
    {
        long s = (long)Math.Ceiling(time.TotalSeconds);
        if (s < 60) return $"{Math.Max(1, s)} s";
        long m = s / 60;
        return m < 10 ? $"{m} min {s % 60} s" : m < 60 ? $"{m} min" : $"{m / 60} h {m % 60} min";
    }
    public static string Files(int count) => $"{count.ToString("N0", System.Globalization.CultureInfo.InvariantCulture)} {(count == 1 ? "file" : "files")}";
    public static string Folders(int count) => $"{count.ToString("N0", System.Globalization.CultureInfo.InvariantCulture)} {(count == 1 ? "folder" : "folders")}";
    /// <summary>
    /// "a.jpg, b.pdf +3" (at most <paramref name="max"/> entries). Files that came inside a sent folder count once, as
    /// "Photos folder": a relative path ("Photos/2024/a.jpg") by its first part, a full path by its first folder under <paramref name="root"/>.
    /// </summary>
    public static string Names(IEnumerable<string> paths, string? root = null, int max = 2)
    {
        var shown = new List<string>();
        var folders = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (string path in paths)
        {
            string relative = path;
            if (Path.IsPathFullyQualified(path))
            {
                relative = root is null ? Path.GetFileName(path) : Path.GetRelativePath(root, path);
                if (relative.StartsWith("..", StringComparison.Ordinal) || Path.IsPathFullyQualified(relative)) relative = Path.GetFileName(path);
            }
            relative = relative.Replace('\\', '/');
            int slash = relative.IndexOf('/');
            if (slash <= 0) shown.Add(relative);
            else if (folders.Add(relative[..slash])) shown.Add(relative[..slash] + " folder");
        }
        return shown.Count == 0 ? "No files" : shown.Count <= max ? string.Join(", ", shown) : $"{string.Join(", ", shown.Take(max))} +{shown.Count - max}";
    }
    public static string Amount(long done, long total) => total < 0 ? Size(done) : $"{Size(done)} of {Size(total)}";
    public static string Ago(DateTimeOffset time)
    {
        var span = DateTimeOffset.Now - time;
        return span.TotalMinutes < 1 ? "just now" : span.TotalHours < 1 ? $"{(int)span.TotalMinutes} min ago" : span.TotalDays < 1 ? $"{(int)span.TotalHours} h ago" : $"{(int)span.TotalDays} days ago";
    }
}
