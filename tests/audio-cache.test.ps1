# Actual manifest/cache/download coordinator, real temporary files, deterministic HTTP/storage doubles.
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$testOutput=Join-Path $projectRoot 'build/audio-cache-tests'
$utf8=[Text.UTF8Encoding]::new($false)
$sources=@{
'org/json/JSONObject.java'=@'
package org.json;import com.google.gson.*;public class JSONObject {
 final JsonObject o;public JSONObject(){o=new JsonObject();}public JSONObject(String s){o=JsonParser.parseString(s).getAsJsonObject();}JSONObject(JsonObject v){o=v;}
 public JSONObject put(String k,Object v){o.add(k,v instanceof JSONObject?((JSONObject)v).o:v instanceof JSONArray?((JSONArray)v).a:new Gson().toJsonTree(v));return this;}
 public Object opt(String k){JsonElement v=o.get(k);if(v==null||v.isJsonNull())return null;if(v.isJsonPrimitive()){JsonPrimitive p=v.getAsJsonPrimitive();if(p.isNumber())return p.getAsNumber();if(p.isString())return p.getAsString();}return null;}
 public String optString(String k,String d){try{return o.get(k).getAsString();}catch(Exception e){return d;}}
 public String getString(String k){return o.get(k).getAsString();}public int optInt(String k,int d){try{return o.get(k).getAsInt();}catch(Exception e){return d;}}
 public long optLong(String k,long d){try{return o.get(k).getAsLong();}catch(Exception e){return d;}}
 public JSONArray optJSONArray(String k){try{return new JSONArray(o.getAsJsonArray(k));}catch(Exception e){return null;}}
 public String toString(){return o.toString();}
}
'@
'org/json/JSONArray.java'=@'
package org.json;import com.google.gson.*;public class JSONArray {final JsonArray a;public JSONArray(){a=new JsonArray();}JSONArray(JsonArray v){if(v==null)throw new IllegalArgumentException();a=v;}public int length(){return a.size();}public JSONArray put(JSONObject v){a.add(v.o);return this;}public JSONObject optJSONObject(int i){try{return new JSONObject(a.get(i).getAsJsonObject());}catch(Exception e){return null;}}}
'@
'android/os/Handler.java'=@'
package android.os;public class Handler {public Handler(){}public Handler(Looper l){}public final java.util.Queue<Runnable> pending=new java.util.concurrent.ConcurrentLinkedQueue<>();public boolean post(Runnable r){pending.add(r);return true;}public void drain(){Runnable r;while((r=pending.poll())!=null)r.run();}}
'@
'com/darood/app/AppSettings.java'='package com.darood.app;public class AppSettings {public static final String PREFS_FILE="app_settings";}'
'com/darood/app/AppLogger.java'='package com.darood.app;public class AppLogger {public static void i(String t,String m){}public static void w(String t,String m){}public static void w(String t,String m,Throwable e){}}'
}
foreach($entry in $sources.GetEnumerator()){
 $path=Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $path -Parent))|Out-Null
 [IO.File]::WriteAllText($path,$entry.Value,$utf8)
}
$gson=Get-ChildItem (Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson') -Recurse -Filter 'gson-*.jar' | Where-Object {$_.Name -notmatch 'sources|javadoc'} | Select-Object -First 1 -ExpandProperty FullName
$android=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platforms/android-36/android.jar'
$javaBin=if($env:JAVA_HOME){Join-Path $env:JAVA_HOME 'bin'}else{'C:/Program Files/Java/jdk-21/bin'}
$javaSources=@($sources.Keys|ForEach-Object{Join-Path $testOutput $_})
@('AudioManifest','AudioCache','AudioDownloads')|ForEach-Object{$javaSources+=Join-Path $projectRoot ('app/src/main/java/com/darood/app/'+$_+'.java')}
$javaSources+=Join-Path $PSScriptRoot 'AudioCacheTest.java'
& (Join-Path $javaBin 'javac.exe') -implicit:none -encoding UTF-8 -cp ($gson+';'+$android) -d $testOutput @javaSources
if($LASTEXITCODE -ne 0){throw 'Audio cache tests compilation failed'}
& (Join-Path $javaBin 'java.exe') -cp ($testOutput+';'+$gson+';'+$android) com.darood.app.AudioCacheTest (Join-Path $testOutput 'data')
if($LASTEXITCODE -ne 0){throw 'Audio cache tests failed'}
