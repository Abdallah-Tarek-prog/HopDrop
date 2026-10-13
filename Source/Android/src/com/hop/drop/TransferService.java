package com.hop.drop;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import com.hop.drop.core.Json;
import com.hop.drop.core.Peer;
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
        Peer.Device queued = app.book().get(job.deviceId);
        String label = queued == null ? "the device" : queued.label();
        startForeground(Notices.OUTGOING, app.notification("transfers", "Connecting to " + label,
                job.textOnly() ? "Getting ready to send your text" : "Getting ready to send " + job.summary, true, -1)
                .setSmallIcon(R.drawable.ic_direction_out).setShowWhen(false).build());
        pending.incrementAndGet();
        Sends.submit(job.deviceId, () -> {
            try {
                Sends.run(app, job, true);
            } finally {
                main.post(() -> {
                    if (pending.decrementAndGet() == 0) {
                        stopForeground(STOP_FOREGROUND_REMOVE);
                        stopSelf(latestStart.get());
                    }
                });
            }
        });
        return START_NOT_STICKY;
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
