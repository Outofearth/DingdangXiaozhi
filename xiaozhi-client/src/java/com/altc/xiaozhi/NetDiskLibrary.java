package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDataSource;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 网络盘（OpenList 网关）。
 *
 * <h3>为什么是「网关」而不是直连网盘</h3>
 * 夸克 / 阿里 / 百度 / 移动四家里只有百度有个人能用的官方接口，其余要么不开放、
 * 要么审核不过。所以在家里 PC 上跑一个 OpenList（`docs/14` 有部署手册），
 * 四家网盘都挂在它下面；音箱这边只认它一个 HTTP 源 —— 换网盘不用改代码。
 *
 * <h3>接口契约（v4.2.9 实测，见 docs/14 §6）</h3>
 * <pre>
 *   POST /api/auth/login  {username,password}        → data.token（48 小时过期）
 *   POST /api/fs/list     {path,page,per_page,refresh:false} → data.content[]
 *   POST /api/fs/get      {path}                     → data.raw_url
 *   GET  raw_url          支持 Range（206），Content-Type: audio/mpeg
 * </pre>
 *
 * <h3>三个必须遵守的细节（都踩过）</h3>
 * <ul>
 *   <li>设备用的是**只读账号**（`speaker`，permission=2）：带 `refresh:true` 直接 403，
 *       表现是「列出 0 项」。所以这里永远 refresh=false，靠网关 30 分钟缓存。</li>
 *   <li>token 48 小时过期：401 时清掉重登一次，不能让用户隔天就发现「网盘不见了」。</li>
 *   <li>直链是网关按**请求 Host** 生成的：万一给回来的是 127.0.0.1，设备上打不开，
 *       {@link #fixHost} 会把它改回网关地址。</li>
 * </ul>
 *
 * <h3>清单不能卡住界面</h3>
 * 一次扫描要遍历网盘目录，每层都是一次 HTTP（夸克还要过网关转一手），
 * 所以 {@link #tracks()} **绝不同步扫**：有缓存就返回缓存，没缓存就起后台线程扫，
 * 先返回空。启动时 {@link #prewarm()} 预热一次，用户真正开口前就已经扫完了。
 *
 * <h3>两条线，各管一件事</h3>
 * <ul>
 *   <li>**清单**（{@link #listing()}）：只列**当前这一层**，一层一层翻 —— 用户就是这么翻网盘的。</li>
 *   <li>**索引**（{@link #tracks()}）：BFS 一层一层累积，落盘续跑，负责「说个名字直接放」。
 *       音频和视频都收（视频交给 VideoActivity 出画面）。</li>
 * </ul>
 */
public class NetDiskLibrary {
    private static final String TAG = "NetDisk";

    /* 配置键（SharedPreferences "xz"） */
    public static final String KEY_ENABLED = "olEnabled";
    public static final String KEY_BASE = "olBase";
    public static final String KEY_PATH = "olPath";
    public static final String KEY_USER = "olUser";
    public static final String KEY_PASS = "olPass";

    /*
     * 默认值只是**占位示例**：这里没有、也不应该有任何真实网关地址或凭据。
     * 第一次使用请到「设置 → 网络盘」填自己的网关地址与账号，点「测试连接」确认能列目录。
     */
    public static final String DEF_BASE = "http://192.168.1.100:5244";
    public static final String DEF_PATH = "/quark";
    public static final String DEF_USER = "admin";
    public static final String DEF_PASS = "";

    /** 清单缓存 5 分钟：网关那边本身还有 30 分钟缓存，这里只是少打几次接口。 */
    private static final long OK_TTL = 5 * 60 * 1000L;
    /** 失败后 30 秒内不再重试，避免网关关机时每句话都等一次超时。 */
    private static final long FAIL_TTL = 30 * 1000L;
    /**
     * 「按名字直达」用的**全量索引**：一次只列一层，分多趟累积，落盘续跑。
     *
     * <p>以前是一次递归扫完（最多 5000 条、只收音频）：扫得慢、重启就白扫，
     * 而且超出上限或被 `MAX_PER_DIR` 截断的文件永远「说名字点不到」——
     * 用户的体感是「明明有这首歌，它说找不到」。
     *
     * <p>现在改成 BFS：每趟只推进若干个目录（{@link #PASS_BUDGET_MS}），
     * 每趟结束把「已列的目录 + 已收的文件」写进 `files/ol_index.tsv`。
     * 下次启动直接读盘、从断点继续，**越用越全**，永远不需要「等它扫完」。
     */
    private static final int MAX_DEPTH = 6;          // 实测最深 5 层（国粤翻唱/CD01/…），留一层余量
    /** 索引条目上限（内存约 5MB）。真实库 2676 个文件，远到不了。 */
    private static final int INDEX_MAX = 12000;
    /** 单个目录最多收多少（1677 首那个目录必须全收，否则说名字点不到）。 */
    private static final int MAX_PER_DIR = 2000;
    /** 一趟最多跑多久：跑完就落盘、让出网络，下一趟接着来。 */
    private static final long PASS_BUDGET_MS = 5000L;
    /** 两趟之间歇一下，别把 Wi-Fi 占满（会影响正在播的流）。 */
    private static final long PASS_GAP_MS = 800L;
    /** 一趟最多列这么多个目录（防止一个超慢的网关把一趟拖成几分钟）。 */
    private static final int PASS_MAX_DIRS = 40;
    /** 索引扫完之后多久重扫一遍（网盘里新加的文件要能自动出现）。 */
    private static final long INDEX_TTL = 30 * 60 * 1000L;
    /**
     * 索引文件名**绝不能以 `ol_` 开头**：缓存清理（`StorageCtl`）和 LRU（`pruneCache`）
     * 都是按 `smb_` / `ol_` 前缀扫的，索引一旦叫 `ol_index.tsv` 就会被当缓存删掉。
     */
    private static final String INDEX_FILE = "olidx.tsv";
    /**
     * 清单里一页显示多少条。
     *
     * 实测「经典老歌1677首」一个目录就有 1677 个文件 —— 一次全念完要十几分钟，
     * 云端 TTS 也会截断。所以固定一页 25 条，说「下一批」翻页。
     * 取 25 而不是 20 是为了让**根目录那 22 个文件夹一页装得下**：
     * 最常见的一句话「网盘里有什么」不该还要再翻一次页。
     */
    public static final int PAGE_SIZE = 25;
    /** 目录缓存最多留几层（来回进出不重复打网关）。 */
    private static final int LISTING_CACHE = 12;
    /**
     * 探测「每个文件夹里有多少个」：清单生成时最多等这么久，剩下的留给后台慢慢补。
     *
     * <p>每个子目录都要多打一次 {@code fs/list}（根层实测 11ms~600ms，1677 首那个目录 4.2s）。
     * 实测 22 个文件夹里混进一个慢目录，整层就被拖到 6 秒 —— 用户等不了。
     * 所以：前台只等 {@link #PROBE_MS}，没探完的交给后台线程继续，
     * {@link Dir#count} 是 volatile 的、Dir 对象又挂在缓存里的 Listing 上，
     * 后台补完的数字**下次进这一层就能看到**，不影响这一次的清单。
     */
    private static final int PROBE_MS = 1500;
    /** 后台补数字最多跑多久（再久也没意义了，用户早换目录了）。 */
    private static final int PROBE_BG_MS = 8000;
    private static final int PROBE_MAX_DIRS = 40;
    /** 并发探几个目录：串行太慢（22 个目录 × 0.5s），太多又白占带宽。 */
    private static final int PROBE_THREADS = 3;
    /** 太小的多半是封面/歌词/碎片。 */
    private static final long MIN_SIZE = 50 * 1024;

    public static final long CACHE_MAX_BYTES = RemoteLibrary.CACHE_MAX_BYTES;

    /** 网盘曲目。source() 前缀 `ol:` 让播放链路能一眼认出它该走 HTTP 而不是本地文件。 */
    public static class Track implements PlayItem {
        public final String path;      // /quark/京剧/锁麟囊.mp3（网关内的绝对路径）
        public final String name;      // 文件名（含扩展名）
        public final String title;
        public final String[] aliases;
        public final long size;
        /** 直链缓存：带签名，约 10 分钟有效，别当永久地址存。 */
        volatile String raw;
        volatile long rawAt;

        Track(String path, String name, String title, String[] aliases, long size) {
            this.path = path;
            this.name = name;
            this.title = title;
            this.aliases = aliases;
            this.size = size;
        }

        @Override public String source() { return "ol:" + path; }
        @Override public String displayTitle() { return title; }
        @Override public boolean isVideo() { return LocalFiles.isVideoName(name); }
    }

    /**
     * 网盘里的一个**文件夹**（清单里的一条，选中就下钻）。
     *
     * <p>{@code source()} 用 `oldir:` 前缀，和曲目的 `ol:` 分开 —— 播放链路看到
     * `oldir:` 就知道这不是「播这个文件」，而是「进去看看」，不会拿它去开播放器。
     */
    public static class Dir implements PlayItem {
        public final String path;   // /quark/京剧+河北梆子（音视频+无损CD）
        public final String name;
        /**
         * 里面大概有多少个条目（文件夹 + 文件）。-1 = 还没探到。
         *
         * <p>网关的 {@code fs/list} 只列一层，**不返回**子目录里有几个东西，
         * 所以要额外为每个文件夹打一次请求（{@link #probeDirs}）。
         * 只探一层、只探有限个、超时就停 —— 用户听到「邓紫棋（97 个）」
         * 就能判断要不要进去，不用先进去再退出来。
         */
        public volatile int count = -1;

        Dir(String path, String name) {
            this.path = path;
            this.name = name;
        }

        /** 清单里念的样子：「邓紫棋（97 个）」「空文件夹」「CD01」（还没探到就不瞎说）。 */
        public String label() {
            int n = count;
            if (n < 0) return name;
            if (n == 0) return name + "（空文件夹）";
            return name + "（" + n + " 个）";
        }

        @Override public String source() { return "oldir:" + path; }
        @Override public String displayTitle() { return name; }
        @Override public boolean isVideo() { return false; }
    }

    /**
     * 一个目录的「一层」内容。
     *
     * <p>刻意**不递归**：用户要的是「先看看这一层有哪些文件夹和文件」，
     * 递归压平会把 2676 个文件糊成一份念不完的清单（这正是上一版的问题）。
     */
    public static class Listing {
        public final String path;
        /** 文件夹在前（保持网关返回顺序），能播的文件在后。 */
        public final List<PlayItem> items;
        public final int dirs;
        /** 这一层能播的**音频**条数。 */
        public final int playable;
        /** 这一层能播的**视频**条数（画面交给 VideoActivity）。 */
        public final int videos;
        /** 这版放不了的（曲谱图片 / 封面 / 奇怪格式），只报个数不列。 */
        public final int blocked;
        final long at;

        Listing(String path, List<PlayItem> items, int dirs, int playable, int videos, int blocked) {
            this.path = path;
            this.items = items;
            this.dirs = dirs;
            this.playable = playable;
            this.videos = videos;
            this.blocked = blocked;
            this.at = System.currentTimeMillis();
        }

        public int size() { return items.size(); }
    }

    private final Context ctx;

    private volatile List<Track> cache;
    private volatile String cacheKey;
    private volatile long cacheOkAt;
    private volatile long failAt;
    private volatile boolean scanning;
    private volatile String lastError;

    /** 目录缓存：path → 一层内容。access-order，超 12 层淘汰最久没用的。 */
    private final java.util.LinkedHashMap<String, Listing> listings =
            new java.util.LinkedHashMap<String, Listing>(16, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Listing> e) {
                    return size() > LISTING_CACHE;
                }
            };
    /** 当前停在哪个目录（null = 挂载根）。用户说「回到根目录」就清空。 */
    private volatile String curPath;
    /** 当前目录的第几页（0 起）。换目录时归零。 */
    private volatile int curPage;
    /** 正在后台列哪个目录（同一个目录不重复起线程）。 */
    private volatile String loadingPath;

    /** 登录态（48 小时有效，401 时强制重登）。 */
    private volatile String token;
    private volatile long tokenAt;

    public NetDiskLibrary(Context c) { ctx = c.getApplicationContext(); }

    private SharedPreferences sp() { return ctx.getSharedPreferences("xz", Context.MODE_PRIVATE); }

    /* ==================== 配置 ==================== */

    /**
     * 默认**开**：网关就在家里 PC 上，装好即用；关掉只是让清单少「网络盘」一组。
     * 真没有网关时扫描失败也是后台静默的，不影响本机/共享盘。
     */
    public boolean enabled() { return sp().getBoolean(KEY_ENABLED, true); }

    public String base() {
        String b = sp().getString(KEY_BASE, DEF_BASE).trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
        return b;
    }

    /** 挂载点路径（网关里的目录），如 /quark。 */
    public String rootPath() {
        String p = sp().getString(KEY_PATH, DEF_PATH).trim();
        if (p.isEmpty()) p = "/";
        if (!p.startsWith("/")) p = "/" + p;
        while (p.endsWith("/") && p.length() > 1) p = p.substring(0, p.length() - 1);
        return p;
    }

    private String user() { return sp().getString(KEY_USER, DEF_USER).trim(); }
    private String pass() { return sp().getString(KEY_PASS, DEF_PASS); }

    private String key() { return base() + "|" + rootPath() + "|" + user(); }

    private File cacheDir() {
        File d = ctx.getExternalFilesDir(null);
        return d != null ? d : ctx.getCacheDir();
    }

    /* ==================== HTTP 基础 ==================== */

    /** 401 专用异常：用来区分「该重登」和「真出错」。 */
    private static class AuthException extends IOException {
        AuthException(String s) { super(s); }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return new String(bos.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            try { in.close(); } catch (Throwable ignore) {}
        }
    }

    private String http(String apiPath, JSONObject body, String tok) throws IOException {
        HttpURLConnection c = null;
        try {
            URL u = new URL(base() + apiPath);
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Accept", "application/json");
            if (tok != null && !tok.isEmpty()) c.setRequestProperty("Authorization", tok);
            if (body != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                byte[] raw = body.toString().getBytes(Charset.forName("UTF-8"));
                c.setFixedLengthStreamingMode(raw.length);
                try (OutputStream os = c.getOutputStream()) { os.write(raw); }
            }
            final int code = c.getResponseCode();
            String s = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 401) throw new AuthException(s);
            if (code >= 400) throw new IOException("HTTP " + code + " " + s);
            return s;
        } finally {
            if (c != null) {
                try { c.disconnect(); } catch (Throwable ignore) {}
            }
        }
    }

    private synchronized String token(boolean force) throws IOException {
        if (!force && token != null && !token.isEmpty()
                && System.currentTimeMillis() - tokenAt < 40L * 3600 * 1000) {
            return token;
        }
        JSONObject b = new JSONObject();
        try {
            b.put("username", user());
            b.put("password", pass());
        } catch (Throwable ignore) {}
        String s = http("/api/auth/login", b, null);
        String t = "";
        try {
            JSONObject o = new JSONObject(s);
            JSONObject d = o.optJSONObject("data");
            t = d == null ? "" : d.optString("token", "");
            if (t.isEmpty()) throw new IOException("登录失败：" + o.optString("message", s));
        } catch (IOException e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("登录返回看不懂：" + s);
        }
        token = t;
        tokenAt = System.currentTimeMillis();
        Log.i(TAG, "login ok as " + user());
        return t;
    }

    /** 调一个需要登录的接口，401 自动重登重试一次。 */
    private JSONObject api(String apiPath, JSONObject body) throws IOException {
        String t = token(false);
        try {
            return parse(http(apiPath, body, t));
        } catch (AuthException e) {
            Log.w(TAG, "token 失效，重登");
            token = null;
            String t2 = token(true);
            return parse(http(apiPath, body, t2));
        }
    }

    /** JSON 解析失败也算 IO 错 —— 网关返回网页（502 页面之类）时别把调用方搞崩。 */
    private static JSONObject parse(String s) throws IOException {
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            throw new IOException("网关返回不是 JSON：" + (s == null ? "" : s.substring(0, Math.min(120, s.length()))));
        }
    }

    /* ==================== 列目录 ==================== */

    /**
     * 列一个目录的一层内容。
     *
     * @param perPage 要多少条；**0 = 不限**。
     *                这里以前写死 200，于是「经典老歌1677首」永远只看得到前 200 个
     *                （分页浏览必须拿全量，否则「下一批」翻到第 200 个就没了）。
     *                递归索引走 {@link #MAX_PER_DIR} 那个上限就够，不必拉全量。
     */
    private JSONObject listDir(String path, int perPage) throws IOException {
        JSONObject b = new JSONObject();
        try {
            b.put("path", path);
            b.put("page", 1);
            b.put("per_page", perPage);
            // 只读账号带 refresh 会被网关判 403（Refresh without permission），
            // 表现为「列出 0 项」—— 所以这一行必须是 false。
            b.put("refresh", false);
        } catch (Throwable ignore) {}
        JSONObject o = api("/api/fs/list", b);
        if (o.optInt("code") != 200) {
            throw new IOException("list " + path + " → " + o.optInt("code")
                    + " " + o.optString("message"));
        }
        JSONObject d = o.optJSONObject("data");
        return d == null ? new JSONObject() : d;
    }

    /* ==================== 清单 ==================== */

    private static final List<Track> EMPTY = new ArrayList<>();

    /**
     * 曲目索引（给「说个名字直接放」用）。**永不同步扫描**：有缓存给缓存，没缓存起后台线程。
     *
     * <p>索引是**累积**的：每趟只列若干个目录，列完落盘；下次启动读盘续跑。
     * 所以这里不做「扫完才给用」——一开始有几条就先用几条。
     */
    public List<Track> tracks() {
        if (!enabled()) return EMPTY;
        loadIndex();
        final String k = key();
        if (cacheKey != null && !k.equals(cacheKey)) resetIndex();   // 换网关/挂载目录：旧索引作废
        cacheKey = k;
        final long now = System.currentTimeMillis();
        if (!scanning) {
            if (indexComplete) {
                // 扫完了就少动：半小时才重扫一次，不然 30 秒的扫描会一直占着 Wi-Fi
                if (now - cacheOkAt >= INDEX_TTL) { refreshIndex(); startScan(); }
            } else if (now - lastPassAt >= PASS_GAP_MS) {
                startScan();
            }
        }
        List<Track> cur = cache;
        if (cur == null) { cur = snapshot(); cache = cur; }
        return cur;
    }

    /**
     * 同步探一次（设置页「测试连接」、调试命令用；绝不在主线程调）。
     *
     * <p>只列**根层**：以前这里做全量递归，一个 1677 首的目录要等 4 秒以上，
     * 而用户想知道的其实就一句「连得上吗、里面有什么」。
     */
    public String testConnection() {
        try {
            Listing l = list(rootPath());
            if (l == null) return lastError == null ? "连不上网关" : lastError;
            lastError = null;
            failAt = 0;
            if (l.dirs == 0 && l.playable == 0) {
                return l.blocked > 0
                        ? "连上了，但这里只有 " + l.blocked + " 个这版放不了的文件（视频/曲谱）"
                        : "连上了，但目录是空的";
            }
            StringBuilder sb = new StringBuilder("连接成功，这里有 ");
            if (l.dirs > 0) sb.append(l.dirs).append(" 个文件夹");
            if (l.dirs > 0 && l.playable > 0) sb.append("、");
            if (l.playable > 0) sb.append(l.playable).append(" 个可播放的文件");
            if (l.blocked > 0) sb.append("，另有 ").append(l.blocked).append(" 个放不了的");
            return sb.toString();
        } catch (Throwable t) {
            return "连接失败：" + t;
        }
    }

    /**
     * 启动时预热，别等第一句语音点歌才去扫。
     *
     * <p>两条线各预热一份：清单要的**根层一层**（11ms，用户一开口就能看到 22 个文件夹），
     * 以及「按名字直达」要的全量索引（后台慢慢扫，不影响第一句话）。
     */
    public void prewarm() {
        try { listing(); } catch (Throwable ignore) {}
        try { tracks(); } catch (Throwable ignore) {}
    }

    public void invalidate() {
        cache = null;
        cacheOkAt = 0;
        failAt = 0;
        synchronized (listings) { listings.clear(); }
        resetIndex();
        cdRoot();
    }

    public String lastError() { return lastError; }

    /**
     * 网关离线（开机没开 / 网关没启动）。
     *
     * 清单里必须**明说「离网」**，不能让用户以为「网盘里的歌没了」——
     * 这两件事在用户眼里差别很大（一个去开电脑，一个以为文件被删了）。
     */
    public boolean offline() { return enabled() && lastError != null; }

    /* ==================== 全量索引（按名字直达） ==================== */

    /*
     * 为什么不是「一次递归扫完」：
     *   · 真实库 22 个根目录 / 56 个二级目录 / 2676 个文件，一次扫要 30 秒以上；
     *   · 扫到一半 App 被杀 = 全白扫，用户永远等不到「说名字能点上」；
     *   · 上限一到，后面的文件永远进不了索引，表现成「明明有这首歌，它说找不到」。
     *
     * 所以索引是**一层一层长出来的**：BFS 队列 + 已列目录集合 + 每趟落盘。
     * 列表页浏览时列过的目录也会写进同一份索引（见 list()），用户翻过的地方立刻可点播。
     */

    /** 路径 → 曲目（保持收录顺序，清单头部稳定）。 */
    private final Map<String, Track> index = new java.util.LinkedHashMap<>();
    /** 已经整层列过的目录。 */
    private final java.util.Set<String> indexed = new java.util.HashSet<>();
    /** BFS 待列队列。 */
    private final java.util.ArrayDeque<String> frontier = new java.util.ArrayDeque<>();
    private volatile boolean indexLoaded;
    private volatile boolean indexComplete;
    private volatile long lastPassAt;

    /** 一趟的结果。 */
    private static class IndexPass {
        boolean done;      // 队列空了 = 整棵树都列过
        boolean fatal;     // 根目录都列不出来 = 网关离线
        int dirs;          // 这趟列了几个目录
    }

    private void startScan() {
        scanning = true;
        new Thread(() -> {
            int passes = 0, dirs = 0;
            try {
                while (true) {
                    IndexPass p = indexPass();
                    passes++;
                    dirs += p.dirs;
                    lastPassAt = System.currentTimeMillis();
                    cache = snapshot();
                    cacheOkAt = lastPassAt;
                    if (p.done) { indexComplete = true; saveIndex(); break; }
                    saveIndex();
                    if (p.fatal) break;
                    if (passes >= 40) break;            // 兜底：一次进程最多推 40 趟
                    Thread.sleep(PASS_GAP_MS);
                }
                Log.i(TAG, "index " + index.size() + " 条 / " + indexed.size() + " 目录，"
                        + passes + " 趟列了 " + dirs + " 个目录"
                        + (indexComplete ? "（完成）" : "（后台继续）"));
            } catch (Throwable t) {
                Log.e(TAG, "index failed", t);
                saveIndex();
            } finally {
                scanning = false;
            }
        }, "ol-scan").start();
    }

    private IndexPass indexPass() {
        final IndexPass p = new IndexPass();
        final long deadline = System.currentTimeMillis() + PASS_BUDGET_MS;
        seedFrontier();
        while (true) {
            synchronized (index) {
                if (index.size() >= INDEX_MAX) { p.done = true; break; }
            }
            if (p.dirs >= PASS_MAX_DIRS) break;
            if (p.dirs > 0 && System.currentTimeMillis() > deadline) break;
            String dir = nextDir();
            if (dir == null) { p.done = true; break; }
            if (!indexDir(dir)) {
                if (dir.equals(rootPath())) { p.fatal = true; break; }   // 根都列不出来 = 离线
                Log.w(TAG, "index skip " + dir);
            }
            synchronized (index) { indexed.add(dir); }
            p.dirs++;
        }
        return p;
    }

    private void seedFrontier() {
        final String root = rootPath();
        synchronized (index) {
            if (indexed.contains(root) || frontier.contains(root)) return;
            frontier.add(root);
        }
    }

    private String nextDir() {
        synchronized (index) {
            while (!frontier.isEmpty()) {
                String d = frontier.poll();
                if (d == null || d.isEmpty()) continue;
                if (indexed.contains(d)) continue;
                return d;
            }
        }
        return null;
    }

    /**
     * 列一个目录并把它这一层收进索引。
     *
     * @return false = 这一层没列出来（网络/权限），调用方决定是跳过还是判离线
     */
    private boolean indexDir(String dir) {
        JSONObject d;
        try {
            // 0 = 不限：1677 首的那个目录必须全收，写 200 就永远只看得到前 200 个
            d = listDir(dir, 0);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            Log.w(TAG, "index list failed: " + dir + " " + t);
            return false;
        }
        lastError = null;
        failAt = 0;
        JSONArray arr = d.optJSONArray("content");
        if (arr == null) return true;
        final String root = rootPath();
        final String rel = relOf(dir, root);
        final List<Track> got = new ArrayList<>();
        int inThisDir = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            if (name.isEmpty()) continue;
            if (o.optBoolean("is_dir", false)) {
                final String full = dir + "/" + name;
                if (depthOf(full, root) <= MAX_DEPTH) {
                    synchronized (index) {
                        if (!indexed.contains(full) && !frontier.contains(full)) frontier.add(full);
                    }
                }
                continue;
            }
            // 这一层先收文件：广度优先，任何一个大目录都不会把别的目录挤掉。
            // 视频也收 —— 说片名要能直达，不然「得先进目录」这件事用户永远记不住。
            if (inThisDir >= MAX_PER_DIR) continue;
            Track t = buildTrack(dir, rel, name, o.optLong("size", 0));
            if (t == null) continue;
            got.add(t);
            inThisDir++;
        }
        synchronized (index) {
            for (Track t : got) {
                if (index.size() >= INDEX_MAX) break;
                if (!index.containsKey(t.path)) index.put(t.path, t);
            }
        }
        return true;
    }

    /** 目录相对挂载点的深度：`/quark` = 0，`/quark/a` = 1。 */
    private static int depthOf(String path, String root) {
        if (path == null || root == null || path.length() <= root.length()) return 0;
        int n = 0;
        for (int i = root.length(); i < path.length(); i++) if (path.charAt(i) == '/') n++;
        return n;
    }

    /** 目录 → 相对挂载点的前缀（结尾带 `/`，给「用目录名补标题」用）。 */
    private static String relOf(String dir, String root) {
        if (dir == null || root == null || dir.length() <= root.length()) return "";
        return dir.substring(root.length() + 1) + "/";
    }

    private List<Track> snapshot() {
        synchronized (index) { return new ArrayList<>(index.values()); }
    }

    /**
     * 把一个文件包成 Track。
     *
     * @return null = 不合格（图片/奇怪格式/太小），不占索引配额
     */
    private static Track buildTrack(String dirPath, String rel, String name, long size) {
        if (!LocalFiles.supportedName(name)) return null;
        final boolean video = LocalFiles.isVideoName(name);
        if (!video && !MediaFormats.isAudioExt(MediaFormats.extOf(name))) return null;
        if (size < MIN_SIZE) return null;
        String base = name;
        int dot = name.lastIndexOf('.');
        if (dot > 0) base = name.substring(0, dot);
        final String folder = lastFolderWithHan(rel);
        // 标题：文件名本身带汉字、且不短（≥4 字）时不再挂目录前缀。
        // 网盘的歌几乎都是「一个专辑一个目录」，前缀会和曲名重复
        // （「河北梆子mp3全剧001（11部）·河北梆子 打金枝全剧」），清单每行都要念一遍太长了；
        // 反过来「01.mp3」「track03」这种必须靠目录名才能分辨，就保留前缀。
        final String title = (base.length() >= 4 && LocalFiles.hasHan(base))
                ? base : LocalFiles.decorateTitle(base, folder);
        return new Track(dirPath + "/" + name, name, title,
                LocalFiles.decorateAliases(base, folder), size);
    }

    /* ---------- 落盘 / 读盘 ---------- */

    /**
     * 索引落盘（`files/ol_index.tsv`）。
     *
     * 格式：`R&lt;挂载点&gt;` 头 + `C`（扫完标记）+ `D&lt;目录&gt;`（已列）+ `F&lt;路径&gt;&lt;字节数&gt;`。
     * 只在每趟结束时写、先写 tmp 再改名 —— 半截文件比没有文件更糟。
     * {@code saveLock} 是写互斥：扫描线程与「浏览时顺手并入」都会写，别让两个写手把文件写花。
     */
    private final Object saveLock = new Object();

    private void saveIndex() {
        final File d = cacheDir();
        if (d == null) return;
        // 没读过盘就写 = 拿空索引盖掉旧索引，绝不能发生
        if (!indexLoaded) { loadIndex(); if (!indexLoaded) return; }
        synchronized (saveLock) {
        final File tmp = new File(d, INDEX_FILE + ".tmp");
        final File dst = new File(d, INDEX_FILE);
        java.io.BufferedWriter w = null;
        try {
            w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(tmp), "UTF-8"), 16384);
            w.write("R\t");
            w.write(rootPath());
            w.write('\n');
            if (indexComplete) w.write("C\n");
            synchronized (index) {
                for (String p : indexed) { w.write("D\t"); w.write(p); w.write('\n'); }
                for (Track t : index.values()) {
                    w.write("F\t"); w.write(t.path); w.write('\t');
                    w.write(Long.toString(t.size)); w.write('\n');
                }
            }
            w.flush();
            w.close();
            w = null;
            if (!tmp.renameTo(dst)) { dst.delete(); tmp.renameTo(dst); }
        } catch (Throwable t) {
            Log.w(TAG, "index save failed: " + t);
        } finally {
            try { if (w != null) w.close(); } catch (Throwable ignore) {}
            if (tmp.exists()) tmp.delete();
        }
        }
    }

    private void loadIndex() {
        if (indexLoaded) return;
        synchronized (index) {
            if (indexLoaded) return;
            indexLoaded = true;
            final File f = new File(cacheDir(), INDEX_FILE);
            // 旧名字（`ol_index.tsv`）会被缓存清理当缓存删掉，遇到就丢掉重建
            try { new File(cacheDir(), "ol_index.tsv").delete(); } catch (Throwable ignore) {}
            if (!f.exists()) return;
            final List<String[]> files = new ArrayList<>();
            final java.util.Set<String> dirs = new java.util.HashSet<>();
            String root = null;
            boolean complete = false;
            java.io.BufferedReader r = null;
            try {
                r = new java.io.BufferedReader(new java.io.InputStreamReader(
                        new java.io.FileInputStream(f), "UTF-8"), 16384);
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.length() < 2) {
                        if ("C".equals(line)) complete = true;
                        continue;
                    }
                    if (line.startsWith("R\t")) { root = line.substring(2); continue; }
                    if (line.startsWith("D\t")) { dirs.add(line.substring(2)); continue; }
                    if (!line.startsWith("F\t")) continue;
                    final int tab = line.lastIndexOf('\t');
                    if (tab <= 2) continue;
                    files.add(new String[]{line.substring(2, tab), line.substring(tab + 1)});
                }
            } catch (Throwable t) {
                Log.w(TAG, "index load failed: " + t);
                return;
            } finally {
                try { if (r != null) r.close(); } catch (Throwable ignore) {}
            }
            if (root == null || !root.equals(rootPath())) {
                f.delete();          // 换过网关/挂载目录，旧索引没用了
                return;
            }
            indexed.addAll(dirs);
            indexComplete = complete;
            for (String[] a : files) {
                if (index.size() >= INDEX_MAX) break;
                final long size = parseLong(a[1]);
                final Track t = rebuild(a[0], size);
                if (t != null) index.put(t.path, t);
            }
            cache = new ArrayList<>(index.values());
            cacheOkAt = System.currentTimeMillis();
            Log.i(TAG, "index loaded: " + index.size() + " 条 / " + indexed.size()
                    + " 目录 complete=" + complete);
        }
    }

    /** 从落盘的路径还原 Track（标题/别名按同样规则重算，不占磁盘）。 */
    private Track rebuild(String full, long size) {
        final int slash = full.lastIndexOf('/');
        if (slash <= 0) return null;
        final String dir = full.substring(0, slash);
        return buildTrack(dir, relOf(dir, rootPath()), full.substring(slash + 1), size);
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (Throwable ignore) { return 0; }
    }

    /** 从根重扫（保留已有条目，只把「已列」清掉 —— 新加的文件会补进来）。 */
    private void refreshIndex() {
        synchronized (index) {
            indexed.clear();
            frontier.clear();
            indexComplete = false;
            lastPassAt = 0;
        }
    }

    /** 彻底作废（换了网关/挂载目录、用户手动刷新）。 */
    private void resetIndex() {
        synchronized (index) {
            index.clear();
            indexed.clear();
            frontier.clear();
            indexComplete = false;
            cache = null;
            cacheOkAt = 0;
            lastPassAt = 0;
            indexLoaded = true;      // 别再从磁盘把旧的读回来
        }
        try {
            File f = new File(cacheDir(), INDEX_FILE);
            if (f.exists()) f.delete();
        } catch (Throwable ignore) {}
    }

    /** 索引是否已把整棵树列完（「看视频」按目录过滤要靠它判断结果可不可信）。 */
    public boolean indexComplete() { return indexComplete; }

    /**
     * 索引里**已知含视频**的目录 → 该目录里的视频条数。
     *
     * <h3>为什么需要这个</h3>
     * 网盘根层 22 个文件夹，真正放片子的只有三四个。用户问「有哪些视频」时
     * 把 22 个文件夹全列一遍，等于让他一个个进去碰运气 —— 实测就是这么被吐槽的。
     * 索引本来就收了每个文件的全路径，顺手按父目录汇总一下就知道「哪个目录出过视频」，
     * 不必额外记一份账（也不必改落盘格式，索引读回来就有）。
     *
     * <p>值是这个目录**直接放着**的视频数；更深层的视频算在它所在的子目录名下。
     */
    public Map<String, Integer> videoDirs() {
        loadIndex();
        final Map<String, Integer> m = new java.util.HashMap<>();
        synchronized (index) {
            for (Track t : index.values()) {
                if (!t.isVideo()) continue;
                final int i = t.path.lastIndexOf('/');
                if (i <= 0) continue;
                final String dir = t.path.substring(0, i);
                Integer c = m.get(dir);
                m.put(dir, c == null ? 1 : c + 1);
            }
        }
        return m;
    }

    /** 设置页用：索引规模一句话。 */
    public String indexSummary() {
        loadIndex();
        int n, dirs, videos = 0;
        boolean done;
        synchronized (index) {
            n = index.size();
            dirs = indexed.size();
            done = indexComplete;
            for (Track t : index.values()) if (t.isVideo()) videos++;
        }
        if (n == 0) return done ? "索引是空的（目录里没有可播放的文件）"
                : "索引还没建好（启动后会自动建，越用越全）";
        return "索引 " + n + " 条（音频 " + (n - videos) + " / 视频 " + videos + "）"
                + " · 已列 " + dirs + " 个文件夹" + (done ? "（已扫完）" : "（后台还在补）");
    }

    /**
     * 设置页的「目录树」：整棵树，靠索引在内存里拼，**基本不发 HTTP**。
     *
     * <h3>为什么不再一层层列</h3>
     * 老版本是「列根层 + 前 3 个文件夹各 4 项」，用户看到的是半棵树，
     * 想看「CD01 下面到底是什么」永远看不到；而且每列一层都要打一次 {@code fs/list}，
     * 真要点开全树得几分钟（1677 首那个目录单层就 4.2 秒）。
     *
     * <p>索引里本来就有每个文件的完整路径 + 已列过的目录集合，直接按 `/` 拼树就行 ——
     * 一次遍历 8000 多条，几十毫秒，还能列到第 4 层。文件太多时每个目录只摊开
     * {@code maxFiles} 个，其余写「…还有 N 个」，不然一个目录 1677 行没人看得下去。
     *
     * <p>索引还没建好（刚装、刚换网关）时退回实时列根层，至少让用户看见网关是通的。
     *
     * @param maxDepth 展开到第几层（1 = 只有根层的子项）
     * @param maxLines 最多写多少行 —— 布局里那个固定高度的框靠它兜住，别生成几万行
     */
    public String treePreview(int maxDepth, int maxLines) {
        loadIndex();
        final Node root = buildIndexTree();
        final boolean empty = root.kids.isEmpty() && root.files.isEmpty();
        if (empty) return livePreview();
        int audio = 0, video = 0;
        synchronized (index) {
            for (Track t : index.values()) { if (t.isVideo()) video++; else audio++; }
        }
        final StringBuilder body = new StringBuilder();
        final int[] lines = new int[]{0};
        dumpTree(root, "", 0, Math.max(1, maxDepth), 6, maxLines, body, lines);
        final StringBuilder sb = new StringBuilder();
        sb.append(rootPath()).append(" — ").append(root.kids.size()).append(" 个文件夹、")
                .append(audio + video).append(" 个可播放文件（音频 ").append(audio)
                .append(" / 视频 ").append(video).append("）\n")
                .append("共 ").append(lines[0]).append(" 行 · 框内可上下滑动")
                .append(lines[0] >= maxLines ? "（已截断）" : "（全展开）").append('\n');
        sb.append(body);
        sb.append('\n').append(indexSummary()).append('\n');
        if (lines[0] >= maxLines) {
            sb.append("（树太长了，只显示前 ").append(maxLines).append(" 行；每个目录最多摊开 6 个文件）");
        } else {
            sb.append("（文件多的目录最多摊开 6 个，其余按数量标注）");
        }
        return sb.toString().trim();
    }

    /** 索引的树节点（只用于设置页展示，不进播放链路）。 */
    private static class Node {
        final String path;
        final String name;
        final Map<String, Node> kids = new java.util.LinkedHashMap<>();
        final List<Track> files = new ArrayList<>();
        int audio, video;

        Node(String path, String name) { this.path = path; this.name = name; }

        int dirCount() {
            int n = kids.size();
            for (Node k : kids.values()) n += k.dirCount();
            return n;
        }
    }

    /** 把「已列目录 + 已收文件」拼成一棵树（挂载点/换网关后路径对不上就丢掉那一支）。 */
    private Node buildIndexTree() {
        final String root = rootPath();
        final Node tree = new Node(root, root);
        synchronized (index) {
            for (String d : indexed) {
                if (d == null || d.isEmpty() || d.equals(root)) continue;
                ensureNode(tree, root, d);
            }
            for (Track t : index.values()) {
                final int i = t.path.lastIndexOf('/');
                if (i <= 0) continue;
                final Node n = ensureNode(tree, root, t.path.substring(0, i));
                if (n == null) continue;
                n.files.add(t);
                if (t.isVideo()) n.video++; else n.audio++;
            }
        }
        return tree;
    }

    /** 找到（必要时创建）path 对应的节点；不在挂载点下面返回 null。 */
    private static Node ensureNode(Node tree, String root, String path) {
        if (path == null || !path.startsWith(root)) return null;
        if (path.length() == root.length()) return tree;
        String rel = path.substring(root.length() + 1);
        Node cur = tree;
        String acc = root;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty()) continue;
            acc = acc + "/" + seg;
            Node k = cur.kids.get(seg);
            if (k == null) { k = new Node(acc, seg); cur.kids.put(seg, k); }
            cur = k;
        }
        return cur;
    }

    /** 递归画树：先子文件夹（带总数），再本层文件（最多 6 个）。 */
    private void dumpTree(Node n, String prefix, int depth, int maxDepth, int maxFiles,
                          int maxLines, StringBuilder sb, int[] lines) {
        final List<Node> ks = new ArrayList<>(n.kids.values());
        for (int i = 0; i < ks.size(); i++) {
            if (lines[0] >= maxLines) return;
            final Node k = ks.get(i);
            final boolean last = (i == ks.size() - 1) && n.files.isEmpty();
            sb.append(prefix).append(last ? "└ " : "├ ").append(clip(k.name));
            final int nf = k.files.size();
            if (nf > 0) {
                sb.append("（").append(nf).append(" 个可播放");
                if (k.video > 0) sb.append("，含视频 ").append(k.video);
                sb.append("）");
            } else if (!k.kids.isEmpty()) {
                sb.append("（").append(k.dirCount()).append(" 个子文件夹）");
            } else {
                sb.append("（空）");
            }
            sb.append('\n');
            lines[0]++;
            if (depth + 1 < maxDepth) {
                dumpTree(k, prefix + (last ? "   " : "│  "), depth + 1, maxDepth, maxFiles, maxLines, sb, lines);
            } else if (!k.kids.isEmpty()) {
                if (lines[0] >= maxLines) return;
                sb.append(prefix).append(last ? "   " : "│  ")
                        .append("└ …下面还有 ").append(k.dirCount()).append(" 层没展开\n");
                lines[0]++;
            }
        }
        final int shown = Math.min(maxFiles, n.files.size());
        for (int i = 0; i < shown; i++) {
            if (lines[0] >= maxLines) return;
            final Track t = n.files.get(i);
            final boolean last = (i == shown - 1) && n.files.size() == shown;
            sb.append(prefix).append(last ? "└ " : "├ ").append(clip(t.name))
                    .append(t.isVideo() ? "（视频）" : "").append('\n');
            lines[0]++;
        }
        if (n.files.size() > shown && lines[0] < maxLines) {
            sb.append(prefix).append("└ …还有 ").append(n.files.size() - shown).append(" 个文件\n");
            lines[0]++;
        }
    }

    /** 文件名太长会把树形缩进挤乱，截一下（等宽字体下 34 个字符已经占满一行）。 */
    private static String clip(String s) {
        if (s == null) return "";
        return s.length() <= 34 ? s : s.substring(0, 33) + "…";
    }

    /**
     * 索引还没建好时的兜底：只列根层 + 前 8 个文件夹各 6 项。
     * 慢、而且只有一层，但至少能回答「网关通没通、挂载对不对」。
     */
    private String livePreview() {
        StringBuilder sb = new StringBuilder();
        Listing root;
        try {
            root = list(rootPath());
        } catch (Throwable t) {
            return "连不上网关：" + t;
        }
        if (root == null) return "连不上网关（索引也还没建好）";
        sb.append(rootPath()).append(" — ").append(root.dirs).append(" 个文件夹、")
                .append(root.playable + root.videos).append(" 个可播放文件\n")
                .append("（全量索引还在后台建，下面是实时列出来的前几项）\n");
        int shown = 0;
        for (PlayItem it : root.items) {
            if (!(it instanceof Dir)) continue;
            if (shown >= 8) break;
            Dir d = (Dir) it;
            sb.append(shown == 7 ? "└ " : "├ ").append(clip(d.name));
            if (d.count >= 0) sb.append("（").append(d.count).append(" 个）");
            sb.append('\n');
            shown++;
            try {
                Listing kids = list(d.path);
                int k = 0;
                for (PlayItem c : kids.items) {
                    if (k >= 4) break;
                    sb.append("│  ├ ").append(clip(c.displayTitle())).append('\n');
                    k++;
                }
                if (kids.size() > k) sb.append("│  └ …还有 ").append(kids.size() - k).append(" 项\n");
            } catch (Throwable t) {
                sb.append("│  （这一层没读出来：").append(t).append("）\n");
            }
        }
        if (root.dirs > shown) sb.append("└ …还有 ").append(root.dirs - shown).append(" 个文件夹\n");
        sb.append(indexSummary());
        return sb.toString().trim();
    }


    /** "京剧/经典老歌/" → "经典老歌"；不含汉字（如 "mp3/"）返回空串，不加前缀。 */
    private static String lastFolderWithHan(String rel) {
        if (rel == null || rel.isEmpty()) return "";
        String t = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
        int slash = t.lastIndexOf('/');
        String last = slash >= 0 ? t.substring(slash + 1) : t;
        return LocalFiles.hasHan(last) ? last : "";
    }

    /* ==================== 目录浏览（一层一层看） ==================== */

    /*
     * 为什么改成「一层一层看」而不是把 2676 个文件压平成一份清单：
     *   用户听完 22 个文件夹名，说「查第 3 个」或「看看邓紫棋」，进去再听那一层 ——
     *   这才是人翻网盘的方式。压平之后名单念不完（1677 首一个目录），
     *   用户想找的东西也永远排在后面。
     *
     * 但「说个歌名直接放」不能因此变慢，所以两条路并存：
     *   · 清单 = 当前目录一层（这里）
     *   · 按名点播 = 全量递归索引（tracks()/match()，后台慢慢建）
     */

    /** 当前停在哪个目录；根 = 挂载点。 */
    public String curPath() {
        String p = curPath;
        return (p == null || p.isEmpty()) ? rootPath() : p;
    }

    /** 当前目录名；在根上返回空串（念的时候说「根目录」）。 */
    public String curName() {
        String p = curPath();
        String r = rootPath();
        if (p.equals(r)) return "";
        int i = p.lastIndexOf('/');
        return i >= 0 && i + 1 < p.length() ? p.substring(i + 1) : p;
    }

    public boolean atRoot() { return curPath().equals(rootPath()); }

    /** 进一个目录。 */
    public void cd(String path) {
        String p = normPath(path);
        if (p.isEmpty()) return;
        curPath = p.equals(rootPath()) ? null : p;
        curPage = 0;
        Log.i(TAG, "cd → " + curPath());
    }

    /** 回上一级；已在根上返回 false。 */
    public boolean up() {
        String p = curPath();
        if (p.equals(rootPath())) return false;
        int i = p.lastIndexOf('/');
        String parent = i > 0 ? p.substring(0, i) : rootPath();
        // 不能退到挂载点外面去（/quark 的上一级是 /，那是网关自己的目录，不属于音乐库）
        if (parent.length() < rootPath().length()) parent = rootPath();
        cd(parent);
        return true;
    }

    public void cdRoot() {
        curPath = null;
        curPage = 0;
    }

    public int page() { return curPage; }

    /** 当前目录一共几页（没加载出来时算 1 页）。 */
    public int pageCount() {
        Listing l = cachedListing(curPath());
        int n = l == null ? 0 : l.size();
        return n <= PAGE_SIZE ? 1 : (n + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    /** 翻页；返回 false 表示已经是最后一页。 */
    public boolean nextPage() {
        if (curPage + 1 >= pageCount()) return false;
        curPage++;
        return true;
    }

    public boolean prevPage() {
        if (curPage <= 0) return false;
        curPage--;
        return true;
    }

    private static String normPath(String p) {
        if (p == null) return "";
        String s = p.trim();
        if (s.isEmpty()) return "";
        if (!s.startsWith("/")) s = "/" + s;
        while (s.endsWith("/") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private Listing cachedListing(String path) {
        synchronized (listings) {
            Listing l = listings.get(path);
            if (l == null) return null;
            if (System.currentTimeMillis() - l.at > OK_TTL) { listings.remove(path); return null; }
            return l;
        }
    }

    /**
     * 当前目录的一层内容。**绝不同步列**：有缓存就返回，没有就起后台线程并返回 null，
     * 调用方看到 null 就写「正在读取…」（列一层 11ms~4.2s，实测 1677 个文件那个要 4.2 秒）。
     */
    public Listing listing() {
        if (!enabled()) return null;
        final String p = curPath();
        Listing l = cachedListing(p);
        if (l != null) return l;
        if (p.equals(loadingPath)) return null;
        if (System.currentTimeMillis() - failAt < FAIL_TTL) return null;
        loadingPath = p;
        new Thread(() -> {
            try {
                list(p);
            } catch (Throwable e) {
                lastError = String.valueOf(e);
                failAt = System.currentTimeMillis();
                Log.e(TAG, "list dir failed: " + p, e);
            } finally {
                if (p.equals(loadingPath)) loadingPath = null;
            }
        }, "ol-dir").start();
        return null;
    }

    /** 同步列一层（要发 HTTP，绝不在主线程调）。 */
    public Listing list(String path) throws IOException {
        // 先读盘：这一层要并进索引，不先 load 的话 saveIndex 会把完整索引覆盖成「刚浏览的几条」
        // —— 实测踩过：进设置页点了「看目录树」，索引从 8970 条掉到 3 条。
        loadIndex();
        final String p = normPath(path);
        Listing c = cachedListing(p);
        if (c != null) return c;
        JSONObject d = listDir(p, 0);            // 0 = 不限：分页浏览要全量
        List<PlayItem> items = new ArrayList<>();
        List<PlayItem> files = new ArrayList<>();
        int dirs = 0, playable = 0, videos = 0, blocked = 0;
        JSONArray arr = d.optJSONArray("content");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                if (name.isEmpty()) continue;
                if (o.optBoolean("is_dir", false)) {
                    items.add(new Dir(p + "/" + name, name));
                    dirs++;
                    continue;
                }
                if (!LocalFiles.supportedName(name)) { blocked++; continue; }
                long size = o.optLong("size", 0);
                String ext = MediaFormats.extOf(name);
                // 音频 → 本机播放器；视频 → VideoActivity 出画面。
                // 曲谱图片 / 封面 / 奇怪格式仍然只报个数：点了也没东西可放。
                final boolean isVideo = LocalFiles.isVideoName(name);
                if (!isVideo && !MediaFormats.isAudioExt(ext)) { blocked++; continue; }
                if (size < MIN_SIZE) { blocked++; continue; }
                String base = name;
                int dot = name.lastIndexOf('.');
                if (dot > 0) base = name.substring(0, dot);
                String folder = curName();
                final String title = (base.length() >= 4 && LocalFiles.hasHan(base))
                        ? base : LocalFiles.decorateTitle(base, LocalFiles.hasHan(folder) ? folder : "");
                files.add(new Track(p + "/" + name, name, title,
                        LocalFiles.decorateAliases(base, LocalFiles.hasHan(folder) ? folder : ""), size));
                if (isVideo) videos++; else playable++;
            }
        }
        // 顺手探一下每个子文件夹里有多少个（只在这一层，限时限量）。
        // 必须在放进缓存**之前**做完：清单是照着这些条目念的，
        // 念完再补数字就晚了（用户已经听到「邓紫棋」三个字、开始猜里面有什么了）。
        if (dirs > 0) probeDirs(items);
        items.addAll(files);                     // 文件夹在前，文件在后
        // 用户翻到哪一层，就把那一层并进全量索引：
        //   · 翻过的地方立刻能「说个名字直接放」（不用等后台 BFS 排到它）；
        //   · 这一层是 per_page=0 列全的，标记成「已列」还能让后台少跑一次。
        boolean touched = false;
        synchronized (index) {
            for (PlayItem it : files) {
                Track t = (Track) it;
                if (index.size() >= INDEX_MAX) break;
                if (!index.containsKey(t.path)) { index.put(t.path, t); touched = true; }
            }
            if (indexed.add(p)) touched = true;
        }
        if (touched && !scanning) saveIndex();
        Listing l = new Listing(p, items, dirs, playable, videos, blocked);
        synchronized (listings) { listings.put(p, l); }
        lastError = null;
        failAt = 0;
        Log.i(TAG, "list " + p + " dirs=" + dirs + " audio=" + playable
                + " video=" + videos + " blocked=" + blocked);
        return l;
    }

    /**
     * 探一层里每个文件夹「大概有多少个」：前台只等 {@link #PROBE_MS}，剩下的丢后台。
     *
     * <p>网关不返回子目录的条目数，只能每个目录再打一次 {@code fs/list}；
     * 用 {@code per_page=1} 只拿 {@code total}，不搬内容 —— 一个 1677 首的目录
     * 拿全量要 4.2 秒，而拿 total 是网关现成的字段。
     *
     * <p>三个线程并发探（网关是 Go 写的，扛得住）：串行 22 个目录要十几秒，
     * 3 并发之后 1.5 秒能拿到大半。没赶上的留给后台线程，
     * {@link Dir#count} 是 volatile、Dir 又挂在缓存的 Listing 上，
     * 补完的数字下次进这一层就在了。
     * 出错就留 -1（清单里不显示数字），绝不因为一个目录失败拖垮整层。
     */
    private void probeDirs(List<PlayItem> items) {
        final List<Dir> todo = new ArrayList<>();
        for (PlayItem it : items) {
            if (it instanceof Dir && ((Dir) it).count < 0) todo.add((Dir) it);
        }
        if (todo.isEmpty()) return;
        final int n = Math.min(PROBE_MAX_DIRS, todo.size());
        final long t0 = System.currentTimeMillis();
        final long deadline = t0 + PROBE_BG_MS;
        final java.util.concurrent.atomic.AtomicInteger cur =
                new java.util.concurrent.atomic.AtomicInteger();
        Runnable worker = () -> {
            while (true) {
                int i = cur.getAndIncrement();
                if (i >= n) return;
                if (System.currentTimeMillis() > deadline) return;
                Dir d = todo.get(i);
                try {
                    JSONObject o = listDir(d.path, 1);
                    int total = o.optInt("total", -1);
                    if (total < 0) {
                        JSONArray a = o.optJSONArray("content");
                        total = a == null ? 0 : a.length();
                    }
                    d.count = total;
                } catch (Throwable e) {
                    Log.w(TAG, "probe " + d.path + " failed: " + e);
                }
            }
        };
        Thread[] ts = new Thread[PROBE_THREADS];
        for (int k = 0; k < ts.length; k++) {
            ts[k] = new Thread(worker, "ol-probe-" + k);
            ts[k].setDaemon(true);
            ts[k].start();
        }
        final long until = System.currentTimeMillis() + PROBE_MS;
        for (Thread t : ts) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) break;
            try {
                t.join(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int got = 0;
        for (Dir d : todo) if (d.count >= 0) got++;
        Log.i(TAG, "probe " + got + "/" + n + " dirs in "
                + (System.currentTimeMillis() - t0) + "ms");
    }

    /**
     * 在当前这一层里按名字找文件夹（「查一下邓紫棋」「看看经典老歌」）。
     *
     * 只认文件夹，不认文件 —— 找文件是 {@link #match} 的事，那条路会全量递归找。
     */
    public Dir findDir(String spoken) {
        String s = LocalFiles.norm(spoken);
        if (s.length() < 2) return null;
        Listing l = cachedListing(curPath());
        if (l == null) return null;
        Dir best = null;
        int bestScore = 0;
        for (PlayItem it : l.items) {
            if (!(it instanceof Dir)) continue;
            Dir dir = (Dir) it;
            // 别名也要过 norm：norm 会统一大小写、去标点、繁转简。
            // 不做这一步，说「打开CD01」对不上「国粤翻唱经典(30CD).CD01.flac」——
            // 网盘目录名里英文数字一大堆，大小写不统一就永远进不去（实测踩到）。
            int sc = LocalFiles.aliasScore(s, LocalFiles.norm(dir.name));
            if (sc > bestScore) { bestScore = sc; best = dir; }
        }
        return bestScore >= 6 ? best : null;
    }

    /** 当前目录里的第 idx 个条目（1 起，跨页连续）；越界返回 null。 */
    public PlayItem itemAt(int idx) {
        Listing l = cachedListing(curPath());
        if (l == null || idx < 1 || idx > l.size()) return null;
        return l.items.get(idx - 1);
    }


    /* ==================== 播放 ==================== */

    /**
     * 取一个文件的可播放直链。
     *
     * 网关给的是 `/p/...?sign=...` 代理流（夸克驱动强制 native_proxy）；直链带签名，
     * 大约 10 分钟失效，所以在 Track 上缓存 8 分钟，够一次播放用，又不至于拿过期的。
     */
    public String rawUrl(Track t) {
        if (t == null) return null;
        if (t.raw != null && System.currentTimeMillis() - t.rawAt < 8 * 60 * 1000L) return t.raw;
        try {
            JSONObject b = new JSONObject();
            b.put("path", t.path);
            JSONObject o = api("/api/fs/get", b);
            JSONObject d = o.optJSONObject("data");
            String r = d == null ? "" : d.optString("raw_url", "");
            if (r.isEmpty()) r = d == null ? "" : d.optString("sign", "");
            if (r.isEmpty()) {
                Log.w(TAG, "raw_url 空: " + t.path + " msg=" + o.optString("message"));
                return null;
            }
            r = fixHost(r);
            t.raw = r;
            t.rawAt = System.currentTimeMillis();
            return r;
        } catch (Throwable e) {
            Log.e(TAG, "rawUrl failed " + t.path, e);
            return null;
        }
    }

    /** 直链里的 host 必须是设备能访问的地址；万一网关给回 127.0.0.1，改成网关地址。 */
    private String fixHost(String url) {
        try {
            URI b = URI.create(base());
            String want = b.getHost() + (b.getPort() > 0 ? ":" + b.getPort() : "");
            URI u = URI.create(url);
            String have = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            if (want.equals(have)) return url;
            int schemeEnd = url.indexOf("://");
            if (schemeEnd < 0) return url;
            int pathStart = url.indexOf('/', schemeEnd + 3);
            if (pathStart < 0) pathStart = url.length();
            String fixed = url.substring(0, schemeEnd + 3) + want + url.substring(pathStart);
            Log.i(TAG, "直链 host 修正 " + have + " → " + want);
            return fixed;
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * 用「路径 + 文件名 + 字节数」临时造一个 Track。
     *
     * <p>视频页（{@link VideoActivity}）拿到的是 Intent 里的三个字符串，手里没有 Track 对象；
     * 让它自己去解析目录、猜标题只会多一套不一样的规则。这里复用同一套命名逻辑。
     */
    public Track trackFor(String path, String name, long size) {
        String p = normPath(path);
        String n = (name == null || name.isEmpty())
                ? p.substring(p.lastIndexOf('/') + 1) : name;
        String base = n;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return new Track(p, n, base, LocalFiles.aliasesFromBase(base), size);
    }

    /**
     * 边下边播：把网盘文件包成 MediaDataSource。
     *
     * 播放器读的是**本地文件**（后台顺序下载），只有跳到大后面没下过的位置才走 Range 精确读 ——
     * 与共享盘同一套策略，因为病根是同一个：小请求 + 网络往返 = 供给不足 = mp3 一断一断。
     *
     * 必须后台调用（要发 HTTP）。
     */
    public MediaDataSource openDataSource(Track t) {
        if (t == null) return null;
        try {
            String raw = rawUrl(t);
            if (raw == null) return null;
            File dir = cacheDir();
            if (dir == null) return null;
            long size = t.size;
            // 超过缓存上限的（长片）照样能播，只是下完不留存
            final boolean keep = size > 0 && size <= CACHE_MAX_BYTES;
            if (keep) {
                long free = RemoteLibrary.freeBytes(dir);
                if (free >= 0 && free < size + RemoteLibrary.FREE_RESERVE) {
                    RemoteLibrary.pruneCache(dir, Math.max(0, free - RemoteLibrary.FREE_RESERVE), null);
                    RemoteLibrary.cleanParts(dir);
                }
            }
            File part = new File(dir, "part_" + System.currentTimeMillis() + "_" + t.name);
            OlStreamSource s = new OlStreamSource(raw, size, part, t.name, keep);
            s.start();
            Log.i(TAG, "openDataSource " + t.path + " size=" + size + " keep=" + keep);
            return s;
        } catch (Throwable e) {
            Log.e(TAG, "openDataSource failed: " + t.path, e);
            return null;
        }
    }

    /** 整包下载兜底（边下边播失败时用）。返回本地路径，失败 null。 */
    public String fetchToCache(Track t, File cacheDir) {
        File out = null;
        try {
            String raw = rawUrl(t);
            if (raw == null) return null;
            long size = Math.max(t.size, 0);
            if (size > CACHE_MAX_BYTES) {
                Log.w(TAG, "文件 " + RemoteLibrary.human(size) + " 超过缓存上限，不做整包缓存");
                return null;
            }
            RemoteLibrary.cleanParts(cacheDir);
            RemoteLibrary.pruneCache(cacheDir, CACHE_MAX_BYTES - size, null);
            long need = size + RemoteLibrary.FREE_RESERVE;
            long free = RemoteLibrary.freeBytes(cacheDir);
            if (free >= 0 && free < need) {
                Log.w(TAG, "空间不足：需要 " + RemoteLibrary.human(need) + "，剩 " + RemoteLibrary.human(free));
                return null;
            }
            final long stamp = System.currentTimeMillis();
            File tmp = new File(cacheDir, "part_" + stamp + "_" + t.name);
            out = new File(cacheDir, "ol_" + stamp + "_" + t.name);
            long written = 0;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(raw).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Accept-Encoding", "identity");
                try (InputStream in = c.getInputStream();
                     FileOutputStream os = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[256 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        os.write(buf, 0, n);
                        written += n;
                    }
                }
            } finally {
                if (c != null) { try { c.disconnect(); } catch (Throwable ignore) {} }
            }
            File finalFile = tmp;
            if (tmp.renameTo(out)) finalFile = out;
            else Log.w(TAG, "改名失败，保留临时文件 " + tmp.getName());
            out = null;
            Log.i(TAG, "fetch ok " + finalFile.getName() + " " + RemoteLibrary.human(written));
            return finalFile.getAbsolutePath();
        } catch (Throwable e) {
            if (out != null) { try { if (out.exists()) out.delete(); } catch (Throwable ignore) {} }
            Log.e(TAG, "fetch failed", e);
            return null;
        }
    }

    /** 找整包下过的缓存（文件名 + 字节数两道校验，与共享盘一致）。 */
    public File findCache(Track t) {
        if (t == null) return null;
        File dir = cacheDir();
        if (dir == null) return null;
        final String suffix = "_" + t.name;
        File[] fs = dir.listFiles((d, n) -> n.startsWith("ol_") && n.endsWith(suffix));
        if (fs == null || fs.length == 0) return null;
        File best = null;
        for (File f : fs) {
            if (t.size > 0 && f.length() != t.size) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        if (best != null) Log.i(TAG, "缓存命中 " + best.getName());
        return best;
    }

    /* ==================== 匹配 ==================== */

    public List<Track> head(int limit) {
        List<Track> all = tracks();
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    public Track match(String spoken) { return match(spoken, MediaKind.ANY); }

    /**
     * 打分匹配（与 LocalFiles/RemoteLibrary 同一套规则：具体名字要赢过 2 字目录名）。
     *
     * <p>**先看当前这一层**，再比全量索引：用户刚在清单里看到的文件，名字最可能就在这一层，
     * 而且这一层已经在内存里。全量索引有 3000 条上限、又是深度优先扫的，
     * 深处的 30 张 CD 根本进不来 —— 实测「放孤单背影」因此匹配到一个 6 分的同音碎片，
     * 放出来完全是另一首歌。
     */
    public Track match(String spoken, int prefer) {
        String s = LocalFiles.norm(spoken);
        if (s.length() < 2) return null;
        Track here = null;
        int hereScore = 0;
        for (Track t : currentTracks()) {
            int sc = scoreOf(t, s);
            if (sc > hereScore) { hereScore = sc; here = t; }
        }
        Track global = null;
        int gScore = 0;
        List<Track> all = tracks();
        if (all != null && !all.isEmpty()) {
            if (prefer != MediaKind.ANY) {
                List<Track> pool = new ArrayList<>();
                for (Track t : all) {
                    if (prefer == MediaKind.VIDEO ? t.isVideo() : !t.isVideo()) pool.add(t);
                }
                if (!pool.isEmpty()) {
                    Track t = matchIn(pool, s);
                    gScore = t == null ? 0 : scoreOf(t, s);
                    global = t;
                }
            }
            if (gScore < 6) {
                Track t = matchIn(all, s);
                int sc = t == null ? 0 : scoreOf(t, s);
                if (sc > gScore) { global = t; gScore = sc; }
            }
        }
        // 分数一样时用当前层的：用户刚看到的那份更贴近他要找的
        if (gScore >= 6 && gScore > hereScore) return global;
        return hereScore >= 6 ? here : null;
    }

    /** 当前层里能播的条目（不含文件夹）。 */
    private List<Track> currentTracks() {
        Listing l = cachedListing(curPath());
        if (l == null) return EMPTY;
        List<Track> out = new ArrayList<>();
        for (PlayItem it : l.items) if (it instanceof Track) out.add((Track) it);
        return out;
    }

    private static int scoreOf(Track t, String s) {
        int sc = 0;
        for (String a : t.aliases) {
            int v = LocalFiles.aliasScore(s, a);
            if (v > sc) sc = v;
        }
        return sc;
    }

    private Track matchIn(List<Track> all, String s) {
        Track best = null;
        int bestScore = 0;
        for (Track t : all) {
            int sc = 0;
            for (String a : t.aliases) {
                int v = LocalFiles.aliasScore(s, a);
                if (v > sc) sc = v;
            }
            if (sc > bestScore) { bestScore = sc; best = t; }
        }
        return bestScore >= 6 ? best : null;
    }
}
