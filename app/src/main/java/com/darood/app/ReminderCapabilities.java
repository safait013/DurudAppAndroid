package com.darood.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import org.json.JSONObject;

/** Read-only capability snapshot shared by setup, upgrade guidance and reminder settings. */
final class ReminderCapabilities {
    static boolean exact(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        return am != null && (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms());
    }
    static boolean highChannel(Context c) {
        if (Build.VERSION.SDK_INT < 26) return true;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = nm == null ? null : nm.getNotificationChannel(ReminderAlarm.CHANNEL);
        return channel != null && channel.getImportance() >= NotificationManager.IMPORTANCE_HIGH;
    }
    static boolean ready(Context c) {
        ReminderAlarm.createChannel(c);
        return ReminderAlarm.canNotify(c) && highChannel(c) && exact(c) && ReminderAlarm.canFullScreen(c);
    }
    static JSONObject snapshot(Context c) {
        ReminderAlarm.createChannel(c);
        JSONObject result = new JSONObject();
        try {
            result.put("notifications", ReminderAlarm.canNotify(c) && highChannel(c));
            result.put("exact", exact(c));
            result.put("fullScreen", ReminderAlarm.canFullScreen(c));
        } catch (Exception e) { AppLogger.w("ReminderAlarm", "Capability snapshot failed", e); }
        AppLogger.i("ReminderAlarm", "Capability check " + result);
        return result;
    }
}
