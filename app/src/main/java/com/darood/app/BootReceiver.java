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
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AppLogger.i("BootReceiver", "Boot/update received; rescheduling notifications");
            Context localized = LanguageManager.applyLanguage(context);
            NotificationScheduler.rescheduleAll(localized);
            NotificationScheduler.restoreUpdateReminder(context);
            // The sunset alarm is cancelled by the system on reboot, and
            // PendingIntents are invalidated by an app update: re-arm it so the
            // location-aware Hijri date keeps rolling without user action.
            HijriCoordinator.get().onBootCompleted(localized);
        }
    }
}