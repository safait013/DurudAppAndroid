package com.darood.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/** One dated month-end alarm: day 29 may refresh online; day 30 only resolves offline. */
public final class HijriSunsetScheduler {

    private static final String TAG = "HijriSunsetScheduler";
    public static final String ACTION_REFRESH =
            "com.primebytelabs.durood.ACTION_HIJRI_SUNSET_REFRESH";
    private static final int REQUEST_CODE = 7101;
    public static final String EXTRA_DATE = "hijri_ending_date";
    public static final String EXTRA_DAY = "hijri_ending_day";
    /** Small buffer after the actual sunset so the transition is always visible. */
    private static final long BUFFER_MS = 90_000L;
    private static final String KEY_DATE = "hijri_sunset_scheduled_date";
    private static final String KEY_DAY = "hijri_sunset_scheduled_day";
    private static final String KEY_AT = "hijri_sunset_scheduled_at";

    private HijriSunsetScheduler() {
    }

    /** No past-time clamping: only a validated future month-end boundary can be armed. */
    public static void scheduleNext(Context context, String date, int day, long sunsetEpochMillis) {
        if (HijriMath.calendarFromIso(date) == null || (day != 29 && day != 30)
                || sunsetEpochMillis <= System.currentTimeMillis()) {
            cancel(context);
            return;
        }
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            AppLogger.w(TAG, "AlarmManager unavailable");
            return;
        }
        long triggerAt = sunsetEpochMillis + BUFFER_MS;
        Intent intent = new Intent(context, HijriSunsetReceiver.class)
                .setAction(ACTION_REFRESH).putExtra(EXTRA_DATE, date).putExtra(EXTRA_DAY, day);
        SharedPreferences prefs = context.getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
        int lookupFlags = PendingIntent.FLAG_NO_CREATE
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        if (date.equals(prefs.getString(KEY_DATE, null)) && day == prefs.getLong(KEY_DAY, 0)
                && triggerAt == prefs.getLong(KEY_AT, 0)
                && PendingIntent.getBroadcast(context, REQUEST_CODE, intent, lookupFlags) != null) {
            AppLogger.d(TAG, "Duplicate month-end schedule prevented for " + date);
            return;
        }
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
        prefs.edit().putString(KEY_DATE, date).putLong(KEY_DAY, day).putLong(KEY_AT, triggerAt).apply();
        AppLogger.i(TAG, "Month-end sunset scheduled for " + date + "; day=" + day
                + "; API eligible=" + (day == 29) + "; time=" + HijriMath.hhmm(triggerAt));
    }

    /** Cancels any pending sunset alarm (called before re-scheduling). */
    public static void cancel(Context context) {
        context.getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE).edit()
                .remove(KEY_DATE).remove(KEY_DAY).remove(KEY_AT).apply();
        AlarmManager am =
                (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent intent = new Intent(context, HijriSunsetReceiver.class)
                .setAction(ACTION_REFRESH);
        int flags = PendingIntent.FLAG_NO_CREATE
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
        if (pi == null) return;
        am.cancel(pi);
        pi.cancel();
        AppLogger.i(TAG, "Sunset refresh alarm cancelled");
    }
}
