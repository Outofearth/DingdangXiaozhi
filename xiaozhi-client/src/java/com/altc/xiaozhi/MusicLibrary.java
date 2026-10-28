package com.altc.xiaozhi;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * 本地曲库：把服务端「逐句 TTS」生成出来的整首歌存成 PCM 文件。
 *
 * 为什么要这么做：服务端的音乐是边生成边下发的 TTS 流，用户一说话服务端就会
 * 停掉这路流（VAD 打断，客户端无论用 manual 还是 realtime 都拦不住）。
 * 唯一能让「播放中调音量 / 说话」不影响音乐的办法，就是让音乐根本不走服务端 ——
 * 听过的歌直接从本地文件播放，服务端怎么断都跟它无关。
 */
public class MusicLibrary {
    private static final String TAG = "MusicLibrary";
    /** 小于这个长度视为无效缓存（约 1 秒 24kHz）。 */
    private static final long MIN_BYTES = 24000 * 2;

    private final File dir;

    public MusicLibrary(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        dir = new File(base, "music");
        if (!dir.exists()) dir.mkdirs();
    }

    private static String key(String title) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < title.length(); i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else sb.append('_');
        }
        String s = sb.toString();
        return s.isEmpty() ? "track" : s;
    }

    private File pcmFile(String title) { return new File(dir, key(title) + ".pcm"); }
    private File metaFile(String title) { return new File(dir, key(title) + ".meta"); }

    public boolean has(String title) {
        if (title == null || title.isEmpty()) return false;
        File f = pcmFile(title);
        return f.exists() && f.length() > MIN_BYTES;
    }

    /** 已缓存的歌名列表（用来把「播放XX」匹配到本地曲目）。 */
    public List<String> titles() {
        List<String> out = new ArrayList<>();
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".meta")) continue;
            try {
                Scanner sc = new Scanner(f, "UTF-8");
                if (sc.hasNextLine()) {
                    String t = sc.nextLine().trim();
                    if (!t.isEmpty() && has(t)) out.add(t);
                }
                sc.close();
            } catch (Throwable ignore) {}
        }
        return out;
    }

    /** 保存整首 PCM（16bit 小端单声道）。 */
    public boolean save(String title, short[] pcm, int len, int sampleRate) {
        if (title == null || title.isEmpty() || pcm == null || len <= 0) return false;
        try {
            FileOutputStream fo = new FileOutputStream(pcmFile(title));
            byte[] buf = new byte[8192];
            int i = 0;
            while (i < len) {
                int n = Math.min(4096, len - i);
                for (int k = 0; k < n; k++) {
                    short s = pcm[i + k];
                    buf[k * 2] = (byte) (s & 0xff);
                    buf[k * 2 + 1] = (byte) ((s >> 8) & 0xff);
                }
                fo.write(buf, 0, n * 2);
                i += n;
            }
            fo.close();
            FileWriter w = new FileWriter(metaFile(title));
            w.write(title + "\n" + sampleRate + "\n" + len + "\n");
            w.close();
            Log.i(TAG, "saved " + title + " " + len + " samples @" + sampleRate);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "save failed: " + title, t);
            return false;
        }
    }

    /** 读元信息：{sampleRate, samples}；不存在返回 null。 */
    public int[] meta(String title) {
        if (!has(title)) return null;
        try {
            Scanner sc = new Scanner(metaFile(title), "UTF-8");
            String name = sc.hasNextLine() ? sc.nextLine() : "";
            int sr = sc.hasNextLine() ? parseInt(sc.nextLine(), 24000) : 24000;
            int samples = sc.hasNextLine() ? parseInt(sc.nextLine(), 0) : 0;
            sc.close();
            if (name.isEmpty() || samples <= 0) return null;
            return new int[]{sr, samples};
        } catch (Throwable t) {
            return null;
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Throwable t) { return def; }
    }

    /** 读出整首 PCM。 */
    public short[] load(String title) {
        int[] m = meta(title);
        if (m == null) return null;
        final int samples = m[1];
        try {
            FileInputStream fi = new FileInputStream(pcmFile(title));
            byte[] buf = new byte[samples * 2];
            int read = 0;
            while (read < buf.length) {
                int n = fi.read(buf, read, buf.length - read);
                if (n <= 0) break;
                read += n;
            }
            fi.close();
            int got = read / 2;
            short[] pcm = new short[got];
            for (int i = 0; i < got; i++) {
                pcm[i] = (short) ((buf[i * 2] & 0xff) | (buf[i * 2 + 1] << 8));
            }
            return pcm;
        } catch (Throwable t) {
            Log.e(TAG, "load failed: " + title, t);
            return null;
        }
    }

    public void remove(String title) {
        try { pcmFile(title).delete(); metaFile(title).delete(); } catch (Throwable ignore) {}
    }

    /** 已缓存几首（.pcm 够长且 .meta 能读出名字的才算）。 */
    public int count() {
        return titles().size();
    }

    /** 曲库总占用（.pcm + .meta）。设置页「存储」卡片显示用。 */
    public long totalBytes() {
        long sum = 0;
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        for (File f : fs) {
            try { if (f.isFile()) sum += f.length(); } catch (Throwable ignore) {}
        }
        return sum;
    }

    /**
     * 清空整个曲库，返回删掉几首。
     *
     * 只删 .pcm / .meta 两种后缀，目录本身和其他文件一概不动 ——
     * 这个目录（files/music）同时也是「默认扫描根」之一，用户自己放进来的
     * mp3 万一在这儿，绝不能跟着一起没。
     */
    public int clearAll() {
        File[] fs = dir.listFiles();
        if (fs == null) return 0;
        int n = 0;
        for (File f : fs) {
            String name = f.getName();
            boolean pcm = name.endsWith(".pcm");
            if (!pcm && !name.endsWith(".meta")) continue;
            try {
                if (f.delete()) { if (pcm) n++; }
                else Log.w(TAG, "清不掉 " + name);
            } catch (Throwable ignore) {}
        }
        if (n > 0) Log.i(TAG, "clearAll 删掉 " + n + " 首");
        return n;
    }
}
