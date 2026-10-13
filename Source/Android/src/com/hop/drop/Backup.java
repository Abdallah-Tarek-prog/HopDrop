package com.hop.drop;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.BatteryManager;
import com.hop.drop.core.Format;
import com.hop.drop.core.Peer;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Auto-backup (Settings → Auto-backup, off by default): new files in one folder the user picked (for example the
 * camera's) go to one paired device by themselves, whenever that device is on the same network. They arrive in one
 * folder of the same name there, filled up over time ("merge"). The files already sent are remembered by their id and
 * size, so nothing is sent twice. It runs while HopDrop runs: in the background when "Receive in the background" is on.
 */
final class Backup {
    /** At most this many files per round; the rest go in the next one. */
    private static final int ROUND = 500;
    /** Without a reason to look sooner (the device showing up), a folder is looked through at most this often. */
    private static final long EVERY_MS = 10 * 60 * 1000;
    private static final AtomicBoolean busy = new AtomicBoolean();
    /** A round's files are being sent (or wait their turn): no new round until it ends, so nothing goes twice. */
    private static final AtomicBoolean sending = new AtomicBoolean();
    private static final AtomicBoolean again = new AtomicBoolean();

    /** What Settings shows. */
    static final class State {
        final boolean on, charging;
        final String folder, deviceId, status;
        final long lastRun;
        State(boolean on, String folder, String deviceId, boolean charging, String status, long lastRun) {
            this.on = on; this.folder = folder; this.deviceId = deviceId; this.charging = charging; this.status = status; this.lastRun = lastRun;
        }
    }

    private Backup() { }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("backup_v1", Context.MODE_PRIVATE); }

    static State state(Context c) {
        SharedPreferences p = prefs(c);
        return new State(p.getBoolean("on", false), p.getString("folder_name", null), p.getString("device", null),
                p.getBoolean("charging", false), p.getString("status", null), p.getLong("last_run", 0));
    }

    static Uri tree(Context c) {
        String t = prefs(c).getString("tree", null);
        return t == null ? null : Uri.parse(t);
    }

    /** Turns auto-backup on for {@code tree} to {@code deviceId}. {@code existing}: also send the files already there. */
    static void start(HopApp app, Uri tree, String folderName, String deviceId, boolean existing) {
        prefs(app).edit().putBoolean("on", true).putString("tree", tree.toString()).putString("folder_name", folderName)
                .putString("device", deviceId).putString("status", existing ? null : "Looking at what's already there…")
                .putLong("last_check", 0).apply();
        new Thread(() -> {
            // A new folder starts with nothing sent. "Only new ones": what's in it now counts as sent (a list of what's there,
            // because some storage doesn't say when a file changed).
            sentFile(app, deviceId).delete();
            if (!existing) snapshot(app, tree, deviceId);
            check(app, true);
        }, "HopDrop backup start").start();
    }

    /** Marks everything in the folder right now as sent to {@code deviceId}. */
    private static void snapshot(HopApp app, Uri tree, String deviceId) {
        try (FileOutputStream out = new FileOutputStream(sentFile(app, deviceId), true)) {
            for (FolderScan.Item f : FolderScan.files(app.getContentResolver(), tree)) out.write((key(f) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    static void stop(Context c) { prefs(c).edit().putBoolean("on", false).putString("status", null).apply(); }

    /** Each device has its own list of what it got. A device that never got anything starts with "only new files from now". */
    static void setDevice(HopApp app, String deviceId) {
        prefs(app).edit().putString("device", deviceId).putString("status", null).apply();
        Uri tree = tree(app);
        new Thread(() -> {
            if (tree != null && !sentFile(app, deviceId).exists()) snapshot(app, tree, deviceId);
            check(app, true);
        }, "HopDrop backup device").start();
    }

    static void setCharging(HopApp app, boolean on) {
        prefs(app).edit().putBoolean("charging", on).apply();
        check(app, true);
    }

    /** A device showed up (or connected to this phone): if it's the backup's device, look for new files now. */
    static void deviceSeen(HopApp app, String id) {
        String device = prefs(app).getString("device", null);
        if (device != null && device.equalsIgnoreCase(id)) check(app, false);
    }

    private static void status(HopApp app, String text) {
        prefs(app).edit().putString("status", text).apply();
        app.changed();
    }

    /** The device wasn't reachable when a round started; the files wait for the next one. */
    static void waiting(HopApp app, String label) { status(app, "Waiting for " + label); }

    private static boolean charging(Context c) {
        Intent battery = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int plugged = battery == null ? 0 : battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return plugged != 0;
    }

    /**
     * Sends the folder's new files, if any. {@code now}: the user asked (or a setting changed), so don't wait for the
     * usual interval. Only devices on the same network are used; a backup never joins a direct link by itself.
     */
    static void check(HopApp app, boolean now) {
        SharedPreferences p = prefs(app);
        if (!p.getBoolean("on", false) || app.peer() == null) return;
        long last = p.getLong("last_check", 0);
        if (!now && System.currentTimeMillis() - last < EVERY_MS && p.getString("status", "").startsWith("Up to date")) return;
        if (sending.get() || !busy.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                round(app);
            } catch (Exception e) {
                status(app, "Couldn't read the folder. Pick it again in Settings.");
            } finally {
                busy.set(false);
            }
        }, "HopDrop backup").start();
    }

    private static void round(HopApp app) throws Exception {
        SharedPreferences p = prefs(app);
        p.edit().putLong("last_check", System.currentTimeMillis()).apply();
        Uri tree = tree(app);
        Peer.Device device = app.book().get(p.getString("device", ""));
        if (tree == null) { status(app, "Pick a folder"); return; }
        if (device == null) { status(app, "Pick a device"); return; }
        if (p.getBoolean("charging", false) && !charging(app)) { status(app, "Waiting for the charger"); return; }
        Set<String> sent = sent(app, device.id);
        List<FolderScan.Item> files = FolderScan.files(app.getContentResolver(), tree);
        List<String[]> picked = new ArrayList<>();
        for (FolderScan.Item f : files) {
            if (sent.contains(key(f))) continue;
            if (picked.size() < ROUND) picked.add(new String[]{f.uri.toString(), f.name, f.folder, String.valueOf(f.size), key(f)});
        }
        if (picked.isEmpty()) {
            status(app, "Up to date");
            return;
        }
        Sends.Job job = new Sends.Job(device.id, Format.files(picked.size()) + " to back up");
        job.listed.addAll(picked);
        job.backup = true;
        job.merge = true;
        status(app, "Backing up " + Format.files(picked.size()) + " to " + device.label());
        sending.set(true);
        Sends.submit(device.id, () -> Sends.run(app, job, false));
    }

    /** The round's files arrived: remember them, so they're never sent again. */
    static synchronized void sent(HopApp app, Sends.Job job) {
        try (FileOutputStream out = new FileOutputStream(sentFile(app, job.deviceId), true)) {
            for (String[] item : job.listed) out.write((item[4] + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        prefs(app).edit().putLong("last_run", System.currentTimeMillis()).putInt("last_count", job.listed.size())
                .putString("status", "Backed up " + Format.files(job.listed.size())).apply();
        app.changed();
        // More may be waiting (big first backups go in rounds): the next round starts once this one has ended.
        again.set(true);
    }

    /** The round's send ended (sent, failed or the device was away): another round may start, at once if this one worked. */
    static void ended(HopApp app) {
        sending.set(false);
        if (again.getAndSet(false)) check(app, true);
    }

    private static File sentFile(Context c, String deviceId) {
        return new File(c.getFilesDir(), "backup-sent-" + deviceId.substring(0, Math.min(16, deviceId.length())) + ".txt");
    }

    private static synchronized Set<String> sent(Context c, String deviceId) {
        Set<String> keys = new HashSet<>();
        try {
            File f = sentFile(c, deviceId);
            if (f.exists()) keys.addAll(Files.readAllLines(f.toPath(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return keys;
    }

    private static String key(FolderScan.Item f) { return f.documentId + "|" + f.size; }
}
