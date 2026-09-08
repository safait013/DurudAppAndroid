package com.darood.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Dispatches a dated month-end event; keeps the process alive for background resolution. */
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
        final PendingResult pending = goAsync();
        try {
            HijriCoordinator.get().onSunsetFired(context.getApplicationContext(),
                    intent.getStringExtra(HijriSunsetScheduler.EXTRA_DATE),
                    intent.getIntExtra(HijriSunsetScheduler.EXTRA_DAY, 0), pending::finish);
        } catch (Throwable t) {
            pending.finish();
            AppLogger.e("HijriSunsetReceiver", "Sunset refresh crashed", t);
        }
    }
}
