package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 一首/一部放完之后干什么：续播下一个 / 重播当前 / 停在这里。
 *
 * 存在统一 SP `xz` 的 {@link #KEY} 里（字符串 "0"/"1"/"2"，和 syncMode 的存法一致）。
 * 音乐（MainActivity.onFileTrackDone）和视频（VideoActivity.onCompletion）共用这一份设置，
 * 免得「歌续播、片子不续播」这种两套行为让用户猜。
 */
public final class PlayMode {
    /** SP 键。 */
    public static final String KEY = "playMode";

    public static final int NEXT = 0;      // 续播下一个（默认）
    public static final int REPEAT = 1;    // 重播当前这一个
    public static final int STOP = 2;      // 放完就停

    private PlayMode() {}

    public static int get(Context ctx) {
        if (ctx == null) return NEXT;
        try {
            SharedPreferences sp = ctx.getSharedPreferences("xz", Context.MODE_PRIVATE);
            return clamp(Integer.parseInt(sp.getString(KEY, String.valueOf(NEXT))));
        } catch (Throwable t) {
            return NEXT;
        }
    }

    public static void set(Context ctx, int mode) {
        if (ctx == null) return;
        try {
            ctx.getSharedPreferences("xz", Context.MODE_PRIVATE)
                    .edit().putString(KEY, String.valueOf(clamp(mode))).apply();
        } catch (Throwable ignore) {}
    }

    private static int clamp(int v) {
        return v < NEXT || v > STOP ? NEXT : v;
    }

    /** 给界面/日志用的名字。 */
    public static String name(int mode) {
        switch (clamp(mode)) {
            case REPEAT: return "重播当前";
            case STOP: return "停在这里";
            default: return "续播下一个";
        }
    }
}
