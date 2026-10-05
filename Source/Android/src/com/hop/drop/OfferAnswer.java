package com.hop.drop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The Accept and Decline buttons on a "wants to send you files" notification. */
public final class OfferAnswer extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ((HopApp) context.getApplicationContext()).answerOffer(intent.getStringExtra("id"),
                intent.getBooleanExtra("accept", false));
    }
}
