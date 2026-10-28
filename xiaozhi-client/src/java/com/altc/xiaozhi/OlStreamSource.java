package com.altc.xiaozhi;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 网络盘「边下边播」数据源：后台顺序下载到本地临时文件，播放器读本地文件。
 *
 * <p>为什么和共享盘（{@link SmbStreamSource}）用同一套结构：病根是同一个 ——
 * 播放器按小块读，每一次读都是「一趟网络往返 + 一个往返延迟」，吞吐 = 块大小 ÷ 延迟。
 * 夸克经网关转一手，小请求只有几百 KB/s（实测音箱侧 2MB/5s ≈ 400KB/s），
 * 而 mp3 解码要 40KB/s、视频要 300KB/s~1MB/s —— 边读边卡就是必然。
 * 改成「下载线程只管往前推、播放器只读本地磁盘」之后，读操作里再没有网络。
 *
 * <p>三种读法：
 * <ul>
 *   <li>读已下载的部分 → 本地文件，微秒级。</li>
 *   <li>紧跟着下载进度（顺序播放） → 等下载追上（wait 120ms 轮询）。</li>
 *   <li>跳到大后面没下载的地方（视频 seek） → 独立 HTTP 连接 + {@code Range} 精确读，
 *       不阻塞下载，也不会读错位置。</li>
 * </ul>
 *
 * <p>线程：下载线程只碰 localLock；Range 读单独用 randomLock；两者互不干扰。
 */
class OlStreamSource extends MediaDataSource {
    private static final String TAG = "OlStream";

    private static final int DOWN_BUF = 64 * 1024;
    /**
     * 「紧跟着下载进度」的判定窗口。播放位置在这个范围内当顺序播放、等下载追上；
     * 超出才是真跳转（视频 seek），改走 Range 精确读。
     */
    private static final long NEAR_DOWNLOAD = 8L * 1024 * 1024;
    /**
     * 随机读的内存预取窗口。
     *
     * <p>mp4 的 moov（索引）通常压在文件尾部，MediaPlayer 一打开就从尾往前连续读
     * 几十个 2KB 小块。每块一次 HTTP 往返（实测 0.5~2 秒）→ 加起来二十秒还在
     * 「正在缓冲」，画面出不来。改成一次拉 512KB 进内存、后面的小块全命中，
     * 打开一个 69MB 的 mp4 只要 2~3 次请求。
     *
     * <p>512KB 是权衡：512KB 在 400KB/s~2MB/s 的网盘上约 0.3~1.3 秒，
     * 太大会把「先出声」拖慢，太小又要多打几次请求。
     */
    private static final int RAND_PREFETCH = 512 * 1024;

    private final String url;
    private final String name;
    private final File part;
    /** 下完是否留作缓存（超过缓存上限的长片不留）。 */
    private final boolean keepCache;

    private volatile long size;

    private RandomAccessFile local;
    private final Object localLock = new Object();
    private final Object randomLock = new Object();

    /** 已经落盘的字节数（写完才更新，读线程只认这个值）。 */
    private volatile long downloaded;
    private volatile boolean complete, failed, closed;
    private volatile String errMsg;
    /** 下完后改成的正式缓存文件（改名后 part 路径失效，close 时不能删）。 */
    private volatile File finalFile;

    private volatile HttpURLConnection down;
    private volatile InputStream downIn;
    private Thread thread;

    /** 统计：真机核对用。 */
    private long randReads, randBytes, maxRandMs;
    /** 随机读预取缓冲（见 {@link #RAND_PREFETCH}）：只在 randomLock 里动。 */
    private byte[] preBuf = new byte[0];
    private long preStart = -1;
    private int preLen = 0;

    OlStreamSource(String url, long size, File part, String name, boolean keepCache) throws IOException {
        this.url = url;
        this.size = size;
        this.part = part;
        this.name = name == null || name.isEmpty() ? "media" : name;
        this.keepCache = keepCache;
        this.local = new RandomAccessFile(part, "rw");
        try { local.setLength(0); } catch (Throwable ignore) {}
    }

    void start() {
        thread = new Thread(this::downloadLoop, "ol-download");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public int readAt(long position, byte[] out, int off, int len) throws IOException {
        if (position < 0 || len <= 0) return -1;
        final long sz = size;
        if (sz > 0 && position >= sz) return -1;
        if (sz > 0 && position + len > sz) len = (int) (sz - position);

        if (failed) throw new IOException("网盘下载失败: " + errMsg);

        final long have = downloaded;
        if (position < have) {
            final int can = (int) Math.min(len, have - position);
            return readLocal(position, out, off, can);
        }
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
            if (failed) throw new IOException("网盘下载失败: " + errMsg);
            final long now = downloaded;
            if (position >= now) return -1;
            final int can = (int) Math.min(len, now - position);
            return readLocal(position, out, off, can);
        }
        return readRemote(position, out, off, len);
    }

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
                throw new IOException("本地读失败: " + t);
            }
        }
    }

    /** 跳到大后面没下载的位置：独立连接 + Range 精确读（带内存预取，见 {@link #RAND_PREFETCH}）。 */
    private int readRemote(long position, byte[] out, int off, int len) throws IOException {
        if (len <= 0) return -1;
        final long t0 = System.nanoTime();
        synchronized (randomLock) {
            // 命中预取窗口：纯内存拷贝，零网络。mp4 打开时那几十个 2KB 小块全靠这里。
            if (preLen > 0 && position >= preStart && position + len <= preStart + preLen) {
                System.arraycopy(preBuf, (int) (position - preStart), out, off, len);
                return len;
            }
            // 预取窗口以 position 为中心向**前**后各扩一半：mp4 的 moov 索引是
            // 「先读文件尾 8 个字节拿到 moov 长度，再往前读整个 moov」——
            // 读取位置是**递减**的，只向后扩等于白拉。
            final int want = Math.max(len, RAND_PREFETCH);
            long from = position - (want - len) / 2;
            if (from < 0) from = 0;
            long to = from + want;
            if (size > 0 && to > size) {
                to = size;
                from = Math.max(0, to - want);
            }
            final int span = (int) Math.max(len, to - from);
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(20000);
                c.setRequestProperty("Range", "bytes=" + from + "-" + (from + span - 1));
                // 别让任何一层压缩：Range 的字节偏移必须对应文件原始字节
                c.setRequestProperty("Accept-Encoding", "identity");
                final int code = c.getResponseCode();
                if (code != 200 && code != 206) throw new IOException("HTTP " + code);
                final byte[] buf = new byte[span];
                int n = 0;
                try (InputStream in = c.getInputStream()) {
                    long skip = code == 200 ? from : 0;   // 服务端忽略 Range 时自己跳
                    long done = 0;
                    while (done < skip) {
                        final long k = in.skip(skip - done);
                        if (k <= 0) break;
                        done += k;
                    }
                    while (n < span) {
                        final int k = in.read(buf, n, span - n);
                        if (k <= 0) break;
                        n += k;
                    }
                }
                if (n <= 0) return -1;
                preBuf = buf;
                preStart = from;
                preLen = n;
                final int at = (int) (position - from);
                if (at < 0 || at >= n) return -1;
                final int give = Math.min(len, n - at);
                System.arraycopy(buf, at, out, off, give);
                randReads++;
                randBytes += n;
                final long ms = (System.nanoTime() - t0) / 1000000L;
                if (ms > maxRandMs) maxRandMs = ms;
                if (randReads <= 8 || ms > 800) {
                    Log.i(TAG, "range read " + name + " @" + position + " 取 " + (n / 1024)
                            + "KB 给 " + give + "B in " + ms + "ms"
                            + " | 累计 " + randReads + " 次 " + (randBytes / 1024) + "KB 峰 " + maxRandMs + "ms");
                }
                return give;
            } catch (Throwable t) {
                throw new IOException("网盘 Range 读失败: " + t);
            } finally {
                if (c != null) { try { c.disconnect(); } catch (Throwable ignore) {} }
            }
        }
    }

    /** 后台顺序下载。绝不做随机读，带宽全给「往前走」。 */
    private void downloadLoop() {
        long pos = 0;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            down = c;
            c.setConnectTimeout(6000);
            c.setReadTimeout(20000);
            // 顺带把真实大小问到（openDataSource 时可能只有网关列表给的大约值）
            c.setRequestProperty("Accept-Encoding", "identity");
            final int code = c.getResponseCode();
            if (code != 200 && code != 206) throw new IOException("HTTP " + code);
            long cl = c.getContentLength();
            if (cl > 0 && size <= 0) size = cl;

            final InputStream in = c.getInputStream();
            downIn = in;
            final byte[] b = new byte[DOWN_BUF];
            while (!closed) {
                final int n = in.read(b, 0, b.length);
                if (n <= 0) break;
                synchronized (localLock) {
                    if (local == null) break;
                    local.seek(pos);
                    local.write(b, 0, n);
                    pos += n;
                    downloaded = pos;
                    localLock.notifyAll();
                }
                if (size > 0 && pos >= size) break;
            }
            complete = size > 0 ? pos >= size : true;
            Log.i(TAG, "download done " + name + " " + pos + "/" + size + "B complete=" + complete);
            if (complete) promote();
        } catch (Throwable t) {
            if (!closed) {
                failed = true;
                errMsg = String.valueOf(t);
                Log.e(TAG, "download failed " + name, t);
            }
        } finally {
            try { if (downIn != null) downIn.close(); } catch (Throwable ignore) {}
            try { if (down != null) down.disconnect(); } catch (Throwable ignore) {}
            synchronized (localLock) { localLock.notifyAll(); }
        }
    }

    /**
     * 下完立刻改名成正式缓存 {@code ol_*}，别等 close：
     * 下一首起播时会 cleanParts() 清场，那时这一首可能还在播、文件还没 close，
     * 留在 part_ 里就会被当成垃圾删掉（共享盘那边实测踩过，19MB 白下）。
     * 改名不影响已打开的句柄。
     */
    private void promote() {
        if (finalFile != null) return;
        if (!keepCache) {
            Log.i(TAG, "长片不进缓存，播完即清: " + name);
            return;
        }
        final File dir = part.getParentFile();
        if (dir == null) return;
        final File keep = new File(dir, "ol_" + System.currentTimeMillis() + "_" + name);
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
        return size > 0 ? size : -1;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (localLock) { localLock.notifyAll(); }
        try { if (downIn != null) downIn.close(); } catch (Throwable ignore) {}
        try { if (down != null) down.disconnect(); } catch (Throwable ignore) {}
        if (thread != null) thread.interrupt();
        synchronized (localLock) {
            if (local != null) {
                try { local.close(); } catch (Throwable ignore) {}
                local = null;
            }
        }
        if (finalFile == null) {
            try { part.delete(); } catch (Throwable ignore) {}
        }
        if (randReads > 0) {
            Log.i(TAG, "close " + name + " | Range 读 " + randReads + " 次 " + (randBytes / 1024)
                    + "KB 峰 " + maxRandMs + "ms");
        }
    }
}
