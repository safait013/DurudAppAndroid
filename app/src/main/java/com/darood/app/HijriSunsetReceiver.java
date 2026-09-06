package com.darood.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Fires once at/after the local sunset, asks {@link HijriCoordinator} to
 * recompute the now-applicable date, reschedule the next sunset alarm, and push
 * the updated Hijri date to the Home screen if it is visible. Deliberately
 * lightweight (cache/DB + alarm only — no API work inside the receiver); the
 * full location/API refresh runs on the next app foreground.
 */
public class HijriSunsetReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (!HijriSunsetScheduler.ACTION_REFRESH.equals(intent.getAction())) {
            AppLogger.w("HijriSunsetReceiver", "Unexpected action: " + intent.getAction());
            return;
        }
        AppLogger.i("HijriSunsetReceiver", "Sunset refresh fired");
        try {
            HijriCoordinator.get().onSunsetFired(context.getApplicationContext());
        } catch (Throwable t) {
            AppLogger.e("HijriSunsetReceiver", "Sunset refresh crashed", t);
        }
    }
}