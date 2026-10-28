package com.altc.xiaozhi;

import android.media.AudioManager;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.util.Log;

/**
 * 本地音频文件播放器（mp3 / flac / wav / m4a / aac / ogg / opus，以及 mp4/mkv 的纯音频用法）。
 *
 * 为什么单独写一个而不是复用 {@link AudioPlayer}：
 * AudioPlayer 播的是服务端逐句 TTS 下来的裸 PCM（24kHz 单声道），
 * 服务端的音乐没有 pause/resume 能力，只能靠「缓存 + 断点重喂」硬撑。
 * 本地文件是完整的一条流，交给 MediaPlayer 就能天然 pause / resume / seek，
 * 不会出现「继续播放从头开始」这种问题。
 *
 * 数据来源有两种（P3 起）：
 *   1) 本地路径 setDataSource(String)；
 *   2) 远程 SMB 包出来的 {@link MediaDataSource}（边下边播，不用等整首下载完）。
 * 视频容器（mp4 等）不设 Surface，MediaPlayer 只把音轨解出来 —— 就是「只出声」。
 *
 * 注意：所有方法都必须在主线程调用（MediaPlayer 的回调会派发到创建它的 Looper）。
 */
public class FileMusicPlayer {
    private static final String TAG = "FileMusicPlayer";

    public interface Listener {
        /** 播放结束（自然放完）。 */
        void onDone();
        /** 打不开 / 解码失败。 */
        void onError(String why);
    }

    private MediaPlayer mp;
    private String path = "";
    /** 远程直读的数据源：release 时必须一起关，否则 SMB 会话泄漏。 */
    private MediaDataSource ds;
    private float gain = 1f;
    private boolean paused = false;
    /** 已经 prepare 完成：没准备好的时候 seekTo 会被 MediaPlayer 直接吞掉。 */
    private boolean prepared = false;
    /** 递增代次：prepareAsync 期间被新的播放顶掉时，旧回调要丢弃。 */
    private int gen = 0;
    private Listener listener;
    /** 是否已经真的开始出声（用于区分「起播就失败」和「播到一半断流」）。 */
    private boolean started = false;

    /** 开始播一个本地文件。返回 false 表示这个文件打不开。 */
    public synchronized boolean play(String filePath, Listener l) {
        return begin(filePath, null, l);
    }

    /**
     * 开始播一个远程数据源（SMB 边下边播）。返回 false 表示数据源打不开。
     *
     * @param label 仅用于日志/状态显示（文件名）
     */
    public synchronized boolean play(MediaDataSource source, String label, Listener l) {
        return begin(label, source, l);
    }

    private synchronized boolean begin(String label, MediaDataSource source, Listener l) {
        stop();
        if (source == null && (label == null || label.isEmpty())) return false;
        final int my = ++gen;
        listener = l;
        path = label == null ? "" : label;
        paused = false;
        started = false;
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            if (source != null) {
                ds = source;
                mp.setDataSource(source);
            } else {
                mp.setDataSource(label);
            }
            mp.setOnPreparedListener(p -> {
                if (my != gen) return;                 // 已被后来的播放顶掉
                try {
                    prepared = true;
                    p.setVolume(gain, gain);
                    p.start();
                    paused = false;
                    started = true;
                } catch (Throwable t) {
                    Log.e(TAG, "start failed", t);
                }
            });
            mp.setOnCompletionListener(p -> {
                if (my != gen) return;
                paused = false;
                Listener cb = listener;
                if (cb != null) cb.onDone();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                if (my != gen) return false;
                Log.e(TAG, "MediaPlayer error what=" + what + " extra=" + extra
                        + " started=" + started + " src=" + path);
                Listener cb = listener;
                release(true);
                if (cb != null) cb.onError("what=" + what + " extra=" + extra);
                return true;
            });
            mp.prepareAsync();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "play failed: " + path, t);
            release(true);
            return false;
        }
    }

    /** 暂停，保留播放位置（MediaPlayer 自己记着，resume 从这儿接）。 */
    public synchronized void pause() {
        if (mp == null || !prepared) return;
        try {
            if (mp.isPlaying()) { mp.pause(); paused = true; }
        } catch (Throwable ignore) {}
    }

    /** 从暂停处继续。 */
    public synchronized boolean resume() {
        if (mp == null || !prepared) return false;
        try {
            mp.start();
            paused = false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 停止并释放。 */
    public synchronized void stop() {
        gen++;
        release(false);
    }

    /**
     * 跳到指定位置（毫秒）。
     *
     * MediaPlayer 在 prepare 完成前不接受 seekTo（会被静默丢弃），所以这里先挡一下，
     * 返回 false 让调用方知道自己白说了、可以给个提示。
     */
    public synchronized boolean seekTo(int ms) {
        if (mp == null || !prepared) return false;
        try {
            int dur = mp.getDuration();
            int target = Math.max(0, ms);
            // 留半秒余量：贴到末尾会立刻触发 onCompletion，用户以为「跳一下就没了」
            if (dur > 0) target = Math.min(target, Math.max(0, dur - 500));
            mp.seekTo(target);
            Log.i(TAG, "seek " + target + "ms (dur=" + dur + ")");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "seek failed", t);
            return false;
        }
    }

    /** prepare 完成、可以 seek 了。 */
    public synchronized boolean isPrepared() { return mp != null && prepared; }

    private void release(boolean keepListener) {
        if (!keepListener) listener = null;
        if (mp != null) {
            try { mp.reset(); } catch (Throwable ignore) {}
            try { mp.release(); } catch (Throwable ignore) {}
            mp = null;
        }
        if (ds != null) {
            try { ds.close(); } catch (Throwable ignore) {}
            ds = null;
        }
        paused = false;
        started = false;
        prepared = false;
        path = "";
    }

    /**
     * 实时音量（ducking 用）：说话时压到很小，说完还原。
     * 只改输出增益，不动播放位置，所以音乐不会断。
     */
    public synchronized void setGain(float g) {
        gain = Math.max(0f, Math.min(1f, g));
        if (mp == null || !prepared) return;      // prepare 完会按 gain 再设一次
        try {
            if (mp != null) mp.setVolume(gain, gain);
        } catch (Throwable ignore) {}
    }

    public synchronized boolean isPlaying() {
        if (mp == null || !prepared) return false;
        try { return mp.isPlaying(); } catch (Throwable t) { return false; }
    }

    /** 有曲目但被暂停了（可能是被一句提问让开的）。 */
    public synchronized boolean isPaused() {
        return mp != null && paused;
    }

    public synchronized boolean hasTrack() { return mp != null; }

    /**
     * 当前播放位置。
     *
     * 必须在 prepared 之后才问：prepareAsync 期间调 getCurrentPosition 会返回
     * 「wrong state」，某些固件（这台就是）直接把播放器打进 error 状态，
     * 报 (-38,0) 然后整首放不了 —— 进度条每秒轮询一次，正好踩中这个雷。
     */
    public synchronized int positionMs() {
        if (mp == null || !prepared) return 0;
        try { return mp.getCurrentPosition(); } catch (Throwable t) { return 0; }
    }

    /** 总时长。同样要等 prepared，否则 read 会 poison 掉播放器（见 positionMs）。 */
    public synchronized int durationMs() {
        if (mp == null || !prepared) return 0;
        try { return mp.getDuration(); } catch (Throwable t) { return 0; }
    }

    public synchronized String path() { return path; }
}
