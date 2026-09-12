package com.darood.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Central coordinator for the location-aware Hijri date feature.
 *
 * <p>Owns the full pipeline:
 * <ol>
 *   <li>Current local Gregorian date/time determines "today".</li>
 *   <li>Manual corrections are the base for subsequent local sunset increments.
 *       Day 29 may resolve the next month-end date.</li>
 *   <li>Resolution priority: manual override → AlAdhan API → cache → fallback.</li>
 *   <li>Only an effective day-29 boundary permits an automatic API request.
 *       Every day still has a local sunset event, including offline day 30 rollover.</li>
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
    private static final String KEY_TRANSITIONS = "hijri_29_refresh_handled_dates";

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
            // The existing foreground snapshot timer also discovers month-end boundaries offline.
            final Context app = context.getApplicationContext();
            EXECUTOR.execute(() -> {
                try {
                    reconcile(app, HijriCache.getLastLocation(app), System.currentTimeMillis());
                } catch (Throwable t) {
                    AppLogger.e(TAG, "Snapshot reconciliation failed", t);
                }
            });
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
        if (location != null) HijriCache.setLastLocation(context, location[0], location[1]);
        else location = HijriCache.getLastLocation(context);
        reconcile(context, location, System.currentTimeMillis());
    }

    /** Shared foreground, alarm, boot and manual-edit path; all calls are serialized. */
    private synchronized void reconcile(Context context, double[] location, long now) {
        Resolved resolved = resolveEffective(context, location, now, true);
        resolvedData = resolved.data;
        resolvedSource = resolved.source;
        applicableDate = resolved.applicable;
        AppLogger.i(TAG, "Effective Hijri date=" + resolved.data.hijriDay + "-"
                + resolved.data.hijriMonth + "-" + resolved.data.hijriYear
                + "; Gregorian=" + resolved.applicable + "; source=" + resolved.source);
        syncHijriDay29Notification(context, resolved, now);
        HijriNewMonthNotification.notifyIfNewMonth(context, resolved.data, resolved.source);
        scheduleNextSunset(context, resolved, now);
        pushForeground(resolved.toSnapshotJson(context).toString());
    }

    /** Receiver work stays alive through goAsync and uses the same executor as manual edits. */
    public void onSunsetFired(Context context, String date, int expectedDay, Runnable completion) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                double[] location = HijriCache.getLastLocation(app);
                long now = System.currentTimeMillis();
                // Every daily event and any legacy/stale event reconcile the persisted state.
                // The final effective outgoing date alone decides network eligibility.
                reconcile(app, location, now);
            } catch (Throwable t) {
                AppLogger.e(TAG, "onSunsetFired failed", t);
            } finally {
                completion.run();
            }
        });
    }

    /** Alarms vanish on reboot; revalidate current dates instead of trusting saved schedule flags. */
    public void onBootCompleted(Context context, Runnable completion) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                HijriSunsetScheduler.cancel(app);
                HijriDay29Notification.resetScheduleAfterBoot(app);
                reconcile(app, HijriCache.getLastLocation(app), System.currentTimeMillis());
            } catch (Throwable t) {
                AppLogger.e(TAG, "onBootCompleted failed", t);
            } finally {
                completion.run();
            }
        });
    }

    /** Part 2 also needs off-main-thread manual lookup and serialization with edits. */
    public void onDay29NotificationFired(Context context, Intent intent, Runnable completion) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                HijriDay29Notification.handleFire(LanguageManager.applyLanguage(app), intent);
            } catch (Throwable t) {
                AppLogger.e(TAG, "Day-29 notification failed", t);
            } finally {
                completion.run();
            }
        });
    }

    // ===== Resolution core (shared by sync and async paths) =====

    /** Sync-safe resolution: manual → cached API data → arithmetic fallback. */
    private Resolved computeCore(Context context, double[] location) {
        return computeCore(context, location, System.currentTimeMillis());
    }

    private synchronized Resolved computeCore(Context context, double[] location, long now) {
        return resolveEffective(context, location, now, false);
    }

    /** Changes in Room corrections invalidate only derived state, never raw API/cache or user data. */
    private String manualKey(Context context) {
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (HijriOverride o : HijriOverrideRepository.get(context).getAll()) {
            keys.add(o.gregorianDate + ":" + o.hijriDay + ":" + o.hijriMonth + ":" + o.hijriYear + ":" + o.updatedAt);
        }
        java.util.Collections.sort(keys);
        return keys.toString();
    }

    private Resolved resolveEffective(Context context, double[] location, long now, boolean persist) {
        String today = HijriMath.dateIso(now);
        String target = now < sunsetForDate(context, today, location) ? today : HijriMath.addDays(today, 1);
        String key = manualKey(context);
        JSONObject state = HijriCache.effectiveState(context);
        HijriDayData saved = HijriDayData.fromJson(state);
        Resolved current = null;
        if (saved != null && key.equals(state.optString("manualKey", ""))) {
            // Clock/timezone moving backwards must not replay an already processed sunset.
            current = new Resolved(saved.gregorianDate, saved, state.optString("source", "cache"),
                    sunsetForDate(context, saved.gregorianDate, location));
        }
        if (current == null) {
            HijriOverride latest = null;
            for (HijriOverride o : HijriOverrideRepository.get(context).getAll()) {
                if (o.gregorianDate.compareTo(today) <= 0
                        && (latest == null || o.gregorianDate.compareTo(latest.gregorianDate) > 0)) latest = o;
            }
            HijriDayData cached = HijriCache.latestDayAtOrBefore(context, today);
            String seed = latest != null ? latest.gregorianDate : cached != null ? cached.gregorianDate : today;
            current = resolveApplicable(context, seed, location, false);
        }
        if (persist && (saved == null || !key.equals(state.optString("manualKey", ""))))
            HijriCache.putEffective(context, current.data, current.source, key);
        boolean advanced = false;
        while (current.applicable.compareTo(target) < 0) {
            String nextDate = HijriMath.addDays(current.applicable, 1);
            if (nextDate == null) break;
            HijriOverride incomingManual = HijriOverrideRepository.get(context).find(nextDate);
            Resolved incoming;
            boolean day29 = current.data.hijriDay == 29;
            if (persist) AppLogger.i(TAG, "Sunset transition detected: " + current.applicable
                    + "; previous effective=" + current.data.hijriDay + "-" + current.data.hijriMonth + "-" + current.data.hijriYear);
            if (day29) {
                if (persist) {
                    AppLogger.i(TAG, "Final effective day 29 ending at sunset");
                    HijriDay29Notification.fireEffectiveBoundary(context, current.applicable);
                }
                boolean allowNetwork = persist && claimDay29(context, current.applicable);
                incoming = resolveApplicable(context, nextDate, location, allowNetwork, current.data);
            } else if (incomingManual != null) {
                incoming = resolveApplicable(context, nextDate, location, false);
            } else {
                HijriDayData solar = HijriDayData.fallback(nextDate,
                        location == null ? null : location[0], location == null ? null : location[1], now);
                incoming = new Resolved(nextDate, current.data.nextLocalDay(solar), current.source,
                        sunsetForDate(context, nextDate, location));
                if (persist) AppLogger.i(TAG, "Local increment performed; API refresh skipped on normal sunset");
            }
            if (persist) {
                HijriCache.putEffective(context, incoming.data, incoming.source, key);
                AppLogger.i(TAG, "New effective Hijri date=" + incoming.data.hijriDay + "-"
                        + incoming.data.hijriMonth + "-" + incoming.data.hijriYear);
                HijriNewMonthNotification.notifyIfNewMonth(context, incoming.data, incoming.source);
            }
            current = incoming;
            advanced = true;
        }
        if (persist && !advanced) AppLogger.d(TAG, "Duplicate sunset transition prevented; effective date already current");
        return current;
    }

    private boolean claimDay29(Context context, String date) {
        SharedPreferences prefs = context.getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
        Set<String> handled = new HashSet<>(prefs.getStringSet(KEY_TRANSITIONS, new HashSet<String>()));
        if (!handled.add(date)) {
            AppLogger.i(TAG, "Duplicate day-29 API event prevented: " + date);
            return false;
        }
        return prefs.edit().putStringSet(KEY_TRANSITIONS, handled).commit();
    }
    /**
     * Applies the manual-override-then-data priority for one Gregorian date.
     * Only a validated day-29 transition sets {@code allowNetwork}; an ordinary
     * foreground, snapshot or reboot uses local sources.
     */
    private Resolved resolveApplicable(Context context, String applicable,
                                       double[] location, boolean allowNetwork) {
        return resolveApplicable(context, applicable, location, allowNetwork, null);
    }

    private Resolved resolveApplicable(Context context, String applicable,
                                       double[] location, boolean allowNetwork, HijriDayData outgoing) {
        HijriOverride manual = HijriOverrideRepository.get(context).find(applicable);
        if (manual != null) {
            long sunset = sunsetForDate(context, applicable, location);
            AppLogger.i(TAG, "Manual override active for " + applicable
                    + "; API refresh skipped because manual override is authoritative");
            return new Resolved(applicable,
                    manualToDayData(context, manual, applicable, location, sunset), "manual", sunset);
        }
        JSONObject effective = HijriCache.effectiveDay(context, applicable);
        HijriDayData effectiveData = HijriDayData.fromJson(effective);
        if (!allowNetwork && effectiveData != null && manualKey(context).equals(effective.optString("manualKey", ""))
                && (outgoing == null || validMonthEnd(outgoing, effectiveData))) {
            return new Resolved(applicable, effectiveData, effective.optString("source", "cache"),
                    sunsetForDate(context, applicable, location));
        }
        HijriDayData cached = HijriCache.getDay(context, applicable);
        // Only the validated day-29 boundary caller allows network. Recheck even prefetched cache.
        if (allowNetwork && location != null) {
            AppLogger.i(TAG, "Day-29 API refresh started for " + applicable);
            HijriDayData api = HijriApiClient.fetch(applicable, location[0], location[1]);
            if (api != null && applicable.equals(api.gregorianDate)
                    && (outgoing == null || validMonthEnd(outgoing, api))) {
                HijriCache.putDay(context, api);
                // Never let a response supersede a manual value added while the request was in flight.
                if (HijriOverrideRepository.get(context).find(applicable) != null)
                    return resolveApplicable(context, applicable, location, false);
                AppLogger.i(TAG, "Day-29 API refresh succeeded for " + applicable);
                return new Resolved(applicable, api, "api", sunsetForDate(context, applicable, location));
            }
            AppLogger.w(TAG, "Day-29 API refresh failed/invalid for " + applicable);
        }
        if (cached != null && (outgoing == null || validMonthEnd(outgoing, cached))) {
            AppLogger.d(TAG, "Effective source=CACHE for " + applicable);
            return new Resolved(applicable, cached, "cache", sunsetForDate(context, applicable, location));
        }
        HijriDayData fallback = HijriDayData.fallback(applicable,
                location == null ? null : location[0], location == null ? null : location[1],
                System.currentTimeMillis());
        if (outgoing != null && !validMonthEnd(outgoing, fallback)) {
            // If offline data conflicts with a corrected day 29, conservatively complete 30 days.
            int month = outgoing.hijriMonth == 12 ? 1 : outgoing.hijriMonth + 1;
            int year = outgoing.hijriYear + (outgoing.hijriMonth == 12 ? 1 : 0);
            fallback = new HijriDayData(applicable, outgoing.hijriDay == 29 ? 30 : 1,
                    outgoing.hijriDay == 29 ? outgoing.hijriMonth : month,
                    outgoing.hijriDay == 29 ? outgoing.hijriYear : year,
                    fallback.sunsetLocal, fallback.timezone, fallback.latitude, fallback.longitude,
                    fallback.fetchedAt, true);
        }
        if (outgoing != null) HijriCache.putDay(context, fallback);
        return new Resolved(applicable, fallback, "fallback", sunsetForDate(context, applicable, location));
    }

    private boolean validMonthEnd(HijriDayData outgoing, HijriDayData incoming) {
        if (outgoing.hijriDay == 29 && incoming.hijriDay == 30 && incoming.hijriMonth == outgoing.hijriMonth
                && incoming.hijriYear == outgoing.hijriYear) return true;
        int nextMonth = outgoing.hijriMonth == 12 ? 1 : outgoing.hijriMonth + 1;
        int nextYear = outgoing.hijriYear + (outgoing.hijriMonth == 12 ? 1 : 0);
        return incoming.hijriDay == 1 && incoming.hijriMonth == nextMonth
                && incoming.hijriYear == nextYear;
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
        HijriDayData cachedDay = HijriCache.getDay(context, date);
        boolean sameLocation = location == null || cachedDay == null
                || (Math.abs(cachedDay.latitude - location[0]) < 0.1
                    && Math.abs(cachedDay.longitude - location[1]) < 0.1);
        if (cached != null && sameLocation) {
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

    /** A daily local sunset event advances the effective date; only day 29 permits networking. */
    private void scheduleNextSunset(Context context, Resolved resolved, long now) {
        try {
            int day = resolved.data.hijriDay;
            if (resolved.sunset > now) {
                HijriSunsetScheduler.scheduleNext(context, resolved.applicable, day, resolved.sunset);
                AppLogger.i(TAG, "Sunset event scheduled; day=" + day + "; API eligible=" + (day == 29));
            } else {
                HijriSunsetScheduler.cancel(context);
                AppLogger.d(TAG, "No future sunset boundary; effective day=" + day);
            }
        } catch (Throwable t) {
            AppLogger.e(TAG, "Failed to schedule month-end sunset", t);
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
                reconcile(app, HijriCache.getLastLocation(app), System.currentTimeMillis());
                loadOverrides(app);
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
            reconcile(app, HijriCache.getLastLocation(app), System.currentTimeMillis());
            loadOverrides(app);
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
