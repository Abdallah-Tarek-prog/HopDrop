package com.hop.drop.store;

import android.util.Log;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;

/**
 * How long each step of saving a received file takes, to find what makes thousands of small files slow. Off unless turned
 * on with {@code adb shell setprop log.tag.HopDropTiming DEBUG}; then logcat gets the average of every step per 200 files.
 */
final class SaveTimings {
    static final String TAG = "HopDropTiming";
    /** Steps: creating the entry, opening it for writing, writing, closing, picking a free name, making it visible, finding its folder. */
    static final int CREATE = 0, OPEN = 1, WRITE = 2, CLOSE = 3, NAME = 4, PUBLISH = 5, FOLDER = 6;
    private static final int BETWEEN = 7, OTHER = 8;
    private static final String[] STEPS = {"create", "open", "write", "close", "name", "publish", "folder", "between", "other"};
    private static final int EVERY = 200;
    /** A pause longer than this between two files is a new transfer, not part of saving. */
    private static final long NEW_TRANSFER_NS = 5_000_000_000L;
    private static final Clock OFF = new Clock(false);
    private static final long[] sums = new long[STEPS.length];
    private static int counted, logged;
    private static long lastEnd, batchStarted;

    private SaveTimings() {
    }

    /** The clock for one file; a clock that does nothing while timing is off. */
    static Clock start() {
        return Log.isLoggable(TAG, Log.DEBUG) ? new Clock(true) : OFF;
    }

    private static synchronized void record(long started, long ended, long[] spent) {
        if (lastEnd != 0 && started - lastEnd < NEW_TRANSFER_NS && started > lastEnd) spent[BETWEEN] = started - lastEnd;
        long steps = 0;
        for (int s = 0; s < BETWEEN; s++) steps += spent[s];
        spent[OTHER] = Math.max(0, ended - started - steps);
        lastEnd = ended;
        if (counted == 0) batchStarted = started;
        for (int s = 0; s < STEPS.length; s++) sums[s] += spent[s];
        if (++counted < EVERY) return;
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "files %d-%d, ms per file:", logged + 1, logged + counted));
        long all = 0;
        for (int s = 0; s < STEPS.length; s++) {
            all += sums[s];
            line.append(String.format(Locale.ROOT, " %s %.1f", STEPS[s], sums[s] / 1e6 / counted));
            sums[s] = 0;
        }
        // "wall": the clock time the batch took per file. Lower than "total" when steps of different files overlap.
        line.append(String.format(Locale.ROOT, " | total %.1f | wall %.1f", all / 1e6 / counted, (ended - batchStarted) / 1e6 / counted));
        Log.d(TAG, line.toString());
        logged += counted;
        counted = 0;
    }

    static final class Clock {
        private final boolean on;
        private final long[] spent = new long[STEPS.length];
        private long started, mark;

        private Clock(boolean on) {
            this.on = on;
            if (on) started = mark = System.nanoTime();
        }

        /** Starts the next step now (time since the last step isn't counted to any step). */
        void begin() {
            if (on) mark = System.nanoTime();
        }

        /** Ends a step that started at the last {@link #begin} or {@link #end}. */
        void end(int step) {
            if (!on) return;
            long now = System.nanoTime();
            spent[step] += now - mark;
            mark = now;
        }

        /** Counts the time spent inside writes. */
        OutputStream wrap(OutputStream out) {
            if (!on) return out;
            return new FilterOutputStream(out) {
                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    long t = System.nanoTime();
                    out.write(b, off, len);
                    spent[WRITE] += System.nanoTime() - t;
                }
            };
        }

        /** The file is saved. */
        void done() {
            if (on) record(started, System.nanoTime(), spent);
        }
    }
}
