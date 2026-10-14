package com.hop.drop;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.service.notification.StatusBarNotification;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import com.hop.drop.core.Peer;
import com.hop.drop.core.BackupRules;
import com.hop.drop.net.DirectLink;
import java.io.File;
import java.nio.file.Files;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// sdk 35: the Android version the screenshot tests already use, so Robolectric needs no extra download.
@Config(application = HopApp.class, sdk = 35)
public final class AndroidFixesTest {
    private HopApp app;
    private File source;
    private final Uri tree = Uri.parse("content://test.docs/tree/root");

    public static final class Documents extends ContentProvider {
        static File source;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
            MatrixCursor rows = new MatrixCursor(columns);
            if (uri.toString().contains("/children")) rows.addRow(values(columns, "root/file", "note.txt", "text/plain", 5L));
            else rows.addRow(values(columns, "root", "Notes", DocumentsContract.Document.MIME_TYPE_DIR, 0L));
            return rows;
        }
        private Object[] values(String[] columns, String id, String name, String type, long size) {
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                switch (columns[i]) {
                    case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row[i] = id; break;
                    case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row[i] = name; break;
                    case DocumentsContract.Document.COLUMN_MIME_TYPE: row[i] = type; break;
                    case DocumentsContract.Document.COLUMN_SIZE: row[i] = size; break;
                    default: row[i] = 0L;
                }
            }
            return row;
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }

    @Before public void setup() throws Exception {
        app = (HopApp) RuntimeEnvironment.getApplication();
        source = new File(app.getFilesDir(), "note.txt");
        Files.writeString(source.toPath(), "hello");
        Documents.source = source;
        ShadowContentResolver.registerProviderInternal("test.docs", new Documents());
    }

    @Test public void backupListsBelongToEachDevice() throws Exception {
        String firstId = Backup.create(app, tree, "Notes", "device-one");
        String secondId = Backup.create(app, tree, "Notes", "device-one");
        Sends.Job first = new Sends.Job("device-one", "backup");
        first.backupId = firstId;
        first.listed.add(new String[]{"content://test.docs/document/file", "note.txt", "Notes", "5", "root/file|5"});
        Backup.sent(app, first);
        File one = new File(app.getFilesDir(), BackupRules.sentFile(firstId, "device-one"));
        File two = new File(app.getFilesDir(), BackupRules.sentFile(firstId, "device-two"));
        File otherBackup = new File(app.getFilesDir(), BackupRules.sentFile(secondId, "device-one"));
        assertTrue(Files.readString(one.toPath()).contains("root/file|5"));
        assertFalse(two.exists());
        assertFalse(otherBackup.exists());
        assertTrue(Backup.snapshot(app, firstId, tree, "device-two", BackupRules.ALL));
        assertTrue(Files.readString(two.toPath()).contains("root/file|5"));
        assertEquals("root/file|5\n", Files.readString(one.toPath()));
    }

    @Test public void oldBackupKeepsItsSentListDuringMigration() throws Exception {
        app.getSharedPreferences("backup_v2", 0).edit().clear().commit();
        app.getSharedPreferences("backup_v1", 0).edit().clear().putBoolean("on", true).putString("tree", tree.toString())
            .putString("folder_name", "Notes").putString("device", "old-device").putBoolean("charging", true).commit();
        Files.writeString(new File(app.getFilesDir(), BackupRules.oldSentFile("old-device")).toPath(), "root/file|5\n");
        Backup.Entry entry = Backup.entries(app).get(0);
        assertTrue(entry.on); assertTrue(entry.charging); assertEquals("Notes", entry.folder);
        assertEquals("root/file|5\n", Files.readString(new File(app.getFilesDir(), BackupRules.sentFile(entry.id, "old-device")).toPath()));
        assertEquals(entry.id, Backup.entries(app).get(0).id);
    }

    /**
     * A waiting send's private copies go when it's cancelled. (Making the copy can't be checked here: Robolectric always lets
     * HopDrop keep access to a file, so it never needs one. The phone test covers that.)
     */
    @Test public void cancellingAWaitingSendRemovesItsPrivateCopy() throws Exception {
        Sends.Job job = new Sends.Job("device-one", "1 file");
        File copy = new File(app.getFilesDir(), "waiting/" + job.id + "/0/note.txt");
        assertTrue(copy.getParentFile().mkdirs());
        Files.writeString(copy.toPath(), "hello");
        job.files.add(Uri.fromFile(copy));
        Sends.queue(app, job);
        assertEquals(Uri.fromFile(copy), Sends.queued(app).get(0).files.get(0));
        Sends.cancelQueued(app, "device-one");
        assertTrue(Sends.queued(app).isEmpty());
        assertFalse(copy.exists());
        assertFalse(copy.getParentFile().exists());
    }

    @Test public void cancellingOneWaitingDeviceKeepsTheOther() {
        Sends.queue(app, new Sends.Job("device-one", "first"));
        Sends.queue(app, new Sends.Job("device-two", "second"));
        Sends.cancelQueued(app, "device-one");
        assertEquals(0, Sends.waitingFor(app, "device-one"));
        assertEquals(1, Sends.waitingFor(app, "device-two"));
    }

    @Test public void cancelledRetryDoesNotSendAfterItsTurnArrives() throws Exception {
        String deviceId = "device-cancelled";
        app.book().put(new Peer.Device(deviceId, "Away PC", "windows", 7410, "198.51.100.1"));
        Sends.Job job = new Sends.Job(deviceId, "1 file");
        Sends.queue(app, job);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        Sends.submit(deviceId, () -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
            Sends.retry(app, deviceId, false);
            Sends.cancelQueued(app, deviceId);
            Sends.submit(deviceId, finished::countDown);
            release.countDown();
            assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(Sends.queued(app).isEmpty());
        } finally {
            release.countDown();
            java.lang.reflect.Field lanes = Sends.class.getDeclaredField("lanes");
            lanes.setAccessible(true);
            @SuppressWarnings("unchecked") java.util.Map<String, java.util.concurrent.ExecutorService> map =
                    (java.util.Map<String, java.util.concurrent.ExecutorService>) lanes.get(null);
            java.util.concurrent.ExecutorService lane = map.remove(deviceId);
            if (lane != null) lane.shutdownNow();
        }
    }

    @Test public void cancelledWaitingRetryDoesNotRequeueUnreachableDevice() {
        Peer.Device device = new Peer.Device("cancelled-retry", "Cancelled retry PC", "windows", 7410, null);
        app.book().put(device);
        Sends.Job job = new Sends.Job(device.id, "1 file");
        Sends.queue(app, job);
        Sends.cancelQueued(app, device.id);

        // No saved address means reach() stops before opening a socket.
        Sends.run(app, job, false, true);

        assertFalse(Sends.queued(app, job.id));
        assertNull(app.live().outgoing(device.label()));
        assertFalse(active(4000 + (device.label().hashCode() & 0xfff)));
    }

    @Test public void firstSendStillQueuesAndAnnouncesUnreachableDevice() {
        Peer.Device device = new Peer.Device("first-send-away", "First send PC", "windows", 7410, null);
        app.book().put(device);
        Sends.Job job = new Sends.Job(device.id, "1 file");

        // No saved address means reach() stops before opening a socket.
        Sends.run(app, job, false);

        assertTrue(Sends.queued(app, job.id));
        assertEquals("queued", app.live().outgoing(device.label()).note);
        assertTrue(active(4000 + (device.label().hashCode() & 0xfff)));
    }

    @Test public void waitingRetryRemainsQueuedWhenDeviceIsStillAway() {
        Peer.Device device = new Peer.Device("retry-away", "Waiting PC", "windows", 7410, null);
        app.book().put(device);
        Sends.Job job = new Sends.Job(device.id, "1 file");
        Sends.queue(app, job);

        Sends.run(app, job, false, true);

        assertTrue(Sends.queued(app, job.id));
        assertEquals("queued", app.live().outgoing(device.label()).note);
        assertFalse(active(4000 + (device.label().hashCode() & 0xfff)));
        Sends.cancelQueued(app, device.id);
        assertNull(app.live().outgoing(device.label()));
    }

    @Test public void outgoingProgressAndCleanupStayPerDevice() {
        Peer.Progress one = new Peer.Progress("transfer-one", false, "First PC", "a.txt", 1, 1, 1, 5, 0, -1, false, false, "device-one");
        Peer.Progress two = new Peer.Progress("transfer-two", false, "Second PC", "b.txt", 1, 1, 1, 5, 0, -1, false, false, "device-two");
        app.notices().progress(one);
        app.notices().progress(two);
        assertNotEquals(Notices.outgoingId(one.deviceId), Notices.outgoingId(two.deviceId));
        assertTrue(active(Notices.outgoingId(one.deviceId)));
        assertTrue(active(Notices.outgoingId(two.deviceId)));
        app.notices().clearOutgoing(one.deviceId);
        assertFalse(active(Notices.outgoingId(one.deviceId)));
        assertTrue(active(Notices.outgoingId(two.deviceId)));
    }

    @Test public void clipboardActionAppearsOnlyAfterPairing() {
        Notification before = app.notices().ready();
        assertTrue(before.actions == null || before.actions.length == 0);
        app.book().put(new Peer.Device("device-one", "First PC", "windows", 7410, "192.168.1.2"));
        Notification after = app.notices().ready();
        assertEquals(1, after.actions.length);
        assertEquals("Send clipboard", after.actions[0].title.toString());
    }

    private boolean active(int id) {
        for (StatusBarNotification notification : app.getSystemService(NotificationManager.class).getActiveNotifications())
            if (notification.getId() == id) return true;
        return false;
    }

    @Test public void failedJoinReleasesItsUser() throws Exception {
        DirectLink link = new DirectLink(app);
        java.lang.reflect.Field wifi = DirectLink.class.getDeclaredField("wifi");
        wifi.setAccessible(true);
        wifi.set(link, null);
        try {
            link.use("DIRECT-HopDrop-ABCD", "abcdefghijklmnopqrst", "192.168.137.1", 100);
            fail("Wi-Fi is unavailable in the test");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().startsWith("wifi_off"));
        }
        assertNull(link.connectedTo());
        java.lang.reflect.Field users = DirectLink.class.getDeclaredField("users");
        users.setAccessible(true);
        assertEquals(0, users.getInt(link));
    }

    @Test public void secondJoinWaitsForTheJoinLock() throws Exception {
        DirectLink link = new DirectLink(app);
        java.lang.reflect.Field wifi = DirectLink.class.getDeclaredField("wifi");
        wifi.setAccessible(true);
        wifi.set(link, null);
        java.lang.reflect.Field joining = DirectLink.class.getDeclaredField("joining");
        joining.setAccessible(true);
        java.lang.reflect.Field users = DirectLink.class.getDeclaredField("users");
        users.setAccessible(true);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        synchronized (joining.get(link)) {
            Thread worker = new Thread(() -> {
                try { link.use("DIRECT-HopDrop-ABCD", "abcdefghijklmnopqrst", "192.168.137.1", 100); }
                catch (Throwable e) { failure.set(e); }
                finally { done.countDown(); }
            });
            worker.start();
            long end = System.currentTimeMillis() + 1000;
            while (System.currentTimeMillis() < end) {
                synchronized (link) { if (users.getInt(link) == 1) break; }
                Thread.sleep(5);
            }
            synchronized (link) { assertEquals(1, users.getInt(link)); }
            assertFalse(done.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
        }
        assertTrue(done.await(1, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof java.io.IOException);
        assertEquals(0, users.getInt(link));
    }
}
