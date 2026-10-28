package com.altc.xiaozhi;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;

/**
 * 物理麦克风按键状态探针。
 *
 * 背景：这台叮当智能屏的电源键短按不是「息屏」，而是切换麦克风开关，
 * 该逻辑在厂商（com.tencent.ai.tvs.framework）私有层，Android 层收不到 KeyEvent
 * （实测 adb 注入 KEYCODE_POWER 只会熄屏）。所以状态只能从「数据面」去读：
 *
 *  1. Settings 里的 tvssettings/key_system_microphone / key_user_op_microphone
 *     —— 反编译 tvs.framework 得到的键名，键不在时返回 null。
 *  2. AudioManager.isMicrophoneMute() —— 若厂商走的是 AudioService 的 mute 通道。
 *
 * 两者任一变化都记录下来，用来判定按键实际改了哪一层，进而决定录制侧怎么处理。
 */
public class MicState {
    private static final String TAG = "MicState";

    public static final String KEY_SYSTEM_MIC = "tvssettings/key_system_microphone";
    public static final String KEY_USER_OP_MIC = "tvssettings/key_user_op_microphone";

    private final Context ctx;
    private final AudioManager am;
    private String lastSnapshot = "";

    public MicState(Context c) {
        this.ctx = c.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    /** 读取一次当前状态的紧凑描述。 */
    public String snapshot() {
        String mute;
        try {
            mute = am != null ? String.valueOf(am.isMicrophoneMute()) : "?";
        } catch (Throwable t) {
            mute = "err";
        }
        return "micMute=" + mute
                + " sys=" + get(KEY_SYSTEM_MIC)
                + " user=" + get(KEY_USER_OP_MIC);
    }

    /** 按键切换后真正关心的那个布尔值；未知返回 null。 */
    public Boolean isMicOn() {
        String v = get(KEY_SYSTEM_MIC);
        if (v == null) v = get(KEY_USER_OP_MIC);
        if (v == null) return null;
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "open".equalsIgnoreCase(v);
    }

    private String get(String key) {
        String v = null;
        try {
            v = Settings.System.getString(ctx.getContentResolver(), key);
            if (v == null) v = Settings.Secure.getString(ctx.getContentResolver(), key);
            if (v == null) v = Settings.Global.getString(ctx.getContentResolver(), key);
        } catch (Throwable ignore) {}
        return v;
    }

    /** 周期性检查，状态变化时回调（在主线程）。 */
    public void startWatch(Handler h, OnChange cb) {
        final Runnable[] task = new Runnable[1];
        task[0] = new Runnable() {
            @Override
            public void run() {
                String now = snapshot();
                if (!now.equals(lastSnapshot)) {
                    Log.i(TAG, "mic state changed: " + now);
                    lastSnapshot = now;
                    if (cb != null) cb.onChange(now, isMicOn());
                }
                h.postDelayed(task[0], 1500);
            }
        };
        h.post(task[0]);
    }

    public interface OnChange {
        /** @param value 为 null 表示厂商状态键尚未出现（无法判定） */
        void onChange(String snapshot, Boolean value);
    }
}
