package com.altc.xiaozhi;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * Plays the downlink Opus stream from the server.
 *
 * The server announces its downlink params in the hello message
 * (<code>audio_params.sample_rate</code>, usually 24000 but it can differ
 * between TTS voices). {@link #setSampleRate(int)} re-creates the decoder and
 * AudioTrack so switching voices never changes pitch/speed.
 *
 * 关键改动：AudioTrack 改为「按需创建」。
 * 这台机器的 MTK 音频 HAL 在输入流与输出流同时存在时会卡住 routing patch
 * （dumpsys 里能看到 Pending config events: routing 堆积），
 * 表现为录音完全读不到数据、AudioRecord.stop() 永久阻塞。
 * 所以录音前先 {@link #releaseOutput()} 把输出流放掉，需要播放时再建。
 */
public class AudioPlayer {
    public static final int DEFAULT_SAMPLE_RATE = 24000;
    public static final int CHANNELS = 1;
    public static final int FRAME_MS = 60;

    private int sampleRate = DEFAULT_SAMPLE_RATE;
    private int maxPcm = DEFAULT_SAMPLE_RATE * FRAME_MS / 1000 * 3;
    private long dec;
    private AudioTrack track;
    private final Object lock = new Object();

    /** 解码后的 PCM 出口：音乐缓存靠它把整首歌存下来做断点续播。 */
    public interface PcmSink {
        void onPcm(short[] pcm, int len);
    }

    private volatile PcmSink pcmSink;
    /** 静默模式：照常解码、照常进缓存，但不送到扬声器（补流时用）。 */
    private volatile boolean silent = false;
    /** 累计真正写进 AudioTrack 的采样数 = 这首歌已经放到哪儿了。 */
    private long playedSamples = 0;
    /**
     * 播放增益（1.0 = 原音量）。
     *
     * 全双工对话时用它做 ducking：用户开口说话的一瞬间把音乐压到 15%，
     * 既保证音乐「还在放」，又能让麦克风少拾到自己的声音。
     * 走 AudioTrack.setVolume，不碰播放位置，所以不会中断。
     */
    private volatile float gain = 1f;

    public void setGain(float g) {
        gain = Math.max(0f, Math.min(1f, g));
        synchronized (lock) {
            if (track != null) {
                try { track.setVolume(gain); } catch (Throwable ignore) {}
            }
        }
    }

    public float gain() { return gain; }

    public void setPcmSink(PcmSink s) { this.pcmSink = s; }

    public void setSilent(boolean s) {
        silent = s;
    }

    public boolean isSilent() { return silent; }

    public long playedSamples() {
        synchronized (lock) { return playedSamples; }
    }

    public void resetPlayed() {
        synchronized (lock) { playedSamples = 0; }
    }

    /**
     * 直接把「已播采样数」设成某个值。
     *
     * 跳转进度时用：断点续播把 playedSamples 当作**绝对播放位置**来记
     * （暂停时 {@code music.suspend(player.playedSamples())}），
     * 如果跳到 2 分钟后不清零重计，后面记下来的断点就会指到开头附近。
     */
    public void setPlayedSamples(long n) {
        synchronized (lock) { playedSamples = Math.max(0, n); }
    }

    public AudioPlayer() {
        synchronized (lock) {
            dec = OpusCodec.createDecoder(DEFAULT_SAMPLE_RATE, CHANNELS);
        }
    }

    /** Apply the downlink sample rate announced by the server hello. */
    public void setSampleRate(int sr) {
        if (sr <= 0 || sr == sampleRate) return;
        synchronized (lock) {
            sampleRate = sr;
            maxPcm = (sr * FRAME_MS / 1000) * 3;
            if (dec != 0) {
                try { OpusCodec.destroyDecoder(dec); } catch (Exception ignored) {}
                dec = 0;
            }
            dec = OpusCodec.createDecoder(sr, CHANNELS);
            if (track != null) {
                releaseTrackLocked();
                openTrackLocked();
            }
        }
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** 最近一次写入音频数据的时间：连续对话要等这一句真正播完再开麦，避免自激。 */
    private volatile long lastWriteAt = 0;

    public boolean idleFor(long ms) {
        return System.currentTimeMillis() - lastWriteAt > ms;
    }

    public void playOpus(byte[] opus, int len) {
        synchronized (lock) {
            if (dec == 0) return;
            short[] pcm = new short[maxPcm];
            int n = OpusCodec.decode(dec, opus, len, pcm, pcm.length);
            if (n <= 0) return;
            PcmSink sink = pcmSink;
            if (sink != null) {
                try { sink.onPcm(pcm, n); } catch (Throwable ignore) {}
            }
            if (silent) return;          // 补流：只缓存不出声
            if (track == null) openTrackLocked();
            if (track == null) return;
            try { track.write(pcm, 0, n); playedSamples += n; lastWriteAt = System.currentTimeMillis(); }
            catch (Throwable ignore) {}
        }
    }

    /**
     * 直接写一段 PCM（断点续播时从本地缓存喂进来）。
     * track.write 是阻塞的，所以调用线程天然按播放速度走。
     */
    public void writePcm(short[] pcm, int off, int len) {
        synchronized (lock) {
            if (track == null) openTrackLocked();
            if (track == null) return;
            try { track.write(pcm, off, len); playedSamples += len; lastWriteAt = System.currentTimeMillis(); }
            catch (Throwable ignore) {}
        }
    }

    /**
     * 打断：立刻清空还没播完的音频，用于「用户开口/新指令」时中止当前朗读。
     * 只 flush 不重建，避免延迟。
     */
    public void flush() {
        synchronized (lock) {
            if (track != null) {
                try { track.pause(); } catch (Throwable ignore) {}
                try { track.flush(); } catch (Throwable ignore) {}
                try { track.play(); } catch (Throwable ignore) {}
            }
            lastWriteAt = 0;
        }
    }

    /** 录音前调用：放掉输出流，避免和输入流抢音频路由。 */
    public void releaseOutput() {
        synchronized (lock) {
            releaseTrackLocked();
        }
    }

    private void openTrackLocked() {
        try {
            int frameSamples = sampleRate * FRAME_MS / 1000;
            int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuf, frameSamples * 2 * 2), AudioTrack.MODE_STREAM);
            track.setVolume(gain);
            track.play();
        } catch (Throwable t) {
            track = null;
        }
    }

    private void releaseTrackLocked() {
        if (track != null) {
            try { track.stop(); } catch (Throwable ignore) {}
            try { track.release(); } catch (Throwable ignore) {}
            track = null;
        }
    }

    public void release() {
        synchronized (lock) {
            if (dec != 0) {
                try { OpusCodec.destroyDecoder(dec); } catch (Exception ignored) {}
                dec = 0;
            }
            releaseTrackLocked();
        }
    }
}
