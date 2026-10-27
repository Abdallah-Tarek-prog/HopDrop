package com.hop.drop;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/** Buttons on HopDrop's notifications that act without opening the app: Copy (received text), Cancel (a waiting send). */
public final class NoticeAction extends BroadcastReceiver {
    static final String COPY = "com.hop.drop.COPY", CANCEL_QUEUE = "com.hop.drop.CANCEL_QUEUE";

    @Override
    public void onReceive(Context context, Intent intent) {
        HopApp app = (HopApp) context.getApplicationContext();
        String action = intent.getAction();
        if (COPY.equals(action)) {
            String text = intent.getStringExtra("text");
            if (text != null && app.copyText(text) && android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, Language.text(context, R.string.copied), Toast.LENGTH_SHORT).show();
        } else if (CANCEL_QUEUE.equals(action)) {
            String device = intent.getStringExtra("device");
            if (device != null) Sends.cancelQueued(app, device);
        }
        int id = intent.getIntExtra("notification", 0);
        if (id != 0) context.getSystemService(NotificationManager.class).cancel(id);
    }
}
