# Draws an Android vector drawable (paths with solid or linear-gradient fills, and round-capped strokes) as a PNG with WPF.
# Dot-source it, then: $png = ConvertTo-VectorPng -Path <vector.xml> -Size 64   (returns the PNG bytes).
Add-Type -AssemblyName PresentationCore, WindowsBase

function ConvertTo-VectorPng([string]$Path, [int]$Size) {
    $android = 'http://schemas.android.com/apk/res/android'
    $xml = [xml](Get-Content -Raw $Path)
    $viewport = [double]::Parse($xml.vector.GetAttribute('viewportWidth', $android), [Globalization.CultureInfo]::InvariantCulture)
    $colours = [System.Windows.Media.BrushConverter]::new()
    $number = { param($node, $name) [double]::Parse($node.GetAttribute($name, $android), [Globalization.CultureInfo]::InvariantCulture) }
    $drawing = [System.Windows.Media.DrawingVisual]::new()
    $context = $drawing.RenderOpen()
    $context.PushTransform([System.Windows.Media.ScaleTransform]::new($Size / $viewport, $Size / $viewport))
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
        $context.DrawGeometry($fill, $pen, $geometry)
    }
    $context.Pop()
    $context.Close()
    $bitmap = [System.Windows.Media.Imaging.RenderTargetBitmap]::new($Size, $Size, 96, 96, [System.Windows.Media.PixelFormats]::Pbgra32)
    $bitmap.Render($drawing)
    $encoder = [System.Windows.Media.Imaging.PngBitmapEncoder]::new()
    $encoder.Frames.Add([System.Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
    $memory = [System.IO.MemoryStream]::new()
    try { $encoder.Save($memory); return , $memory.ToArray() } finally { $memory.Dispose() }
}
