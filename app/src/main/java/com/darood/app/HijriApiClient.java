package com.darood.app;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTPS client for the AlAdhan location-aware Islamic calendar/timings data.
 *
 * <p>One request to {@code /v1/timings/{dd-mm-yyyy}?latitude=..&amp;longitude=..&amp;method=3}
 * returns everything PART 1 needs for a Gregorian date at the user's location:
 * the Gregorian date, the Hijri day/month/year, the local Sunset and the IANA
 * timezone. Responses are strictly validated before being trusted.
 *
 * <p>HTTPS only; callers run on a background thread.
 */
public final class HijriApiClient {

    private static final String TAG = "HijriApiClient";
    private static final String BASE_URL = "https://api.aladhan.com/v1/timings/";
    private static final int TIMEOUT_MS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Pattern TIME_PATTERN = Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})");

    private HijriApiClient() {
    }

    /**
     * Fetches the Hijri date + local sunset for one Gregorian date at the given
     * location. Returns null on any network/server/parse failure (never throws).
     */
    public static HijriDayData fetch(String gregorianDate, double latitude, double longitude) {
        try {
            String body = httpGet(buildUrl(gregorianDate, latitude, longitude));
            if (body == null) {
                return null;
            }
            return parse(body, gregorianDate);
        } catch (Throwable t) {
            AppLogger.w(TAG, "API fetch failed for " + gregorianDate, t);
            return null;
        }
    }

    static String buildUrl(String gregorianDate, double latitude, double longitude) {
        String[] p = gregorianDate.split("-");
        return BASE_URL + p[2] + "-" + p[1] + "-" + p[0]
                + "?latitude=" + latitude + "&longitude=" + longitude + "&method=3";
    }

    static HijriDayData parse(String body, String requestedDate) {
        try {
            JSONObject root = new JSONObject(body);
            int code = root.optInt("code", -1);
            if (code != 200) {
                AppLogger.w(TAG, "AlAdhan error code: " + code);
                return null;
            }
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                AppLogger.w(TAG, "AlAdhan response missing data");
                return null;
            }
            JSONObject timings = data.optJSONObject("timings");
            JSONObject dateInfo = data.optJSONObject("date");
            JSONObject meta = data.optJSONObject("meta");
            if (timings == null || dateInfo == null) {
                AppLogger.w(TAG, "AlAdhan response missing timings/date");
                return null;
            }

            String sunset = firstTimePart(timings.optString("Sunset", ""));
            String timezone = meta == null ? "" : meta.optString("timezone", "");

            JSONObject gregorian = dateInfo.optJSONObject("gregorian");
            String apiGregorian = gregorian == null ? "" : gregorian.optString("date", "");
            String normalized = normalizeApiDate(apiGregorian);
            if (normalized == null) {
                normalized = requestedDate;
            }

            JSONObject hijri = dateInfo.optJSONObject("hijri");
            if (hijri == null) {
                AppLogger.w(TAG, "AlAdhan response missing hijri date");
                return null;
            }
            int hijriDay = parseNumber(hijri.optString("day", ""));
            int hijriYear = parseNumber(hijri.optString("year", ""));
            JSONObject hijriMonth = hijri.optJSONObject("month");
            int hijriMonthNumber = hijriMonth == null ? 0 : hijriMonth.optInt("number", 0);
            if (hijriDay < 1 || hijriDay > 30 || hijriMonthNumber < 1 || hijriMonthNumber > 12
                    || hijriYear < 1 || sunset.isEmpty()) {
                AppLogger.w(TAG, "AlAdhan payload failed validation");
                return null;
            }
            double lat = meta == null ? 0 : meta.optDouble("latitude", 0);
            double lng = meta == null ? 0 : meta.optDouble("longitude", 0);
            return new HijriDayData(normalized, hijriDay, hijriMonthNumber, hijriYear,
                    sunset, timezone, lat, lng, System.currentTimeMillis(), false);
        } catch (Exception e) {
            AppLogger.w(TAG, "Failed to parse AlAdhan response", e);
            return null;
        }
    }

    /** Converts AlAdhan's {@code DD-MM-YYYY} to {@code yyyy-MM-dd}, or null if invalid. */
    static String normalizeApiDate(String apiDate) {
        if (apiDate == null) {
            return null;
        }
        String[] p = apiDate.split("-");
        if (p.length != 3 || p[0].length() != 2 || p[1].length() != 2 || p[2].length() != 4) {
            return null;
        }
        try {
            int d = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            int y = Integer.parseInt(p[2]);
            if (d < 1 || m < 1 || m > 12 || y < 1900) {
                return null;
            }
                        return String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m, d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Extracts the {@code HH:mm} portion from an AlAdhan timing string. */
    private static String firstTimePart(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher m = TIME_PATTERN.matcher(raw);
        if (!m.find()) {
            return "";
        }
        return m.group(1) + ":" + m.group(2);
    }

    private static int parseNumber(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (!(connection instanceof HttpsURLConnection)) {
            AppLogger.w(TAG, "AlAdhan must be fetched over HTTPS");
            connection.disconnect();
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
                AppLogger.w(TAG, "AlAdhan HTTP " + responseCode);
                return null;
            }
            return readLimited(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static String readLimited(HttpURLConnection connection) throws Exception {
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_RESPONSE_BYTES) {
                    throw new java.io.IOException("AlAdhan response too large");
                }
                output.write(buffer, 0, read);
            }
            return output.toString("UTF-8");
        }
    }
}