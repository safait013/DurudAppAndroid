package com.darood.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists non-language app settings in the same SharedPreferences file used by
 * {@link LanguageManager} ("app_settings"). Arabic font today; theme and
 * notification settings can join here in later steps.
 */
public final class AppSettings {

    public static final String PREFS_FILE = "app_settings";
    public static final String KEY_ARABIC_FONT = "arabic_font";

    public static final String FONT_AMIRI = "amiri";
    public static final String FONT_SCHEHERAZADE = "scheherazade";
    public static final String FONT_LATEEF = "lateef";
    public static final String FONT_NOTO_NASKH = "notonaskh";
    public static final String DEFAULT_ARABIC_FONT = FONT_AMIRI;

    public static final String KEY_ARABIC_FONT_SIZE = "arabic_font_size";
    public static final int MIN_ARABIC_FONT_SIZE = 16;
    public static final int MAX_ARABIC_FONT_SIZE = 32;
    public static final int DEFAULT_ARABIC_FONT_SIZE = 24;

    public static final String KEY_THEME = "theme";
    public static final String THEME_DEFAULT = "default";
    public static final String THEME_GREEN = "green";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_BROWN = "brown";
    public static final String THEME_PURPLE = "purple";
    public static final String THEME_DARK = "dark";
    public static final String DEFAULT_THEME = THEME_DEFAULT;
    public static final String[] SUPPORTED_THEMES = {
            THEME_DEFAULT, THEME_GREEN, THEME_BLUE, THEME_BROWN, THEME_PURPLE, THEME_DARK
    };

    public static final String KEY_CRASH_REPORT_CONSENT = "crash_report_consent";

    /** Separate one-time flag marking that first-launch setup was completed. */
    public static final String KEY_SETUP_COMPLETED = "setup_completed";

    /** One-time flag: the optional location permission dialog was already shown. */
    public static final String KEY_HIJRI_LOCATION_ASKED = "hijri_location_permission_asked";

    /** Official developer/contact email (matches the mailto link in the About/Contact UI). */
    public static final String SUPPORT_EMAIL = "primebytelabs.support@gmail.com";

    private AppSettings() {
    }

    /** @return whether the user opted in to email crash reports (default false). */
    public static boolean getCrashReportConsent(Context context) {
        return prefs(context).getBoolean(KEY_CRASH_REPORT_CONSENT, false);
    }

    /** Persists the crash-report email consent. */
    public static void saveCrashReportConsent(Context context, boolean consent) {
        prefs(context).edit().putBoolean(KEY_CRASH_REPORT_CONSENT, consent).apply();
    }

    /** @return whether the one-time first-launch setup has been completed (default false). */
    public static boolean isSetupComplete(Context context) {
        return prefs(context).getBoolean(KEY_SETUP_COMPLETED, false);
    }

    /** Marks the first-launch setup as completed (separate from the individual setting values). */
    public static void saveSetupCompleted(Context context, boolean completed) {
        prefs(context).edit().putBoolean(KEY_SETUP_COMPLETED, completed).apply();
    }

    /** True once the optional location permission dialog has been shown. */
    public static boolean isHijriLocationPermissionAsked(Context context) {
        return prefs(context).getBoolean(KEY_HIJRI_LOCATION_ASKED, false);
    }

    /** Records that the optional location permission dialog was shown. */
    public static void setHijriLocationPermissionAsked(Context context, boolean asked) {
        prefs(context).edit().putBoolean(KEY_HIJRI_LOCATION_ASKED, asked).apply();
    }

    /** @return the saved Arabic font code (defaults to Amiri; invalid values fall back). */
    public static String getArabicFont(Context context) {
        String code = prefs(context).getString(KEY_ARABIC_FONT, DEFAULT_ARABIC_FONT);
        return isValidArabicFont(code) ? code : DEFAULT_ARABIC_FONT;
    }

    /** Persists the Arabic font choice (ignored unless the code is valid). */
    public static void saveArabicFont(Context context, String code) {
        if (isValidArabicFont(code)) {
            prefs(context).edit().putString(KEY_ARABIC_FONT, code).apply();
        }
    }

    public static boolean isValidArabicFont(String code) {
        return FONT_AMIRI.equals(code) || FONT_SCHEHERAZADE.equals(code)
                || FONT_LATEEF.equals(code) || FONT_NOTO_NASKH.equals(code);
    }

    /** @return the saved Arabic font size in sp, clamped to [16, 32] (default 24). */
    public static int getArabicFontSize(Context context) {
        int size = prefs(context).getInt(KEY_ARABIC_FONT_SIZE, DEFAULT_ARABIC_FONT_SIZE);
        return Math.max(MIN_ARABIC_FONT_SIZE, Math.min(MAX_ARABIC_FONT_SIZE, size));
    }

    /** Persists the Arabic font size in sp (clamped to the allowed 16–32 range). */
    public static void saveArabicFontSize(Context context, int sizeSp) {
        int clamped = Math.max(MIN_ARABIC_FONT_SIZE, Math.min(MAX_ARABIC_FONT_SIZE, sizeSp));
        prefs(context).edit().putInt(KEY_ARABIC_FONT_SIZE, clamped).apply();
    }

    /** @return the saved theme code (one of the supported themes; invalid values fall back to default). */
    public static String getTheme(Context context) {
        String theme = prefs(context).getString(KEY_THEME, DEFAULT_THEME);
        return isValidTheme(theme) ? theme : DEFAULT_THEME;
    }

    /** Persists the theme choice (ignored unless the code is a supported theme). */
    public static void saveTheme(Context context, String theme) {
        if (isValidTheme(theme)) {
            prefs(context).edit().putString(KEY_THEME, theme).apply();
        }
    }

    public static boolean isValidTheme(String theme) {
        for (String t : SUPPORTED_THEMES) {
            if (t.equals(theme)) {
                return true;
            }
        }
        return false;
    }

    /**
     * CSS font-family name of the bundled @font-face for the given font code.
     * These families are declared locally in assets/index.html and load from
     * assets/fonts/ — no internet access needed.
     */
    public static String arabicFontFamily(String code) {
        if (FONT_SCHEHERAZADE.equals(code)) return "ScheherazadeNewLocal";
        if (FONT_LATEEF.equals(code)) return "LateefLocal";
        if (FONT_NOTO_NASKH.equals(code)) return "NotoNaskhLocal";
        return "AmiriLocal";
    }

    /** User-visible font name (proper noun — not translated). */
    public static String arabicFontLabel(String code) {
        if (FONT_SCHEHERAZADE.equals(code)) return "Scheherazade New";
        if (FONT_LATEEF.equals(code)) return "Lateef";
        if (FONT_NOTO_NASKH.equals(code)) return "Noto Naskh Arabic";
        return "Amiri";
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }
}