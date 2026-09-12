package com.darood.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

/** Lightweight localized alarm surface. Home keeps a dismissible notification; Back dismisses. */
public class ReminderAlarmActivity extends Activity {
    private String occurrence;
    private boolean bound, resumed;
    private ReminderAlarmService controller;
    private final android.content.ServiceConnection connection = new android.content.ServiceConnection() {
        @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            controller = ((ReminderAlarmService.AlarmBinder) binder).service();
            if (resumed) controller.surfaceVisible(occurrence);
        }
        @Override public void onServiceDisconnected(android.content.ComponentName name) { controller = null; }
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refresh(); }
    };

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(LanguageManager.applyLanguage(base));
    }

    @SuppressWarnings("deprecation") // Window flags are the supported equivalent on API 21–26 only.
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppLogger.i("ReminderAlarm", "Alarm Activity created");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        // No keyguard dismissal or unlock request is made.
        ContextCompat.registerReceiver(this, changes, new IntentFilter(ReminderAlarm.ACTION_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::dismiss);
        }
        occurrence = getIntent().getStringExtra(ReminderAlarm.EXTRA_OCCURRENCE);
        if (occurrence == null || !occurrence.equals(ReminderAlarm.active(this))) { finish(); return; }
        showContent();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Old PendingIntents cannot dismiss or replace a newer active occurrence.
        refresh();
    }

    private void refresh() {
        String active = ReminderAlarm.active(this);
        if (active == null) { finish(); return; }
        occurrence = active;
        showContent();
        if (resumed && controller != null) controller.surfaceVisible(occurrence);
    }

    private void showContent() {
        int background = Color.rgb(255, 248, 232);
        int primary = Color.rgb(26, 58, 42);
        switch (AppSettings.getTheme(this)) {
            case "green": primary = Color.rgb(20, 83, 45); break;
            case "blue": primary = Color.rgb(30, 58, 95); break;
            case "brown": primary = Color.rgb(78, 52, 46); break;
            case "purple": primary = Color.rgb(74, 35, 90); break;
            case "dark": background = Color.rgb(20, 28, 24); primary = Color.rgb(232, 220, 183); break;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(background);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        content.setPadding(dp(28), dp(48), dp(28), dp(48));
        content.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(dp(28) + safe.left, dp(48) + safe.top, dp(28) + safe.right, dp(48) + safe.bottom);
            }
            return insets;
        });
        content.addView(text(getString(ReminderAlarm.title(occurrence)), 30, primary));
        TextView clock = text("", 44, primary);
        content.addView(clock);
        content.addView(text(getString(ReminderAlarm.message(occurrence)), 24, primary));
        content.addView(text(getString(ReminderAlarm.mode(this).label()), 18, primary));
        Button dismiss = new Button(this);
        dismiss.setText(R.string.reminder_dismiss);
        dismiss.setTextSize(22);
        dismiss.setMinHeight(dp(64));
        dismiss.setTextColor(Color.rgb(26, 58, 42));
        dismiss.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(225, 199, 121)));
        dismiss.setOnClickListener(v -> dismiss());
        content.addView(dismiss, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -1));
        setContentView(scroll);
        handler.removeCallbacksAndMessages(null);
        handler.post(new Runnable() {
            @Override public void run() {
                if (ReminderAlarm.active(ReminderAlarmActivity.this) == null) { finish(); return; }
                clock.setText(android.text.format.DateFormat.getTimeFormat(ReminderAlarmActivity.this)
                        .format(new java.util.Date()));
                handler.postDelayed(this, 1000);
            }
        });
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setGravity(Gravity.CENTER); view.setPadding(0, dp(12), 0, dp(12));
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onStart() {
        super.onStart();
        if (occurrence != null && occurrence.equals(ReminderAlarm.active(this))) {
            bound = bindService(new Intent(this, ReminderAlarmService.class), connection, Context.BIND_AUTO_CREATE);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (occurrence == null || !occurrence.equals(ReminderAlarm.active(this))) { refresh(); return; }
        if (controller != null) controller.surfaceVisible(occurrence);
        AppLogger.i("ReminderAlarm", "Alarm Activity visible " + occurrence);
    }

    @Override protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override protected void onStop() {
        if (controller != null) controller.surfaceHidden();
        if (bound) { unbindService(connection); bound = false; }
        controller = null;
        super.onStop();
    }

    private void dismiss() {
        AppLogger.i("ReminderAlarm", "Dismiss pressed");
        ReminderAlarm.dismiss(this, occurrence);
        finish();
    }

    @Override public void onBackPressed() { dismiss(); }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterReceiver(changes);
        if (isFinishing() && occurrence != null) ReminderAlarm.dismiss(this, occurrence);
        super.onDestroy();
    }
}
