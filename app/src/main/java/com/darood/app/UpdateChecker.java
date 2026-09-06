package com.darood.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * Fetches the published app version from the project's HTTPS configuration file.
 *
 * <p>This class deliberately only detects and persists update availability. It does
 * not create notifications, dialogs, or reminders; those belong to a later feature
 * part. A failed check leaves all app features and existing user data untouched.</p>
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String REMOTE_CONFIG_URL =
            "https://raw.githubusercontent.com/safait013/DurudAppAndroid/main/app-update.json";
    private static final long CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024;

    private static final String KEY_LAST_CHECK_MS = "update_last_check_ms";
    private static final String KEY_LATEST_VERSION_CODE = "update_latest_version_code";
    private static final String KEY_LATEST_VERSION_NAME = "update_latest_version_name";
    private static final String KEY_UPDATE_AVAILABLE = "update_available";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean CHECK_IN_PROGRESS = new AtomicBoolean(false);

    private UpdateChecker() {
    }

    /** Starts one asynchronous check when no check has been attempted in the last 24 hours. */
    public static void checkIfDue(Context context) {
        final Context appContext = context.getApplicationContext();
        if (!isCheckDue(appContext)) {
            return;
        }
        if (!CHECK_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                checkNow(appContext);
            } catch (Throwable t) {
                // This is a final safety net: update checking must never affect app startup.
                AppLogger.w(TAG, "Update check failed", t);
            } finally {
                CHECK_IN_PROGRESS.set(false);
            }
        });
    }

    /** Returns the last successfully determined availability state for later feature parts. */
    public static boolean isUpdateAvailable(Context context) {
        return prefs(context).getBoolean(KEY_UPDATE_AVAILABLE, false);
    }

    private static boolean isCheckDue(Context context) {
        long lastCheck = prefs(context).getLong(KEY_LAST_CHECK_MS, 0L);
        long now = System.currentTimeMillis();
        return lastCheck <= 0L || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS;
    }

    @SuppressWarnings("deprecation") // NetworkInfo is needed on minSdk 21 without extra dependencies.
    private static void checkNow(Context context) {
        AppLogger.i(TAG, "Update check started");
        // Cache attempts too, preventing repeated network work on every app launch offline.
        prefs(context).edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply();

        final long installedVersionCode = getInstalledVersionCode(context);
        if (installedVersionCode <= 0L) {
            AppLogger.w(TAG, "Installed version could not be determined");
            return;
        }
        AppLogger.i(TAG, "Installed versionCode=" + installedVersionCode);

        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        if (network == null || !network.isConnected()) {
            AppLogger.w(TAG, "Update check network failure: no active network");
            return;
        }

        try {
            RemoteVersion remote = fetchRemoteVersion();
            if (remote == null) {
                return; // The validation method has already logged the reason.
            }
            AppLogger.i(TAG, "Remote versionCode=" + remote.versionCode
                    + ", versionName=" + remote.versionName);

            boolean available = installedVersionCode < remote.versionCode;
            prefs(context).edit()
                    .putLong(KEY_LATEST_VERSION_CODE, remote.versionCode)
                    .putString(KEY_LATEST_VERSION_NAME, remote.versionName)
                    .putBoolean(KEY_UPDATE_AVAILABLE, available)
                    .apply();
            AppLogger.i(TAG, available ? "Update available" : "Update not available");
            NotificationScheduler.handleUpdateAvailability(context, installedVersionCode,
                    remote.versionCode);
            AppLogger.i(TAG, "Update check completed");
        } catch (IOException e) {
            AppLogger.w(TAG, "Update check network failure", e);
        } catch (Exception e) {
            AppLogger.w(TAG, "Update check failed", e);
        }
    }

    /** Reads the installed package metadata; versionName is intentionally never compared. */
    @SuppressWarnings("deprecation") // PackageInfo.versionCode supports API 21-27.
    static long getInstalledVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            AppLogger.w(TAG, "Unable to read installed package metadata", e);
            return -1L;
        }
    }

    private static RemoteVersion fetchRemoteVersion() throws Exception {
        URL url = new URL(REMOTE_CONFIG_URL);
        if (!"https".equalsIgnoreCase(url.getProtocol())) {
            AppLogger.w(TAG, "Invalid remote configuration URL");
            return null;
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            AppLogger.w(TAG, "Remote configuration must use HTTPS");
            return null;
        }
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setUseCaches(false);
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                AppLogger.w(TAG, "Update check server error: HTTP " + responseCode);
                return null;
            }
            String body = readLimited(connection);
            return parseRemoteVersion(body);
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(HttpURLConnection connection) throws IOException {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new IOException("Remote configuration response is too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }

    /** Validates the exact expected schema without allowing malformed data to escape. */
    static RemoteVersion parseRemoteVersion(String json) {
        try {
            JSONObject object = new JSONObject(json);
            Object rawCode = object.opt("latestVersionCode");
            if (!(rawCode instanceof Integer) && !(rawCode instanceof Long)) {
                AppLogger.w(TAG, "Invalid remote data: latestVersionCode is not an integer");
                return null;
            }
            long code = ((Number) rawCode).longValue();
            String name = object.optString("latestVersionName", "").trim();
            if (code <= 0L || name.isEmpty() || name.length() > 128 || containsControlCharacter(name)) {
                AppLogger.w(TAG, "Invalid remote data");
                return null;
            }
            return new RemoteVersion(code, name);
        } catch (Exception e) {
            AppLogger.w(TAG, "Invalid remote data", e);
            return null;
        }
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
    }

    static final class RemoteVersion {
        final long versionCode;
        final String versionName;

        RemoteVersion(long versionCode, String versionName) {
            this.versionCode = versionCode;
            this.versionName = versionName;
        }
    }
}
