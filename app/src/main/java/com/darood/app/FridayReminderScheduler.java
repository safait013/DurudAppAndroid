package com.darood.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** One built-in definition, one upcoming local-Friday occurrence. No network or independent solar math. */
final class FridayReminderScheduler {
    static final String ACTION = "com.darood.app.FRIDAY_SUNSET_REMINDER";
    static final int REQUEST_CODE = 6300;
    static final long OFFSET_MS = 15 * 60_000L;
    private static final String ENABLED = "friday_reminder_enabled";
    private static final String INITIALIZED = "friday_reminder_initialized";
    private static final String DUE = "friday_reminder_due";
    private static final String SUNSET = "friday_reminder_sunset";
    private static final String DATE = "friday_reminder_date";
    private static final String HANDLED = "friday_reminder_handled_date";

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
    }

    static void initialize(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(INITIALIZED, false)) {
            // Preserve any explicit choice even if upgrading an interrupted initialization.
            p.edit().putBoolean(ENABLED, p.getBoolean(ENABLED, true)).putBoolean(INITIALIZED, true).commit();
            AppLogger.i("FridayReminder", "Built-in Friday reminder initialized once");
        }
    }

    static boolean isEnabled(Context c) { initialize(c); return prefs(c).getBoolean(ENABLED, true); }

    static void setEnabled(Context c, boolean enabled) {
        prefs(c).edit().putBoolean(INITIALIZED, true).putBoolean(ENABLED, enabled).commit();
        AppLogger.i("FridayReminder", "Enabled=" + enabled);
        if (!enabled) {
            String active = ReminderAlarm.active(c);
            if (active != null && active.startsWith("friday:")) ReminderAlarm.dismiss(c, active);
        }
        reschedule(c);
    }

    static final class Occurrence {
        final String date;
        final long sunset, trigger;
        final boolean approximate;
        Occurrence(String date, long sunset, boolean approximate) {
            this.date = date; this.sunset = sunset; this.trigger = sunset - OFFSET_MS;
            this.approximate = approximate;
        }
    }

    static String date(Calendar day) {
        return String.format(Locale.US, "%04d-%02d-%02d", day.get(Calendar.YEAR),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH));
    }

    /** Uses the existing cached local sunset, or its existing offline calculation with known coordinates. */
    static Occurrence next(Context c, long now) {
        double[] location = HijriCache.getLastLocation(c);
        if (location == null || Double.isNaN(location[0]) || Double.isNaN(location[1])
                || Math.abs(location[0]) > 90 || Math.abs(location[1]) > 180) return null;
        Calendar friday = Calendar.getInstance();
        friday.setTimeInMillis(now);
        friday.add(Calendar.DAY_OF_YEAR, (Calendar.FRIDAY - friday.get(Calendar.DAY_OF_WEEK) + 7) % 7);
        for (int i = 0; i < 2; i++, friday.add(Calendar.DAY_OF_YEAR, 7)) {
            String date = date(friday);
            if (date.equals(prefs(c).getString(HANDLED, ""))) continue;
            HijriDayData cached = HijriCache.getDay(c, date);
            Long sunset = null;
            boolean approximate = true;
            if (cached != null && (!cached.approxSunset || Math.abs(location[0]) < 66)
                    && Math.abs(cached.latitude - location[0]) < 0.1
                    && Math.abs(cached.longitude - location[1]) < 0.1
                    && cached.timezone.equals(TimeZone.getDefault().getID())
                    && cached.sunsetLocal.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) {
                sunset = HijriCache.sunsetMillis(c, date);
                approximate = cached.approxSunset;
            }
            if (sunset == null) {
                // Existing approximation clamps polar no-sunset conditions; do not invent an alarm there.
                if (Math.abs(location[0]) >= 66) return null;
                // The existing formula uses a UTC date. Near the international date line the
                // matching local Friday can belong to a neighbouring UTC solar date.
                for (int offset : new int[]{0, -1, 1}) {
                    Calendar solarDate = (Calendar) friday.clone();
                    solarDate.add(Calendar.DAY_OF_YEAR, offset);
                    long calculated = HijriMath.sunsetApproxMillis(location[0], location[1], solarDate.get(Calendar.YEAR),
                            solarDate.get(Calendar.MONTH), solarDate.get(Calendar.DAY_OF_MONTH));
                    Calendar local = Calendar.getInstance();
                    local.setTimeInMillis(calculated);
                    if (date.equals(date(local))) { sunset = calculated; break; }
                }
            }
            if (sunset == null) return null;
            Calendar localSunset = Calendar.getInstance();
            localSunset.setTimeInMillis(sunset);
            if (!date.equals(date(localSunset))) return null; // incompatible timezone/location context
            Occurrence result = new Occurrence(date, sunset, approximate);
            if (result.trigger > now) return result;
        }
        return null;
    }

    private static PendingIntent pending(Context c, Intent intent, int flags) {
        return PendingIntent.getBroadcast(c, REQUEST_CODE, intent,
                flags | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    static void reschedule(Context c) { schedule(c, System.currentTimeMillis()); }

    static void restoreAfterBoot(Context c) {
        // Alarms do not survive reboot. Do not preserve an already-due pending occurrence.
        prefs(c).edit().remove(DUE).remove(SUNSET).remove(DATE).commit();
    }

    static void schedule(Context c, long now) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long due = prefs(c).getLong(DUE, 0);
        if (isEnabled(c) && NotificationScheduler.hasNotificationPermission(c) && due > 0 && due <= now
                && now < prefs(c).getLong(SUNSET, 0)) {
            Occurrence awaiting = next(c, due - 1);
            if (awaiting != null && awaiting.trigger == due
                    && awaiting.date.equals(prefs(c).getString(DATE, ""))) {
                // Cache refresh / app resume must not replace an in-flight or OS-delayed occurrence.
                AppLogger.i("FridayReminder", "Keeping pending receiver delivery friday:" + awaiting.date + ":" + due);
                return;
            }
        }
        Occurrence next = isEnabled(c) && NotificationScheduler.hasNotificationPermission(c) ? next(c, now) : null;
        if (next == null) {
            PendingIntent old = pending(c, new Intent(c, NotificationReceiver.class).setAction(ACTION), PendingIntent.FLAG_NO_CREATE);
            if (old != null) { am.cancel(old); old.cancel(); }
            prefs(c).edit().remove(DUE).remove(SUNSET).remove(DATE).commit();
            AppLogger.i("FridayReminder", "Scheduling deferred: disabled, permission unavailable or no reliable local sunset");
            return;
        }
        Intent intent = new Intent(c, NotificationReceiver.class).setAction(ACTION)
                .putExtra("trigger", next.trigger).putExtra("date", next.date);
        PendingIntent pi = pending(c, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        if (!prefs(c).edit().putLong(DUE, next.trigger).putLong(SUNSET, next.sunset).putString(DATE, next.date).commit()) {
            am.cancel(pi); return;
        }
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.trigger, pi);
                else am.setExact(AlarmManager.RTC_WAKEUP, next.trigger, pi);
            } else inexact(am, next.trigger, pi);
        } catch (SecurityException e) {
            AppLogger.w("FridayReminder", "Exact access revoked; delayed fallback", e);
            inexact(am, next.trigger, pi);
        }
        AppLogger.i("FridayReminder", "Scheduled friday:" + next.date + ":" + next.trigger
                + " sunset=" + next.sunset + " timezone=" + TimeZone.getDefault().getID()
                + " approximate=" + next.approximate + " offsetMinutes=15 mode=RING");
    }

    private static void inexact(AlarmManager am, long trigger, PendingIntent pi) {
        AppLogger.w("FridayReminder", "Exact capability unavailable; Friday delivery may be delayed");
        if (Build.VERSION.SDK_INT >= 23) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        else am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
    }

    static void handleFire(Context c, Intent intent) {
        long now = System.currentTimeMillis();
        long trigger = intent.getLongExtra("trigger", 0);
        String date = intent.getStringExtra("date");
        SharedPreferences p = prefs(c);
        AppLogger.i("FridayReminder", "Receiver fired friday:" + date + ":" + trigger);
        if (!isEnabled(c) || date == null || trigger <= 0 || trigger > now
                || trigger != p.getLong(DUE, 0) || !date.equals(p.getString(DATE, ""))
                || date.equals(p.getString(HANDLED, ""))) {
            AppLogger.i("FridayReminder", "Duplicate/stale/disabled occurrence blocked");
            return;
        }
        long sunset = p.getLong(SUNSET, 0);
        if (!p.edit().putString(HANDLED, date).commit()) return;
        reschedule(c); // Future recurrence is independent of Dismiss and the active controller.
        if (now >= sunset) {
            AppLogger.w("FridayReminder", "Delivery arrived after sunset; skipped stale Friday alarm");
            return;
        }
        ReminderAlarm.fire(c, "friday:" + date + ":" + trigger, ReminderAlertMode.RING);
    }
}
