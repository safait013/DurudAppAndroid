package com.darood.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.HashSet;
import java.util.Set;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/** Posts one localized notification when the final effective Hijri date enters day one. */
public final class HijriNewMonthNotification {
    private static final String KEY_HANDLED_EVENTS = "hijri_new_month_handled_events";
    private static final int CONTENT_REQUEST_CODE = 7203;
    private static final String[] MONTH_KEYS = {"", "MUHARRAM", "SAFAR", "RABI_AL_AWWAL",
            "RABI_AL_THANI", "JUMADA_AL_AWWAL", "JUMADA_AL_THANI", "RAJAB", "SHABAN",
            "RAMADAN", "SHAWWAL", "DHU_AL_QIDAH", "DHU_AL_HIJJAH"};
    private HijriNewMonthNotification() { }

    /** Invoked only with the coordinator's already final, sunset-based resolution. */
    @SuppressLint("MissingPermission")
    public static void notifyIfNewMonth(Context context, HijriDayData data, String source) {
        if (data == null || data.hijriDay != 1 || data.hijriMonth < 1 || data.hijriMonth > 12) return;
        String key = MONTH_KEYS[data.hijriMonth] + "_" + data.hijriYear;
        SharedPreferences prefs = prefs(context);
        if (handled(prefs, key)) {
            AppLogger.i("HijriNewMonthNotification", "Duplicate new-month event prevented: " + key);
            return;
        }
        AppLogger.i("HijriNewMonthNotification", "New-month transition detected: " + key
                + "; effective source=" + source);
        if (!NotificationScheduler.hasNotificationPermission(context)) {
            // Mark processed so recovery refreshes cannot repeatedly attempt/spam this transition.
            markHandled(prefs, key);
            AppLogger.w("HijriNewMonthNotification", "Notification permission denied: " + key);
            return;
        }
        // Resolve all display text now, never when an event is first detected/saved.
        Context localized = LanguageManager.applyLanguage(context);
        String month = localized.getString(monthStringResource(data.hijriMonth));
        String message = localized.getString(R.string.hijri_new_month_notification_message,
                month, data.hijriYear);
        NotificationScheduler.createChannel(localized);
        Intent openHome = new Intent(localized, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(localized, CONTENT_REQUEST_CODE, openHome, flags);
        NotificationCompat.Builder notification = new NotificationCompat.Builder(localized, NotificationScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(localized.getString(R.string.hijri_new_month_notification_title))
                .setContentText(message).setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT).setContentIntent(content);
        NotificationManagerCompat.from(localized).notify(notificationId(data.hijriMonth, data.hijriYear), notification.build());
        markHandled(prefs, key);
        AppLogger.i("HijriNewMonthNotification", "New-month notification generated: " + key
                + "; current language=" + LanguageManager.getSavedLanguage(localized));
    }

    private static boolean handled(SharedPreferences prefs, String key) {
        return prefs.getStringSet(KEY_HANDLED_EVENTS, new HashSet<String>()).contains(key);
    }

    private static void markHandled(SharedPreferences prefs, String key) {
        Set<String> events = new HashSet<>(prefs.getStringSet(KEY_HANDLED_EVENTS, new HashSet<String>()));
        events.add(key);
        prefs.edit().putStringSet(KEY_HANDLED_EVENTS, events).apply();
    }
    private static int monthStringResource(int month) {
        switch (month) {
            case 1: return R.string.hijri_month_1; case 2: return R.string.hijri_month_2;
            case 3: return R.string.hijri_month_3; case 4: return R.string.hijri_month_4;
            case 5: return R.string.hijri_month_5; case 6: return R.string.hijri_month_6;
            case 7: return R.string.hijri_month_7; case 8: return R.string.hijri_month_8;
            case 9: return R.string.hijri_month_9; case 10: return R.string.hijri_month_10;
            case 11: return R.string.hijri_month_11; default: return R.string.hijri_month_12;
        }
    }
    private static int notificationId(int month, int year) { return 0x20000000 | ((year & 0xffff) << 4) | month; }
    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
    }
}