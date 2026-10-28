package com.altc.xiaozhi;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地音频文件扫描器。
 *
 * 扫描位置：
 *   1. 默认根（{@link #defaultRoots}）：/sdcard/Music、/sdcard/Movies、Video(s)/视频、应用私有 music/
 *   2. 设置页「额外扫描目录」里填的路径（{@link #extraRoots}），在默认根之上**追加**
 *
 * 只认系统解码器确实支持的格式：
 *   音频 mp3 / flac / wav / m4a / aac / ogg / opus
 *   视频 mp4 / m4v / mov / 3gp / mkv（MT8167 的 OMX.MTK.VIDEO.DECODER.AVC、.HEVC 硬解）
 * ape / wma / rmvb / avi 系统不支持，列出来也放不了。
 *
 * 视频文件有两种用法（P1/P2）：用户只想听声音时走 MediaPlayer 只出声（不出画面），
 * 想「看」时交给 VideoActivity。判定见 {@link MediaKind}。
 */
public class LocalFiles {
    private static final String TAG = "LocalFiles";
    /** 纯音频扩展名。 */
    private static final String[] AUDIO_EXT = {
            ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".opus"
    };
    /**
     * 视频容器：这几个都能被系统 MediaPlayer / MediaCodec 打开。
     * 其中 mp4 / m4v / mov / 3gp 是同一个 ISO-BMFF 家族（H.264/HEVC 硬解），
     * mkv 走系统的 Matroska 解析器。webm 只有 VP8 软解，播幻灯片一样，不收。
     */
    private static final String[] VIDEO_EXT = {
            ".mp4", ".m4v", ".mov", ".3gp", ".mkv"
    };
    private static final int MAX_FILES = 2000;
    /** 向下递归的最大层数（见 {@link #collect}）。 */
    private static final int MAX_DEPTH = 6;
    private static final long CACHE_MS = 8000;

    public static class Track implements PlayItem {
        public final File file;
        public final String title;          // 显示 / 朗读用
        public final String[] aliases;      // 匹配用（都已是规范化的键）
        /** 视频容器（mp4/mkv…）。是否真的当视频放，还要过 MediaKind 判定。 */
        public final boolean video;

        Track(File f, String title, String[] aliases) {
            this.file = f;
            this.title = title;
            this.aliases = aliases;
            this.video = isVideoName(f.getName());
        }

        public String path() { return file.getAbsolutePath(); }

        @Override public String source() { return path(); }
        @Override public String displayTitle() { return title; }
        @Override public boolean isVideo() { return video; }
    }

    /** 设置页「额外扫描目录」的 SP 键：多行文本，每行一个路径。 */
    public static final String KEY_MEDIA_DIRS = "mediaDirs";

    private final Context ctx;
    private List<Track> cache;
    private long cachedAt = 0;

    public LocalFiles(Context c) { this.ctx = c.getApplicationContext(); }

    /**
     * 默认扫描根：标准 Music / Movies + 国产盒子常见的 Video(s)、视频 + 应用私有 music。
     *
     * 这些**始终**会被扫（用户填的额外目录是在此之上追加，不是替换），
     * 免得改了目录之后原有歌曲突然"消失"。
     */
    public static List<File> defaultRoots(Context ctx) {
        List<File> roots = new ArrayList<>();
        File music = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        if (music != null) roots.add(music);
        File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        if (movies != null) roots.add(movies);
        try {
            File sd = Environment.getExternalStorageDirectory();
            if (sd != null) {
                for (String n : new String[]{"Video", "Videos", "视频"}) {
                    File v = new File(sd, n);
                    if (v.isDirectory()) roots.add(v);
                }
            }
        } catch (Throwable ignore) {}
        File priv = ctx.getExternalFilesDir(null);
        if (priv != null) roots.add(new File(priv, "music"));
        return roots;
    }

    /** 用户在设置页填的额外目录（已解析成 File，只保留真实存在且可读的）。 */
    public static List<File> extraRoots(Context ctx) {
        List<File> out = new ArrayList<>();
        String raw;
        try {
            raw = ctx.getSharedPreferences("xz", Context.MODE_PRIVATE)
                    .getString(KEY_MEDIA_DIRS, "");
        } catch (Throwable t) {
            return out;
        }
        if (raw == null || raw.trim().isEmpty()) return out;
        // 换行 / 逗号 / 分号 / 顿号 都当分隔符：手机上打字不便，别逼人守格式
        for (String line : raw.split("[\\r\\n,;；、]+")) {
            File f = resolveDir(ctx, line);
            if (f == null) continue;
            boolean dup = false;
            for (File e : out) {
                if (e.getAbsolutePath().equals(f.getAbsolutePath())) { dup = true; break; }
            }
            if (!dup) out.add(f);
        }
        return out;
    }

    /**
     * 用户输入的一行 → 目录 File。不存在 / 不是目录 / 不可读都返回 null。
     *
     * 以 / 开头按绝对路径；否则当作 /sdcard 下的相对路径（「Download/音乐」少打字）。
     */
    public static File resolveDir(Context ctx, String p) {
        String s = cleanPath(p);
        if (s.isEmpty()) return null;
        File f;
        if (s.startsWith("/")) {
            f = new File(s);
        } else {
            File sd = Environment.getExternalStorageDirectory();
            f = (sd == null) ? new File(s) : new File(sd, s);
        }
        return (f.isDirectory() && f.canRead()) ? f : null;
    }

    /**
     * 归一化用户输入的路径：
     * 全角转半角（中文输入法会把 / 打成 ／，实测这台设备的搜狗就是如此）、
     * 去掉资源管理器复制过来的引号、去首尾空白。空串表示这行没内容。
     */
    public static String cleanPath(String p) {
        String s = p == null ? "" : p.trim();
        if (s.isEmpty()) return "";
        StringBuilder nb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) nb.append((char) (c - 0xFEE0)); // 全角 ASCII → 半角
            else if (c == 0x3000) nb.append(' ');                          // 全角空格
            else nb.append(c);
        }
        s = nb.toString().trim();
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')) {
            s = s.substring(1, s.length() - 1).trim();
        }
        return s;
    }

    /** 实际扫描用的根：默认在前、额外在后，去重。 */
    public static List<File> scanRoots(Context ctx) {
        List<File> roots = defaultRoots(ctx);
        outer:
        for (File f : extraRoots(ctx)) {
            for (File r : roots) {
                if (r.getAbsolutePath().equals(f.getAbsolutePath())) continue outer;
            }
            roots.add(f);
        }
        return roots;
    }

    /** 所有可播的本地文件（带 8 秒缓存，避免每次说话都扫盘）。 */
    public synchronized List<Track> tracks() {
        long now = System.currentTimeMillis();
        if (cache != null && now - cachedAt < CACHE_MS) return cache;
        List<Track> out = new ArrayList<>();
        for (File r : scanRoots(ctx)) collect(r, out, 0);
        // 按文件名排序：File.listFiles() 的顺序不保证，「下一首」得有个稳定的顺序
        try {
            out.sort((a, b) -> a.file.getName().compareToIgnoreCase(b.file.getName()));
        } catch (Throwable ignore) {}
        cache = out;
        cachedAt = now;
        Log.i(TAG, "scanned " + out.size() + " audio files");
        return out;
    }

    /** 手动失效（刚推完新歌 / 刚改完扫描目录时用）。 */
    public synchronized void invalidate() { cache = null; cachedAt = 0; }

    /**
     * 设置页「立即重新扫描」用：重扫一遍并给出人话结论。
     * 无效路径单独点出来（打错字是最常见的失败原因）。
     */
    public static String scanReport(Context ctx) {
        LocalFiles lf = new LocalFiles(ctx);
        int n = lf.tracks().size();
        int extraOk = extraRoots(ctx).size();
        List<String> bad = new ArrayList<>();
        String raw = "";
        try {
            raw = ctx.getSharedPreferences("xz", Context.MODE_PRIVATE)
                    .getString(KEY_MEDIA_DIRS, "");
        } catch (Throwable ignore) {}
        if (raw != null) {
            for (String line : raw.split("[\\r\\n,;；、]+")) {
                String s = line.trim();
                if (!s.isEmpty() && resolveDir(ctx, s) == null) bad.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(n).append(" 个媒体文件");
        sb.append("（默认目录");
        if (extraOk > 0) sb.append(" + ").append(extraOk).append(" 个自定义目录");
        sb.append("）");
        if (!bad.isEmpty()) {
            sb.append("\n找不到：");
            for (int i = 0; i < bad.size() && i < 3; i++) {
                if (i > 0) sb.append("、");
                sb.append(bad.get(i));
            }
            if (bad.size() > 3) sb.append(" 等 ").append(bad.size()).append(" 个");
        }
        return sb.toString();
    }

    /**
     * 递归收集目录下的媒体文件。
     *
     * 深度上限 {@link #MAX_DEPTH}：用户的实际用法经常是 Music/歌手/专辑/歌.mp3，
     * 太浅（原来是 3）会把「分类再分一层」的文件夹整片漏掉；放太深又怕误扫整个存储。
     * 6 层足够覆盖正常整理习惯，同时 MAX_FILES 兜住极端情况。
     */
    private void collect(File dir, List<Track> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > MAX_DEPTH || out.size() >= MAX_FILES) return;
        File[] fs;
        try { fs = dir.listFiles(); } catch (Throwable t) { return; }
        if (fs == null) return;
        for (File f : fs) {
            if (out.size() >= MAX_FILES) return;
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
                continue;
            }
            if (!supported(f.getName())) continue;
            if (f.length() < 100 * 1024) continue;          // 太小：多半是碎片
            Track t = build(f, f.getParentFile());
            if (t != null) out.add(t);
        }
    }

    private static boolean supported(String name) {
        return supportedName(name);
    }

    /** 扩展名是否在本机解码器支持范围内（远程库也复用）。 */
    public static boolean supportedName(String name) {
        return isAudioName(name) || isVideoName(name);
    }

    /** 纯音频文件（mp3/flac/…）。 */
    public static boolean isAudioName(String name) {
        String n = name.toLowerCase();
        for (String e : AUDIO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** 视频容器（mp4/mkv/…）。 */
    public static boolean isVideoName(String name) {
        String n = name.toLowerCase();
        for (String e : VIDEO_EXT) if (n.endsWith(e)) return true;
        return false;
    }

    /** 文件名（去扩展名）→ 显示歌名：去前导序号（01. / 01_ / 01- / 01 、）。供远程库复用。 */
    public static String titleFromBase(String base) {
        String title = base.replaceFirst("^\\s*\\d{1,3}\\s*[._\\-、\\s]\\s*", "").trim();
        // 下划线在 TTS 里会被念成 "Book_of_Kells"（逐词断开、很别扭），统一压成单空格。
        // norm() 本来就去空格，所以对匹配没有影响。
        title = title.replace('_', ' ').replaceAll("\\s{2,}", " ").trim();
        return title.isEmpty() ? base : title;
    }

    /** 文件名（去扩展名）→ 匹配别名（规范化、去重）。供远程库复用。 */
    public static String[] aliasesFromBase(String base) {
        String title = titleFromBase(base);
        List<String> al = new ArrayList<>();
        al.add(norm(title));
        al.add(norm(base));
        // "周杰伦 - 稻香" / "稻香-周杰伦"：两边都能当歌名
        for (String sep : new String[]{" - ", "-", "—", "_"}) {
            if (base.contains(sep)) {
                String[] parts = base.split(java.util.regex.Pattern.quote(sep));
                for (String p : parts) {
                    String n = norm(p);
                    // 只收含汉字的片段。英文片段（test / audio / in / mp4）拆出来太泛，
                    // 一句「播放 test_smb_audio」会先把本地《test_audio_in_mp4》命中，
                    // 远程那首反而轮不上 —— 实测踩过这个坑。
                    if (n.length() >= 2 && hasHan(n)) al.add(n);
                }
            }
        }
        List<String> uniq = new ArrayList<>();
        for (String a : al) if (a.length() >= 2 && !uniq.contains(a)) uniq.add(a);
        return uniq.toArray(new String[0]);
    }

    /** 是否含汉字（用来判断一个片段值不值得当别名）。 */
    public static boolean hasHan(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x3400 && c <= 0x9FFF) return true;
        }
        return false;
    }

    private static Track build(File f, File parentDir) {
        String raw = f.getName();
        int dot = raw.lastIndexOf('.');
        String base = dot > 0 ? raw.substring(0, dot) : raw;
        String folder = folderLabel(parentDir);
        String title = decorateTitle(base, folder);
        String[] aliases = decorateAliases(base, folder);
        if (aliases.length == 0) return null;
        return new Track(f, title, aliases);
    }

    /**
     * 子文件夹名 → 用来区分和匹配的前缀。
     *
     * 只在名字含汉字时才采用：`Music/xiaozhi/xxx.mp3` 这种英文技术目录不该变成
     * 「xiaozhi·Song」，而 `Music/佛经/心经.mp3` 变成「佛经·心经」一眼就能区分开，
     * 两个不同文件夹里的同名文件也不会在清单里去重掉一个。
     */
    public static String folderLabel(File dir) {
        if (dir == null) return "";
        String n = dir.getName();
        if (n == null) return "";
        n = n.trim();
        return hasHan(n) ? n : "";
    }

    /** 「佛经」+「六祖坛经」→「佛经·六祖坛经」；folder 为空时原样返回。 */
    public static String decorateTitle(String base, String folder) {
        String t = titleFromBase(base);
        if (folder == null || folder.isEmpty()) return t;
        return folder + "·" + t;
    }

    /**
     * 别名：原文件名 + 文件夹名 + 「文件夹+歌名」。
     *
     * 这样「播放坛经」「播放佛经」「播放佛经的坛经」都能命中 —— 说错/说全都能对上。
     */
    public static String[] decorateAliases(String base, String folder) {
        List<String> al = new ArrayList<>();
        for (String a : aliasesFromBase(base)) al.add(a);
        if (folder != null && !folder.isEmpty()) {
            String f = norm(folder);
            if (f.length() >= 2 && !al.contains(f)) al.add(f);
            for (String e : new String[]{ norm(folder + titleFromBase(base)), norm(folder + base) }) {
                if (e.length() > f.length() && !al.contains(e)) al.add(e);
            }
        }
        return al.toArray(new String[0]);
    }

    // 繁→简 对照（每项「繁体字 + 简体字」两字符）。ASR 一般给简体，而磁盘文件名常带繁体
    // （如 以後的以後 / 莊心妍 / 華語 / 精選），不转换就永远匹配不上。只收常见字，够覆盖歌名。
    private static final String[] TS_PAIRS = {
            "後后", "來来", "愛爱", "們们", "這这", "時时", "樂乐", "夢梦", "遠远", "別别",
            "會会", "對对", "說说", "讓让", "過过", "還还", "裡里", "裏里", "為为", "與与",
            "個个", "麼么", "樣样", "開开", "關关", "聽听", "見见", "買买", "賣卖", "東东",
            "車车", "長长", "門门", "風风", "飛飞", "頭头", "語语", "話话", "讀读", "書书",
            "寫写", "學学", "習习", "國国", "陽阳", "陰阴", "雲云", "電电", "視视", "機机",
            "腦脑", "網网", "頁页", "遊游", "戲戏", "歡欢", "聲声", "響响", "華华", "麗丽",
            "歲岁", "際际", "經经", "貴贵", "壓压", "縮缩", "張张", "條条", "規规", "範范",
            "圍围", "環环", "節节", "約约", "給给", "幾几", "數数", "據据", "統统", "計计",
            "億亿", "萬万", "於于", "從从", "眾众", "專专", "業业", "務务", "員员", "動动",
            "勢势", "勝胜", "負负", "擔担", "莊庄", "選选", "媽妈", "師师", "醫医", "藥药",
            "剛刚", "邊边", "錢钱", "夠够", "發发", "髮发", "乾干", "僅仅", "顧顾", "覺觉",
            "週周", "輕轻", "鬆松", "掃扫", "顆颗", "麵面", "點点", "燈灯", "熱热", "燒烧",
            "湯汤", "飯饭", "擺摆", "處处", "擁拥", "擠挤",
            // 佛经 / 宗教类文件名常整串是繁体（「佛经 準提咒.mp4」「佛经 靜心寧聽.mp4」），
            // 缺这几个字会让「准提咒」「静心聆听」永远匹配不上，最后退到只按文件夹名"佛经"
            // 命中，乖乖放成第一首《六祖坛经》—— 实测踩过。
            "準准", "靜静", "寧宁", "隨随", "嚴严", "誦诵", "願愿", "淨净", "觀观",
            "蓮莲", "薩萨", "懺忏", "彌弥", "圓圆", "滿满", "禪禅", "緣缘", "悅悦",
            "憶忆", "塵尘", "滅灭", "續续", "斷断", "礙碍", "識识", "讚赞", "蘇苏",
            "蘭兰", "靈灵", "顯显", "類类", "順顺", "須须", "頂顶", "項项", "領领",
            "顏颜", "講讲", "護护", "變变", "畫画", "練练", "歷历", "曆历", "奮奋",
            "獨独", "眾众", "燈灯", "鐘钟", "鐘鐘", "劍剑", "俠侠", "債债", "傷伤",
            "價价", "儀仪", "億亿", "兒儿", "兩两", "冊册", "凍冻", "凱凯", "劃划",
            "劉刘", "勞劳", "厲厉", "啟启", "喬乔", "喪丧", "單单", "嘗尝", "噴喷",
            "園园", "圖图", "團团", "堅坚", "報报", "場场", "塊块", "夢梦", "奪夺",
            "奮奋", "婦妇", "嬰婴", "孫孙", "寧宁", "寶宝", "將将", "尋寻", "導导",
            "屆届", "島岛", "嶺岭", "巒峦", "帥帅", "帶带", "幣币", "幫帮", "幹干",
            "廣广", "廟庙", "廠厂", "廳厅", "彈弹", "歸归", "彙汇", "徑径", "復复",
            "憶忆", "懷怀", "懸悬", "懼惧", "戰战", "戲戏", "戶户", "執执", "堅坚",
            "換换", "揚扬", "搖摇", "擔担", "撫抚", "擊击", "擬拟", "擴扩", "攝摄",
            "敘叙", "敗败", "敵敌", "斂敛", "曆历", "書书", "會会", "東东", "極极",
            "構构", "槍枪", "樣样", "樓楼", "標标", "樞枢", "樹树", "橋桥", "檢检",
            "權权", "歐欧", "歡欢", "殘残", "殺杀", "毀毁", "氣气", "漢汉", "溝沟",
            "滅灭", "滄沧", "滯滞", "滿满", "潔洁", "潤润", "潮潮", "濃浓", "濁浊",
            "牆墙", "獎奖", "獨独", "獲获", "現现", "環环", "產产", "畢毕", "異异",
            "疊叠", "療疗", "發发", "盡尽", "監监", "盤盘", "眾众", "礎础", "禍祸",
            "稅税", "穩稳", "窮穷", "竄窜", "競竞", "筆笔", "節节", "範范", "築筑",
            "簡简", "籃篮", "籌筹", "紀纪", "純纯", "紙纸", "級级", "素素", "絕绝",
            "絡络", "絲丝", "維维", "綱纲", "緣缘", "緊紧", "線线", "緒绪", "緣缘",
            "總总", "織织", "繼继", "纏缠", "罰罚", "義义", "聞闻", "聯联", "聲声",
            "職职", "肅肃", "脫脱", "臨临", "臺台", "興兴", "舉举", "舊旧", "艷艳",
            "藝艺", "節节", "莊庄", "華华", "萬万", "葉叶", "蓋盖", "蘇苏", "處处",
            "虛虚", "號号", "蟲虫", "術术", "衛卫", "衝冲", "補补", "裝装", "製制",
            "複复", "見见", "規规", "視视", "親亲", "覺觉", "觀观", "訂订", "計计",
            "訓训", "記记", "訪访", "設设", "許许", "訴诉", "詞词", "詩诗", "話话",
            "該该", "詳详", "認认", "誠诚", "誤误", "說说", "誰谁", "課课", "調调",
            "談谈", "請请", "論论", "諮咨", "諾诺", "謀谋", "講讲", "證证", "識识",
            "譜谱", "警警", "護护", "讀读", "變变", "讓让", "讚赞", "谷谷", "豐丰",
            "豬猪", "貓猫", "貝贝", "負负", "財财", "責责", "貴贵", "買买", "貸贷",
            "費费", "資资", "賓宾", "賞赏", "賠赔", "賢贤", "賣卖", "賤贱", "賦赋",
            "質质", "賴赖", "購购", "賽赛", "贈赠", "贊赞", "贏赢", "趙赵", "趕赶",
            "趨趋", "跡迹", "踐践", "蹤踪", "軍军", "軒轩", "軟软", "較较", "載载",
            "輝辉", "輪轮", "輸输", "轉转", "辦办", "辭辞", "農农", "迴回", "遊游",
            "運运", "過过", "達达", "違违", "遙遥", "遜逊", "遞递", "遲迟", "選选",
            "遺遗", "遼辽", "邁迈", "還还", "邊边", "邏逻", "郵邮", "鄉乡", "鄭郑",
            "醜丑", "釋释", "針针", "釘钉", "釣钓", "鈴铃", "鉤钩", "銀银", "銅铜",
            "鋼钢", "錄录", "錢钱", "錦锦", "鍵键", "鍾钟", "鎮镇", "鏡镜", "鐵铁",
            "鑑鉴", "長长", "門门", "閉闭", "開开", "閒闲", "間间", "閱阅", "闆板",
            "關关", "陽阳", "陰阴", "陣阵", "階阶", "際际", "隨随", "險险", "隱隐",
            "雙双", "雜杂", "雞鸡", "離离", "難难", "雲云", "電电", "霧雾", "靈灵",
            "靜静", "韻韵", "頁页", "頂顶", "項项", "順顺", "須须", "預预", "頓顿",
            "頗颇", "領领", "頭头", "頸颈", "頻频", "顆颗", "題题", "顏颜", "願愿",
            "類类", "顧顾", "顯显", "風风", "飛飞", "飯饭", "飲饮", "飾饰", "養养",
            "餘余", "館馆", "馬马", "駕驾", "騎骑", "驚惊", "驗验", "體体", "高丽",
            "鬥斗", "魚鱼", "鳥鸟", "鳳凤", "鴻鸿", "鵝鹅", "鷹鹰", "鹽盐", "麗丽",
            "麥麦", "黃黄", "點点", "黨党", "齊齐", "齒齿", "龍龙", "龜龟"
    };

    private static final java.util.Map<Character, Character> T2S = new java.util.HashMap<>();
    static {
        for (String p : TS_PAIRS) {
            if (p.length() == 2) T2S.put(p.charAt(0), p.charAt(1));
        }
    }

    /** 规范化：去空格和常见标点、繁转简、转小写。 */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if ("，。、！？!?,.~～:：;；\"'“”‘’()（）[]【】《》<>·".indexOf(c) >= 0) continue;
            Character t = T2S.get(c);
            if (t != null) c = t;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * 单字拼音（无声调，只留 a-z0-9）。
     *
     * 为什么要逐字：整串交给 ICU 时它按「词」选读音，会把「禅乐」读成 chanle；
     * 而用户说的「禅月」是 chanyue。逐字 + 多音字备选表才能对上。
     * 依赖系统 ICU（反射调用，见 {@link #translit}）；没有 ICU 就返回 null，
     * 整层同音容错自动失效，不影响原有的字面匹配。
     */
    private static final java.util.HashMap<Character, String> PY_CACHE = new java.util.HashMap<>();

    public static String charPinyin(char c) {
        synchronized (PY_CACHE) {
            if (PY_CACHE.containsKey(c)) return PY_CACHE.get(c);
        }
        String out = null;
        try {
            String p = translit("Han-Latin", String.valueOf(c));
            if (p != null) {
                String a = translit("Latin-ASCII", p);
                if (a != null) p = a;
                StringBuilder sb = new StringBuilder(p.length());
                for (int i = 0; i < p.length(); i++) {
                    char k = Character.toLowerCase(p.charAt(i));
                    if (k >= 'a' && k <= 'z') sb.append(k);
                }
                if (sb.length() > 0) out = sb.toString();
            }
        } catch (Throwable ignore) {}
        synchronized (PY_CACHE) { PY_CACHE.put(c, out); }
        return out;
    }

    /**
     * 多音字备选读音（只收歌名/日常语里高频的）。
     *
     * 例：「乐」逐字读 lè，但「禅乐 / 音乐」读 yuè；只有把备选读音也纳入比较，
     * 用户说「禅月(chán yuè)」才能对上文件《禅乐》。
     */
    private static String[] polyphone(char c) {
        switch (c) {
            case '乐': case '樂': return new String[]{"yue", "le"};
            case '曲': return new String[]{"qu"};
            case '觉': case '覺': return new String[]{"jiao"};
            case '长': case '長': return new String[]{"zhang"};
            case '重': return new String[]{"chong"};
            case '行': return new String[]{"hang"};
            case '还': case '還': return new String[]{"huan"};
            case '都': return new String[]{"du"};
            case '差': return new String[]{"cha"};
            case '系': case '係': return new String[]{"ji"};
            case '和': return new String[]{"he"};
            case '数': case '數': return new String[]{"shu"};
            case '处': case '處': return new String[]{"chu"};
            case '传': case '傳': return new String[]{"zhuan"};
            case '卷': return new String[]{"juan"};
            case '空': return new String[]{"kong"};
            case '分': return new String[]{"fen"};
            case '单': case '單': return new String[]{"shan"};
            case '解': return new String[]{"xie"};
            case '落': return new String[]{"lao"};
            case '曾': return new String[]{"zeng"};
            case '调': case '調': return new String[]{"diao"};
            case '朝': return new String[]{"chao"};
            case '薄': return new String[]{"bo"};
            case '散': return new String[]{"san"};
            case '结': case '結': return new String[]{"jie"};
            case '了': return new String[]{"liao"};
            case '子': return new String[]{"zi"};
            default: return null;
        }
    }

    private static final class Syl {
        char ch;
        String py;
    }

    /** 只取汉字音节（英文/数字/标点忽略）。 */
    private static java.util.List<Syl> syllables(String s) {
        java.util.List<Syl> out = new java.util.ArrayList<>();
        if (s == null) return out;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x3400 || c > 0x9FFF) continue;     // 只处理 CJK
            String py = charPinyin(c);
            if (py == null) continue;
            Syl sy = new Syl();
            sy.ch = c;
            sy.py = py;
            out.add(sy);
        }
        return out;
    }

    /** 两个音节是否同音（含多音字备选读音）。 */
    private static boolean sylMatch(Syl a, Syl b) {
        if (a.py.equals(b.py)) return true;
        String[] xa = polyphone(a.ch);
        if (xa != null) for (String t : xa) if (t.equals(b.py)) return true;
        String[] xb = polyphone(b.ch);
        if (xb != null) for (String t : xb) if (t.equals(a.py)) return true;
        return false;
    }

    /**
     * 两句话「连续同音的音节数」。
     * 「播放禅月」vs「Zen Music 禅乐1」→ chan + yue(乐的多音字) = 2，判定同音命中。
     * 要求 ≥2 才算命中，单音节偶合不算（避免「播放音乐」胡乱命中）。
     */
    public static int syllableRun(String spoken, String title) {
        java.util.List<Syl> a = syllables(spoken);
        java.util.List<Syl> b = syllables(title);
        if (a.isEmpty() || b.isEmpty()) return 0;
        int best = 0;
        for (int i = 0; i < a.size(); i++) {
            for (int j = 0; j < b.size(); j++) {
                int k = 0;
                while (i + k < a.size() && j + k < b.size()
                        && sylMatch(a.get(i + k), b.get(j + k))) k++;
                if (k > best) best = k;
            }
        }
        return best;
    }

    /**
     * 走反射调用 android.icu.text.Transliterator。
     *
     * 编译用的 android.jar 里没有这个类（javac 会报「找不到符号」），但它从 API 24
     * 起就在系统里，所以反射是最省事的用法：运行时没有就返回 null，整层容错自动失效。
     */
    private static Object transCache0, transCache1;
    private static java.lang.reflect.Method mGet, mTrans;

    private static synchronized String translit(String id, String s) {
        try {
            if (mGet == null) {
                Class<?> tr = Class.forName("android.icu.text.Transliterator");
                mGet = tr.getMethod("getInstance", String.class);
                mTrans = tr.getMethod("transliterate", String.class);
            }
            Object inst;
            if ("Han-Latin".equals(id)) {
                if (transCache0 == null) transCache0 = mGet.invoke(null, id);
                inst = transCache0;
            } else {
                if (transCache1 == null) transCache1 = mGet.invoke(null, id);
                inst = transCache1;
            }
            if (inst == null) return null;
            Object r = mTrans.invoke(inst, s);
            return r instanceof String ? (String) r : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从一句话里认出用户想听哪首本地文件。
     * 取「最长命中」：说「播放周杰伦的稻香」，别名 "周杰伦的稻香" 比 "稻香" 更具体。
     * 三级兜底：正向包含 → 反向包含（说短了）→ 拼音同音（说成同音字）。
     */
    public Track match(String spoken) {
        return match(spoken, MediaKind.ANY);
    }

    /**
     * 带倾向的匹配。
     *
     * @param prefer {@link MediaKind#ANY} / {@link MediaKind#AUDIO} / {@link MediaKind#VIDEO}：
     *               用户说「看视频」时先只在视频里找，说「听歌」时先只在音频里找；
     *               偏好里没找到再退回全量匹配（宁可放错类型，也别什么都不放）。
     */
    public Track match(String spoken, int prefer) {
        String s = norm(spoken);
        if (s.length() < 2) return null;
        java.util.List<Track> all = tracks();
        if (prefer != MediaKind.ANY) {
            java.util.List<Track> pool = new java.util.ArrayList<>();
            for (Track t : all) {
                if (prefer == MediaKind.VIDEO ? t.video : !t.video) pool.add(t);
            }
            if (!pool.isEmpty()) {
                Track t = matchIn(pool, s);
                if (t != null) return t;
            }
        }
        return matchIn(all, s);
    }

    /**
     * 指令里可以剥掉的动词前缀。剥掉之后剩下的才是「用户到底想听哪个」。
     * 长词排前面：先吃「播放」再吃「放」，否则「播放佛经」会剩个「佛经」以外的残渣。
     */
    private static final String[] CMD_PREFIX = {
            "我要听", "我要看", "我想听", "我想看", "给我放", "给我播",
            "来一首", "唱一首", "播放", "放一下", "打开", "听一下", "看一下",
            "唱", "听", "看", "放", "来"
    };

    /** 去掉指令动词，只留内容（入参已规范化）。 */
    public static String contentOf(String normed) {
        String s = normed == null ? "" : normed;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String p : CMD_PREFIX) {
                if (s.length() > p.length() && s.startsWith(p)) {
                    s = s.substring(p.length());
                    changed = true;
                }
            }
        }
        return s;
    }

    /**
     * 泛称 / 疑问词：剥掉之后什么都不剩，就说明这句话是在**问清单**，不是在点某个名字。
     *
     * 「音乐」「歌曲」是泛称，可共享盘里偏偏有《世界顶级纯音乐八首》
     * 《1920-1950 年中國經典歌曲》—— 句子里的「音乐 / 歌曲」和文件名里的两字
     * 同音连续（正好 6 分，刚够门槛），于是「看看有什么音乐」被当成点播直接开播，
     * 用户要的清单反倒没出来（真机实测）。
     */
    private static final String[] GENERIC_WORDS = {
            "有什么", "有哪些", "都有什么", "都有啥", "哪些", "什么", "多少",
            "清单", "列表", "全部", "所有", "一共", "总共", "看看", "看一下", "一下",
            "音乐", "歌曲", "视频", "电影", "片子", "影片", "电视", "短片",
            "音频", "声音", "媒体", "文件", "节目", "东西"
    };

    /**
     * 这句话里有没有「真正的名字」——剥掉动词、泛称、疑问词后还剩 ≥2 个字。
     *
     * 用来挡掉「播放音乐」「看看有什么歌曲」这类没有具体对象的句子：它们不该靠
     * 文件名里恰好含「音乐 / 歌曲」二字去碰运气播放。
     * 「播放纯音乐八首」剥完还剩「纯八首」，仍算有名字，照旧能点播。
     */
    public static boolean hasRealName(String spoken) {
        String c = contentOf(norm(spoken));
        if (c.isEmpty()) return false;
        for (String g : GENERIC_WORDS) {
            if (c.contains(g)) c = c.replace(g, "");
        }
        return c.length() >= 2;
    }

    /**
     * 一句话和一条别名有多像 —— 取代原来「三轮各自抢先返回」的写法。
     *
     * 为什么必须改成打分：以前只要「这句话里包含某条别名」就立刻返回，
     * 而文件夹名「佛经」是所有佛经文件的别名 —— 于是「播放佛经准提咒」会先撞上
     * 第一条含「佛经」的《六祖坛经》并当成命中，具体名字永远轮不到（真机实测）。
     * 现在按分数排队：整串命中 > 命中够长 > 只是同类同音，2 字的文件夹别名垫底。
     *
     * @return 0 表示不像；分数随相似度递增
     */
    public static int aliasScore(String query, String alias) {
        if (query == null || alias == null) return 0;
        final int ql = query.length(), al = alias.length();
        if (al < 2 || ql < 2) return 0;
        final String content = contentOf(query);
        // 1) 别名整串出现在这句话里
        if (query.contains(alias)) {
            int sc = al * 4;
            if (content.equals(alias)) {
                sc += 14;                     // 整句话就是在说它（「播放佛经」）
            } else if (al <= 2) {
                sc -= 4;                      // 2 字短名（多半是文件夹）让位给具体名字
            }
            return sc;
        }
        // 2) 用户只说了名字的一段（「禅乐1」对「Zen Music 禅乐1」）
        if (al >= 3 && ql >= 3 && alias.contains(query)) return ql * 2;
        // 3) 同音容错（「静心聆听」对文件「靜心寧聽」）
        int run = syllableRun(content.length() >= 2 ? content : query, alias);
        if (run >= 2) return run * 3;
        return 0;
    }

    /** 低于这个分数就不算命中：单靠 2 字文件夹别名（4 分）抢不到播放权。 */
    private static final int MATCH_MIN_SCORE = 6;

    private Track matchIn(java.util.List<Track> all, String s) {
        Track best = null;
        int bestScore = 0;
        for (Track t : all) {
            int sc = 0;
            for (String a : t.aliases) {
                int v = aliasScore(s, a);
                if (v > sc) sc = v;
            }
            if (sc > bestScore) { bestScore = sc; best = t; }
        }
        return bestScore >= MATCH_MIN_SCORE ? best : null;
    }

    /** 按名字精确/包含查找（给「播放第 N 首」「上一首」之类用）。 */
    public Track byTitle(String title) {
        String k = norm(title);
        for (Track t : tracks()) {
            if (norm(t.title).equals(k)) return t;
        }
        for (Track t : tracks()) {
            for (String a : t.aliases) if (a.equals(k)) return t;
        }
        return null;
    }

    /** 曲目清单（最多 limit 首，给语音播报用）。 */
    public List<Track> head(int limit) {
        List<Track> all = tracks();
        return all.size() <= limit ? all : all.subList(0, limit);
    }
}
