package com.darood.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Efficient automatic sunset refresh: exactly one {@link AlarmManager} alarm set
 * for the next local sunset, re-scheduled after every sunset (one-shot, never a
 * per-second/minute timer, never a permanent background service). When it fires,
 * {@link HijriSunsetReceiver} recomputes the applicable date, reschedules the next
 * sunset and refreshes the Home screen if it is visible.
 */
public final class HijriSunsetScheduler {

    private static final String TAG = "HijriSunsetScheduler";
    public static final String ACTION_REFRESH =
            "com.primebytelabs.durood.ACTION_HIJRI_SUNSET_REFRESH";
    private static final int REQUEST_CODE = 7101;
    /** Small buffer after the actual sunset so the transition is always visible. */
    private static final long BUFFER_MS = 90_000L;

    private HijriSunsetScheduler() {
    }

    /**
     * Schedules the next automatic refresh at (or just after) the given sunset.
     * A past/invalid instant is always clamped into the future so the refresh can
     * never stop running.
     */
    public static void scheduleNext(Context context, long sunsetEpochMillis) {
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            AppLogger.w(TAG, "AlarmManager unavailable");
            return;
        }
        long now = System.currentTimeMillis();
        long triggerAt = Math.max(sunsetEpochMillis + BUFFER_MS, now + 60_000L);
        Intent intent = new Intent(context, HijriSunsetReceiver.class)
                .setAction(ACTION_REFRESH);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);

        if (Build.VERSION.SDK_INT >= 31) {
            if (am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        AppLogger.i(TAG, "Sunset refresh scheduled at " + HijriMath.hhmm(triggerAt));
    }

    /** Cancels any pending sunset alarm (called before re-scheduling). */
    public static void cancel(Context context) {
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(context, HijriSunsetReceiver.class)
                .setAction(ACTION_REFRESH);
        int flags = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
        am.cancel(pi);
        pi.cancel();
        AppLogger.i(TAG, "Sunset refresh alarm cancelled");
    }
}