package com.darood.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Receives scheduled reminder alarms (one per enabled weekday) and either posts
 * the localized Durud &amp; Salam notification and rolls the alarm forward one week,
 * or (if the schedule was changed meanwhile) silently cancels the stale alarm.
 */
public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            AppLogger.w("NotificationReceiver", "Received null intent or action");
            return;
        }
        if (NotificationScheduler.ACTION_NOTIFICATION.equals(intent.getAction())) {
            AppLogger.i("NotificationReceiver", "Notification alarm received");
            // Use the in-app language (not the system language) for the notification text.
            Context localized = LanguageManager.applyLanguage(context);
            NotificationScheduler.handleNotificationFire(localized, intent);
        } else if (HijriDay29Notification.ACTION.equals(intent.getAction())) {
            // The saved in-app language is intentionally read when the alarm fires.
            Context localized = LanguageManager.applyLanguage(context);
            AppLogger.i("NotificationReceiver", "Hijri day-29 sunset alarm received");
            HijriDay29Notification.handleFire(localized, intent);
        } else if (NotificationScheduler.ACTION_UPDATE_REMINDER.equals(intent.getAction())) {
            AppLogger.i("NotificationReceiver", "Update reminder alarm received");
            NotificationScheduler.handleUpdateReminder(context, intent);
        }
    }
}