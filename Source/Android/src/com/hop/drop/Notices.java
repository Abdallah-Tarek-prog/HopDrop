package com.hop.drop;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import com.hop.drop.core.Format;
import com.hop.drop.core.Peer;
import com.hop.drop.net.LocalSockets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Every notification HopDrop shows. Copy rules: the title says what is happening and with whom,
 * the collapsed line says how far along it is, the expanded text adds the file name and speed.
 */
final class Notices {
    static final int OUTGOING = 410;
    static final String OUTGOING_GROUP = "hopdrop.outgoing";
    static final int PAIRING = 412;
    static final int RECEIVER = 413;
    static final int RECEIVER_PROBLEM = 414;
    private static final int OFFER = 20000;
    private final HopApp app;
    private final NotificationManager manager;
    /** When each transfer's notification was last updated: Android shows about one update a second anyway, so more is wasted work. */
    private final Map<String, Long> posted = new ConcurrentHashMap<>();

    Notices(HopApp app) {
        this.app = app;
        manager = app.getSystemService(NotificationManager.class);
    }

    /** The receiver's ongoing notification while it is idle. */
    Notification ready() {
        String name = app.peer() == null ? Language.text(app, R.string.notification_this_phone) : app.peer().name();
        List<LocalSockets.Address> addresses = LocalSockets.describe(app);
        String where = addresses.isEmpty() ? Language.text(app, R.string.notification_join_network)
                : Language.text(app, R.string.notification_visible_as, name, Language.ltr(app, addresses.get(0).ip));
        Notification.Builder ready = app.notification("transfers", Language.text(app, R.string.notification_ready), where, true, -1)
                .setSmallIcon(R.drawable.ic_notification).setShowWhen(false);
        if (!app.book().all().isEmpty()) {
            Intent send = new Intent(app, QuickSendActivity.class).putExtra("clipboard", true);
            ready.addAction(0, Language.text(app, R.string.send_clipboard), PendingIntent.getActivity(app, 5, send,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return ready.build();
    }

    void showReady() {
        if (ReceiveService.running) manager.notify(RECEIVER, ready());
    }

    void progress(Peer.Progress p) {
        long at = SystemClock.elapsedRealtime();
        Long last = posted.get(p.transferId);
        if (last != null && at - last < 1000 && !p.waiting && !p.reconnecting) return;
        posted.put(p.transferId, at);
        int id = p.incoming ? RECEIVER : outgoingId(p.deviceId);
        PendingIntent cancelAction = cancelAction(p);
        if (p.reconnecting) {
            Notification.Builder reconnecting = app.notification("transfers", Language.text(app,
                    p.incoming ? R.string.live_waiting_reconnect : R.string.live_reconnecting, p.peer),
                    Language.text(app, R.string.notification_reconnect_help), true, -1)
                    .setSmallIcon(p.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out).setShowWhen(false)
                    .addAction(0, Language.text(app, R.string.ui_cancel), cancelAction);
            if (!p.incoming) reconnecting.setGroup(OUTGOING_GROUP);
            manager.notify(id, reconnecting.build());
            return;
        }
        if (p.waiting) {
            manager.notify(id, app.notification("transfers", Language.text(app, R.string.live_waiting_accept, p.peer),
                    Language.text(app, R.string.notification_waiting_start, Language.fileObjects(app, p.count)), true, -1)
                    .setSmallIcon(R.drawable.ic_direction_out).setShowWhen(false)
                    .setGroup(OUTGOING_GROUP)
                    .addAction(0, Language.text(app, R.string.ui_cancel), cancelAction).build());
            return;
        }
        String title = Language.text(app, p.incoming ? R.string.notification_receiving : R.string.notification_sending,
                Language.fileObjects(app, p.count), p.peer);
        String left = p.secondsLeft < 0 ? "" : " · " + Language.text(app, R.string.time_left, Language.duration(app, p.secondsLeft));
        String amount = p.total < 0 ? Language.size(app, p.done) : Language.text(app, R.string.amount_of,
                Language.size(app, p.done), Language.size(app, p.total));
        String now = p.count == 1 ? Language.ltr(app, p.file) : Language.text(app, R.string.notification_position,
                Language.ltr(app, p.file), p.index, p.count);
        Notification.Builder builder = app.notification("transfers", title, amount + left, true, p.percent())
                .setSmallIcon(p.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out)
                .setStyle(new Notification.BigTextStyle().bigText(now + "\n" + amount + " · "
                        + Language.text(app, R.string.speed_per_second, Language.size(app, (long)p.speed)) + left))
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .addAction(0, Language.text(app, R.string.ui_cancel), cancelAction);
        if (!p.incoming) builder.setGroup(OUTGOING_GROUP);
        if (p.percent() >= 0) builder.setSubText(p.percent() + "%");
        manager.notify(id, builder.build());
    }

    static int outgoingId(String deviceId) { return 0x40000000 | (deviceId.hashCode() & 0x3fffffff); }

    private PendingIntent cancelAction(Peer.Progress p) {
        Intent cancel = new Intent(app, p.incoming ? ReceiveService.class : TransferService.class).setAction("cancel");
        if (!p.incoming) cancel.putExtra("device", p.deviceId).setData(Uri.parse("hopdrop:cancel/" + p.deviceId));
        return PendingIntent.getService(app, p.incoming ? 2 : outgoingId(p.deviceId), cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void clearOutgoing(String deviceId) {
        if (deviceId != null) manager.cancel(outgoingId(deviceId));
    }

    void result(Peer.Result r) {
        posted.remove(r.transferId);
        boolean ok = r.error == null;
        boolean cancelled = !ok && r.error.startsWith("cancelled");
        String title;
        if (ok) {
            title = Language.text(app, r.incoming ? R.string.received_from : R.string.sent_to,
                    Language.files(app, r.files.size()), r.peer);
        } else if (cancelled) {
            title = Language.text(app, r.incoming ? R.string.notification_cancelled_in : R.string.notification_cancelled_out, r.peer);
        } else {
            title = Language.text(app, r.incoming ? R.string.notification_stopped_in : R.string.notification_stopped_out, r.peer);
        }
        String where = r.folder == null ? "" : Language.text(app, r.incoming ? R.string.history_saved_in : R.string.history_saved_in_on,
                Language.ltr(app, r.folder), r.peer);
        String line;
        String details;
        if (ok) {
            line = Language.names(app, r.files) + " · " + Language.size(app, r.bytes);
            details = Language.list(app, r.files, 8) + (where.isEmpty() ? "" : "\n" + where) + "\n"
                    + (r.millis > 0 ? Language.text(app, R.string.history_size_in, Language.size(app, r.bytes),
                    Language.duration(app, Math.max(1, r.millis / 1000))) : Language.size(app, r.bytes));
        } else {
            String reason = cancelled ? cancelReason(app, r) : ErrorText.forDevice(app, r.error, r.peer);
            String kept = r.files.isEmpty() ? Language.text(app, r.incoming ? R.string.notification_no_files_saved : R.string.notification_no_files_delivered)
                    : Language.text(app, r.incoming ? R.string.notification_partial_saved : R.string.notification_partial_delivered,
                    r.files.size(), Language.fileObjects(app, r.offered), Language.names(app, r.files)) + (where.isEmpty() ? "" : " " + where);
            line = reason;
            details = reason + "\n" + kept;
        }
        // Files that arrived pop up on screen; everything else (sent, failed, cancelled) arrives quietly.
        Notification.Builder n = app.notification(ok && r.incoming ? "received" : "results", title, line, false, -1)
                .setSmallIcon(ok ? (r.incoming ? R.drawable.ic_direction_in : R.drawable.ic_direction_out)
                        : R.drawable.ic_status_error)
                .setStyle(new Notification.BigTextStyle().bigText(details))
                .setAutoCancel(true);
        if (r.incoming && !r.files.isEmpty()) {
            Intent show = new Intent(app, MainActivity.class).putExtra("show_files", true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            n.addAction(0, Language.text(app, R.string.main_show_files), PendingIntent.getActivity(app, 3, show,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            if (r.files.size() == 1) {
                Uri uri = app.receivedUri(r.files.get(0));
                if (uri != null) {
                    String mime = app.getContentResolver().getType(uri);
                    Intent open = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime == null ? "*/*" : mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    n.addAction(0, Language.text(app, R.string.open_file), PendingIntent.getActivity(app, 4, open,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
                }
            }
        }
        if (r.incoming) showReady(); else clearOutgoing(r.deviceId);
        manager.notify(1000 + (r.transferId.hashCode() & 0xffff), n.build());
    }

    /** "Ask before receiving": Accept and Decline right on the notification. It disappears when the offer expires. */
    void offer(Peer.Offer o) {
        int code = o.transferId.hashCode() & 0xffff;
        PendingIntent accept = PendingIntent.getBroadcast(app, code * 2, new Intent(app, OfferAnswer.class)
                .putExtra("id", o.transferId).putExtra("accept", true), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent decline = PendingIntent.getBroadcast(app, code * 2 + 1, new Intent(app, OfferAnswer.class)
                .putExtra("id", o.transferId).putExtra("accept", false), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String size = o.total >= 0 ? " · " + Language.size(app, o.total) : "";
        manager.notify(OFFER + code, app.notification("requests", Language.text(app, R.string.offer_files, o.peer, Language.fileObjects(app, o.files.size())),
                Language.names(app, o.files) + size, false, -1)
                .setSmallIcon(R.drawable.ic_direction_in)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setPriority(Notification.PRIORITY_HIGH)
                .setTimeoutAfter(Math.max(1000, o.deadline - System.currentTimeMillis()))
                .addAction(0, Language.text(app, R.string.ui_accept), accept)
                .addAction(0, Language.text(app, R.string.ui_decline), decline)
                .setAutoCancel(true).build());
    }

    void clearOffer(Peer.Offer o) {
        manager.cancel(OFFER + (o.transferId.hashCode() & 0xffff));
    }

    /** Text from a paired device: the text itself, with Copy (unless it's already copied), Open link and Share. */
    void text(String peer, String text, boolean copied) {
        int id = 3000 + (int) (System.nanoTime() & 0x3ff);
        String trimmed = text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
        Notification.Builder n = app.notification(copied ? "clipboard" : "received", Language.text(app,
                copied ? R.string.notification_copied_from : R.string.text_from, peer),
                trimmed, false, -1).setSmallIcon(R.drawable.ic_direction_in)
                .setStyle(new Notification.BigTextStyle().bigText(trimmed)).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_MESSAGE);
        if (!copied) n.addAction(0, Language.text(app, R.string.ui_copy), PendingIntent.getBroadcast(app, id, new Intent(app, NoticeAction.class)
                .setAction(NoticeAction.COPY).putExtra("text", text).putExtra("notification", id),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        String link = Links.single(text);
        if (link != null) n.addAction(0, Language.text(app, R.string.ui_open_link), PendingIntent.getActivity(app, id + 1,
                new Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        n.addAction(0, Language.text(app, R.string.ui_share), PendingIntent.getActivity(app, id + 2, Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.notify(id, n.build());
    }

    /** A send couldn't reach its device: it waits and goes when the device is back. Cancel gives up on it. */
    void queued(String deviceId, String peer, String what) {
        int id = 4000 + (peer.hashCode() & 0xfff);
        manager.notify(id, app.notification("results", Language.text(app, R.string.notification_queued_title, peer),
                Language.text(app, R.string.notification_queued_body, what, peer), false, -1).setSmallIcon(R.drawable.ic_direction_out)
                .setAutoCancel(true)
                .addAction(0, Language.text(app, R.string.ui_cancel), PendingIntent.getBroadcast(app, id, new Intent(app, NoticeAction.class)
                        .setAction(NoticeAction.CANCEL_QUEUE).putExtra("device", deviceId).putExtra("notification", id),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build());
    }

    /** Removes a waiting-send notice when its job is cancelled. */
    void clearQueued(String peer) { manager.cancel(4000 + (peer.hashCode() & 0xfff)); }

    /** Who cancelled: this phone ("You cancelled the transfer" / "Transfer cancelled") or the other device. */
    static String cancelReason(android.content.Context context, Peer.Result r) {
        return r.error.contains("Peer cancelled") || r.error.contains("Sender cancelled")
                ? Language.text(context, R.string.peer_cancelled, r.peer) : Language.text(context, R.string.main_you_cancelled_it);
    }

    /** An outgoing transfer that never started (unreachable device, missing file, …). */
    void couldNotSend(String peer, String reason) {
        manager.notify(1000 + (peer.hashCode() & 0xffff), app.notification("results", Language.text(app, R.string.notification_could_not_send, peer),
                reason, false, -1).setSmallIcon(R.drawable.ic_status_error)
                .setStyle(new Notification.BigTextStyle().bigText(reason)).setAutoCancel(true).build());
    }

    void receiverProblem(String reason) {
        manager.notify(RECEIVER_PROBLEM, app.notification("results", Language.text(app, R.string.notification_receiver_problem), reason,
                false, -1).setSmallIcon(R.drawable.ic_status_error)
                .setStyle(new Notification.BigTextStyle().bigText(reason)).setAutoCancel(true).build());
    }

    void clearReceiverProblem() {
        manager.cancel(RECEIVER_PROBLEM);
    }
}
