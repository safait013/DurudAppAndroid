# Actual AppSettings and LanguageManager, with in-memory Android preferences/configuration doubles.
$base = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'hijri-refresh.test.ps1'))
$base = $base.Replace('$PSScriptRoot', ("'" + $PSScriptRoot.Replace("'", "''") + "'"))
Invoke-Expression $base.Substring(0, $base.IndexOf('foreach ($entry in $sources.GetEnumerator())'))
$preferences = $sources['android/content/SharedPreferences.java'].Replace(' public String getString', ' public boolean contains(String k){return values.containsKey(k);} public boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);} public int getInt(String k,int d){return (int)values.getOrDefault(k,d);} public String getString').Replace('  public Editor putString(', '  public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;} public Editor putInt(String k,int v){changes.put(k,v);return this;} public Editor putString(')
$sources = @{
'android/content/SharedPreferences.java' = $preferences
'android/content/Context.java' = @'
package android.content;import android.content.res.*;public class Context {
 public static final int MODE_PRIVATE=0;private final SharedPreferences prefs=new SharedPreferences();public Configuration applied;
 public Context getApplicationContext(){return this;}public SharedPreferences getSharedPreferences(String n,int m){return prefs;}
 public Resources getResources(){return new Resources();}public Context createConfigurationContext(Configuration c){applied=c;return this;}
}
'@
'android/content/res/Resources.java' = 'package android.content.res;public class Resources {public Configuration getConfiguration(){return new Configuration();}}'
'android/content/res/Configuration.java' = @'
package android.content.res;import java.util.Locale;import android.os.LocaleList;public class Configuration {
 public Locale locale=Locale.getDefault();public boolean rtl;public Configuration(){}public Configuration(Configuration c){locale=c.locale;}
 public void setLocales(LocaleList l){locale=l.get(0);}public void setLocale(Locale l){locale=l;}public void setLayoutDirection(Locale l){rtl=l.getLanguage().equals("ur");}
}
'@
'android/os/LocaleList.java' = 'package android.os;import java.util.Locale;public class LocaleList {private final Locale l;public LocaleList(Locale l){this.l=l;}public Locale get(int i){return l;}}'
'android/os/Build.java' = 'package android.os;public class Build {public static class VERSION {public static int SDK_INT=36;}public static class VERSION_CODES {public static final int N=24;}}'
'com/darood/app/AppLogger.java' = 'package com.darood.app;public class AppLogger {public static void i(String t,String m){}}'
'com/darood/app/FirstLaunchTest.java' = @'
package com.darood.app;import android.content.Context;import java.util.Locale;public class FirstLaunchTest {
 static int checks;static void check(boolean b,String s){if(!b)throw new AssertionError(s);checks++;}
 public static void main(String[] args){
  for(String device:new String[]{"en","bn","ur"}){
   Locale.setDefault(new Locale(device));Context c=new Context();
   LanguageManager.applyLanguage(c);check(c.applied.locale.getLanguage().equals("en"),"unsaved language never follows device");
   AppSettings.initializeLaunchPreferences(c);
   check(!AppSettings.isSetupComplete(c)&&LanguageManager.getSavedLanguage(c).equals("en"),"fresh install English and setup required");
   AppSettings.saveArabicFont(c,"lateef");AppSettings.saveArabicFontSize(c,30);AppSettings.saveTheme(c,"purple");
   LanguageManager.saveLanguage(c,"bn");AppSettings.initializeLaunchPreferences(c);
   check(!AppSettings.isSetupComplete(c)&&LanguageManager.getSavedLanguage(c).equals("bn"),"unfinished setup retains choice after recreation");
   check(AppSettings.getArabicFont(c).equals("lateef")&&AppSettings.getArabicFontSize(c)==30&&AppSettings.getTheme(c).equals("purple"),"setup preferences preserved");
   AppSettings.saveSetupCompleted(c,true);AppSettings.initializeLaunchPreferences(c);
   check(AppSettings.isSetupComplete(c)&&LanguageManager.getSavedLanguage(c).equals("bn"),"completed relaunch opens Home in Bangla");
   LanguageManager.saveLanguage(c,"ur");LanguageManager.applyLanguage(c);check(c.applied.rtl,"Urdu RTL resources");
  }
  Context legacy=new Context();LanguageManager.saveLanguage(legacy,"ur");AppSettings.initializeLaunchPreferences(legacy);
  check(AppSettings.isSetupComplete(legacy)&&LanguageManager.getSavedLanguage(legacy).equals("ur"),"legacy existing-user upgrade bypasses setup");
  Context existing=new Context();AppSettings.saveSetupCompleted(existing,true);LanguageManager.saveLanguage(existing,"bn");AppSettings.initializeLaunchPreferences(existing);
  check(AppSettings.isSetupComplete(existing)&&LanguageManager.getSavedLanguage(existing).equals("bn"),"app update preserves explicit completed flag and language");
  System.out.println(checks+" first-launch preference/language checks passed (Android doubles).");
 }
}
'@
}
$testOutput = Join-Path $projectRoot 'build/first-launch-tests'
foreach ($entry in $sources.GetEnumerator()) {
 $file = Join-Path $testOutput $entry.Key
 [IO.Directory]::CreateDirectory((Split-Path $file -Parent)) | Out-Null
 [IO.File]::WriteAllText($file,$entry.Value,$utf8)
}
$javaSources = @($sources.Keys | ForEach-Object { Join-Path $testOutput $_ })
@('AppSettings','LanguageManager') | ForEach-Object { $javaSources += Join-Path $projectRoot ('app/src/main/java/com/darood/app/' + $_ + '.java') }
& (Join-Path $env:JAVA_HOME 'bin/javac.exe') -encoding UTF-8 -d $testOutput @javaSources
if ($LASTEXITCODE -ne 0) { throw 'First-launch tests compilation failed' }
& (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $testOutput com.darood.app.FirstLaunchTest
if ($LASTEXITCODE -ne 0) { throw 'First-launch tests failed' }
