package com.altc.xiaozhi;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 聊天记录导出 / 复制。
 *
 * 导出目标：{@code /sdcard/Android/data/com.altc.xiaozhi/files/exports/chat_*.txt}
 * （应用私有外部目录，**不需要任何存储权限**，电脑上 adb pull 就能取走）。
 * 每行一条：{@code [2026-10-26 13:02:11] 小智：…}，事件行角色写「事件」。
 */
public class ChatExport {

    private static final SimpleDateFormat FILE_TS =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
    private static final SimpleDateFormat LINE_TS =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    public static String dirOf(Context c) {
        File d = c.getExternalFilesDir("exports");
        if (d == null) d = new File(c.getFilesDir(), "exports");
        return d.getAbsolutePath();
    }

    public static File write(Context c, List<ChatStore.Msg> msgs, String rangeLabel) {
        File d = c.getExternalFilesDir("exports");
        if (d == null) d = new File(c.getFilesDir(), "exports");
        if (!d.exists() && !d.mkdirs()) return null;
        File f = new File(d, "chat_" + FILE_TS.format(new Date()) + ".txt");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f);
            OutputStreamWriter w = new OutputStreamWriter(fos, "UTF-8");
            w.write(toText(msgs, rangeLabel));
            w.flush();
            w.close();
            return f;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Throwable ignore) {
            }
        }
    }

    public static String toText(List<ChatStore.Msg> msgs, String rangeLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("小智语音 · 聊天记录\n");
        sb.append("导出时间：").append(LINE_TS.format(new Date())).append('\n');
        if (rangeLabel != null && !rangeLabel.isEmpty()) {
            sb.append("范围：").append(rangeLabel).append('\n');
        }
        sb.append("共 ").append(msgs.size()).append(" 条\n");
        sb.append("------------------------------------------------------------\n");
        for (ChatStore.Msg m : msgs) {
            sb.append('[').append(m.ts > 0 ? LINE_TS.format(new Date(m.ts)) : "时间未知")
                    .append("] ");
            sb.append(m.isEvent() ? "事件" : (m.isMe() ? "我" : "小智")).append("：");
            sb.append(m.text.replace("\n", "\n        "));
            sb.append('\n');
        }
        return sb.toString();
    }

    public static boolean copy(Context c, String text) {
        try {
            ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(ClipData.newPlainText("小智聊天记录", text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
