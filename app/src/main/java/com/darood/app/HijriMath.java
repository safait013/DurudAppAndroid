package com.darood.app;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Pure date/solar mathematics for the location-aware Hijri date feature.
 *
 * <p>This class has <em>no Android dependencies</em> so the critical Islamic-date
 * logic can be validated directly on the JVM. Everything computed here is an
 * approximation used <em>only</em> as a graceful fallback when live/cached
 * AlAdhan data is unavailable (offline first run, location denied). The
 * authoritative Hijri date and local sunset always come from the AlAdhan API;
 * this class keeps the entire app usable worldwide without a network and without
 * hardcoding any city (Bangladesh/Dhaka/UTC is never assumed).
 */
public final class HijriMath {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /** Astronomical sunset zenith: centre of the sun 50 arc-minutes below the horizon. */
    private static final double SUNSET_ZENITH_DEG = 90.833;

    private HijriMath() {
    }

    // ===== Gregorian date helpers (device-local calendar) =====

    /** @return the device-local date as {@code yyyy-MM-dd} for the given epoch millis. */
    public static String dateIso(long epochMillis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochMillis);
        return iso(cal);
    }

    /** @return {@code yyyy-MM-dd} in the device's local time zone. */
    public static String iso(Calendar cal) {
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    /**
     * Parses {@code yyyy-MM-dd} into a device-local {@link Calendar}, or returns
     * {@code null} when the string is not a valid calendar date.
     */
    public static Calendar calendarFromIso(String isoDate) {
        if (isoDate == null || isoDate.length() != 10 || isoDate.charAt(4) != '-'
                || isoDate.charAt(7) != '-') {
            return null;
        }
        try {
            int year = Integer.parseInt(isoDate.substring(0, 4));
            int month = Integer.parseInt(isoDate.substring(5, 7));
            int day = Integer.parseInt(isoDate.substring(8, 10));
            if (year < 1900 || year > 2200 || month < 1 || month > 12 || day < 1 || day > 31) {
                return null;
            }
            Calendar cal = Calendar.getInstance();
            cal.clear();
            cal.set(year, month - 1, day, 0, 0, 0);
            // Re-read to reject invalid rollovers (e.g. 2026-02-31 becomes March).
            if (cal.get(Calendar.YEAR) != year || cal.get(Calendar.MONTH) != month - 1
                    || cal.get(Calendar.DAY_OF_MONTH) != day) {
                return null;
            }
            return cal;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** @return the date {@code days} days before/after {@code isoDate}, or null for invalid input. */
    public static String addDays(String isoDate, int days) {
        Calendar cal = calendarFromIso(isoDate);
        if (cal == null) {
            return null;
        }
        cal.add(Calendar.DAY_OF_MONTH, days);
        return iso(cal);
    }

    /** Formats epoch millis as {@code HH:mm} in the device's local time zone. */
    public static String hhmm(long epochMillis) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(epochMillis);
        return String.format(java.util.Locale.US, "%02d:%02d",
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE));
    }

    // ===== Approximate local sunset (fallback only) =====

    /**
     * Approximates the local sunset instant for the given location/date as epoch
     * millis (device-local clock). Accuracy is typically within ~1–3 minutes.
     * Used only when no live/cached AlAdhan sunset is available.
     *
     * @param lat   latitude in degrees
     * @param lng   longitude in degrees
     * @param year  Gregorian year
     * @param month 0-based calendar month (Calendar.JANUARY = 0)
     * @param day   day of month
     */
    public static long sunsetApproxMillis(double lat, double lng, int year, int month, int day) {
        Calendar utcMidnight = Calendar.getInstance(UTC);
        utcMidnight.clear();
        utcMidnight.set(year, month, day, 0, 0, 0);
        long midnightUtc = utcMidnight.getTimeInMillis();

        int dayOfYear = utcMidnight.get(Calendar.DAY_OF_YEAR);
        double n = dayOfYear;
        double bRad = 2.0 * Math.PI * (n - 81.0) / 365.0;
        double equationOfTimeMin = 9.87 * Math.sin(2.0 * bRad)
                - 7.53 * Math.cos(bRad) - 1.5 * Math.sin(bRad);
        double declinationRad = Math.toRadians(23.44) * Math.sin(bRad);
        double latRad = Math.toRadians(lat);
        double cosHourAngle = (Math.cos(Math.toRadians(SUNSET_ZENITH_DEG))
                - Math.sin(latRad) * Math.sin(declinationRad))
                / (Math.cos(latRad) * Math.cos(declinationRad));
        if (cosHourAngle < -1.0) {
            cosHourAngle = -1.0;
        } else if (cosHourAngle > 1.0) {
            cosHourAngle = 1.0;
        }
        double hourAngleDeg = Math.toDegrees(Math.acos(cosHourAngle));
        double solarHour = 12.0 + hourAngleDeg / 15.0 - equationOfTimeMin / 60.0;
        // UTC instant of sunset; wall-clock formatting applies the device time zone.
        double utcHour = solarHour - lng / 15.0;
        return midnightUtc + (long) (utcHour * 3_600_000L);
    }

    /**
     * Last-resort approximate sunset when even the location is unknown: uses the
     * device time zone's standard meridian as longitude and the equator as
     * latitude. Correct within tens of minutes for most inhabited latitudes; the
     * app labels this source as "approximate".
     */
    public static long sunsetApproxMillisFallback(int year, int month, int day) {
        double utcOffsetHours = TimeZone.getDefault().getRawOffset() / 3_600_000L;
        double lng = utcOffsetHours * 15.0;
        return sunsetApproxMillis(0.0, lng, year, month, day);
    }

    // ===== Arithmetic Hijri (tabular) fallback =====

    /**
     * Converts a proleptic Gregorian date to the Hijri date using the standard
     * arithmetic (tabular) Islamic calendar — the same family of algorithm the
     * AlAdhan "Hijri (Standard)" / Umm al-Qura-style libraries use. It is a
     * <em>fallback only</em> for full offline operation.
     *
     * @param year  Gregorian year
     * @param month 1-based Gregorian month
     * @param day   1-based Gregorian day
     * @return {@code {hijriDay, hijriMonth, hijriYear}} (all 1-based)
     */
    public static int[] hijriFromGregorian(int year, int month, int day) {
        long jd = julianDayFromGregorian(year, month, day);

        long l = jd - 1948440 + 10632;
        long n = Math.floorDiv(l - 1, 10631);
        l = l - 10631 * n + 354;
        long j = Math.floorDiv(10985 - l, 5316) * Math.floorDiv(50 * l, 17719)
                + Math.floorDiv(l, 5670) * Math.floorDiv(43 * l, 15238);
        l = l - Math.floorDiv(30 - j, 15) * Math.floorDiv(17719 * j, 50)
                - Math.floorDiv(j, 16) * Math.floorDiv(15238 * j, 43) + 29;
        long monthH = Math.floorDiv(24 * l, 709);
        long dayH = l - Math.floorDiv(709 * monthH, 24);
        long yearH = 30 * n + j - 30;
        return new int[]{(int) dayH, (int) monthH, (int) yearH};
    }

    /** Julian Day Number for a proleptic Gregorian date (month is 1-based). */
    static long julianDayFromGregorian(int year, int month, int day) {
        long a = (14 - month) / 12L;
        long y = year + 4800 - a;
        long m = month + 12 * a - 3;
        return day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045;
    }
}