package com.darood.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

/** One player owned by MainActivity; all playback operations run on the UI thread. */
final class DuroodAudioPlayer {
    private static final String TAG = "DuroodAudio";
    private long generation;
    private AudioDownloads.Request request;
    private boolean downloading;
    interface StateListener {
        void onStateChanged(int id, boolean playing, boolean salam);
    }

    private final StateListener listener;
    private boolean playing;

    DuroodAudioPlayer(StateListener listener) {
        this.listener = listener;
    }

    void publishState() {
        listener.onStateChanged(activeId, playing, activeSalam);
    }

    void toggle(Context context, int id) {
        toggle(context, id, false);
    }

    void toggleSalam(Context context, int id) {
        toggle(context, id, true);
    }

    private void toggle(Context context, int id, boolean salam) {
        if (downloading && activeId == id && activeSalam == salam) {
            AppLogger.i(TAG, "Duplicate request ignored while downloading");
            return;
        }
        if (player != null && activeId == id && activeSalam == salam) stop();
        else play(context, id, salam);
    }

    private MediaPlayer player;
    private int activeId;
    private boolean activeSalam;

    static boolean hasAudio(int id) {
        return AudioManifest.validId("durood", id);
    }

    static boolean hasSalamAudio(int id) {
        return AudioManifest.validId("salam", id);
    }

    void play(Context context, int id) {
        play(context, id, false);
    }

    private void play(Context context, int id, boolean salam) {
        stop();
        final String content = (salam ? "Salam" : "Durood") + " ID=" + id;
        if (!(salam ? hasSalamAudio(id) : hasAudio(id))) {
            AppLogger.w(TAG, "Missing audio resource; " + content);
            return;
        }
        activeId = id;
        activeSalam = salam;
        downloading = true;
        long expected = generation;
        Context application = context.getApplicationContext();
        request = AudioDownloads.get(application).request(salam ? "salam" : "durood", id, (audio, failure) -> {
            if (generation != expected) return; // Stop, navigation or another item wins over late downloads.
            request = null; downloading = false;
            if (failure != null) { stop(); showFailure(application, failure); return; }
            startLocal(application, audio, content);
        });
    }

    private void startLocal(Context context, AudioCache.LocalAudio audio, String content) {
        try {
            MediaPlayer next = new MediaPlayer();
            player = next;
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            next.setOnPreparedListener(prepared -> {
                if (player != prepared) return;
                try {
                    prepared.start();
                    playing = true;
                    publishState();
                    AppLogger.i(TAG, "Playback started; " + content);
                } catch (RuntimeException e) {
                    AppLogger.e(TAG, "Playback failed; " + content, e);
                    stop();
                    showFailure(context, AudioCache.Failure.UNAVAILABLE);
                }
            });
            next.setOnCompletionListener(completed -> {
                if (player != completed) return;
                AppLogger.i(TAG, "Playback completed; " + content);
                stop();
            });
            next.setOnErrorListener((failed, what, extra) -> {
                if (player == failed) {
                    AppLogger.e(TAG, "Playback failed; " + content
                            + "; what=" + what + "; extra=" + extra);
                    stop();
                    AudioDownloads.get(context).invalidate(audio);
                    showFailure(context, AudioCache.Failure.UNAVAILABLE);
                }
                return true;
            });
            next.setDataSource(audio.file.getAbsolutePath());
            next.prepareAsync();
        } catch (Exception e) {
            AppLogger.e(TAG, "Playback failed; " + content, e);
            stop();
            AudioDownloads.get(context).invalidate(audio);
            showFailure(context, AudioCache.Failure.UNAVAILABLE);
        }
    }

    void stop() {
        generation++;
        if (request != null) { request.cancel(); request = null; }
        downloading = false;
        MediaPlayer previous = player;
        String previousContent = (activeSalam ? "Salam" : "Durood") + " ID=" + activeId;
        player = null;
        activeId = 0;
        activeSalam = false;
        playing = false;
        publishState();
        if (previous == null) return;
        // release() also stops playback and is valid while preparing asynchronously.
        try {
            previous.release();
            AppLogger.i(TAG, "Playback stopped/released; " + previousContent);
        } catch (RuntimeException e) {
            AppLogger.e(TAG, "Playback cleanup failed; " + previousContent, e);
        }
    }

    private void showFailure(Context context, AudioCache.Failure failure) {
        int message;
        switch (failure) {
            case NO_INTERNET: message = R.string.audio_no_internet; break;
            case STORAGE: message = R.string.audio_storage_low; break;
            case DOWNLOAD: message = R.string.audio_download_failed; break;
            default: message = R.string.audio_unavailable;
        }
        android.widget.Toast.makeText(LanguageManager.applyLanguage(context), message, android.widget.Toast.LENGTH_LONG).show();
    }
}
