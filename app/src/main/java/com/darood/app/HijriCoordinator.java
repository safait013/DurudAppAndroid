package com.darood.app;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central coordinator for the location-aware Hijri date feature.
 *
 * <p>Owns the full pipeline:
 * <ol>
 *   <li>Current local Gregorian date/time determines "today".</li>
 *   <li>Today's local sunset decides which Gregorian day's Hijri date applies:
 *       before sunset → today, at/after sunset → tomorrow. The Hijri value is
 *       always <em>looked up</em> for the selected Gregorian date (never
 *       incremented manually), so 29→1 and year transitions come from real data.</li>
 *   <li>Resolution priority: manual override → AlAdhan API → cached AlAdhan data
 *       → arithmetic fallback.</li>
 *   <li>The next-sunset AlarmManager alarm is re-scheduled after every sunset.</li>
 * </ol>
 *
 * <p>All heavy work runs on one background executor; the Home screen snapshot is
 * always available synchronously from manual/cache/fallback sources.
 */
public final class HijriCoordinator {

    private static final String TAG = "HijriCoordinator";
    private static final long LOCATION_TIMEOUT_MS = 8_000L;
    private static final int MIN_HIJRI_YEAR = 1200;
    private static final int MAX_HIJRI_YEAR = 1600;

    /** Receives asynchronous updates; implemented by {@link MainActivity}. */
    public interface HijriRefreshListener {
        void onHijriDateUpdated(String snapshotJson);

        void onHijriOverridesLoaded(String overridesJson);
    }

    private static volatile HijriCoordinator instance;
    private static volatile WeakReference<HijriRefreshListener> foregroundListener;

    private final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final AtomicBoolean REFRESHING = new AtomicBoolean(false);

    /** Cached result of the last successful resolution, refreshed on foreground. */
    private volatile String applicableDate;
    private volatile HijriDayData resolvedData;
    private volatile String resolvedSource;

    private HijriCoordinator() {
    }

    public static HijriCoordinator get() {
        if (instance == null) {
            synchronized (HijriCoordinator.class) {
                if (instance == null) {
                    instance = new HijriCoordinator();
                }
            }
        }
        return instance;
    }

    /** Registers the foreground UI to receive pushed updates (set in onResume). */
    public static void setForegroundListener(HijriRefreshListener listener) {
        foregroundListener =
                listener == null ? null : new WeakReference<>(listener);
    }

    /** True when the app holds at least one location runtime permission. */
    public static boolean hasLocationPermission(Context context) {
        return HijriLocationHelper.hasPermission(context);
    }

    // ===== Snapshot for the Home screen (synchronous, never blocks) =====

    /**
     * Builds the snapshot JSON immediately from manual/cache/fallback sources.
     * Returns "{}" if even the fallback fails (never throws).
     */
    public String snapshotJson(Context context) {
        try {
            Resolved resolved = computeCore(context, HijriCache.getLastLocation(context));
            resolvedData = resolved.data;
            resolvedSource = resolved.source;
            applicableDate = resolved.applicable;
            return resolved.toSnapshotJson(context).toString();
        } catch (Throwable t) {
            AppLogger.e(TAG, "snapshotJson failed", t);
            return "{}";
        }
    }

    // ===== Full asynchronous refresh (location + API + cache + schedule) =====

    /** Kicks off the full foreground refresh on the background executor (deduped). */
    public void refresh(Context context) {
        if (!REFRESHING.compareAndSet(false, true)) {
            AppLogger.d(TAG, "Refresh already in progress; skipping duplicate");
            return;
        }
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                doRefresh(app);
            } catch (Throwable t) {
                AppLogger.e(TAG, "Refresh crashed", t);
            } finally {
                REFRESHING.set(false);
            }
        });
    }

    private void doRefresh(Context context) {
        double[] location = HijriLocationHelper.getLocation(context, LOCATION_TIMEOUT_MS);
        if (location != null) {
            HijriCache.setLastLocation(context, location[0], location[1]);
        }
        if (location == null) {
            location = HijriCache.getLastLocation(context);
        }

        long now = System.currentTimeMillis();
        String today = HijriMath.dateIso(now);
        long todaySunset = sunsetForDate(context, today, location);
        String applicable = (now < todaySunset) ? today : HijriMath.addDays(today, 1);
        AppLogger.i(TAG, "Today=" + today + " applicable=" + applicable
                + " sunsetToday=" + HijriMath.hhmm(todaySunset));

        Resolved resolved = resolveApplicable(context, applicable, location, true);
        resolvedData = resolved.data;
        resolvedSource = resolved.source;
        applicableDate = applicable;
        syncHijriDay29Notification(context, resolved, now);
        HijriNewMonthNotification.notifyIfNewMonth(context, resolved.data, resolved.source);

        // Prefetch the following day so the after-sunset transition works offline.
        if (!"manual".equals(resolved.source) && location != null) {
            prefetchNextDay(context, applicable, location);
        }

        scheduleNextSunset(context, applicable, location, now);
        pushForeground(snapshotJson(context));
    }

    /** Lightweight recompute used when the sunset alarm fires (receiver context). */
    public void onSunsetFired(Context context) {
        try {
            // Deliver the outgoing day before this receiver advances the effective date.
            HijriDay29Notification.fireIfDue(context, HijriMath.dateIso(System.currentTimeMillis()));
            double[] location = HijriCache.getLastLocation(context);
            Resolved resolved = computeCore(context, location);
            resolvedData = resolved.data;
            resolvedSource = resolved.source;
            applicableDate = resolved.applicable;
            syncHijriDay29Notification(context, resolved, System.currentTimeMillis());
            HijriNewMonthNotification.notifyIfNewMonth(context, resolved.data, resolved.source);
            scheduleNextSunset(context, resolved.applicable, location,
                    System.currentTimeMillis());
            pushForeground(snapshotJson(context));
            AppLogger.i(TAG, "Sunset transition applied; applicable=" + resolved.applicable);
        } catch (Throwable t) {
            AppLogger.e(TAG, "onSunsetFired failed", t);
        }
        // Also try to update the new day's data from the API (best effort).
        refresh(context);
    }

    /** Re-arms the sunset alarm after reboot/app-update (no UI involved). */
    public void onBootCompleted(Context context) {
        try {
            double[] location = HijriCache.getLastLocation(context);
            Resolved resolved = computeCore(context, location);
            syncHijriDay29Notification(context, resolved, System.currentTimeMillis());
            HijriNewMonthNotification.notifyIfNewMonth(context, resolved.data, resolved.source);
            scheduleNextSunset(context, resolved.applicable, location,
                    System.currentTimeMillis());
            AppLogger.i(TAG, "Sunset alarm re-armed on boot; applicable="
                    + resolved.applicable);
        } catch (Throwable t) {
            AppLogger.e(TAG, "onBootCompleted failed", t);
        }
    }

    // ===== Resolution core (shared by sync and async paths) =====

    /** Sync-safe resolution: manual → cached API data → arithmetic fallback. */
    private Resolved computeCore(Context context, double[] location) {
        long now = System.currentTimeMillis();
        String today = HijriMath.dateIso(now);
        long todaySunset = sunsetForDate(context, today, location);
        String applicable = (now < todaySunset) ? today : HijriMath.addDays(today, 1);
        return resolveApplicable(context, applicable, location, false);
    }

    /**
     * Applies the manual-override-then-data priority for one Gregorian date.
     * When {@code allowNetwork} is set, a stale/absent cache entry is replaced by
     * a fresh AlAdhan fetch (and the result is cached).
     */
    private Resolved resolveApplicable(Context context, String applicable,
                                       double[] location, boolean allowNetwork) {
        long now = System.currentTimeMillis();

        // 1. Manual override wins.
        HijriOverride manual = HijriOverrideRepository.get(context).find(applicable);
        if (manual != null) {
            long sunset = sunsetForDate(context, applicable, location);
            HijriDayData data = manualToDayData(context, manual, applicable, location, sunset);
            AppLogger.i(TAG, "Manual override active for " + applicable);
            return new Resolved(applicable, data, "manual", sunset);
        }

        // 2. Cached AlAdhan data for the same location bucket is authoritative.
        HijriDayData cached = HijriCache.getDay(context, applicable);
        boolean cachedUsable = cached != null
                && !cached.approxSunset
                && HijriCache.locationMatches(context, location);
        if (cachedUsable) {
            long sunset = sunsetForDate(context, applicable, location);
            AppLogger.d(TAG, "Using cached API data for " + applicable);
            return new Resolved(applicable, cached, "api", sunset);
        }

        // 3. Fresh AlAdhan API (only when allowed and a location exists).
        if (allowNetwork && location != null) {
            AppLogger.i(TAG, "Fetching AlAdhan data for " + applicable);
            HijriDayData api = HijriApiClient.fetch(applicable, location[0], location[1]);
            if (api != null) {
                HijriCache.putDay(context, api);
                long sunset = sunsetForDate(context, applicable, location);
                AppLogger.i(TAG, "AlAdhan API success for " + applicable);
                return new Resolved(applicable, api, "api", sunset);
            }
            AppLogger.w(TAG, "AlAdhan API failed for " + applicable);
        }

        // 4. Stale cached data still beats the approximation.
        if (cached != null) {
            long sunset = sunsetForDate(context, applicable, location);
            AppLogger.i(TAG, "Cache fallback for " + applicable);
            return new Resolved(applicable, cached, "cache", sunset);
        }

        // 5. Graceful arithmetic fallback (offline, no location).
        AppLogger.w(TAG, "No data available; using arithmetic fallback for " + applicable);
        HijriDayData fallback = HijriDayData.fallback(applicable,
                location == null ? null : location[0],
                location == null ? null : location[1], now);
        long sunset = sunsetForDate(context, applicable, location);
        return new Resolved(applicable, fallback, "fallback", sunset);
    }

    private HijriDayData manualToDayData(Context context, HijriOverride manual,
                                         String applicable, double[] location, long sunset) {
        Long cachedSunset = HijriCache.sunsetMillis(context, applicable);
        String sunsetStr = cachedSunset != null ? HijriMath.hhmm(cachedSunset) : HijriMath.hhmm(sunset);
        double lat = 0;
        double lng = 0;
        if (location != null) {
            lat = location[0];
            lng = location[1];
        } else {
            double[] cached = HijriCache.getLastLocation(context);
            if (cached != null) {
                lat = cached[0];
                lng = cached[1];
            }
        }
        return new HijriDayData(applicable, manual.hijriDay, manual.hijriMonth, manual.hijriYear,
                sunsetStr, HijriCache.getLastTz(context), lat, lng,
                System.currentTimeMillis(), cachedSunset == null);
    }

    /** Sunset epoch millis for a date: cached AlAdhan → solar approximation. */
    private long sunsetForDate(Context context, String date, double[] location) {
        Long cached = HijriCache.sunsetMillis(context, date);
        if (cached != null) {
            return cached;
        }
        java.util.Calendar calendar = HijriMath.calendarFromIso(date);
        if (calendar == null) {
            calendar = java.util.Calendar.getInstance();
        }
        double[] loc = location != null ? location : HijriCache.getLastLocation(context);
        if (loc != null) {
            return HijriMath.sunsetApproxMillis(loc[0], loc[1],
                    calendar.get(java.util.Calendar.YEAR),
                    calendar.get(java.util.Calendar.MONTH),
                    calendar.get(java.util.Calendar.DAY_OF_MONTH));
        }
        return HijriMath.sunsetApproxMillisFallback(
                calendar.get(java.util.Calendar.YEAR),
                calendar.get(java.util.Calendar.MONTH),
                calendar.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** Fetches and caches the following day quietly (offline-safe transition). */
    private void prefetchNextDay(Context context, String applicable, double[] location) {
        try {
            String next = HijriMath.addDays(applicable, 1);
            if (next == null || HijriCache.getDay(context, next) != null) {
                return;
            }
            AppLogger.d(TAG, "Prefetching next day " + next);
            HijriDayData data = HijriApiClient.fetch(next, location[0], location[1]);
            if (data != null) {
                HijriCache.putDay(context, data);
            }
        } catch (Throwable t) {
            AppLogger.w(TAG, "Next-day prefetch failed", t);
        }
    }

    /** Re-schedules the one sunset alarm for the next future sunset. */
    private void scheduleNextSunset(Context context, String applicable,
                                    double[] location, long now) {
        try {
            long todaySunset = sunsetForDate(context, applicable, location);
            long trigger = todaySunset > now ? todaySunset : todaySunset + 23L * 60L * 60L * 1000L;
            HijriSunsetScheduler.scheduleNext(context, trigger);
        } catch (Throwable t) {
            AppLogger.e(TAG, "Failed to schedule next sunset", t);
        }
    }

    /** Schedules only from the same final effective date and sunset used by Home. */
    private void syncHijriDay29Notification(Context context, Resolved resolved, long now) {
        if (resolved.data != null && resolved.data.hijriDay == 29 && resolved.sunset > now) {
            AppLogger.i(TAG, "Effective Hijri day 29 detected for " + resolved.applicable + "; sunset=" + HijriMath.hhmm(resolved.sunset));
            HijriDay29Notification.schedule(context, resolved.applicable, resolved.sunset);
        } else {
            HijriDay29Notification.cancel(context, "effective Hijri day is not 29 or sunset passed");
        }
    }

    /** Delivery-time validation reuses normal manual override → API/cache resolution. */
    public boolean isEffectiveHijriDay29ForGregorianDate(Context context, String gregorianDate) {
        try {
            Resolved resolved = resolveApplicable(context, gregorianDate, HijriCache.getLastLocation(context), false);
            AppLogger.i(TAG, "Day-29 delivery evaluated for " + gregorianDate + "; day=" + (resolved.data == null ? 0 : resolved.data.hijriDay) + "; source=" + resolved.source);
            return resolved.data != null && resolved.data.hijriDay == 29;
        } catch (Throwable t) {
            AppLogger.e(TAG, "Day-29 delivery evaluation failed", t);
            return false;
        }
    }
    /** Pushes the latest snapshot to the foreground activity (if any). */
    private void pushForeground(String snapshotJson) {
        HijriRefreshListener listener =
                foregroundListener == null ? null : foregroundListener.get();
        if (listener != null) {
            try {
                listener.onHijriDateUpdated(snapshotJson);
            } catch (Throwable t) {
                AppLogger.w(TAG, "Failed to push snapshot to foreground", t);
            }
        }
    }

    // ===== Manual override CRUD =====

    /** Validates + persists a manual override. Returns "ok" or "invalid". */
    public String saveOverride(Context context, String json) {
        try {
            JSONObject input = new JSONObject(json == null ? "" : json);
            String gregorianDate = input.optString("gregorianDate", "");
            int day = input.optInt("hijriDay", 0);
            int month = input.optInt("hijriMonth", 0);
            int year = input.optInt("hijriYear", 0);
            if (HijriMath.calendarFromIso(gregorianDate) == null) {
                AppLogger.w(TAG, "Invalid Gregorian date for override: " + gregorianDate);
                return "invalid";
            }
            if (day < 1 || day > 30 || month < 1 || month > 12
                    || year < MIN_HIJRI_YEAR || year > MAX_HIJRI_YEAR) {
                AppLogger.w(TAG, "Invalid Hijri values: " + day + "-" + month + "-" + year);
                return "invalid";
            }
            final HijriOverride override = new HijriOverride(
                    gregorianDate, day, month, year, System.currentTimeMillis());
            final Context app = context.getApplicationContext();
            AppLogger.i(TAG, "Saving override for " + gregorianDate);
            EXECUTOR.execute(() -> {
                HijriOverrideRepository.get(app).upsert(override);
                pushForeground(snapshotJson(app));
                loadOverrides(app);
                refresh(app);
            });
            return "ok";
        } catch (Throwable t) {
            AppLogger.e(TAG, "saveOverride failed", t);
            return "invalid";
        }
    }

    /** Deletes a manual override. Returns "ok" or "invalid". */
    public String deleteOverride(Context context, String gregorianDate) {
        if (HijriMath.calendarFromIso(gregorianDate) == null) {
            AppLogger.w(TAG, "Invalid Gregorian date for delete: " + gregorianDate);
            return "invalid";
        }
        final Context app = context.getApplicationContext();
        final String date = gregorianDate;
        AppLogger.i(TAG, "Deleting override for " + date);
        EXECUTOR.execute(() -> {
            HijriOverrideRepository.get(app).delete(date);
            pushForeground(snapshotJson(app));
            loadOverrides(app);
            refresh(app);
        });
        return "ok";
    }

    /** Loads all manual overrides async and pushes them to the foreground UI. */
    public void loadOverrides(Context context) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                List<HijriOverride> overrides = HijriOverrideRepository.get(app).getAll();
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < overrides.size(); i++) {
                    HijriOverride o = overrides.get(i);
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"gregorianDate\":\"").append(o.gregorianDate)
                            .append("\",\"hijriDay\":").append(o.hijriDay)
                            .append(",\"hijriMonth\":").append(o.hijriMonth)
                            .append(",\"hijriYear\":").append(o.hijriYear)
                            .append(",\"updatedAt\":").append(o.updatedAt).append('}');
                }
                sb.append(']');
                pushOverrides(sb.toString());
            } catch (Throwable t) {
                AppLogger.e(TAG, "loadOverrides failed", t);
                pushOverrides("[]");
            }
        });
    }

    private void pushOverrides(String overridesJson) {
        HijriRefreshListener listener =
                foregroundListener == null ? null : foregroundListener.get();
        if (listener != null) {
            try {
                listener.onHijriOverridesLoaded(overridesJson);
            } catch (Throwable t) {
                AppLogger.w(TAG, "Failed to push overrides to foreground", t);
            }
        }
    }

    /** One resolution outcome: the applicable Gregorian date + resolved data. */
    private static final class Resolved {
        final String applicable;
        final HijriDayData data;
        final String source;
        final long sunset;

        Resolved(String applicable, HijriDayData data, String source, long sunset) {
            this.applicable = applicable;
            this.data = data;
            this.source = source == null ? "fallback" : source;
            this.sunset = sunset;
        }

        JSONObject toSnapshotJson(Context context) {
            JSONObject o = new JSONObject();
            try {
                o.put("ready", data != null);
                o.put("gregorian", data == null ? "" : data.gregorianDate);
                o.put("hijriDay", data == null ? 0 : data.hijriDay);
                o.put("hijriMonth", data == null ? 0 : data.hijriMonth);
                o.put("hijriYear", data == null ? 0 : data.hijriYear);
                o.put("source", source);
                o.put("sunset", HijriMath.hhmm(sunset));
                String tz = data != null && !data.timezone.isEmpty()
                        ? data.timezone : HijriCache.getLastTz(context);
                o.put("timezone", tz);
                o.put("locationKnown",
                        HijriCache.getLastLocation(context) != null);
            } catch (JSONException e) {
                AppLogger.w(TAG, "toSnapshotJson failed", e);
            }
            return o;
        }
    }
}