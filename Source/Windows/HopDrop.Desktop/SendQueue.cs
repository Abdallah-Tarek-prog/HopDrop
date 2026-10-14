using System.Diagnostics;
using System.Net.Sockets;
using System.Text.Json;
using System.Collections.Concurrent;
using HopDrop.Core;

namespace HopDrop.Desktop;

internal sealed record PendingSend(Guid Id, string DeviceId, List<SendItem>? Items, string? Text, bool Clipboard, DateTimeOffset Created);

/// <summary>Pending sends live in App, so a closed window does not stop retries.</summary>
internal sealed class SendQueue : IDisposable
{
    private readonly HopDropPeer _peer;
    private readonly string _path;
    private readonly List<PendingSend> _entries;
    private readonly Dictionary<string, SemaphoreSlim> _gates = [];
    private readonly ConcurrentDictionary<string, TransferResult> _lastResult = new();
    private readonly ConcurrentDictionary<string, byte> _moved = new();
    private readonly ConcurrentDictionary<string, byte> _trying = new();
    private readonly ConcurrentDictionary<Guid, byte> _suppressed = new();
    private readonly Dictionary<Guid, CancellationTokenSource> _running = [];
    private readonly HashSet<Guid> _retrying = [];
    /// <summary>Every 20 s: devices seen on the network are tried again (a device that comes back right after a failed try
    /// may not count as "new" to discovery); others only every 2 minutes, by their saved addresses.</summary>
    private readonly System.Windows.Threading.DispatcherTimer _timer = new() { Interval = TimeSpan.FromSeconds(20) };
    private int _ticks;
    private bool _disposed;
    public event Action? Changed;
    public IReadOnlyList<PendingSend> Entries => _entries;
    public bool Has(string deviceId) => _entries.Any(e => e.DeviceId == deviceId);
    public bool TakeSuppressed(Guid transferId) => _suppressed.TryRemove(transferId, out _);
    public SendQueue(HopDropPeer peer, string directory)
    {
        _peer = peer; _path = Path.Combine(directory, "queue.json");
        try { _entries = JsonSerializer.Deserialize<List<PendingSend>>(File.ReadAllText(_path)) ?? []; }
        catch (Exception ex) when (ex is IOException or JsonException or UnauthorizedAccessException) { _entries = []; Debug.WriteLine(ex); }
        _peer.TransferFinished += OnFinished;
        _timer.Tick += (_, _) =>
        {
            bool all = ++_ticks % 6 == 0;
            foreach (var device in _entries.Select(e => e.DeviceId).Distinct().ToArray())
                if (all || _peer.Nearby.Any(n => n.Id == device)) Retry(device);
        };
        _timer.Start();
    }
    private void OnFinished(TransferResult result)
    {
        if (result.Incoming) return;
        _lastResult[result.PeerId] = result;
        if (!_trying.ContainsKey(result.PeerId)) return;
        if (result.SavedPaths.Count > 0 || result.Bytes > 0) _moved.TryAdd(result.PeerId, 0);
        if (!_moved.ContainsKey(result.PeerId) && (result.ErrorCode is "io_error" or "timeout"))
            _suppressed.TryAdd(result.TransferId, 0);
    }
    private SemaphoreSlim Gate(string device) => _gates.TryGetValue(device, out var gate) ? gate : _gates[device] = new SemaphoreSlim(1, 1);
    public Task SendFilesAsync(string device, IReadOnlyList<SendItem> items) => SendAsync(new PendingSend(Guid.NewGuid(), device, items.ToList(), null, false, DateTimeOffset.UtcNow), false);
    public Task SendTextAsync(string device, string text, bool clipboard = false) => SendAsync(new PendingSend(Guid.NewGuid(), device, null, text, clipboard, DateTimeOffset.UtcNow), false);
    private async Task SendAsync(PendingSend entry, bool retry)
    {
        var gate = Gate(entry.DeviceId);
        await gate.WaitAsync();
        try
        {
            if (_disposed || retry && !_entries.Any(e => e.Id == entry.Id)) return;
            var items = entry.Items?.Where(i => File.Exists(i.Path)).ToList();
            if (entry.Items is not null && items!.Count == 0)
            {
                if (retry) { Remove(entry.Id); Platform.Notify("Send cancelled", "The files are no longer on this PC."); }
                else throw new FileNotFoundException("The files are no longer on this PC.");
                return;
            }
            _lastResult.TryRemove(entry.DeviceId, out _);
            _moved.TryRemove(entry.DeviceId, out _);
            _trying.TryAdd(entry.DeviceId, 0);
            using var stop = new CancellationTokenSource();
            _running[entry.Id] = stop;
            if (!retry) RememberDevice(entry.DeviceId);
            try
            {
                if (entry.Text is not null) await _peer.SendTextAsync(entry.DeviceId, entry.Text, entry.Clipboard, stop.Token);
                else await _peer.SendAsync(entry.DeviceId, items!, stop.Token);
                if (retry) Remove(entry.Id);
                if (entry.Text is not null && !_lastResult.ContainsKey(entry.DeviceId))
                {
                    App.WindowInstance?.OnHistoryChanged();
                    Platform.Notify("Sent text to " + await DeviceNameAsync(entry.DeviceId), entry.Text.Length > 200 ? entry.Text[..200] + "…" : entry.Text);
                }
                else if (retry && App.WindowInstance is { IsVisible: true, IsActive: true })
                    Platform.Notify("Sent to " + await DeviceNameAsync(entry.DeviceId), entry.Text is null ? Fmt.Files(items!.Count) : "Text");
            }
            catch (Exception ex)
            {
                if (_disposed || stop.IsCancellationRequested) return;
                string name = await DeviceNameAsync(entry.DeviceId);
                bool moved = _moved.ContainsKey(entry.DeviceId);
                bool offline = !moved && (ex is SocketException or TimeoutException or OperationCanceledException
                    || ex is HopDropException he && (he.Code is "io_error" or "timeout"));
                if (offline)
                {
                    if (!retry)
                    {
                        _entries.Add(entry); Save();
                        var device = (await _peer.DevicesAsync()).FirstOrDefault(d => d.Id == entry.DeviceId);
                        Platform.Notify("Offline · sends when it's back", name + "\n" + WaitingHint(device));
                    }
                }
                else
                {
                    if (retry) Remove(entry.Id);
                    Platform.Notify($"Couldn't send to {name}", Platform.ExplainException(ex, name));
                    if (!retry) throw;
                }
            }
            finally { _trying.TryRemove(entry.DeviceId, out _); _running.Remove(entry.Id); }
        }
        finally { gate.Release(); Changed?.Invoke(); }
    }
    private static void RememberDevice(string id)
    {
        try { Preferences.LastDevice = id; } catch (Exception ex) when (ex is IOException or UnauthorizedAccessException) { Debug.WriteLine(ex); }
    }
    internal static string WaitingHint(PairedDevice? device)
    {
        if (device?.Platform == "android" && App.Instance.Link?.State == DirectLinkState.On)
            return "Open HopDrop on your phone and tap Connect";
        return Preferences.DirectLink ? "Connect both devices to the same Wi-Fi" :
            "Use the same Wi-Fi or turn on Settings → Direct connection";
    }
    private async Task<string> DeviceNameAsync(string id)
    {
        try { var d = (await _peer.DevicesAsync()).FirstOrDefault(d => d.Id == id); return d?.Alias ?? d?.Name ?? "your device"; }
        catch { return "your device"; }
    }
    private void Save()
    {
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_path)!);
            string temp = _path + ".tmp";
            File.WriteAllText(temp, JsonSerializer.Serialize(_entries));
            File.Move(temp, _path, true);
        }
        catch (Exception ex) { Debug.WriteLine(ex); Platform.Notify("Couldn't save pending sends", ex.Message); }
        Changed?.Invoke();
    }
    private void Remove(Guid id) { _entries.RemoveAll(e => e.Id == id); Save(); }
    public void Cancel(string device)
    {
        foreach (var entry in _entries.Where(e => e.DeviceId == device).ToArray())
            if (_running.TryGetValue(entry.Id, out var stop)) stop.Cancel();
        _entries.RemoveAll(e => e.DeviceId == device); Save();
    }
    public void Retry(string device)
    {
        if (_disposed) return;
        foreach (var entry in _entries.Where(e => e.DeviceId == device).ToArray())
            if (_retrying.Add(entry.Id)) _ = RetryAsync(entry);
    }
    private async Task RetryAsync(PendingSend entry)
    {
        try { await SendAsync(entry, true); } catch (Exception ex) { Debug.WriteLine(ex); }
        finally { _retrying.Remove(entry.Id); }
    }
    public void RetryNearby()
    {
        foreach (var id in _peer.Nearby.Select(n => n.Id).Distinct()) Retry(id);
    }
    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true; _timer.Stop(); _peer.TransferFinished -= OnFinished;
        foreach (var stop in _running.Values.ToArray()) stop.Cancel();
    }
}
