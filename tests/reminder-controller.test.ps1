# Runs the actual shared controller against observable Android audio/service doubles.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testOutput = Join-Path $projectRoot 'build/reminder-controller-tests'
$utf8 = [Text.UTF8Encoding]::new($false)
$sources = @{
'android/content/Context.java' = @'
package android.content;public class Context {
 public static final String ALARM_SERVICE="alarm",POWER_SERVICE="power",VIBRATOR_SERVICE="vibrator",VIBRATOR_MANAGER_SERVICE="vibrators";
 public Object getSystemService(String s){switch(s){case "alarm":return new android.app.AlarmManager();case "power":return new android.os.PowerManager();case "vibrators":return new android.os.VibratorManager();default:return android.os.Vibrator.INSTANCE;}}
 public String getPackageName(){return "test";}public void sendBroadcast(Intent i){}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
}
'@
'android/content/Intent.java' = @'
package android.content;public class Intent {String value;public Intent(String a){}public Intent(Context c,Class<?> type){}public Intent putExtra(String k,String v){value=v;return this;}public String getStringExtra(String k){return value;}public Intent setPackage(String p){return this;}}
'@
'android/app/Notification.java' = 'package android.app;public class Notification {public final boolean launch;public Notification(boolean l){launch=l;}}'
'android/app/AlarmManager.java' = 'package android.app;public class AlarmManager {public static boolean exact=true;public boolean canScheduleExactAlarms(){return exact;}}'
'android/app/Service.java' = @'
package android.app;import android.content.*;public class Service extends Context {
 public static final int START_NOT_STICKY=2,START_STICKY=1;public static boolean foreground,stopped,denyForeground;public static int type;public static Notification notification;
 public android.os.IBinder onBind(Intent i){return null;}public int onStartCommand(Intent i,int f,int id){return 0;}
 public void startForeground(int id,Notification n){if(denyForeground)throw new SecurityException();foreground=true;notification=n;androidx.core.app.NotificationManagerCompat.present=true;}
 public void startForeground(int id,Notification n,int t){type=t;startForeground(id,n);}
 public void stopForeground(boolean remove){foreground=false;if(remove)androidx.core.app.NotificationManagerCompat.present=false;}
 public void stopSelf(){stopped=true;}public void startService(Intent i){onStartCommand(i,0,1);}
 public void onTimeout(int id){}public void onTimeout(int id,int type){}public void onDestroy(){}
}
'@
'android/content/pm/ServiceInfo.java' = 'package android.content.pm;public class ServiceInfo {public static final int FOREGROUND_SERVICE_TYPE_SHORT_SERVICE=1,FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED=2;}'
'android/content/res/Resources.java' = 'package android.content.res;public class Resources {public static int opened;public AssetFileDescriptor openRawResourceFd(int id){opened=id;return new AssetFileDescriptor();}}'
'android/content/res/AssetFileDescriptor.java' = 'package android.content.res;public class AssetFileDescriptor implements AutoCloseable {public java.io.FileDescriptor getFileDescriptor(){return new java.io.FileDescriptor();}public long getStartOffset(){return 12;}public long getLength(){return 59578;}public void close(){}}'
'android/media/AudioAttributes.java' = 'package android.media;public class AudioAttributes {public static final int USAGE_ALARM=4,CONTENT_TYPE_SONIFICATION=4;public int usage;public static class Builder {AudioAttributes a=new AudioAttributes();public Builder setUsage(int u){a.usage=u;return this;}public Builder setContentType(int t){return this;}public AudioAttributes build(){return a;}}}'
'android/media/MediaPlayer.java' = @'
package android.media;public class MediaPlayer {
 public static int created,released,started,playing;public static boolean failPrepare,defer;public static MediaPlayer last;
 public boolean looping,isPlaying,isReleased;public AudioAttributes attributes;public OnPreparedListener prepared;
 public interface OnPreparedListener {void onPrepared(MediaPlayer p);}public interface OnErrorListener{boolean onError(MediaPlayer p,int w,int e);}
 public MediaPlayer(){created++;last=this;}public void setAudioAttributes(AudioAttributes a){attributes=a;}
 public void setDataSource(java.io.FileDescriptor fd,long o,long l){}public void setLooping(boolean b){looping=b;}
 public void setOnErrorListener(OnErrorListener l){}public void setOnPreparedListener(OnPreparedListener l){prepared=l;}
 public void prepareAsync(){if(failPrepare)throw new IllegalStateException();if(!defer)prepared.onPrepared(this);}
 public void start(){if(isReleased)throw new IllegalStateException();started++;playing++;isPlaying=true;}
 public void release(){if(isReleased)throw new IllegalStateException("double release");isReleased=true;released++;if(isPlaying)playing--;}
}
'@
'android/os/Build.java' = 'package android.os;public class Build {public static class VERSION {public static int SDK_INT=36;}}'
'android/os/IBinder.java' = 'package android.os;public interface IBinder {}'
'android/os/Binder.java' = 'package android.os;public class Binder implements IBinder {}'
'android/os/PowerManager.java' = 'package android.os;public class PowerManager {public static final int PARTIAL_WAKE_LOCK=1;public static int held;public WakeLock newWakeLock(int t,String s){return new WakeLock();}public static class WakeLock {boolean active;public void acquire(){active=true;held++;}public boolean isHeld(){return active;}public void release(){active=false;held--;}}}'
'android/os/VibrationEffect.java' = 'package android.os;public class VibrationEffect {public static int repeat;public static VibrationEffect createWaveform(long[] p,int r){repeat=r;return new VibrationEffect();}}'
'android/os/Vibrator.java' = 'package android.os;public class Vibrator {public static final Vibrator INSTANCE=new Vibrator();public static int starts;public static boolean vibrating;public boolean hasVibrator(){return true;}public void vibrate(VibrationEffect e,android.media.AudioAttributes a){starts++;vibrating=true;}public void vibrate(long[] p,int r,android.media.AudioAttributes a){starts++;vibrating=true;}public void cancel(){vibrating=false;}}'
'android/os/VibratorManager.java' = 'package android.os;public class VibratorManager {public Vibrator getDefaultVibrator(){return Vibrator.INSTANCE;}}'
'androidx/core/app/NotificationManagerCompat.java' = 'package androidx.core.app;public class NotificationManagerCompat {public static boolean present;public static int cancelled;public static NotificationManagerCompat from(android.content.Context c){return new NotificationManagerCompat();}public void cancel(int id){cancelled=id;present=false;}}'
'com/darood/app/R.java' = 'package com.darood.app;public class R {public static class raw {public static final int durood_premium_soft_ting_ting=99;}public static class string {public static final int reminder_ring=1,reminder_vibrate=2,reminder_silent=3;}}'
'com/darood/app/AppLogger.java' = 'package com.darood.app;public class AppLogger {public static void i(String t,String m){}public static void w(String t,String m){}public static void w(String t,String m,Throwable e){}}'
'com/darood/app/ReminderAlarm.java' = @'
package com.darood.app;import android.content.*;public class ReminderAlarm {
 static final int NOTIFICATION_ID=6200;static final String EXTRA_OCCURRENCE="occurrence",ACTION_CHANGED="changed";
 static String active;static ReminderAlertMode selected=ReminderAlertMode.RING;static int fallbacks;
 static String active(Context c){return active;}static ReminderAlertMode mode(Context c){return selected;}
 static boolean canNotify(Context c){return true;}
 static android.app.Notification notification(Context c,String a,boolean launch){return new android.app.Notification(launch);}
 static void postFallback(Context c,String a){postFallback(c,a,true);}
 static void postFallback(Context c,String a,boolean launch){fallbacks++;androidx.core.app.NotificationManagerCompat.present=true;}
}
'@
}
foreach($entry in $sources.GetEnumerator()) {
 $file=Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $file -Parent)) | Out-Null
 [IO.File]::WriteAllText($file,$entry.Value,$utf8)
}
$javaSources=@($sources.Keys | ForEach-Object {Join-Path $testOutput $_})
@('ReminderAlarmService','ReminderAlertMode') | ForEach-Object {$javaSources+=Join-Path $projectRoot ('app/src/main/java/com/darood/app/'+$_+'.java')}
$javaSources+=Join-Path $PSScriptRoot 'ReminderControllerTest.java'
$javaBin=Join-Path $env:JAVA_HOME 'bin'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d $testOutput @javaSources
if($LASTEXITCODE -ne 0){throw 'Controller tests compilation failed'}
& (Join-Path $javaBin 'java.exe') -cp $testOutput com.darood.app.ReminderControllerTest
if($LASTEXITCODE -ne 0){throw 'Controller tests failed'}
