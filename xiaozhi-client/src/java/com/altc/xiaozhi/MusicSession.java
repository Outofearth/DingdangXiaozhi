package com.altc.xiaozhi;

import java.util.ArrayList;
import java.util.List;

/**
 * 一首歌（服务端 play_music 逐句 TTS）的本地缓存与会话状态。
 *
 * 背景：服务端没有 pause/resume 能力，用户说「继续播放」时它会把整首歌从头
 * 重新 TTS 一遍（实测：每次都从第一句歌词开始发）。客户端唯一的办法是自己
 * 记住「播到第几个采样」，继续时从断点放，而不是听它从头再来。
 *
 * 数据模型：整首歌按句缓存 PCM（{@link Line}），句子序号由服务端
 * tts/sentence_start 的下发顺序决定。补流（重新请求同一首歌）时按同样的
 * 顺序写入，因此「同序号覆盖」，不会把歌拼成两份。
 */
public class MusicSession {

    /** 单首歌缓存上限（PCM 字节数），超过就不再缓存，避免吃爆内存。 */
    private static final int MAX_BYTES = 20 * 1024 * 1024;   // 约 7 分钟 24kHz mono

    public static class Line {
        public final String text;
        public short[] pcm = new short[0];
        public int len = 0;

        Line(String text) { this.text = text; }

        void reset() { len = 0; }

        void append(short[] d, int off, int n) {
            if (len + n > pcm.length) {
                int cap = Math.max(2048, pcm.length == 0 ? 2048 : pcm.length);
                while (cap < len + n) cap *= 2;
                pcm = java.util.Arrays.copyOf(pcm, cap);
            }
            System.arraycopy(d, off, pcm, len, n);
            len += n;
        }
    }

    /** 消费缓存 PCM 的出口（续播时写到 AudioTrack）。 */
    public interface Sink {
        void write(short[] pcm, int off, int len);
    }

    /** 续播中止判断（用户又开口 / 又要播新歌）。 */
    public interface Abort {
        boolean aborted();
    }

    private final List<Line> lines = new ArrayList<>();
    private String title = "";
    private int fillIndex = -1;          // 当前正在填充的句子下标
    private boolean skipSlot = false;    // 补流时碰到已有内容的句子：只对齐序号，不覆盖
    private boolean refillMode = false;
    private long bytes = 0;

    private boolean active = false;      // 有可续播的一首歌
    private boolean collecting = false;  // 这一轮的下行音频是不是这首歌的
    private boolean suspended = false;   // 用户主动停过（断点在 pauseAt）
    private boolean complete = false;    // 整首已经缓存完整（补流成功）
    private boolean gaveUp = false;      // 补不齐了（纯音乐等），不再自动补流
    private long pauseAt = 0;            // 断点：已经播出去的采样数
    private long lastAudioAt = 0;        // 最近一次收到这首歌音频的时间

    /**
     * 暂停那一刻「正在填充的最后一句」及其已收采样数。
     *
     * 纯音乐（如《疲劳消解》）服务端只下发一句《歌名》，后面全是音频流。
     * 这类音频补流时如果沿用「已有内容就整句跳过」，就一个采样都补不进来，
     * complete 永远为 false → 无限静默重取，整机被拖死。所以对这种「未收完的
     * 最后一句」改成按采样偏移续接：重发的前 sealLen 个采样丢掉，之后追加。
     */
    private int sealIndex = -1;
    private int sealLen = 0;
    private int discard = 0;             // 补流时当前句还需丢弃的采样数（去重）
    private boolean capped = false;      // 缓存到了上限（超长音频），后面再收也没地方放

    /** 一首歌开始（收到 % play_music 标记时调用）。 */
    public synchronized void begin() {
        lines.clear();
        title = "";
        fillIndex = -1;
        bytes = 0;
        active = true;
        collecting = true;
        suspended = false;
        complete = false;
        gaveUp = false;
        pauseAt = 0;
        sealIndex = -1;
        sealLen = 0;
        discard = 0;
        capped = false;
        skipSlot = false;
        refillMode = false;
    }

    /** 补流：整首歌重新下发一遍，只用来填首发时没拿到的句子，已有内容一律不覆盖。 */
    public synchronized void refillStart() {
        if (!active) return;
        fillIndex = -1;
        skipSlot = false;
        discard = 0;
        refillMode = true;
        collecting = true;
    }

    /** 这一轮 TTS 结束，之后的下行音频不再算这首歌。 */
    public synchronized void endTurn() {
        collecting = false;
        refillMode = false;
    }

    public synchronized void clear() {
        lines.clear();
        title = "";
        fillIndex = -1;
        bytes = 0;
        active = false;
        collecting = false;
        suspended = false;
        complete = false;
        gaveUp = false;
        pauseAt = 0;
        sealIndex = -1;
        sealLen = 0;
        discard = 0;
        capped = false;
        skipSlot = false;
        refillMode = false;
    }

    public synchronized boolean isActive() { return active; }
    /** 这一轮真的在下发这首歌的音频（用来判断「现在确实在放歌」）。 */
    public synchronized boolean isCollecting() { return active && collecting; }
    /**
     * 最近 ms 毫秒内这首歌还在往下发音频 —— 也就是「大概率正在听歌」。
     * 不能用 collecting 判断：用户一开口客户端就会先发 abort，服务端立刻补一个
     * tts stop，collecting 会被清掉，但那会儿用户明明还在听歌。
     */
    public synchronized boolean isRecentlyPlaying(long ms) {
        return active && !suspended && lastAudioAt > 0
                && System.currentTimeMillis() - lastAudioAt < ms;
    }
    public synchronized boolean isSuspended() { return suspended; }
    public synchronized boolean isComplete() { return complete; }
    public synchronized String title() { return title; }
    public synchronized long pausePoint() { return pauseAt; }
    public synchronized int lineCount() { return lines.size(); }

    /**
     * 服务端下发一句歌词：开始新的一行。
     * 第一行《歌名》会被抽成 title，不参与缓存（它通常只是标题念白，也算音频，保留）。
     */
    public synchronized void addLine(String text) {
        if (!active || !collecting) return;
        if (title.isEmpty()) {
            String t = extractTitle(text);
            if (t != null) title = t;
        }
        fillIndex++;
        skipSlot = false;
        discard = 0;
        if (fillIndex < lines.size()) {
            Line old = lines.get(fillIndex);
            if (refillMode) {
                if (fillIndex == sealIndex && old.len > 0) {
                    // 暂停时还没收完的那一句：补流会从这句开头重发，前 sealLen 个
                    // 采样本地已有，丢掉；之后收到的接着往后追加（纯音乐全靠这条）。
                    discard = sealLen;
                } else if (old.len > 0) {
                    // 完整句子：首发那份就是用户正在听的，整句跳过，只对齐序号
                    skipSlot = true;
                }
            }
        } else {
            lines.add(new Line(text));
        }
    }

    /** 从「《逐梦》」这种句子里抽歌名。 */
    private static String extractTitle(String text) {
        int a = text.indexOf('《');
        int b = text.indexOf('》');
        if (a >= 0 && b > a) return text.substring(a + 1, b);
        return null;
    }

    /** 追加一段解码后的 PCM（归到当前句）。 */
    public synchronized void appendPcm(short[] pcm, int n) {
        if (!active || !collecting || n <= 0 || fillIndex < 0 || fillIndex >= lines.size()) return;
        lastAudioAt = System.currentTimeMillis();   // 先记活跃时间：跳过/去重也是真的在下发
        if (skipSlot) return;
        int off = 0;
        if (discard > 0) {
            int d = Math.min(discard, n);
            discard -= d;
            off = d;
            n -= d;
            if (n <= 0) return;                     // 这一段全是重复部分
        }
        if (bytes + (long) n * 2 > MAX_BYTES) { capped = true; return; }
        Line ln = lines.get(fillIndex);
        ln.append(pcm, off, n);
        bytes += (long) n * 2;
    }

    /** 缓存已到上限（超长音频）：再补也放不下了，按「能存多少算多少」收工。 */
    public synchronized boolean isCapped() { return capped; }

    /** 缓存里一共有多少采样。 */
    public synchronized long totalSamples() {
        long t = 0;
        for (Line ln : lines) t += ln.len;
        return t;
    }

    /** 把整首按顺序拼成一整块 PCM（存本地曲库用）。 */
    public synchronized short[] exportAll() {
        int total = (int) Math.min(Integer.MAX_VALUE, totalSamples());
        short[] out = new short[total];
        int p = 0;
        for (Line ln : lines) {
            if (p + ln.len > total) break;
            System.arraycopy(ln.pcm, 0, out, p, ln.len);
            p += ln.len;
        }
        return out;
    }

    /**
     * 从本地曲库装载一整首（当作「已完整缓存的歌」），之后直接用 startResume 播放。
     * 播放路径与断点续播完全一样，只是数据来源换成了文件，与服务端彻底解耦。
     */
    public synchronized void loadFrom(String t, short[] pcm, int len) {
        clear();
        if (pcm == null || len <= 0) return;
        title = t == null ? "" : t;
        Line ln = new Line("《" + title + "》");
        ln.append(pcm, 0, Math.min(len, pcm.length));
        lines.add(ln);
        bytes = (long) ln.len * 2;
        fillIndex = 0;
        active = true;
        complete = true;
        suspended = true;      // startResume 的前置条件
        collecting = false;
        pauseAt = 0;
        lastAudioAt = System.currentTimeMillis();
    }

    /** 用户喊停：记下断点，并立即停止收集（服务端接着回的「好啦」不能算歌词）。 */
    public synchronized void suspend(long playedSamples) {
        if (!active) return;
        suspended = true;
        collecting = false;
        pauseAt = Math.max(0, playedSamples);
        // 记下「暂停这一刻还收着的最后一句」：补流从这里按采样偏移续接
        sealIndex = fillIndex;
        sealLen = (fillIndex >= 0 && fillIndex < lines.size()) ? lines.get(fillIndex).len : 0;
    }

    /** 补不齐（纯音乐只下发一句等情况）：标记放弃，不再自动补流，避免死循环。 */
    public synchronized void giveUp() { gaveUp = true; }
    public synchronized boolean isGaveUp() { return gaveUp; }

    /** 继续播放前把断点往前挪一点，避免漏掉 AudioTrack 里还没放完的那点缓冲。 */
    public synchronized void rewindForBuffer(long samples) {
        pauseAt = Math.max(0, pauseAt - Math.max(0, samples));
    }

    /**
     * 跳到指定采样位置（语音「跳到 2 分钟」用）。
     *
     * 只改断点、不动缓存：{@link #playFrom} 本来就是从任意采样开始喂，
     * 所以跳转不需要重新解码，也不会碰到服务端（服务端根本没有 seek 能力）。
     *
     * @return 实际落点（采样），越界会被夹到缓存范围内
     */
    public synchronized long seekTo(long samples) {
        if (!active) return 0;
        long total = totalSamples();
        pauseAt = Math.max(0, Math.min(samples, Math.max(0, total)));
        suspended = true;       // seek 之后要靠 startResume 重新喂，前置条件必须有
        return pauseAt;
    }

    public synchronized void resumeDone() {
        suspended = false;
    }

    public synchronized void markComplete(boolean c) { complete = c; }

    /** 已缓存的音频总时长（毫秒）。 */
    public synchronized long cachedMs(int sampleRate) {
        if (sampleRate <= 0) return 0;
        return totalSamples() * 1000L / sampleRate;
    }

    /**
     * 从 from 采样开始，把缓存顺序喂给 sink（阻塞，直到写完或被 abort）。
     * 返回 true 表示完整放完，false 表示被中止。
     */
    public boolean playFrom(long from, Sink sink, Abort abort) {
        List<Line> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(lines);
        }
        long cursor = 0;
        for (Line ln : snapshot) {
            long end = cursor + ln.len;
            if (end <= from) { cursor = end; continue; }
            int pos = (int) Math.max(0, from - cursor);
            while (pos < ln.len) {
                if (abort != null && abort.aborted()) return false;
                int chunk = Math.min(1440, ln.len - pos);
                sink.write(ln.pcm, pos, chunk);
                pos += chunk;
            }
            cursor = end;
        }
        return true;
    }
}
