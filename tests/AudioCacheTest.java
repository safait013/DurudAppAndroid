package com.darood.app;

import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class AudioCacheTest {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    static String manifest(int revision,int d1,int d2,int s15,String name) throws Exception {
        JSONArray d=new JSONArray().put(new JSONObject().put("id",1).put("file",name).put("version",d1))
                .put(new JSONObject().put("id",2).put("file","durood2.m4a").put("version",d2));
        JSONArray s=new JSONArray().put(new JSONObject().put("id",15).put("file","salam15.m4a").put("version",s15));
        return new JSONObject().put("manifestVersion",revision).put("baseUrl","https://audio.example/audio")
                .put("durood",d).put("salam",s).toString();
    }
    static class Backend implements AudioCache.Backend {
        String manifest;long now=100000000,space=100000000;boolean online=true,failManifest,failTransfer,full,failRename,failMetadata,badAudio;
        final Map<String,String> metadata=new HashMap<>();final List<String> urls=new ArrayList<>();int audioCount,manifestCount;
        CountDownLatch entered,release;
        public long now(){return now;}public boolean online(){return online;}public long free(File f){return space;}
        public String metadata(String k){return metadata.getOrDefault(k,"");}
        public boolean saveMetadata(String k,String value){if(failMetadata)return false;metadata.put(k,value);return true;}
        public boolean storageFailure(IOException e){return e.getMessage().contains("ENOSPC");}
        public void validateAudio(File f)throws IOException{if(badAudio)throw new IOException("invalid audio");}
        public void replace(File a,File b)throws IOException{if(failRename&&b.getName().endsWith(".m4a"))throw new IOException("rename failed");Files.move(a.toPath(),b.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        public AudioCache.Response open(String url)throws IOException {
            boolean isManifest=url.equals(AudioManifest.URL);urls.add(url);
            if(isManifest){manifestCount++;if(failManifest)throw new IOException("HTTP 500");}else{
                audioCount++;
                if(entered!=null){entered.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("test timeout");}catch(InterruptedException e){throw new IOException(e);}}
            }
            byte[] bytes=(isManifest?manifest:"audio bytes "+url).getBytes(StandardCharsets.UTF_8);
            InputStream input=new ByteArrayInputStream(bytes){boolean read;@Override public synchronized int read(byte[] b,int off,int length){return super.read(b,off,length);}};
            if(!isManifest&&(failTransfer||full))input=new InputStream(){int n;public int read()throws IOException{if(n++>2)throw new IOException(full?"ENOSPC":"connection lost");return 1;}};
            final InputStream stream=input;
            return new AudioCache.Response(){public InputStream stream(){return stream;}public long length(){return bytes.length;}public void close()throws IOException{stream.close();}};
        }
        void refresh(){now+=AudioCache.MANIFEST_TTL+1;}
    }
    interface Operation {void run()throws Exception;}
    static void failure(AudioCache.Failure expected,Operation op)throws Exception{
        try{op.run();throw new AssertionError("expected "+expected);}catch(AudioCache.AudioException e){check(e.failure==expected,"error "+expected);}
    }
    static long temps(File root)throws IOException{try(java.util.stream.Stream<Path>s=Files.walk(root.toPath())){return s.filter(p->p.toString().endsWith(".tmp")).count();}}
    public static void main(String[] args)throws Exception{
        File root=new File(args[0],UUID.randomUUID().toString());root.mkdirs();
        Backend b=new Backend();b.manifest=manifest(1,1,1,1,"durood1.m4a");AudioCache c=new AudioCache(root,b);
        AudioCache.LocalAudio first=c.resolve("durood",1);
        check(first.file.isFile()&&b.audioCount==1,"first download creates local audio");
        check(new JSONObject(b.metadata(first.key)).optInt("version",0)==1,"version persisted after download");
        check(c.resolve("durood",1).file.equals(first.file)&&b.audioCount==1&&b.manifestCount==1,"second play uses cache and fresh manifest without requests");
        AudioCache.LocalAudio second=c.resolve("durood",2);
        AudioCache.LocalAudio salam=c.resolve("salam",15);
        check(b.urls.contains("https://audio.example/audio/salam/salam15.m4a?audioVersion=1"),"Final Hadith URL uses Salam ID 15");
        b.manifest=manifest(1,2,1,1,"durood1.m4a");b.refresh();int count=b.audioCount;
        AudioCache.LocalAudio updated=c.resolve("durood",1);
        check(b.audioCount==count+1&&!updated.file.equals(first.file)&&!first.file.exists(),"single-file version update replaces old generation");
        check(new JSONObject(b.metadata(first.key)).optInt("version",0)==2,"new version saved");
        c.resolve("durood",2);c.resolve("salam",15);
        check(b.audioCount==count+1&&second.file.exists()&&salam.file.exists(),"other Durood and Salam cache untouched");
        b.manifest=manifest(2,2,1,1,"durood1.m4a");b.refresh();c.resolve("durood",2);
        check(b.audioCount==count+1,"manifestVersion alone does not invalidate audio");
        b.online=false;b.refresh();check(c.resolve("durood",1).file.equals(updated.file),"offline valid cache works with stale manifest timestamp");
        AudioCache restarted=new AudioCache(root,b);check(restarted.resolve("salam",15).file.equals(salam.file),"persisted manifest/metadata survive process recreation offline");
        failure(AudioCache.Failure.NO_INTERNET,()->new AudioCache(new File(root,"empty"),b).resolve("durood",1));
        b.online=true;b.manifest=manifest(2,3,1,1,"durood1.m4a");b.refresh();c.resolve("durood",2);b.online=false;
        failure(AudioCache.Failure.NO_INTERNET,()->c.resolve("durood",1));
        check(updated.file.exists()&&new JSONObject(b.metadata(first.key)).optInt("version",0)==2,"known stale offline retains old data without marking current");
        b.online=true;b.space=1;failure(AudioCache.Failure.STORAGE,()->c.resolve("durood",1));
        b.space=100000000;String oldMeta=b.metadata(first.key);
        b.failTransfer=true;failure(AudioCache.Failure.DOWNLOAD,()->c.resolve("durood",1));b.failTransfer=false;
        check(updated.file.exists()&&b.metadata(first.key).equals(oldMeta)&&temps(root)==0,"half-download preserves old cache and metadata, cleans temp");
        b.full=true;failure(AudioCache.Failure.STORAGE,()->c.resolve("durood",1));b.full=false;
        check(updated.file.exists()&&b.metadata(first.key).equals(oldMeta)&&temps(root)==0,"mid-download ENOSPC safely rolls back");
        b.failRename=true;failure(AudioCache.Failure.DOWNLOAD,()->c.resolve("durood",1));b.failRename=false;
        check(updated.file.exists()&&b.metadata(first.key).equals(oldMeta),"rename failure preserves old cache");
        b.failMetadata=true;failure(AudioCache.Failure.STORAGE,()->c.resolve("durood",1));b.failMetadata=false;
        check(updated.file.exists()&&b.metadata(first.key).equals(oldMeta),"metadata commit failure preserves old generation");
        b.badAudio=true;failure(AudioCache.Failure.DOWNLOAD,()->c.resolve("durood",1));b.badAudio=false;
        b.manifest=manifest(2,3,1,1,"durood1_new.m4a");b.refresh();AudioCache.LocalAudio renamed=c.resolve("durood",1);
        check(renamed.file.getName().endsWith("durood1_new.m4a")&&!updated.file.exists(),"filename change replaces only after successful transaction");
        b.manifest=manifest(2,3,1,1,"renamed_again.m4a");b.refresh();count=b.audioCount;c.resolve("durood",1);
        check(b.audioCount==count+1,"filename change detected even with same item version");
        Files.write(second.file.toPath(),new byte[0]);count=b.audioCount;second=c.resolve("durood",2);
        check(b.audioCount==count+1&&second.file.length()>0,"zero byte cache redownloads only requested item");
        Files.write(second.file.toPath(),new byte[]{1,2,3});count=b.audioCount;c.resolve("durood",2);
        check(b.audioCount==count+1,"checksum detects nonzero corrupt cache");
        b.failManifest=true;b.refresh();count=b.audioCount;c.resolve("salam",15);
        check(b.audioCount==count,"manifest failure uses valid last-success cache");b.failManifest=false;
        b.manifest=manifest(3,3,1,2,"renamed_again.m4a");b.refresh();count=b.audioCount;c.resolve("salam",15);c.resolve("durood",1);
        check(b.audioCount==count+1,"Salam version change leaves Durood untouched");
        failure(AudioCache.Failure.UNAVAILABLE,()->c.resolve("durood",25));
        for(String bad:new String[]{"../durood1.m4a","folder/a.m4a","a\\b.m4a","%2fescape.m4a"}){
            AudioManifest invalid=AudioManifest.parse(manifest(1,1,1,1,bad));check(invalid.item("durood",1)==null,"unsafe filename rejected");
        }
        String duplicate=manifest(1,1,1,1,"durood1.m4a").replace("\"id\":2","\"id\":1");
        check(AudioManifest.parse(duplicate).item("durood",1)==null,"duplicate IDs invalidate that item");
        check(AudioManifest.parse(manifest(1,0,1,1,"durood1.m4a")).item("durood",1)==null,"nonpositive item version rejected");
        try{AudioManifest.parse(manifest(0,1,1,1,"durood1.m4a"));throw new AssertionError();}catch(IllegalArgumentException expected){checks++;}
        Backend coalesced=new Backend();coalesced.manifest=manifest(1,1,1,1,"durood1.m4a");
        AudioCache concurrentCache=new AudioCache(new File(root,"coalesced"),coalesced);concurrentCache.resolve("durood",2);
        coalesced.audioCount=0;coalesced.entered=new CountDownLatch(1);coalesced.release=new CountDownLatch(1);
        ExecutorService worker=Executors.newFixedThreadPool(2);android.os.Handler main=new android.os.Handler();
        AudioDownloads downloads=new AudioDownloads(concurrentCache,worker,main);
        int[] delivered={0};downloads.request("durood",1,(a,e)->{if(e==null)delivered[0]++;});
        check(coalesced.entered.await(5,TimeUnit.SECONDS),"first download entered");
        downloads.request("durood",1,(a,e)->{if(e==null)delivered[0]++;});
        int[] cacheDelivered={0};downloads.request("durood",2,(a,e)->{if(e==null)cacheDelivered[0]++;});
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(main.pending.isEmpty()&&System.nanoTime()<deadline)Thread.sleep(10);
        main.drain();check(cacheDelivered[0]==1&&delivered[0]==0,"cached B is not blocked by downloading A");
        coalesced.release.countDown();
        worker.shutdown();check(worker.awaitTermination(5,TimeUnit.SECONDS),"download worker completed");main.drain();
        check(coalesced.audioCount==1&&delivered[0]==2,"same-key simultaneous requests share one download");
        check(temps(root)==0,"no temp files remain after all failures/successes");
        System.out.println(checks+" manifest/cache/coalescing checks passed (real files, HTTP/storage/Android doubles).");
    }
}
