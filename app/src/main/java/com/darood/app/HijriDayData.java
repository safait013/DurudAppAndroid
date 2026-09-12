package com.darood.app;

import org.json.JSONObject;

/**
 * A single day's location-aware data: the Hijri date associated with one
 * Gregorian date plus the local sunset and timezone context. Instances come from
 * the AlAdhan API, the local cache, or (as a graceful offline fallback) from
 * {@link HijriMath}.
 */
public final class HijriDayData {

    /** Gregorian date this record describes, {@code yyyy-MM-dd}. */
    public final String gregorianDate;
    public final int hijriDay;
    public final int hijriMonth;
    public final int hijriYear;
    /** Sunset wall time {@code HH:mm} in the location's timezone; "" when unknown. */
    public final String sunsetLocal;
    /** IANA timezone id of the location; "" when unknown. */
    public final String timezone;
    public final double latitude;
    public final double longitude;
    /** Epoch millis when this record was fetched/computed. */
    public final long fetchedAt;
    /** True when the sunset is a local approximation instead of AlAdhan data. */
    public final boolean approxSunset;

    public HijriDayData(String gregorianDate, int hijriDay, int hijriMonth, int hijriYear,
                        String sunsetLocal, String timezone, double latitude, double longitude,
                        long fetchedAt, boolean approxSunset) {
        this.gregorianDate = gregorianDate;
        this.hijriDay = hijriDay;
        this.hijriMonth = hijriMonth;
        this.hijriYear = hijriYear;
        this.sunsetLocal = sunsetLocal == null ? "" : sunsetLocal;
        this.timezone = timezone == null ? "" : timezone;
        this.latitude = latitude;
        this.longitude = longitude;
        this.fetchedAt = fetchedAt;
        this.approxSunset = approxSunset;
    }

    /** Advances from an effective date, preserving a valid 30-day/month/year boundary. */
    public HijriDayData nextLocalDay(HijriDayData nextDayContext) {
        // Only the day-29 resolver can shorten a month. Offline uncertainty completes 30 days.
        int day = hijriDay < 30 ? hijriDay + 1 : 1;
        int month = hijriDay < 30 ? hijriMonth : (hijriMonth == 12 ? 1 : hijriMonth + 1);
        int year = hijriYear + (hijriDay == 30 && hijriMonth == 12 ? 1 : 0);
        return new HijriDayData(nextDayContext.gregorianDate, day, month, year,
                nextDayContext.sunsetLocal, nextDayContext.timezone, nextDayContext.latitude,
                nextDayContext.longitude, nextDayContext.fetchedAt, nextDayContext.approxSunset);
    }

    /** @return this record as a JSON object for the persistent cache. */
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("g", gregorianDate);
            o.put("d", hijriDay);
            o.put("m", hijriMonth);
            o.put("y", hijriYear);
            o.put("s", sunsetLocal);
            o.put("tz", timezone);
            o.put("lat", latitude);
            o.put("lng", longitude);
            o.put("at", fetchedAt);
            o.put("approx", approxSunset);
        } catch (Exception e) {
            AppLogger.w("HijriDayData", "Failed to serialize", e);
        }
        return o;
    }

    /** @return the record stored in {@code o}, or null when the payload is invalid. */
    public static HijriDayData fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            String g = o.optString("g", "");
            int d = o.optInt("d", 0);
            int m = o.optInt("m", 0);
            int y = o.optInt("y", 0);
            if (g.length() != 10 || d < 1 || d > 30 || m < 1 || m > 12 || y < 1) {
                return null;
            }
            return new HijriDayData(g, d, m, y, o.optString("s", ""),
                    o.optString("tz", ""), o.optDouble("lat", 0), o.optDouble("lng", 0),
                    o.optLong("at", 0L), o.optBoolean("approx", true));
        } catch (Exception e) {
            AppLogger.w("HijriDayData", "Failed to parse cache entry", e);
            return null;
        }
    }

    /**
     * Graceful offline fallback: computes the tabular Hijri date and an
     * approximate local sunset for the given location without any network.
     */
    public static HijriDayData fallback(String gregorianDate, Double latitude, Double longitude,
                                        long fetchedAt) {
        java.util.Calendar cal = HijriMath.calendarFromIso(gregorianDate);
        if (cal == null) {
            cal = java.util.Calendar.getInstance();
        }
        int[] hijri = HijriMath.hijriFromGregorian(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH));
        double lat = latitude == null ? 0 : latitude;
        double lng = longitude == null ? 0 : longitude;
        long sunset;
        if (latitude != null && longitude != null) {
            sunset = HijriMath.sunsetApproxMillis(lat, lng,
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH));
        } else {
            sunset = HijriMath.sunsetApproxMillisFallback(
                    cal.get(java.util.Calendar.YEAR),
                    cal.get(java.util.Calendar.MONTH),
                    cal.get(java.util.Calendar.DAY_OF_MONTH));
        }
        return new HijriDayData(gregorianDate, hijri[0], hijri[1], hijri[2],
                HijriMath.hhmm(sunset), "", lat, lng, fetchedAt, true);
    }
}
