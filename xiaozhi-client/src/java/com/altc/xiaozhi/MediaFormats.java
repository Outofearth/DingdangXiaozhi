package com.altc.xiaozhi;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 媒体格式识别 + 语音里的「时间/序号」解析。
 *
 * 为什么单独一层：ASR 把用户说的话给过来时，里面可能带着格式词
 * （「播放 mp4」「放个 avi」「听 flac 版」），也可能带着进度
 * （「跳到 2 分钟」「快进 30 秒」「从第 3 首开始放」）。这些都不是歌名的一部分，
 * 必须先在本地解析掉，否则会被当成文件名去匹配，最后落到服务端瞎猜。
 *
 * 三个能力的边界：
 *   1) 格式词 ↔ 媒体类型：mp4/mkv/avi… = 视频；mp3/flac/wav… = 音乐。
 *      注意「能识别」和「能解码」是两件事 —— avi/rmvb 用户会说，但这台机器解不了，
 *      要在本地明确告知，而不是丢给云端。
 *   2) 时间：绝对位置（2 分钟 / 01:30）、相对（快进 30 秒）、百分比（一半 / 50%）。
 *   3) 序号：第 3 个 / 第 5 首 / 播放 3 号。
 */
public class MediaFormats {
    private static final String TAG = "MediaFormats";

    /** 本机有解码器的音频格式（{@link LocalFiles} 扫描用的同一套）。 */
    public static final String[] AUDIO_OK = {"mp3", "flac", "wav", "m4a", "aac", "ogg", "opus"};
    /** 本机有解码器的视频容器。 */
    public static final String[] VIDEO_OK = {"mp4", "m4v", "mov", "3gp", "mkv"};
    /** 用户会说、但本机没有解码器的音频格式。 */
    public static final String[] AUDIO_X = {
            "wma", "ape", "alac", "amr", "mid", "midi", "aiff", "aif", "dsf", "dff", "mp2", "ac3", "mka"
    };
    /** 用户会说、但本机没有解码器的视频格式。 */
    public static final String[] VIDEO_X = {
            "avi", "rmvb", "flv", "wmv", "webm", "ts", "m2ts", "mts", "mpg", "mpeg",
            "vob", "f4v", "asf", "ogv", "divx", "m2v"
    };

    /**
     * 口语格式词 → 扩展名。
     *
     * 收进来的是「ASR 真的会吐出来的写法」：mp4 常被识别成「mp四」「mp 4」，
     * 所以匹配前先把空格/全角数字/常见中文数字归一化。
     * 两字符的 rm / ts 故意不收 —— 太容易误命中（英文单词、拼音片段）。
     */
    private static final String[][] SPOKEN = {
            {"mp4", "mp4"}, {"mpeg4", "mp4"}, {"mpg4", "mp4"},
            {"mkv", "mkv"}, {"matroska", "mkv"}, {"m4v", "m4v"},
            {"3gp", "3gp"}, {"mov", "mov"},
            {"avi", "avi"}, {"rmvb", "rmvb"}, {"flv", "flv"}, {"wmv", "wmv"}, {"webm", "webm"},
            {"m2ts", "m2ts"}, {"mpeg", "mpeg"}, {"mpg", "mpg"}, {"vob", "vob"},
            {"f4v", "f4v"}, {"divx", "divx"},
            {"mp3", "mp3"}, {"flac", "flac"}, {"wav", "wav"}, {"ape", "ape"},
            {"aac", "aac"}, {"m4a", "m4a"}, {"ogg", "ogg"}, {"opus", "opus"}, {"wma", "wma"},
    };

    static {
        // 长的先匹配：「mpeg4」不能被「mpeg」半路截胡
        java.util.Arrays.sort(SPOKEN, (a, b) -> b[0].length() - a[0].length());
    }

    private MediaFormats() {}

    /* ==================== 扩展名 ==================== */

    /** 取扩展名（小写、不含点）；没有则返回空串。 */
    public static String extOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase();
    }

    public static boolean isAudioExt(String ext) {
        return contains(AUDIO_OK, ext) || contains(AUDIO_X, ext);
    }

    public static boolean isVideoExt(String ext) {
        return contains(VIDEO_OK, ext) || contains(VIDEO_X, ext);
    }

    /** 本机能不能真的解这个格式。 */
    public static boolean playable(String ext) {
        return contains(AUDIO_OK, ext) || contains(VIDEO_OK, ext);
    }

    public static boolean known(String ext) {
        return isAudioExt(ext) || isVideoExt(ext);
    }

    /** 能放的格式，给提示语用。 */
    public static String supportedText() {
        return "视频 mp4/mkv/mov/3gp，音频 mp3/flac/wav/m4a/aac/ogg/opus";
    }

    private static boolean contains(String[] arr, String v) {
        if (v == null || v.isEmpty()) return false;
        for (String a : arr) if (a.equalsIgnoreCase(v)) return true;
        return false;
    }

    /* ==================== 从一句话里认格式 ==================== */

    /**
     * 用户这句话里提到的格式（扩展名形式）。没提返回 null。
     *
     * 例：「播放mp4」→ "mp4"；「放个avi的电影」→ "avi"；「听flac」→ "flac"。
     */
    public static String extFromUtterance(String utter) {
        if (utter == null) return null;
        String s = fmtString(utter);
        if (s.isEmpty()) return null;
        for (String[] p : SPOKEN) {
            if (s.contains(p[0])) return p[1];
        }
        return null;
    }

    /**
     * 用户这句话表达的媒体类型（只看格式词）。
     *
     * @return {@link MediaKind#AUDIO} / {@link MediaKind#VIDEO} / {@link MediaKind#ANY}
     */
    public static int kindFromUtterance(String utter) {
        String ext = extFromUtterance(utter);
        if (ext == null) return MediaKind.ANY;
        if (isVideoExt(ext)) return MediaKind.VIDEO;
        if (isAudioExt(ext)) return MediaKind.AUDIO;
        return MediaKind.ANY;
    }

    /* ==================== 时间 ==================== */

    /** 一句话里的时间意图。 */
    public static class TimeSpec {
        /** 绝对位置（毫秒）；-1 = 没给。 */
        public int absMs = -1;
        /** 百分比 0-100；-1 = 没给。 */
        public int pct = -1;
        /** 相对偏移（正 = 往前快进）。 */
        public int deltaMs = 0;
        public boolean hasDelta = false;

        public boolean valid() { return absMs >= 0 || pct >= 0 || hasDelta; }

        @Override public String toString() {
            if (hasDelta) return (deltaMs >= 0 ? "+" : "") + deltaMs + "ms";
            if (pct >= 0) return pct + "%";
            return absMs + "ms";
        }
    }

    private static final Pattern P_CLOCK =
            Pattern.compile("(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?");
    private static final Pattern P_PCT = Pattern.compile("(\\d{1,3})\\s*%");
    private static final Pattern P_PCT_CN = Pattern.compile("百分之\\s*(\\d{1,3})");
    private static final Pattern P_HOUR = Pattern.compile("(\\d{1,2})\\s*(?:个)?(?:小时|钟头)");
    private static final Pattern P_MIN = Pattern.compile("(\\d{1,3})\\s*(?:分钟|分)");
    private static final Pattern P_SEC = Pattern.compile("(\\d{1,3})\\s*秒");

    /**
     * 解析「跳到 2 分钟 / 快进 30 秒 / 放到一半 / 01:30」里的时间。
     * 没提到时间返回 {@code spec.valid() == false}。
     */
    public static TimeSpec parseTime(String utter) {
        TimeSpec t = new TimeSpec();
        if (utter == null) return t;
        final String raw = norm(utter);
        final String s = cn2num(raw);

        // 1) 时钟写法 01:30 / 1:02:03
        Matcher c = P_CLOCK.matcher(s);
        if (c.find()) {
            long ms;
            if (c.group(3) != null) {
                ms = (Long.parseLong(c.group(1)) * 3600L
                        + Long.parseLong(c.group(2)) * 60L
                        + Long.parseLong(c.group(3))) * 1000L;
            } else {
                ms = (Long.parseLong(c.group(1)) * 60L + Long.parseLong(c.group(2))) * 1000L;
            }
            t.absMs = (int) Math.min(Integer.MAX_VALUE - 1, ms);
            return t;
        }

        // 2) 百分比：50% / 百分之五十 / 一半 / 中间
        //    注意「一半」要在 cn2num 之前判：cn2num 会把「一」转成 1，变成「1半」
        Matcher p = P_PCT.matcher(s);
        Matcher p2 = P_PCT_CN.matcher(s);
        if (p.find()) { t.pct = Math.min(100, Integer.parseInt(p.group(1))); return t; }
        if (p2.find()) { t.pct = Math.min(100, Integer.parseInt(p2.group(1))); return t; }
        if (raw.contains("一半") || raw.contains("中间") || raw.contains("正中")) {
            t.pct = 50;
            return t;
        }
        if (raw.contains("开头") || raw.contains("从头")) { t.absMs = 0; return t; }
        if (raw.contains("结尾") || raw.contains("最末")) { t.pct = 100; return t; }

        // 3) 中文口语：1 小时 20 分 / 2 分半 / 90 秒
        long ms = 0;
        boolean any = false;
        Matcher h = P_HOUR.matcher(s);
        if (h.find()) { ms += Long.parseLong(h.group(1)) * 3600_000L; any = true; }
        boolean halfMin = s.contains("分半") || s.contains("半分钟");
        Matcher m = P_MIN.matcher(s);
        if (m.find()) { ms += Long.parseLong(m.group(1)) * 60_000L; any = true; }
        else if (halfMin) { ms += 30_000L; any = true; }
        if (halfMin && m.find(0)) ms += 30_000L;      // 「3分半」= 3分30秒
        if (s.contains("半小时") && !h.find(0)) { ms += 30 * 60_000L; any = true; }
        Matcher sec = P_SEC.matcher(s);
        if (sec.find()) { ms += Long.parseLong(sec.group(1)) * 1000L; any = true; }
        if (!any) return t;

        boolean forward = containsAny(s, new String[]{"快进", "前进", "往前", "向前", "往后跳"});
        boolean back = containsAny(s, new String[]{"快退", "后退", "倒退", "往后", "倒回", "返回"});
        if (forward || back) {
            t.hasDelta = true;
            t.deltaMs = (int) Math.min(Integer.MAX_VALUE / 2, ms);
            if (back) t.deltaMs = -t.deltaMs;
        } else {
            t.absMs = (int) Math.min(Integer.MAX_VALUE - 1, ms);
        }
        return t;
    }

    /**
     * 这句话像不像「动进度条」。
     *
     * 光有时间词不够：「一首三分钟的歌」「有哪些两小时的电影」都不是跳转请求，
     * 必须再带上跳/快进/定位/从…开始这类动词才认。
     */
    public static boolean looksLikeSeek(String utter) {
        if (utter == null) return false;
        String s = norm(utter);
        return containsAny(s, new String[]{
                "跳到", "跳转", "跳过", "快进", "快退", "前进", "后退", "倒退", "倒回",
                "定位", "调到", "调整到", "拖动", "拖到", "移到", "移动到", "回到", "返回到",
                "看到", "放到", "进度", "直接到", "开始播放", "从第", "从", "换到", "切换到"
        });
    }

    /* ==================== 序号 ==================== */

    private static final Pattern P_ORD =
            Pattern.compile("第\\s*(\\d{1,3})\\s*(?:个|首|集|条|支|部|号|张|段|曲|名|位)");
    private static final Pattern P_ORD_NUM =
            Pattern.compile("(?:播放|放|听|看|来)\\s*(\\d{1,3})\\s*(?:号|个|首|集|条|支|部)");

    /**
     * 「第 3 个 / 第 5 首 / 播放 3 号」里的序号；没提到返回 -1。
     *
     * 必须有量词才认 —— 「播放 2002 年的第一场雪」里的 2002 不能被当成序号。
     */
    public static int ordinal(String utter) {
        if (utter == null) return -1;
        String s = cn2num(norm(utter));
        Matcher m = P_ORD.matcher(s);
        if (m.find()) return safeInt(m.group(1));
        Matcher m2 = P_ORD_NUM.matcher(s);
        if (m2.find()) return safeInt(m2.group(1));
        return -1;
    }

    private static int safeInt(String v) {
        try { return Integer.parseInt(v); } catch (Throwable t) { return -1; }
    }

    /**
     * 序号的量词暗示的类型：「第 3 首 / 第 2 曲」= 音频，「第 2 集 / 第 1 部」= 视频。
     * 「第 3 个 / 第 5 条」不表态，返回 ANY。
     */
    public static int ordinalKind(String utter) {
        if (utter == null) return MediaKind.ANY;
        String s = cn2num(norm(utter));
        Matcher m = P_ORD.matcher(s);
        if (!m.find()) return MediaKind.ANY;
        String whole = m.group(0);
        if (whole.contains("首") || whole.contains("曲")) return MediaKind.AUDIO;
        if (whole.contains("集") || whole.contains("部")) return MediaKind.VIDEO;
        return MediaKind.ANY;
    }

    /* ==================== 字符串归一化 ==================== */

    /** 去空白 + 全角数字转半角 + 小写。 */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000') continue;
            if (c >= '０' && c <= '９') c = (char) ('0' + (c - '０'));
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 格式词匹配专用：再把「mp三 / mp四」这种常见误识别纠正成数字。 */
    private static String fmtString(String s) {
        String t = norm(s);
        if (t.isEmpty()) return t;
        return t.replace('三', '3')
                .replace('四', '4')
                .replace('二', '2')
                .replace('一', '1')
                .replace('五', '5');
    }

    /** 中文数字 → 阿拉伯（只处理 0-999 的常见写法：「二十三」「十五」「一百」）。 */
    private static String cn2num(String s) {
        if (s == null) return "";
        final String cn = "零一二三四五六七八九十百两";
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (cn.indexOf(c) >= 0) {
                int j = i;
                while (j < s.length() && cn.indexOf(s.charAt(j)) >= 0) j++;
                String tok = s.substring(i, j).replace('两', '二');
                int v = cnValue(tok);
                if (v >= 0) out.append(v); else out.append(tok);
                i = j;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int cnValue(String tok) {
        if (tok == null || tok.isEmpty()) return -1;
        final String d = "零一二三四五六七八九";
        int total = 0, cur = -1;
        for (int i = 0; i < tok.length(); i++) {
            char c = tok.charAt(i);
            int v = d.indexOf(c);
            if (v >= 0) { cur = v; continue; }
            if (c == '十') { if (total > 0) total += (cur < 0 ? 1 : cur) * 10; else total = (cur < 0 ? 1 : cur) * 10; cur = -1; }
            else if (c == '百') { total += (cur < 0 ? 1 : cur) * 100; cur = -1; }
            else return -1;
        }
        if (cur >= 0) total += cur;
        return total;
    }

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /** 时间戳格式化（给状态栏/字幕用）。 */
    public static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        int m = s / 60;
        int h = m / 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m % 60, s % 60);
        return String.format("%02d:%02d", m, s % 60);
    }
}
