using System.Diagnostics;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using HopDrop.Core;

namespace HopDrop.Desktop;

/// <summary>A message-only HWND keeps clipboard sharing alive after the window is freed.</summary>
internal sealed class ClipboardShare : IDisposable
{
    private const int WmClipboardUpdate = 0x031D;
    [DllImport("user32.dll", SetLastError = true)] private static extern bool AddClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool RemoveClipboardFormatListener(IntPtr hwnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern uint RegisterClipboardFormat(string format);
    [DllImport("user32.dll")] private static extern bool IsClipboardFormatAvailable(uint format);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool OpenClipboard(IntPtr hwnd);
    [DllImport("user32.dll")] private static extern bool CloseClipboard();
    [DllImport("user32.dll")] private static extern IntPtr GetClipboardData(uint format);
    [DllImport("kernel32.dll")] private static extern IntPtr GlobalLock(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern bool GlobalUnlock(IntPtr handle);
    [DllImport("kernel32.dll")] private static extern UIntPtr GlobalSize(IntPtr handle);
    private static readonly uint Exclude = RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing");
    private static readonly uint ViewerIgnore = RegisterClipboardFormat("Clipboard Viewer Ignore");
    private static readonly uint History = RegisterClipboardFormat("CanIncludeInClipboardHistory");
    private static readonly uint Cloud = RegisterClipboardFormat("CanUploadToCloudClipboard");
    private readonly HwndSource _source;
    private readonly System.Windows.Threading.DispatcherTimer _debounce = new() { Interval = TimeSpan.FromMilliseconds(400) };
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _sending = new();
    private readonly ConcurrentDictionary<string, string> _next = new();
    private string? _last, _own;
    public ClipboardShare()
    {
        var parameters = new HwndSourceParameters("HopDrop clipboard") { ParentWindow = new IntPtr(-3), Width = 0, Height = 0, WindowStyle = 0 };
        _source = new HwndSource(parameters);
        _source.AddHook(Hook);
        AddClipboardFormatListener(_source.Handle);
        _debounce.Tick += async (_, _) =>
        {
            _debounce.Stop();
            if (!Preferences.ShareCopiedText) return;
            if (PrivateClipboard()) { _own = null; return; }
            string? text = ReadText();
            if (text == _own) { _own = null; _last = text; return; }
            _own = null;
            if (!ClipboardSharePolicy.ShouldShare(Preferences.ShareCopiedText, text, null, _last, false)) return;
            _last = text;
            try
            {
                var devices = await App.Peer.DevicesAsync();
                await Task.WhenAll(devices.Where(d => d.Platform == "android" && App.Peer.Nearby.Any(n => n.Id == d.Id))
                    .Select(device => SendOnlineAsync(device.Id, text!)));
            }
            catch (Exception ex) { Debug.WriteLine(ex); }
        };
    }
    /// <summary>One send at a time per phone; a copy made meanwhile replaces any older one still waiting, so the phone ends up with the latest.</summary>
    private async Task SendOnlineAsync(string id, string text)
    {
        _next[id] = text;
        var gate = _sending.GetOrAdd(id, _ => new SemaphoreSlim(1, 1));
        while (_next.ContainsKey(id))
        {
            if (!await gate.WaitAsync(0)) return; // the running send picks up the newest text when it's done
            try
            {
                while (_next.TryRemove(id, out var latest))
                {
                    try { await App.Peer.SendTextAsync(id, latest, clipboard: true); }
                    catch (Exception ex) { Debug.WriteLine(ex); }
                }
            }
            finally { gate.Release(); }
        }
    }
    private bool PrivateClipboard()
    {
        if (!OpenClipboard(_source.Handle)) return true;
        try
        {
            return ClipboardSharePolicy.IsPrivate(IsClipboardFormatAvailable(Exclude), IsClipboardFormatAvailable(ViewerIgnore),
                Dword(History), Dword(Cloud));
        }
        finally { CloseClipboard(); }
    }
    private static int? Dword(uint format)
    {
        if (!IsClipboardFormatAvailable(format)) return null;
        IntPtr handle = GetClipboardData(format);
        if (handle == IntPtr.Zero || GlobalSize(handle).ToUInt64() < 4) return 0;
        IntPtr data = GlobalLock(handle);
        if (data == IntPtr.Zero) return 0;
        try { return Marshal.ReadInt32(data); }
        finally { GlobalUnlock(handle); }
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
