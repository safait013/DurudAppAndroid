# Reuse existing MediaPlayer lifecycle doubles, replacing only the obsolete raw-resource source.
$base=[IO.File]::ReadAllText((Join-Path $PSScriptRoot 'pronunciation-player.test.ps1'))
$base=$base.Replace('$PSScriptRoot',("'"+$PSScriptRoot.Replace("'","''")+"'"))
Invoke-Expression $base.Substring(0,$base.IndexOf('foreach ($entry in $sources.GetEnumerator())'))
$testOutput=Join-Path $projectRoot 'build/pronunciation-cache-player-tests'
$sources.Remove('com/darood/app/PronunciationPlayerTest.java')
$sources['android/media/MediaPlayer.java']=$sources['android/media/MediaPlayer.java'].Replace(' public void prepareAsync()', ' public static String path;public void setDataSource(String p){path=p;} public void prepareAsync()')
$sources['com/darood/app/R.java']='package com.darood.app;public class R {public static class string {public static final int audio_no_internet=1,audio_storage_low=2,audio_download_failed=3,audio_unavailable=4;}}'
$sources['com/darood/app/LanguageManager.java']='package com.darood.app;public class LanguageManager {public static android.content.Context applyLanguage(android.content.Context c){return c;}}'
$sources['android/widget/Toast.java']='package android.widget;public class Toast {public static final int LENGTH_LONG=1;public static int message;public static Toast makeText(android.content.Context c,int m,int d){message=m;return new Toast();}public void show(){}}'
$sources['com/darood/app/AudioManifest.java']='package com.darood.app;public class AudioManifest {static boolean validId(String c,int id){return id>0&&id<=(c.equals("durood")?25:15);}}'
$sources['com/darood/app/AudioCache.java']='package com.darood.app;public class AudioCache {enum Failure {NO_INTERNET,STORAGE,DOWNLOAD,UNAVAILABLE}static class LocalAudio {final java.io.File file;LocalAudio(String p){file=new java.io.File(p);}}}'
$sources['com/darood/app/AudioDownloads.java']=@'
package com.darood.app;import java.util.*;public class AudioDownloads {
 interface Callback {void complete(AudioCache.LocalAudio a,AudioCache.Failure e);}interface Request {void cancel();}
 static final AudioDownloads instance=new AudioDownloads();static int requests,cancellations,invalidated;static final List<Callback> callbacks=new ArrayList<>();static final List<String> keys=new ArrayList<>();
 static AudioDownloads get(android.content.Context c){return instance;}
 Request request(String category,int id,Callback callback){requests++;callbacks.add(callback);keys.add(category+":"+id);return ()->cancellations++;}
 void invalidate(AudioCache.LocalAudio a){invalidated++;}
 static void finish(int index){callbacks.get(index).complete(new AudioCache.LocalAudio("private/"+keys.get(index).replace(':','_')+".m4a"),null);}
}
'@
foreach($entry in $sources.GetEnumerator()){
 $file=Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $file -Parent))|Out-Null
 [IO.File]::WriteAllText($file,$entry.Value,$utf8)
}
$javaBin=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin'}else{'C:/Program Files/Java/jdk-21/bin'}
$javaSources=@($sources.Keys|ForEach-Object{Join-Path $testOutput $_})
$javaSources+=Join-Path $projectRoot 'app/src/main/java/com/darood/app/DuroodAudioPlayer.java'
$javaSources+=Join-Path $PSScriptRoot 'PronunciationCachePlayerTest.java'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -d $testOutput @javaSources
if($LASTEXITCODE -ne 0){throw 'Cache player tests compilation failed'}
& (Join-Path $javaBin 'java.exe') -cp $testOutput com.darood.app.PronunciationCachePlayerTest
if($LASTEXITCODE -ne 0){throw 'Cache player tests failed'}
