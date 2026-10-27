package com.hop.drop;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.hop.drop.core.Json;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends what the user asked for (spec 6, TransferService): one job per device, devices side by side (see {@link Sends}).
 * Being a foreground service is what lets a send go on after the user leaves HopDrop, and lets the phone join a PC's
 * direct link. Its notification stays until the last job ends.
 */
public final class TransferService extends Service {
    /** Jobs queued or running; the notification stays (and Android keeps the service) until the last one ends. */
    private final AtomicInteger pending = new AtomicInteger();
    /** The newest start: stopping with it only works once no newer start came in, whatever order parallel jobs end in. */
    private final AtomicInteger latestStart = new AtomicInteger();
    /** Starts (onStartCommand) and ends both run here, so an ending job can't stop the service under a job that just started. */
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        latestStart.set(startId);
        HopApp app = (HopApp) getApplication();
        if ("cancel".equals(intent.getAction())) {
            String device = intent.getStringExtra("device");
            if (app.peer() != null) {
                if (device == null) app.peer().cancelOutgoing(); else app.peer().cancelOutgoing(device);
            }
            if (pending.get() == 0) stopSelf(startId);
            return START_NOT_STICKY;
        }
        Sends.Job job;
        try {
            job = Sends.Job.from(Json.parseObject(intent.getStringExtra("job")));
        } catch (Exception e) {
            return START_NOT_STICKY;
        }
        int count = pending.incrementAndGet();
        startForeground(Notices.OUTGOING, summary(app, count));
        Sends.submit(job.deviceId, () -> {
            try {
                Sends.run(app, job, true);
            } finally {
                app.notices().clearOutgoing(job.deviceId);
                main.post(() -> {
                    int left = pending.decrementAndGet();
                    if (left == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf(latestStart.get());
                    } else {
                        startForeground(Notices.OUTGOING, summary(app, left));
                    }
                });
            }
        });
        return START_NOT_STICKY;
    }

    private Notification summary(HopApp app, int count) {
        return app.notification("transfers", Language.text(app, R.string.notification_sending_summary),
                Language.wrap(app).getResources().getQuantityString(R.plurals.sends_in_progress, count, count), true, -1)
                .setSmallIcon(R.drawable.ic_direction_out).setGroup(Notices.OUTGOING_GROUP).setGroupSummary(true)
                .setShowWhen(false).build();
    }

    /** Android 15 limits background data-sync work to six hours a day; stop cleanly instead of being killed. */
    @Override
    public void onTimeout(int startId, int fgsType) {
        HopApp app = (HopApp) getApplication();
        if (app.peer() != null) app.peer().cancelOutgoing();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
