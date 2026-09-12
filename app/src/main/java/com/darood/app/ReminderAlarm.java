package com.darood.app;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/** One active user reminder. Future schedules are owned exclusively by NotificationScheduler. */
final class ReminderAlarm {
    static final String CHANNEL = "user_reminder_alarms_v2";
    static final int NOTIFICATION_ID = 6200;
    static final String EXTRA_OCCURRENCE = "occurrence";
    static final String ACTION_DISMISS = "com.darood.app.DISMISS_USER_REMINDER";
    static final String ACTION_CHANGED = "com.darood.app.USER_REMINDER_CHANGED";
    private static final String ACTIVE = "reminder_active";
    private static final String UNTIL = "reminder_active_until";

    private ReminderAlarm() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
    }

    static String active(Context context) {
        return prefs(context).getString(ACTIVE, null);
    }

    static ReminderAlertMode mode(Context context) {
        return ReminderAlertMode.parse(prefs(context).getString("reminder_active_mode", null));
    }

    static boolean canFullScreen(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        return manager != null && (Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent());
    }

    static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                context.getString(R.string.reminder_alarm_channel), NotificationManager.IMPORTANCE_HIGH);
        // The controller owns all audio/haptics. Channel defaults must never add a second alert.
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setDescription(context.getString(R.string.reminder_alarm_channel_description));
        ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    static boolean canNotify(Context context) {
        if (!NotificationScheduler.hasNotificationPermission(context)
                || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE))
                    .getNotificationChannel(CHANNEL);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    static void fire(Context context, String occurrence, ReminderAlertMode mode) {
        if (occurrence.equals(active(context))) {
            AppLogger.i("ReminderAlarm", "Duplicate active occurrence blocked " + occurrence);
            return;
        }
        createChannel(context);
        if (!canNotify(context)) {
            AppLogger.w("ReminderAlarm", "Notification permission/channel unavailable; alarm skipped");
            return;
        }
        // A new occurrence replaces the controller in-place; never race stopSelf against a new start.
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        if (!prefs(context).edit().putString(ACTIVE, occurrence)
                .putString("reminder_active_mode", mode.name())
                .remove(UNTIL).commit()) {
            AppLogger.w("ReminderAlarm", "Active occurrence persistence failed");
            return;
        }
        AppLogger.i("ReminderAlarm", "Receiver delivery " + occurrence + " mode=" + mode
                + " fullScreen=" + canFullScreen(context));
        start(context, occurrence);
    }

    static void start(Context context, String occurrence) {
        if (occurrence == null || !occurrence.equals(active(context))) return;
        if (ReminderAlarmService.isRunning(occurrence)) return;
        try {
            ContextCompat.startForegroundService(context, new Intent(context, ReminderAlarmService.class)
                    .putExtra(EXTRA_OCCURRENCE, occurrence));
        } catch (RuntimeException e) {
            AppLogger.w("ReminderAlarm", "Background alarm controller unavailable; visual notification fallback", e);
            postFallback(context, occurrence);
        }
    }

    static void postFallback(Context context, String occurrence) {
        postFallback(context, occurrence, true);
    }

    static void postFallback(Context context, String occurrence, boolean launch) {
        if (!occurrence.equals(active(context)) || !canNotify(context)) return;
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context, occurrence, launch));
            AppLogger.i("ReminderAlarm", "Temporary full-screen notification posted; OS controls launch " + occurrence);
        } catch (SecurityException e) {
            AppLogger.w("ReminderAlarm", "Notification delivery permission failure", e);
            dismiss(context, occurrence);
        }
    }

    static Notification notification(Context base, String occurrence) {
        return notification(base, occurrence, true);
    }

    static int title(String occurrence) {
        return occurrence != null && occurrence.startsWith("friday:")
                ? R.string.friday_reminder_title : R.string.notification_title;
    }

    static int message(String occurrence) {
        return occurrence != null && occurrence.startsWith("friday:")
                ? R.string.friday_reminder_message : R.string.notification_text;
    }

    static Notification notification(Context base, String occurrence, boolean launch) {
        Context context = LanguageManager.applyLanguage(base);
        Intent show = new Intent(context, ReminderAlarmActivity.class)
                .setData(Uri.parse("durood-reminder:" + occurrence))
                .putExtra(EXTRA_OCCURRENCE, occurrence)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        android.os.Bundle options = null;
        if (Build.VERSION.SDK_INT >= 35) {
            ActivityOptions activityOptions = ActivityOptions.makeBasic();
            activityOptions.setPendingIntentCreatorBackgroundActivityStartMode(
                    Build.VERSION.SDK_INT >= 36 ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                            : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            options = activityOptions.toBundle();
        }
        PendingIntent content = PendingIntent.getActivity(context, NOTIFICATION_ID, show, flags, options);
        Intent dismiss = new Intent(context, NotificationReceiver.class).setAction(ACTION_DISMISS)
                .setData(show.getData()).putExtra(EXTRA_OCCURRENCE, occurrence);
        PendingIntent dismissPi = PendingIntent.getBroadcast(context, NOTIFICATION_ID, dismiss, flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(title(occurrence)))
                .setContentText(context.getString(message(occurrence)))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(context.getString(message(occurrence))))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                // setSilent(true) creates a silent notification GROUP in AndroidX, suppressing FSI.
                // Use explicit null sound/vibration instead; HIGH channel remains ungrouped.
                .setOngoing(true).setAutoCancel(false).setOnlyAlertOnce(true)
                .setSound(null).setVibrate(null).setDefaults(0)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(content).setDeleteIntent(dismissPi)
                .addAction(0, context.getString(R.string.reminder_dismiss), dismissPi);
        if (launch && canFullScreen(context)) {
            builder.setFullScreenIntent(content, true);
            AppLogger.i("ReminderAlarm", "Direct Activity full-screen intent attached " + occurrence);
        } else if (launch) {
            AppLogger.w("ReminderAlarm", "Full-screen intent unavailable; high-priority notification fallback");
        }
        return builder.build();
    }

    /** null means cancel whichever occurrence is active (settings edits/recovery). */
    static void dismiss(Context context, String occurrence) {
        String stored = prefs(context).getString(ACTIVE, null);
        if (occurrence != null && !occurrence.equals(stored)) return;
        prefs(context).edit().remove(ACTIVE).remove(UNTIL).remove("reminder_active_mode").commit();
        ReminderAlarmService.stopAlert(occurrence);
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID);
        context.sendBroadcast(new Intent(ACTION_CHANGED).setPackage(context.getPackageName()));
        if (stored != null) AppLogger.i("ReminderAlarm", "Dismiss; controller and notification 6200 cleared " + stored);
    }
}
