package com.darood.app;

import android.app.AlarmManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import androidx.core.app.NotificationManagerCompat;

/** One controller: foreground in background, bound while the alarm surface is visible. */
public class ReminderAlarmService extends Service {
    private static ReminderAlarmService running;
    private MediaPlayer player;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private String occurrence;
    private boolean visible, foreground, shortFallback;

    final class AlarmBinder extends Binder {
        ReminderAlarmService service() { return ReminderAlarmService.this; }
    }
    static boolean isRunning(String expected) {
        return running != null && expected.equals(running.occurrence);
    }
    @Override public IBinder onBind(Intent intent) {
        begin(ReminderAlarm.active(this));
        return new AlarmBinder();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String active = ReminderAlarm.active(this);
        String requested = intent == null ? active : intent.getStringExtra(ReminderAlarm.EXTRA_OCCURRENCE);
        try {
            // Fulfil every startForegroundService request, including a start raced by Dismiss.
            promote(active == null ? "expired" : active, !visible && !isRunning(active == null ? "" : active));
            if (active == null || !ReminderAlarm.canNotify(this)) {
                cleanup(); stopForeground(true); foreground = false; stopSelf();
                return START_NOT_STICKY;
            }
            if (!active.equals(requested)) AppLogger.i("ReminderAlarm", "Stale service start ignored " + requested);
            begin(active);
            if (visible) removeTemporaryNotification();
        } catch (RuntimeException e) {
            AppLogger.w("ReminderAlarm", "Foreground controller restricted; full-screen notification fallback", e);
            // Clear the started-FGS contract even when an already-visible bound surface remains.
            stopForeground(true); foreground = false; stopSelf();
            if (visible && active != null) {
                begin(active);
                removeTemporaryNotification();
            } else {
                cleanup();
                if (active != null) ReminderAlarm.postFallback(this, active);
            }
        }
        return shortFallback ? START_NOT_STICKY : START_STICKY;
    }
    private void promote(String active, boolean launch) {
        if (Build.VERSION.SDK_INT >= 34) {
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            shortFallback = alarms == null || !alarms.canScheduleExactAlarms();
            // Documented type for continuing exact alarms, including haptics-only alarms.
            int type = shortFallback ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
                    : ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED;
            startForeground(ReminderAlarm.NOTIFICATION_ID, ReminderAlarm.notification(this, active, launch), type);
        } else startForeground(ReminderAlarm.NOTIFICATION_ID, ReminderAlarm.notification(this, active, launch));
        foreground = true;
        AppLogger.i("ReminderAlarm", "Alarm notification posted " + active + " fullScreenRequested=" + launch
                + " shortFallback=" + shortFallback);
    }
    private void begin(String next) {
        if (next == null || next.equals(occurrence)) return;
        cleanup(); occurrence = next; running = this;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":reminder");
            // Held only by an active alarm; Dismiss/controller destruction releases it.
            wakeLock.acquire();
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
            ReminderAlertMode mode = ReminderAlarm.mode(this);
            AppLogger.i("ReminderAlarm", "Controller starting " + next + " mode=" + mode);
            if (mode == ReminderAlertMode.RING) startTone(attributes);
            else if (mode == ReminderAlertMode.VIBRATE) {
                vibrator = Build.VERSION.SDK_INT >= 31
                        ? ((VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE)).getDefaultVibrator()
                        : (Vibrator) getSystemService(VIBRATOR_SERVICE);
                if (vibrator != null && vibrator.hasVibrator()) {
                    long[] pattern = {0, 600, 500};
                    if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), attributes);
                    else vibrator.vibrate(pattern, 0, attributes);
                    AppLogger.i("ReminderAlarm", "Repeating vibration started " + next);
                }
            } else AppLogger.i("ReminderAlarm", "Silent visual alarm " + next);
        } catch (RuntimeException e) {
            AppLogger.w("ReminderAlarm", "Alert resource unavailable; visual alarm remains", e);
            releaseResources();
        }
        sendBroadcast(new Intent(ReminderAlarm.ACTION_CHANGED).setPackage(getPackageName()));
    }
    private void startTone(AudioAttributes attributes) {
        MediaPlayer candidate = new MediaPlayer();
        player = candidate;
        try (AssetFileDescriptor resource = getResources().openRawResourceFd(R.raw.durood_premium_soft_ting_ting)) {
            if (resource == null) throw new IllegalStateException("Bundled alarm WAV unavailable");
            candidate.setAudioAttributes(attributes);
            candidate.setDataSource(resource.getFileDescriptor(), resource.getStartOffset(), resource.getLength());
            candidate.setLooping(true);
            candidate.setOnErrorListener((mp, what, extra) -> {
                AppLogger.w("ReminderAlarm", "Bundled WAV playback failed what=" + what + " extra=" + extra);
                if (player == mp) releasePlayer();
                return true;
            });
            candidate.setOnPreparedListener(mp -> {
                if (player != mp || occurrence == null || !occurrence.equals(ReminderAlarm.active(this))) return;
                try { mp.start(); AppLogger.i("ReminderAlarm", "Bundled WAV loop started " + occurrence); }
                catch (RuntimeException e) { releasePlayer(); AppLogger.w("ReminderAlarm", "WAV start failed; visual alarm remains", e); }
            });
            candidate.prepareAsync();
        } catch (Exception e) {
            releasePlayer();
            AppLogger.w("ReminderAlarm", "WAV resource/prepare failed; visual alarm remains", e);
        }
    }
    /** Called by the bound, resumed Activity after its content has been installed. */
    void surfaceVisible(String expected) {
        if (expected == null || !expected.equals(ReminderAlarm.active(this))) return;
        begin(expected); visible = true; removeTemporaryNotification();
    }
    private void removeTemporaryNotification() {
        if (foreground) stopForeground(true);
        foreground = false;
        NotificationManagerCompat.from(this).cancel(ReminderAlarm.NOTIFICATION_ID);
        AppLogger.i("ReminderAlarm", "Visible bound Activity; temporary notification 6200 removed " + occurrence);
    }
    /** Home/another Activity: Android requires a notification for a continuing background alert. */
    void surfaceHidden() {
        visible = false;
        if (occurrence == null || !occurrence.equals(ReminderAlarm.active(this))) return;
        try {
            // Keep a started service alive after unbind, while the Activity is still bound.
            startService(new Intent(this, ReminderAlarmService.class).putExtra(ReminderAlarm.EXTRA_OCCURRENCE, occurrence));
            promote(occurrence, false); // Leaving the surface must not relaunch it.
        } catch (RuntimeException e) {
            AppLogger.w("ReminderAlarm", "Background continuation denied; legal notification fallback", e);
            String active = occurrence;
            cleanup(); stopForeground(true); foreground = false; stopSelf();
            ReminderAlarm.postFallback(this, active, false);
        }
    }
    static void stopAlert(String expected) {
        ReminderAlarmService service = running;
        if (service == null || (expected != null && !expected.equals(service.occurrence))) return;
        service.cleanup(); service.stopForeground(true); service.foreground = false; service.stopSelf();
    }
    private void releasePlayer() {
        if (player != null) {
            player.setOnPreparedListener(null); player.setOnErrorListener(null);
            player.release(); player = null;
            AppLogger.i("ReminderAlarm", "Bundled WAV stopped/released");
        }
    }
    private void releaseResources() {
        releasePlayer();
        if (vibrator != null) { vibrator.cancel(); vibrator = null; AppLogger.i("ReminderAlarm", "Vibration stopped"); }
        if (wakeLock != null) { if (wakeLock.isHeld()) wakeLock.release(); wakeLock = null; }
    }
    private void cleanup() {
        releaseResources(); occurrence = null;
        if (running == this) running = null;
    }
    @Override public void onTimeout(int startId) {
        // Only Android's denied-exact-access short-service fallback has a time limit.
        if (visible || !shortFallback) return;
        String active = occurrence;
        AppLogger.w("ReminderAlarm", "Android short-service limit; exact access needed for indefinite background alert");
        cleanup(); stopForeground(true); foreground = false; stopSelf();
        if (active != null) ReminderAlarm.postFallback(this, active, false);
    }
    @Override public void onTimeout(int startId, int fgsType) { onTimeout(startId); }
    @Override public void onDestroy() {
        cleanup();
        // OS restarts may resume active state; only Dismiss owns persisted cleanup.
        super.onDestroy();
    }
}
