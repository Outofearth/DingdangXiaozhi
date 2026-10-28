package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDataSource;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * 远程音乐库（SMB / Windows 文件共享）。
 *
 * 思路：电脑上的音乐/视频文件夹用 Windows 自带的文件共享（右键 → 共享）共享出去，
 * 小智这边用 jcifs-ng 像读「网络硬盘」一样列歌、取歌。
 *
 * 播远程文件有两条路：
 *   1) 边下边播（P3）：{@link #openDataSource} 把 SMB 文件包成 {@link MediaDataSource}，
 *      MediaPlayer 读到哪里我们就 seek 到哪里 —— 不再等整首下载完，几十 MB 的歌秒开。
 *   2) 下到本地缓存再播（老路）：{@link #fetchToCache}，直读失败时兜底，
 *      也适合网络抖动大、边播边卡的情况。缓存带 LRU 清理和剩余空间校验。
 *
 * PC 端不必安装或运行任何额外程序：Windows 文件共享是系统自带能力。
 */
public class RemoteLibrary {
    private static final String TAG = "RemoteLibrary";
    private static final long OK_TTL = 60 * 1000L;       // 清单缓存 60 秒
    private static final long FAIL_TTL = 15 * 1000L;     // 失败后 15 秒内不再试
    /** 递归列共享目录的最大层数（SMB 每次 listFiles 都是一次网络往返，别无限钻）。 */
    private static final int MAX_DEPTH = 4;
    /** 最多收多少个文件，防止超大的共享把清单撑爆。 */
    private static final int MAX_TRACKS = 2000;
    /**
     * 每次扫描最多给几个「视频容器」补读文件头。
     *
     * 为什么要补：共享盘上 `.mp4` 只是外壳，里面可能只有一条音轨（B 站/下载站给的
     * 「白话文讲解」这类纯音频 mp4 很常见）。清单以前只能按扩展名猜成视频，用户点进去
     * 是黑屏；更糟的是播一次之后播放路径读了文件头、把它记成音频，清单下次又变成少一个 ——
     * 同一个文件两次问出两个结果，看着就是「文件信息不对」。
     */
    private static final int MAX_PROBE = 8;
    /** 补读文件头的总时间预算：超了就放弃剩下的，宁可按扩展名猜，也不能把清单卡住。 */
    private static final long PROBE_BUDGET = 3000L;
    /** 下载缓存总上限（超出按最久未用清理）。 */
    public static final long CACHE_MAX_BYTES = 600L * 1024 * 1024;
    /** 下载前要求的最低剩余空间（留出余量，别把系统盘塞死）。 */
    public static final long FREE_RESERVE = 150L * 1024 * 1024;

    /** 远程曲目（source 是 smb:// 直链，可边下边播，也可先拷到本地）。 */
    public static class Track implements PlayItem {
        public final String smbUrl;   // smb://host/share/file.mp3
        public final String name;     // 文件名（含扩展名）
        public final String title;
        public final String[] aliases;
        public final long size;       // 字节数（扫描时顺带拿到，用于空间校验）

        Track(String smbUrl, String name, String title, String[] aliases, long size) {
            this.smbUrl = smbUrl;
            this.name = name;
            this.title = title;
            this.aliases = aliases;
            this.size = size;
        }

        @Override public String source() { return smbUrl; }
        @Override public String displayTitle() { return title; }
        @Override public boolean isVideo() { return LocalFiles.isVideoName(name); }
    }

    private final Context ctx;
    private List<Track> cache;
    private String cacheKey;
    private long cacheOkAt = 0;
    private long failAt = 0;

    public RemoteLibrary(Context c) { ctx = c.getApplicationContext(); }

    private SharedPreferences sp() { return ctx.getSharedPreferences("xz", Context.MODE_PRIVATE); }

    public boolean enabled() {
        return sp().getBoolean("smbEnabled", false);
    }

    private String host()  { return sp().getString("smbHost", "").trim(); }
    private String share() { return sp().getString("smbShare", "").trim(); }
    private String user()  { return sp().getString("smbUser", "").trim(); }
    private String pass()  { return sp().getString("smbPass", ""); }

    private String key() { return host() + "|" + share() + "|" + user(); }

    /** 带凭据的 jcifs 上下文（每次新建，避免不同账号串味）。 */
    private CIFSContext ctxWithCreds() throws CIFSException {
        // 支持「域名\账户」「账户@域名」写法；拆分出 domain / user
        String raw = user();
        String domain = "";
        String u = raw;
        int bs = u.indexOf('\\');
        if (bs >= 0) { domain = u.substring(0, bs); u = u.substring(bs + 1); }
        else {
            int at = u.indexOf('@');
            if (at >= 0) { domain = u.substring(at + 1); u = u.substring(0, at); }
        }
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.enableSMB2", "true");
        p.setProperty("jcifs.smb.client.enableSMB1", "false");
        p.setProperty("jcifs.smb.client.connTimeout", "5000");
        p.setProperty("jcifs.smb.client.soTimeout", "8000");
        // 空账号（访客/匿名）：让 jcifs 走 guest 登录分支
        if (u.isEmpty()) p.setProperty("jcifs.smb.client.guest", "true");
        CIFSContext c = new BaseContext(new PropertyConfiguration(p));
        // 4 参构造：(CIFSContext, domain, user, password)
        NtlmPasswordAuthentication auth =
                new NtlmPasswordAuthentication(c, domain, u, pass());
        Log.i(TAG, "ctxWithCreds: domain='" + domain + "' user='" + u + "' guest=" + u.isEmpty());
        return c.withCredentials(auth);
    }

    public synchronized List<Track> tracks() {
        if (!enabled() || host().isEmpty() || share().isEmpty()) return new ArrayList<>();
        long now = System.currentTimeMillis();
        String k = key();
        if (cache != null && k.equals(cacheKey) && now - cacheOkAt < OK_TTL) return cache;
        if (cache != null && k.equals(cacheKey) && now - failAt < FAIL_TTL) return cache;
        List<Track> r = scan();
        if (r != null) { cache = r; cacheKey = k; cacheOkAt = now; failAt = 0; return cache; }
        failAt = now;
        return cache != null && k.equals(cacheKey) ? cache : new ArrayList<>();
    }

    public synchronized void invalidate() { cache = null; cacheOkAt = 0; failAt = 0; }

    /** 仅用于「测试连接」：返回错误文案（null = 成功）。 */
    public synchronized String testConnection() {
        List<Track> r = scan();
        return r == null ? "连不上（检查主机名/IP、共享名、账号密码，且电脑与音箱在同一局域网）" : null;
    }

    /**
     * 列共享目录里的媒体文件。
     *
     * **必须递归**：用户把歌按「mp3/佛经/《六祖坛经》.mp3」这样分文件夹是最常见的整理方式，
     * 早先这里有一句 `if (f.isDirectory()) continue;` 只扫根目录一层，结果子文件夹里的
     * 文件在清单里根本不出现（语音问「有佛经吗」也只会回没有）。
     *
     * 深度上限 {@link #MAX_DEPTH} + 数量上限 {@link #MAX_TRACKS}：SMB 每次 listFiles
     * 都是一次网络往返，不能无限往下钻。
     */
    private List<Track> scan() {
        try {
            CIFSContext c = ctxWithCreds();
            String url = "smb://" + host() + "/" + share() + "/";
            SmbFile dir = new SmbFile(url, c);
            if (!dir.isDirectory()) return new ArrayList<>();
            List<Track> out = new ArrayList<>();
            collectSmb(dir, "", out, 0);
            probeKinds(out);
            Log.i(TAG, "scan ok: smb://" + host() + "/" + share() + "/  files=" + out.size());
            return out;
        } catch (Throwable t) {
            Log.e(TAG, "scan failed", t);
            return null;
        }
    }

    /**
     * 递归收集一个共享子目录。
     *
     * @param rel 相对共享根的子目录路径，形如 "" 或 "佛经/" 或 "佛经/经文/"
     */
    private void collectSmb(SmbFile dir, String rel, List<Track> out, int depth) {
        if (depth > MAX_DEPTH || out.size() >= MAX_TRACKS) return;
        SmbFile[] files;
        try {
            files = dir.listFiles();
        } catch (Throwable t) {
            Log.w(TAG, "list failed: " + rel, t);
            return;
        }
        if (files == null) return;
        for (SmbFile f : files) {
            if (out.size() >= MAX_TRACKS) return;
            String fn;
            try { fn = f.getName(); } catch (Throwable t) { continue; }
            boolean isDir;
            try {
                isDir = f.isDirectory();
            } catch (Throwable t) {
                isDir = fn.endsWith("/");       // 拿不到属性时按名字兜底
            }
            if (isDir) {
                String name = fn.endsWith("/") ? fn.substring(0, fn.length() - 1) : fn;
                if (!name.isEmpty()) collectSmb(f, rel + name + "/", out, depth + 1);
                continue;
            }
            if (!LocalFiles.supportedName(fn)) continue;
            long len = 0;
            try { len = f.length(); } catch (Throwable ignore) {}
            if (len < 100 * 1024) continue;                // 太小多半是碎片
            String base = fn;
            int dot = fn.lastIndexOf('.');
            if (dot > 0) base = fn.substring(0, dot);
            String folder = lastFolderWithHan(rel);
            String title = LocalFiles.decorateTitle(base, folder);
            String[] aliases = LocalFiles.decorateAliases(base, folder);
            String path;
            try {
                path = f.getCanonicalPath();
            } catch (Throwable t) {
                Log.w(TAG, "canonical path failed: " + rel + fn, t);
                continue;
            }
            out.add(new Track(path, fn, title, aliases, len));
        }
    }

    /** "佛经/经文/" → "经文"；不含汉字（如 "mp3/"）返回空串，不加前缀。 */
    private static String lastFolderWithHan(String rel) {
        if (rel == null || rel.isEmpty()) return "";
        String t = rel.endsWith("/") ? rel.substring(0, rel.length() - 1) : rel;
        int slash = t.lastIndexOf('/');
        String last = slash >= 0 ? t.substring(slash + 1) : t;
        return LocalFiles.hasHan(last) ? last : "";
    }

    /**
     * 给「视频容器」补一次文件头判定，结果写进 {@link MediaKind} 的偏好表。
     *
     * 共享盘上的 mp4 不一定是视频：纯音频 mp4 很常见。清单以前只能按扩展名猜，
     * 猜成视频后用户点进去是黑屏；而且播放路径会读文件头并记住真实类型，
     * 于是「同一条目两次问出两种结果」—— 这次扫完就定下来，两边用同一个答案。
     *
     * 只有在「判过就不再探」（SP 有记录直接跳过）、数量上限 {@link #MAX_PROBE}
     * 和总预算 {@link #PROBE_BUDGET} 内才做，一次性成本，之后的扫描全是本地读表。
     */
    private void probeKinds(List<Track> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        // 网络探测绝不上 UI 线程：主线程调 tracks() 拿队列时宁可按扩展名猜，也不能卡住界面
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return;
        final long deadline = System.currentTimeMillis() + PROBE_BUDGET;
        int done = 0;
        for (Track t : tracks) {
            if (done >= MAX_PROBE || System.currentTimeMillis() > deadline) break;
            if (!MediaFormats.isVideoExt(MediaFormats.extOf(t.name))) continue;   // mp3/flac 不可能是视频
            if (MediaKind.remembered(ctx, t.name) != MediaKind.ANY) continue;     // 判过了，直接用
            MediaDataSource ds = openDataSource(t.smbUrl);
            if (ds == null) continue;
            int k = MediaKind.probeSource(ds);          // 内部会 close(ds)
            done++;
            if (k != MediaKind.ANY) {
                MediaKind.remember(ctx, t.name, k);
                Log.i(TAG, "probe kind " + t.name + " -> " + MediaKind.name(k));
            }
        }
        if (done > 0) Log.i(TAG, "probeKinds: 本次读了 " + done + " 个文件头");
    }

    /* ==================== P3：边下边播 ==================== */

    /**
     * 把 SMB 上的文件包成 MediaDataSource，交给 MediaPlayer 边读边播。
     *
     * 为什么可行：MediaPlayer 对 http/file 源本来就是随机读取（尤其 mp4 要先读 moov），
     * 只要我们能按偏移读字节，它就不关心数据是从哪来的。
     * jcifs 的 SmbRandomAccessFile.seek + read 正好对应「按偏移读」，
     * 但它不是线程安全的（MediaPlayer 可能同时从解码线程和主线程读），所以整个
     * readAt 加锁串行化。
     *
     * 注意：必须从后台线程调用 —— jcifs 建上下文时会做 DNS（InetAddress.getLocalHost），
     * 在主线程上会直接抛 NetworkOnMainThreadException（实测踩过）。
     *
     * @param smbUrl smb://host/share/file.mp3
     * @return 直接可用的数据源；打不开返回 null
     */
    public MediaDataSource openDataSource(String smbUrl) {
        try {
            final CIFSContext c = ctxWithCreds();
            final SmbFile f = new SmbFile(smbUrl, c);
            final long size = f.length();
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getCacheDir();
            // 边下边播要落盘：先确认装得下，别为了一首歌把设备写爆；
            // 超过缓存上限（大视频）也不落盘，宁可走直读 —— 视频管线缓冲深，本来就扛得住
            if (size > 0 && size <= CACHE_MAX_BYTES && freeBytes(dir) > size + FREE_RESERVE) {
                cleanParts(dir);                    // 顺手清掉上次中断留下的半截文件
                final int cut = smbUrl.lastIndexOf('/');
                final String nm = cut >= 0 ? smbUrl.substring(cut + 1) : "media";
                final File part = new File(dir, "part_" + System.currentTimeMillis() + "_" + nm);
                final SmbStreamSource s = new SmbStreamSource(c, smbUrl, size, part);
                s.start();
                Log.i(TAG, "openDataSource(边下边播) " + smbUrl + " size=" + size);
                return s;
            }
            Log.w(TAG, "openDataSource: 文件 " + human(size) + " 太大或空间不够，走直读");
            return openRawDataSource(smbUrl);
        } catch (Throwable t) {
            Log.e(TAG, "openDataSource failed: " + smbUrl, t);
            return null;
        }
    }

    /**
     * 不带预读的裸数据源 —— **只给压测探针用**，用来对比「预读到底值不值」。
     *
     * 播放路径一律走 {@link #openDataSource}：裸读每次都要网络往返，
     * 正是「mp3 一断一断」的老病根，别再拿它播歌。
     */
    public MediaDataSource openRawDataSource(String smbUrl) {
        try {
            CIFSContext c = ctxWithCreds();
            final SmbRandomAccessFile raf = new SmbRandomAccessFile(smbUrl, "r", 0x1 | 0x2, c);
            final long size = raf.length();
            Log.i(TAG, "openRawDataSource " + smbUrl + " size=" + size);
            return new MediaDataSource() {
                @Override public int readAt(long position, byte[] buffer, int offset, int length)
                        throws IOException {
                    synchronized (raf) {
                        try {
                            raf.seek(position);
                            int total = 0;
                            while (total < length) {
                                int n = raf.read(buffer, offset + total, length - total);
                                if (n <= 0) break;
                                total += n;
                            }
                            return total == 0 ? -1 : total;   // -1 = 读到末尾
                        } catch (IOException e) {
                            throw e;
                        } catch (Throwable t) {
                            throw new IOException("smb read failed: " + t);
                        }
                    }
                }

                @Override public long getSize() { return size; }

                @Override                 public void close() throws IOException {
                    synchronized (raf) {
                        try { raf.close(); } catch (Throwable t) { throw new IOException("" + t); }
                    }
                }
            };
        } catch (Throwable t) {
            Log.e(TAG, "openRawDataSource failed: " + smbUrl, t);
            return null;
        }
    }

    /* ==================== 下载兜底：空间校验 + LRU ==================== */

    /** 目录剩余字节数。 */
    public static long freeBytes(File dir) {
        try {
            StatFs fs = new StatFs(dir.getAbsolutePath());
            return fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * LRU 清理：把缓存文件（`smb_*` 共享盘 / `ol_*` 网盘）按「最后修改时间」从旧到新删，
     * 直到总大小 ≤ maxBytes（或删无可删）。
     *
     * 两种前缀合起来算**一个**预算：设备只有 2.4G 可用空间，各给 600MB 就是 1.2G，
     * 会把播放时要用到的磁盘缓存挤没。
     *
     * @param keep 这一首不要删（正在播的那份）
     */
    public static void pruneCache(File dir, long maxBytes, File keep) {
        if (dir == null) return;
        File[] fs = dir.listFiles((d, name) -> name.startsWith("smb_") || name.startsWith("ol_"));
        if (fs == null || fs.length == 0) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= maxBytes) return;
        Arrays.sort(fs, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());   // 旧的排前面
            }
        });
        for (File f : fs) {
            if (total <= maxBytes) break;
            if (keep != null && f.getAbsolutePath().equals(keep.getAbsolutePath())) continue;
            long n = f.length();
            if (f.delete()) {
                total -= n;
                Log.i(TAG, "prune " + f.getName() + " (" + n + "B)");
            }
        }
    }

    /**
     * 从 SMB 把一首歌拷到本地缓存目录，返回本地路径（交给 FileMusicPlayer 播放）。
     * 失败返回 null。
     *
     * 下载前先做两件事：清掉旧缓存、确认剩余空间够（不然写一半 ENOSPC 更糟）。
     */
    /**
     * 从 SMB 把一个文件拷到本地缓存目录，返回本地路径。失败返回 null。
     *
     * 下载前先做三件事（T4 补了前两件）：
     *  1) 拿不到真实大小时先问共享盘要（{@code SmbFile.length()}）——
     *     否则「2.4G 的片子往 2.7G 存储里塞」这种必然失败的事只能等到写爆
     *     ENOSPC 才发现，还会留下半个垃圾文件；
     *  2) 清掉旧缓存、确认「文件大小 + 余量」装得下；
     *  3) 下载途中每约 4MB 复查一次剩余空间，写满就中止并删掉半成品。
     */
    public String fetchToCache(Track t, File cacheDir) {
        File out = null;
        try {
            CIFSContext c = ctxWithCreds();
            SmbFile src = new SmbFile(t.smbUrl, c);

            long size = Math.max(t.size, 0);
            if (size == 0) {
                try { size = Math.max(src.length(), 0); } catch (Throwable ignore) {}
            }

            // T4：比缓存上限还大的文件一律不整包缓存。
            // 理由不是「装不下」而是「装了也白装」：pruneCache 的上限就是
            // CACHE_MAX_BYTES，2.5G 下来会立刻被 LRU 清掉，中间还把整台机器的
            // 2.7G 存储挤到只剩几十兆 —— 系统自己先挂。长片只走直读。
            if (size > CACHE_MAX_BYTES) {
                Log.w(TAG, "文件 " + human(size) + " 超过缓存上限 " + human(CACHE_MAX_BYTES)
                        + "，不做整包缓存（长片只走直读）");
                return null;
            }

            // 上次被强杀留下的临时文件，顺手清掉
            cleanParts(cacheDir);

            // 只留最近用的几个，按总大小做 LRU；同时给这次的下载腾地方
            pruneCache(cacheDir, CACHE_MAX_BYTES - size, null);

            long need = size + FREE_RESERVE;
            long free = freeBytes(cacheDir);
            if (free >= 0 && free < need) {
                pruneCache(cacheDir, Math.max(0, free - FREE_RESERVE), null);
                free = freeBytes(cacheDir);
                if (free < need) {
                    Log.w(TAG, "空间不足：需要 " + human(need) + "（文件 " + human(size)
                            + " + 余量 " + human(FREE_RESERVE) + "），剩余 " + human(free));
                    return null;
                }
            }
            Log.i(TAG, "fetch start " + t.name + " size=" + human(size) + " free=" + human(free));

            // 先写 part_ 临时文件，成了再改名成 smb_：
            // 下载中途被强杀/掉线时留下的是 part_，不会被扫进媒体清单当成一个能播的条目。
            final long stamp = System.currentTimeMillis();
            File tmp = new File(cacheDir, "part_" + stamp + "_" + t.name);
            out = new File(cacheDir, "smb_" + stamp + "_" + t.name);
            long written = 0;
            long nextCheck = 0;
            try (InputStream in = src.getInputStream();
                 FileOutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[256 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    written += n;
                    // 每约 4MB 复查一次：大小算错 / 盘上别的进程在写，都能在这里止损
                    if (written >= nextCheck) {
                        nextCheck = written + 4L * 1024 * 1024;
                        long f = freeBytes(cacheDir);
                        if (f >= 0 && f < FREE_RESERVE / 2) {
                            throw new java.io.IOException("设备空间写满，已中止下载");
                        }
                    }
                }
            }
            File finalFile = tmp;
            if (tmp.renameTo(out)) {
                finalFile = out;
            } else {
                Log.w(TAG, "改名失败，直接保留临时文件 " + tmp.getName());
            }
            out = null;     // 成功：别在 catch 里删掉成品
            Log.i(TAG, "fetch ok " + finalFile.getName() + " " + human(written));
            return finalFile.getAbsolutePath();
        } catch (Throwable e) {
            // 半成品必须删掉：留着既占地方，又可能被当成完整缓存再播（黑屏）
            if (out != null) {
                try { if (out.exists() && !out.delete()) Log.w(TAG, "半成品删不掉 " + out); }
                catch (Throwable ignore) {}
            }
            Log.e(TAG, "fetch failed", e);
            return null;
        }
    }

    /**
     * 找「已经整包下过」的同名缓存文件，命中就完全不用再走网络。
     *
     * 边下边播下完之后会把 part_ 转成 smb_ 留着（见 SmbStreamSource.close），
     * 但下次点同一首时如果还从 SMB 重下一遍，那缓存就白留了 —— 这里负责把它用起来。
     *
     * 校验用「文件名 + 字节数」两道：同名文件在不同子目录可能重名，size 能挡住绝大多数误命中；
     * 电脑上内容改了但大小没变的情况挡不住，和原来的 fetchToCache 一个水平，可以接受。
     */
    public File findCache(Track t) {
        if (t == null) return null;
        return findCache(t.name, t.size);
    }

    /**
     * 按「文件名 + 字节数」找整包缓存（视频页也要走这条路）。
     *
     * <p>视频以前没有命中入口：看完一集 2GB 的片子，下次点开又从头连 SMB 拉 ——
     * 缓存明明还在。把入口统一成这个名字，音频/视频两边都调它。
     */
    public File findCache(String name, long size) {
        if (name == null || name.isEmpty()) return null;
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getCacheDir();
        if (dir == null) return null;
        final String suffix = "_" + name;
        File[] fs = dir.listFiles((d, n) -> n.startsWith("smb_") && n.endsWith(suffix));
        if (fs == null || fs.length == 0) return null;
        File best = null;
        for (File f : fs) {
            if (size > 0 && f.length() != size) continue;   // 大小对不上，不是同一份
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        if (best != null) Log.i(TAG, "缓存命中 " + best.getName() + " " + human(best.length()));
        return best;
    }

    /**
     * 清掉下载中途被打断留下的 part_ 临时文件（它们不会被扫进媒体清单）。
     *
     * **3 分钟内改过的不动**：那是「正在播的那一首」的下载线程还在写。
     * 早先无条件删，结果换歌时把上一首的临时文件删了 —— 播放还继续（inode 还在），
     * 但下完之后 rename 找不到源文件，下好的缓存直接丢掉。
     */
    public static void cleanParts(File dir) {
        if (dir == null) return;
        final long cutoff = System.currentTimeMillis() - 3 * 60 * 1000L;
        try {
            File[] fs = dir.listFiles((d, name) -> name.startsWith("part_"));
            if (fs == null) return;
            for (File f : fs) {
                if (f.lastModified() > cutoff) continue;
                if (f.delete()) Log.i(TAG, "clean part " + f.getName());
            }
        } catch (Throwable ignore) {}
    }

    /** 字节数 → 人能看懂的大小（日志/提示用）。 */
    public static String human(long b) {
        if (b < 0) return "?";
        if (b < 1024) return b + "B";
        if (b < 1024L * 1024) return String.format("%.1fK", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format("%.1fM", b / 1024.0 / 1024);
        return String.format("%.2fG", b / 1024.0 / 1024 / 1024);
    }

    /** 曲目清单（最多 limit 首，给语音播报 / MCP 工具回候选用）。 */
    public List<Track> head(int limit) {
        List<Track> all = tracks();
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** 从一句话里认出用户想听哪首（最长命中）。 */
    public Track match(String spoken) {
        return match(spoken, MediaKind.ANY);
    }

    /** 带倾向的匹配：说「看视频」时先只在视频里找。 */
    public Track match(String spoken, int prefer) {
        String s = LocalFiles.norm(spoken);
        if (s.length() < 2) return null;
        List<Track> all = tracks();
        if (prefer != MediaKind.ANY) {
            List<Track> pool = new ArrayList<>();
            for (Track t : all) {
                if (prefer == MediaKind.VIDEO ? t.isVideo() : !t.isVideo()) pool.add(t);
            }
            if (!pool.isEmpty()) {
                Track t = matchIn(pool, s);
                if (t != null) return t;
            }
        }
        return matchIn(all, s);
    }

    /**
     * 打分匹配（与 {@link LocalFiles#aliasScore} 同一套规则）。
     * 以前这里也是「命中即返回」，于是 2 字的文件夹别名「佛经」会把
     * 「播放佛经准提咒」抢成《六祖坛经》—— 现在按分数排队，具体名字赢。
     */
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
