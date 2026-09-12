package com.darood.app;

import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import java.util.Calendar;
import java.util.TimeZone;

/** Actual Friday scheduler/cache/solar calculation, deterministic dates, Android API doubles. */
public class FridayReminderTest {
    static int checks;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
    static long instant(int y, int m, int d, int h, int minute) {
        Calendar c = Calendar.getInstance(); c.clear(); c.set(y, m-1, d, h, minute); return c.getTimeInMillis();
    }
    public static void main(String[] args) {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Dhaka"));
            Context c = new Context();
            long morning = instant(2026, 9, 11, 9, 0);
            check(FridayReminderScheduler.isEnabled(c), "new install defaults enabled");
            FridayReminderScheduler.schedule(c, morning);
            check(!AlarmManager.alarms.containsKey(6300), "no invented location or hardcoded sunset");
            HijriCache.setLastLocation(c, 23.8103, 90.4125);
            HijriDayData cache = new HijriDayData("2026-09-11", 29, 3, 1448, "18:05", "Asia/Dhaka", 23.8103, 90.4125, morning, false);
            HijriCache.putDay(c, cache);
            FridayReminderScheduler.Occurrence next = FridayReminderScheduler.next(c, morning);
            check(next != null && next.trigger == instant(2026,9,11,17,50), "cached 18:05 sunset yields exactly 17:50");
            check(next.sunset-next.trigger==900000 && !next.approximate, "exact 15-minute offset and cached source");
            FridayReminderScheduler.schedule(c, morning);
            for(int i=0;i<5;i++) FridayReminderScheduler.schedule(c, morning);
            check(AlarmManager.alarms.size()==1, "repeated scheduling keeps one stable pending intent");
            check(AlarmManager.times.get(6300)==next.trigger, "stable upcoming occurrence retained");
            FridayReminderScheduler.schedule(c, next.trigger+1000);
            check(AlarmManager.times.get(6300)==next.trigger, "cache/resume does not cancel an in-flight delivery");
            next = FridayReminderScheduler.next(c, instant(2026,9,11,18,0));
            check(next.date.equals("2026-09-18"), "passed Friday rolls to next Friday");
            check(next.approximate && next.sunset==HijriMath.sunsetApproxMillis(23.8103,90.4125,2026,8,18), "reuses existing offline solar math with known location");
            check(next.trigger!=instant(2026,9,18,17,50), "next Friday recomputes sunset instead of fixed weekly time");
            FridayReminderScheduler.setEnabled(c,false);
            FridayReminderScheduler.initialize(c);
            check(!FridayReminderScheduler.isEnabled(c)&&!AlarmManager.alarms.containsKey(6300), "disabled survives default initialization/relaunch");
            FridayReminderScheduler.setEnabled(c,true);
            androidx.core.content.ContextCompat.permission=-1;
            FridayReminderScheduler.schedule(c,morning);
            check(!AlarmManager.alarms.containsKey(6300)&&FridayReminderScheduler.isEnabled(c), "denial defers without erasing preference");
            androidx.core.content.ContextCompat.permission=0;
            AlarmManager.exact=false;
            int beforeInexact=AlarmManager.inexactCalls;
            FridayReminderScheduler.schedule(c,morning);
            check(AlarmManager.inexactCalls>beforeInexact, "no exact access legal delayed fallback");
            AlarmManager.exact=true; AlarmManager.denyExact=true;
            beforeInexact=AlarmManager.inexactCalls;
            FridayReminderScheduler.schedule(c,morning);
            check(AlarmManager.inexactCalls>beforeInexact, "permission revocation race handled");
            AlarmManager.denyExact=false;
            long due=System.currentTimeMillis()-1000;
            c.getSharedPreferences("app_settings",0).edit().putLong("friday_reminder_due",due)
                    .putLong("friday_reminder_sunset",due+900000).putString("friday_reminder_date","2026-09-11").commit();
            Intent fire=new Intent().setAction(FridayReminderScheduler.ACTION).putExtra("trigger",due).putExtra("date","2026-09-11");
            int before=ReminderAlarm.fires;
            new NotificationReceiver().onReceive(c,fire);
            check(ReminderAlarm.fires==before+1&&ReminderAlarm.lastMode==ReminderAlertMode.RING, "Friday receiver routes to shared Ring controller");
            new NotificationReceiver().onReceive(c,fire);
            check(ReminderAlarm.fires==before+1, "duplicate occurrence ignored");
            ReminderAlarm.dismiss(c,ReminderAlarm.active);
            check(FridayReminderScheduler.isEnabled(c)&&AlarmManager.times.get(6300)>System.currentTimeMillis(), "dismiss keeps next Friday");
            new BootReceiver().onReceive(c,new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
            check(AlarmManager.alarms.containsKey(6300)&&ReminderAlarm.fires==before+1, "boot restores future only");
            FridayReminderScheduler.setEnabled(c,false);
            new BootReceiver().onReceive(c,new Intent().setAction(Intent.ACTION_BOOT_COMPLETED));
            check(!AlarmManager.alarms.containsKey(6300), "boot preserves explicit Friday disable");
            Context polar = new Context(); HijriCache.setLastLocation(polar,80,15);
            check(FridayReminderScheduler.next(polar,morning)==null, "no fabricated polar sunset from clamped approximation");
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
            Context ny = new Context(); HijriCache.setLastLocation(ny,40.7128,-74.0060);
            next=FridayReminderScheduler.next(ny,instant(2026,10,30,23,0));
            check(next.date.equals("2026-11-06"), "next Friday survives DST boundary");
            check(next.sunset-next.trigger==900000, "DST does not alter offset");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"));
            Context nz = new Context(); HijriCache.setLastLocation(nz,-36.8485,174.7633);
            next=FridayReminderScheduler.next(nz,instant(2026,9,11,9,0));
            check(next!=null&&next.date.equals("2026-09-11"), "positive timezone uses local Friday");
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"));
            Context samoa = new Context(); HijriCache.setLastLocation(samoa,-13.8333,-171.75);
            next=FridayReminderScheduler.next(samoa,instant(2026,9,11,9,0));
            check(next!=null&&next.date.equals("2026-09-11")&&next.sunset-next.trigger==900000,
                    "international date line selects the matching UTC solar date for local Friday");
            System.out.println(checks+" Friday reminder checks passed (Android API doubles).");
        } finally { TimeZone.setDefault(original); }
        System.exit(0);
    }
}
