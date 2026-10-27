using System.Collections.Concurrent;
using System.Net.Sockets;
using HopDrop.Core;

namespace HopDrop.Linux;

internal sealed record QueuedSend(Guid Id, string DeviceId, List<SendItem>? Files, string? Text, bool Clipboard,
    DateTimeOffset Created, bool Interrupted = false);
internal sealed record SendSubmission(Guid Id, string Status, string? Error = null);

/// <summary>Durable offline sends. An interrupted send requires review before retrying, to avoid duplicates.</summary>
internal sealed class OutgoingQueue : IAsyncDisposable
{
    private readonly HopDropPeer _peer;
    private readonly JsonStore<List<QueuedSend>> _store;
    private readonly CancellationTokenSource _stop = new();
    private readonly ConcurrentDictionary<string, SemaphoreSlim> _lanes = new();
    private readonly ConcurrentDictionary<Guid, CancellationTokenSource> _running = new();
    private readonly ConcurrentDictionary<Guid, SendSubmission> _results = new();
    private readonly ConcurrentDictionary<string, bool> _moved = new();
    private readonly ConcurrentDictionary<Guid, byte> _suppressed = new();
    private readonly ConcurrentDictionary<int, Task> _workers = new();
    private readonly Task _timer;
    private QueuedSend[] _snapshot = [];
    private int _workerId;
    public event Action? Changed;
    public event Action<string>? Failed;
    public IReadOnlyList<QueuedSend> Entries => Volatile.Read(ref _snapshot);
    public bool IsSending(Guid id) => _running.ContainsKey(id);
    public bool TakeSuppressed(Guid transfer) => _suppressed.TryRemove(transfer, out _);

    public OutgoingQueue(HopDropPeer peer, string directory)
    {
        _peer = peer;
        _store = new(Path.Combine(directory, "queue.json"), []);
        _peer.TransferFinished += OnFinished;
        _peer.DeviceContacted += Retry;
        _peer.NearbyDevicesChanged += OnNearby;
        _timer = TimerAsync();
    }
    public async Task InitializeAsync() => await RefreshAsync();
    private void OnFinished(TransferResult result)
    {
        if (result.Incoming || !_moved.ContainsKey(result.PeerId)) return;
        if (result.SavedPaths.Count > 0 || result.Bytes > 0) _moved[result.PeerId] = true;
        if (!_moved[result.PeerId] && (result.ErrorCode is "io_error" or "timeout"))
            _suppressed[result.TransferId] = 0;
    }
    private void OnNearby(IReadOnlyList<NearbyDevice> devices)
    {
        foreach (var device in devices) Retry(device.Id);
    }
    public void Retry(string device) => _ = StartWorker(device);
    private Task StartWorker(string device)
    {
        if (_stop.IsCancellationRequested) return Task.CompletedTask;
        int id = Interlocked.Increment(ref _workerId);
        var task = ProcessAsync(device);
        _workers[id] = task;
        _ = task.ContinueWith(_ => _workers.TryRemove(id, out var ignored), TaskScheduler.Default);
        return task;
    }
    public Task<SendSubmission> SendFilesAsync(string device, IReadOnlyList<SendItem> files) =>
        SubmitAsync(new(Guid.NewGuid(), device, files.ToList(), null, false, DateTimeOffset.UtcNow));
    public Task<SendSubmission> SendTextAsync(string device, string text, bool clipboard = false) =>
        SubmitAsync(new(Guid.NewGuid(), device, null, text, clipboard, DateTimeOffset.UtcNow));
    private async Task<SendSubmission> SubmitAsync(QueuedSend entry)
    {
        if (entry.Files is { Count: 0 } || entry.Text is { Length: 0 }) throw new ArgumentException("Choose files or text first.");
        if (!(await _peer.DevicesAsync()).Any(d => d.Id == entry.DeviceId)) throw new HopDropException("not_paired", "Device is not paired.");
        await _store.UpdateAsync(list => [.. list, entry]);
        await RefreshAsync();
        await StartWorker(entry.DeviceId);
        return _results.TryGetValue(entry.Id, out var result) ? result : new(entry.Id, "queued");
    }
    private async Task ProcessAsync(string device)
    {
        var lane = _lanes.GetOrAdd(device, _ => new SemaphoreSlim(1, 1));
        if (!await lane.WaitAsync(0)) return;
        try
        {
            foreach (var entry in (await _store.ReadAsync()).Where(e => e.DeviceId == device && !e.Interrupted).OrderBy(e => e.Created))
            {
                if (_stop.IsCancellationRequested) break;
                using var cancel = CancellationTokenSource.CreateLinkedTokenSource(_stop.Token);
                _running[entry.Id] = cancel;
                _moved[device] = false;
                try
                {
                    bool exists = false;
                    await _store.UpdateAsync(list => list.Select(e =>
                    {
                        if (e.Id != entry.Id) return e;
                        exists = true; return e with { Interrupted = true };
                    }).ToList());
                    if (!exists) continue;
                    cancel.Token.ThrowIfCancellationRequested();
                    await RefreshAsync();
                    if (entry.Files is { } files)
                    {
                        if (files.Any(f => !File.Exists(f.Path))) throw new FileNotFoundException("A queued file is no longer available.");
                        await _peer.SendAsync(device, files, cancel.Token);
                    }
                    else await _peer.SendTextAsync(device, entry.Text!, entry.Clipboard, cancel.Token);
                    await RemoveAsync(entry.Id);
                    _results[entry.Id] = new(entry.Id, "sent");
                }
                catch (Exception error)
                {
                    bool moved = _moved.TryGetValue(device, out var value) && value;
                    if (_stop.IsCancellationRequested)
                    {
                        if (!moved) await MarkWaitingAsync(entry.Id);
                        break;
                    }
                    if (cancel.IsCancellationRequested || error is HopDropException { Code: "cancelled" })
                    {
                        await RemoveAsync(entry.Id);
                        _results[entry.Id] = new(entry.Id, "cancelled");
                    }
                    else if (!moved && (error is SocketException or TimeoutException or OperationCanceledException ||
                        error is HopDropException { Code: "io_error" or "timeout" }))
                    {
                        await MarkWaitingAsync(entry.Id);
                        _results[entry.Id] = new(entry.Id, "queued");
                        break;
                    }
                    else
                    {
                        await RemoveAsync(entry.Id);
                        string code = error is HopDropException hop ? hop.Code : "io_error";
                        _results[entry.Id] = new(entry.Id, "failed", code);
                        Failed?.Invoke(code);
                    }
                }
                finally
                {
                    _running.TryRemove(entry.Id, out _);
                    _moved.TryRemove(device, out _);
                    await RefreshAsync();
                }
            }
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException)
        { Failed?.Invoke("io_error"); }
        finally { lane.Release(); }
    }
    private Task MarkWaitingAsync(Guid id) => _store.UpdateAsync(list => list.Select(e => e.Id == id ? e with { Interrupted = false } : e).ToList());
    private Task RemoveAsync(Guid id) => _store.UpdateAsync(list => list.Where(e => e.Id != id).ToList());
    private async Task RefreshAsync()
    {
        Volatile.Write(ref _snapshot, (await _store.ReadAsync()).ToArray());
        Changed?.Invoke();
    }
    public async Task CancelAsync(Guid id)
    {
        if (_running.TryGetValue(id, out var cancel))
        {
            try { cancel.Cancel(); }
            catch (ObjectDisposedException) { /* The send finished between lookup and cancellation. */ }
        }
        await RemoveAsync(id);
        _results[id] = new(id, "cancelled");
        await RefreshAsync();
    }
    public async Task RetryInterruptedAsync(Guid id)
    {
        var entry = Entries.FirstOrDefault(e => e.Id == id);
        if (entry is null || IsSending(id)) return;
        await MarkWaitingAsync(id);
        await RefreshAsync();
        Retry(entry.DeviceId);
    }
    private async Task TimerAsync()
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(20));
        try
        {
            while (await timer.WaitForNextTickAsync(_stop.Token))
                foreach (string device in Entries.Where(e => !e.Interrupted).Select(e => e.DeviceId).Distinct()) Retry(device);
        }
        catch (OperationCanceledException) when (_stop.IsCancellationRequested) { }
    }
    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        _peer.TransferFinished -= OnFinished;
        _peer.DeviceContacted -= Retry;
        _peer.NearbyDevicesChanged -= OnNearby;
        await _timer;
        await Task.WhenAll(_workers.Values);
        _stop.Dispose();
    }
}
