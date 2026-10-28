package com.altc.xiaozhi;

import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import java.io.File;

/**
 * 播放缓存的统计与清理（设置页「存储」卡片用；命令行 {@code tools/cache.sh} 是同一套语义）。
 *
 * <p>只有三类东西会真的占地方：
 * <ul>
 *   <li>{@code smb_<时间戳>_<名>} 共享盘文件的整包缓存 —— 边下边播下完就转正到这里
 *       （{@link RemoteLibrary#findCache} 下次同名同大小直接播本地，不再走网络）；
 *       直读失败后「整首下载」的兜底也放这里。LRU 上限 600MB；</li>
 *   <li>{@code part_<时间戳>_<名>} 边下边播**正在下**的临时文件，或下载被打断留下的残片。
 *       正在播的那份也在里面，所以「清理残片」要跳过刚写过的那几个
 *       （见 {@link #delete(File, String, long)}），否则一点清理就把当前播放打断；</li>
 *   <li>{@code music/*.pcm} 云端唱过的歌整首存下来的 PCM（{@link MusicLibrary}，无上限）。</li>
 * </ul>
 *
 * <p>本机文件播放不落盘（原地读），所以这里没有本机播放的缓存项 —— 用户看不到它是正常的。
 */
public class StorageCtl {
    private static final String TAG = "StorageCtl";

    /** 下载中断的残片前缀。 */
    public static final String PREFIX_PART = "part_";
    /** 整包下载兜底缓存的前缀（共享盘）。 */
    public static final String PREFIX_SMB = "smb_";
    /** 整包下载兜底缓存的前缀（网络盘）。两者合起来算一份 600MB 预算。 */
    public static final String PREFIX_OL = "ol_";

    private final Context ctx;

    public StorageCtl(Context c) { ctx = c.getApplicationContext(); }

    /** 缓存都在 app 私有外部目录；没有外部存储才退到内部 cache。 */
    public File dir() {
        File f = ctx.getExternalFilesDir(null);
        return f != null ? f : ctx.getCacheDir();
    }

    /** 某前缀的文件统计，返回 {@code {个数, 字节}}。 */
    public long[] stat(String prefix) { return stat(dir(), prefix); }

    public static long[] stat(File dir, String prefix) {
        long n = 0, b = 0;
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs != null) {
            for (File f : fs) {
                if (!f.isFile()) continue;
                if (prefix != null && !f.getName().startsWith(prefix)) continue;
                n++;
                try { b += f.length(); } catch (Throwable ignore) {}
            }
        }
        return new long[]{n, b};
    }

    /** 删掉某前缀的文件，返回 {@code {真正删掉的个数, 释放字节}}（删不掉的重新数一遍就能看出来）。 */
    public long[] delete(String prefix) { return delete(dir(), prefix, 0); }

    /**
     * 同上，但跳过最近 {@code minAgeMs} 毫秒内还在写的文件。
     *
     * 「清理下载残片」必须带上这个：边下边播的文件就在 {@code part_} 里，
     * 而且正在被播放器读 —— 无脑全删等于把当前正在播的这首拆掉。
     */
    public long[] delete(String prefix, long minAgeMs) { return delete(dir(), prefix, minAgeMs); }

    public static long[] delete(File dir, String prefix) { return delete(dir, prefix, 0); }

    public static long[] delete(File dir, String prefix, long minAgeMs) {
        if (dir == null) return new long[]{0, 0};
        long[] before = stat(dir, prefix);
        File[] fs = dir.listFiles();
        if (fs != null) {
            final long now = System.currentTimeMillis();
            for (File f : fs) {
                if (!f.isFile()) continue;
                if (prefix != null && !f.getName().startsWith(prefix)) continue;
                if (minAgeMs > 0 && now - f.lastModified() < minAgeMs) {
                    Log.i(TAG, "跳过刚写过的 " + f.getName());
                    continue;
                }
                try {
                    if (!f.delete()) Log.w(TAG, "删不掉 " + f.getName());
                } catch (Throwable ignore) {}
            }
        }
        long[] after = stat(dir, prefix);
        return new long[]{before[0] - after[0], before[1] - after[1]};
    }

    /**
     * 缓存明细，**按大小从大到小**排（设置页「缓存明细」用）。
     *
     * <p>为什么要这个：{@link #stat} 只给个总数（「18 个 · 420MB」），
     * 用户根本不知道是谁占的 —— 想留两首常听的、清掉那几个几百兆的视频，
     * 只能整个清空。按大小排一眼就能看出「就是这三部片子」。
     *
     * @param prefixes 只收这些前缀；空 / null = 全收
     */
    public static java.util.List<File> list(File dir, String... prefixes) {
        final java.util.List<File> out = new java.util.ArrayList<>();
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.isFile()) continue;
            if (prefixes != null && prefixes.length > 0) {
                boolean hit = false;
                for (String p : prefixes) {
                    if (p != null && f.getName().startsWith(p)) { hit = true; break; }
                }
                if (!hit) continue;
            }
            out.add(f);
        }
        out.sort((a, b) -> Long.compare(b.length(), a.length()));
        return out;
    }

    /** 外部存储 {@code {可用字节, 总字节}}；拿不到返回 {@code {-1, -1}}。 */
    public static long[] space() {
        try {
            File root = Environment.getExternalStorageDirectory();
            if (root == null) return new long[]{-1, -1};
            StatFs sf = new StatFs(root.getPath());
            return new long[]{sf.getAvailableBytes(), sf.getTotalBytes()};
        } catch (Throwable t) {
            Log.w(TAG, "space() failed", t);
            return new long[]{-1, -1};
        }
    }

    /** 字节 → 人话（共用 {@link RemoteLibrary#human}，日志和界面别出现两种写法）。 */
    public static String human(long b) { return RemoteLibrary.human(b); }
}
