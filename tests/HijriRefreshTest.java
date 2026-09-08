package com.darood.app;

// JVM regression harness: real coordinator/cache/schedulers/notification classes.
// Android services, Room storage and HTTP are test doubles, not device integration tests.
import android.content.*;
import android.app.*;
import androidx.core.app.NotificationManagerCompat;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

public final class HijriRefreshTest {
    static final String DATE="2030-05-10";
    static Context context;
    static HijriCoordinator coordinator=HijriCoordinator.get();
    static int checks;
    static final double[] LOCATION={10,20};
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static Method method(String name,Class<?>...types)throws Exception{
        Method m=HijriCoordinator.class.getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    static Object call(String name,Class<?>[]types,Object...args)throws Exception{
        try{return method(name,types).invoke(coordinator,args);}
        catch(InvocationTargetException e){throw new AssertionError(name,e.getCause());}
    }
    static ExecutorService executor()throws Exception{
        Field f=HijriCoordinator.class.getDeclaredField("EXECUTOR");f.setAccessible(true);return (ExecutorService)f.get(coordinator);
    }
    static void drain()throws Exception{executor().submit(()->{}).get(10,TimeUnit.SECONDS);}
    static void restart()throws Exception{
        executor().shutdownNow();
        Constructor<HijriCoordinator> c=HijriCoordinator.class.getDeclaredConstructor();c.setAccessible(true);
        coordinator=c.newInstance();Field f=HijriCoordinator.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,coordinator);
    }
    static HijriDayData data(String date,int day,int month,int year){
        return new HijriDayData(date,day,month,year,"18:00",TimeZone.getDefault().getID(),10,20,System.currentTimeMillis(),false);
    }
    static void cache(String date,int day){HijriCache.putDay(context,data(date,day,8,1451));}
    static void manual(String date,int day){HijriOverrideRepository.get(context).upsert(new HijriOverride(date,day,8,1451,1));}
    static long at(String date,int hour){
        Calendar c=HijriMath.calendarFromIso(date);c.set(Calendar.HOUR_OF_DAY,hour);return c.getTimeInMillis();
    }
    static void reset(String date){
        context=new Context();HijriOverrideRepository.rows.clear();AlarmManager.alarms.clear();AlarmManager.times.clear();
        PendingIntent.existing.clear();NotificationManagerCompat.counts.clear();
        HijriApiClient.calls=0;HijriApiClient.response=null;HijriApiClient.duringFetch=null;
        cache(HijriMath.addDays(date,-1),9);cache(date,10);cache(HijriMath.addDays(date,1),11);
        HijriCache.setLastLocation(context,10,20);
    }
    static void reconcile(String date,int hour)throws Exception{
        call("reconcile",new Class[]{Context.class,double[].class,long.class},context,LOCATION,at(date,hour));
    }
    static int effective(String date,int hour)throws Exception{
        Object resolved=call("computeCore",new Class[]{Context.class,double[].class,long.class},context,LOCATION,at(date,hour));
        Field f=resolved.getClass().getDeclaredField("data");f.setAccessible(true);return ((HijriDayData)f.get(resolved)).hijriDay;
    }
    static int posted(int category){
        return NotificationManagerCompat.counts.entrySet().stream().filter(e->(e.getKey()&0xf0000000)==category).mapToInt(Map.Entry::getValue).sum();
    }
    static void noRefreshAlarm(String message){check(!AlarmManager.alarms.containsKey(7101),message);}
    static void day29Alarm(){
        PendingIntent p=AlarmManager.alarms.get(7101);
        check(p!=null&&p.intent.getIntExtra(HijriSunsetScheduler.EXTRA_DAY,0)==29,"dated day-29 event scheduled");
    }
    public static void main(String[]args)throws Exception{
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        try {
            String next=HijriMath.addDays(DATE,1);
            // Tests 1 and 2: both raw API 28 and raw API 29 must lose to manual 28.
            for(int raw:new int[]{28,29}){
                reset(DATE);cache(DATE,raw);manual(DATE,28);
                reconcile(DATE,12);noRefreshAlarm("manual 28 has no sunset refresh");
                reconcile(DATE,20);
                check(HijriApiClient.calls==0,"normal sunset made no API call");
                check(effective(DATE,20)==28,"manual 28 stays displayed through sunset");
                check(HijriOverrideRepository.rows.get(DATE).hijriDay==28,"manual record unchanged");
                check(posted(0x30000000)==0,"manual 28 suppressed Part 2");
            }
            // Test 3: final manual 29, not raw API 28, controls eligibility.
            reset(DATE);cache(DATE,28);manual(DATE,29);reconcile(DATE,12);day29Alarm();
            HijriApiClient.response=data(next,30,8,1451);reconcile(DATE,20);
            check(HijriApiClient.calls==1&&next.equals(HijriApiClient.requested),"manual 29 checked correct next Gregorian date");
            check(effective(DATE,20)==30,"day 29 -> 30");
            check(HijriOverrideRepository.rows.get(DATE).hijriDay==29,"outgoing manual record remains stored");
            check(posted(0x30000000)==1,"Part 2 fired once");
            // Repeated foreground snapshots reuse the same OS alarm without re-scheduling.
            reset(DATE);cache(DATE,29);reconcile(DATE,12);
            int sets=AlarmManager.setCalls;reconcile(DATE,12);
            check(sets==AlarmManager.setCalls,"duplicate schedules suppressed");
            // Tests 4 and 5, including process restart deduplication.
            for(int incoming:new int[]{30,1}){
                reset(DATE);cache(DATE,29);reconcile(DATE,12);day29Alarm();
                HijriApiClient.response=data(next,incoming,incoming==1?9:8,1451);
                reconcile(DATE,20);reconcile(DATE,20);restart();reconcile(DATE,20);
                check(HijriApiClient.calls==1,"one API request per persisted sunset key");
                check(effective(DATE,20)==incoming,"validated month-end result used");
                check(posted(0x30000000)==1,"Part 2 not duplicated after restart");
                check(posted(0x20000000)==(incoming==1?1:0),"Part 3 once only for day 1");
            }
            // Tests 6 and 7: replace/cancel both event kinds after editing.
            reset(DATE);manual(DATE,29);reconcile(DATE,12);day29Alarm();
            Intent stale=AlarmManager.alarms.get(7101).intent;
            manual(DATE,28);reconcile(DATE,12);noRefreshAlarm("29 -> 28 cancels refresh");
            check(!AlarmManager.alarms.containsKey(7102),"29 -> 28 cancels Part 2");
            CountDownLatch done=new CountDownLatch(1);
            coordinator.onSunsetFired(context,DATE,29,done::countDown);
            check(done.await(5,TimeUnit.SECONDS),"stale receiver finished");check(HijriApiClient.calls==0,"stale alarm cannot refresh");
            manual(DATE,29);reconcile(DATE,12);day29Alarm();
            manual(DATE,30);reconcile(DATE,12);
            check(AlarmManager.alarms.get(7101).intent.getIntExtra(HijriSunsetScheduler.EXTRA_DAY,0)==30,"30 has only offline event");
            check(!AlarmManager.alarms.containsKey(7102),"manual 30 has no day-29 notification");
            // Test 8: ordinary restart has neither network nor generic alarm.
            reset(DATE);restart();reconcile(DATE,12);reconcile(DATE,20);
            check(HijriApiClient.calls==0,"normal day-10 restart is offline");noRefreshAlarm("normal day has no alarm");
            // Test 9: actual async boot path restores the OS alarm despite saved prefs.
            String today=HijriMath.dateIso(System.currentTimeMillis());
            reset(today);
            HijriCache.putDay(context,new HijriDayData(today,29,8,1451,"23:59","UTC",10,20,1,false));
            reconcile(today,12);day29Alarm();check(AlarmManager.alarms.containsKey(7102),"Part 2 initially scheduled");
            AlarmManager.alarms.clear();AlarmManager.times.clear();PendingIntent.existing.clear();
            done=new CountDownLatch(1);coordinator.onBootCompleted(context,done::countDown);
            check(done.await(5,TimeUnit.SECONDS),"boot recovery finished");day29Alarm();
            check(AlarmManager.alarms.containsKey(7102),"Part 2 restored after boot");
            check(HijriApiClient.calls==0,"boot before day-29 sunset is offline");
            // Test 10: direct allowed API path cannot supersede an incoming manual override.
            reset(DATE);manual(DATE,28);
            call("resolveApplicable",new Class[]{Context.class,String.class,double[].class,boolean.class},context,DATE,LOCATION,true);
            check(HijriApiClient.calls==0,"manual authority blocks an otherwise allowed fetch");
            // Incoming manual 28 also blocks the valid outgoing day-29 online transition.
            reset(DATE);cache(DATE,29);manual(next,28);reconcile(DATE,20);
            check(HijriApiClient.calls==0&&effective(DATE,20)==28,"incoming manual blocks API and wins");
            // A response arriving after a correction is cached separately, never made effective.
            reset(DATE);cache(DATE,29);HijriApiClient.response=data(next,1,9,1451);
            HijriApiClient.duringFetch=()->manual(next,28);reconcile(DATE,20);
            check(effective(DATE,20)==28,"in-flight API response cannot override correction");
            check(HijriCache.getDay(context,next).hijriDay==1,"API cache remains separate from manual record");
            // Failure and invalid date/month progression produce a valid local fallback.
            for(int invalid=0;invalid<3;invalid++){
                reset(DATE);manual(DATE,29);
                HijriApiClient.response=invalid==0?null:invalid==1?data(next,29,8,1451):data("2030-05-12",1,9,1451);
                reconcile(DATE,20);reconcile(DATE,20);
                check(effective(DATE,20)==30||effective(DATE,20)==1,"offline/invalid response yields month-end fallback");
                check(HijriApiClient.calls==1,"failure does not cause duplicate API attempts");
                check(HijriOverrideRepository.rows.get(DATE).hijriDay==29,"failure preserves manual data");
            }
            // Year rollover, day-30 offline new-month notification and location-denied fallback.
            reset(DATE);HijriCache.putDay(context,data(DATE,29,12,1451));
            HijriApiClient.response=data(next,1,1,1452);reconcile(DATE,20);
            check(HijriCache.getDay(context,next).hijriYear==1452&&posted(0x20000000)==1,"year rollover accepted");
            reset(DATE);cache(DATE,30);reconcile(DATE,20);
            check(HijriApiClient.calls==0&&effective(DATE,20)==1,"day 30 -> 1 resolves without API");
            check(posted(0x20000000)==1,"offline day-30 transition retains Part 3");
            reset(DATE);manual(DATE,29);
            call("reconcile",new Class[]{Context.class,double[].class,long.class},context,null,at(DATE,20));
            check(HijriApiClient.calls==0,"no location uses offline transition");
            // Cache can initialize from empty or malformed storage.
            context=new Context();HijriCache.putDay(context,data(DATE,10,8,1451));
            check(HijriCache.getDay(context,DATE)!=null,"first cache entry persists");
            context.getSharedPreferences(AppSettings.PREFS_FILE,0).edit().putString("hijri_cache_v2","broken").apply();
            HijriCache.putDay(context,data(DATE,10,8,1451));check(HijriCache.getDay(context,DATE)!=null,"malformed cache recovers");
            // Public save/delete paths immediately reschedule without a location/network refresh.
            reset(today);HijriCache.putDay(context,new HijriDayData(today,28,8,1451,"23:59","UTC",10,20,1,false));
            check("ok".equals(coordinator.saveOverride(context,"{\"gregorianDate\":\""+today+"\",\"hijriDay\":29,\"hijriMonth\":8,\"hijriYear\":1451}")),"save accepted");
            drain();day29Alarm();
            coordinator.saveOverride(context,"{\"gregorianDate\":\""+today+"\",\"hijriDay\":28,\"hijriMonth\":8,\"hijriYear\":1451}");drain();noRefreshAlarm("public edit cancels event");
            coordinator.saveOverride(context,"{\"gregorianDate\":\""+today+"\",\"hijriDay\":29,\"hijriMonth\":8,\"hijriYear\":1451}");drain();day29Alarm();
            coordinator.deleteOverride(context,today);drain();noRefreshAlarm("public delete reverts to raw 28 and cancels");
            check(HijriApiClient.calls==0,"manual CRUD has no normal API refresh");
            // Local timezone/DST source is retained; no city/timezone is hardcoded in production.
            for(String zone:new String[]{"Asia/Dhaka","America/New_York","Europe/London","Australia/Sydney"}){
                TimeZone.setDefault(TimeZone.getTimeZone(zone));reset(DATE);manual(DATE,29);reconcile(DATE,12);day29Alarm();
                check(AlarmManager.times.get(7101)==at(DATE,18)+90000,"sunset scheduled in local timezone "+zone);
            }
            System.out.println(checks+" Hijri refresh checks passed (API/Room/Android test doubles).");
        } finally {executor().shutdownNow();}
    }
}
