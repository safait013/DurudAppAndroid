package com.darood.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.JSONObject;

/** Per-item cache transactions; manifest refresh and metadata commits are synchronized independently of downloads. */
final class AudioCache {
    static final long MANIFEST_TTL = 2 * 60 * 60_000L;
    static final long RETRY_DELAY = 60_000L;
    static final long MARGIN = 2 * 1024 * 1024L;
    static final long MAX_AUDIO = 64 * 1024 * 1024L;
    enum Failure { NO_INTERNET, STORAGE, DOWNLOAD, UNAVAILABLE }
    static final class AudioException extends Exception {
        final Failure failure;
        AudioException(Failure failure) { super(failure.name()); this.failure = failure; }
    }
    interface Response extends AutoCloseable {
        InputStream stream() throws IOException;
        long length();
        @Override void close() throws IOException;
    }
    interface Backend {
        long now();
        boolean online();
        long free(File directory);
        Response open(String url) throws IOException;
        void replace(File temp, File target) throws IOException;
        void validateAudio(File file) throws IOException;
        boolean storageFailure(IOException error);
        String metadata(String key);
        boolean saveMetadata(String key, String json);
    }
    static final class LocalAudio {
        final File file;
        final String key, record;
        LocalAudio(File file, String key, String record) { this.file = file; this.key = key; this.record = record; }
    }
    private final File root;
    private final Backend io;
    private AudioManifest manifest;
    private long fetchedAt, lastAttempt;
    private boolean loaded, attempted;

    AudioCache(File root, Backend io) { this.root = root; this.io = io; }

    LocalAudio resolve(String category, int id) throws AudioException {
        if (!AudioManifest.validId(category, id)) throw new AudioException(Failure.UNAVAILABLE);
        AppLogger.i("AudioCache", "Request " + category + ":" + id);
        AudioManifest current = usableManifest();
        AudioManifest.Item item = current.item(category, id);
        if (item == null) {
            AppLogger.w("AudioCache", "Manifest item unavailable " + category + ":" + id);
            throw new AudioException(Failure.UNAVAILABLE);
        }
        String key = "audio_version_" + item.key();
        String oldRecord = io.metadata(key);
        JSONObject old = json(oldRecord);
        File directory = new File(root, category);
        File oldFile = storedFile(directory, old, id);
        int localVersion = old == null ? 0 : old.optInt("version", 0);
        boolean sameName = old != null && item.file.equals(old.optString("file", ""));
        AppLogger.i("AudioCache", item.key() + " file=" + item.file + " remoteVersion=" + item.version
                + " localVersion=" + localVersion + " filenameChanged=" + (old != null && !sameName));
        if (oldFile != null && oldFile.isFile() && oldFile.length() > 0 && sameName && localVersion == item.version) {
            try {
                if (digest(oldFile).equals(old.optString("sha256", ""))) {
                    AppLogger.i("AudioCache", "Cache hit " + item.key());
                    return new LocalAudio(oldFile, key, oldRecord);
                }
            } catch (IOException ignored) { /* A broken cache needs only this item's download. */ }
            AppLogger.w("AudioCache", "Corrupt cache " + item.key());
        }
        AppLogger.i("AudioCache", (old == null ? "Cache miss " : "Cache stale/missing ") + item.key());
        if (!io.online()) throw new AudioException(Failure.NO_INTERNET);
        File temp = null, target = null;
        boolean committed = false;
        try {
            ensureDirectory(directory);
            requireSpace(directory, MARGIN);
            // Immutable generations keep the old file AND metadata valid until the transaction commits.
            String generation = id + "_" + UUID.randomUUID() + "_" + item.file;
            target = new File(directory, generation);
            temp = new File(directory, generation + ".tmp");
            String url = item.url(current.baseUrl);
            AppLogger.i("AudioCache", "Download start " + item.key() + " version=" + item.version);
            // Supabase/CDN may otherwise serve an older response at an unchanged filename.
            try (Response response = io.open(url + "?audioVersion=" + item.version)) {
                long expected = response.length();
                if (expected == 0 || expected > MAX_AUDIO) throw new IOException("Invalid audio length");
                requireSpace(directory, (expected > 0 ? expected : 1024 * 1024L) + MARGIN);
                long total = 0, started = io.now();
                try (InputStream in = response.stream(); FileOutputStream out = new FileOutputStream(temp)) {
                    byte[] buffer = new byte[32768];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_AUDIO || io.now() - started > 60_000) throw new IOException("Audio transfer limit");
                        requireSpace(directory, count + MARGIN);
                        out.write(buffer, 0, count);
                    }
                    out.getFD().sync();
                }
                if (total == 0 || (expected >= 0 && total != expected)) throw new IOException("Incomplete audio transfer");
            }
            io.validateAudio(temp);
            String checksum = digest(temp);
            io.replace(temp, target);
            AppLogger.i("AudioCache", "Cache replacement ready " + item.key());
            String record = new JSONObject().put("version", item.version).put("file", item.file)
                    .put("local", target.getName()).put("sha256", checksum).toString();
            synchronized (this) {
                if (!io.saveMetadata(key, record)) throw new AudioException(Failure.STORAGE);
                committed = true;
            }
            AppLogger.i("AudioCache", "Download success; version metadata saved " + item.key() + "=" + item.version);
            if (oldFile != null && !oldFile.equals(target)) remove(oldFile);
            return new LocalAudio(target, key, record);
        } catch (AudioException e) {
            AppLogger.w("AudioCache", "Download failed " + item.key() + " " + e.failure);
            throw e;
        } catch (IOException e) {
            Failure failure = io.storageFailure(e) || io.free(root) < MARGIN ? Failure.STORAGE
                    : !io.online() ? Failure.NO_INTERNET : Failure.DOWNLOAD;
            AppLogger.w("AudioCache", "Download I/O failure " + item.key() + " " + failure, e);
            throw new AudioException(failure);
        } catch (Exception e) {
            AppLogger.w("AudioCache", "Download validation failure " + item.key(), e);
            throw new AudioException(Failure.DOWNLOAD);
        } finally {
            if (temp != null) remove(temp);
            if (!committed && target != null) remove(target);
        }
    }

    /** Playback errors invalidate only the exact generation that failed, never a newer download. */
    synchronized void invalidate(LocalAudio audio) {
        if (audio.record.equals(io.metadata(audio.key))) {
            if (io.saveMetadata(audio.key, "")) remove(audio.file);
            AppLogger.w("AudioCache", "Playback-invalid cache " + audio.key);
        }
    }

    private synchronized AudioManifest usableManifest() throws AudioException {
        File cache = new File(root, "audio_manifest.json");
        if (!loaded) {
            loaded = true;
            try {
                JSONObject saved = new JSONObject(read(cache));
                manifest = AudioManifest.parse(saved.getString("manifest"));
                fetchedAt = saved.optLong("fetchedAt", 0);
            } catch (Exception e) { AppLogger.i("AudioCache", "No usable cached manifest"); }
        }
        long now = io.now();
        boolean stale = manifest == null || now < fetchedAt || now - fetchedAt >= MANIFEST_TTL;
        if (stale && io.online() && (!attempted || now < lastAttempt || now - lastAttempt >= RETRY_DELAY)) {
            lastAttempt = now; attempted = true;
            File temp = new File(root, "audio_manifest.json.tmp");
            try (Response response = io.open(AudioManifest.URL)) {
                String raw;
                try (InputStream in = response.stream()) { raw = read(in); }
                AudioManifest fresh = AudioManifest.parse(raw);
                // Invalid JSON/headers never replace the last successful manifest.
                manifest = fresh; fetchedAt = now;
                try {
                    ensureDirectory(root);
                    byte[] bytes = new JSONObject().put("fetchedAt", now).put("manifest", raw)
                            .toString().getBytes(StandardCharsets.UTF_8);
                    try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
                    io.replace(temp, cache);
                } catch (Exception e) { AppLogger.w("AudioCache", "Manifest persistence failed; using fetched manifest", e); }
                AppLogger.i("AudioCache", "Manifest source=remote manifestVersion=" + fresh.manifestVersion);
                return manifest;
            } catch (Exception e) {
                AppLogger.w("AudioCache", "Manifest refresh failed; preserving cache", e);
            } finally { remove(temp); }
        }
        if (manifest == null) throw new AudioException(io.online() ? Failure.DOWNLOAD : Failure.NO_INTERNET);
        AppLogger.i("AudioCache", "Manifest source=cache manifestVersion=" + manifest.manifestVersion);
        return manifest;
    }

    private static JSONObject json(String raw) { try { return new JSONObject(raw); } catch (Exception e) { return null; } }
    private static File storedFile(File directory, JSONObject record, int id) {
        if (record == null) return null;
        String local = record.optString("local", "");
        // Only generated names in this category directory; metadata cannot name another item's file.
        if (!local.startsWith(id + "_") || !local.matches("[0-9]+_[0-9a-f-]{36}_[A-Za-z0-9][A-Za-z0-9._-]*\\.m4a") || local.contains("..")) return null;
        return new File(directory, local);
    }
    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private audio directory");
    }
    private void requireSpace(File directory, long bytes) throws AudioException {
        if (io.free(directory) < bytes) throw new AudioException(Failure.STORAGE);
    }
    private static String read(File file) throws IOException { try (InputStream in = new FileInputStream(file)) { return read(in); } }
    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
        while ((n = in.read(buffer)) != -1) { if (out.size() + n > 256 * 1024) throw new IOException("Manifest too large"); out.write(buffer, 0, n); }
        return out.toString("UTF-8");
    }
    private static String digest(File file) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) { byte[] b = new byte[32768]; int n; while ((n = in.read(b)) != -1) hash.update(b, 0, n); }
            StringBuilder result = new StringBuilder();
            for (byte b : hash.digest()) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }
    private static void remove(File file) {
        if (file.exists()) AppLogger.i("AudioCache", "Temp/obsolete generation cleanup=" + file.delete());
    }
}
