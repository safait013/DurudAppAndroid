package com.darood.app;

import android.app.Service;
import android.app.AlarmManager;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.PowerManager;
import android.os.Vibrator;
import androidx.core.app.NotificationManagerCompat;

public class ReminderControllerTest {
    static int checks;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;}
    static Intent intent(ReminderAlarmService s,String occurrence){return new Intent(s,ReminderAlarmService.class).putExtra("occurrence",occurrence);}
    public static void main(String[] args){
        ReminderAlarmService s=new ReminderAlarmService();
        ReminderAlarm.active="user:1";
        s.onStartCommand(intent(s,"user:1"),0,1);
        check(MediaPlayer.playing==1&&Service.notification.launch,"receiver service starts sound and requests FSI without Activity/tap");
        check(MediaPlayer.last.looping&&MediaPlayer.last.attributes.usage==4,"looping alarm audio attributes");
        check(android.content.res.Resources.opened==R.raw.durood_premium_soft_ting_ting,"exact bundled WAV opened");
        check(Service.type==2,"exact-access controller uses unbounded systemExempted type");
        int created=MediaPlayer.created;
        s.onStartCommand(intent(s,"user:1"),0,2);
        s.onBind(intent(s,"user:1"));
        check(MediaPlayer.created==created&&MediaPlayer.playing==1,"duplicate service delivery/rebind does not duplicate WAV");
        s.surfaceVisible("user:1");
        check(!Service.foreground&&!NotificationManagerCompat.present&&MediaPlayer.playing==1,"visible bound Activity removes temporary notification while sound continues");
        s.surfaceHidden();
        check(Service.foreground&&NotificationManagerCompat.present&&!Service.notification.launch,"Home retains mandatory background notification without reopening Activity");
        ReminderAlarm.active="user:2";ReminderAlarm.selected=ReminderAlertMode.VIBRATE;
        s.onStartCommand(intent(s,"user:2"),0,3);
        check(MediaPlayer.playing==0&&Vibrator.vibrating&&Vibrator.starts==1,"Vibrate replacement stops WAV and starts one loop");
        check(android.os.VibrationEffect.repeat==0,"vibration repeats until stopped");
        s.onStartCommand(intent(s,"user:2"),0,4);
        check(Vibrator.starts==1,"duplicate vibration prevented");
        ReminderAlarm.active="user:3";ReminderAlarm.selected=ReminderAlertMode.SILENT;
        s.onStartCommand(intent(s,"user:3"),0,5);
        check(!Vibrator.vibrating&&MediaPlayer.playing==0&&Service.notification.launch,"Silent still requests fullscreen with no audio/haptics");
        ReminderAlarmService.stopAlert("user:2");
        check(ReminderAlarmService.isRunning("user:3"),"stale dismiss cannot stop newer occurrence");
        s.surfaceVisible("user:3");
        Service.denyForeground=true;ReminderAlarm.active="user:visible-replacement";
        s.onStartCommand(intent(s,ReminderAlarm.active),0,5);
        check(ReminderAlarmService.isRunning(ReminderAlarm.active)&&!NotificationManagerCompat.present,
                "foreground permission race preserves visible bound controller without pending FGS contract");
        ReminderAlarm.active="user:3";Service.denyForeground=false;
        s.surfaceVisible("user:3");
        s.onTimeout(5);
        check(ReminderAlarmService.isRunning("user:3"),"visible alarm has no automatic two-minute timeout");
        ReminderAlarm.active=null;ReminderAlarmService.stopAlert("user:3");
        check(!ReminderAlarmService.isRunning("user:3")&&PowerManager.held==0&&!NotificationManagerCompat.present,"Dismiss releases resources and removes exact notification");
        s.onDestroy();
        s=new ReminderAlarmService();ReminderAlarm.active="user:4";ReminderAlarm.selected=ReminderAlertMode.RING;
        MediaPlayer.defer=true;
        s.onStartCommand(intent(s,"user:4"),0,6);
        MediaPlayer pending=MediaPlayer.last;MediaPlayer.OnPreparedListener callback=pending.prepared;
        ReminderAlarm.active=null;ReminderAlarmService.stopAlert("user:4");callback.onPrepared(pending);
        check(MediaPlayer.playing==0&&pending.isReleased,"late prepare callback cannot restart after Dismiss");
        MediaPlayer.defer=false;MediaPlayer.failPrepare=true;ReminderAlarm.active="user:5";
        s.onStartCommand(intent(s,"old"),0,7);
        check(ReminderAlarmService.isRunning("user:5")&&MediaPlayer.last.isReleased,"prepare failure leaves visual controller; stale start uses current occurrence");
        ReminderAlarmService.stopAlert(null);MediaPlayer.failPrepare=false;
        s=new ReminderAlarmService();AlarmManager.exact=false;ReminderAlarm.active="user:6";
        s.onStartCommand(intent(s,"user:6"),0,8);
        check(Service.type==1&&MediaPlayer.playing==1,"denied exact access uses legal short-service fallback");
        s.onTimeout(8);
        check(MediaPlayer.playing==0&&ReminderAlarm.fallbacks==1&&NotificationManagerCompat.present,"OS timeout cleans controller and retains fallback instead of ANR");
        check(PowerManager.held==0,"all controller wake locks released");
        System.out.println(checks+" shared reminder controller checks passed (Android API doubles).");
    }
}
