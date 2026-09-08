# Runs the actual controller on the JVM with Android API test doubles; no device playback.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testOutput = Join-Path $projectRoot 'build/salam-controller-tests'
$utf8 = [Text.UTF8Encoding]::new($false)
$sources = @{
'android/content/Context.java' = @'
package android.content;
public class Context {
 public Context getApplicationContext(){return this;}
 public android.content.res.Resources getResources(){return new android.content.res.Resources();}
}
'@
'android/content/res/AssetFileDescriptor.java' = @'
package android.content.res;
public class AssetFileDescriptor implements AutoCloseable {
 public java.io.FileDescriptor getFileDescriptor(){return new java.io.FileDescriptor();}
 public long getStartOffset(){return 0;}
 public long getLength(){return 1;}
 public void close(){}
}
'@
'android/content/res/Resources.java' = @'
package android.content.res;
public class Resources {
 public static int selected;
 public static boolean fail;
 public AssetFileDescriptor openRawResourceFd(int id){selected=id;if(fail)throw new NotFoundException("test");return new AssetFileDescriptor();}
 public static class NotFoundException extends RuntimeException {public NotFoundException(String message){super(message);}}
}
'@
'android/media/AudioAttributes.java' = @'
package android.media;
public class AudioAttributes {
 public static final int USAGE_MEDIA=1,CONTENT_TYPE_SPEECH=1;
 public static class Builder {
  public Builder setUsage(int v){return this;}
  public Builder setContentType(int v){return this;}
  public AudioAttributes build(){return new AudioAttributes();}
 }
}
'@
'android/media/MediaPlayer.java' = @'
package android.media;
public class MediaPlayer {
 public interface OnPreparedListener {void onPrepared(MediaPlayer p);}
 public interface OnCompletionListener {void onCompletion(MediaPlayer p);}
 public interface OnErrorListener {boolean onError(MediaPlayer p,int what,int extra);}
 public static MediaPlayer last;
 public static int live,maxLive;
 public static boolean failStart;
 public OnPreparedListener prepared;
 public OnCompletionListener completed;
 public OnErrorListener error;
 public boolean released,started;
 public MediaPlayer(){last=this;live++;maxLive=Math.max(maxLive,live);}
 public void setAudioAttributes(AudioAttributes a){}
 public void setOnPreparedListener(OnPreparedListener l){prepared=l;}
 public void setOnCompletionListener(OnCompletionListener l){completed=l;}
 public void setOnErrorListener(OnErrorListener l){error=l;}
 public void setDataSource(java.io.FileDescriptor fd,long offset,long length){}
 public void prepareAsync(){}
 public void start(){if(released||failStart)throw new IllegalStateException("test");started=true;}
 public void release(){if(released)throw new IllegalStateException("double release");released=true;started=false;live--;}
}
'@
'com/darood/app/AppLogger.java' = @'
package com.darood.app;
public class AppLogger {
 public static void i(String t,String m){}
 public static void w(String t,String m){}
 public static void w(String t,String m,Throwable e){}
 public static void e(String t,String m){}
 public static void e(String t,String m,Throwable e){}
}
'@
'com/darood/app/R.java' = @'
package com.darood.app;
public class R {public static class raw {public static final int durood1=1;public static final int durood2=2;public static final int durood3=3;public static final int durood4=4;public static final int durood5=5;public static final int durood6=6;public static final int durood7=7;public static final int durood8=8;public static final int durood9=9;public static final int durood10=10;public static final int salam1=101;public static final int salam2=102;public static final int salam3=103;public static final int salam4=104;}}
'@
'com/darood/app/PronunciationPlayerTest.java' = @'
package com.darood.app;
import android.content.Context;
import android.content.res.Resources;
import android.media.MediaPlayer;
public class PronunciationPlayerTest {
 static int checks,id;static boolean playing,salam;
 static void check(boolean b,String label){if(!b)throw new AssertionError(label);checks++;}
 static void idle(){check(id==0&&!playing&&MediaPlayer.live==0,"idle, released, reset");}
 public static void main(String[] args){
  Context context=new Context();
  DuroodAudioPlayer player=new DuroodAudioPlayer((i,p,s)->{id=i;playing=p;salam=s;});
  player.publishState();idle();
  for(int i=1;i<=4;i++){
   check(DuroodAudioPlayer.hasSalamAudio(i),"Salam availability");
   player.toggleSalam(context,i);
   check(Resources.selected==100+i,"correct Salam raw resource");
   MediaPlayer.last.prepared.onPrepared(MediaPlayer.last);
   check(id==i&&playing&&salam,"Salam started");
   player.toggleSalam(context,i);idle();
  }
  for(int i=1;i<=10;i++){
   check(DuroodAudioPlayer.hasAudio(i),"Durood availability");
   player.toggle(context,i);
   check(Resources.selected==i,"unchanged Durood raw resource");
   MediaPlayer.last.prepared.onPrepared(MediaPlayer.last);
   check(id==i&&playing&&!salam,"Durood started");
   player.stop();idle();
  }
  for(int i=5;i<=15;i++){
   check(!DuroodAudioPlayer.hasSalamAudio(i),"missing Salam availability");
   int previous=Resources.selected;player.toggleSalam(context,i);
   check(Resources.selected==previous,"missing resource never opened");idle();
  }
  for(int i:new int[]{0,-1,Integer.MAX_VALUE}){
   check(!DuroodAudioPlayer.hasSalamAudio(i),"invalid ID unavailable");
   player.toggleSalam(context,i);idle();
  }
  player.toggle(context,1);
  MediaPlayer durood=MediaPlayer.last;durood.prepared.onPrepared(durood);
  player.toggleSalam(context,1);
  MediaPlayer first=MediaPlayer.last;
  check(durood.released&&first!=durood,"equal numeric IDs across types switch");
  first.prepared.onPrepared(first);check(id==1&&salam&&playing,"Salam owns state");
  player.toggleSalam(context,2);
  MediaPlayer second=MediaPlayer.last;
  check(first.released,"previous Salam released");
  second.prepared.onPrepared(second);check(id==2&&salam&&playing,"second Salam owns state");
  first.prepared.onPrepared(first);first.completed.onCompletion(first);first.error.onError(first,1,2);
  check(id==2&&salam&&playing&&!second.released,"stale callbacks ignored");
  player.toggle(context,1);
  check(second.released,"Salam released before Durood");
  MediaPlayer.last.prepared.onPrepared(MediaPlayer.last);
  check(id==1&&!salam&&playing,"Durood owns state again");
  MediaPlayer.last.completed.onCompletion(MediaPlayer.last);idle();
  player.toggleSalam(context,3);MediaPlayer.last.prepared.onPrepared(MediaPlayer.last);
  MediaPlayer.last.completed.onCompletion(MediaPlayer.last);idle();
  player.toggleSalam(context,4);MediaPlayer.last.error.onError(MediaPlayer.last,1,2);idle();
  player.toggleSalam(context,1);MediaPlayer pending=MediaPlayer.last;player.stop();idle();
  pending.prepared.onPrepared(pending);check(!pending.started,"late preparation cannot restart");
  player.toggleSalam(context,1);player.toggleSalam(context,1);idle();
  MediaPlayer.failStart=true;player.toggleSalam(context,1);MediaPlayer.last.prepared.onPrepared(MediaPlayer.last);idle();MediaPlayer.failStart=false;
  Resources.fail=true;player.toggleSalam(context,1);idle();Resources.fail=false;
  player.stop();player.stop();idle();
  check(MediaPlayer.maxLive==1,"never more than one allocated player");
  System.out.println(checks+" shared player checks passed (Android API test doubles; not real playback).");
 }
}
'@
}
foreach ($entry in $sources.GetEnumerator()) {
 $file = Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $file -Parent)) | Out-Null
 [IO.File]::WriteAllText($file,$entry.Value,$utf8)
}
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Program Files/Java/jdk-21/bin' }
$javaSources = @($sources.Keys | ForEach-Object { Join-Path $testOutput $_ })
$javaSources += Join-Path $projectRoot 'app/src/main/java/com/darood/app/DuroodAudioPlayer.java'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d $testOutput @javaSources
if ($LASTEXITCODE -ne 0) { throw 'Controller test compilation failed' }
& (Join-Path $javaBin 'java.exe') -cp $testOutput com.darood.app.PronunciationPlayerTest
if ($LASTEXITCODE -ne 0) { throw 'Controller tests failed' }
