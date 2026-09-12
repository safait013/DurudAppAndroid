package com.darood.app;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public class ReminderSchedulerTest {
    static int checks;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    static final String CONFIG = "{\"enabled\":true,\"days\":{\"2\":[{\"h\":20,\"m\":0},{\"h\":21,\"m\":30,\"alertMode\":\"VIBRATE\"},{\"h\":22,\"m\":0,\"alertMode\":\"SILENT\"}]}}";
    public static void main(String[] args) throws Exception {
        Context c = new Context();
        check(NotificationScheduler.saveConfig(c, CONFIG), "save existing array config");
        JSONObject days = NotificationScheduler.getConfig(c).optJSONObject("days");
        check(days.optJSONArray("2").length() == 3, "all times preserved");
        check(days.optJSONArray("2").optJSONObject(0).optString("alertMode").equals("RING"), "legacy default Ring");
        check(days.optJSONArray("2").optJSONObject(1).optString("alertMode").equals("VIBRATE"), "Vibrate stored");
        check(days.optJSONArray("2").optJSONObject(2).optString("alertMode").equals("SILENT"), "Silent stored");
        NotificationScheduler.rescheduleAll(c);
        check(AlarmManager.alarms.size() == 3, "three stable alarms");
        Intent old = AlarmManager.alarms.get(4200).intent;
        NotificationScheduler.rescheduleAll(c);
        check(AlarmManager.alarms.size() == 3, "reschedule does not duplicate");
        for (int i = 0; i < 3; i++) {
            Intent fire = AlarmManager.alarms.get(4200+i).intent;
            long due = System.currentTimeMillis() - 1000;
            fire.putExtra("reminder_trigger", due);
            c.getSharedPreferences("app_settings",0).edit().putLong("reminder_due_2_"+i,due).commit();
            int before = ReminderAlarm.fires;
            new NotificationReceiver().onReceive(c, fire);
            check(ReminderAlarm.fires == before+1, "occurrence delivered once");
            check(ReminderAlarm.lastMode == ReminderAlertMode.values()[i], "each mode delivered independently");
            new NotificationReceiver().onReceive(c, fire);
            check(ReminderAlarm.fires == before+1, "duplicate receiver prevented");
            ReminderAlarm.dismiss(c, ReminderAlarm.active);
            check(NotificationScheduler.isEnabled(c) && AlarmManager.alarms.size() == 3, "dismiss keeps recurrence");
            check(AlarmManager.times.get(4200+i) > System.currentTimeMillis(), "next occurrence remains future");
        }
        NotificationScheduler.saveConfig(c, CONFIG.replace("20", "19"));
        NotificationScheduler.rescheduleAll(c);
        int before = ReminderAlarm.fires;
        NotificationScheduler.handleNotificationFire(c, old);
        check(ReminderAlarm.fires == before, "old revision rejected after editing");
        Intent deleted = AlarmManager.alarms.get(4202).intent;
        NotificationScheduler.saveConfig(c, "{\"enabled\":false,\"days\":{}}");
        NotificationScheduler.rescheduleAll(c);
        NotificationScheduler.handleNotificationFire(c, deleted);
        check(AlarmManager.alarms.isEmpty() && ReminderAlarm.fires == before, "disable/delete cancels alarms");
        c.getSharedPreferences("app_settings",0).edit().putString("notif_config", "{\"enabled\":true,\"days\":{\"6\":{\"h\":20,\"m\":15}}}").commit();
        NotificationScheduler.rescheduleAll(c);
        check(AlarmManager.alarms.containsKey(4600), "legacy object format restored without settings visit");
        check(NotificationScheduler.saveConfig(c, NotificationScheduler.getConfig(c).toString()), "legacy format saves without loss");
        AlarmManager.exact = false;
        NotificationScheduler.rescheduleAll(c);
        check(AlarmManager.inexactCalls > 0, "missing exact permission uses fallback");
        AlarmManager.exact = true; AlarmManager.denyExact = true;
        int inexact = AlarmManager.inexactCalls;
        NotificationScheduler.rescheduleAll(c);
        check(AlarmManager.inexactCalls > inexact, "exact permission revocation race uses fallback");
        AlarmManager.denyExact = false;
        androidx.core.content.ContextCompat.permission = -1;
        NotificationScheduler.rescheduleAll(c);
        check(AlarmManager.alarms.isEmpty() && NotificationScheduler.isEnabled(c), "notification denial preserves saved config");
        androidx.core.content.ContextCompat.permission = 0;
        new BootReceiver().onReceive(c, new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
        check(AlarmManager.alarms.containsKey(4600), "boot restores user reminder");
        check(AlarmManager.times.get(4600) > System.currentTimeMillis(), "boot never rings immediately");
        check(ReminderAlertMode.parse("invalid") == ReminderAlertMode.RING, "unknown mode safe default");
        check(ReminderAlertMode.parse(null) == ReminderAlertMode.RING, "missing mode safe default");
        System.out.println(checks + " reminder scheduler checks passed (Android API doubles).");
        System.exit(0); // Hijri boot recovery owns an executor in this shared test environment.
    }
}
