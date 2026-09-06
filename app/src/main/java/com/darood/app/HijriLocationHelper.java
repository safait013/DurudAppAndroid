package com.darood.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Minimal one-shot location acquisition built on the platform
 * {@link LocationManager} (no Google Play Services dependency, no continuous GPS
 * tracking). Handles permission grants/denials, disabled location services,
 * timeouts and failures by degrading to the last cached location or null — the
 * rest of the app must stay fully usable without a fix.
 */
public final class HijriLocationHelper {

    private static final String TAG = "HijriLocationHelper";
    private static final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };
    /** Treated as "fresh enough" without a new fix. */
    private static final long MAX_LAST_KNOWN_AGE_MS = 30L * 60L * 1000L;

    private HijriLocationHelper() {
    }

    /** True when the app holds at least one location runtime permission. */
    public static boolean hasPermission(Context context) {
        for (String permission : PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /** True when at least one location provider (GPS or network) is enabled. */
    public static boolean isLocationServicesEnabled(Context context) {
        try {
            LocationManager lm =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return false;
            }
            return lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Throwable t) {
            AppLogger.w(TAG, "isLocationServicesEnabled failed", t);
            return false;
        }
    }

    /**
     * Blocking location lookup ({@code timeoutMs} bound). Returns {@code {lat, lng}}
     * or null; falls back to the cached location when a fresh fix is unavailable.
     * Call from a background thread only.
     */
    public static double[] getLocation(Context context, long timeoutMs) {
        if (!hasPermission(context)) {
            AppLogger.i(TAG, "Location permission not granted; using cache");
            return HijriCache.getLastLocation(context);
        }
        if (!isLocationServicesEnabled(context)) {
            AppLogger.w(TAG, "Location services disabled; using cache");
            return HijriCache.getLastLocation(context);
        }
        try {
            LocationManager lm =
                    (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return HijriCache.getLastLocation(context);
            }
            Location last = lastKnown(context, lm);
            if (last != null
                    && System.currentTimeMillis() - last.getTime() < MAX_LAST_KNOWN_AGE_MS) {
                AppLogger.i(TAG, "Fresh last-known location reused");
                return toPair(last);
            }
            Location fresh = requestFresh(context, lm, timeoutMs);
            if (fresh != null) {
                AppLogger.i(TAG, "Location fix obtained (accuracy "
                        + Math.round(fresh.getAccuracy()) + " m)");
                return toPair(fresh);
            }
                        if (last != null) {
                AppLogger.w(TAG, "Location timeout; reused older last-known fix");
                return toPair(last);
            }
            AppLogger.w(TAG, "No location available");
            return HijriCache.getLastLocation(context);
        } catch (Throwable t) {
            AppLogger.w(TAG, "Location acquisition failed", t);
            return HijriCache.getLastLocation(context);
        }
    }

    private static Location lastKnown(Context context, LocationManager lm) {
        if (!hasPermission(context)) {
            return null;
        }
        Location best = null;
        for (String provider : providers(lm)) {
            try {
                Location location = lm.getLastKnownLocation(provider);
                if (location != null && isBetter(location, best)) {
                    best = location;
                }
            } catch (SecurityException e) {
                AppLogger.w(TAG, "Permission lost during lastKnown for " + provider, e);
            } catch (Exception e) {
                AppLogger.w(TAG, "lastKnown failed for " + provider, e);
            }
        }
        return best;
    }

    private static Location requestFresh(final Context context, final LocationManager lm, long timeoutMs) {
        if (!hasPermission(context)) {
            return null;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] best = new Location[1];
        if (Build.VERSION.SDK_INT >= 30) {
            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final CancellationSignal[] signals =
                    new CancellationSignal[providers(lm).length];
            int i = 0;
            for (String provider : providers(lm)) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        CancellationSignal signal = new CancellationSignal();
                        signals[i] = signal;
                        final int index = i;
                        lm.getCurrentLocation(provider, signal, executor, location -> {
                            if (location != null && isBetter(location, best[0])) {
                                best[0] = location;
                            }
                            latch.countDown();
                        });
                        i = index + 1;
                    }
                } catch (SecurityException e) {
                    AppLogger.w(TAG, "Permission lost during getCurrentLocation for " + provider, e);
                } catch (Exception e) {
                    AppLogger.w(TAG, "getCurrentLocation failed for " + provider, e);
                }
            }
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    AppLogger.w(TAG, "getCurrentLocation timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (CancellationSignal signal : signals) {
                if (signal != null) {
                    signal.cancel();
                }
            }
            executor.shutdown();
            return best[0];
        }

        // API 21–29: requestSingleUpdate for each enabled provider.
        final LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                if (isBetter(location, best[0])) {
                    best[0] = location;
                }
                latch.countDown();
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onStatusChanged(@NonNull String provider, int status, Bundle extras) {
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
            }
        };
        try {
            for (String provider : providers(lm)) {
                try {
                    if (lm.isProviderEnabled(provider)) {
                        //noinspection deprecation
                        lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
                    }
                } catch (SecurityException e) {
                    AppLogger.w(TAG, "Permission lost during requestSingleUpdate for " + provider, e);
                } catch (Exception e) {
                    AppLogger.w(TAG, "requestSingleUpdate failed for " + provider, e);
                }
            }
            try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    AppLogger.w(TAG, "requestSingleUpdate timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            lm.removeUpdates(listener);
        } catch (Exception e) {
            AppLogger.w(TAG, "Location listener setup failed", e);
        }
        return best[0];
    }

    /** Enabled providers, network first (works with coarse permission, faster). */
    private static String[] providers(LocationManager lm) {
        List<String> enabled = new ArrayList<>();
        for (String provider : new String[]{LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (lm.isProviderEnabled(provider)) {
                    enabled.add(provider);
                }
            } catch (Exception ignored) {
                // Provider no longer exists — skip it.
            }
        }
        return enabled.toArray(new String[0]);
    }

    private static boolean isBetter(Location candidate, Location current) {
        if (current == null) {
            return true;
        }
        return candidate.getAccuracy() < current.getAccuracy();
    }

    private static double[] toPair(Location location) {
        return new double[]{location.getLatitude(), location.getLongitude()};
    }
}