package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;

/**
 * 音量统一出口。
 *
 * <p>为什么要有这个类：机身音量键走的是 AudioService 的「档位」模型（这台机器 STREAM_MUSIC
 * 是 0~15 共 16 档，按一次 = 1 档 ≈ 6.7%），而以前语音指令是按「百分比」加减（默认 15%），
 * 两边尺子不一样 —— 说一次「大声点」等于按两下机身键，报出来的百分比还和实际档位对不上
 * （比如「调到 30%」实际落在 33%，屏幕上却写 30%）。结果就是用户觉得「不一致」。
 *
 * <p>现在所有入口（语音、设置页、MCP 工具、启动音量）都走这里：
 * <ul>
 *   <li>相对调节用 {@link AudioManager#adjustStreamVolume} —— 和机身键完全同一条代码路径，
 *       一次一档；</li>
 *   <li>绝对调节用 {@link #setPct(int)}，写完后 **回读实际档位**，界面只报实际值；</li>
 *   <li>{@link #label()} 同时给出百分比和档位（「53%（8/15 档）」），跟系统音量条对得上。</li>
 * </ul>
 */
public final class VolumeCtl {

    /** 语音「大声点」一次走几档：1 档 = 按一次机身音量键。 */
    public static final int STEPS_MIN = 1;
    public static final int STEPS_MAX = 5;
    public static final int STEPS_DEFAULT = 1;

    private final Context ctx;
    private final AudioManager am;

    public VolumeCtl(Context c) {
        this.ctx = c.getApplicationContext();
        this.am = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    public boolean ok() {
        return am != null && max() > 0;
    }

    /** 最大档位（这台机器是 15）。 */
    public int max() {
        try {
            return am == null ? 0 : am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 当前档位。注意：这台机器 STREAM_MUSIC 是按输出设备分开存的，这里读到的是当前设备（扬声器）。 */
    public int idx() {
        final int m = max();
        if (m <= 0) return 0;
        try {
            int i = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return Math.max(0, Math.min(m, i));
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 当前百分比（按实际档位换算，不是「请求值」）。 */
    public int pct() {
        final int m = max();
        return m <= 0 ? 0 : Math.round(idx() * 100f / m);
    }

    /** 「53%（8/15 档）」——界面上一律用这个，和系统音量条同一把尺子。 */
    public String label() {
        final int m = max();
        if (m <= 0) return "音量不可用";
        return pct() + "%（" + idx() + "/" + m + " 档）";
    }

    /** 一档大约多少百分比。 */
    public int stepPct() {
        final int m = max();
        return m <= 0 ? 0 : Math.round(100f / m);
    }

    /** 语音一次走几档（设置页可改，默认 1 档 = 和机身键一样）。 */
    public int steps() {
        try {
            SharedPreferences sp = ctx.getSharedPreferences("xz", Context.MODE_PRIVATE);
            int v = sp.getInt("volSteps", STEPS_DEFAULT);
            return Math.max(STEPS_MIN, Math.min(STEPS_MAX, v));
        } catch (Throwable t) {
            return STEPS_DEFAULT;
        }
    }

    /**
     * 相对调节：dir &gt; 0 加，dir &lt; 0 减，走 steps 档。
     *
     * @return 实际档位是否有变化（到顶 / 到底 时返回 false）
     */
    public boolean step(int dir, int steps) {
        if (am == null || max() <= 0 || dir == 0) return false;
        final int before = idx();
        final int n = Math.max(1, Math.min(STEPS_MAX, steps));
        final int key = dir > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        for (int i = 0; i < n; i++) {
            try {
                // flags = 0：不弹系统音量条（我们自己有文字反馈），档位算法和机身键完全一致
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, key, 0);
            } catch (Throwable t) {
                break;
            }
        }
        return idx() != before;
    }

    /** 绝对调节：0~100 百分比 → 最近档位。 */
    public boolean setPct(int pct) {
        if (am == null || max() <= 0) return false;
        final int m = max();
        final int i = Math.max(0, Math.min(m, Math.round(pct / 100f * m)));
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, i, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 档位 → 百分比（写盘用）。
     *
     * <p>「启动音量」那个滑杆存的仍是百分比，落档时用同一个 round，保证写进去的百分比
     * 反算回来还是同一档，不会因为取整漂移。
     */
    public int pctOfStep(int step) {
        final int m = max();
        return m <= 0 ? 0 : Math.round(step * 100f / m);
    }

    /** 百分比 → 最近档位（写盘前后对账用）。 */
    public int stepOfPct(int pct) {
        final int m = max();
        return m <= 0 ? 0 : Math.max(0, Math.min(m, Math.round(pct / 100f * m)));
    }
}
