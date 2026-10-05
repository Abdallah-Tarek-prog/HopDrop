package com.hop.drop.core;

/**
 * Tracks one transfer's bytes over time: a smoothed speed (so "time left" doesn't jump around),
 * time left, and whether it is time to report progress again. Times are System.nanoTime() values.
 */
public final class TransferMeter {
    private static final long SAMPLE_NANOS = 500_000_000L;
    private static final long REPORT_NANOS = 250_000_000L;
    private final long total;
    private final long started;
    private long sampleAt;
    private long sampleBytes;
    private long reportedAt;
    private double speed;

    public TransferMeter(long total, long now) {
        this.total = total;
        started = now;
        sampleAt = now;
        reportedAt = now - REPORT_NANOS;
    }

    /** Records that {@code done} bytes have moved in total. */
    public void update(long done, long now) {
        long elapsed = now - sampleAt;
        if (elapsed < SAMPLE_NANOS) return;
        double current = (done - sampleBytes) / (elapsed / 1e9);
        speed = speed <= 0 ? current : speed * 0.7 + current * 0.3;
        sampleAt = now;
        sampleBytes = done;
    }

    /** Bytes per second; the plain average until the first half-second sample exists. */
    public double speed(long done, long now) {
        if (speed > 0) return speed;
        double seconds = (now - started) / 1e9;
        return seconds <= 0 ? 0 : done / seconds;
    }

    /** Whole seconds left, or -1 when unknown. */
    public long secondsLeft(long done, long now) {
        double rate = speed(done, now);
        if (total < 0 || rate <= 0) return -1;
        return (long) Math.ceil(Math.max(0, total - done) / rate);
    }

    /** True at most four times a second, so listeners are not flooded. */
    public boolean due(long now) {
        if (now - reportedAt < REPORT_NANOS) return false;
        reportedAt = now;
        return true;
    }

    public long elapsedMillis(long now) {
        return (now - started) / 1_000_000L;
    }
}
