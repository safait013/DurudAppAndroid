package com.darood.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;
import java.util.TimeZone;

/**
 * Small offline cache for AlAdhan day data, persisted as JSON in the same
 * SharedPreferences file every other setting uses ("app_settings"). It keeps
 * the Gregorian date, the Hijri date, the local sunset, the timezone/location
 * context and the fetch timestamp so the Home screen can show a correct date
 * entirely offline.
 *
 * <p>No second database is created: manual overrides live in Room
 * ({@code hijri_overrides} table), automatic data lives here.
 */
public final class HijriCache {

    public static final String PREFS_FILE = AppSettings.PREFS_FILE;
    private static final String KEY_CACHE = "hijri_cache_v2";
    private static final int MAX_ENTRIES = 45;

    private HijriCache() {
    }

    // ===== Whole-document IO =====

    private static JSONObject read(Context context) {
        try {
            String raw = prefs(context).getString(KEY_CACHE, null);
            if (raw == null) {
                return new JSONObject();
            }
            JSONObject o = new JSONObject(raw);
            if (!o.has("days")) {
                o.put("days", new JSONObject());
            }
            return o;
        } catch (Exception e) {
            AppLogger.w("HijriCache", "Cache read failed; starting empty", e);
            return new JSONObject();
        }
    }

    private static void write(Context context, JSONObject cache) {
        try {
            prune(cache);
            prefs(context).edit().putString(KEY_CACHE, cache.toString()).apply();
        } catch (Exception e) {
            AppLogger.w("HijriCache", "Cache write failed", e);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    // ===== Location context =====

    /** @return the last known location {@code {lat, lng}} or null when never set. */
    public static double[] getLastLocation(Context context) {
        try {
            JSONObject cache = read(context);
            if (!cache.has("lastLat") || !cache.has("lastLng")) {
                return null;
            }
            return new double[]{cache.optDouble("lastLat", 0), cache.optDouble("lastLng", 0)};
        } catch (Exception e) {
            AppLogger.w("HijriCache", "getLastLocation failed", e);
            return null;
        }
    }

    /** Persists the location (rounded to ~11 m so the logged footprint stays coarse). */
    public static void setLastLocation(Context context, double lat, double lng) {
        try {
            JSONObject cache = read(context);
            cache.put("lastLat", Math.round(lat * 10000.0) / 10000.0);
            cache.put("lastLng", Math.round(lng * 10000.0) / 10000.0);
            write(context, cache);
        } catch (Exception e) {
            AppLogger.w("HijriCache", "setLastLocation failed", e);
        }
    }

    /** @return the last known IANA timezone id, or "" when unknown. */
    public static String getLastTz(Context context) {
        try {
            return read(context).optString("lastTz", "");
        } catch (Exception e) {
            return "";
        }
    }

    public static void setLastTz(Context context, String timezone) {
        try {
            JSONObject cache = read(context);
            cache.put("lastTz", timezone == null ? "" : timezone);
            write(context, cache);
        } catch (Exception e) {
            AppLogger.w("HijriCache", "setLastTz failed", e);
        }
    }

    /**
     * @return true when the cached location context matches the supplied location
     * (both rounded to ~111 km buckets). A null/unknown location always matches so
     * cached data is still usable before the first fix.
     */
    public static boolean locationMatches(Context context, double[] location) {
        try {
            JSONObject cache = read(context);
            if (!cache.has("lastLat") || !cache.has("lastLng")) {
                return true;
            }
            if (location == null) {
                return true;
            }
            long cachedLat = Math.round(cache.optDouble("lastLat", 0) * 10);
            long cachedLng = Math.round(cache.optDouble("lastLng", 0) * 10);
            return cachedLat == Math.round(location[0] * 10)
                    && cachedLng == Math.round(location[1] * 10);
        } catch (Exception e) {
            return true;
        }
    }

    // ===== Day data =====

    /** @return the cached data for a Gregorian date, or null when absent/invalid. */
    public static HijriDayData getDay(Context context, String gregorianDate) {
        try {
            JSONObject cache = read(context);
            JSONObject days = cache.optJSONObject("days");
            if (days == null || !days.has(gregorianDate)) {
                return null;
            }
            return HijriDayData.fromJson(days.optJSONObject(gregorianDate));
        } catch (Exception e) {
            AppLogger.w("HijriCache", "getDay failed", e);
            return null;
        }
    }

    public static void putDay(Context context, HijriDayData data) {
        if (data == null || data.gregorianDate == null) {
            return;
        }
        try {
            JSONObject cache = read(context);
            JSONObject days = cache.optJSONObject("days");
            days.put(data.gregorianDate, data.toJson());
            cache.put("days", days);
            if (data.timezone != null && !data.timezone.isEmpty()) {
                cache.put("lastTz", data.timezone);
            }
            if (data.latitude != 0 || data.longitude != 0) {
                cache.put("lastLat", Math.round(data.latitude * 10000.0) / 10000.0);
                cache.put("lastLng", Math.round(data.longitude * 10000.0) / 10000.0);
            }
            write(context, cache);
            AppLogger.i("HijriCache", "Cached day " + data.gregorianDate
                    + " hijri=" + data.hijriDay + "-" + data.hijriMonth + "-" + data.hijriYear);
        } catch (Exception e) {
            AppLogger.w("HijriCache", "putDay failed", e);
        }
    }

    /**
     * Converts a cached sunset (location wall time + location timezone) into an
     * absolute epoch millis usable for comparing against "now" on any device.
     *
     * @return sunset epoch millis, or null when no cached sunset exists.
     */
    public static Long sunsetMillis(Context context, String gregorianDate) {
        HijriDayData day = getDay(context, gregorianDate);
        if (day == null || day.sunsetLocal.isEmpty()) {
            return null;
        }
        Calendar gregorian = HijriMath.calendarFromIso(gregorianDate);
        if (gregorian == null) {
            return null;
        }
        String[] hm = day.sunsetLocal.split(":");
        try {
            int hour = Integer.parseInt(hm[0]);
            int minute = Integer.parseInt(hm[1]);
            String tzId = (day.timezone == null || day.timezone.isEmpty())
                    ? TimeZone.getDefault().getID() : day.timezone;
            Calendar local = Calendar.getInstance(TimeZone.getTimeZone(tzId));
            local.set(gregorian.get(Calendar.YEAR), gregorian.get(Calendar.MONTH),
                    gregorian.get(Calendar.DAY_OF_MONTH), hour, minute, 0);
            local.set(Calendar.MILLISECOND, 0);
            return local.getTimeInMillis();
        } catch (Exception e) {
            AppLogger.w("HijriCache", "sunsetMillis failed", e);
            return null;
        }
    }

    /** Drops the oldest day entries so the cache file never grows unboundedly. */
    private static void prune(JSONObject cache) {
        try {
            JSONObject days = cache.optJSONObject("days");
            if (days == null || days.length() <= MAX_ENTRIES) {
                return;
            }
            String oldest = null;
            long oldestAt = Long.MAX_VALUE;
            Iterator<String> it = days.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject entry = days.optJSONObject(key);
                long at = entry == null ? Long.MAX_VALUE : entry.optLong("at", Long.MAX_VALUE);
                if (at < oldestAt) {
                    oldestAt = at;
                    oldest = key;
                }
            }
            if (oldest != null) {
                days.remove(oldest);
            }
        } catch (Exception e) {
            AppLogger.w("HijriCache", "prune failed", e);
        }
        }
}