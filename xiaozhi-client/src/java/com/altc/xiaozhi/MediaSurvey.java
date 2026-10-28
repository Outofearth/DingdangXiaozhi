package com.altc.xiaozhi;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这台音箱到底能放哪些东西」的汇总扫描。
 *
 * 用户问「有哪些歌 / 有哪些视频」时，不能只看一个地方 —— 实际的媒体散在五处：
 *   1) 本机：/sdcard/Music、/sdcard/Movies 等（{@link LocalFiles}）
 *   2) 共享文件夹：家里电脑 / NAS 的 Windows 共享（{@link RemoteLibrary}，SMB）
 *   3) 网络盘：家里 PC 上的 OpenList 网关，底下挂着夸克/阿里/百度/移动（{@link NetDiskLibrary}）
 *   4) 云端：服务端唱过、整首存成 PCM 的曲目（{@link MusicLibrary}）
 *   5) 缓存：播放时整包下到本地的那份（files 目录下的 smb_* / ol_* 文件）
 *
 * 汇总结果按「来源分组 + 全局连续序号」组织，用户听完可以说
 * 「播放第 5 个」，由 MainActivity 直接选中播放（不必再让云端猜歌名）。
 */
public class MediaSurvey {
    private static final String TAG = "MediaSurvey";

    public static final int SRC_LOCAL = 0;
    public static final int SRC_SMB = 1;
    public static final int SRC_NET = 2;
    public static final int SRC_CLOUD = 3;
    public static final int SRC_CACHE = 4;
    /** 来源总数：数组/循环都用它，别写死 4（加了网络盘之后就是这样漏改的）。 */
    public static final int SRC_COUNT = 5;

    public static String srcName(int src) {
        switch (src) {
            case SRC_LOCAL: return "本机";
            case SRC_SMB:  return "共享文件夹";
            case SRC_NET:  return "网络盘";
            case SRC_CLOUD: return "云端缓存";
            default:        return "下载缓存";
        }
    }

    /** 清单里的一项：包住本地文件 / SMB 条目 / 云端 PCM 曲目。 */
    public static class Entry implements PlayItem {
        public final int src;
        /** 本地 / SMB / 下载缓存条目；云端条目为 null。 */
        public final PlayItem item;
        /** 云端 PCM 曲目的歌名（src == SRC_CLOUD 时有效）。 */
        public final String pcmTitle;
        public final String title;
        public final boolean video;
        public final long size;
        /** 朗读用文本（视频会被标上「（视频）」）。 */
        public String label;

        Entry(int src, PlayItem item, String pcmTitle, String title, boolean video, long size) {
            this.src = src;
            this.item = item;
            this.pcmTitle = pcmTitle;
            this.title = title == null || title.isEmpty() ? "未命名" : title;
            this.video = video;
            this.size = size;
            this.label = this.title;
        }

        @Override public String source() {
            return item != null ? item.source() : "pcm:" + pcmTitle;
        }
        @Override public String displayTitle() { return title; }
        @Override public boolean isVideo() { return video; }
    }

    /** 给「视频/音频」条目打标签（复用 MainActivity 的判定，避免各处标准不一）。 */
    public interface Labeller {
        String label(PlayItem it);
    }

    private MediaSurvey() {}

    /**
     * 上一次 {@link #build} 里，各来源「因为别处已经有了而没列出来」的条数。
     *
     * 用户会拿清单里的数字去数文件夹：「共享文件夹里有 11 首歌」，清单只说 9 个
     * （2 首本机也有，被去重了）—— 数字对不上就成了「文件信息不对」。
     * 用 ThreadLocal 传：build 与 overview 总是在同一个线程里前后脚调用，
     * 不走静态字段，免得两个线程互相污染。
     */
    private static final ThreadLocal<int[]> LAST_HIDDEN = new ThreadLocal<>();

    /** 清单末尾要补的一句话（如「网络盘离线」）；null = 不用补。 */
    private static final ThreadLocal<String> LAST_NOTE = new ThreadLocal<>();

    /**
     * 某些来源的组标题要自己写（网络盘要带上「当前在哪个文件夹」和分页信息）。
     * 值和 {@link #LAST_HIDDEN} 一样用 ThreadLocal 传，理由相同。
     */
    private static final ThreadLocal<Map<Integer, String>> LAST_HEAD = new ThreadLocal<>();

    /**
     * 汇总五个来源。
     *
     * 类型判定不只看扩展名：mp4 里只有一条音轨的（MV 音频版、下载站的"带封面音频"）
     * 会被 {@link MediaKind} 判成音频 —— 这种文件出现在"视频"清单里，
     * 用户点进去只会看到黑屏。所以本地文件走完整判定，远程条目只能按扩展名 + 记忆。
     *
     * @param kind {@link MediaKind#ANY} / AUDIO / VIDEO
     */
    public static List<Entry> build(Context ctx, LocalFiles lf, RemoteLibrary rl,
                                    NetDiskLibrary nd, MusicLibrary lib, int kind) {
        List<Entry> out = new ArrayList<>();
        Map<String, Entry> seen = new LinkedHashMap<>();
        final int[] hidden = new int[SRC_COUNT];   // 每个来源被去重掉多少条（overview 要用来说准数字）

        // 1) 本机
        try {
            if (lf != null) {
                for (LocalFiles.Track t : lf.tracks()) {
                    int k = decideLocal(ctx, t);
                    push(out, seen, new Entry(SRC_LOCAL, t, null, t.title,
                            k == MediaKind.VIDEO, t.file != null ? t.file.length() : 0), kind, hidden);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "local failed", e);
        }

        // 2) 共享文件夹（SMB）
        try {
            if (rl != null && rl.enabled()) {
                List<RemoteLibrary.Track> rs = rl.tracks();
                if (rs != null) {
                    for (RemoteLibrary.Track t : rs) {
                        int k = decideRemote(ctx, t);
                        push(out, seen, new Entry(SRC_SMB, t, null, t.displayTitle(),
                                k == MediaKind.VIDEO, t.size), kind, hidden);
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "smb failed", e);
        }

        // 3) 网络盘（OpenList 网关 → 夸克/阿里/百度/移动）
        //
        // 这里**只看当前这一层**，不递归：网盘里 22 个文件夹、第二层就有 2676 个文件，
        // 压平成一份清单念不完（一个「经典老歌1677首」目录就 1677 条）。
        // 用户听完这一层说「查第 3 个」进去，再看下一层 —— 跟人翻网盘一个样。
        // 「说个歌名直接放」走的是另一条路（NetDiskLibrary.match 的全量索引），不受影响。
        LAST_NOTE.set(null);
        Map<Integer, String> head = new java.util.HashMap<>();
        try {
            if (nd != null && nd.enabled()) {
                NetDiskLibrary.Listing ls = nd.listing();
                if (ls == null) {
                    // 还在后台列（1677 个文件那个目录要 4 秒多），先说清楚在做什么，
                    // 别让用户以为「网盘里的东西没了」。
                    head.put(SRC_NET, "网络盘：正在读「" + netDirLabel(nd) + "」，稍等一下再问一次");
                } else {
                    // A1：「有哪些视频」时只列**索引里已知有视频**的文件夹。
                    // 根层 22 个文件夹里真正放片子的只有三四个，全列一遍等于让用户
                    // 一个个进去碰运气。索引本来就收了每个文件的完整路径，
                    // 按父目录一汇总就知道哪个目录出过视频（NetDiskLibrary.videoDirs）。
                    // 索引还没扫完、又一个都没命中时不过滤：不能把「还没扫到」说成「没有」。
                    List<PlayItem> cand = ls.items;
                    Map<String, Integer> vdirs = null;
                    if (kind == MediaKind.VIDEO && ls.dirs > 0) {
                        vdirs = nd.videoDirs();
                        int hit = 0;
                        for (PlayItem it : ls.items) {
                            if (it instanceof NetDiskLibrary.Dir
                                    && vdirs.containsKey(((NetDiskLibrary.Dir) it).path)) hit++;
                        }
                        if (hit > 0 || nd.indexComplete()) {
                            List<PlayItem> f = new ArrayList<>();
                            for (PlayItem it : ls.items) {
                                if (it instanceof NetDiskLibrary.Dir) {
                                    if (vdirs.containsKey(((NetDiskLibrary.Dir) it).path)) f.add(it);
                                } else {
                                    f.add(it);              // 本层直接放着的视频文件照列
                                }
                            }
                            cand = f;
                        }
                    }
                    final boolean filtered = cand != ls.items;
                    final int page = nd.page();
                    // 过滤后的条数一般一页装得下；此时页码无意义，从头列，免得翻页翻空
                    final int from = filtered ? 0 : page * NetDiskLibrary.PAGE_SIZE;
                    final int to = Math.min(cand.size(), from + NetDiskLibrary.PAGE_SIZE);
                    // 文件夹在前、文件在后（Listing 已经排好），只取当前这一页。
                    // 必须整组同一页：用户听到「3. 邓紫棋」就说「查第 3 个」，
                    // 序号要和屏幕上、和 itemAt 里的顺序对得上。
                    final int before = out.size();
                    for (int i = from; i < to; i++) {
                        PlayItem it = cand.get(i);
                        if (it instanceof NetDiskLibrary.Track) {
                            NetDiskLibrary.Track t = (NetDiskLibrary.Track) it;
                            int k = decideNet(ctx, t);
                            push(out, seen, new Entry(SRC_NET, t, null, t.displayTitle(),
                                    k == MediaKind.VIDEO, t.size), kind, hidden);
                        } else {
                            // 文件夹：title 保持干净（「播放第 3 个」这类提示念着不啰嗦），
                            // label 带上「里面大概有几个」—— 用户听到「邓紫棋（97 个）」
                            // 就能判断要不要进去，不必先进去再退出来。
                            NetDiskLibrary.Dir dir = (NetDiskLibrary.Dir) it;
                            Entry en = new Entry(SRC_NET, it, null, dir.name, false, 0);
                            if (filtered && vdirs != null) {
                                // 视频清单里报「这个目录有几个视频」，比报总条目数有用得多
                                Integer v = vdirs.get(dir.path);
                                en.label = dir.name + "（" + (v == null ? 1 : v) + " 个视频）";
                            } else {
                                en.label = dir.label();
                            }
                            push(out, seen, en, kind, hidden);
                        }
                    }
                    final int listed = out.size() - before;
                    if (filtered) {
                        head.put(SRC_NET, netHeadVideo(nd, cand, vdirs));
                    } else {
                        head.put(SRC_NET, netHead(nd, ls));
                    }
                    // 一条都没来得及列（类型过滤掉了）时不能只留个组标题、下面空空：
                    // 纯视频目录里问「有哪些歌」，用户看到「网络盘「Zan」3 个视频：」
                    // 然后什么都没有，会以为坏了。要说清楚「这里没有音频，有 3 个视频」。
                    if (listed == 0 && !filtered) {
                        final String where = netDirLabel(nd);
                        if (ls.videos > 0 && kind == MediaKind.AUDIO) {
                            head.put(SRC_NET, "网络盘「" + where + "」这里没有音频，有 "
                                    + ls.videos + " 个视频（说「有哪些视频」就能列出来）");
                        } else if (ls.playable > 0 && kind == MediaKind.VIDEO) {
                            head.put(SRC_NET, "网络盘「" + where + "」这里没有视频，有 "
                                    + ls.playable + " 个音频（说「有哪些歌」就能列出来）");
                        }
                    }
                    if (filtered && cand.isEmpty()) {
                        // 索引扫完了、这一层确实没有视频目录 —— 说清楚，别让用户以为漏了
                        head.put(SRC_NET, "网络盘" + (nd.atRoot() ? "（根目录）" : "「" + nd.curName() + "」")
                                + "已知的文件夹里没找到视频");
                        LAST_NOTE.set("说「有哪些歌」可以看音频清单，或者直接进文件夹翻。");
                    } else if (to < cand.size()) {
                        LAST_NOTE.set("网络盘还有 " + (cand.size() - to) + " 个没列出来，说「下一批」看后面的。");
                    } else if (!nd.atRoot()) {
                        LAST_NOTE.set("说「返回上一层」回上一层，说「回到根目录」回到最开始那 22 个文件夹。");
                    }
                }
                if (nd.offline()) {
                    head.remove(SRC_NET);
                    LAST_NOTE.set("网络盘暂时连不上（电脑没开机或网关没启动），这一组先没算进来。");
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "netdisk failed", e);
        }
        LAST_HEAD.set(head);

        // 4) 云端（服务端唱过、整首存成 PCM）
        try {
            if (lib != null) {
                for (String t : lib.titles()) {
                    if (t == null || t.isEmpty()) continue;
                    push(out, seen, new Entry(SRC_CLOUD, null, t, t, false, 0), kind, hidden);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "cloud failed", e);
        }

        // 5) 下载缓存（smb_<时间戳>_<原名> / ol_<时间戳>_<原名>）
        try {
            File dir = ctx == null ? null : ctx.getExternalFilesDir(null);
            File[] fs = dir == null ? null : dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String fn = f.getName();
                    if (!fn.startsWith("smb_") && !fn.startsWith("ol_")) continue;
                    if (!LocalFiles.supportedName(fn)) continue;
                    if (f.length() < 100 * 1024) continue;
                    String real = realCacheName(fn);
                    String base = real;
                    int dot = real.lastIndexOf('.');
                    if (dot > 0) base = real.substring(0, dot);
                    LocalFiles.Track tk = new LocalFiles.Track(f,
                            LocalFiles.titleFromBase(base), LocalFiles.aliasesFromBase(base));
                    push(out, seen, new Entry(SRC_CACHE, tk, null, tk.title,
                            decideLocal(ctx, tk) == MediaKind.VIDEO, f.length()), kind, hidden);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "cache failed", e);
        }

        // 标签：视频条目加「（视频）」，让朗读时能听出这个要出画面。
        // 清单只念前一小段，所以后面那些不重复扫一遍文件头。
        // ⚠️ 只改视频条目的 label —— 以前这里顺手把**所有**前 40 条的 label 重置成 title，
        // 结果网络盘文件夹的「（97 个）」全被抹掉了（A2 就是这么失效的）。
        int n = 0;
        for (Entry e : out) {
            if (n++ >= 40) break;
            if (e.video) e.label = e.title + "（视频）";
        }
        LAST_HIDDEN.set(hidden);
        StringBuilder dbg = new StringBuilder();
        for (int i = 0; i < SRC_COUNT; i++) dbg.append(i == 0 ? "" : "/").append(hidden[i]);
        Log.i(TAG, "survey kind=" + kind + " -> " + out.size() + " (去重隐藏 " + dbg + ")");
        return out;
    }

    /** 本地文件（含下载缓存）：走完整五级判定（目录能定就不读文件头）。 */
    private static int decideLocal(Context ctx, PlayItem it) {
        try {
            return MediaKind.decide(ctx, it.source(), nameOf(it), null, true);
        } catch (Throwable t) {
            return it.isVideo() ? MediaKind.VIDEO : MediaKind.AUDIO;
        }
    }

    /** 远程（SMB）：读不了文件头，只能 印象 → 目录 → 扩展名。 */
    private static int decideRemote(Context ctx, RemoteLibrary.Track t) {
        int k = MediaKind.remembered(ctx, t.name);
        if (k != MediaKind.ANY) return k;
        k = MediaKind.fromPath(t.smbUrl);
        if (k != MediaKind.ANY) return k;
        return t.isVideo() ? MediaKind.VIDEO : MediaKind.AUDIO;
    }

    /**
     * 网络盘：读不了文件头，只能 印象 → 路径 → 扩展名（和共享盘同一套判断）。
     *
     * <p>以前这里写死 AUDIO（那一版网盘只收音频），现在网盘视频也能出画面了，
     * 必须按真实类型判 —— 否则「有哪些视频」永远列不出网盘里的片子。
     */
    private static int decideNet(Context ctx, NetDiskLibrary.Track t) {
        int k = MediaKind.remembered(ctx, t.name);
        if (k != MediaKind.ANY) return k;
        k = MediaKind.fromPath(t.path);
        if (k != MediaKind.ANY) return k;
        return t.isVideo() ? MediaKind.VIDEO : MediaKind.AUDIO;
    }

    /** 念的时候用：根上叫「根目录」，子目录用目录名。 */
    private static String netDirLabel(NetDiskLibrary nd) {
        return nd.atRoot() ? "根目录" : nd.curName();
    }

    /**
     * 网络盘组的标题。
     *
     * <p>不能沿用默认的「网络盘 N 个」—— 那一层有可能是 22 个文件夹、0 个文件，
     * 说「22 个音频」是错的；用户拿数字去数文件夹时会对不上。
     */
    private static String netHead(NetDiskLibrary nd, NetDiskLibrary.Listing ls) {
        StringBuilder sb = new StringBuilder("网络盘");
        if (!nd.atRoot()) sb.append("「").append(nd.curName()).append("」");
        else sb.append("（根目录）");
        boolean any = false;
        if (ls.dirs > 0) { sb.append(ls.dirs).append(" 个文件夹"); any = true; }
        if (ls.playable > 0) {
            if (any) sb.append("、");
            sb.append(ls.playable).append(" 个音频");
            any = true;
        }
        if (ls.videos > 0) {
            if (any) sb.append("、");
            sb.append(ls.videos).append(" 个视频");
            any = true;
        }
        if (ls.blocked > 0) {
            sb.append("，另有 ").append(ls.blocked).append(" 个放不了的（曲谱图片之类）");
        }
        if (!any) sb.append("空的");
        return sb.toString();
    }

    /**
     * 「只看视频」时网络盘组的标题。
     *
     * <p>不能沿用 {@link #netHead}：那一份说的是「22 个文件夹」，可这里只列了其中
     * 有视频的 3 个 —— 数字对不上，用户会以为清单漏了东西。
     * 所以标题直接说「已知有视频的 3 个文件夹」，并把「索引还没扫完」这句带上，
     * 免得他把「暂时没发现」当成「真的没有」。
     */
    private static String netHeadVideo(NetDiskLibrary nd, List<PlayItem> cand,
                                       Map<String, Integer> vdirs) {
        int dirs = 0, files = 0, vids = 0;
        for (PlayItem it : cand) {
            if (it instanceof NetDiskLibrary.Dir) {
                dirs++;
                Integer v = vdirs == null ? null : vdirs.get(((NetDiskLibrary.Dir) it).path);
                vids += v == null ? 1 : v;
            } else {
                files++;
            }
        }
        StringBuilder sb = new StringBuilder("网络盘");
        if (nd.atRoot()) sb.append("（根目录）");
        else sb.append("「").append(nd.curName()).append("」");
        boolean any = false;
        if (dirs > 0) { sb.append("已知有视频的 ").append(dirs).append(" 个文件夹"); any = true; }
        if (files > 0) {
            if (any) sb.append("、");
            sb.append(files).append(" 个视频文件");
            any = true;
        }
        if (!any) sb.append("已知的文件夹里没找到视频");
        else if (vids > 0) sb.append("，共 ").append(vids).append(" 个视频");
        if (!nd.indexComplete() && any) sb.append("（索引还在建，可能还有别处有）");
        return sb.toString();
    }

    private static String nameOf(PlayItem it) {
        if (it instanceof RemoteLibrary.Track) return ((RemoteLibrary.Track) it).name;
        if (it instanceof NetDiskLibrary.Track) return ((NetDiskLibrary.Track) it).name;
        String s = it.source();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /**
     * 加一条：按类型过滤 + 去重（同名同类型只留第一个，来源越靠前越优先）。
     * 去重键带上「视频/音频」，否则同一个名字的 mp3 和 mp4 会被吞掉一个。
     */
    private static void push(List<Entry> out, Map<String, Entry> seen, Entry e, int kind,
                             int[] hidden) {
        if (e == null) return;
        // 文件夹不是「可播放条目」，两条规矩都和别人不一样：
        //   · 不参与去重 —— 「邓紫棋」这种目录名在别处可能也有同名条目，
        //     一被吞掉用户就永远进不去（而且清单里看着像网盘是空的）。
        //   · 音频和视频清单**都**要列 —— 网盘视频现在能出画面了，
        //     问「有哪些视频」时「北京人艺话剧」这种文件夹必须看得见，
        //     否则片子永远藏在目录里找不到。
        if (e.item instanceof NetDiskLibrary.Dir) {
            out.add(e);
            return;
        }
        if (kind == MediaKind.AUDIO && e.video) return;
        if (kind == MediaKind.VIDEO && !e.video) return;
        String key = LocalFiles.norm(e.title) + (e.video ? "#v" : "#a");
        if (key.length() <= 3) key = key + "#" + e.size;
        if (seen.containsKey(key)) {
            // 不重复列，但记一笔：overview 要靠它把「文件夹里到底几个」说准。
            // 注意这里也包含「同一来源里的同名文件」（不同专辑都有「01.mp3」），
            // 所以文案写的是「重名或别处已有」，不能只说「别处已有」。
            if (hidden != null && e.src >= 0 && e.src < SRC_COUNT) hidden[e.src]++;
            return;
        }
        seen.put(key, e);
        out.add(e);
    }

    /** `smb_1759000000000_小阿七.mp3` / `ol_1759000000000_小阿七.mp3` → `小阿七.mp3`。 */
    static String realCacheName(String fn) {
        if (fn == null) return fn;
        if (!fn.startsWith("smb_") && !fn.startsWith("ol_")) return fn;
        int i = fn.indexOf('_', 4);
        return i > 0 && i + 1 < fn.length() ? fn.substring(i + 1) : fn;
    }

    /**
     * 朗读/上屏用的清单文本。
     *
     * 序号是**全局连续**的：用户听到「7.某某」就可以说「播放第 7 个」。
     * 长度受 maxChars 限制 —— 云端 TTS 单次工具结果太长会被摘要（10 首念成「有几首」）。
     *
     * <p>版式（用户指定，见 2026-10-27 的清单排版要求）：一句总数 → 空行 →
     * 「来源 N 个：」→ 每条**单独一行**「序号. 名字」→ 空行 → 结尾提示。
     * 旧版用「、」把十几条串成一整段，序号和名字糊在一块儿，用户根本对不上号，
     * 也没法一眼看出哪几条是视频。
     */
    public static String overview(List<Entry> items, int kind, int maxChars) {
        return overview(items, kind, maxChars, 0);
    }

    /**
     * @param speakLines 只把前 N 条写进文本；{@code 0} = 全写。
     *
     * <p>为什么有这个参数：一页 25 条，云端从头念到尾要**五十多秒**，用户早就不耐烦了。
     * 屏幕上一行一条全列着（可以慢慢看、可以点），嘴上只念前十条 ——
     * 听到想要的就说「第几个」，没听到就说「下一批」或者直接说歌名。
     */
    public static String overview(List<Entry> items, int kind, int maxChars, int speakLines) {
        final Map<Integer, String> head0 = LAST_HEAD.get();
        // 一条都没列出来、但网络盘组有话说（「正在读取…」）时不能回「什么都找不到」——
        // 那会把「等一下就有」说成「没有」。
        if (items == null || items.isEmpty()) {
            if (head0 == null || head0.isEmpty()) return emptyText(kind);
            StringBuilder s2 = new StringBuilder();
            for (Integer k : head0.keySet()) {
                if (s2.length() > 0) s2.append('\n');
                s2.append(head0.get(k));
            }
            String note0 = LAST_NOTE.get();
            if (note0 != null) s2.append('\n').append(note0);
            return s2.toString();
        }
        final int total = items.size();
        int dirCount = 0;
        for (Entry e : items) if (e.item instanceof NetDiskLibrary.Dir) dirCount++;
        StringBuilder sb = new StringBuilder();
        if (dirCount > 0) {
            // 有文件夹时必须分开说：22 个文件夹 + 3 首歌说成「25 个音频」，
            // 用户点进去发现「音频」其实是目录，就不信这个清单了。
            sb.append("好嘞，这边一共有 ").append(total).append(" 个（")
                    .append(dirCount).append(" 个文件夹");
            if (total > dirCount) {
                sb.append("、").append(total - dirCount).append(" 个")
                        .append(kind == MediaKind.VIDEO ? "视频" : "音频");
            }
            sb.append("）！\n");
        } else {
            sb.append("好嘞，这边一共有 ").append(total).append(" 个")
                    .append(kind == MediaKind.VIDEO ? "视频"
                            : kind == MediaKind.AUDIO ? "音频" : "可播放的")
                    .append("！\n");
        }

        int[] per = new int[SRC_COUNT];
        for (Entry e : items) if (e.src >= 0 && e.src < SRC_COUNT) per[e.src]++;
        final int[] hidden = LAST_HIDDEN.get();
        final Map<Integer, String> head = LAST_HEAD.get();

        int idx = 0;
        int written = 0;                 // 真正写进文本的条目数（念清单就是念这些）
        boolean truncated = false;       // 长度到顶
        boolean spoken = false;          // 念够了：剩下的只上屏、不出声
        for (int src = 0; src < SRC_COUNT && !truncated; src++) {
            final String headText = head == null ? null : head.get(src);
            // 有自定义标题但一条都没列出来（「正在读取…」「网络盘空了」）也要显示，
            // 否则那句提示永远出不来 —— 用户只会觉得「网络盘这一组消失了」。
            if (per[src] == 0 && headText == null) continue;
            if (sb.length() > maxChars) { truncated = true; break; }
            final int hid = (hidden != null && src < hidden.length) ? hidden[src] : 0;
            // 组标题前空一行，把各来源分开；数字要和用户数文件夹时看到的一致：
            // 本机已有的那几首会被去重，只说 9 个而文件夹里是 11 首，就成了「文件信息不对」。
            if (headText != null) {
                // 只有真的跟着条目才加冒号：像「正在读「根目录」，稍等一下」这种
                // 整句话再加个冒号（…再问一次：）读起来很怪。
                sb.append('\n').append(headText);
                if (per[src] > 0) sb.append("：");
                sb.append('\n');
            } else {
                sb.append('\n').append(srcName(src)).append(" ").append(per[src]).append(" 个");
                if (hid > 0) {
                    sb.append("（实际 ").append(per[src] + hid)
                            .append(" 个，").append(hid).append(" 个重名或别处已有，没重复列）");
                }
                sb.append("：\n");
            }
            for (Entry e : items) {
                if (e.src != src) continue;
                idx++;
                // 序号照样往下走（第 13 个就是第 13 个），只是不再写进文本。
                if (speakLines > 0 && written >= speakLines) { spoken = true; continue; }
                String l = e.label == null ? e.title : e.label;
                if (sb.length() + l.length() + 8 > maxChars) { truncated = true; break; }
                sb.append(idx).append(". ").append(l).append('\n');
                written++;
            }
        }
        final String note = LAST_NOTE.get();
        if (note != null) sb.append('\n').append(note).append('\n');
        final int rest = total - written;
        if (spoken && rest > 0) {
            sb.append("\n（后面还有 ").append(rest).append(" 个，都列在屏幕上了，说名字或者第几个就能放。）");
        } else if (truncated) sb.append("\n还有更多没说，说「第几个」也能直接挑。");
        else if (dirCount > 0) sb.append("\n说「查第几个」进文件夹看，说名字也能直接放。");
        else sb.append("\n说「播放第几个」就能直接放。");
        return sb.toString();
    }

    /**
     * 兜底版式修正：把「1.xxx，2.yyy，3.zzz」这种被压成一整段的清单拆回一行一条。
     *
     * <p>什么时候会用到：本地认不出用户那句话，整轮交给云端；云端自己调了
     * {@code self.media.list}，却把工具结果**改写成一段话**（换行全被它换成逗号），
     * 屏幕上一堆名字堆在一起 —— 用户反馈「格式又回到堆在一起的状态了」。
     * 主路径是本地补一份 {@link #overview} 上屏（版式天然正确），这个方法是
     * 万一那段改写还是漏到屏幕上的最后一道防线。
     *
     * <p>只在「一段里断出 ≥2 行」时才动手：带序号的（{@code 1.xxx，2.yyy}），
     * 或者一段里顿号 ≥4 个（云端把序号删了、只剩 {@code A、B、C、D…}）。
     * 普通回答（含「3.5 公里」这类）原样返回。
     */
    public static String reflowNumbered(String text) {
        if (text == null || text.length() < 12) return text;
        try {
            // 序号前面通常是逗号/顿号/分号（云端改写时把换行换成了这些），在这些位置断行
            String s = text.replaceAll("(?<=[，,、；;])\\s*(?=\\d{1,2}[.．](?!\\d))", "\n");
            // 也有用句号/问号收尾再接序号的
            s = s.replaceAll("(?<=[。.!！?？])\\s*(?=\\d{1,2}[.．](?!\\d))", "\n");
            int lines = countLines(s);
            // 不带序号的：「A、B、C、D…」—— 云端常把清单里的「1.」整个删掉，只剩一串顿号；
            // 一段里顿号 ≥4 个基本就是清单被压成一行了（用户实测漏上屏的那条就是这样）。
            if (lines < 2 && countChar(s, '、') >= 4) {
                s = s.replace('、', '\n');
                lines = countLines(s);
            }
            if (lines < 2) return text;              // 不是清单，原样返回
            // 序号后面统一成「N. 」，跟本地版式一致；行尾残留的顿号/逗号去掉
            s = s.replaceAll("(^|\\n)(\\d{1,2})[.．]\\s*", "$1$2. ");
            s = s.replaceAll("(?m)[、，,]\\s*$", "");
            return s.trim();
        } catch (Throwable t) {
            return text;
        }
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }

    private static int countChar(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    public static String emptyText(int kind) {
        if (kind == MediaKind.VIDEO) {
            return "这台音箱还没找到视频。把电影拷到 Movies 目录，或者在设置里打开共享文件夹／网络盘。";
        }
        if (kind == MediaKind.AUDIO) {
            return "这台音箱还没找到音乐。把歌拷到 Music 目录，或者在设置里打开共享文件夹／网络盘。";
        }
        return "本机、共享文件夹、网络盘、云端缓存里都还没有可播放的音乐或视频。";
    }
}
