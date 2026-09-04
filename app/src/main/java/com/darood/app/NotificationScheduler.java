package com.darood.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/**
 * Real notification scheduler for Durud &amp; Salam reminders.
 *
 * One {@link AlarmManager} alarm per enabled weekday. Each alarm is a one-shot
 * alarm for the next occurrence of that weekday/time; when it fires,
 * {@link NotificationReceiver} posts the notification and re-schedules the same
 * weekday one week later (handles DST and avoids periodic-alarm drift).
 *
 * Persistence: SharedPreferences "app_settings" key "notif_config" (JSON):
 * { "enabled": bool, "days": { "1"(Sunday).."7"(Saturday): {"h":..,"m":..} } }
 *
 * Alarm ID strategy: stable alarm requestCode = 4000 + weekday (1..7) and a
 * stable notification id = 2100 + weekday. Every time the settings change we
 * cancel all existing alarms first, then re-create only the enabled ones, so
 * duplicates are impossible and changed times replace the old alarm.
 */
public final class NotificationScheduler {

    public static final String PREFS_FILE = AppSettings.PREFS_FILE; // "app_settings"
    public static final String KEY_CONFIG = "notif_config";

    public static final String CHANNEL_ID = "durood_reminders";
    public static final String ACTION_NOTIFICATION = "com.darood.app.ACTION_DUROOD_NOTIFICATION";

    public static final String EXTRA_DAY = "day";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTE = "minute";
    public static final String EXTRA_INDEX = "index";

    /** Stable unique alarm PendingIntent request codes per weekday (1..7). */
    private static final int ALARM_ID_BASE = 4000;
    /** Stable notification id per weekday. */
    private static final int NOTIFICATION_ID_BASE = 2100;
    /** Maximum number of reminders allowed per weekday. */
    private static final int MAX_TIMES_PER_DAY = 20;
    /** Request code for the notification's content (open-app) PendingIntent. */
    private static final int CONTENT_REQUEST_CODE = 300;
    private static final int DEFAULT_HOUR = 20; // 8:00 PM default
    private static final int DEFAULT_MINUTE = 0;

    private NotificationScheduler() {
    }

    // ===== Scheduling =====

    /**
     * Cancels every existing alarm, then (if enabled and the notification
     * permission is granted) schedules one alarm per enabled weekday.
     * Call after any settings change, on boot and after app update.
     */
    public static void rescheduleAll(Context context) {
        AppLogger.i("NotificationScheduler", "Rescheduling notifications");
        cancelAll(context);
        createChannel(context);
        if (!isEnabled(context)) {
            AppLogger.i("NotificationScheduler", "Notifications disabled; alarms cleared");
            return;
        }
        if (!hasNotificationPermission(context)) {
            AppLogger.w("NotificationScheduler", "Notification permission not granted; scheduling deferred");
            return; // config is saved; scheduling resumes when permission is granted
        }
        JSONObject days = getConfig(context).optJSONObject("days");
        if (days == null) {
            return;
        }
        Iterator<String> keys = days.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                int day = Integer.parseInt(key);
                org.json.JSONArray times = days.optJSONArray(key);
                if (times == null) {
                    continue;
                }
                for (int i = 0; i < times.length(); i++) {
                    JSONObject t = times.optJSONObject(i);
                    if (t == null) {
                        continue;
                    }
                    scheduleDay(context, day, i,
                            t.optInt("h", DEFAULT_HOUR), t.optInt("m", DEFAULT_MINUTE));
                }
            } catch (Exception e) {
                AppLogger.w("NotificationScheduler", "Failed to schedule day " + key, e);
            }
        }
    }

    /** Schedules a one-shot alarm for the next occurrence of the given weekday/time. */
    public static void scheduleDay(Context context, int dayOfWeek, int index, int hour, int minute) {
        if (dayOfWeek < Calendar.SUNDAY || dayOfWeek > Calendar.SATURDAY) {
            return;
        }
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        int h = clamp(hour, 0, 23);
        int m = clamp(minute, 0, 59);
        long triggerAt = nextTriggerMillis(dayOfWeek, h, m);
        Intent intent = new Intent(context, NotificationReceiver.class)
                .setAction(ACTION_NOTIFICATION)
                .putExtra(EXTRA_DAY, dayOfWeek)
                .putExtra(EXTRA_INDEX, index)
                .putExtra(EXTRA_HOUR, h)
                .putExtra(EXTRA_MINUTE, m);
        PendingIntent pi = pendingForDay(context, dayOfWeek, index, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

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
        AppLogger.d("NotificationScheduler", "Scheduled day " + dayOfWeek + " at " + h + ":" + m);
    }

    /** Cancels the alarm for one weekday (used when a day is removed). */
    public static void cancelDay(Context context, int dayOfWeek) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        for (int i = 0; i < MAX_TIMES_PER_DAY; i++) {
            Intent intent = new Intent(context, NotificationReceiver.class)
                    .setAction(ACTION_NOTIFICATION);
            PendingIntent pi = pendingForDay(context, dayOfWeek, i, intent, PendingIntent.FLAG_NO_CREATE);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
        }
    }

    /** Cancels every scheduled alarm (settings change or notifications disabled). */
    public static void cancelAll(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        for (int day = Calendar.SUNDAY; day <= Calendar.SATURDAY; day++) {
            cancelDay(context, day);
        }
        AppLogger.i("NotificationScheduler", "Cancelled all notification alarms");
    }

    // ===== Notification firing =====

    /**
     * Called when an alarm fires. Re-schedules the same weekday for the following
     * week, then posts the localized notification. If the config no longer has
     * the day enabled (or notifications got disabled), the alarm is cancelled
     * instead without showing anything.
     */
    public static void handleNotificationFire(Context context, Intent fired) {
        if (!isEnabled(context)) {
            cancelAll(context);
            return;
        }
        int day = fired.getIntExtra(EXTRA_DAY, 0);
        int index = fired.getIntExtra(EXTRA_INDEX, 0);
        AppLogger.i("NotificationScheduler", "Notification alarm fired (day=" + day + ", index=" + index + ")");
        JSONObject days = getConfig(context).optJSONObject("days");
        org.json.JSONArray times = days != null ? days.optJSONArray(String.valueOf(day)) : null;
        boolean stillEnabled = times != null && index < times.length();
        if (!stillEnabled) {
            cancelDay(context, day);
            return;
        }
        int hour = fired.getIntExtra(EXTRA_HOUR, DEFAULT_HOUR);
        int minute = fired.getIntExtra(EXTRA_MINUTE, DEFAULT_MINUTE);
        scheduleDay(context, day, index, hour, minute); // roll forward one week

        createChannel(context);
        Intent contentIntent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int contentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            contentFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentPi = PendingIntent.getActivity(context, CONTENT_REQUEST_CODE,
                contentIntent, contentFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(context.getString(R.string.notification_text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(contentPi)
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.notification_text)));
        if (Build.VERSION.SDK_INT >= 21) {
            builder.setVibrate(new long[]{0L});
        }

        NotificationManagerCompat.from(context).notify(notificationId(day, index), builder.build());
    }

    /** Creates the notification channel (API 26+); localized name &amp; description. */
    public static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(true);
        nm.createNotificationChannel(channel);
    }

    // ===== Permission =====

    /** POST_NOTIFICATIONS is only required on Android 13+ (API 33+). */
    public static boolean hasNotificationPermission(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(context,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // ===== Helpers =====

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Stable alarm request code per weekday + index. */
    private static int alarmRequestCode(int dayOfWeek, int index) {
        return ALARM_ID_BASE + dayOfWeek * 100 + index;
    }

    /** Stable notification id per weekday + index. */
    private static int notificationId(int dayOfWeek, int index) {
        return NOTIFICATION_ID_BASE + dayOfWeek * 100 + index;
    }

    private static PendingIntent pendingForDay(Context context, int dayOfWeek, int index, Intent intent, int flags) {
        int f = flags;
        if (Build.VERSION.SDK_INT >= 23) {
            f |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, alarmRequestCode(dayOfWeek, index), intent, f);
    }

    /** Milliseconds until the next occurrence of the given weekday/time. */
    static long nextTriggerMillis(int dayOfWeek, int hour, int minute) {
        Calendar now = Calendar.getInstance();
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        next.set(Calendar.DAY_OF_WEEK, dayOfWeek);
        if (!next.after(now)) {
            next.add(Calendar.DAY_OF_YEAR, 7);
        }
        return next.getTimeInMillis();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /** @return the persisted config, or a sensible default when nothing is stored. */
    public static JSONObject getConfig(Context context) {
        String raw = prefs(context).getString(KEY_CONFIG, null);
        if (raw == null) {
            return defaultConfig();
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            AppLogger.e("NotificationScheduler", "Failed to parse notification config; using default", e);
            return defaultConfig();
        }
    }

    private static JSONObject defaultConfig() {
        JSONObject def = new JSONObject();
        try {
            def.put("enabled", false);
            def.put("days", new JSONObject());
        } catch (Exception ignored) {
            AppLogger.w("NotificationScheduler", "Failed to build default notification config");
        }
        return def;
    }

    /** True once global notifications are enabled. */
    public static boolean isEnabled(Context context) {
        return getConfig(context).optBoolean("enabled", false);
    }

    /**
     * Validates, cleans and persists the config supplied by the settings UI
     * ({"enabled":bool,"days":{"<1..7>":{"h":..,"m":..},...}}). Hour/minute are
     * clamped to valid ranges and non-weekday keys are dropped.
     */
    public static boolean saveConfig(Context context, String json) {
        try {
            JSONObject in = new JSONObject(json);
            boolean enabled = in.optBoolean("enabled", false);
            JSONObject days = in.optJSONObject("days");
            JSONObject clean = new JSONObject();
            JSONObject cleanDays = new JSONObject();
            if (days != null) {
                Iterator<String> keys = days.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    int day;
                    try {
                        day = Integer.parseInt(key);
                    } catch (Exception e) {
                        continue;
                    }
                    if (day < Calendar.SUNDAY || day > Calendar.SATURDAY) {
                        continue;
                    }
                    org.json.JSONArray times = days.optJSONArray(key);
                    if (times == null) {
                        continue;
                    }
                    org.json.JSONArray cleanTimes = new org.json.JSONArray();
                    for (int i = 0; i < times.length(); i++) {
                        JSONObject t = times.optJSONObject(i);
                        if (t == null) {
                            continue;
                        }
                        int hour = clamp(t.optInt("h", DEFAULT_HOUR), 0, 23);
                        int minute = clamp(t.optInt("m", DEFAULT_MINUTE), 0, 59);
                        cleanTimes.put(new JSONObject().put("h", hour).put("m", minute));
                    }
                    if (cleanTimes.length() > 0) {
                        cleanDays.put(String.valueOf(day), cleanTimes);
                    }
                }
            }
            clean.put("enabled", enabled);
            clean.put("days", cleanDays);
            prefs(context).edit().putString(KEY_CONFIG, clean.toString()).apply();
            return true;
        } catch (Exception e) {
            AppLogger.e("NotificationScheduler", "Failed to save notification config", e);
            return false;
        }
    }
}
