package com.darood.app;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** One stable, local-sunset reminder for the effective Hijri day 29. */
public final class HijriDay29Notification {
    public static final String ACTION = "com.darood.app.ACTION_HIJRI_DATE_VERIFICATION";
    private static final String EXTRA_KEY = "hijri_event_key";
    private static final String EXTRA_DATE = "hijri_gregorian_date";
    private static final int ALARM_REQUEST_CODE = 7102;
    private static final int CONTENT_REQUEST_CODE = 7103;
    private static final String KEY_SCHEDULED = "hijri_29_scheduled_event";
    private static final String KEY_SCHEDULED_AT = "hijri_29_scheduled_at";
    private static final String KEY_HANDLED = "hijri_29_handled_event";
    private static final String KEY_HANDLED_DATES = "hijri_29_handled_dates";
    private static final long SUNSET_BUFFER_MS = 90_000L;
    private HijriDay29Notification() { }

    public static void schedule(Context context, String gregorianDate, long sunsetMillis) {
        if (gregorianDate == null || gregorianDate.isEmpty()) return;
        String key = eventKey(gregorianDate);
        long triggerAt = sunsetMillis + SUNSET_BUFFER_MS;
        if (triggerAt <= System.currentTimeMillis()) {
            AppLogger.i("HijriDay29Notification", "Day-29 sunset already passed; not scheduling");
            return;
        }
        SharedPreferences p = prefs(context);
        if (key.equals(p.getString(KEY_HANDLED, null))) {
            AppLogger.i("HijriDay29Notification", "Duplicate event prevented: " + key);
            return;
        }
        if (key.equals(p.getString(KEY_SCHEDULED, null)) && triggerAt == p.getLong(KEY_SCHEDULED_AT, 0L)) {
            AppLogger.d("HijriDay29Notification", "Day-29 event already scheduled: " + key);
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) { AppLogger.w("HijriDay29Notification", "AlarmManager unavailable"); return; }
        Intent intent = new Intent(context, NotificationReceiver.class).setAction(ACTION)
                .putExtra(EXTRA_KEY, key).putExtra(EXTRA_DATE, gregorianDate);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
        if (Build.VERSION.SDK_INT >= 31 && manager.canScheduleExactAlarms()) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        else if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        else manager.set(AlarmManager.RTC_WAKEUP, triggerAt, pending);
        p.edit().putString(KEY_SCHEDULED, key).putLong(KEY_SCHEDULED_AT, triggerAt).apply();
        AppLogger.i("HijriDay29Notification", "Day-29 sunset notification scheduled: " + key);
    }

    public static void cancel(Context context, String reason) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, NotificationReceiver.class).setAction(ACTION);
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
        if (manager != null && pending != null) { manager.cancel(pending); pending.cancel(); }
        prefs(context).edit().remove(KEY_SCHEDULED).remove(KEY_SCHEDULED_AT).apply();
        AppLogger.i("HijriDay29Notification", "Day-29 notification cancelled: " + reason);
    }

    /** Force restoration of the OS alarm while preserving its stable identity and handled key. */
    public static void resetScheduleAfterBoot(Context context) {
        prefs(context).edit().remove(KEY_SCHEDULED_AT).apply();
    }

    /** Fallback from the validated month-end transition/recovery path. */
    public static void fireIfDue(Context context, String gregorianDate) {
        String key = eventKey(gregorianDate);
        if (!key.equals(prefs(context).getString(KEY_SCHEDULED, null))) return;
        Intent fired = new Intent().putExtra(EXTRA_KEY, key).putExtra(EXTRA_DATE, gregorianDate);
        handleFire(LanguageManager.applyLanguage(context), fired);
    }

    /** Validated daily transition also recovers a missed day-29 notification after process death. */
    public static void fireEffectiveBoundary(Context context, String date) {
        String key = eventKey(date);
        if (key.equals(prefs(context).getString(KEY_HANDLED, null))
                || prefs(context).getStringSet(KEY_HANDLED_DATES, new java.util.HashSet<String>()).contains(key)) return;
        prefs(context).edit().putString(KEY_SCHEDULED, key).apply();
        fireIfDue(context, date);
    }
    /** Called with a context localized at receiver delivery time. */
    @SuppressLint("MissingPermission")
    public static void handleFire(Context context, Intent intent) {
        String key = intent.getStringExtra(EXTRA_KEY);
        String date = intent.getStringExtra(EXTRA_DATE);
        if (key == null || date == null || !key.equals(prefs(context).getString(KEY_SCHEDULED, null))) {
            AppLogger.i("HijriDay29Notification", "Stale day-29 event ignored"); return;
        }
        if (key.equals(prefs(context).getString(KEY_HANDLED, null))
                || prefs(context).getStringSet(KEY_HANDLED_DATES, new java.util.HashSet<String>()).contains(key)) {
            AppLogger.i("HijriDay29Notification", "Duplicate delivery prevented: " + key); return;
        }
        if (!HijriCoordinator.get().isEffectiveHijriDay29ForGregorianDate(context, date)) {
            cancel(context, "effective date changed before delivery"); return;
        }
        if (!NotificationScheduler.hasNotificationPermission(context)) {
            finish(context, key); AppLogger.w("HijriDay29Notification", "Notification permission denied"); return;
        }
        NotificationScheduler.createChannel(context);
        Intent openApp = new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, CONTENT_REQUEST_CODE, openApp, flags);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, NotificationScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.hijri_29_notification_title))
                .setContentText(context.getString(R.string.hijri_29_notification_message))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(context.getString(R.string.hijri_29_notification_message)))
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).setContentIntent(content);
        AppLogger.i("HijriDay29Notification", "Day-29 notification fired; current language=" + LanguageManager.getSavedLanguage(context));
        NotificationManagerCompat.from(context).notify(notificationId(date), notification.build());
        finish(context, key);
    }

    private static void finish(Context context, String key) {
        java.util.Set<String> handled = new java.util.HashSet<>(prefs(context).getStringSet(KEY_HANDLED_DATES, new java.util.HashSet<String>()));
        handled.add(key);
        prefs(context).edit().putStringSet(KEY_HANDLED_DATES, handled).putString(KEY_HANDLED, key)
                .remove(KEY_SCHEDULED).remove(KEY_SCHEDULED_AT).apply();
    }
    private static String eventKey(String date) { return "hijri_29_sunset_" + date; }
    private static int notificationId(String date) { return 0x30000000 | (date.hashCode() & 0x0fffffff); }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
    }
}
