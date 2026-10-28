package com.altc.xiaozhi;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

import jcifs.CIFSContext;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbRandomAccessFile;

/**
 * 共享盘「边下边播」数据源：后台顺序下载到本地临时文件，播放器读本地文件。
 *
 * <h3>为什么不用内存预读</h3>
 * 试过两个内存窗口版本，都不行，别再走回头路：
 * <ul>
 *   <li><b>同步大块预读</b>：跨过窗口尾巴时 MediaPlayer 那次 read 要同步等一次填充，
 *       实测 1.4 秒，照样 underrun —— 从「频繁小卡」变成「偶尔大卡」。</li>
 *   <li><b>后台预读 + 精确同步读兜底</b>：这首 mp4（内嵌音轨）的解析器会反复回头读
 *       116KB~263KB 那一段（封面/索引区），窗口被反复重置；后台线程又一直抢着
 *       SMB 句柄，同步读排队到 2~3 秒，最后 MediaPlayer 直接报 error(1, -1009)。</li>
 * </ul>
 *
 * <h3>现在这条路为什么可靠</h3>
 * 播放器读的是**本地文件**，任意位置、任意顺序都只看本地磁盘，
 * 不可能拿错数据、也不可能被网络卡住 —— 这从根上消掉了 underrun。
 * <ul>
 *   <li>顺序播放：等下载追上即可（下载 0.67MB/s ≫ 音频 40KB/s，永远领先）。</li>
 *   <li>回头读已下过的部分（那个反复读 116KB~263KB 的 mp4 正好属此类）：本地直接命中。</li>
 *   <li>跳到大后面没下载的地方（视频 seek、mp3 拖进度）：走**另一条独立 SMB 连接**精确读，
 *       既不阻塞下载、也不会读错位置。</li>
 * </ul>
 * 真机对比（同一首 6.4MB 内嵌音频的 mp4，播 55 秒）：
 * <pre>
 *   裸读（原实现）  : AudioTrack underrun 9 次，writtenAudio 一直落后 audioSink 最多 0.99 秒
 *   本地边下边播    : underrun 0 次
 * </pre>
 *
 * 线程：下载线程只碰 localLock；随机读连接单独用 randomLock；两者互不干扰。
 */
class SmbStreamSource extends MediaDataSource {
    private static final String TAG = "SmbStream";

    /** 下载缓冲。 */
    private static final int DOWN_BUF = 64 * 1024;
    /**
     * 「紧跟着下载进度」的判定窗口。
     *
     * 播放位置在这个范围内就当顺序播放，等下载追上来；
     * 超出才是真跳转（视频 seek 到很后面），改走独立连接的精确读。
     * 没有这个界限，顺序播放会白白多开一路 SMB 读，把带宽吃成两半。
     */
    private static final long NEAR_DOWNLOAD = 4L * 1024 * 1024;

    private final CIFSContext ctx;
    private final String url;
    private final long size;
    private final String name;
    private final File part;

    private RandomAccessFile local;
    private SmbRandomAccessFile random;
    private final Object localLock = new Object();
    private final Object randomLock = new Object();

    /** 已经安全落到本地文件的字节数（写完才更新，读线程只认这个值）。 */
    private volatile long downloaded;
    private volatile boolean complete, failed, closed;
    private volatile String errMsg;
    /** 下完后改成的正式缓存文件（改名后 part 路径就失效了，close 时不能再删）。 */
    private volatile File finalFile;
    private Thread down;

    /** 统计：真机核对。 */
    private long randReads, randBytes, maxRandMs;

    SmbStreamSource(CIFSContext ctx, String url, long size, File part) throws IOException {
        this.ctx = ctx;
        this.url = url;
        this.size = size;
        int cut = url.lastIndexOf('/');
        this.name = cut >= 0 ? url.substring(cut + 1) : url;
        this.part = part;
        this.local = new RandomAccessFile(part, "rw");
        try {
            local.setLength(0);
        } catch (Throwable ignore) {}
    }

    /** 启动后台下载。调用方应在构造后立即启动。 */
    void start() {
        down = new Thread(this::downloadLoop, "smb-download");
        down.setDaemon(true);
        down.start();
    }

    @Override
    public int readAt(long position, byte[] out, int off, int len) throws IOException {
        if (position < 0 || position >= size || len <= 0) return -1;
        if (position + len > size) len = (int) (size - position);

        if (failed) throw new IOException("smb download failed: " + errMsg);

        final long have = downloaded;
        if (position < have) {
            // 已经下到本地了：直接读本地（能给多少给多少）
            final int can = (int) Math.min(len, have - position);
            return readLocal(position, out, off, can);
        }

        // 完全没下到：顺序播放就等下载追上，真跳转才走精确读
        if (position < have + NEAR_DOWNLOAD) {
            synchronized (localLock) {
                final long deadline = System.currentTimeMillis() + 30000L;
                while (!closed && !failed && downloaded <= position
                        && System.currentTimeMillis() < deadline) {
                    try {
                        localLock.wait(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }
            }
            if (failed) throw new IOException("smb download failed: " + errMsg);
            final long now = downloaded;
            if (position >= now) return -1;              // 下载完/中断
            final int can = (int) Math.min(len, now - position);
            return readLocal(position, out, off, can);
        }

        return readRemote(position, out, off, len);
    }

    /** 本地文件读：微秒级，永远不阻塞。 */
    private int readLocal(long position, byte[] out, int off, int len) throws IOException {
        if (len <= 0) return -1;
        synchronized (localLock) {
            if (local == null) return -1;
            try {
                local.seek(position);
                int n = 0;
                while (n < len) {
                    final int k = local.read(out, off + n, len - n);
                    if (k <= 0) break;
                    n += k;
                }
                return n > 0 ? n : -1;
            } catch (Throwable t) {
                throw new IOException("local read failed: " + t);
            }
        }
    }

    /** 跳到还没下载的位置：用另一条独立连接精确读，不动下载那条。 */
    private int readRemote(long position, byte[] out, int off, int len) throws IOException {
        final long t0 = System.nanoTime();
        synchronized (randomLock) {
            try {
                if (random == null) {
                    random = new SmbRandomAccessFile(url, "r", 0x1 | 0x2, ctx);
                    Log.i(TAG, "random channel open " + name);
                }
                random.seek(position);
                int n = 0;
                while (n < len) {
                    final int k = random.read(out, off + n, len - n);
                    if (k <= 0) break;
                    n += k;
                }
                if (n <= 0) return -1;
                randReads++;
                randBytes += n;
                final long ms = (System.nanoTime() - t0) / 1000000L;
                if (ms > maxRandMs) maxRandMs = ms;
                if (randReads <= 5 || ms > 800) {
                    Log.i(TAG, "random read " + name + " @" + position + " " + n + "B in " + ms
                            + "ms | 累计 " + randReads + " 次 " + (randBytes / 1024) + "KB 峰 "
                            + maxRandMs + "ms");
                }
                return n;
            } catch (Throwable t) {
                throw new IOException("smb random read failed: " + t);
            }
        }
    }

    /** 后台顺序下载。绝不做随机读，保证带宽全给「往前走」。 */
    private void downloadLoop() {
        InputStream in = null;
        long pos = 0;
        try {
            in = new SmbFile(url, ctx).openInputStream();
            final byte[] b = new byte[DOWN_BUF];
            while (!closed) {
                final int n = in.read(b, 0, b.length);
                if (n <= 0) break;
                synchronized (localLock) {
                    if (local == null) break;
                    local.seek(pos);
                    local.write(b, 0, n);
                    pos += n;
                    downloaded = pos;            // 落盘后才对外可见
                    localLock.notifyAll();
                }
                if (pos >= size) break;
            }
            complete = pos >= size;
            Log.i(TAG, "download done " + name + " " + pos + "/" + size + "B complete=" + complete);
            if (complete) promote();          // 下完立刻转正，别等 close
        } catch (Throwable t) {
            if (!closed) {
                failed = true;
                errMsg = String.valueOf(t);
                Log.e(TAG, "download failed " + name, t);
            }
        } finally {
            if (in != null) { try { in.close(); } catch (Throwable ignore) {} }
            synchronized (localLock) { localLock.notifyAll(); }
        }
    }

    /**
     * 把下完的 part_ 改名为正式的 smb_ 缓存。
     *
     * 必须「下完就改名」，不能等 close：
     * 下一首起播时 openDataSource 会先 cleanParts() 清场，那时上一首可能还在播、
     * 文件还没 close —— 留在 part_ 里就会被当成垃圾删掉，下完的 19MB 白下（实测踩过）。
     * 改名不影响已打开的句柄（Linux 上 inode 不变），播放照常继续。
     */
    private void promote() {
        if (finalFile != null) return;
        final File dir = part.getParentFile();
        if (dir == null) return;
        final File keep = new File(dir, "smb_" + System.currentTimeMillis() + "_" + name);
        if (part.renameTo(keep)) {
            finalFile = keep;
            Log.i(TAG, "缓存已保留 " + keep.getName());
            RemoteLibrary.pruneCache(dir, RemoteLibrary.CACHE_MAX_BYTES, keep);
        } else {
            Log.w(TAG, "改名失败，留在临时文件 " + part.getName());
        }
    }

    @Override
    public long getSize() {
        return size;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (localLock) { localLock.notifyAll(); }
        if (down != null) down.interrupt();
        synchronized (localLock) {
            if (local != null) {
                try { local.close(); } catch (Throwable ignore) {}
                local = null;
            }
        }
        synchronized (randomLock) {
            if (random != null) {
                try { random.close(); } catch (Throwable ignore) {}
                random = null;
            }
        }
        // 整包下完的已经转成正式缓存（promote），不能删；半截文件留着既占地方
        // 又可能被当成完整缓存，直接清掉
        if (finalFile == null) {
            try { part.delete(); } catch (Throwable ignore) {}
        }
    }
}
