using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Interop;

namespace HopDrop.Desktop;

/// <summary>A message-only HWND keeps clipboard sharing alive after the window is freed.</summary>
internal sealed class ClipboardShare : IDisposable
{
    private const int WmClipboardUpdate = 0x031D;
    [DllImport("user32.dll", SetLastError = true)] private static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    private readonly HwndSource _source;
    private readonly System.Windows.Threading.DispatcherTimer _debounce = new() { Interval = TimeSpan.FromMilliseconds(400) };
    private readonly App _app;
    private string? _last, _own;
    public ClipboardShare(App app)
    {
        _app = app;
        var parameters = new HwndSourceParameters("HopDrop clipboard") { ParentWindow = new IntPtr(-3), Width = 0, Height = 0, WindowStyle = 0 };
        _source = new HwndSource(parameters);
        _source.AddHook(Hook);
        AddClipboardFormatListener(_source.Handle);
        _debounce.Tick += async (_, _) =>
        {
            _debounce.Stop();
            if (!Preferences.ShareCopiedText) return;
            string? text = ReadText();
            if (text is null || text.Length == 0 || text.Length > HopDrop.Core.HopDropPeer.MaxText) return;
            if (text == _own) { _own = null; _last = text; return; }
            _own = null;
            if (text == _last) return;
            _last = text;
            try
            {
                var devices = await App.Peer.DevicesAsync();
                await Task.WhenAll(devices.Where(d => d.Platform == "android" && App.Peer.Nearby.Any(n => n.Id == d.Id))
                    .Select(device => _app.Queue.SendTextAsync(device.Id, text, true)));
            }
            catch (Exception ex) { Debug.WriteLine(ex); }
        };
    }
    private IntPtr Hook(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        if (msg == WmClipboardUpdate) { _debounce.Stop(); _debounce.Start(); }
        return IntPtr.Zero;
    }
    public static string? ReadText()
    {
        try { return System.Windows.Clipboard.ContainsText() ? System.Windows.Clipboard.GetText() : null; }
        catch (ExternalException) { return null; }
    }
    public bool PutText(string text)
    {
        try { System.Windows.Clipboard.SetText(text); _own = text; _last = text; return true; }
        catch (ExternalException ex) { Debug.WriteLine(ex); return false; }
    }
    public void Dispose() { _debounce.Stop(); RemoveClipboardFormatListener(_source.Handle); _source.Dispose(); }
}
