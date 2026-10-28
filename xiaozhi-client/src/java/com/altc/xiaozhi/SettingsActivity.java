package com.altc.xiaozhi;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置页（卡片式）。
 *
 * 所有开关都**立即写 SharedPreferences**，主界面在 onResume 里重新读，
 * 因此不需要在页面之间做事件回调。
 */
public class SettingsActivity extends Activity {

    private SharedPreferences sp;
    private EditText urlEdit;
    private EditText tokenEdit;
    private EditText deviceEdit;
    private EditText dirsEdit;
    private TextView tvDevice;
    private TextView tvVolStep;
    private TextView tvVolDefault;
    private TextView tvVolNow;
    private VolumeCtl volumes;
    private TextView dirsStatus;
    private android.widget.SeekBar sbVolStep;
    private android.widget.SeekBar sbVolDefault;
    /** 存储卡片：三项缓存占用 + 可用空间 + 页面内反馈行。 */
    private TextView tvCacheLib;
    private TextView tvCacheSmb;
    private TextView tvCacheParts;
    private TextView tvCacheSpace;
    private TextView tvCacheStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        sp = getSharedPreferences("xz", MODE_PRIVATE);

        // 顶栏右侧显示构建版本标识（智能音箱版 / 手机·平板版）
        TextView cs = findViewById(R.id.conn_state);
        if (cs != null) cs.setText(AppConfig.VERSION_TAG);

        urlEdit = findViewById(R.id.edit_url);
        tokenEdit = findViewById(R.id.edit_token);
        deviceEdit = findViewById(R.id.et_device);
        tvDevice = findViewById(R.id.tv_device);

        urlEdit.setText(sp.getString("url", XiaoZhiClient.DEFAULT_URL));
        tokenEdit.setText(sp.getString("token", OtaClient.PUBLIC_TOKEN));
        deviceEdit.setText(sp.getString("deviceId", ""));
        tvDevice.setText("client-id: " + shortId(sp.getString("clientId", "-")));

        bindSwitch(R.id.sw_continuous, "continuous", false);
        bindSwitch(R.id.sw_camera, "cameraEnabled", true);
        bindSwitch(R.id.sw_mic, "micEnabled", true);
        bindSwitch(R.id.sw_keep_photo, "keepPhoto", false);
        bindSwitch(R.id.sw_smb, "smbEnabled", false);
        // 网络盘默认开着：网关就在家里 PC 上，关掉只是让清单少一组；真没网关时也只是扫描失败
        bindSwitch(R.id.sw_netdisk, NetDiskLibrary.KEY_ENABLED, true);
        bindSwitch(R.id.sw_vol_apply, "volApplyOnStart", false);
        bindFloatEntry();

        // 媒体扫描目录（P1）：默认目录始终扫，这里是追加
        dirsEdit = findViewById(R.id.et_media_dirs);
        dirsStatus = findViewById(R.id.tv_media_dirs_status);
        dirsEdit.setText(sp.getString(LocalFiles.KEY_MEDIA_DIRS, ""));
        findViewById(R.id.btn_media_dirs_apply).setOnClickListener(v -> applyMediaDirs(true));
        findViewById(R.id.btn_media_dirs_scan).setOnClickListener(v -> rescanMediaDirs());

        bindVolume();
        bindPlayMode();
        bindStorage();

        ((EditText) findViewById(R.id.et_smb_host)).setText(sp.getString("smbHost", ""));
        ((EditText) findViewById(R.id.et_smb_share)).setText(sp.getString("smbShare", ""));
        ((EditText) findViewById(R.id.et_smb_user)).setText(sp.getString("smbUser", ""));
        ((EditText) findViewById(R.id.et_smb_pass)).setText(sp.getString("smbPass", ""));

        // 网络盘（OpenList 网关）
        ((EditText) findViewById(R.id.et_ol_base))
                .setText(sp.getString(NetDiskLibrary.KEY_BASE, NetDiskLibrary.DEF_BASE));
        ((EditText) findViewById(R.id.et_ol_path))
                .setText(sp.getString(NetDiskLibrary.KEY_PATH, NetDiskLibrary.DEF_PATH));
        ((EditText) findViewById(R.id.et_ol_user))
                .setText(sp.getString(NetDiskLibrary.KEY_USER, NetDiskLibrary.DEF_USER));
        ((EditText) findViewById(R.id.et_ol_pass))
                .setText(sp.getString(NetDiskLibrary.KEY_PASS, NetDiskLibrary.DEF_PASS));

        findViewById(R.id.back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_save).setOnClickListener(v -> onSaveClicked());

        findViewById(R.id.btn_smb_test).setOnClickListener(v -> {
            // 先以当前输入框里的值（即便还没点「保存」）为准，写入 SP 再测
            String h = ((EditText) findViewById(R.id.et_smb_host)).getText().toString().trim();
            String s = ((EditText) findViewById(R.id.et_smb_share)).getText().toString().trim();
            String u = ((EditText) findViewById(R.id.et_smb_user)).getText().toString().trim();
            String p = ((EditText) findViewById(R.id.et_smb_pass)).getText().toString();
            sp.edit().putString("smbHost", h).putString("smbShare", s)
                    .putString("smbUser", u).putString("smbPass", p).apply();
            RemoteLibrary rl = new RemoteLibrary(this);
            new Thread(() -> {
                String err = rl.testConnection();
                runOnUiThread(() -> Toast.makeText(this,
                        err == null ? "连接成功" : ("连接失败：" + err),
                        Toast.LENGTH_LONG).show());
            }, "smb-test").start();
        });

        findViewById(R.id.btn_ol_test).setOnClickListener(v -> {
            saveNetDiskFields();
            refreshNetStatus(true);
        });

        findViewById(R.id.btn_ol_tree).setOnClickListener(v -> {
            saveNetDiskFields();
            refreshNetStatus(true);
        });

        // 进设置页就自动探一次：用户不用点，也能看出「网关在不在、挂载对不对」。
        // 只在后台跑，不挡界面；网关关机时 3 秒内会给一句「连不上」。
        refreshNetStatus(true);
        bindTreeScroll();

        findViewById(R.id.btn_console).setOnClickListener(v -> {
            Intent i = new Intent(this, ConsoleActivity.class);
            startActivity(i);
        });

        findViewById(R.id.btn_appearance).setOnClickListener(v ->
                startActivity(new Intent(this, AppearanceActivity.class)));

        findViewById(R.id.btn_local_music).setOnClickListener(v ->
                startActivity(new Intent(this, LocalMusicActivity.class)));

        findViewById(R.id.btn_rebind).setOnClickListener(v -> fillRandomIdentity());

        findViewById(R.id.btn_clear_chat).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle("清空聊天记录？")
                        .setMessage("只清空本机保存的对话（最多 100 条）。服务端控制台里的历史记录不受影响。")
                        .setPositiveButton("清空", (d, w) -> {
                            new ChatStore(this).clear();
                            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("取消", null)
                        .show());

        initSyncUI();
        findViewById(R.id.btn_test_photo).setOnClickListener(v -> testPhoto());
    }

    // ==================== 多设备同步（设置页 UI） ====================
    private final SyncManager.Callback syncCb = new SyncManager.Callback() {
        @Override public void onSyncDone(int added, int peerCount) {
            runOnUiThread(() -> {
                TextView tv = findViewById(R.id.tv_sync_status);
                if (tv != null) tv.setText("上次同步：合并 " + added + " 条，当前 " + peerCount + " 个同账号设备在线");
            });
        }
        @Override public void onPeersChanged(int peerCount) {
            runOnUiThread(() -> {
                TextView tv = findViewById(R.id.tv_sync_status);
                if (tv != null) tv.setText("发现 " + peerCount + " 个同账号设备在线");
            });
        }
    };

    private void initSyncUI() {
        Spinner spMode = findViewById(R.id.sp_sync_mode);
        EditText etPair = findViewById(R.id.et_pair_code);
        TextView tvDev = findViewById(R.id.tv_sync_device);
        TextView tvStatus = findViewById(R.id.tv_sync_status);
        TextView btnNow = findViewById(R.id.btn_sync_now);

        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.sync_mode_entries, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(adapter);
        int mode = 0;
        try { mode = Integer.parseInt(sp.getString("syncMode", "0")); } catch (Exception ignore) {}
        spMode.setSelection(mode);

        etPair.setText(sp.getString("pairCode", ""));
        tvDev.setText("device-id：" + sp.getString("deviceId", "-"));

        spMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                sp.edit().putString("syncMode", String.valueOf(pos)).apply();
                if (pos == SyncManager.AUTO) tvStatus.setText("自动模式：在前台且同局域网时自动同步");
                else if (pos == SyncManager.MANUAL) tvStatus.setText("手动模式：点「立即同步」才同步");
                else tvStatus.setText("已关闭同步");
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnNow.setOnClickListener(v -> {
            sp.edit().putString("pairCode", etPair.getText().toString().trim()).apply();
            SyncManager sm = SyncManager.init(SettingsActivity.this);
            sm.addCallback(syncCb);
            sm.start();
            sm.triggerSyncOnce();
            tvStatus.setText("已发起同步（发现 " + sm.peerCount() + " 个同账号设备，需在同一 Wi-Fi）");
        });

        SyncManager.init(this).addCallback(syncCb);
    }

    @Override
    protected void onPause() {
        super.onPause();
        SyncManager sm = SyncManager.get();
        if (sm != null) sm.removeCallback(syncCb);
    }

    private String shortId(String s) {
        return s.length() > 8 ? s.substring(0, 8) + "…" : s;
    }

    /** device-id 必须是 MAC 格式：6 组两位十六进制，冒号分隔。 */
    private static final java.util.regex.Pattern MAC_RE =
            java.util.regex.Pattern.compile("^([0-9a-f]{2}:){5}[0-9a-f]{2}$");

    /**
     * 保存：先校验 device-id，只有它变了才弹确认框。
     *
     * device-id 决定云端"这台设备是谁"，改它 = 丢掉现有绑定，
     * 所以必须显式确认；其余字段（url / token / smb）随时可存。
     */
    private void onSaveClicked() {
        final String cur = sp.getString("deviceId", "");
        String raw = deviceEdit.getText().toString().trim().toLowerCase();
        if (raw.isEmpty()) {
            deviceEdit.setError("不能为空：这是设备在云端的身份");
            // 音箱没有鼠标，setError 那个小图标看不见，必须配 Toast
            Toast.makeText(this, "device-id 不能为空", Toast.LENGTH_LONG).show();
            return;
        }
        if (!MAC_RE.matcher(raw).matches()) {
            deviceEdit.setError("格式应为 00:11:22:33:44:55（12 位十六进制，冒号分隔）");
            Toast.makeText(this, "格式错误：应像 00:11:22:33:44:55（12 位十六进制）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (raw.equalsIgnoreCase(cur)) {
            saveFields();
            Toast.makeText(this, "已保存，返回后自动重连", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("切换设备身份？")
                .setMessage("当前：" + (cur.isEmpty() ? "（无）" : cur) + "\n改为：" + raw
                        + "\n\n身份一改，原来的绑定就失效了，必须到 xiaozhi.me 重新输入验证码。\n\n"
                        + "· 重装 App 后想找回原绑定 → 填回原来的值，这是对的\n"
                        + "· 这是一台新音箱 → 用新值，或点「随机新身份」生成")
                .setPositiveButton("切换并重连", (d, w) -> {
                    saveFields();
                    sp.edit()
                            .putString("deviceId", raw)
                            .remove("token")           // 旧 token 属于旧身份，让 OTA 重新下发
                            .putBoolean("rebindPending", true)
                            .apply();
                    Toast.makeText(this, "已切换身份，返回后重新激活", Toast.LENGTH_LONG).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 非身份字段：随时可存，不影响绑定关系。 */
    private void saveFields() {
        sp.edit()
                .putString("url", urlEdit.getText().toString().trim())
                .putString("token", tokenEdit.getText().toString().trim())
                // 顺带存扫描目录：有人填完就直接点「保存并重连」，不能悄悄丢掉
                .putString(LocalFiles.KEY_MEDIA_DIRS,
                        normalizeDirs(new java.util.ArrayList<>()))
                .putString("smbHost", ((EditText) findViewById(R.id.et_smb_host)).getText().toString().trim())
                .putString("smbShare", ((EditText) findViewById(R.id.et_smb_share)).getText().toString().trim())
                .putString("smbUser", ((EditText) findViewById(R.id.et_smb_user)).getText().toString().trim())
                .putString("smbPass", ((EditText) findViewById(R.id.et_smb_pass)).getText().toString())
                .apply();
        saveNetDiskFields();
    }

    /** 网络盘四个字段：随时可存，保存后主界面下次扫描就用新的。 */
    private void saveNetDiskFields() {
        sp.edit()
                .putString(NetDiskLibrary.KEY_BASE,
                        ((EditText) findViewById(R.id.et_ol_base)).getText().toString().trim())
                .putString(NetDiskLibrary.KEY_PATH,
                        ((EditText) findViewById(R.id.et_ol_path)).getText().toString().trim())
                .putString(NetDiskLibrary.KEY_USER,
                        ((EditText) findViewById(R.id.et_ol_user)).getText().toString().trim())
                .putString(NetDiskLibrary.KEY_PASS,
                        ((EditText) findViewById(R.id.et_ol_pass)).getText().toString())
                .apply();
    }

    /**
     * 探一次网关，把结果和目录树写进页面。
     *
     * <p>这台机器 Toast 看不见，所有反馈必须落在页面上；进页面时也可能没点任何按钮，
     * 所以 {@code withTree=false} 时也要给一句「正在检测…」，否则用户以为没这功能。
     */
    private void refreshNetStatus(final boolean withTree) {
        final TextView st = findViewById(R.id.tv_ol_status);
        final TextView tree = findViewById(R.id.tv_ol_tree);
        final View treeBox = findViewById(R.id.sv_ol_tree);
        final View treeHint = findViewById(R.id.tv_ol_tree_hint);
        st.setVisibility(View.VISIBLE);
        st.setText("正在检测网关…");
        if (withTree) {
            treeBox.setVisibility(View.VISIBLE);
            treeHint.setVisibility(View.VISIBLE);
            tree.setText("正在读目录树…");
        }
        new Thread(() -> {
            final NetDiskLibrary nd = new NetDiskLibrary(SettingsActivity.this);
            final String msg = nd.testConnection();
            // 树是拿索引在内存里拼的（不逐层读盘），整棵树一次给全；
            // 框高度固定 260dp，超出的部分靠框自己的滑条滚。
            // 行数上限给到 1600：实测整棵树（206 个目录 + 每目录最多 6 个文件）约 1400 行，
            // 这样「所有层级」都摊得开，又不会因为某天网盘暴涨生成几万行把界面拖死。
            final String t = withTree ? nd.treePreview(5, 1600) : null;
            runOnUiThread(() -> {
                if (isFinishing()) return;
                st.setText(msg);
                // 用颜色区分死活：在线绿色（brand），离线红色（warn），一眼能看出
                try {
                    st.setTextColor(getResources().getColor(
                            msg.startsWith("连接成功") ? R.color.brand : R.color.danger));
                } catch (Throwable ignore) {}
                if (t != null) {
                    treeBox.setVisibility(View.VISIBLE);
                    treeHint.setVisibility(View.VISIBLE);
                    tree.setText(t);
                    if (treeBox instanceof android.widget.ScrollView) {
                        ((android.widget.ScrollView) treeBox).scrollTo(0, 0);   // 每次刷新都从顶上开始看
                    }
                }
            });
        }, "ol-status").start();
    }

    /** 目录树框里上一次按下的位置（判断手指是往上推还是往下拉）。 */
    private float treeTouchY;

    /**
     * 让目录树那个框**自己吃下整段手势**。
     *
     * <p>外页本身就是一个滚动容器。同方向嵌套时，手指在框里滑动，事件会被外层抢走一半 ——
     * 实测「框一格没动、页面反而跳了一屏」，子控件收到的还是 ACTION_CANCEL。
     * 常规的 {@code requestDisallowInterceptTouchEvent} 在这台定制 ROM 上不生效，
     * 所以直接用 {@link PageScrollView#setScrollLocked} 把外层的拦截关掉：
     * 手指按下就锁、抬手解锁，框里能滚，页面也不会跟着乱动。
     */
    private void bindTreeScroll() {
        final View box = findViewById(R.id.sv_ol_tree);
        if (!(box instanceof android.widget.ScrollView)) return;
        final android.widget.ScrollView sv = (android.widget.ScrollView) box;
        final View page = findViewById(R.id.sv_settings_page);
        final PageScrollView pager = page instanceof PageScrollView ? (PageScrollView) page : null;
        sv.setOnTouchListener((v, e) -> {
            final int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                treeTouchY = e.getY();
                v.getParent().requestDisallowInterceptTouchEvent(true);   // 老系统上仍值得留一手
                if (pager != null) pager.setScrollLocked(true);
            } else if (action == android.view.MotionEvent.ACTION_UP
                    || action == android.view.MotionEvent.ACTION_CANCEL) {
                v.getParent().requestDisallowInterceptTouchEvent(false);
                if (pager != null) pager.setScrollLocked(false);
            } else if (action == android.view.MotionEvent.ACTION_MOVE && pager != null) {
                // 框已经滚到头，还继续往同一方向推：解锁，把剩下的手势还给页面，
                // 免得「滑到底了页面还不动」让人以为卡死。
                final View child = sv.getChildAt(0);
                final int content = child == null ? 0 : child.getHeight();
                final boolean fingerUp = e.getY() < treeTouchY;
                final boolean canGoDown = sv.getScrollY() + sv.getHeight() < content;
                final boolean canGoUp = sv.getScrollY() > 0;
                if ((fingerUp && !canGoDown) || (!fingerUp && !canGoUp)) {
                    pager.setScrollLocked(false);
                }
            }
            return false;   // 不吃事件本身，ScrollView 自己也还要滚
        });
    }

    /** 从外观页返回时刷新当前排列方式的描述。 */
    @Override
    protected void onResume() {
        super.onResume();
        TextView tv = findViewById(R.id.tv_appearance);
        boolean split = AppearanceActivity.MODE_SPLIT.equals(
                sp.getString(AppearanceActivity.KEY_LAYOUT, AppearanceActivity.MODE_STACK));
        tv.setText(split ? "左右排列（聊天在左、操作面板在右）"
                : "上下排列（聊天在上、按钮在下）");
        refreshFloatStatus();
        // 从主界面回来时音量可能被说过「大声点」或按过机身键，这里重新读实际档位
        refreshNowVolume();
        // 期间可能新增了下载缓存或唱过新歌，顺手重算一次
        refreshStorage();
    }

    private void bindSwitch(int id, final String key, boolean def) {
        Switch sw = findViewById(id);
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) ->
                sp.edit().putBoolean(key, checked).apply());
    }

    // ==================== 播完之后（续播 / 重播 / 停止） ====================

    /**
     * 一首歌或一部片子放完以后做什么。音乐（MainActivity.onFileTrackDone）和
     * 视频（VideoActivity.onCompletion）共用这一份设置，避免两套行为让人猜。
     */
    private void bindPlayMode() {
        Spinner spMode = findViewById(R.id.sp_play_mode);
        TextView tv = findViewById(R.id.tv_play_mode);
        if (spMode == null) return;
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.play_mode_entries, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spMode.setAdapter(adapter);
        final int cur = PlayMode.get(this);
        spMode.setSelection(cur);
        if (tv != null) tv.setText(describePlayMode(cur));
        spMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                PlayMode.set(SettingsActivity.this, pos);
                if (tv != null) tv.setText(describePlayMode(pos));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** 三种行为各是什么意思 —— 光看下拉项容易选错，底下补一句。 */
    private static String describePlayMode(int mode) {
        if (mode == PlayMode.REPEAT) {
            return "当前：放完自动从头再放一遍（单曲循环）。";
        }
        if (mode == PlayMode.STOP) {
            return "当前：放完就停，停在结尾，等你说「下一首」或「退出」。";
        }
        return "当前：放完自动播队列里的下一个，屏幕上会显示续播的文件名。";
    }

    // ==================== 待机悬浮入口 ====================

    /**
     * 「待机悬浮入口」开关：开启时若已有悬浮窗权限就起服务；
     * 没权限则不硬起，改成跳系统授权页（这台设备调 appops 也能直接给）。
     */
    private void bindFloatEntry() {
        Switch sw = findViewById(R.id.sw_float_entry);
        sw.setChecked(sp.getBoolean("floatEntry", true));
        sw.setOnCheckedChangeListener((b, checked) -> {
            sp.edit().putBoolean("floatEntry", checked).apply();
            if (checked) {
                if (hasOverlay()) {
                    startService(new Intent(this, FloatEntryService.class));
                    refreshFloatStatus();
                } else {
                    openOverlaySettings();
                }
            } else {
                stopService(new Intent(this, FloatEntryService.class));
                refreshFloatStatus();
            }
        });
    }

    /** 状态落到文字上（Toast 在这台设备看不见），缺权限时点文字去授权。 */
    private void refreshFloatStatus() {
        TextView tv = findViewById(R.id.tv_float_status);
        if (tv == null) return;
        boolean enabled = sp.getBoolean("floatEntry", true);
        boolean can = hasOverlay();
        if (!enabled) {
            tv.setText("未开启");
            tv.setClickable(false);
            return;
        }
        if (can) {
            tv.setText("已开启：叮当待机屏左上角会浮出「小智」按钮，可拖动，点一下回小智");
            tv.setClickable(false);
        } else {
            tv.setText("还差「显示在其他应用上层」权限 —— 点这里授权（或用 adb 授予）");
            tv.setClickable(true);
            tv.setOnClickListener(v -> openOverlaySettings());
        }
    }

    private boolean hasOverlay() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return true;
        return Settings.canDrawOverlays(this);
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            // 有些定制 ROM 没有这个系统页，提示走 adb
            Toast.makeText(this,
                    "请授予「显示在其他应用上层」权限，或执行 adb shell appops set com.altc.xiaozhi SYSTEM_ALERT_WINDOW allow",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 拍一张看看摄像头是否正常；只有开了「保留照片」才会写盘。 */
    private void testPhoto() {
        Toast.makeText(this, "拍照中…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                byte[] jpeg = CameraCapture.captureJpeg(this);
                String extra = "";
                if (sp.getBoolean("keepPhoto", false)) {
                    java.io.File f = new java.io.File(getExternalFilesDir(null), "last_photo.jpg");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(jpeg);
                    fo.close();
                    extra = " -> " + f.getAbsolutePath();
                }
                final String msg = "拍照成功 " + (jpeg.length / 1024) + " KB" + extra;
                runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        "拍照失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "test-photo").start();
    }

    /**
     * 生成一个随机设备身份，**只填进输入框、不立即生效**。
     *
     * 真正落盘要再点「保存并重连」并在对话框里确认 ——
     * 免得误触一次就把现有绑定弄丢（旧版是点了就写、直接丢绑定）。
     */
    private void fillRandomIdentity() {
        String id = OtaClient.randomDeviceId();
        deviceEdit.setText(id);
        Toast.makeText(this, "已生成 " + id + "，点「保存并重连」生效", Toast.LENGTH_LONG).show();
    }

    // ==================== 媒体扫描目录（P1） ====================

    /**
     * 把输入框里的多行文本规范化：只保留真实存在的目录，返回换行拼接的结果。
     * 解析不出来的路径塞进 bad，交给调用方提示用户。
     */
    private String normalizeDirs(java.util.List<String> bad) {
        StringBuilder sb = new StringBuilder();
        String raw = dirsEdit.getText().toString();
        // 换行 / 逗号 / 分号 / 顿号都算分隔符
        for (String line : raw.split("[\\r\\n,;；、]+")) {
            // 先归一化（全角转半角、去引号），存进去的就是能直接用的路径
            String s = LocalFiles.cleanPath(line);
            if (s.isEmpty()) continue;
            if (LocalFiles.resolveDir(this, s) == null) {
                bad.add(s);
                continue;
            }
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    /** 「应用目录」：保存 + （可选）立刻重扫并报数量。 */
    private void applyMediaDirs(boolean rescan) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        String norm = normalizeDirs(bad);
        sp.edit().putString(LocalFiles.KEY_MEDIA_DIRS, norm).apply();
        if (!bad.isEmpty()) {
            // 有错的就不自动重扫：否则「找不到」的提示立刻被扫描结果盖掉，用户根本来不及看。
            // 输入框也保留原文，方便就地修改；有效的那几条已经存下了。
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < bad.size() && i < 3; i++) {
                if (i > 0) sb.append("、");
                sb.append(bad.get(i));
            }
            if (bad.size() > 3) sb.append(" 等");
            showDirsStatus("找不到 " + bad.size() + " 个路径（未保存）：" + sb
                    + "\n其余 " + (norm.isEmpty() ? 0 : norm.split("\n").length) + " 个已保存");
            return;
        }
        dirsEdit.setText(norm);
        int n = norm.isEmpty() ? 0 : norm.split("\n").length;
        showDirsStatus(n == 0 ? "已清空：只扫默认目录" : ("已保存 " + n + " 个目录"));
        if (rescan) rescanMediaDirs();
    }

    /** 重扫一遍并报「共 N 个媒体文件」——用户改完目录最想知道的就是这个。 */
    private void rescanMediaDirs() {
        // 先把当前输入框落盘，免得「只改了没点应用」就扫出旧结果
        sp.edit().putString(LocalFiles.KEY_MEDIA_DIRS, normalizeDirs(
                new java.util.ArrayList<>())).apply();
        showDirsStatus("扫描中…");
        new Thread(() -> {
            final String r = LocalFiles.scanReport(this);
            runOnUiThread(() -> showDirsStatus(r));
        }, "media-rescan").start();
    }

    /**
     * 结果就地显示在卡片里。
     * 这台设备上 Toast 合成后看不见（SurfaceFlinger 里有图层，屏幕上没有），
     * 所以不能只靠 Toast —— 设置页所有关键反馈都得落到文字上。
     */
    private void showDirsStatus(String s) {
        if (dirsStatus == null) return;
        dirsStatus.setText(s);
        dirsStatus.setVisibility(TextView.VISIBLE);
    }

    // ==================== 音量（P2） ====================

    /**
     * 音量三项：每次走几档（1~5 档，1 档 = 按一次机身音量键）、启动音量百分比、当前音量显示。
     *
     * <p>这里不再用「百分比步长」—— 那和机身音量键的档位模型对不上（说一次「大声点」会走 1.5~2 档）。
     * 现在步长就是档数，和系统音量条一一对应；启动音量仍按百分比存，但落档用同一个取整，
     * 反算回来还是同一档，不会漂。
     */
    private void bindVolume() {
        tvVolStep = findViewById(R.id.tv_vol_step);
        tvVolDefault = findViewById(R.id.tv_vol_default);
        tvVolNow = findViewById(R.id.tv_vol_now);
        sbVolStep = findViewById(R.id.sb_vol_step);
        sbVolDefault = findViewById(R.id.sb_vol_default);
        volumes = new VolumeCtl(this);

        final int perStep = Math.max(1, volumes.stepPct());
        int steps = Math.max(VolumeCtl.STEPS_MIN,
                Math.min(VolumeCtl.STEPS_MAX, sp.getInt("volSteps", VolumeCtl.STEPS_DEFAULT)));
        sbVolStep.setMax(VolumeCtl.STEPS_MAX - VolumeCtl.STEPS_MIN);
        sbVolStep.setProgress(steps - VolumeCtl.STEPS_MIN);
        tvVolStep.setText(steps + " 档 · 约 " + (steps * perStep) + "%");

        int def = Math.max(0, Math.min(100, sp.getInt("volDefault", 60)));
        def = Math.round(def / 5f) * 5;
        sbVolDefault.setProgress(def / 5);
        tvVolDefault.setText(volumeHint(def));
        refreshNowVolume();

        sbVolStep.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                int v = p + VolumeCtl.STEPS_MIN;
                tvVolStep.setText(v + " 档 · 约 " + (v * perStep) + "%");
                if (fromUser) sp.edit().putInt("volSteps", v).apply();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });

        sbVolDefault.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean fromUser) {
                int v = p * 5;
                tvVolDefault.setText(volumeHint(v));
                if (fromUser) sp.edit().putInt("volDefault", v).apply();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) { }
        });
    }

    /**
     * 启动音量的显示：这台机器只有 16 档，写死百分比会撒谎（30% 实际落 33%）。
     * 所以直接告诉用户落到哪一档，反算回来是多少。
     */
    private String volumeHint(int pct) {
        if (volumes == null) volumes = new VolumeCtl(this);
        if (!volumes.ok()) return pct + "%";
        final int m = volumes.max();
        final int idx = volumes.stepOfPct(pct);
        final int actual = volumes.pctOfStep(idx);
        return actual == pct
                ? pct + "%（" + idx + "/" + m + " 档）"
                : pct + "% → " + actual + "%（" + idx + "/" + m + " 档）";
    }

    /** 「当前音量 7%（1/15 档）」——写的就是实际档位，和机身键按出来的完全一致。 */
    private void refreshNowVolume() {
        if (tvVolNow == null) return;
        if (volumes == null) volumes = new VolumeCtl(this);
        tvVolNow.setText(volumes.ok()
                ? "当前音量 " + volumes.label()
                : "当前音量不可用（这个系统不允许改媒体音量）");
    }

    // ==================== 存储与缓存 ====================

    /**
     * 「存储」卡片：三项真正落盘的缓存 + 本机可用空间。
     *
     * <p>为什么非要给个入口：{@code part_} 残片只在「下一次下载」开头才会被自动清，
     * 不下新东西就永远占着（实测有一片 310MB 的《六祖坛经》挂了整整一上午）；
     * 而云端曲库压根没有上限，听过的歌每首约 2.9MB/分钟（24kHz/16bit/单声道）。
     * 这两样都得让用户自己看得见、动得了手，不能只靠 adb。
     *
     * <p>{@code smb_} 现在是「共享盘整包缓存」：边下边播下完就转正到这里，
     * 下次点同一首直接播本地、不再走网络。清掉不影响电脑上的原文件，只是下次要重下。
     *
     * <p>本机文件播放不落盘，所以卡片里没有它 —— 不是漏写。
     */
    private void bindStorage() {
        tvCacheLib = findViewById(R.id.tv_cache_lib);
        tvCacheSmb = findViewById(R.id.tv_cache_smb);
        tvCacheParts = findViewById(R.id.tv_cache_parts);
        tvCacheSpace = findViewById(R.id.tv_cache_space);
        tvCacheStatus = findViewById(R.id.tv_cache_status);
        if (tvCacheLib == null) return;      // 万一跑的是没这张卡的老布局，别崩

        findViewById(R.id.btn_cache_parts).setOnClickListener(v ->
                cleanCache(StorageCtl.PREFIX_PART, "下载残片", 180000L));   // 跳过正在播的那份
        findViewById(R.id.btn_cache_smb).setOnClickListener(v -> cleanDownloadCache());
        findViewById(R.id.btn_cache_manage).setOnClickListener(v -> showCacheFiles());

        findViewById(R.id.btn_cache_lib).setOnClickListener(v -> {
            final MusicLibrary lib = new MusicLibrary(this);
            final int n = lib.count();
            final long bytes = lib.totalBytes();
            if (n == 0) {
                showCacheStatus("曲库已经是空的，没有可清的");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("清空云端曲库？")
                    .setMessage("会删掉 " + n + " 首（" + StorageCtl.human(bytes) + "）的本地整首副本。\n\n"
                            + "· 原文件在服务端，不会丢\n"
                            + "· 下次点播这几首要重新从云端流式唱一遍，开头会有缓冲\n"
                            + "· 说话打断音乐的问题也会跟着回来（这份副本本来就是为了避开它）")
                    .setPositiveButton("清空", (d, w) -> {
                        final int removed = lib.clearAll();
                        showCacheStatus("已清空曲库：" + removed + " 首，释放 " + StorageCtl.human(bytes));
                        refreshStorage();
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });

        refreshStorage();
    }

    /** 按前缀清缓存：后台删，删完把数字刷新一遍。删的都是缓存副本，不动原文件。 */
    private void cleanCache(final String prefix, final String label) {
        cleanCache(prefix, label, 0);
    }

    /**
     * @param minAgeMs 跳过最近这么多毫秒内还在写的文件。
     *                 边下边播的临时文件就躺在 part_ 里且正在被播放器读，
     *                 无脑全删会把当前这首歌直接拆掉，所以「清残片」要带上这个。
     */
    private void cleanCache(final String prefix, final String label, final long minAgeMs) {
        showCacheStatus("正在清理" + label + "…");
        new Thread(() -> {
            final long[] r = new StorageCtl(this).delete(prefix, minAgeMs);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                showCacheStatus(r[0] <= 0
                        ? label + "：没有可清的"
                        : label + "：清掉 " + r[0] + " 个，释放 " + StorageCtl.human(r[1]));
                refreshStorage();
            });
        }, "cache-clean").start();
    }

    /** 下载缓存有两种来源（共享盘 smb_ / 网络盘 ol_），一起统计、一起清。 */
    private void cleanDownloadCache() {
        showCacheStatus("正在清理下载缓存…");
        new Thread(() -> {
            StorageCtl ctl = new StorageCtl(this);
            long[] a = ctl.delete(StorageCtl.PREFIX_SMB);
            long[] b = ctl.delete(StorageCtl.PREFIX_OL);
            final long n = a[0] + b[0], bytes = a[1] + b[1];
            runOnUiThread(() -> {
                if (isFinishing()) return;
                showCacheStatus(n <= 0 ? "下载缓存：没有可清的"
                        : "下载缓存：清掉 " + n + " 个，释放 " + StorageCtl.human(bytes));
                refreshStorage();
            });
        }, "cache-clean").start();
    }

    private void refreshStorage() {
        if (tvCacheLib == null) return;
        final MusicLibrary lib = new MusicLibrary(this);
        final java.io.File dir = new StorageCtl(this).dir();
        new Thread(() -> {
            final int libCount = lib.count();
            final long libBytes = lib.totalBytes();
            final long[] smb0 = StorageCtl.stat(dir, StorageCtl.PREFIX_SMB);
            final long[] ol = StorageCtl.stat(dir, StorageCtl.PREFIX_OL);
            final long[] smb = new long[]{smb0[0] + ol[0], smb0[1] + ol[1]};
            final long[] parts = StorageCtl.stat(dir, StorageCtl.PREFIX_PART);
            final long[] space = StorageCtl.space();
            runOnUiThread(() -> {
                if (isFinishing() || tvCacheLib == null) return;
                tvCacheLib.setText(libCount == 0
                        ? "空 · 只有点播过的歌才会存到这儿"
                        : libCount + " 首 · " + StorageCtl.human(libBytes));
                tvCacheSmb.setText(smb[0] == 0
                        ? "没有 · 直读正常就不会产生"
                        : smb[0] + " 个 · " + StorageCtl.human(smb[1]));
                tvCacheParts.setText(parts[0] == 0
                        ? "没有"
                        : parts[0] + " 个 · " + StorageCtl.human(parts[1]) + " · 白占地方，建议清掉");
                tvCacheSpace.setText(space[0] < 0
                        ? "本机可用空间读取失败（不影响播放）"
                        : "本机可用 " + StorageCtl.human(space[0])
                                + " / 共 " + StorageCtl.human(space[1]));
            });
        }, "cache-stat").start();
    }

    // ==================== 缓存明细（按大小排、挑着删） ====================

    /**
     * 「缓存明细」：把所有 {@code smb_/ol_/part_} 文件**按大小从大到小**列出来，勾选着删。
     *
     * <p>原来的三个按钮只能「整类清空」，可实际想清干净的多半就是那几个几百兆的视频 ——
     * 而常听的两首歌想留着。列表一排序就一目了然，不用靠猜。
     */
    private void showCacheFiles() {
        showCacheStatus("正在统计缓存明细…");
        new Thread(() -> {
            final java.io.File dir = new StorageCtl(this).dir();
            final java.util.List<java.io.File> fs = StorageCtl.list(dir,
                    StorageCtl.PREFIX_SMB, StorageCtl.PREFIX_OL, StorageCtl.PREFIX_PART);
            runOnUiThread(() -> {
                if (isFinishing()) return;
                showCacheDialog(fs);
            });
        }, "cache-list").start();
    }

    /**
     * 弹「缓存明细」。
     *
     * <p>不用 {@code setMessage + setMultiChoiceItems}：AlertDialog 里这两者抢同一块区域，
     * 消息一设上去列表就整个不见了（实测弹出来只有一行说明，看不到任何文件）。
     * 所以自己拼一个视图：上面一句说明，下面一个带复选框的列表，框内自己滚。
     */
    private void showCacheDialog(final java.util.List<java.io.File> fs) {
        if (fs == null || fs.isEmpty()) {
            showCacheStatus("缓存是空的，没有可清的（点播过的文件下完才会留在这儿）");
            return;
        }
        final long now = System.currentTimeMillis();
        final String[] labels = new String[fs.size()];
        final boolean[] checked = new boolean[fs.size()];
        final boolean[] busy = new boolean[fs.size()];
        long total = 0;
        for (int i = 0; i < fs.size(); i++) {
            final java.io.File f = fs.get(i);
            total += f.length();
            // 3 分钟内还在写的：边下边播的 part_ 或刚转正的那份，删了会把当前播放拆掉
            busy[i] = now - f.lastModified() < 180000L;
            labels[i] = StorageCtl.human(f.length()) + " · " + clipName(f.getName(), 24)
                    + "（" + srcLabelOf(f.getName()) + (busy[i] ? "，正在用" : "") + "）";
        }

        final float d = getResources().getDisplayMetrics().density;
        final android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding((int) (18 * d), (int) (4 * d), (int) (18 * d), 0);

        final TextView hint = new TextView(this);
        hint.setText("按大小从大到小排。勾选要删的，再点「删除选中」。\n"
                + "标着「正在用」的那份会跳过 —— 正在放的东西不会被拆掉。");
        hint.setTextSize(12);
        hint.setTextColor(getResources().getColor(R.color.text_secondary));
        hint.setPadding(0, 0, 0, (int) (6 * d));
        box.addView(hint);

        final android.widget.LinearLayout list = new android.widget.LinearLayout(this);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        for (int i = 0; i < fs.size(); i++) {
            final int idx = i;
            final android.widget.CheckBox cb = new android.widget.CheckBox(this);
            cb.setText(labels[i]);
            cb.setTextSize(13);
            cb.setTextColor(getResources().getColor(R.color.text_primary));
            cb.setPadding((int) (4 * d), (int) (5 * d), 0, (int) (5 * d));
            cb.setOnCheckedChangeListener((b, v) -> checked[idx] = v);
            list.addView(cb);
        }
        final android.widget.ScrollView sc = new android.widget.ScrollView(this);
        sc.addView(list);
        // 高度按条数给，最多 8 条高，再多就在框里滚 —— 别让对话框顶到屏幕外
        final int rows = Math.min(fs.size(), 8);
        box.addView(sc, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (rows * 44 * d)));

        new AlertDialog.Builder(this)
                .setTitle("缓存明细（共 " + fs.size() + " 个 · " + StorageCtl.human(total) + "）")
                .setView(box)
                .setPositiveButton("删除选中", (dlg, w) -> deleteCacheFiles(fs, checked, busy))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 删勾选的缓存。做三件事：跳过正在用的、统计释放量、把结果写到页面状态行
     * （Toast 在这台机器上看不见，只能落在文字上）。
     */
    private void deleteCacheFiles(final java.util.List<java.io.File> fs,
                                  final boolean[] checked, final boolean[] busy) {
        int n = 0, skipped = 0, want = 0;
        long bytes = 0;
        for (int i = 0; i < fs.size(); i++) {
            if (!checked[i]) continue;
            want++;
            if (busy[i]) { skipped++; continue; }
            final java.io.File f = fs.get(i);
            final long len = f.length();
            try {
                if (f.delete()) { n++; bytes += len; } else skipped++;
            } catch (Throwable ignore) { skipped++; }
        }
        if (want == 0) {
            showCacheStatus("没有勾选任何一项（点左边的方框选中再点「删除选中」）");
            return;
        }
        showCacheStatus(n == 0
                ? "一个都没删掉：" + skipped + " 个正在用，等放完再清"
                : "已删除 " + n + " 个，释放 " + StorageCtl.human(bytes)
                        + (skipped > 0 ? "（" + skipped + " 个正在用，跳过了）" : ""));
        refreshStorage();
    }

    private static String srcLabelOf(String name) {
        if (name.startsWith(StorageCtl.PREFIX_OL)) return "网络盘";
        if (name.startsWith(StorageCtl.PREFIX_SMB)) return "共享盘";
        if (name.startsWith(StorageCtl.PREFIX_PART)) return "残片";
        return "缓存";
    }

    /** 缓存文件名前面挂着 `smb_<时间戳>_`，去掉之后再截断，别让一屏只看得见时间戳。 */
    private static String clipName(String fn, int max) {
        String s = MediaSurvey.realCacheName(fn);
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** 页面内反馈（这台设备 Toast 合成后看不见）。 */
    private void showCacheStatus(String s) {
        if (tvCacheStatus == null) return;
        if (s == null) { tvCacheStatus.setVisibility(View.GONE); return; }
        tvCacheStatus.setText(s);
        tvCacheStatus.setVisibility(TextView.VISIBLE);
    }
}
