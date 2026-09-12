# Runs actual Hijri code on the JVM; OS alarms, Room and HTTP use test doubles.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$testOutput = Join-Path $projectRoot 'build/hijri-refresh-tests'
$utf8 = [Text.UTF8Encoding]::new($false)
$sources = @{
'android/content/SharedPreferences.java' = @'
package android.content;
import java.util.*;
public class SharedPreferences {
 final Map<String,Object> values=new HashMap<>();
 public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
 public long getLong(String k,long d){return (long)values.getOrDefault(k,d);}
 @SuppressWarnings("unchecked") public Set<String> getStringSet(String k,Set<String>d){return (Set<String>)values.getOrDefault(k,d);}
 public Editor edit(){return new Editor();}
 public class Editor {
  final Map<String,Object> changes=new HashMap<>();
  public Editor putString(String k,String v){changes.put(k,v);return this;}
  public Editor putLong(String k,long v){changes.put(k,v);return this;}
  public Editor putStringSet(String k,Set<String>v){changes.put(k,new HashSet<>(v));return this;}
  public Editor remove(String k){changes.put(k,null);return this;}
  public boolean commit(){changes.forEach((k,v)->{if(v==null)values.remove(k);else values.put(k,v);});return true;}
  public void apply(){commit();}
 }
}
'@
'android/content/Context.java' = @'
package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;public static final String ALARM_SERVICE="alarm";
 final SharedPreferences prefs=new SharedPreferences();
 public Context getApplicationContext(){return this;}
 public SharedPreferences getSharedPreferences(String file,int mode){return prefs;}
 public Object getSystemService(String s){return android.app.AlarmManager.INSTANCE;}
 public String getString(int id,Object...args){return ""+id+java.util.Arrays.toString(args);}
}
'@
'android/content/Intent.java' = @'
package android.content;
import java.util.*;
public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_CLEAR_TOP=2,FLAG_ACTIVITY_SINGLE_TOP=4;
 public static final String ACTION_BOOT_COMPLETED="boot",ACTION_MY_PACKAGE_REPLACED="update",ACTION_TIME_CHANGED="time",ACTION_TIMEZONE_CHANGED="timezone";
 public String action="",target="";public final Map<String,Object> extras=new HashMap<>();
 public Intent(){}public Intent(Context c,Class<?>cls){target=cls.getName();}
 public Intent setAction(String a){action=a;return this;}public String getAction(){return action;}
 public Intent putExtra(String k,String v){extras.put(k,v);return this;}
 public Intent putExtra(String k,int v){extras.put(k,v);return this;}
 public String getStringExtra(String k){return (String)extras.get(k);}
 public int getIntExtra(String k,int v){return (int)extras.getOrDefault(k,v);}
 public Intent addFlags(int f){return this;}
}
'@
'android/content/BroadcastReceiver.java' = @'
package android.content;
public abstract class BroadcastReceiver {
 public abstract void onReceive(Context c,Intent i);
 public PendingResult goAsync(){return new PendingResult();}
 public static class PendingResult {public void finish(){}}
}
'@
'android/app/AlarmManager.java' = @'
package android.app;
import java.util.*;
public class AlarmManager {
 public static final String ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED="exact-access";
 public static final AlarmManager INSTANCE=new AlarmManager();public static final int RTC_WAKEUP=0;
 public static int setCalls;
 public static final Map<Integer,PendingIntent> alarms=new HashMap<>();
 public static final Map<Integer,Long> times=new HashMap<>();
 public boolean canScheduleExactAlarms(){return true;}
 public void setExactAndAllowWhileIdle(int t,long at,PendingIntent p){set(t,at,p);}
 public void setAndAllowWhileIdle(int t,long at,PendingIntent p){set(t,at,p);}
 public void set(int t,long at,PendingIntent p){setCalls++;alarms.put(p.code,p);times.put(p.code,at);}
 public void cancel(PendingIntent p){alarms.remove(p.code);times.remove(p.code);}
}
'@
'android/app/PendingIntent.java' = @'
package android.app;
import android.content.*;import java.util.*;
public class PendingIntent {
 public static final int FLAG_UPDATE_CURRENT=1,FLAG_IMMUTABLE=2,FLAG_NO_CREATE=4;
 public static final Map<String,PendingIntent> existing=new HashMap<>();
 public int code;public Intent intent;String key;
 public static PendingIntent getBroadcast(Context c,int code,Intent i,int flags){
  String key=code+":"+i.target+":"+i.action;PendingIntent p=existing.get(key);
  if(p==null&&(flags&FLAG_NO_CREATE)!=0)return null;
  if(p==null){p=new PendingIntent();p.code=code;p.key=key;existing.put(key,p);}if(p.intent==null||(flags&FLAG_UPDATE_CURRENT)!=0)p.intent=i;return p;
 }
 public static PendingIntent getActivity(Context c,int code,Intent i,int f){return getBroadcast(c,code,i,f);}
 public void cancel(){existing.remove(key);}
}
'@
'android/os/Build.java' = @'
package android.os;public class Build {public static class VERSION {public static int SDK_INT=36;}}
'@
'android/annotation/SuppressLint.java' = @'
package android.annotation;public @interface SuppressLint {String[] value();}
'@
'androidx/core/app/NotificationCompat.java' = @'
package androidx.core.app;
import android.content.Context;import android.app.PendingIntent;
public class NotificationCompat {
 public static final int PRIORITY_DEFAULT=0;
 public static class BigTextStyle {public BigTextStyle bigText(String s){return this;}}
 public static class Builder {
 public Builder(Context c,String id){}public Builder setSmallIcon(int i){return this;}
 public Builder setContentTitle(String s){return this;}public Builder setContentText(String s){return this;}
 public Builder setStyle(BigTextStyle s){return this;}public Builder setAutoCancel(boolean b){return this;}
 public Builder setPriority(int p){return this;}public Builder setContentIntent(PendingIntent p){return this;}
 public Object build(){return this;}
 }
}
'@
'androidx/core/app/NotificationManagerCompat.java' = @'
package androidx.core.app;
import android.content.Context;import java.util.*;
public class NotificationManagerCompat {
 public static final Map<Integer,Integer> counts=new HashMap<>();
 public static NotificationManagerCompat from(Context c){return new NotificationManagerCompat();}
 public void notify(int id,Object n){counts.put(id,counts.getOrDefault(id,0)+1);}
}
'@
'org/json/JSONException.java' = @'
package org.json;public class JSONException extends Exception {public JSONException(String m){super(m);}}
'@
'org/json/JSONObject.java' = @'
package org.json;
import com.google.gson.*;import java.util.*;
public class JSONObject {
 private final JsonObject o;
 public JSONObject(){o=new JsonObject();}private JSONObject(JsonObject o){this.o=o;}
 public JSONObject(String s)throws JSONException{try{o=JsonParser.parseString(s).getAsJsonObject();}catch(Exception e){throw new JSONException(e.toString());}}
 public JSONObject put(String k,Object v)throws JSONException{o.add(k,v instanceof JSONObject?((JSONObject)v).o:new Gson().toJsonTree(v));return this;}
 public boolean has(String k){return o.has(k);}
 public String optString(String k,String d){try{return o.get(k).getAsString();}catch(Exception e){return d;}}
 public int optInt(String k,int d){try{return o.get(k).getAsInt();}catch(Exception e){return d;}}
 public long optLong(String k,long d){try{return o.get(k).getAsLong();}catch(Exception e){return d;}}
 public double optDouble(String k,double d){try{return o.get(k).getAsDouble();}catch(Exception e){return d;}}
 public boolean optBoolean(String k,boolean d){try{return o.get(k).getAsBoolean();}catch(Exception e){return d;}}
 public JSONObject optJSONObject(String k){try{JsonObject child=o.getAsJsonObject(k);return child==null?null:new JSONObject(child);}catch(Exception e){return null;}}
 public int length(){return o.size();}public Iterator<String> keys(){return o.keySet().iterator();}
 public void remove(String k){o.remove(k);}public String toString(){return o.toString();}
}
'@
'com/darood/app/AppLogger.java' = @'
package com.darood.app;public class AppLogger {
 public static void i(String t,String m){}public static void d(String t,String m){}
 public static void w(String t,String m){}public static void w(String t,String m,Throwable e){}
 public static void e(String t,String m,Throwable e){throw new AssertionError(m,e);}
}
'@
'com/darood/app/AppSettings.java' = @'
package com.darood.app;public class AppSettings {public static final String PREFS_FILE="app_settings";}
'@
'com/darood/app/MainActivity.java' = @'
package com.darood.app;public class MainActivity {}
'@
'com/darood/app/LanguageManager.java' = @'
package com.darood.app;import android.content.Context;public class LanguageManager {
 public static Context applyLanguage(Context c){return c;}public static String getSavedLanguage(Context c){return "en";}
}
'@
'com/darood/app/ReminderAlarm.java' = @'
package com.darood.app;import android.content.Context;public class ReminderAlarm {
 public static final String ACTION_DISMISS="dismiss",EXTRA_OCCURRENCE="occurrence";
 public static void dismiss(Context c,String occurrence){}
}
'@
'com/darood/app/FridayReminderScheduler.java' = @'
package com.darood.app;import android.content.*;public class FridayReminderScheduler {
 public static final String ACTION="friday";public static void handleFire(Context c,Intent i){}public static void restoreAfterBoot(Context c){}
}
'@
'com/darood/app/NotificationScheduler.java' = @'
package com.darood.app;import android.content.*;
public class NotificationScheduler {
 public static final String CHANNEL_ID="notifications",ACTION_NOTIFICATION="notification",ACTION_UPDATE_REMINDER="reminder";
 public static boolean hasNotificationPermission(Context c){return true;}public static void createChannel(Context c){}
 public static void rescheduleAll(Context c){}public static void restoreUpdateReminder(Context c){}
 public static void handleNotificationFire(Context c,Intent i){}public static void handleUpdateReminder(Context c,Intent i){}
}
'@
'com/darood/app/HijriOverride.java' = @'
package com.darood.app;public class HijriOverride {
 public String gregorianDate;public int hijriDay,hijriMonth,hijriYear;public long updatedAt;
 public HijriOverride(String g,int d,int m,int y,long at){gregorianDate=g;hijriDay=d;hijriMonth=m;hijriYear=y;updatedAt=at;}
}
'@
'com/darood/app/HijriOverrideRepository.java' = @'
package com.darood.app;import android.content.Context;import java.util.*;
public class HijriOverrideRepository {
 public static final Map<String,HijriOverride> rows=new HashMap<>();
 public static HijriOverrideRepository get(Context c){return new HijriOverrideRepository();}
 public HijriOverride find(String date){return rows.get(date);}
 public List<HijriOverride> getAll(){return new ArrayList<>(rows.values());}
 public void upsert(HijriOverride o){rows.put(o.gregorianDate,o);}public void delete(String date){rows.remove(date);}
}
'@
'com/darood/app/HijriLocationHelper.java' = @'
package com.darood.app;import android.content.Context;public class HijriLocationHelper {
 public static boolean hasPermission(Context c){return true;}
 public static double[] getLocation(Context c,long timeout){return HijriCache.getLastLocation(c);}
}
'@
'com/darood/app/HijriApiClient.java' = @'
package com.darood.app;public class HijriApiClient {
 public static int calls;public static String requested;public static HijriDayData response;public static Runnable duringFetch;
 public static HijriDayData fetch(String d,double lat,double lon){calls++;requested=d;if(duringFetch!=null)duringFetch.run();return response;}
}
'@
'com/darood/app/R.java' = @'
package com.darood.app;public class R {
 public static class drawable {public static final int ic_notification=1;}
 public static class string {public static final int hijri_29_notification_title=1;public static final int hijri_29_notification_message=2;public static final int hijri_new_month_notification_message=3;public static final int hijri_new_month_notification_title=4;public static final int hijri_month_1=5;public static final int hijri_month_2=6;public static final int hijri_month_3=7;public static final int hijri_month_4=8;public static final int hijri_month_5=9;public static final int hijri_month_6=10;public static final int hijri_month_7=11;public static final int hijri_month_8=12;public static final int hijri_month_9=13;public static final int hijri_month_10=14;public static final int hijri_month_11=15;public static final int hijri_month_12=16;}
}
'@
}
foreach ($entry in $sources.GetEnumerator()) {
 $file = Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $file -Parent)) | Out-Null
 [IO.File]::WriteAllText($file,$entry.Value,$utf8)
}
$gsonRoot = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson'
$gson = Get-ChildItem -LiteralPath $gsonRoot -Recurse -Filter 'gson-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
if (-not $gson) { throw 'An existing Gradle-cached Gson jar is required for the test JSON adapter' }
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { 'C:/Program Files/Java/jdk-21/bin' }
$javaSources = @($sources.Keys | ForEach-Object { Join-Path $testOutput $_ })
@('HijriCoordinator','HijriSunsetScheduler','HijriSunsetReceiver','HijriDay29Notification','HijriNewMonthNotification','HijriMath','HijriDayData','HijriCache','BootReceiver','NotificationReceiver') | ForEach-Object { $javaSources += Join-Path $projectRoot ('app/src/main/java/com/darood/app/' + $_ + '.java') }
$javaSources += Join-Path $PSScriptRoot 'HijriRefreshTest.java'
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -cp $gson -d $testOutput @javaSources
if ($LASTEXITCODE -ne 0) { throw 'Hijri test compilation failed' }
& (Join-Path $javaBin 'java.exe') -cp ($testOutput + ';' + $gson) com.darood.app.HijriRefreshTest
if ($LASTEXITCODE -ne 0) { throw 'Hijri regression tests failed' }
