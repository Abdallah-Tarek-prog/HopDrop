namespace HopDrop.Core;

/// <summary>
/// Smoothed speed and time left for one transfer (same rules as the Android app's TransferMeter),
/// plus a throttle so progress is reported about four times a second instead of once per data frame.
/// </summary>
public sealed class TransferMeter(long total, TimeSpan now)
{
    private static readonly TimeSpan Sample = TimeSpan.FromMilliseconds(500);
    private static readonly TimeSpan Report = TimeSpan.FromMilliseconds(250);
    private readonly TimeSpan _started = now;
    private TimeSpan _sampleAt = now, _reportedAt = now - Report;
    private long _sampleBytes;
    private double _speed;

    public void Update(long done, TimeSpan now)
    {
        var elapsed = now - _sampleAt;
        if (elapsed < Sample) return;
        double current = (done - _sampleBytes) / elapsed.TotalSeconds;
        _speed = _speed <= 0 ? current : _speed * 0.7 + current * 0.3;
        _sampleAt = now; _sampleBytes = done;
    }
    public double Speed(long done, TimeSpan now)
    {
        if (_speed > 0) return _speed;
        double seconds = (now - _started).TotalSeconds;
        return seconds <= 0 ? 0 : done / seconds;
    }
    public TimeSpan? Left(long done, TimeSpan now)
    {
        double rate = Speed(done, now);
        return total < 0 || rate <= 0 ? null : TimeSpan.FromSeconds(Math.Ceiling(Math.Max(0, total - done) / rate));
    }
    public bool Due(TimeSpan now)
    {
        if (now - _reportedAt < Report) return false;
        _reportedAt = now; return true;
    }
    public TimeSpan Elapsed(TimeSpan now) => now - _started;
}
