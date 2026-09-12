package com.darood.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

/** Application-scoped download coalescing. No networking or disk access during UI availability checks. */
final class AudioDownloads {
    interface Callback { void complete(AudioCache.LocalAudio audio, AudioCache.Failure error); }
    interface Request { void cancel(); }
    private static AudioDownloads instance;
    private final AudioCache cache;
    private final Handler main;
    private final ExecutorService worker;
    private final Map<String, Job> jobs = new HashMap<>();
    private static final class Job { final List<Callback> listeners = new ArrayList<>(); }

    static synchronized AudioDownloads get(Context context) {
        if (instance == null) instance = new AudioDownloads(context.getApplicationContext());
        return instance;
    }
    private AudioDownloads(Context context) {
        this(new AudioCache(new File(context.getFilesDir(), "audio"), new AndroidBackend(context)),
                workers(), new Handler(Looper.getMainLooper()));
    }
    private static ExecutorService workers() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                task -> new Thread(task, "PronunciationCache"));
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }
    AudioDownloads(AudioCache cache, ExecutorService worker, Handler main) {
        this.cache = cache; this.worker = worker; this.main = main;
    }

    synchronized Request request(String category, int id, Callback callback) {
        String key = category + ":" + id;
        Job job = jobs.get(key);
        if (job == null) {
            job = new Job(); jobs.put(key, job);
            Job pending = job;
            job.listeners.add(callback);
            worker.execute(() -> {
                synchronized (AudioDownloads.this) {
                    if (pending.listeners.isEmpty()) { jobs.remove(key); return; }
                }
                AudioCache.LocalAudio audio = null; AudioCache.Failure error = null;
                try { audio = cache.resolve(category, id); }
                catch (AudioCache.AudioException e) { error = e.failure; }
                catch (RuntimeException e) { AppLogger.w("AudioCache", "Unexpected audio request failure", e); error = AudioCache.Failure.DOWNLOAD; }
                List<Callback> listeners;
                synchronized (AudioDownloads.this) { jobs.remove(key); listeners = new ArrayList<>(pending.listeners); pending.listeners.clear(); }
                AudioCache.LocalAudio result = audio; AudioCache.Failure failure = error;
                main.post(() -> { for (Callback listener : listeners) listener.complete(result, failure); });
            });
        } else {
            job.listeners.add(callback);
            AppLogger.i("AudioCache", "Duplicate download prevented " + key);
        }
        Job subscription = job;
        return () -> { synchronized (AudioDownloads.this) { subscription.listeners.remove(callback); } };
    }

    void invalidate(AudioCache.LocalAudio audio) { worker.execute(() -> cache.invalidate(audio)); }

    private static final class AndroidBackend implements AudioCache.Backend {
        private final Context context;
        private final SharedPreferences prefs;
        AndroidBackend(Context context) {
            this.context = context;
            prefs = context.getSharedPreferences(AppSettings.PREFS_FILE, Context.MODE_PRIVATE);
        }
        public long now() { return System.currentTimeMillis(); }
        @SuppressWarnings("deprecation") public boolean online() {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            if (Build.VERSION.SDK_INT >= 23) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
                return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            }
            android.net.NetworkInfo active = manager.getActiveNetworkInfo();
            return active != null && active.isConnected();
        }
        public long free(File directory) {
            while (!directory.exists() && directory.getParentFile() != null) directory = directory.getParentFile();
            return directory.getUsableSpace();
        }
        public String metadata(String key) { return prefs.getString(key, ""); }
        public boolean saveMetadata(String key, String json) {
            String previous = prefs.getString(key, "");
            if (prefs.edit().putString(key, json).commit()) return true;
            // SharedPreferences changes its in-memory map even when the disk commit fails.
            prefs.edit().putString(key, previous).commit();
            return false;
        }
        public void replace(File temp, File target) throws IOException {
            try { Os.rename(temp.getAbsolutePath(), target.getAbsolutePath()); }
            catch (ErrnoException e) { throw new IOException("Atomic audio rename failed", e); }
        }
        public boolean storageFailure(IOException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ErrnoException && ((ErrnoException) cause).errno == OsConstants.ENOSPC) return true;
                if (cause.getMessage() != null && (cause.getMessage().contains("ENOSPC")
                        || cause.getMessage().toLowerCase(Locale.US).contains("no space left"))) return true;
            }
            return false;
        }
        public void validateAudio(File file) throws IOException {
            MediaMetadataRetriever metadata = new MediaMetadataRetriever();
            try {
                metadata.setDataSource(file.getAbsolutePath());
                if (!"yes".equals(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)))
                    throw new IOException("Downloaded file has no decodable audio track");
            } catch (RuntimeException e) { throw new IOException("Invalid downloaded audio", e); }
            finally { try { metadata.release(); } catch (Exception ignored) { } }
        }
        public AudioCache.Response open(String address) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000); connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Cache-Control", "no-cache");
            try {
                int status = connection.getResponseCode();
                if (status != 200) { AppLogger.w("AudioCache", "HTTP failure status=" + status); throw new IOException("Audio HTTP " + status); }
                long length;
                try { length = Long.parseLong(connection.getHeaderField("Content-Length")); } catch (Exception e) { length = -1; }
                final long expected = length;
                InputStream source = connection.getInputStream();
                long started = android.os.SystemClock.elapsedRealtime();
                InputStream bounded = new FilterInputStream(source) {
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        if (android.os.SystemClock.elapsedRealtime() - started > 60_000) throw new IOException("Audio request timed out");
                        return super.read(b, off, len);
                    }
                };
                return new AudioCache.Response() {
                    public InputStream stream() { return bounded; }
                    public long length() { return expected; }
                    public void close() throws IOException { try { bounded.close(); } finally { connection.disconnect(); } }
                };
            } catch (IOException | RuntimeException e) { connection.disconnect(); throw e; }
        }
    }
}
