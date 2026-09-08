package com.darood.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Vibrator;
import android.text.format.DateFormat;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

public class MainActivity extends Activity implements HijriCoordinator.HijriRefreshListener {

    private WebView webView;
    private final DuroodAudioPlayer duroodAudioPlayer = new DuroodAudioPlayer(this::onDuroodAudioState);
    private boolean pronunciationResumed;

    /** Request code for LanguagePickerActivity opened from Settings (for a result). */
    private static final int REQUEST_CODE_LANGUAGE = 1001;
    /** Request code for the Android 13+ POST_NOTIFICATIONS runtime permission. */
    private static final int REQUEST_CODE_NOTIFICATIONS_PERMISSION = 1002;
    /** Request code for the optional location permission used by the Hijri date. */
    private static final int REQUEST_CODE_LOCATION = 1003;

    @Override
    protected void attachBaseContext(Context base) {
        // Apply the user's saved in-app language to every resource lookup of this
        // Activity (persisted in SharedPreferences; survives restarts & process death).
        super.attachBaseContext(LanguageManager.applyLanguage(base));
    }

    // Note: onCreate intentionally uses deprecated WebSettings file-access APIs (API 30+)
    // so the bundled @font-face Arabic fonts can load via file:// URLs.
    @SuppressWarnings("deprecation")
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLogger.i("MainActivity", "MainActivity created");

        // First launch: setup is handled inside the WebView (index.html shows a
        // guided setup screen when no language has been chosen yet). This keeps
        // language + font + size + theme together in one flow.
        // Full screen / immersive
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );

        // Status bar color
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            getWindow().setStatusBarColor(0xFF1A3A2A); // Dark green
        }

        webView = new WebView(this);
        setContentView(webView);

        // WebView Settings
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        settings.setTextZoom(100);

        // Font Loading: deprecated since API 30, intentionally required so the bundled
        // @font-face Arabic fonts can load via file:// URLs — no network access needed.
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // WebViewClient - handle links
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(intent);
                    return true;
                }
                if (url.startsWith("mailto:")) {
                    Intent intent = new Intent(Intent.ACTION_SENDTO);
                    intent.setData(Uri.parse(url));
                    intent.putExtra(Intent.EXTRA_SUBJECT, String.format(
                            getString(R.string.email_support_subject), getString(R.string.app_name)));
                    try {
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        AppLogger.w("MainActivity", "No email app available", e);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.error_no_email_app), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                if (url.startsWith("intent:")) {
                    try {
                        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                        if (intent != null) {
                            PackageManager packageManager = getPackageManager();
                            if (intent.resolveActivity(packageManager) != null) {
                                startActivity(intent);
                            } else {
                                // Fallback: try to open in browser if target app not installed
                                String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                                if (fallbackUrl != null) {
                                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
                                    startActivity(browserIntent);
                                }
                            }
                        }
                    } catch (Exception e) {
                        AppLogger.w("MainActivity", "Intent handling failed (target app may be missing)", e);
                                Toast.makeText(MainActivity.this,
                                        getString(R.string.whatsapp_not_installed), Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false;
            }
        });

        // WebChromeClient
        webView.setWebChromeClient(new WebChromeClient());

        // JavaScript Interface for Android features
        webView.addJavascriptInterface(new AndroidBridge(), "Android");

        // Load the app (UI strings come from res/values*/strings.xml  -  en, bn, ur)
        loadLocalizedContent();
    }

    /** Result from LanguagePickerActivity opened for a language change (Settings flow). */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_LANGUAGE && resultCode == RESULT_OK) {
            recreate(); // rebuild the complete UI with the newly selected language
        }
    }

    // ====== LOCALIZATION PIPELINE ======
    // Every localized string key referenced by assets/index.html or native code.
    // Arabic religious content is NOT part of this list  -  it stays untouched in the HTML.
    private static final int[] LOCALIZED_STRING_IDS = {
            R.string.app_name,
            R.string.home_tagline,
            R.string.html_lang_code,
            R.string.html_dir,
            R.string.tab_duroods,
            R.string.tab_salam,
            R.string.tab_tasbih,
            R.string.tab_fazilat,
            R.string.search_durood_hint,
            R.string.search_salam_hint,
            R.string.empty_duroods,
            R.string.empty_salams,
            R.string.label_transliteration,
            R.string.label_meaning,
            R.string.meaning_prefix,
            R.string.reference_prefix,
            R.string.btn_read,
            R.string.audio_play,
            R.string.audio_stop,
            R.string.read_saved,
            R.string.read_failed,
            R.string.activity_loading,
            R.string.activity_load_failed,
            R.string.share_failed,
            R.string.btn_copy,
            R.string.btn_copied,
            R.string.btn_share,
            R.string.counter_title,
            R.string.counter_select_target,
            R.string.btn_recited,
            R.string.btn_reset,
            R.string.target_times_format,
            R.string.target_progress_format,
            R.string.toast_target_done,
            R.string.salam_counter_title,
            R.string.counter_select_durood,
            R.string.counter_select_salam,
            R.string.amal_title,
            R.string.amal_item_1,
            R.string.amal_item_2,
            R.string.amal_item_3,
            R.string.fazilat_section_title,
            R.string.settings_title,
            R.string.settings_section_preferences,            R.string.settings_section_about,
            R.string.settings_arabic_font,
            R.string.settings_arabic_font_size,
            R.string.settings_app_version,
            R.string.contact_email,
            R.string.contact_whatsapp,
            R.string.email_support_subject,
            R.string.toast_coming_soon,
            R.string.language_title,
            R.string.theme_title,
            R.string.notifications_title,
            R.string.lang_name_en,
            R.string.lang_name_bn,
            R.string.lang_name_ur,
            R.string.app_version,
            R.string.theme_default,
            R.string.theme_green,
            R.string.theme_blue,
            R.string.theme_brown,
            R.string.theme_purple,
            R.string.theme_dark,
            R.string.notification_settings_title,
            R.string.notifications_enable_label,
            R.string.notifications_schedule_label,
            R.string.day_sunday,
            R.string.day_monday,
            R.string.day_tuesday,
            R.string.day_wednesday,
            R.string.day_thursday,
            R.string.day_friday,
            R.string.day_saturday,
            R.string.notification_save,
            R.string.time_am,
            R.string.time_pm,
            R.string.about_title,
            R.string.about_body,
            R.string.about_credit_title,
            R.string.about_credit_1,
            R.string.about_credit_2,
            R.string.hadith_books_title,
            R.string.book_bukhari,
            R.string.book_muslim,
            R.string.book_tirmidhi,
            R.string.book_abu_dawud,
            R.string.book_ibn_majah,
            R.string.book_ahmad,
            R.string.book_nasai,
            R.string.book_bayhaqi,
            R.string.book_tabarani,
            R.string.book_malik,
            R.string.purpose_title,
            R.string.purpose_body,
            R.string.contact_title,
            R.string.privacy_policy,
            R.string.footer_text,
            R.string.toast_copied,
            R.string.toast_share_copied,
            R.string.local_digits,
            R.string.setup_title,
            R.string.setup_done,
            R.string.menu_title,
            R.string.my_activity_title,
            R.string.about_the_app_title,
            R.string.about_us_title,
            R.string.contact_us_title,
            R.string.developed_by,
            R.string.my_activity_placeholder,
            R.string.crash_report_consent_title,
            R.string.crash_report_consent_desc,
            R.string.log_generated,
            R.string.share_log,
            R.string.log_generate_failed,
            R.string.log_share_failed,
            R.string.activity_durood_label,
            R.string.activity_salam_label,
            R.string.activity_empty,
            R.string.activity_today,
            R.string.activity_yesterday,
            R.string.activity_total,
            // ===== Location-aware Hijri date =====
            R.string.hijri_header_loading,
            R.string.hijri_ah,
            R.string.hijri_month_1,
            R.string.hijri_month_2,
            R.string.hijri_month_3,
            R.string.hijri_month_4,
            R.string.hijri_month_5,
            R.string.hijri_month_6,
            R.string.hijri_month_7,
            R.string.hijri_month_8,
            R.string.hijri_month_9,
            R.string.hijri_month_10,
            R.string.hijri_month_11,
            R.string.hijri_month_12,
            R.string.hijri_settings_title,
            R.string.hijri_settings_auto,
            R.string.hijri_current_title,
            R.string.hijri_sunset_format,
            R.string.hijri_overrides_title,
            R.string.hijri_add_override,
            R.string.hijri_overrides_empty,
            R.string.hijri_gregorian_date_label,
            R.string.hijri_hijri_date_label,
            R.string.hijri_day_label,
            R.string.hijri_month_label,
            R.string.hijri_year_label,
            R.string.hijri_save,
            R.string.hijri_edit_override,
            R.string.hijri_delete,
            R.string.hijri_after_sunset_note,
            R.string.hijri_saved_toast,
            R.string.hijri_deleted_toast,
            R.string.hijri_invalid_gregorian_toast,
            R.string.hijri_invalid_hijri_toast,
            R.string.hijri_delete_confirm,
            R.string.hijri_source_manual,
            R.string.hijri_source_api,
            R.string.hijri_source_cache,
            R.string.hijri_source_fallback,
            R.string.hijri_location_note
    };

    /** Loads assets/index.html with localized UI strings and passes it to the WebView. */
    private void loadLocalizedContent() {
        android.util.Log.i("DaroodApp", "Applying UI language: " + getString(R.string.html_lang_code)
                + " dir=" + getString(R.string.html_dir));
        String html = buildLocalizedHtml();
        if (html != null) {
            webView.loadDataWithBaseURL(
                    "file:///android_asset/index.html", html, "text/html", "utf-8", null);
        } else {
            // Fallback: raw asset if it cannot be read.
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    /**
     * Reads the HTML asset and localizes its UI strings:
     * 1) replaces {{key}} placeholders in the static markup (HTML-escaped values);
     * 2) injects <script>window.APP_STRINGS={...}</script> for JS-generated strings.
     * Arabic religious content in the file is never modified.
     */
    private String buildLocalizedHtml() {
        String html = readAsset("index.html");
        if (html == null) return null;

        java.util.Map<String, String> values = new java.util.LinkedHashMap<>();
        for (int id : LOCALIZED_STRING_IDS) {
            values.put(getResources().getResourceEntryName(id), getString(id));
        }

        // Formatted values that need arguments.
        values.put("email_support_subject", String.format(
                values.get("email_support_subject"), values.get("app_name")));
        String targetFmt = values.get("target_times_format");
        values.put("target_10", String.format(targetFmt, localizeNumber(10)));
        values.put("target_33", String.format(targetFmt, localizeNumber(33)));
        values.put("target_100", String.format(targetFmt, localizeNumber(100)));
        values.put("target_300", String.format(targetFmt, localizeNumber(300)));

        // 1) {{key}} placeholders in the static markup.
        for (java.util.Map.Entry<String, String> entry : values.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}",
                    android.text.TextUtils.htmlEncode(entry.getValue()));
        }

        // 2) Inject all strings as JSON for the page's JavaScript (STR()/fmt() helpers).
        String json = new org.json.JSONObject(values).toString();
        html = html.replace("<!--APP_STRINGS-->",
                "<script>window.APP_STRINGS=" + json + ";</script>");

        // 2b) Inject religious content data inline (en/ur). Inline is more reliable
        //     than <script src> inside loadDataWithBaseURL on some WebView versions.
        StringBuilder dataScripts = new StringBuilder();
        dataScripts.append("<script>window.religiousContentEn={darood:[],salam:[],intro:[]};window.religiousContentUr={darood:[],salam:[],intro:[]};</script>");
        String[] dataFiles = {"en-darood.js", "en-salam.js", "en-intro.js", "ur-darood.js", "ur-salam.js", "ur-intro.js"};
        for (String file : dataFiles) {
            String content = readAsset("data/" + file);
            if (content != null) {
                if (!content.isEmpty() && content.charAt(0) == '\uFEFF') {
                    content = content.substring(1);
                }
                dataScripts.append("<script>").append(content).append("</script>");
            }
        }
        html = html.replace("<!--RELIGIOUS_DATA-->", dataScripts.toString());

        // 3) Selected Arabic font: CSS variable (applies at parse time — no flash)
        //    plus a JS settings object for the font picker UI. Fonts are bundled
        //    under assets/fonts/ and loaded via local @font-face rules.
        String fontCode = AppSettings.getArabicFont(this);
        int fontSizeSp = AppSettings.getArabicFontSize(this);
        // Respect the system font scale (accessibility): sp -> px for the WebView.
        float fontScale = getResources().getConfiguration().fontScale;
        int fontSizePx = Math.round(fontSizeSp * fontScale);
        String themeCode = AppSettings.getTheme(this);
        html = html.replace("<!--ARABIC_FONT-->", "<style>:root{--arabic-font:'"
                + AppSettings.arabicFontFamily(fontCode) + "';--arabic-font-size:"
                + fontSizePx + "px}</style>");
        try {
            org.json.JSONObject appSettings = new org.json.JSONObject();
            appSettings.put("arabic_font", fontCode);
            appSettings.put("arabic_font_family", AppSettings.arabicFontFamily(fontCode));
            appSettings.put("arabic_font_label", AppSettings.arabicFontLabel(fontCode));
            appSettings.put("arabic_font_size", fontSizeSp);
            appSettings.put("font_scale", fontScale);
            appSettings.put("theme", themeCode);
            appSettings.put("theme_label", themeLabel(themeCode));
            html = html.replace("<!--APP_SETTINGS-->",
                    "<script>window.APP_SETTINGS=" + appSettings + ";</script>");
        } catch (Exception e) {
            AppLogger.w("MainActivity", "Failed to build app settings JSON", e);
        }
        // 4) Selected theme: apply data-theme before the CSS parses (no flash).
        String escapedTheme = themeCode.replace("\"", "").replace("<", "").replace(">", "");
        html = html.replace("<html ", "<html data-theme=\"" + escapedTheme + "\" ");
        return html;
    }

    /** Converts Western digits to the active language's digits (see the local_digits resource). */
    private String localizeNumber(int number) {
        String digits = getString(R.string.local_digits);
        StringBuilder sb = new StringBuilder();
        for (char c : String.valueOf(number).toCharArray()) {
            sb.append(c >= '0' && c <= '9' ? digits.charAt(c - '0') : c);
        }
        return sb.toString();
    }

    /** User-visible theme name in the active language. */
    private String themeLabel(String theme) {
        if (AppSettings.THEME_GREEN.equals(theme)) return getString(R.string.theme_green);
        if (AppSettings.THEME_BLUE.equals(theme)) return getString(R.string.theme_blue);
        if (AppSettings.THEME_BROWN.equals(theme)) return getString(R.string.theme_brown);
        if (AppSettings.THEME_PURPLE.equals(theme)) return getString(R.string.theme_purple);
        if (AppSettings.THEME_DARK.equals(theme)) return getString(R.string.theme_dark);
        return getString(R.string.theme_default);
    }

    private String readAsset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) {
                out.write(buffer, 0, len);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) {
            AppLogger.e("MainActivity", "Failed to read asset: " + name, e);
            return null;
        }
    }

    private void postToWebView(String script) {
        runOnUiThread(() -> {
            if (webView != null && !isFinishing() && !isDestroyed()) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private void onDuroodAudioState(int id, boolean playing, boolean salam) {
        postToWebView("window.__duroodAudioState && window.__duroodAudioState("
                + (salam ? 0 : id) + "," + (playing && !salam) + ");"
                + "window.__salamAudioState && window.__salamAudioState("
                + (salam ? id : 0) + "," + (playing && salam) + ");");
    }

    // Bridge class for JS -> Android communication
    public class AndroidBridge {
        @JavascriptInterface
        public boolean hasSalamAudio(int id) {
            return DuroodAudioPlayer.hasSalamAudio(id);
        }

        @JavascriptInterface
        public void playSalamPronunciation(int id) {
            runOnUiThread(() -> {
                AppLogger.i("DuroodAudio", "Pronunciation requested; Salam ID=" + id);
                if (pronunciationResumed && !isFinishing() && !isDestroyed()) {
                    duroodAudioPlayer.toggleSalam(getApplicationContext(), id);
                }
            });
        }

        @JavascriptInterface
        public boolean hasDuroodAudio(int id) {
            return DuroodAudioPlayer.hasAudio(id);
        }

        @JavascriptInterface
        public void playDuroodPronunciation(int id) {
            runOnUiThread(() -> {
                AppLogger.i("DuroodAudio", "Pronunciation requested; Durood ID=" + id);
                if (pronunciationResumed && !isFinishing() && !isDestroyed()) {
                    duroodAudioPlayer.toggle(getApplicationContext(), id);
                }
            });
        }

        @JavascriptInterface
        public void requestDuroodAudioState() {
            runOnUiThread(() -> duroodAudioPlayer.publishState());
        }

        @JavascriptInterface
        public void stopDuroodPronunciation() {
            runOnUiThread(() -> duroodAudioPlayer.stop());
        }

        @JavascriptInterface
        public void vibrate(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (v != null && v.hasVibrator()) {
                    if (android.os.Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(android.os.VibrationEffect.createOneShot(
                                Math.max(0, ms),
                                android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        // Legacy path required for minSdk 21–25 (API < 26).
                        v.vibrate(ms);
                    }
                }
            } catch (Exception e) {
                AppLogger.e("MainActivity", "Vibration failed", e);
            }
        }

        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
        }

        @JavascriptInterface
        public void share(String text) {
            runOnUiThread(() -> {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("text/plain");
                shareIntent.putExtra(Intent.EXTRA_TEXT, text);
                try {
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)));
                } catch (ActivityNotFoundException | SecurityException e) {
                    AppLogger.e("MainActivity", "Share sheet failed", e);
                    Toast.makeText(MainActivity.this, R.string.share_failed, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void copyToClipboard(String text) {
            runOnUiThread(() -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.HONEYCOMB) {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    android.content.ClipData clip =
                            android.content.ClipData.newPlainText(getString(R.string.app_name), text);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.toast_copied), Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        /**
         * Underlying mechanism for the future Settings language screen:
         * saves the chosen language and rebuilds the whole UI in it.
         * Call from JS: Android.changeLanguage('en' | 'bn' | 'ur')
         */
        @JavascriptInterface
        public void changeLanguage(String code) {
            if (code == null || !LanguageManager.isSupported(code)) {
                AppLogger.w("MainActivity", "Ignoring unsupported language code: " + code);
                return;
            }
            AppLogger.i("MainActivity", "Language change requested: " + code);
            runOnUiThread(() -> {
                LanguageManager.saveLanguage(MainActivity.this, code);
                recreate(); // reloads every localized resource + the WebView HTML
            });
        }

        /**
         * Settings → Theme: persists the selected theme. The WebView applies it
         * instantly via the data-theme attribute; persistence makes the choice
         * survive restarts and language changes.
         */
        @JavascriptInterface
        public void setTheme(String theme) {
            AppLogger.i("MainActivity", "Theme change: " + theme);
            AppSettings.saveTheme(MainActivity.this, theme);
        }

        /**
         * Settings → Arabic Font: persists the selected bundled Arabic font.
         * The WebView applies it instantly via the --arabic-font CSS variable;
         * persistence makes the choice survive restarts and language changes.
         */
        @JavascriptInterface
        public void setArabicFont(String code) {
            AppLogger.i("MainActivity", "Arabic font change: " + code);
            AppSettings.saveArabicFont(MainActivity.this, code);
        }

        /**
         * Settings → Arabic Font Size: persists the selected size (16–32sp).
         * The WebView applies it instantly via the --arabic-font-size CSS variable;
         * persistence makes the choice survive restarts and language changes.
         */
        @JavascriptInterface
        public void setArabicFontSize(int sizeSp) {
            AppLogger.i("MainActivity", "Arabic font size change: " + sizeSp);
            AppSettings.saveArabicFontSize(MainActivity.this, sizeSp);
        }

        /**
         * Settings → Language: opens the existing language picker in for-result
         * mode; onActivityResult recreates the UI with the newly selected language.
         */
        @JavascriptInterface
        public void openLanguageSelector() {
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, LanguagePickerActivity.class);
                intent.putExtra(LanguagePickerActivity.EXTRA_FROM_SETTINGS, true);
                startActivityForResult(intent, REQUEST_CODE_LANGUAGE);
            });
        }

        /** True once the user completed the first-launch setup (separate persisted flag). */
        @JavascriptInterface
        public boolean isSetupComplete() {
            return AppSettings.isSetupComplete(MainActivity.this);
        }

        /** Marks the first-launch setup as completed (persisted). */
        @JavascriptInterface
        public void completeSetup() {
            AppSettings.saveSetupCompleted(MainActivity.this, true);
            AppLogger.i("MainActivity", "First-launch setup completed");
        }

        /** Generates a diagnostic log file; returns its file name, or "" on failure. */
        @JavascriptInterface
        public String generateDiagnosticLog() {
            try {
                java.io.File f = LogFileManager.writeDiagnosticLog(MainActivity.this);
                AppLogger.i("MainActivity", "Diagnostic log requested from About page");
                return f != null ? f.getName() : "";
            } catch (Throwable t) {
                AppLogger.e("MainActivity", "generateDiagnosticLog failed", t);
                return "";
            }
        }

        /** Shares a previously generated log file via the system share sheet. */
        @JavascriptInterface
        public void shareLog(final String fileName) {
            runOnUiThread(() -> {
                try {
                    java.io.File file = LogFileManager.resolve(MainActivity.this, fileName);
                    if (file == null || !file.exists()) {
                        AppLogger.e("MainActivity", "shareLog: file not found: " + fileName);
                        Toast.makeText(MainActivity.this,
                                getString(R.string.log_share_failed), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this,
                            getPackageName() + ".fileprovider", file);
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, getString(R.string.share_log)));
                    AppLogger.i("MainActivity", "Log share sheet opened: " + fileName);
                } catch (Throwable t) {
                    AppLogger.e("MainActivity", "shareLog failed", t);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.log_share_failed), Toast.LENGTH_SHORT).show();
                }
            });
        }

        /** Returns whether the user opted in to email crash reports. */
        @JavascriptInterface
        public boolean getCrashReportConsent() {
            return AppSettings.getCrashReportConsent(MainActivity.this);
        }

        /** Persists the crash-report email consent. */
        @JavascriptInterface
        public void setCrashReportConsent(boolean consent) {
            AppSettings.saveCrashReportConsent(MainActivity.this, consent);
            AppLogger.i("MainActivity", "Crash report consent set to " + consent);
        }

        /** Records one read of a Durood/Salam in the My Activity database (async). */
        @JavascriptInterface
        public void recordActivity(final String type, final int contentId) {
            try {
                ActivityRepository.get(MainActivity.this).record(type, contentId);
            } catch (Throwable t) {
                AppLogger.e("MainActivity", "recordActivity failed", t);
            }
        }

        @JavascriptInterface
        public void readDurood(int contentId) {
            AppLogger.i("MainActivity", "Read button pressed: durood #" + contentId);
            try {
                ActivityRepository.get(getApplicationContext()).record(
                        ActivityRepository.TYPE_DUROOD, contentId, saved -> runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                Toast.makeText(MainActivity.this, saved ? R.string.read_saved
                                        : R.string.read_failed, Toast.LENGTH_SHORT).show();
                            }
                        }));
            } catch (Exception e) {
                AppLogger.e("MainActivity", "Read DB request failed: durood #" + contentId, e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(MainActivity.this,
                            R.string.read_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }

        @JavascriptInterface
        public void readSalam(int contentId) {
            AppLogger.i("MainActivity", "Read button pressed: salam #" + contentId);
            try {
                ActivityRepository.get(getApplicationContext()).record(
                        ActivityRepository.TYPE_SALAM, contentId, saved -> runOnUiThread(() -> {
                            if (!isFinishing() && !isDestroyed()) {
                                Toast.makeText(MainActivity.this, saved ? R.string.read_saved
                                        : R.string.read_failed, Toast.LENGTH_SHORT).show();
                            }
                        }));
            } catch (Exception e) {
                AppLogger.e("MainActivity", "Read DB request failed: salam #" + contentId, e);
                runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) Toast.makeText(MainActivity.this,
                            R.string.read_failed, Toast.LENGTH_SHORT).show();
                });
            }
        }

        /** Every opening queries Room after all previously queued increments. */
        @JavascriptInterface
        public void loadActivity() {
            AppLogger.i("MainActivity", "My Activity opened; requesting fresh DB data");
            try {
                ActivityRepository.get(getApplicationContext()).load(records -> {
                    if (records == null) {
                        postToWebView("window.__activityLoadFailed && window.__activityLoadFailed();");
                    } else {
                        postToWebView("window.__activityLoaded && window.__activityLoaded("
                                + activityToJson(records) + ");");
                    }
                });
            } catch (Exception e) {
                AppLogger.e("MainActivity", "loadActivity failed", e);
                postToWebView("window.__activityLoadFailed && window.__activityLoadFailed();");
            }
        }
        private String activityToJson(java.util.List<ActivityRecord> records) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < records.size(); i++) {
                ActivityRecord r = records.get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"type\":\"").append(r.type)
                        .append("\",\"contentId\":").append(r.contentId)
                        .append(",\"date\":\"").append(r.date)
                        .append("\",\"count\":").append(r.count).append('}');
            }
            sb.append(']');
            return sb.toString();
        }

        /**
         * Settings → Notifications: returns the persisted schedule config as JSON
         * for the settings UI: {"enabled":bool,"days":{"<1..7>":{"h":..,"m":..}}}.
         */
        @JavascriptInterface
        public String getNotificationConfig() {
            return NotificationScheduler.getConfig(MainActivity.this).toString();
        }

        /**
         * Settings → Notifications: persists the schedule, cancels old alarms and
         * schedules fresh ones. On Android 13+ the POST_NOTIFICATIONS runtime
         * permission is requested here if it hasn't been granted yet; scheduling
         * resumes automatically once the user grants it.
         */
        @JavascriptInterface
        public void saveNotifications(final String json) {
            runOnUiThread(() -> {
                if (!NotificationScheduler.saveConfig(MainActivity.this, json)) {
                    AppLogger.w("MainActivity", "Failed to save notification config");
                    return;
                }
                AppLogger.i("MainActivity", "Notification config saved");
                if (NotificationScheduler.hasNotificationPermission(MainActivity.this)) {
                    NotificationScheduler.rescheduleAll(MainActivity.this);
                    boolean enabled = NotificationScheduler.isEnabled(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            getString(enabled
                                            ? R.string.notifications_saved
                                            : R.string.notifications_disabled),
                            Toast.LENGTH_SHORT).show();
                } else if (android.os.Build.VERSION.SDK_INT >= 33) {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.notifications_permission_required),
                            Toast.LENGTH_SHORT).show();
                    requestPermissions(
                            new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                            REQUEST_CODE_NOTIFICATIONS_PERMISSION);
                } else {
                    NotificationScheduler.rescheduleAll(MainActivity.this);
                    Toast.makeText(MainActivity.this,
                            getString(R.string.notifications_saved),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        /**
         * Settings → Notifications: opens the platform time picker for a weekday.
         * The chosen time is delivered back to the WebView via
         * window.__notificationTimePicked(day, hour, minute).
         */
        @JavascriptInterface
        public void openTimePicker(final int day, final int index, final int hour, final int minute) {
            runOnUiThread(() -> {
                TimePickerDialog dialog = new TimePickerDialog(MainActivity.this,
                        (view, pickerHour, pickerMinute) -> {
                            final String js = "window.__notificationTimePicked("
                                    + day + "," + index + "," + pickerHour + "," + pickerMinute + ")";
                            webView.post(() -> webView.evaluateJavascript(js, null));
                        },
                        hour, minute, DateFormat.is24HourFormat(MainActivity.this));
                dialog.show();
            });
        }

        /**
         * Home header / Settings → Hijri Date: returns the current snapshot
         * (manual → cached API → fallback) synchronously for first paint.
         * The async path refreshes it via refreshHijriDate().
         */
        @JavascriptInterface
        public String getHijriDateSnapshot() {
            return HijriCoordinator.get().snapshotJson(MainActivity.this);
        }

        /** Home header / Settings → Hijri Date: full foreground refresh. */
        @JavascriptInterface
        public void refreshHijriDate() {
            HijriCoordinator.get().refresh(MainActivity.this);
        }

        /** Settings → Hijri Date: loads the manual-override list (async push). */
        @JavascriptInterface
        public void loadHijriOverrides() {
            HijriCoordinator.get().loadOverrides(MainActivity.this);
        }

        /**
         * Settings → Hijri Date: saves a manual override.
         * @param json {"gregorianDate":"yyyy-MM-dd","hijriDay":n,"hijriMonth":n,"hijriYear":n}
         * @return "ok" or "invalid"
         */
        @JavascriptInterface
        public String saveHijriOverride(String json) {
            return HijriCoordinator.get().saveOverride(MainActivity.this, json);
        }

        /**
         * Settings → Hijri Date: deletes a manual override.
         * @return "ok" or "invalid"
         */
        @JavascriptInterface
        public String deleteHijriOverride(String gregorianDate) {
            return HijriCoordinator.get().deleteOverride(MainActivity.this, gregorianDate);
        }

        /**
         * Settings → Hijri Date: opens the platform date picker. The chosen date
         * is delivered back via window.__hijriDatePicked(year, month0, day).
         */
        @JavascriptInterface
        public void openHijriDatePicker(final int day, final int month0, final int year) {
            runOnUiThread(() -> {
                DatePickerDialog dialog = new DatePickerDialog(MainActivity.this,
                        (view, pickedYear, pickedMonth, pickedDay) -> {
                            final String js = "window.__hijriDatePicked("
                                    + pickedYear + "," + pickedMonth + "," + pickedDay + ")";
                            webView.post(() -> webView.evaluateJavascript(js, null));
                        },
                        year, month0, day);
                dialog.show();
            });
        }
    }

    /** Continues saving after the POST_NOTIFICATIONS runtime permission result. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_LOCATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            AppLogger.i("MainActivity", "Location permission result: granted=" + granted);
            // Whether granted or denied the app remains fully usable: location
            // simply upgrades the automatic date, cache/fallback handle the rest.
            HijriCoordinator.get().refresh(this);
            return;
        }
        if (requestCode != REQUEST_CODE_NOTIFICATIONS_PERMISSION) {
            return;
        }
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted) {
            NotificationScheduler.rescheduleAll(this);
            Toast.makeText(this, getString(R.string.notifications_saved), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, getString(R.string.notifications_permission_denied),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // Back button handling
    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        pronunciationResumed = true;
        webView.onResume();
        // Hijri date: foreground verification + push updates from background work.
        HijriCoordinator.setForegroundListener(this);
        maybeRefreshHijriDate();
    }

    @Override
    protected void onPause() {
        pronunciationResumed = false;
        duroodAudioPlayer.stop();
        super.onPause();
        HijriCoordinator.setForegroundListener(null);
        webView.onPause();
    }

    /**
     * Foreground step for the location-aware Hijri date: on first run the
     * (optional) location permission is requested; afterwards a full refresh
     * (location → sunset → manual/API/cache/fallback → schedule) runs.
     */
    private void maybeRefreshHijriDate() {
        if (!HijriCoordinator.hasLocationPermission(this)
                && !AppSettings.isHijriLocationPermissionAsked(this)) {
            AppSettings.setHijriLocationPermissionAsked(this, true);
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION
                }, REQUEST_CODE_LOCATION);
                return; // onRequestPermissionsResult triggers the refresh
            }
        }
        HijriCoordinator.get().refresh(this);
    }

    // ===== HijriCoordinator.HijriRefreshListener =====

    /** Pushes a fresh Hijri date snapshot into the WebView (any thread). */
    @Override
    public void onHijriDateUpdated(String snapshotJson) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.__hijriDateUpdated && window.__hijriDateUpdated("
                                + snapshotJson + ");", null);
            }
        });
    }

    /** Pushes the manual-override list into the WebView (any thread). */
    @Override
    public void onHijriOverridesLoaded(String overridesJson) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(
                        "window.__hijriOverridesLoaded && window.__hijriOverridesLoaded("
                                + overridesJson + ");", null);
            }
        });
    }

    @Override
    protected void onDestroy() {
        pronunciationResumed = false;
        duroodAudioPlayer.stop();
        AppLogger.i("MainActivity", "MainActivity destroyed");
        if (webView != null) {
            webView.destroy();
            AppLogger.d("MainActivity", "WebView released");
        }
        super.onDestroy();
    }
}

