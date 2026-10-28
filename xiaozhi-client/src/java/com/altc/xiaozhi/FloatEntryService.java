package com.altc.xiaozhi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 待机悬浮入口：在叮当待机屏幕最上层浮一个「小智」按钮，点一下回到 MainActivity。
 *
 * 不依赖系统 dock、不依赖叮当云端 —— 这是个人开发者在「无 root / 平台不对个人开放」
 * 前提下，唯一能在待机界面上随时进小智的本地方案。
 *
 * 用 TYPE_APPLICATION_OVERLAY 绘制（普通 View 浮层，不受「Toast 不可见」那条限制影响）。
 * 需「显示在其他应用上层」权限：安装后用
 *   adb shell appops set com.altc.xiaozhi SYSTEM_ALERT_WINDOW allow
 * 授权；或在设置页点状态文字跳转系统授权页。
 *
 * 外观（v3 起）：圆形彩色图标 + 下方「小智」文字，尺寸/投影/白边对齐叮当待机屏底部
 * 那 4 个 dock 图标（同大小、同圆角质感），一眼看过去像系统自带的一个入口。
 */
public class FloatEntryService extends Service {

    private static final String CHANNEL_ID = "float_entry";

    /** 可视圆的直径（不含四周投影留白）。和叮当待机屏底部那排 dock 图标同量级。 */
    private static final int CIRCLE_DP = 80;
    /** 投影留白：四周各 8dp，所以 View 本体 = 80 + 16 = 96dp。 */
    private static final int SHADOW_PAD_DP = 8;

    /** 记住拖动后的位置，重启/开机自启后回到原处。 */
    private static final String KEY_FLOAT_X = "floatX";
    private static final String KEY_FLOAT_Y = "floatY";

    private WindowManager wm;
    private View bubble;
    private WindowManager.LayoutParams params;
    private float downX, downY;
    private int downPX, downPY;
    private boolean moved;

    /** 当前运行的实例 + 本应用是否在前台（前台时隐藏按钮，杜绝挡住自家 UI）。 */
    private static FloatEntryService inst;
    private static boolean ownAppVisible = false;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        // 没有悬浮窗权限直接退出（调用方已尽量前置拦截，这里再兜底一次）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        showBubble();
        applyVisibility();
        startForeground(1, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 已显示就别重复加 View
        if (bubble == null && wm != null) showBubble();
        applyVisibility();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        if (bubble != null && wm != null) {
            try { wm.removeView(bubble); } catch (Exception ignore) {}
        }
        bubble = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private Notification buildNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "待机悬浮入口",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("待机屏上的小智悬浮按钮");
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle("小智悬浮入口")
                .setContentText("点屏幕上的小智按钮回到小智")
                .setSmallIcon(R.drawable.ic_mic)
                .build();
    }

    /** 圆形图标（绿渐变 + 投影 + 白边）+ 下方「小智」文字，整体可拖动。 */
    private void showBubble() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        int circleSize = dp(CIRCLE_DP + SHADOW_PAD_DP * 2);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        // ---- 圆：底图负责投影/渐变/白边，里面居中放一支白色话筒 ----
        FrameLayout circle = new FrameLayout(this);
        circle.setBackgroundResource(R.drawable.float_bubble_ring);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mic);
        int iconSize = dp(38);
        circle.addView(icon, new FrameLayout.LayoutParams(
                iconSize, iconSize, Gravity.CENTER));

        // ---- 文字：白色 + 投影，亮壁纸上也看得清 ----
        TextView label = new TextView(this);
        label.setText("小智");
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(14);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(3), 0, dp(1), 0xCC000000);

        LinearLayout.LayoutParams clp =
                new LinearLayout.LayoutParams(circleSize, circleSize);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(1);
        box.addView(circle, clp);
        box.addView(label, tlp);

        // 拖动 + 轻点：移动超过阈值算拖动，否则算点击回到小智
        box.setOnTouchListener((v, ev) -> {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    downPX = params.x;
                    downPY = params.y;
                    moved = false;
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - downX;
                    float dy = ev.getRawY() - downY;
                    if (Math.abs(dx) > 4 || Math.abs(dy) > 4) {
                        moved = true;
                        v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    }
                    // 夹在屏幕内：拖出边界后按钮就再也点不到了
                    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                    int bw = v.getWidth() > 0 ? v.getWidth() : circleSize;
                    int bh = v.getHeight() > 0 ? v.getHeight() : circleSize;
                    params.x = clamp(downPX + (int) dx, 0, Math.max(0, dm.widthPixels - bw));
                    params.y = clamp(downPY + (int) dy, 0, Math.max(0, dm.heightPixels - bh));
                    wm.updateViewLayout(bubble, params);
                    break;
                }
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(90).start();
                    if (ev.getAction() == MotionEvent.ACTION_UP) {
                        if (moved) savePosition();
                        else openMainActivity();
                    }
                    break;
            }
            return true;
        });

        int type = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // 默认位置：左边缘、时间/日期下方那块空白（不同壁纸都不会压到待机屏的
        // 天气/时间/问候语）。用户拖过之后就按记住的位置放。
        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
        params.x = sp.getInt(KEY_FLOAT_X, dp(8));
        params.y = sp.getInt(KEY_FLOAT_Y, dp(390));

        bubble = box;
        wm.addView(bubble, params);
    }

    /** 记住当前位置（拖动结束时写，避免每帧都落盘）。 */
    private void savePosition() {
        if (params == null) return;
        getSharedPreferences("xz", MODE_PRIVATE).edit()
                .putInt(KEY_FLOAT_X, params.x)
                .putInt(KEY_FLOAT_Y, params.y)
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private void openMainActivity() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    /** 由 App 的 ActivityLifecycleCallbacks 调用：小智自己在前台时隐藏按钮。 */
    public static void setOwnAppVisible(boolean visible) {
        ownAppVisible = visible;
        FloatEntryService s = inst;
        if (s != null) s.applyVisibility();
    }

    private void applyVisibility() {
        if (bubble != null) {
            bubble.setVisibility(ownAppVisible ? View.GONE : View.VISIBLE);
        }
    }
}
