# Runs the actual scheduler against Android API doubles. No device delivery is simulated.
$base = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'hijri-refresh.test.ps1'))
$base = $base.Replace('$PSScriptRoot', ("'" + $PSScriptRoot.Replace("'", "''") + "'"))
Invoke-Expression $base.Substring(0, $base.IndexOf('foreach ($entry in $sources.GetEnumerator())'))
$testOutput = Join-Path $projectRoot 'build/reminder-alarm-tests-v2'
$sources.Remove('com/darood/app/NotificationScheduler.java')
$sources.Remove('com/darood/app/FridayReminderScheduler.java')
$sources['android/content/SharedPreferences.java'] = $sources['android/content/SharedPreferences.java'].Replace(' public Editor edit()', ' public boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);} public Editor edit()').Replace('  public Editor putString(', '  public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;} public Editor putString(')
$sources['android/content/Context.java'] = $sources['android/content/Context.java'].Replace('ALARM_SERVICE="alarm"','ALARM_SERVICE="alarm",NOTIFICATION_SERVICE="notification"').Replace('return android.app.AlarmManager.INSTANCE;', 'return s.equals(ALARM_SERVICE)?android.app.AlarmManager.INSTANCE:new android.app.NotificationManager();')
$sources['android/content/Intent.java'] = $sources['android/content/Intent.java'].Replace(' public Intent addFlags', ' public Intent putExtra(String k,long v){extras.put(k,v);return this;} public long getLongExtra(String k,long d){return (long)extras.getOrDefault(k,d);} public Intent addFlags')
$sources['android/app/AlarmManager.java'] = $sources['android/app/AlarmManager.java'].Replace('return true;', 'return exact;').Replace(' public static int setCalls;', ' public static int setCalls,inexactCalls;public static boolean exact=true,denyExact=false;').Replace('public void setExactAndAllowWhileIdle(int t,long at,PendingIntent p){set(t,at,p);}', 'public void setExactAndAllowWhileIdle(int t,long at,PendingIntent p){if(denyExact)throw new SecurityException();set(t,at,p);} public void setExact(int t,long at,PendingIntent p){setExactAndAllowWhileIdle(t,at,p);}').Replace('public void setAndAllowWhileIdle(int t,long at,PendingIntent p){set(t,at,p);}', 'public void setAndAllowWhileIdle(int t,long at,PendingIntent p){inexactCalls++;set(t,at,p);}')
$sources['android/content/pm/PackageManager.java'] = 'package android.content.pm;public class PackageManager {public static final int PERMISSION_GRANTED=0;}'
$sources['android/Manifest.java'] = 'package android;public class Manifest {public static class permission {public static final String POST_NOTIFICATIONS="post";}}'
$sources['androidx/core/content/ContextCompat.java'] = 'package androidx.core.content;import android.content.Context;public class ContextCompat {public static int permission=0;public static int checkSelfPermission(Context c,String p){return permission;}}'
$sources['android/app/NotificationChannel.java'] = 'package android.app;public class NotificationChannel {public NotificationChannel(String i,String n,int p){}public void setDescription(String s){}public void setShowBadge(boolean b){}}'
$sources['android/app/NotificationManager.java'] = 'package android.app;public class NotificationManager {public static final int IMPORTANCE_DEFAULT=3;public void createNotificationChannel(NotificationChannel c){}}'
$sources['androidx/core/app/NotificationManagerCompat.java'] = $sources['androidx/core/app/NotificationManagerCompat.java'].Replace(' public void notify', ' public void cancel(int id){counts.remove(id);} public void notify')
$sources['com/darood/app/AppLogger.java'] = $sources['com/darood/app/AppLogger.java'].Replace(' public static void i', ' public static boolean isDebuggable(){return false;} public static void i')
$sources['com/darood/app/UpdateChecker.java'] = 'package com.darood.app;import android.content.Context;public class UpdateChecker {public static long getInstalledVersionCode(Context c){return 1;}}'
$sources['com/darood/app/UpdateStoreActivity.java'] = 'package com.darood.app;public class UpdateStoreActivity {}'
$sources['org/json/JSONObject.java'] = $sources['org/json/JSONObject.java'].Replace('private final JsonObject o;', 'final JsonObject o;').Replace('private JSONObject(JsonObject o)', 'JSONObject(JsonObject o)').Replace('v instanceof JSONObject?((JSONObject)v).o:new Gson().toJsonTree(v)', 'v instanceof JSONObject?((JSONObject)v).o:v instanceof JSONArray?((JSONArray)v).a:new Gson().toJsonTree(v)').Replace(' public boolean has', ' public String optString(String k){return optString(k,"");} public JSONArray optJSONArray(String k){try{return new JSONArray(o.getAsJsonArray(k));}catch(Exception e){return null;}} public boolean has')
$sources['org/json/JSONArray.java'] = @'
package org.json;import com.google.gson.*;public class JSONArray {
 final JsonArray a;public JSONArray(){a=new JsonArray();}JSONArray(JsonArray a){if(a==null)throw new IllegalArgumentException();this.a=a;}
 public JSONArray put(JSONObject o){a.add(o.o);return this;}public int length(){return a.size();}
 public JSONObject optJSONObject(int i){try{return new JSONObject(a.get(i).getAsJsonObject());}catch(Exception e){return null;}}
}
'@
$sources['com/darood/app/ReminderAlarm.java'] = @'
package com.darood.app;import android.content.Context;public class ReminderAlarm {
 public static final String ACTION_DISMISS="dismiss",EXTRA_OCCURRENCE="occurrence";
 static int fires;static ReminderAlertMode lastMode;static String active;
 public static String active(Context c){return active;}
 public static void dismiss(Context c,String occurrence){active=null;}
 public static void fire(Context c,String occurrence,ReminderAlertMode mode){if(!NotificationScheduler.hasNotificationPermission(c))return;fires++;active=occurrence;lastMode=mode;}
}
'@
$resourceNames = [regex]::Matches([IO.File]::ReadAllText((Join-Path $projectRoot 'app/src/main/res/values/strings.xml')), '<string name="([^"]+)"')
$fields = for ($i=0; $i -lt $resourceNames.Count; $i++) { 'public static final int '+$resourceNames[$i].Groups[1].Value+'='+($i+1)+';' }
$sources['com/darood/app/R.java'] = 'package com.darood.app;public class R {public static class drawable {public static final int ic_notification=1;}public static class string {' + ($fields -join '') + '}}'
foreach ($entry in $sources.GetEnumerator()) {
 $file = Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $file -Parent)) | Out-Null
 [IO.File]::WriteAllText($file,$entry.Value,$utf8)
}
$gson = Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson') -Recurse -Filter 'gson-*.jar' | Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1 -ExpandProperty FullName
$javaBin = Join-Path $env:JAVA_HOME 'bin'
$javaSources = @($sources.Keys | ForEach-Object { Join-Path $testOutput $_ })
@('HijriCoordinator','HijriSunsetScheduler','HijriSunsetReceiver','HijriDay29Notification','HijriNewMonthNotification','HijriMath','HijriDayData','HijriCache','BootReceiver','NotificationReceiver','NotificationScheduler','ReminderAlertMode','FridayReminderScheduler') | ForEach-Object { $javaSources += Join-Path $projectRoot ('app/src/main/java/com/darood/app/' + $_ + '.java') }
$javaSources += Join-Path $PSScriptRoot 'ReminderSchedulerTest.java'
$javaSources += Join-Path $PSScriptRoot 'FridayReminderTest.java'
& (Join-Path $javaBin 'javac.exe') -implicit:none -encoding UTF-8 -cp $gson -d $testOutput @javaSources
if ($LASTEXITCODE -ne 0) { throw 'Reminder tests compilation failed' }
& (Join-Path $javaBin 'java.exe') -cp ($testOutput + ';' + $gson) com.darood.app.ReminderSchedulerTest
if ($LASTEXITCODE -ne 0) { throw 'Reminder tests failed' }
& (Join-Path $javaBin 'java.exe') -cp ($testOutput + ';' + $gson) com.darood.app.FridayReminderTest
if ($LASTEXITCODE -ne 0) { throw 'Friday reminder tests failed' }
