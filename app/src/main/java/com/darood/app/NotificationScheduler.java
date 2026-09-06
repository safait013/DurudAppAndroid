package com.darood.app;

import android.annotation.SuppressLint;
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
    public static final String ACTION_UPDATE_REMINDER = "com.darood.app.ACTION_UPDATE_REMINDER";

    public static final String EXTRA_DAY = "day";
    public static final String EXTRA_HOUR = "hour";
    public static final String EXTRA_MINUTE = "minute";
    public static final String EXTRA_INDEX = "index";
    private static final String EXTRA_UPDATE_VERSION_CODE = "update_version_code";

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

    private static final String UPDATE_CHANNEL_ID = "app_updates";
    private static final int UPDATE_NOTIFICATION_ID = 2901;
    private static final int UPDATE_ALARM_REQUEST_CODE = 5901;
    private static final String KEY_UPDATE_TRACKED_VERSION = "update_reminder_version_code";
    private static final String KEY_UPDATE_LAST_NOTIFIED_VERSION = "update_last_notified_version_code";
    private static final String KEY_UPDATE_LAST_NOTIFICATION_TIME = "update_last_notification_time";
    private static final String KEY_UPDATE_NEXT_REMINDER_TIME = "update_next_reminder_time";

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
    @SuppressWarnings("deprecation") // AlarmManager.set is only used on API 21–22 (minSdk).
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
    @SuppressLint("MissingPermission") // Permission is checked before scheduling and again before posting.
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

        if (!hasNotificationPermission(context)) {
            AppLogger.w("NotificationScheduler", "Notification permission not granted at delivery");
            return;
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

    // ===== App update reminders =====

    /** Called only after a valid remote version response has been compared with this install. */
    public static void handleUpdateAvailability(Context context, long installedVersionCode,
                                                long latestVersionCode) {
        if (installedVersionCode >= latestVersionCode) {
            AppLogger.i("NotificationScheduler", "Update installed; stopping old update reminder");
            cancelUpdateReminder(context, true);
            return;
        }

        AppLogger.i("NotificationScheduler", "Update detected: versionCode=" + latestVersionCode);
        SharedPreferences preferences = prefs(context);
        long trackedVersion = preferences.getLong(KEY_UPDATE_TRACKED_VERSION, -1L);
        long lastNotifiedVersion = preferences.getLong(KEY_UPDATE_LAST_NOTIFIED_VERSION, -1L);
        long lastNotificationTime = preferences.getLong(KEY_UPDATE_LAST_NOTIFICATION_TIME, 0L);
        long now = System.currentTimeMillis();

        if (trackedVersion != latestVersionCode) {
            // A newly published version always begins its own notification cycle.
            preferences.edit().putLong(KEY_UPDATE_TRACKED_VERSION, latestVersionCode).apply();
            postUpdateNotification(context, latestVersionCode);
            return;
        }
        if (lastNotifiedVersion != latestVersionCode || lastNotificationTime <= 0L) {
            // Permission may have been denied. Keep its already-scheduled retry intact.
            if (preferences.getLong(KEY_UPDATE_NEXT_REMINDER_TIME, 0L) > now) {
                AppLogger.i("NotificationScheduler", "Update reminder skipped because 3 days have not passed");
                return;
            }
            postUpdateNotification(context, latestVersionCode);
            return;
        }

        long dueAt = lastNotificationTime + updateReminderIntervalMs();
        if (now < dueAt) {
            AppLogger.i("NotificationScheduler", "Update reminder skipped because 3 days have not passed");
            if (preferences.getLong(KEY_UPDATE_NEXT_REMINDER_TIME, 0L) <= 0L) {
                scheduleUpdateReminder(context, latestVersionCode, dueAt);
            }
            return;
        }
        postUpdateNotification(context, latestVersionCode);
    }

    /** Receives the one-shot update alarm. It deliberately re-checks installed metadata first. */
    public static void handleUpdateReminder(Context context, Intent fired) {
        long versionCode = fired.getLongExtra(EXTRA_UPDATE_VERSION_CODE, -1L);
        SharedPreferences preferences = prefs(context);
        if (versionCode <= 0L || preferences.getLong(KEY_UPDATE_TRACKED_VERSION, -1L) != versionCode) {
            AppLogger.i("NotificationScheduler", "Ignoring stale update reminder");
            return;
        }
        long installedVersionCode = UpdateChecker.getInstalledVersionCode(context);
        if (installedVersionCode >= versionCode) {
            AppLogger.i("NotificationScheduler", "Update installed; old reminder cancelled");
            cancelUpdateReminder(context, true);
            return;
        }
        long lastNotificationTime = preferences.getLong(KEY_UPDATE_LAST_NOTIFICATION_TIME, 0L);
        long dueAt = lastNotificationTime + updateReminderIntervalMs();
        long now = System.currentTimeMillis();
        if (lastNotificationTime > 0L && now < dueAt) {
            AppLogger.i("NotificationScheduler", "Update reminder skipped because 3 days have not passed");
            scheduleUpdateReminder(context, versionCode, dueAt);
            return;
        }
        postUpdateNotification(context, versionCode);
    }

    /** Restores the one stable update alarm after reboot or package replacement. */
    public static void restoreUpdateReminder(Context context) {
        SharedPreferences preferences = prefs(context);
        long versionCode = preferences.getLong(KEY_UPDATE_TRACKED_VERSION, -1L);
        if (versionCode <= 0L) {
            return;
        }
        if (UpdateChecker.getInstalledVersionCode(context) >= versionCode) {
            AppLogger.i("NotificationScheduler", "Update installed; old reminder cancelled");
            cancelUpdateReminder(context, true);
            return;
        }
        long triggerAt = preferences.getLong(KEY_UPDATE_NEXT_REMINDER_TIME, 0L);
        if (triggerAt <= 0L) {
            triggerAt = System.currentTimeMillis() + updateReminderIntervalMs();
        }
        scheduleUpdateReminder(context, versionCode, triggerAt);
    }

    @SuppressLint("MissingPermission") // hasNotificationPermission() is checked immediately below.
    private static void postUpdateNotification(Context context, long versionCode) {
        Context localized = LanguageManager.applyLanguage(context);
        if (!hasNotificationPermission(localized)) {
            AppLogger.w("NotificationScheduler", "Update notification permission failure");
            // Keep the cycle alive without showing a notification or requesting permission.
            scheduleUpdateReminder(context, versionCode,
                    System.currentTimeMillis() + updateReminderIntervalMs());
            return;
        }
        createUpdateChannel(localized);
        Intent launchStore = new Intent(localized, UpdateStoreActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(localized, UPDATE_NOTIFICATION_ID,
                launchStore, flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(localized, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.update_notification_title))
                .setContentText(localized.getString(R.string.update_notification_message))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(localized.getString(R.string.update_notification_message)))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(contentIntent);
        AppLogger.i("NotificationScheduler", "Update notification scheduled");
        NotificationManagerCompat.from(localized).notify(UPDATE_NOTIFICATION_ID, builder.build());

        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putLong(KEY_UPDATE_TRACKED_VERSION, versionCode)
                .putLong(KEY_UPDATE_LAST_NOTIFIED_VERSION, versionCode)
                .putLong(KEY_UPDATE_LAST_NOTIFICATION_TIME, now)
                .apply();
        AppLogger.i("NotificationScheduler", "Update notification generated");
        scheduleUpdateReminder(context, versionCode, now + updateReminderIntervalMs());
    }

    private static void scheduleUpdateReminder(Context context, long versionCode, long triggerAt) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null || versionCode <= 0L) return;
        Intent intent = new Intent(context, NotificationReceiver.class)
                .setAction(ACTION_UPDATE_REMINDER)
                .putExtra(EXTRA_UPDATE_VERSION_CODE, versionCode);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, UPDATE_ALARM_REQUEST_CODE, intent, flags);
        long safeTriggerAt = Math.max(System.currentTimeMillis() + 1_000L, triggerAt);
        if (Build.VERSION.SDK_INT >= 23) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTriggerAt, pending);
        } else {
            manager.set(AlarmManager.RTC_WAKEUP, safeTriggerAt, pending);
        }
        prefs(context).edit().putLong(KEY_UPDATE_NEXT_REMINDER_TIME, safeTriggerAt).apply();
        AppLogger.i("NotificationScheduler", "Update reminder scheduled");
    }

    private static void cancelUpdateReminder(Context context, boolean clearState) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, NotificationReceiver.class).setAction(ACTION_UPDATE_REMINDER);
        int flags = PendingIntent.FLAG_NO_CREATE;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(context, UPDATE_ALARM_REQUEST_CODE, intent, flags);
        if (manager != null && pending != null) {
            manager.cancel(pending);
            pending.cancel();
        }
        NotificationManagerCompat.from(context).cancel(UPDATE_NOTIFICATION_ID);
        if (clearState) {
            prefs(context).edit().remove(KEY_UPDATE_TRACKED_VERSION)
                    .remove(KEY_UPDATE_LAST_NOTIFIED_VERSION)
                    .remove(KEY_UPDATE_LAST_NOTIFICATION_TIME)
                    .remove(KEY_UPDATE_NEXT_REMINDER_TIME).apply();
        }
        AppLogger.i("NotificationScheduler", "Old update reminder cancelled");
    }

    private static void createUpdateChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(UPDATE_CHANNEL_ID,
                context.getString(R.string.update_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.update_notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    private static long updateReminderIntervalMs() {
        // Debug builds are intentionally short for manual verification; release remains ~3 days.
        return AppLogger.isDebuggable() ? 3L * 60L * 1000L : 3L * 24L * 60L * 60L * 1000L;
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
