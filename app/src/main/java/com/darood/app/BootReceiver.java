package com.darood.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reschedules all saved notification alarms after the device reboots (alarms do
 * not survive reboot) and after an app update (PendingIntents are invalidated).
 * The schedule is read back from persisted prefs.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            AppLogger.w("BootReceiver", "Received null intent or action");
            return;
        }
        String action = intent.getAction();
        if (android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            NotificationScheduler.rescheduleAll(LanguageManager.applyLanguage(context));
            final PendingResult pending = goAsync();
            try {
                HijriCoordinator.get().onBootCompleted(context, pending::finish);
            } catch (Throwable t) {
                pending.finish();
                AppLogger.e("BootReceiver", "Hijri clock/access recovery failed", t);
            }
            return;
        }
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppLogger.i("BootReceiver", "Boot/update received; rescheduling notifications");
            Context localized = LanguageManager.applyLanguage(context);
            // A reboot/update restores future alarms, never an old undismissed occurrence.
            ReminderAlarm.dismiss(localized, null);
            FridayReminderScheduler.restoreAfterBoot(localized);
            NotificationScheduler.rescheduleAll(localized);
            NotificationScheduler.restoreUpdateReminder(context);
            // Revalidate month-end events off the receiver thread; Room overrides win.
            final PendingResult pending = goAsync();
            try {
                HijriCoordinator.get().onBootCompleted(localized, pending::finish);
            } catch (Throwable t) {
                pending.finish();
                AppLogger.e("BootReceiver", "Hijri recovery failed", t);
            }
        }
    }
}
