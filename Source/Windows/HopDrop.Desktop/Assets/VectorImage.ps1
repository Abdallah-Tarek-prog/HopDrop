# Draws an Android vector drawable (paths with solid or linear-gradient fills, and round-capped strokes) as a PNG with WPF.
# Dot-source it, then: $png = ConvertTo-VectorPng -Path <vector.xml> -Size 64   (returns the PNG bytes).
# -Tile '#FFFFFF' draws the logo on a rounded tile of that colour (the Windows app icon's white tile): the logo is
# scaled to fill most of the tile and drawn bolder (thicker still at tray sizes), so it reads from a distance.
Add-Type -AssemblyName PresentationCore, WindowsBase

function ConvertTo-VectorPng([string]$Path, [int]$Size, [string]$Tile) {
    $android = 'http://schemas.android.com/apk/res/android'
    $xml = [xml](Get-Content -Raw $Path)
    $viewport = [double]::Parse($xml.vector.GetAttribute('viewportWidth', $android), [Globalization.CultureInfo]::InvariantCulture)
    $colours = [System.Windows.Media.BrushConverter]::new()
    $number = { param($node, $name) [double]::Parse($node.GetAttribute($name, $android), [Globalization.CultureInfo]::InvariantCulture) }
    $drawing = [System.Windows.Media.DrawingVisual]::new()
    $context = $drawing.RenderOpen()
    $scale = $Size / $viewport
    if ($Tile) {
        # A rounded tile with a hairline edge (so white still reads on white), the logo at 80 % in its middle.
        $edge = [System.Windows.Media.Pen]::new($colours.ConvertFromString('#FFDDE1E7'), [math]::Max(1, $Size / 64))
        $inset = $edge.Thickness / 2
        $context.DrawRoundedRectangle($colours.ConvertFromString($Tile), $(if ($Size -ge 24) { $edge } else { $null }),
            [System.Windows.Rect]::new($inset, $inset, $Size - 2 * $inset, $Size - 2 * $inset), $Size * 0.22, $Size * 0.22)
        # Bolder outline in logo units: about 3 % of the logo's width, 6 % at 32 px and below (taskbar and tray).
        $bold = if ($Size -le 32) { 6 } else { 3 }
        $bounds = [System.Windows.Rect]::Empty
        foreach ($node in @($xml.vector.path)) { $bounds.Union([System.Windows.Media.Geometry]::Parse($node.GetAttribute('pathData', $android)).Bounds) }
        $bounds.Inflate($bold / 2, $bold / 2)
        # The logo spans 86 % of the tile's width (or height, if that is the tighter side), centred.
        $scale = [math]::Min($Size * 0.86 / $bounds.Width, $Size * 0.86 / $bounds.Height)
        $context.PushTransform([System.Windows.Media.TranslateTransform]::new(
            ($Size - $bounds.Width * $scale) / 2 - $bounds.X * $scale, ($Size - $bounds.Height * $scale) / 2 - $bounds.Y * $scale))
    }
    $context.PushTransform([System.Windows.Media.ScaleTransform]::new($scale, $scale))
    foreach ($node in @($xml.vector.path)) {
        $geometry = [System.Windows.Media.Geometry]::Parse($node.GetAttribute('pathData', $android))
        $fill = $null
        $fillText = $node.GetAttribute('fillColor', $android)
        if ($fillText.StartsWith('#')) { $fill = $colours.ConvertFromString($fillText) }
        $gradient = $node.SelectSingleNode("*[local-name()='attr' and @name='android:fillColor']/*[local-name()='gradient']")
        if ($gradient) {
            $stops = [System.Windows.Media.GradientStopCollection]::new()
            foreach ($item in @($gradient.SelectNodes("*[local-name()='item']"))) {
                $stops.Add([System.Windows.Media.GradientStop]::new($colours.ConvertFromString($item.GetAttribute('color', $android)).Color, (& $number $item 'offset')))
            }
            $fill = [System.Windows.Media.LinearGradientBrush]::new($stops)
            $fill.MappingMode = [System.Windows.Media.BrushMappingMode]::Absolute
            $fill.StartPoint = [System.Windows.Point]::new((& $number $gradient 'startX'), (& $number $gradient 'startY'))
            $fill.EndPoint = [System.Windows.Point]::new((& $number $gradient 'endX'), (& $number $gradient 'endY'))
        }
        $pen = $null
        $strokeText = $node.GetAttribute('strokeColor', $android)
        if ($strokeText) {
            $pen = [System.Windows.Media.Pen]::new($colours.ConvertFromString($strokeText), (& $number $node 'strokeWidth'))
            $pen.StartLineCap = [System.Windows.Media.PenLineCap]::Round
            $pen.EndLineCap = [System.Windows.Media.PenLineCap]::Round
        }
        if ($Tile -and -not $pen) {
            $pen = [System.Windows.Media.Pen]::new($fill, $bold)
            $pen.LineJoin = [System.Windows.Media.PenLineJoin]::Round
        }
        $context.DrawGeometry($fill, $pen, $geometry)
    }
    $context.Pop()
    if ($Tile) { $context.Pop() }
    $context.Close()
    $bitmap = [System.Windows.Media.Imaging.RenderTargetBitmap]::new($Size, $Size, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($drawing)
    $encoder = [System.Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $memory = [System.IO.MemoryStream]::new()
    try { $encoder.Save($memory); return , $memory.ToArray() } finally { $memory.Dispose() }
}
