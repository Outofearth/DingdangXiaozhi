package com.altc.xiaozhi;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * 播放服务端 notify 消息下发的远程音频（音乐、长音频）。
 *
 * 与 TTS 的 Opus 播放器完全独立：TTS 走 {@link AudioPlayer}（opus 解码 + AudioTrack），
 * 这里直接用 MediaPlayer 拉 URL 流，支持 http/https。
 */
public class NotifyPlayer {

    private static final String TAG = "NotifyPlayer";

    private MediaPlayer mp;
    private volatile boolean playing = false;
    private String currentUrl = "";
    private Runnable onDone;

    public synchronized boolean isPlaying() {
        return playing;
    }

    public synchronized String currentUrl() {
        return currentUrl;
    }

    /** 开始播放；同一 URL 重复下发时忽略，避免叠音。 */
    public synchronized void play(final String url, final Runnable done) {
        if (url == null || url.isEmpty()) return;
        if (playing && url.equals(currentUrl)) return;
        stop();
        currentUrl = url;
        onDone = done;
        try {
            final MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            p.setDataSource(url);
            p.setOnPreparedListener(m -> {
                synchronized (NotifyPlayer.this) {
                    if (mp != p) return;
                    playing = true;
                }
                Log.i(TAG, "playing " + url + " dur=" + p.getDuration());
                m.start();
            });
            p.setOnCompletionListener(m -> {
                Log.i(TAG, "completed " + currentUrl);
                finish(p);
            });
            p.setOnErrorListener((m, what, extra) -> {
                Log.e(TAG, "media error what=" + what + " extra=" + extra + " url=" + url);
                finish(p);
                return true;
            });
            p.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "play failed", t);
            finish(null);
        }
    }

    private void finish(MediaPlayer p) {
        Runnable cb = null;
        synchronized (this) {
            playing = false;
            if (p == null || p == mp) mp = null;
            cb = onDone;
            onDone = null;
        }
        if (p != null) {
            try { p.reset(); p.release(); } catch (Throwable ignore) {}
        }
        if (cb != null) {
            try { cb.run(); } catch (Throwable ignore) {}
        }
    }

    public synchronized void stop() {
        MediaPlayer p = mp;
        mp = null;
        playing = false;
        onDone = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignore) {}
            try { p.reset(); p.release(); } catch (Throwable ignore) {}
        }
        currentUrl = "";
    }
}
