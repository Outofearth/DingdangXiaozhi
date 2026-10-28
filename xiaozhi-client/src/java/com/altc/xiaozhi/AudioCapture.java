package com.altc.xiaozhi;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Captures 16 kHz mono PCM from the microphone, encodes each 60 ms frame with
 * Opus, and hands the resulting packet to a {@link Callback}.
 *
 * This is the exact part the old Flutter client got wrong: it opened the
 * AudioRecord but never consumed the read buffer, so the firmware kept feeding
 * data into a sink with no reader (Server=0). Here we read in a tight loop and
 * always consume the buffer.
 *
 * 线程安全：start()/stop() 可能分别在 UI 线程与后台线程并发调用（松手很快时
 * start 还没跑完就收到 stop），用 lock 串行化，避免 stop 之后 start 又把
 * AudioRecord 建起来造成「幽灵录音」。
 */
public class AudioCapture {
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;
    public static final int FRAME_MS = 60;
    public static final int FRAME_SAMPLES = SAMPLE_RATE * FRAME_MS / 1000;   // 960
    private static final int MAX_OPUS = 4000;                               // worst-case packet

    private final long enc;
    private final Callback cb;
    private final Object lock = new Object();
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running = false;
    private volatile Throwable lastError;

    /* ---- 上行统计（只写 status.txt，不上界面）：用来判断麦克风到底有没有数据 ---- */
    private volatile int readFrames;      // 成功读到满帧的次数
    private volatile int rawReads;        // 底层 read() 有返回值的次数（判断 HAL 是否在供数）
    private volatile int encFrames;       // 编码成功并发出去的帧数
    private volatile int silentFrames;    // 整帧全是 0（麦克风被静音）的帧数
    private volatile int maxAmp;          // 本次最大振幅
    private volatile int maxRms;           // 本次最大 RMS（判语音比峰值稳）
    private volatile int reopenCount;     // 因 HAL 不供数据而重建录音通道的次数

    public interface Callback {
        void onOpusFrame(byte[] frame, int len);
        /**
         * 每帧的能量指标，供客户端 VAD 判断「有没有人在说话」。
         * peak 是最大绝对振幅（容易受瞬时噪声干扰），rms 是均方根（更适合判语音）。
         */
        void onLevel(int peak, int rms);
    }

    public AudioCapture(Callback cb) {
        this.cb = cb;
        this.enc = OpusCodec.createEncoder(SAMPLE_RATE, CHANNELS);
    }

    public Throwable getLastError() { return lastError; }

    public void start() {
        synchronized (lock) {
            if (record != null) return;
            try {
                readFrames = 0;
                rawReads = 0;
                encFrames = 0;
                silentFrames = 0;
                maxAmp = 0;
                maxRms = 0;
                reopenCount = 0;
                record = open();
                lastError = null;
                running = true;
                thread = new Thread(this::loop, "audio-capture");
                thread.start();
            } catch (Throwable t) {
                lastError = t;
                running = false;
                if (record != null) {
                    try { record.release(); } catch (Exception ignore) {}
                    record = null;
                }
            }
        }
    }

    /** 建一条可用的录音通道（已 startRecording）。失败抛异常。 */
    private AudioRecord open() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, FRAME_SAMPLES * 2 * 4);
        AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        int st = r.getState();
        if (st != AudioRecord.STATE_INITIALIZED) {
            try { r.release(); } catch (Exception ignore) {}
            throw new IllegalStateException("AudioRecord 初始化失败 state=" + st);
        }
        r.startRecording();
        int rs = r.getRecordingState();
        if (rs != AudioRecord.RECORDSTATE_RECORDING) {
            try { r.release(); } catch (Exception ignore) {}
            throw new IllegalStateException("AudioRecord 启动录音失败 state=" + rs);
        }
        return r;
    }

    /**
     * 录音通道自愈：这家固件的 HAL 偶尔会「活着但不供数据」（Server=00000000），
     * 表现就是按住说话半天没有任何回应。重建一条通道即可恢复，不必重启 app。
     */
    private boolean reopen(AudioRecord old) {
        synchronized (lock) {
            if (!running) return false;
            try { old.stop(); } catch (Throwable ignore) {}
            try { old.release(); } catch (Throwable ignore) {}
            record = null;
            try {
                record = open();
                reopenCount++;
                return true;
            } catch (Throwable t) {
                lastError = t;
                return false;
            }
        }
    }

    private void loop() {
        short[] pcm = new short[FRAME_SAMPLES];
        byte[] out = new byte[MAX_OPUS];
        int filled = 0;
        long noDataSince = System.currentTimeMillis();
        while (running) {
            AudioRecord r = record;
            if (r == null) break;
            int read;
            try {
                // 必须非阻塞：阻塞式 read() 在 HAL 不供数据时会永久挂住，
                // 连 stop() 都唤不醒（会把整条录音通道占死）。
                read = r.read(pcm, filled, FRAME_SAMPLES - filled, AudioRecord.READ_NON_BLOCKING);
            } catch (Throwable t) {
                lastError = t;
                break;
            }
            if (read < 0) {
                if (!running) break;
                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                continue;
            }
            if (read == 0) {
                if (!running) break;
                if (System.currentTimeMillis() - noDataSince > 800) {
                    filled = 0;
                    if (reopen(r)) {
                        noDataSince = System.currentTimeMillis();
                        continue;
                    }
                }
                try { Thread.sleep(8); } catch (InterruptedException e) { break; }
                continue;
            }

            // 关键：HAL 每次给的样本数通常不是整帧（常见 320/640），
            // 必须累积到 960 再编码，否则会把所有部分读全部丢掉 → 上行 0 帧 → 服务端无回应。
            filled += read;
            rawReads++;
            noDataSince = System.currentTimeMillis();
            if (filled < FRAME_SAMPLES) continue;
            filled = 0;
            readFrames++;

            // 统计振幅：整帧为 0 说明麦克风此刻被静音（物理键/系统独占）
            int amp = 0;
            long sumSq = 0;
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                int v = pcm[i];
                int a = v < 0 ? -v : v;
                if (a > amp) amp = a;
                sumSq += (long) v * v;
            }
            int rms = (int) Math.sqrt((double) sumSq / FRAME_SAMPLES);
            if (amp > maxAmp) maxAmp = amp;
            if (rms > maxRms) maxRms = rms;
            if (amp == 0) silentFrames++;
            try { cb.onLevel(amp, rms); } catch (Throwable ignore) {}

            int n = OpusCodec.encode(enc, pcm, FRAME_SAMPLES, out, out.length);
            if (n > 0) {
                byte[] frame = new byte[n];
                System.arraycopy(out, 0, frame, 0, n);
                encFrames++;
                cb.onOpusFrame(frame, n);
            }
        }
    }

    /** 本次录音的上行统计，用于落盘排查（0 帧 = HAL 没供数据；帧多但静音多 = 麦克风被静音）。 */
    public String stats() {
        return "上行 帧=" + readFrames + " 编码=" + encFrames
                + " 静音帧=" + silentFrames + " 峰值=" + maxAmp + " 最大RMS=" + maxRms
                + " 原始读=" + rawReads + " 重建=" + reopenCount
                + (lastError != null ? (" 错误=" + lastError) : "");
    }

    /** 这一轮是否完全没拿到麦克风数据（HAL 没供数，通常是音频 HAL 卡死）。 */
    public boolean gotNothing() {
        return rawReads == 0 && readFrames == 0;
    }

    public void stop() {
        AudioRecord r;
        Thread t;
        synchronized (lock) {
            running = false;
            t = thread;
            thread = null;
            r = record;
            record = null;
        }
        // 先停掉 AudioRecord，让阻塞中的 read() 立刻返回，再 join 线程
        if (r != null) {
            try { r.stop(); } catch (Exception ignore) {}
        }
        if (t != null) {
            try { t.join(800); } catch (InterruptedException ignore) {}
        }
        if (r != null) {
            try { r.release(); } catch (Exception ignore) {}
        }
    }

    public void release() {
        stop();
        OpusCodec.destroyEncoder(enc);
    }

    /**
     * 麦克风自检：直接开一个独立 AudioRecord 录一小段，统计电平。
     *
     * 用途：这台设备的物理麦克风开关在厂商私有层，Android 侧读不到开关状态。
     * 只能反过来看「数据面」—— 若录到的全是 0，说明麦克风在硬件/系统层被掐断
     * （或被厂商语音助手独占），此时任何 app 都拿不到声音。
     *
     * @return 形如 "reads=20 rms=1234 peak=9000 zero=0%"，rms=0 即静音
     */
    public static String probe(int durationMs) {
        int[] s = probeStats(durationMs);
        if (s == null) return "自检失败（AudioRecord 无法初始化）";
        return "reads=" + s[0] + " rms=" + s[1] + " peak=" + s[2] + " 静音占比=" + s[3] + "%";
    }

    /**
     * 采样一次电平，返回 {reads, rms, peak, zeroPct}；初始化失败返回 null。
     *
     * 看门狗：某些固件下 HAL 不再灌数据时 read() 会永久阻塞。若不强行 stop()，
     * 这个线程会一直占着麦克风，导致后面「按住说话」也拿不到数据。
     */
    public static int[] probeStats(int durationMs) {
        return probeStats(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, durationMs);
    }

    /**
     * 换着 source / 采样率各试一遍，判断「拿不到数据」是全局性的，
     * 还是只有默认 MIC 源被掐断（那么换 VOICE_COMMUNICATION 之类就能救）。
     */
    public static String probeMatrix() {
        int[] sources = {
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.DEFAULT
        };
        String[] names = {"MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION", "CAMCORDER", "DEFAULT"};
        int[] rates = {16000, 48000};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sources.length; i++) {
            for (int rate : rates) {
                int[] s = probeStats(sources[i], rate, 400);
                sb.append(names[i]).append('@').append(rate).append('=');
                if (s == null) {
                    sb.append("init_fail");
                } else {
                    sb.append("r").append(s[0]).append("/rms").append(s[1]);
                }
                sb.append(' ');
            }
        }
        return sb.toString().trim();
    }

    public static int[] probeStats(int audioSource, int sampleRate, int durationMs) {
        AudioRecord r = null;
        final Object doneLock = new Object();
        final boolean[] done = {false};
        try {
            int frame = sampleRate * FRAME_MS / 1000;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, frame * 2 * 4);
            r = new AudioRecord(audioSource, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (r.getState() != AudioRecord.STATE_INITIALIZED) return null;
            r.startRecording();
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) return null;

            final AudioRecord ref = r;
            Thread wd = new Thread(() -> {
                try { Thread.sleep(durationMs + 1500L); } catch (InterruptedException ignore) {}
                synchronized (doneLock) {
                    if (done[0]) return;
                }
                try { ref.stop(); } catch (Throwable ignore) {}
            }, "probe-watchdog");
            wd.setDaemon(true);
            wd.start();

            short[] buf = new short[frame];
            long sumSq = 0, samples = 0, nonzero = 0;
            int peak = 0, reads = 0, errs = 0;
            long deadline = System.currentTimeMillis() + durationMs;
            while (System.currentTimeMillis() < deadline) {
                // 必须用非阻塞读：麦克风被掐断时阻塞式 read() 会永久挂住，
                // 连看门狗都唤不醒，那个线程就永远占着录音通道。
                int n;
                try {
                    n = r.read(buf, 0, buf.length, AudioRecord.READ_NON_BLOCKING);
                } catch (Throwable t) {
                    break;
                }
                if (n < 0) { errs++; break; }
                if (n == 0) {
                    try { Thread.sleep(15); } catch (InterruptedException ignore) {}
                    continue;
                }
                reads++;
                for (int i = 0; i < n; i++) {
                    int v = buf[i];
                    int a = v < 0 ? -v : v;
                    if (a > peak) peak = a;
                    if (v != 0) nonzero++;
                    sumSq += (long) v * v;
                }
                samples += n;
            }
            int rms = samples > 0 ? (int) Math.sqrt((double) sumSq / samples) : -1;
            int zeroPct = samples > 0 ? (int) (100L * (samples - nonzero) / samples) : 100;
            return new int[]{reads, rms, peak, zeroPct};
        } catch (Throwable t) {
            return null;
        } finally {
            synchronized (doneLock) { done[0] = true; }
            if (r != null) {
                try { r.stop(); } catch (Exception ignore) {}
                try { r.release(); } catch (Exception ignore) {}
            }
        }
    }
}
