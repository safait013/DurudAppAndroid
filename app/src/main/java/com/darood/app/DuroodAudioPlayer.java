package com.darood.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

/** One player owned by MainActivity; all playback operations run on the UI thread. */
final class DuroodAudioPlayer {
    private static final String TAG = "DuroodAudio";
    // Index is the stable Durood number, independent of filtering and UI language.
    // Append resources here as recordings for later items become available.
    private static final int[] AUDIO = java.util.Arrays.copyOf(new int[]{0,
            R.raw.durood1, R.raw.durood2, R.raw.durood3, R.raw.durood4,
            R.raw.durood5, R.raw.durood6, R.raw.durood7, R.raw.durood8,
            R.raw.durood9, R.raw.durood10}, 26);

    // Same player and lifecycle; the content type distinguishes equal Durood/Salam IDs.
    private static final int[] SALAM_AUDIO = java.util.Arrays.copyOf(new int[]{0,
            R.raw.salam1, R.raw.salam2, R.raw.salam3, R.raw.salam4}, 16);

    static {
        AUDIO[16] = R.raw.durood16;
        AUDIO[17] = R.raw.durood17;
        AUDIO[18] = R.raw.durood18;
        AUDIO[19] = R.raw.durood19;
        AUDIO[20] = R.raw.durood20;
        AUDIO[21] = R.raw.durood21;
        AUDIO[22] = R.raw.durood22;
        AUDIO[23] = R.raw.durood23;
        AUDIO[24] = R.raw.durood24;
        AUDIO[25] = R.raw.durood25;
        SALAM_AUDIO[11] = R.raw.salam11;
        SALAM_AUDIO[12] = R.raw.salam12;
        SALAM_AUDIO[13] = R.raw.salam13;
        SALAM_AUDIO[14] = R.raw.salam14;
        SALAM_AUDIO[15] = R.raw.salam15;
    }

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
        if (player != null && activeId == id && activeSalam == salam) stop();
        else play(context, id, salam);
    }

    private MediaPlayer player;
    private int activeId;
    private boolean activeSalam;

    static boolean hasAudio(int id) {
        return id > 0 && id < AUDIO.length && AUDIO[id] != 0;
    }

    static boolean hasSalamAudio(int id) {
        return id > 0 && id < SALAM_AUDIO.length && SALAM_AUDIO[id] != 0;
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
        final int resource = salam ? SALAM_AUDIO[id] : AUDIO[id];
        AppLogger.i(TAG, "Audio resource selected; " + content + "; resource=" + resource);
        activeId = id;
        activeSalam = salam;
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
                }
                return true;
            });
            try (AssetFileDescriptor audio = context.getApplicationContext()
                    .getResources().openRawResourceFd(resource)) {
                if (audio == null) throw new Resources.NotFoundException("Audio descriptor unavailable");
                next.setDataSource(audio.getFileDescriptor(), audio.getStartOffset(), audio.getLength());
            }
            next.prepareAsync();
        } catch (Exception e) {
            if (e instanceof Resources.NotFoundException) {
                AppLogger.w(TAG, "Missing audio resource; " + content, e);
            }
            AppLogger.e(TAG, "Playback failed; " + content, e);
            stop();
        }
    }

    void stop() {
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
}
