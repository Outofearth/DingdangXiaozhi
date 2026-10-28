package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;

/**
 * 「这个 mp4 到底该当视频放（出画面），还是当音乐放（只出声）」的判定器。
 *
 * 背景：用户手里的 mp4 有相当一部分其实是音乐（MV 的音频轨、演唱会录音、下载站给的
 * 带封面的音频），一律按视频处理会在屏幕上一动不动；反过来全部只出声，又会把真正的
 * 电影当歌放。所以按五级判定，越靠前优先级越高：
 *
 *   1) 指令词/格式词：说「看视频 / 放电影 / 看片」→ 视频；说「只听声音 / 当音乐放」→ 音频；
 *      说「播放 mp4」→ 视频，说「放个 avi」→ 视频，说「听 flac」→ 音频（见 {@link MediaFormats}）。
 *      用户此刻说的话最可信，直接拍板。
 *   2) 手动标记：上一次用户显式选过的方式（SP 里 kind:文件名 → v/a），记住它。
 *   3) 目录：/Movies、/DCIM、/video、/视频 → 视频；/Music、/音乐 → 音频。
 *   4) 媒体参数（仅本地文件，会读一次文件头）：
 *        没有视频轨                 → 音频（纯音乐）
 *        有视频轨但时长 < 20s 或宽 < 480 → 音频（多半是封面/铃声/短提示）
 *        其它                       → 视频
 *   5) 默认：视频容器就当视频。
 *
 * 远程（SMB）文件不能先读头（要先下载），所以只走 1/2/3/5，默认偏向视频。
 */
public class MediaKind {
    private static final String TAG = "MediaKind";

    public static final int ANY = 0;
    public static final int AUDIO = 1;
    public static final int VIDEO = 2;

    private static final String[] VIDEO_WORDS = {
            "看视频", "放视频", "播放视频", "看个视频", "看电影", "看片", "放个片", "放电影",
            "看mv", "视频", "画面", "显示画面", "投屏", "影片", "video", "movie", "电影",
            "电视剧", "看剧", "短片", "纪录片", "看个片子", "出画面"
    };
    private static final String[] AUDIO_WORDS = {
            "只听", "只听声音", "只听歌", "听声音", "不要画面", "关掉画面", "当音乐", "当歌听",
            "纯音频", "只要声音"
    };
    /**
     * 明确指向「音乐」的说法。
     *
     * 注意不能把「播放」「放」单独算音频信号 —— 那是中性动词，后面跟的可能是视频名。
     * 只有句子里明确出现歌/音乐/曲子类的词，才把偏好压到音频。
     */
    private static final String[] AUDIO_STRONG_WORDS = {
            "听歌", "听音乐", "放首歌", "放首歌听", "来首歌", "来首曲子", "放个歌",
            "来一首歌", "放音乐", "播放音乐", "唱首歌", "放首曲子", "歌曲", "这首歌", "那首歌"
    };

    /**
     * 用户这句话有没有明确表达「要看」还是「只听」。
     *
     * 判定顺序（越靠前越硬）：
     *   1) 「只听/不要画面」 —— 一句话里同时出现「视频」和「只听声音」时，用户要后者
     *   2) 清单类问法（「有哪些歌」「有什么视频」）→ 不定类型，让调用方两头都查
     *   3) **格式词**：「播放 mp4」= 视频，「放个 avi」= 视频，「听 flac」= 音频
     *   4) 「看视频/看电影/看片」这类动词+对象
     *   5) 「听歌/放首歌/歌曲」这类音乐指向
     *   6) 以「看」开头（「看小阿七」= 看片子）
     */
    public static int fromUtterance(String utterance) {
        if (utterance == null || utterance.isEmpty()) return ANY;
        String s = utterance.replace(" ", "").toLowerCase();
        for (String w : AUDIO_WORDS) if (s.contains(w)) return AUDIO;
        if (s.contains("什么") || s.contains("哪些") || s.contains("清单")
                || s.contains("列表") || s.contains("多少")) {
            return ANY;                       // 这是问清单，不是点播
        }
        // 格式词最明确：用户说的就是「我要 mp4 那种东西」
        int byFormat = MediaFormats.kindFromUtterance(utterance);
        if (byFormat != ANY) return byFormat;
        for (String w : VIDEO_WORDS) if (s.contains(w)) return VIDEO;
        for (String w : AUDIO_STRONG_WORDS) if (s.contains(w)) return AUDIO;
        if (s.startsWith("看") || s.contains("我要看") || s.contains("想看看")) return VIDEO;
        return ANY;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("xz", Context.MODE_PRIVATE);
    }

    /** 记下用户对某个文件的显式选择（供下次直接沿用）。 */
    public static void remember(Context ctx, String fileName, int kind) {
        if (ctx == null || fileName == null || kind == ANY) return;
        try {
            sp(ctx).edit().putString("kind:" + fileName, kind == VIDEO ? "v" : "a").apply();
        } catch (Throwable ignore) {}
    }

    public static int remembered(Context ctx, String fileName) {
        if (ctx == null || fileName == null) return ANY;
        try {
            String v = sp(ctx).getString("kind:" + fileName, "");
            if ("v".equals(v)) return VIDEO;
            if ("a".equals(v)) return AUDIO;
        } catch (Throwable ignore) {}
        return ANY;
    }

    /** 目录倾向：电影目录/视频目录 → 视频；音乐目录 → 音频。 */
    public static int fromPath(String path) {
        if (path == null) return ANY;
        String p = path.toLowerCase().replace('\\', '/');
        for (String d : new String[]{"/movies", "/movie/", "/dcim", "/video", "/videos",
                "/视频", "/影片", "/电影"}) {
            if (p.contains(d)) return VIDEO;
        }
        for (String d : new String[]{"/music", "/音乐", "/歌曲"}) {
            if (p.contains(d)) return AUDIO;
        }
        return ANY;
    }

    /**
     * 读文件头判断（只有本地文件能做；读不到就返回 ANY，让调用方继续往下判）。
     */
    public static int probe(String path) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            String hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            long dur = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int w = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            Log.i(TAG, "probe " + new File(path).getName() + " video=" + hasVideo
                    + " audio=" + hasAudio + " dur=" + dur + " " + w + "x" + h);
            if (!"yes".equalsIgnoreCase(hasVideo)) {
                // 没有视频轨 → 就是个装错了壳的音频文件
                return AUDIO;
            }
            // 有画面但没有声音轨：当视频放（默片/无声动漫也比干瞪眼强）
            if (!"yes".equalsIgnoreCase(hasAudio)) return VIDEO;
            // 极短或极小：多半是封面动画、铃声、广告片头，当音乐放更合适
            if (dur > 0 && dur < 20_000) return AUDIO;
            if (w > 0 && w < 480) return AUDIO;
            return VIDEO;
        } catch (Throwable t) {
            Log.w(TAG, "probe failed: " + t);
            return ANY;
        } finally {
            try { r.release(); } catch (Throwable ignore) {}
        }
    }

    private static long num(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s.trim()); } catch (Throwable t) { return 0; }
    }

    /**
     * 读「数据源」的文件头判断（远程 SMB 用：jcifs 能随机读，所以不用整首下载）。
     * 读完会关闭数据源。
     *
     * @return {@link #AUDIO} / {@link #VIDEO}，判不出返回 {@link #ANY}
     */
    public static int probeSource(android.media.MediaDataSource ds) {
        if (ds == null) return ANY;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ds);
            String hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO);
            long dur = num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            int w = (int) num(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            Log.i(TAG, "probeSource video=" + hasVideo + " audio=" + hasAudio
                    + " dur=" + dur + " w=" + w);
            if (!"yes".equalsIgnoreCase(hasVideo)) return AUDIO;
            if (!"yes".equalsIgnoreCase(hasAudio)) return VIDEO;
            if (dur > 0 && dur < 20_000) return AUDIO;
            if (w > 0 && w < 480) return AUDIO;
            return VIDEO;
        } catch (Throwable t) {
            Log.w(TAG, "probeSource failed: " + t);
            return ANY;
        } finally {
            try { r.release(); } catch (Throwable ignore) {}
            try { ds.close(); } catch (Throwable ignore) {}
        }
    }

    /**
     * 汇总判定。
     *
     * @param ctx       用于读写「上次选择」偏好，可为 null
     * @param path      本地绝对路径；远程文件传 null
     * @param fileName  文件名（含扩展名），用于偏好键
     * @param utterance 触发这次播放的那句话（可为 null）
     * @param probeable 能不能读文件头（本地文件 true，SMB 远程 false）
     * @return {@link #AUDIO} 或 {@link #VIDEO}（不会返回 ANY）
     */
    public static int decide(Context ctx, String path, String fileName,
                             String utterance, boolean probeable) {
        // 1) 用户这句话本身
        int k = fromUtterance(utterance);
        if (k != ANY) {
            Log.i(TAG, "decide=utterance " + (k == VIDEO ? "视频" : "音频"));
            if (ctx != null && fileName != null) remember(ctx, fileName, k);
            return k;
        }
        // 2) 上次的显式选择
        k = remembered(ctx, fileName);
        if (k != ANY) {
            Log.i(TAG, "decide=remembered " + (k == VIDEO ? "视频" : "音频"));
            return k;
        }
        // 3) 目录
        k = fromPath(path != null ? path : fileName);
        if (k != ANY) {
            Log.i(TAG, "decide=path " + (k == VIDEO ? "视频" : "音频"));
            return k;
        }
        // 3.5) 纯音频扩展名（mp3/flac/wav…）：不可能是视频，连文件头都不用读
        if (MediaFormats.isAudioExt(MediaFormats.extOf(fileName))) {
            Log.i(TAG, "decide=ext 音频");
            return AUDIO;
        }
        // 4) 文件头
        if (probeable && path != null) {
            k = probe(path);
            if (k != ANY) {
                Log.i(TAG, "decide=probe " + (k == VIDEO ? "视频" : "音频"));
                return k;
            }
        }
        // 5) 默认：视频容器就当视频
        Log.i(TAG, "decide=default 视频");
        return VIDEO;
    }

    public static String name(int kind) {
        return kind == VIDEO ? "视频" : "音频";
    }
}
