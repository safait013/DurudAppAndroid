package com.darood.app;
import android.content.Context;
import android.media.MediaPlayer;
public class PronunciationCachePlayerTest {
    static int checks,id;static boolean playing,salam;
    static void check(boolean ok,String m){if(!ok)throw new AssertionError(m);checks++;}
    static void idle(){check(!playing&&id==0&&MediaPlayer.live==0,"idle state and released player");}
    static void startLatest(){AudioDownloads.finish(AudioDownloads.requests-1);MediaPlayer.last.prepared.onPrepared(MediaPlayer.last);}
    public static void main(String[] args){
        Context c=new Context();DuroodAudioPlayer p=new DuroodAudioPlayer((i,b,s)->{id=i;playing=b;salam=s;});
        for(int i=1;i<=25;i++){
            check(DuroodAudioPlayer.hasAudio(i),"Durood button available without network");
            p.toggle(c,i);check(AudioDownloads.keys.get(AudioDownloads.requests-1).equals("durood:"+i),"stable Durood manifest ID");
            startLatest();check(id==i&&playing&&!salam&&MediaPlayer.path.endsWith("durood_"+i+".m4a"),"local Durood playback");
            p.toggle(c,i);idle();
        }
        for(int i=1;i<=15;i++){
            check(DuroodAudioPlayer.hasSalamAudio(i),"Salam button available without network");
            p.toggleSalam(c,i);check(AudioDownloads.keys.get(AudioDownloads.requests-1).equals("salam:"+i),"Salam uses ID 1..15, not Hadith 26..40");
            startLatest();check(id==i&&playing&&salam&&MediaPlayer.path.endsWith("salam_"+i+".m4a"),"local Salam playback");
            MediaPlayer.last.completed.onCompletion(MediaPlayer.last);idle();
        }
        p.toggle(c,12);int a=AudioDownloads.requests-1;p.toggle(c,12);
        check(AudioDownloads.requests==a+1,"repeat pending tap does not start another request");
        p.toggleSalam(c,15);int b=AudioDownloads.requests-1;
        AudioDownloads.finish(a);check(MediaPlayer.live==0,"late Durood download cannot play over latest Salam intent");
        startLatest();MediaPlayer previous=MediaPlayer.last;
        check(id==15&&playing&&salam,"latest Salam starts");
        p.toggle(c,25);check(previous.released,"Salam stops before Durood download");startLatest();
        check(id==25&&playing&&!salam,"Salam to Durood switch");
        previous.prepared.onPrepared(previous);previous.completed.onCompletion(previous);previous.error.onError(previous,1,2);
        check(id==25&&playing,"stale MediaPlayer callbacks ignored");
        MediaPlayer.last.error.onError(MediaPlayer.last,1,2);idle();
        check(AudioDownloads.invalidated==1,"playback error invalidates only failed cached generation");
        p.toggle(c,1);a=AudioDownloads.requests-1;p.stop();AudioDownloads.finish(a);idle();
        check(AudioDownloads.cancellations>0,"Stop/navigation detaches pending callback");
        for(AudioCache.Failure f:AudioCache.Failure.values()){
            p.toggleSalam(c,1);AudioDownloads.callbacks.get(AudioDownloads.requests-1).complete(null,f);idle();
            int expected=f==AudioCache.Failure.NO_INTERNET?1:f==AudioCache.Failure.STORAGE?2:f==AudioCache.Failure.DOWNLOAD?3:4;
            check(android.widget.Toast.message==expected,"localized failure category "+f);
        }
        p.toggle(c,1);AudioDownloads.finish(AudioDownloads.requests-1);MediaPlayer pending=MediaPlayer.last;
        p.stop();pending.prepared.onPrepared(pending);idle();check(!pending.started,"Stop while preparing prevents restart");
        p.toggle(c,1);MediaPlayer.failStart=true;startLatest();idle();MediaPlayer.failStart=false;
        check(MediaPlayer.maxLive==1,"only one allocated player across all flows");
        check(!DuroodAudioPlayer.hasAudio(26)&&!DuroodAudioPlayer.hasSalamAudio(16),"invalid IDs unavailable");
        System.out.println(checks+" cache-backed pronunciation lifecycle checks passed (Android/download doubles).");
    }
}
