$content = @'
package com.darood.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class HijriLocationHelper {

    private static final String TAG = "HijriLocationHelper";
    private static final String[] PERMISSIONS = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
    };
    private static final long MAX_LAST_KNOWN_AGE_MS = 30L * 60L * 1000L;

    private HijriLocationHelper() {
    }

    public static boolean hasPermission(Context context) {
        for (String permission : PERMISSIONS) {
            if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

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
            Location last = lastKnown(lm);
            if (last != null
                    && System.currentTimeMillis() - last.getTime() < MAX_LAST_KNOWN_AGE_MS) {
                AppLogger.i(TAG, "Fresh last-known location reused");
                return toPair(last);
            }
            Location fresh = requestFresh(lm, timeoutMs);
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
'@