package com.altc.xiaozhi;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaDataSource;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.File;

/**
 * 视频播放页（P2「U1 伪全屏」+ P3 SMB 边下边播）。
 *
 * 为什么自带一个播放页：这台机器把系统的 video/mp4 VIEW 处理者裁掉了，
 * 用 Intent 交给系统会直接失败。
 *
 * 三个设计取舍：
 *  1) 底部 84dp 常驻栏（退出 / 麦克风 / 进度 / 字幕）。没有遥控器，控制栏一隐藏
 *     就摸不到麦克风按钮，等于废掉语音交互 —— 所以不自动隐藏。
 *  2) 用 MediaPlayer + SurfaceView 而不是 VideoView：VideoView.setVideoPath 只吃
 *     文件路径，接不了 SMB 直读的 MediaDataSource。自己管 MediaPlayer 才能边下边播。
 *  3) 语音不由本页处理：麦克风、ASR、WebSocket 全在 MainActivity（它转后台还在跑）。
 *     本页只通过广播跟它对话 —— 点麦克风 → 停画面 → 通知主界面开麦；
 *     主界面把字幕广播回来；答完再广播「继续」。
 *
 * 启动方式（仅应用内调用，本页不对外导出）：
 *   Intent 带 path（本机路径）/ smb（共享盘 URL）/ ol + olsize（网络盘路径 + 字节数）
 */
public class VideoActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "VideoActivity";

    /** 主界面 → 本页：控制命令（cmd = pause / resume / exit）。 */
    public static final String A_CMD = "com.altc.xiaozhi.action.VIDEO_CMD";
    /** 主界面 → 本页：字幕/状态文本（text）。 */
    public static final String A_SUB = "com.altc.xiaozhi.action.VIDEO_SUB";
    /** 本页 → 主界面：ev = front（已进入）/ closed（已退出）/ talk（请求开麦）。 */
    public static final String A_EVENT = "com.altc.xiaozhi.action.VIDEO_EVENT";

    private SurfaceView surface;
    private TextView tip, timeText, subText, exitBtn;
    /** 字幕层（浮在画面底部）：放长句 / 台词 / 清单，和 subText 的短状态分开。 */
    private TextView captionText;
    private ImageView micBtn;
    private SeekBar seek;

    private MediaPlayer mp;
    private MediaDataSource ds;
    private String title = "";
    private String localPath;
    private String smbUrl;
    /** 网络盘（OpenList 网关）里的路径，如 `/quark/话剧/雷雨.mp4`；非空时走 HTTP 边下边播。 */
    private String olPath;
    /** 网络盘文件字节数：没有总长就拖不动进度条，MediaPlayer 也会当直播流处理。 */
    private long olSize = 0;
    private boolean surfaceReady = false;
    private boolean prepared = false;
    private boolean userPaused = false;
    /** 因为页面被盖住而暂停（回来时自动接着放），和用户主动暂停区分开。 */
    private boolean pausedByLeave = false;
    /** SMB 直读失败后是否已经试过「下载到本地再播」，避免死循环。 */
    private boolean fallbackTried = false;
    /** 共享盘文件的真实字节数（openDataSource 时拿到），长片兜底前先算空间够不够。 */
    private long remoteSize = 0;

    /* ---------- T1 进度拖动（三态互斥，别搞混） ----------
       dragging : 手指正按在进度条上（此时只预览时间，绝不动播放器）
       seeking  : 已发出 seekTo，等 onSeekComplete（此时进度条不能回弹到旧位置）
       seekStartAt : seek 发出的时刻，用来算耗时 + 兜底超时（SMB 远跳可能很慢）        */
    private boolean dragging = false;
    private boolean seeking = false;
    private boolean seekWasPlaying = false;
    private long seekStartAt = 0;
    private int seekTarget = 0;
    /** 拖到 seek 超过这个时长还没回调，就不等了，把进度条交还给 ticker（防止永久冻住）。 */
    private static final long SEEK_TIMEOUT_MS = 8000;
    /**
     * 底部要给 smartdock 的常驻 dock 让出的高度（dp），**现在是 0**。
     *
     * 历史：dock 曾经常驻（overlay 窗口 mFrame=[0,725][1280,800]，高 75px，
     * insets 体系完全看不到它），只能硬编码让 58dp 才不被压住。
     * 后来 dock 改成自动隐藏（缩成左下角 29px 宽的箭头手柄，需要时点出来），
     * 底部 75px 彻底空出来 → 画面恢复正常全屏，不再让位。
     * 只保留这个常量做兜底：万一哪天系统真报到 insets（换回标准导航栏），
     * 仍按系统值让位（见 applyLayoutInsets）。
     */
    private static final int DOCK_SAFE_BOTTOM_DP = 0;
    /** 底部系统条实际让出的高度，px（dock 自动隐藏后恒为 0）。 */
    private int barInset = 0;

    /* ---------- T4 字幕层：整段显示的门槛与切段轮播 ----------
       3 行、20sp、整屏宽（两侧各留 28dp）一行约 45 字 → 130 字以内整段放得下。
       超过就按标点切段，每段按「中文 TTS 约 220ms/字」估停留时间，夹在 2.2s~9s。 */
    private static final int CAPTION_MAX_CHARS = 130;
    private static final int CAPTION_CHUNK = 45;
    private static final long CAPTION_MS_PER_CHAR = 220;
    private final java.util.List<String> captionChunks = new java.util.ArrayList<>();
    private int captionIndex = 0;
    private final Runnable captionNext = new Runnable() {
        @Override public void run() {
            if (captionChunks.isEmpty() || captionText == null) return;
            if (captionIndex + 1 >= captionChunks.size()) return;   // 停在最后一段
            captionIndex++;
            captionText.setText(captionChunks.get(captionIndex));
            scheduleCaption();
        }
    };

    /**
     * 字幕自动收起：语音文字盖在画面上虽然好看，但一直挂着会挡片尾/字幕，
     * 所以显示 3 秒后自己收（再说话会重新出现并重新计时）。
     */
    private static final long CAPTION_HIDE_MS = 3000;
    private final Runnable captionHide = new Runnable() {
        @Override public void run() {
            if (captionText != null) captionText.setVisibility(View.GONE);
        }
    };

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver cmdReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (A_SUB.equals(action)) {
                String t = i.getStringExtra("text");
                setCaption(t);          // 长句走字幕层（自动换行 / 超长切段轮播）
                return;
            }
            String cmd = i.getStringExtra("cmd");
            if (cmd == null) return;
            Log.i(TAG, "cmd " + cmd);
            switch (cmd) {
                case "pause":
                    doPause("已暂停 · 点麦克风可以说话");
                    break;
                case "resume":
                    doResume();
                    break;
                case "exit":
                    finish();
                    break;
                case "seek":            // 语音「跳到 2 分钟」：绝对位置（毫秒）
                    doVoiceSeek(i.getIntExtra("pos", 0), i.getBooleanExtra("play", false));
                    break;
                case "seekrel":         // 语音「快进 30 秒」：相对当前位置
                    doVoiceSeek(safePos() + i.getIntExtra("delta", 0),
                            i.getBooleanExtra("play", false));
                    break;
                case "seekpct":         // 语音「跳到一半」：按百分比
                    doVoiceSeek(safeDur() * Math.max(0, Math.min(100, i.getIntExtra("pct", 0))) / 100,
                            i.getBooleanExtra("play", false));
                    break;
                case "where":           // 语音「放到哪了」
                    setSub("已放到 " + fmt(safePos()) + " / " + fmt(safeDur()));
                    setCaption("已放到 " + fmt(safePos()) + " / " + fmt(safeDur()));
                    break;
                default:
                    break;
            }
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateProgress(false);
            // seek 迟迟不回来（网线被拔 / 共享盘掉线）：别把进度条永久冻住
            if (seeking && seekStartAt > 0
                    && System.currentTimeMillis() - seekStartAt > SEEK_TIMEOUT_MS) {
                finishSeek("timeout");
            }
            ui.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // FLAG_FULLSCREEN 不只是视觉：smartdock 的「Unpin on full screen apps」就是靠这个
        // 窗口标志判断当前是不是全屏应用（systemUiVisibility 那套它不认），
        // 不加的话 dock 会一直 pin 着、底部 75px 被它占掉。
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        immersive();
        setContentView(R.layout.activity_video);

        surface = findViewById(R.id.video_surface);
        tip = findViewById(R.id.video_tip);
        timeText = findViewById(R.id.video_time);
        subText = findViewById(R.id.video_sub);
        captionText = findViewById(R.id.video_caption);
        exitBtn = findViewById(R.id.video_exit);
        micBtn = findViewById(R.id.video_mic);
        seek = findViewById(R.id.video_seek);

        Intent it = getIntent();
        title = it == null ? "" : String.valueOf(it.getStringExtra("title"));
        if ("null".equals(title)) title = "";
        if (it != null) {
            localPath = it.getStringExtra("path");
            smbUrl = it.getStringExtra("smb");
            olPath = it.getStringExtra("ol");
            olSize = it.getLongExtra("olsize", 0);
        }
        if ((localPath == null || localPath.isEmpty())
                && (smbUrl == null || smbUrl.isEmpty())
                && (olPath == null || olPath.isEmpty())) {
            Log.e(TAG, "no path / smb / ol extra");
            finish();
            return;
        }
        if (title.isEmpty()) {
            String n = localPath != null ? localPath : (smbUrl != null ? smbUrl : olPath);
            int slash = n.lastIndexOf('/');
            title = slash >= 0 ? n.substring(slash + 1) : n;
        }
        Log.i(TAG, "open title=" + title + " path=" + localPath + " smb=" + smbUrl + " ol=" + olPath);

        exitBtn.setOnClickListener(v -> finish());
        // 一按麦克风就把画面停下来：这不是礼貌问题，是必需的 ——
        // 这台机器的 MTK HAL 在「输入 + 输出同时活跃」时会卡住 audio routing，
        // 视频不停就没法可靠地录音（详见 MEMORY 里的硬件坑）。
        micBtn.setOnClickListener(v -> requestTalk());
        setupSeek();

        surface.getHolder().addCallback(this);
        IntentFilter f = new IntentFilter(A_CMD);
        f.addAction(A_SUB);
        registerReceiver(cmdReceiver, f);
        ui.postDelayed(ticker, 500);
        ui.post(this::applyLayoutInsets);      // 等第一轮布局量完再算系统条高度
    }

    /* ==================== 播放控制 ==================== */

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        open();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        // Surface 没了画面就没了，但声音还能继续；这里直接释放，回来时重开更简单
        releasePlayer();
    }

    private void open() {
        if (!surfaceReady || released()) return;
        releasePlayer();
        prepared = false;
        if (smbUrl != null && !smbUrl.isEmpty()) {
            openSmb();
            return;
        }
        if (olPath != null && !olPath.isEmpty()) {
            openOl();
            return;
        }
        try {
            mp = new MediaPlayer();
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
            mp.setDisplay(surface.getHolder());
            mp.setDataSource(localPath);
            wireListeners();
            mp.prepareAsync();
        } catch (Throwable t) {
            Log.e(TAG, "open failed", t);
            tip("打不开：" + t);
        }
    }

    /**
     * SMB 直读：建上下文要 DNS，只能在后台线程做，做好了再回主线程交给 MediaPlayer。
     */
    private void openSmb() {
        tip("正在连接电脑…");
        final String url = smbUrl;
        new Thread(() -> {
            final MediaDataSource src = new RemoteLibrary(this).openDataSource(url);
            runOnUiThread(() -> {
                if (released() || !surfaceReady) {
                    if (src != null) { try { src.close(); } catch (Throwable ignore) {} }
                    return;
                }
                if (src == null) {
                    tip("SMB 打不开：" + title);
                    fallbackDownload();
                    return;
                }
                try {
                    ds = src;
                    try { remoteSize = src.getSize(); } catch (Throwable ignore) {}
                    mp = new MediaPlayer();
                    mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    mp.setDisplay(surface.getHolder());
                    mp.setDataSource(src);
                    wireListeners();
                    // 别在这里 hideTip：prepareAsync 走 SMB 可能要十几秒（实测冷启 21 秒），
                    // 一隐藏用户看到的就是纯黑屏，以为播放器挂了。等 onPrepared 再收。
                    tip("正在缓冲…");
                    ui.postDelayed(() -> {
                        if (!prepared && !released()) tip("网络较慢，正在缓冲…");
                    }, 4000);
                    mp.prepareAsync();
                } catch (Throwable t) {
                    Log.e(TAG, "openSmb failed", t);
                    fallbackDownload();
                }
            });
        }, "video-smb-open").start();
    }

    /**
     * 网络盘（OpenList 网关）直读：和共享盘同一套「边下边播」，只是数据源从 jcifs 换成 HTTP。
     *
     * <p>两步都要网络：{@code fs/get} 换带签名的直链，然后 OlStreamSource 起下载线程。
     * 所以整段放后台，建好了再回主线程交给 MediaPlayer。
     *
     * <p>为什么不能直接把 raw_url 丢给 MediaPlayer：夸克经网关转一手之后小请求只有几百 KB/s，
     * 播放器按小块读 = 每一块都等一趟往返，1080p 视频要 300KB/s~1MB/s，必卡。
     */
    private void openOl() {
        tip("正在连接网络盘…");
        final String path = olPath;
        final long size = olSize;
        new Thread(() -> {
            final NetDiskLibrary nd = new NetDiskLibrary(this);
            final NetDiskLibrary.Track tk = nd.trackFor(path, null, size);
            // 已经整包下过的片子直接本地播 —— 69MB 的片子第一次要下 48 秒，
            // 第二次还从头下就是白白让用户再等一遍（音频那边早就这么做了）。
            final File hit = nd.findCache(tk);
            if (hit != null) {
                runOnUiThread(() -> {
                    if (released()) return;
                    olPath = null;              // 后面都走本地文件
                    localPath = hit.getAbsolutePath();
                    Log.i(TAG, "网盘缓存命中，直接本地播: " + hit.getName());
                    hideTip();
                    open();
                });
                return;
            }
            final MediaDataSource src = nd.openDataSource(tk);
            runOnUiThread(() -> {
                if (released() || !surfaceReady) {
                    if (src != null) { try { src.close(); } catch (Throwable ignore) {} }
                    return;
                }
                if (src == null) {
                    tip("网络盘打不开：" + title);
                    fallbackDownload();
                    return;
                }
                try {
                    ds = src;
                    try { remoteSize = src.getSize(); } catch (Throwable ignore) {}
                    if (remoteSize <= 0) remoteSize = size;
                    mp = new MediaPlayer();
                    mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
                    mp.setDisplay(surface.getHolder());
                    mp.setDataSource(src);
                    wireListeners();
                    // 别急着 hideTip：网关第一次取直链 + 缓冲可能要几秒，
                    // 一隐藏用户看到的就是纯黑屏，以为播放器挂了。
                    tip("正在缓冲…");
                    ui.postDelayed(() -> {
                        if (!prepared && !released()) tip("网络较慢，正在缓冲…");
                    }, 5000);
                    mp.prepareAsync();
                } catch (Throwable t) {
                    Log.e(TAG, "openOl failed", t);
                    fallbackDownload();
                }
            });
        }, "video-ol-open").start();
    }

    private void wireListeners() {
        mp.setOnPreparedListener(p -> {
            prepared = true;
            userPaused = false;
            hideTip();
            applyAspect(p.getVideoWidth(), p.getVideoHeight());
            // 进度条量程 = 真实时长（毫秒），拖到哪儿就是哪儿
            int dur = safeDur();
            if (dur > 0) { seek.setMax(dur); seek.setProgress(0); }
            p.start();
            dumpTracks(p);
            Log.i(TAG, "started " + p.getVideoWidth() + "x" + p.getVideoHeight()
                    + " dur=" + p.getDuration());
            setSub("♪ " + title);
            // 起播（含「续播下一个」自动换片）都把当前文件名亮出来 3 秒再收，
            // 否则屏幕上一直留着上一部的名字，用户以为没换片。
            setCaption("▶ " + title);
        });
        mp.setOnErrorListener((p, what, extra) -> {
            Log.e(TAG, "error what=" + what + " extra=" + extra
                    + " prepared=" + prepared + " smb=" + (smbUrl != null));
            prepared = false;
            if (((smbUrl != null && !smbUrl.isEmpty()) || (olPath != null && !olPath.isEmpty()))
                    && !fallbackTried) {
                tip("直读失败，正在下载到本地…");
                fallbackDownload();
            } else {
                tip("播放失败（what=" + what + " extra=" + extra + "）");
            }
            return true;        // 已处理，别让系统再弹框
        });
        mp.setOnCompletionListener(p -> {
            final int mode = PlayMode.get(this);
            Log.i(TAG, "completed · 播完行为=" + PlayMode.name(mode));
            dragging = false;
            seeking = false;
            seekStartAt = 0;
            if (mode == PlayMode.REPEAT) {
                // 重播：原地回到 0 再放，不用重开播放器（SMB 也不用重新连接）
                try {
                    p.seekTo(0);
                    p.start();
                    prepared = true;
                    userPaused = false;
                    setSub("再放一遍：" + title);
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "repeat failed", t);
                }
            }
            prepared = false;
            userPaused = true;
            if (mode == PlayMode.NEXT) {
                // 续播：交给主界面的视频队列（它知道下一个是什么片名）
                setSub("放完了 · 正在续播下一个…");
                sendEvent("done");
                return;
            }
            // 不自动 finish：用户拖到最右端时也就差一两秒，直接跳回主界面会让人以为是出错。
            setSub("放完了 · 点退出返回，或说下一首");
            tip("放完了");
            try { seek.setProgress(seek.getMax()); } catch (Throwable ignore) {}
        });
        mp.setOnSeekCompleteListener(p -> finishSeek("callback"));
    }

    /** 直读不通（共享盘 / 网络盘）：退回「先整个下载到本地缓存再播」（P3 的兜底路径）。 */
    private void fallbackDownload() {
        if (fallbackTried) { finish(); return; }
        fallbackTried = true;
        final String url = smbUrl;
        final String ol = olPath;
        if ((url == null || url.isEmpty()) && (ol == null || ol.isEmpty())) { finish(); return; }
        final long size = remoteSize;       // 长片兜底前先用它算空间
        new Thread(() -> {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getCacheDir();
            // T4：比缓存上限还大的片子只能直读，别去下 —— 下了也会立刻被 LRU 清掉，
            // 中间还会把这台机器 2.7G 的存储挤爆（实测 2.5G 的片子能过老的「空间够」检查）。
            if (size > RemoteLibrary.CACHE_MAX_BYTES) {
                final String msg = "这片子 " + RemoteLibrary.human(size) + "，超过缓存上限 "
                        + RemoteLibrary.human(RemoteLibrary.CACHE_MAX_BYTES)
                        + "，只能直读不能先下载。检查一下共享盘/网络再试";
                Log.w(TAG, "fallback skipped: " + msg);
                runOnUiThread(() -> {
                    tip(msg);
                    setSub("片子太大，不能先下载再播");
                });
                return;
            }
            // T4：装不下就直接说清楚，别下到一半才 ENOSPC。
            long free = RemoteLibrary.freeBytes(dir);
            if (size > 0 && free >= 0 && free < size + RemoteLibrary.FREE_RESERVE) {
                final String msg = "缓存不下：片子 " + RemoteLibrary.human(size)
                        + "，本机只剩 " + RemoteLibrary.human(free)
                        + "。共享盘直读又不通，先清点空间再试";
                Log.w(TAG, "fallback skipped: " + msg);
                runOnUiThread(() -> {
                    tip(msg);
                    setSub("这个片子太大，缓存不下");
                });
                return;
            }
            final String local;
            if (ol != null && !ol.isEmpty()) {
                NetDiskLibrary nd = new NetDiskLibrary(this);
                local = nd.fetchToCache(nd.trackFor(ol, null, size), dir);
            } else {
                RemoteLibrary rl = new RemoteLibrary(this);
                String name = title;
                int slash = url.lastIndexOf('/');
                if (slash >= 0) name = url.substring(slash + 1);
                RemoteLibrary.Track t = new RemoteLibrary.Track(url, name, title,
                        LocalFiles.aliasesFromBase(name), size);
                local = rl.fetchToCache(t, dir);
            }
            runOnUiThread(() -> {
                if (local == null) {
                    tip("下载失败：网络断了或本机空间不足（片子 " + RemoteLibrary.human(size) + "）");
                    return;
                }
                smbUrl = null;              // 后面都走本地文件
                olPath = null;
                localPath = local;
                hideTip();
                open();
            });
        }, "video-fallback").start();
    }

    private synchronized void releasePlayer() {
        prepared = false;
        // 重开播放器时这三态必须归零，否则 ticker 会卡在 seeking 里再也不刷进度
        dragging = false;
        seeking = false;
        seekStartAt = 0;
        if (mp != null) {
            try { mp.reset(); } catch (Throwable ignore) {}
            try { mp.release(); } catch (Throwable ignore) {}
            mp = null;
        }
        if (ds != null) {
            try { ds.close(); } catch (Throwable ignore) {}
            ds = null;
        }
    }

    private boolean released() { return isFinishing() || isDestroyed(); }

    private void doPause(String hint) {
        if (mp != null && prepared) {
            try { if (mp.isPlaying()) { mp.pause(); userPaused = true; } } catch (Throwable ignore) {}
        }
        setSub(hint);
    }

    private void doResume() {
        if (mp != null && prepared && !mp.isPlaying()) {
            try {
                mp.start();
                userPaused = false;
                setSub("♪ " + title);
            } catch (Throwable ignore) {}
        }
    }

    private void requestTalk() {
        doPause("我在听…说完会自动接着放");
        Intent i = new Intent(A_EVENT);
        i.putExtra("ev", "talk");
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    /* ==================== T1 进度拖动 ==================== */

    /**
     * 拖动策略：拖动过程中只改时间显示、绝不动播放器（回调每秒几十次，
     * 每次都 seekTo 会把解码器打爆）；松手才 seekTo 一次。
     * 拖动前若正在播，先 pause，等 seek 完成再续播 —— SMB 远跳那 1~2 秒里，
     * 用户看到的是停在原地的画面，而不是卡在半路的残帧。
     */
    private void setupSeek() {
        if (seek == null) return;
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (!fromUser || mp == null || !prepared) return;
                int dur = safeDur();
                timeText.setText(dur > 0
                        ? fmt(p) + " / " + fmt(dur) + "  · 松开跳转"
                        : fmt(p));
            }

            @Override public void onStartTrackingTouch(SeekBar sb) {
                if (mp == null || !prepared) return;
                dragging = true;
                seekWasPlaying = mp.isPlaying();
                if (seekWasPlaying) {
                    try { mp.pause(); } catch (Throwable ignore) {}
                }
                Log.i(TAG, "seek drag start pos=" + safePos() + " playing=" + seekWasPlaying);
            }

            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (mp == null || !prepared) { dragging = false; return; }
                int dur = safeDur();
                int target = sb.getProgress();
                // 别贴着最末尾：留 2 秒，否则拖到最右端会立刻触发 onCompletion，
                // 用户以为"拖一下就没了"（实测过：留 1 秒也不够，MediaPlayer 起来就播完了）
                if (dur > 0 && target > dur - 2000) target = Math.max(0, dur - 2000);
                startSeek(Math.max(0, target));
            }
        });
    }

    /**
     * 语音跳转：和手指拖动走同一条 seek 路径（SEEK_CLOSEST + 三态互斥），
     * 区别只在「跳完要不要接着播」。
     *
     * @param play true = 用户说的是「从 2 分钟开始播」，跳完必须放；
     *             false = 保持原来的播放/暂停状态
     */
    private void doVoiceSeek(int target, boolean play) {
        if (mp == null || !prepared) {
            setSub("还在缓冲，稍等一下再说");
            return;
        }
        int dur = safeDur();
        if (dur > 0) target = Math.min(target, Math.max(0, dur - 2000));
        target = Math.max(0, target);
        seekWasPlaying = play || mp.isPlaying();
        try { if (mp.isPlaying()) mp.pause(); } catch (Throwable ignore) {}
        userPaused = !seekWasPlaying;
        startSeek(target);
    }

    private void startSeek(int target) {
        dragging = false;
        seeking = true;
        seekTarget = target;
        seekStartAt = System.currentTimeMillis();
        try {
            // 用 SEEK_CLOSEST 而不是默认的 SEEK_PREVIOUS_SYNC：
            // 后者会退到前一个关键帧，长片上能偏好几秒，用户以为拖错了。
            // 代价是 SMB 远跳要多读一段数据，耗时记在 onSeekComplete 里。
            mp.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            setSub("跳到 " + fmt(target) + " …");
            Log.i(TAG, "seek request " + target + "ms smb=" + (smbUrl != null));
        } catch (Throwable t) {
            Log.e(TAG, "seek failed", t);
            seeking = false;
            seekStartAt = 0;
            if (seekWasPlaying) doResume();
        }
    }

    /** seek 收尾：恢复进度条与播放状态。onSeekComplete 与超时兜底共用。 */
    private void finishSeek(String why) {
        if (!seeking) return;
        long cost = seekStartAt > 0 ? System.currentTimeMillis() - seekStartAt : -1;
        Log.i(TAG, "seek done by " + why + " -> " + seekTarget + " cost=" + cost + "ms");
        seeking = false;
        seekStartAt = 0;
        if (mp != null && seekWasPlaying) {
            try { mp.start(); } catch (Throwable ignore) {}
            setSub("♪ " + title);
        } else {
            setSub("已暂停 · " + fmt(safePos()));
        }
        updateProgress(true);
        // 语音跳转后用户最想知道「到底跳到哪了」，而云端对本地进度一无所知，
        // 这句确认只能自己给：字幕层显示一行 + 回报主界面记进对话。
        int pos = safePos(), dur = safeDur();
        setCaption("已跳到 " + fmt(pos) + (dur > 0 ? " / " + fmt(dur) : ""));
        sendPosEvent(pos, dur);
    }

    private void sendEvent(String ev) {
        Intent i = new Intent(A_EVENT);
        i.putExtra("ev", ev);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    /** 真实落点回报（主界面据此在对话里记一条「已跳到 …」）。 */
    private void sendPosEvent(int pos, int dur) {
        Intent i = new Intent(A_EVENT);
        i.putExtra("ev", "pos");
        i.putExtra("title", title);
        i.putExtra("pos", pos);
        i.putExtra("dur", dur);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private int safeDur() {
        try { return mp == null ? 0 : mp.getDuration(); } catch (Throwable t) { return 0; }
    }

    private int safePos() {
        try { return mp == null ? 0 : mp.getCurrentPosition(); } catch (Throwable t) { return 0; }
    }

    /* ==================== 界面小工具 ==================== */

    /** @param force true = 无视拖动态强刷（seek 收尾时用） */
    private void updateProgress(boolean force) {
        if (mp == null || !prepared) return;
        if (!force) {
            if (dragging) return;                 // 手指还在条上：别跟用户抢进度
            if (seeking) {
                // 远跳太慢时给个话，别让用户以为是死机
                if (seekStartAt > 0 && System.currentTimeMillis() - seekStartAt > 2500) {
                    setSub("缓冲中… 共享盘远跳较慢");
                }
                return;
            }
        }
        try {
            int pos = mp.getCurrentPosition();
            int dur = mp.getDuration();
            if (dur > 0) {
                // max 直接用毫秒：1 小时的片子也能精确到 1 秒以内（旧版固定 1000 档太粗）
                if (seek.getMax() != dur) seek.setMax(dur);
                seek.setProgress(Math.max(0, Math.min(pos, dur)));
                // 别用 ⏸ 这种符号：这台机器的系统字体没有那字形，会显示成方框
                timeText.setText(fmt(pos) + " / " + fmt(dur)
                        + (userPaused ? "  · 已暂停" : ""));
            }
        } catch (Throwable ignore) {}
    }

    private static String fmt(int ms) {
        if (ms < 0) ms = 0;
        int s = ms / 1000;
        int m = s / 60;
        int h = m / 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m % 60, s % 60);
        return String.format("%02d:%02d", m, s % 60);
    }

    private void tip(String s) {
        if (tip == null) return;
        tip.setText(s);
        tip.setVisibility(View.VISIBLE);
    }

    private void hideTip() {
        if (tip != null) tip.setVisibility(View.GONE);
    }

    private void setSub(String s) {
        if (subText != null) subText.setText(s);
    }

    /* ==================== T4 字幕层（长句 / 台词 / 清单） ==================== */

    /**
     * 字幕层：短句整段显示，超过 3 行才按标点切段轮播。
     *
     * 为什么不一律切段：绝大多数 TTS 句子本来就一两行，切了只会闪；
     * 而「四源清单」那种一百多字的长句不切就是一行「…」，等于把要看的字吃掉一半
     * —— 这就是 T4 要修的那个 bug。
     */
    private void setCaption(String s) {
        if (captionText == null) return;
        ui.removeCallbacks(captionNext);
        ui.removeCallbacks(captionHide);        // 新一句话：先别收，重新计时
        captionChunks.clear();
        captionIndex = 0;
        if (s == null) {
            captionText.setVisibility(View.GONE);
            return;
        }
        String t = s.trim();
        // 「…」是主界面表示「正在听/正在想」的占位，不该占一层字幕底板
        if (t.isEmpty() || t.equals("…") || t.equals("...")) {
            captionText.setVisibility(View.GONE);
            if (!t.isEmpty()) setSub(t);
            return;
        }
        if (t.length() <= CAPTION_MAX_CHARS) {
            captionChunks.add(t);
        } else {
            captionChunks.addAll(splitCaption(t));
        }
        captionText.setText(captionChunks.get(0));
        captionText.setVisibility(View.VISIBLE);
        scheduleCaption();
    }

    /** 排下一段的闹钟；最后一段改排「自动收起」，不循环也不常驻。 */
    private void scheduleCaption() {
        if (captionChunks.isEmpty()) return;
        if (captionChunks.size() == 1 || captionIndex + 1 >= captionChunks.size()) {
            // 最后一段：留够读完的时间（但不短于 3 秒），然后自动收
            String cur = captionChunks.get(captionIndex);
            long stay = Math.max(CAPTION_HIDE_MS,
                    Math.min(9000, cur.length() * CAPTION_MS_PER_CHAR));
            ui.removeCallbacks(captionHide);
            ui.postDelayed(captionHide, stay);
            return;
        }
        String cur = captionChunks.get(captionIndex);
        long ms = Math.max(2200, Math.min(9000, cur.length() * CAPTION_MS_PER_CHAR));
        ui.postDelayed(captionNext, ms);
    }

    /**
     * 按标点把长文本切成若干段，每段尽量不超过 {@link #CAPTION_CHUNK} 字。
     * 优先在句末切；句子太长就在逗号处切；实在没标点就硬切。
     */
    private static java.util.List<String> splitCaption(String t) {
        java.util.List<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            cur.append(c);
            boolean soft = "，,、；;：:".indexOf(c) >= 0;
            boolean hard = "。！？!?".indexOf(c) >= 0;
            if (cur.length() >= CAPTION_CHUNK
                    || (hard && cur.length() >= 10)
                    || (soft && cur.length() >= CAPTION_CHUNK * 2 / 3)) {
                String seg = cur.toString().trim();
                if (!seg.isEmpty()) out.add(seg);
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) out.add(tail);
        if (out.isEmpty()) out.add(t);
        return out;
    }

    private void dumpTracks(MediaPlayer p) {
        try {
            MediaPlayer.TrackInfo[] ts = p.getTrackInfo();
            if (ts == null) return;
            for (MediaPlayer.TrackInfo t : ts) {
                MediaFormat f = t.getFormat();
                String mime = f == null ? "?" : f.getString(MediaFormat.KEY_MIME);
                Log.i(TAG, "track type=" + t.getTrackType() + " mime=" + mime);
            }
        } catch (Throwable ignore) {}
    }

    /**
     * 按视频真实宽高比排版：等比缩放 + 留黑边，别把 16:9 的片子拉成 16:10。
     * 只把底部 84dp 控制栏的高度让出来；dock 已自动隐藏，不再额外让位。
     * 16:9 片子在 1280×800 上排成 1223×688，上下无黑边，左右各 28px。
     */
    private void applyAspect(int vw, int vh) {
        if (vw <= 0 || vh <= 0 || surface == null) return;
        int availW = getResources().getDisplayMetrics().widthPixels;
        int availH = getResources().getDisplayMetrics().heightPixels - dp(84) - barInset;
        float scale = Math.min((float) availW / vw, (float) availH / vh);
        int w = Math.round(vw * scale);
        int h = Math.round(vh * scale);
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = w;
        lp.height = h;
        lp.gravity = android.view.Gravity.CENTER_HORIZONTAL | android.view.Gravity.TOP;
        lp.topMargin = Math.max(0, (availH - h) / 2);
        surface.setLayoutParams(lp);
        Log.i(TAG, "aspect " + vw + "x" + vh + " -> " + w + "x" + h
                + " (avail " + availH + ", inset " + barInset + ")");
    }

    /**
     * 只在系统真报出底部 inset（标准导航栏）时才让位。
     * smartdock 的 dock 自动隐藏后 insets 恒报 0 → 画面满屏（历史坑见 DOCK_SAFE_BOTTOM_DP）。
     */
    private void applyLayoutInsets() {
        int inset = dp(DOCK_SAFE_BOTTOM_DP);
        try {
            Rect r = new Rect();
            getWindow().getDecorView().getWindowVisibleDisplayFrame(r);
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int fromFrame = screenH - r.bottom;
            int win = 0;
            try {
                android.view.WindowInsets wi =
                        getWindow().getDecorView().getRootWindowInsets();
                if (wi != null) {
                    win = Math.max(wi.getStableInsetBottom(), wi.getSystemWindowInsetBottom());
                }
            } catch (Throwable ignore) {}
            int sys = Math.max(fromFrame, win);
            if (sys > 0) inset = sys;
            if (inset < 0 || inset > dp(140)) inset = dp(DOCK_SAFE_BOTTOM_DP);
            Log.i(TAG, "inset: sys=" + sys + " -> use " + inset);
        } catch (Throwable ignore) {}
        if (inset == barInset) return;
        barInset = inset;
        View bar = findViewById(R.id.video_bar);
        if (bar != null) {
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) bar.getLayoutParams();
            lp.bottomMargin = inset;
            bar.setLayoutParams(lp);
        }
        Log.i(TAG, "bar inset=" + inset);
        if (mp != null && prepared) applyAspect(mp.getVideoWidth(), mp.getVideoHeight());
    }

    private int dp(float v) {
        return (int) android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /* ==================== 生命周期 ==================== */

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        // 刚才被盖住时暂停过 → 回来了接着放（用户没说停，不该丢进度）
        if (pausedByLeave && mp != null && prepared) {
            try {
                mp.start();
                userPaused = false;
            } catch (Throwable ignore) {}
        }
        pausedByLeave = false;
        sendEvent("front");
    }

    /**
     * 页面被盖住（回主界面 / 别的应用 / 小智前台）就暂停。
     *
     * 这不是礼貌，是必须：同一个 App 里的两个 MediaPlayer 不会互相礼让，
     * 视频在后台继续播、前台又起了一首歌，用户听到的就是两路声音叠在一起
     * （"播 mp4 的同时又在放 mp3"）。除了这里，见 MainActivity.playAnyTrack。
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (isFinishing()) return;
        if (mp != null && prepared) {
            try {
                if (mp.isPlaying()) {
                    mp.pause();
                    pausedByLeave = true;
                    Log.i(TAG, "paused by leave @" + safePos());
                }
            } catch (Throwable ignore) {}
        }
        // 告诉主界面「我不在前台了」：它要恢复 TTS 出声（视频模式会把音频链路静音），
        // 否则用户在聊天页问话时一声都听不到。
        sendEvent("back");
    }

    /**
     * 单实例复用（manifest 里 launchMode=singleTop）：
     * 主界面再点一个片子时不再叠一个 VideoActivity —— 两个实例各持一个 MediaPlayer
     * 就是两路声音。这里直接换源，页面始终只有一个。
     */
    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        setIntent(it);
        if (it == null) return;
        String p = it.getStringExtra("path");
        String s = it.getStringExtra("smb");
        String o = it.getStringExtra("ol");
        long osz = it.getLongExtra("olsize", 0);
        if ((p == null || p.isEmpty()) && (s == null || s.isEmpty())
                && (o == null || o.isEmpty())) return;
        String t = it.getStringExtra("title");
        title = (t == null || "null".equals(t)) ? "" : t;
        if (title.isEmpty()) {
            String n = (p != null && !p.isEmpty()) ? p
                    : (s != null && !s.isEmpty() ? s : o);
            int slash = n.lastIndexOf('/');
            title = slash >= 0 ? n.substring(slash + 1) : n;
        }
        localPath = p;
        smbUrl = s;
        olPath = o;
        olSize = osz;
        fallbackTried = false;
        remoteSize = 0;
        pausedByLeave = false;
        Log.i(TAG, "new intent title=" + title + " path=" + p + " smb=" + s + " ol=" + o);
        releasePlayer();
        if (surfaceReady) open();
    }

    /**
     * 沉浸式必须在窗口真正拿到焦点后才算数：onCreate 里设的那次，
     * 会被系统在 focus 变化时重新算一遍窗口装饰，导航条就又冒出来了 ——
     * 冒出来就压在 84dp 底栏上，把「退出/麦克风」按钮挡住（实测过）。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            immersive();
            applyLayoutInsets();
        }
    }

    /**
     * 触摸打日志：这台机器上有固件浮层抢触摸的历史，出问题时先看这行
     * 才能分清「事件没进来」还是「进来了但没打到控件」。
     * 只记按下/抬起，拖动过程的 MOVE 不记，免得刷屏。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        int a = ev.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP
                || a == MotionEvent.ACTION_CANCEL) {
            Log.i(TAG, "touch a=" + a + " at " + (int) ev.getX() + "," + (int) ev.getY());
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        finish();       // 退出视频 = 回到主界面，别让用户找不到路
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(ticker);
        ui.removeCallbacks(captionNext);
        try { unregisterReceiver(cmdReceiver); } catch (Throwable ignore) {}
        Intent i = new Intent(A_EVENT);
        i.putExtra("ev", "closed");
        i.setPackage(getPackageName());
        sendBroadcast(i);
        releasePlayer();
        super.onDestroy();
    }
}
