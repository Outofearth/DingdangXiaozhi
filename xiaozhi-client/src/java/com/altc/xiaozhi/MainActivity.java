package com.altc.xiaozhi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity
        implements XiaoZhiClient.Listener, AudioCapture.Callback {

    private static final String TAG = "MainActivity";
    private static final int REQ_REC = 1;
    /** 手动按住说话的最长时间。 */
    private static final long MAX_TALK_MS = 20000;
    /** 连续对话下一轮的最长时间（防止一直开着麦克风）。 */
    private static final long AUTO_MAX_MS = 15000;
    /** 连续对话：开口后多久没有声音就判定这一句说完。 */
    private static final long VAD_TAIL_MS = 1000;
    /** 连续对话：开麦后多久一直没声音就放弃（回到待机）。 */
    private static final long VAD_HEAD_MS = 9000;
    /** 判「有人说话」的 RMS 门限（峰值容易被瞬时噪声带偏，用 RMS 更稳）。 */
    private static final int VAD_RMS_SPEECH = 900;
    /** 连续多少帧超过门限才算真的开口（4 帧 = 240ms，过滤关门/碰撞声）。 */
    private static final int VAD_FRAMES_NEEDED = 4;

    /* ---- 基础控件 ---- */
    private TextView statusText;
    private Button talkBtn;
    private TextView btnContinuous;
    private ImageView btnCamera;
    private TextView settingsBtn;
    private LinearLayout micChip;
    private ImageView micIcon;
    private TextView micText;

    /* ---- 「正在播放」卡片（聊天流里的播放条 + 进度条） ---- */
    /** 聊天流末尾那块：标题一行 + 进度条，只在文件音乐播放时存在。 */
    private LinearLayout nowCard;
    private TextView nowCardTitle;
    private NowPlayingBar nowCardBar;
    /** 标题固定部分（▶ 歌名 · 来源），状态后缀（准备中/已暂停）每次刷新时拼。 */
    private String nowCardBase = "";
    /** 当前曲目来源（本机 / 电脑共享），页面重建后接回卡片时要用。 */
    private String nowSource = "";

    /* ---- 聊天区 ---- */
    private ScrollView chatScroll;
    private LinearLayout chatList;
    private TextView chatEmpty;

    /* ---- 运行时 ---- */
    private XiaoZhiClient client;
    private McpHandler mcp;
    private AudioCapture capture;
    private AudioPlayer player;
    /** 服务端 notify 下发的远程音频（音乐等），与 TTS 播放器相互独立。 */
    private NotifyPlayer notifyPlayer;
    /** 本地提示音（T3 兜底用）：没有 TTS，只能靠它把用户视线叫到屏幕上。 */
    private android.media.ToneGenerator tone;
    /** 服务端 play_music 逐句 TTS 下来的歌：本地缓存 + 断点续播。 */
    private final MusicSession music = new MusicSession();
    /** 进入「本地音乐管理」页时收到广播，停掉正在播的本地音乐，避免和试听抢声。 */
    private final BroadcastReceiver stopLocalReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            if (filePlaying) resetFileState();
            if (localPlayback) stopLocalPlayback();
        }
    };
    /** 本地曲库：听过的歌存成 PCM，之后完全本地播放，服务端怎么打断都不影响。 */
    private MusicLibrary library;
    /** 当前这首歌是不是在放本地曲库的版本（是则说话/调音量都不打断它）。 */
    private volatile boolean localPlayback = false;
    /** 本地音频文件（mp3/flac/wav…）扫描器。 */
    private LocalFiles localFiles;
    /** 远程音乐库（SMB / Windows 文件共享）。 */
    private RemoteLibrary remote;
    /** 网络盘：家里 PC 上的 OpenList 网关（夸克/阿里/百度/移动都挂在它下面）。 */
    private NetDiskLibrary netDisk;
    /** 本地文件播放器：MediaPlayer，pause/resume 是原生能力，不会「从头开始」。 */
    private FileMusicPlayer filePlayer;
    /** 当前有本地文件曲目（正在放或已暂停）。 */
    private volatile boolean filePlaying = false;
    /** 本地文件播放队列与下标（上一首/下一首用）。 */
    private java.util.List<PlayItem> fileQueue = new java.util.ArrayList<>();
    private int fileIndex = -1;
    /** 文件播放被一句提问让开了，答完要自动接上。 */
    private volatile boolean fileAutoResume = false;
    /**
     * 视频页（P2）：画面归 VideoActivity，语音仍归本页。
     *
     * 为什么不把麦克风和 WebSocket 搬进视频页：连接、ASR、MCP、TTS 全在这里，
     * 搬过去等于复制一整套状态机。视频页只负责「显示 + 暂停/继续」，两边用广播对话。
     */
    private volatile boolean videoMode = false;
    /** 用户显式说了「暂停」：答完不要自动接上（否则「暂停」形同虚设）。 */
    private volatile boolean videoHold = false;
    /** 视频连播队列（说「下一首」时用）。 */
    private java.util.List<PlayItem> videoQueue = new java.util.ArrayList<>();
    private int videoIndex = -1;
    /**
     * 最近一次「有哪些歌 / 有哪些视频」的清单。
     *
     * 用户听完清单接着说「播放第 3 个」时，直接按这里存的顺序取 —— 不然那句话里
     * 没有任何歌名，本地和云端都只能干瞪眼。3 分钟内有效（内容可能已经变了）。
     */
    private final java.util.List<MediaSurvey.Entry> lastSurvey = new java.util.ArrayList<>();
    private long lastSurveyAt = 0;
    private int lastSurveyKind = MediaKind.ANY;
    private static final long SURVEY_TTL_MS = 3 * 60 * 1000L;
    /** 主界面 → 视频页：pause / resume / exit。 */
    static final String A_VIDEO_CMD = "com.altc.xiaozhi.action.VIDEO_CMD";
    /** 主界面 → 视频页：字幕文本（extra = text）。 */
    static final String A_VIDEO_SUB = "com.altc.xiaozhi.action.VIDEO_SUB";
    /** 视频页 → 主界面：ev = front / closed / talk。 */
    static final String A_VIDEO_EVENT = "com.altc.xiaozhi.action.VIDEO_EVENT";
    /** SMB 直读失败时要退回「先下载」的那一条（给 FileMusicPlayer 的 onError 用）。 */
    private PlayItem streamFallbackItem = null;
    /** 视频页广播：进入 / 退出 / 请求开麦。 */
    private final BroadcastReceiver videoEventReceiver = new BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) {
            String ev = i.getStringExtra("ev");
            if (ev == null) return;
            switch (ev) {
                case "front":
                    videoMode = true;
                    enterVideoMode();
                    break;
                case "closed":
                    videoMode = false;
                    videoHold = false;
                    exitVideoMode();
                    break;
                case "back":
                    // 视频页只是退到后台（没关）：画面已自己暂停，这边恢复 TTS 出声，
                    // 但不把视频队列丢掉 —— 回视频页时能接着往下放。
                    videoMode = false;
                    videoHold = false;
                    player.setSilent(false);
                    break;
                case "talk":
                    persistStatus("视频页请求说话");
                    ui.post(() -> startTalking(true));
                    break;
                case "pos": {
                    // 视频页报回真实落点：云端不知道我们本地跳到哪了，这条确认由本地补
                    final String t = i.getStringExtra("title");
                    final int pos = i.getIntExtra("pos", 0);
                    final int dur = i.getIntExtra("dur", 0);
                    persistStatus("视频落点 " + MediaFormats.fmt(pos) + " / " + MediaFormats.fmt(dur));
                    runOnUiThread(() -> {
                        addEvent("» " + (t == null || t.isEmpty() ? "视频" : t)
                                + " 已跳到 " + MediaFormats.fmt(pos)
                                + (dur > 0 ? " / " + MediaFormats.fmt(dur) : ""));
                        statusText.setText("视频已放到 " + MediaFormats.fmt(pos));
                    });
                    break;
                }
                case "done":
                    ui.post(() -> onVideoDone());
                    break;
                default:
                    break;
            }
        }
    };
    /**
     * 本地已经接管了这条音乐/清单指令，这段时间内把服务端对同一句话的字幕吞掉
     * （它看不见本地/SMB，会回一句自相矛盾的“曲库里没有 / SMB 看不到”）。
     * 声音本来就靠 abort 掐掉了，这里连文字气泡也不让它冒出来。
     */
    private volatile long suppressAiUntil = 0;
    /**
     * 「只吞一条」的抑制窗口，给进度类指令用。
     *
     * 用户说「调到 35 分钟」时本地真的跳了，但云端对这句话只会回
     * 「我这边没有调整播放进度的功能」—— 和事实相反，必须吞掉；
     * 可 blank 抑制 {@link #suppressAiUntil} 是 3 秒硬窗口，云端走完一轮
     * ASR→LLM→TTS 往往已经超时，所以这里放宽到十几秒但只吃一条：
     * 用户紧接着问别的问题仍然能正常看到答案。
     */
    private volatile long suppressAiOnceUntil = 0;
    /**
     * P0：清单类指令先不抢话 —— 给云端一段时间去调用 {@code self.music.list}，
     * 它真调了就把清单念出来。存的是这段等待的截止时间戳（0 = 不在等）。
     */
    private volatile long listPendingUntil = 0;
    /** 本轮清单查询里云端是否真的调用了 self.music.list。 */
    private volatile boolean listToolCalled = false;
    /**
     * 清单回答轮次：云端的字幕**只出声、不上屏**，截止时间戳（0 = 不在这一轮里）。
     *
     * 本地在收到问句的瞬间就把带序号的清单贴上去了，云端读到同一份清单后又会
     * 用自己的话复述一遍（「好嘞，这台音箱上一共有 14 个音频…」），于是屏幕上
     * 出现两条内容几乎一样的回复 —— 用户直接指着屏幕说「有两条重复回复」。
     *
     * 嘴上的那份是好的（用户要的就是「看得出来有什么」），所以这里只拦字幕，
     * 不拦声音；屏幕上留本地那份带序号、能直接说「播放第几个」的清单。
     * 用户开口说下一句（{@link #onAsr}）或窗口自然到期即解除。
     */
    private volatile long listSilenceUntil = 0;
    /** 静默窗口的硬封顶：每句续期，但整轮最多静默这么久，防止云端一直说话永远不解除。 */
    private volatile long listSilenceCap = 0;
    /** 清单字幕静默窗口（毫秒）。云端念 14 条大概 25 秒，每来一句续一次。 */
    private static final long LIST_SILENCE_MS = 25000L;
    /** 一轮清单回答的字幕静默上限（毫秒）。 */
    private static final long LIST_SILENCE_CAP_MS = 120000L;
    /** 本地清单最后一次贴到屏幕上的时间；用来判断「这一轮本地有没有贴过」。 */
    private volatile long listPostedAt = 0;
    /** 云端这次要的清单类型（最近一次 {@code self.media.list} 的 kind）。 */
    private volatile int listToolKind = MediaKind.AUDIO;
    /**
     * 我们自己刚发给云端的文本（原文，已归一化）。
     *
     * <p>服务端会把 {@code sendTextQuery} 的内容以 {@code stt} 回显回来，那会被当成
     * 一次新的用户开口。原来只比对 {@link #lastListQuery}，回显是**自己那句话**时
     * 比对不上就当成「用户换话题」，把清单轮次的字幕静默提前拆掉 —— 云端复述的
     * 「A、B、C…」就漏到屏幕上，又是一大堆名字堆在一起（用户实测反馈）。
     */
    private volatile String selfSentKey = "";
    private volatile long selfSentAt = 0;
    /** 云端来取清单的等待上限（毫秒）。LLM 决策 + 往返一般 2-6 秒，给到 8 秒。 */
    private static final long LIST_TOOL_WAIT_MS = 8000;
    /**
     * T3：第一次等待没等到工具调用时，带**强指令**重试一次。
     *
     * 为什么需要：云端调不调工具完全由 LLM 决定，实测偶发整轮不调 ——
     * 那时用户听到的是一片安静，屏幕上明明有清单，体验上等于「设备死了」。
     * 重试把「问句」换成「指令」（明确点名 self.media.list），命中率高得多；
     * 还是不行就本地兜底（上屏 + 提示音），绝不静默。
     */
    private static final long LIST_RETRY_WAIT_MS = 6000;
    /** 最多尝试几轮（1 次原始问句 + 1 次强指令）。 */
    private static final int LIST_MAX_ATTEMPTS = 2;
    /** 当前是第几轮尝试（1 起）。 */
    private volatile int listAttempt = 0;
    /** 本轮清单查询的类型（重试/兜底都要用）。 */
    private volatile int listKind = MediaKind.ANY;
    /** 本轮清单查询的原始问句。 */
    private volatile String listQueryText = null;
    /** 重试发出的小指令原文：服务端会以 stt 回显，必须认出来并跳过，否则无限套娃。 */
    private volatile String listRetryText = "";
    private volatile long listRetryAt = 0;
    /** 清单查询去重：本地主动发出的文本会被服务端以 stt 回显，导致本地再跑一遍。 */
    private volatile String lastListQuery = "";
    private volatile long lastListAt = 0;
    /** 正在静默补流（重新请求同一首歌，只为把还没拿到的句子缓存下来）。 */
    private volatile boolean refilling = false;
    /** 补流请求已发出，但还没等到服务端回 play_music（这中间别的回复的 tts stop 不能当补流结束）。 */
    private volatile boolean refillActive = false;
    /** 补流开始时的缓存量，用来判断这一轮到底补到东西没有。 */
    private volatile long refillBase = 0;
    /** 续播时缓存不够 —— 先补流，补完自动接上。 */
    private volatile boolean autoResumeAfterRefill = false;
    /** 同一首歌连续补流失败的次数：补两次没进展就放弃，防止死循环。 */
    private volatile int refillFails = 0;
    /** 补流冷却截止时间（用户打断补流后，短时间内不要又自动补起来）。 */
    private volatile long refillSuppressUntil = 0;
    /** 补流看门狗的进展基线 / 连续无进展次数。 */
    private volatile long refillLastTotal = 0;
    private volatile int refillStall = 0;
    /**
     * 全双工模式：音乐正在放的时候用户开口，不打断音乐，只把音量压低。
     * 对应协议里 {@code listen mode=realtime}（ESP32 的 AEC 模式走的就是这条），
     * 实测服务端收到 realtime 监听后不会下发 tts stop，音乐照常继续。
     */
    private volatile boolean duplex = false;
    /** 全双工时把音乐压到多少（既保留「还在放」，又少干扰麦克风）。 */
    private static final float DUCK_GAIN = 0.15f;
    /** 正在从本地缓存续播。 */
    private volatile boolean resuming = false;
    private volatile boolean resumeAbort = false;
    /** 补流用的是文字查询，别把它的 stt 当成用户说的话显示。 */
    private volatile boolean silentQuery = false;

    private ChatStore store;
    private final List<ChatStore.Msg> history = new ArrayList<>();
    private ChatStore.Msg currentAiMsg;
    private TextView currentAiBubble;
    private boolean inAiTurn = false;

    /* ---- 聊天记录多选（长按一条 → 多选） ---- */
    private boolean selectMode = false;
    // 每条消息上方的时间戳格式：false=只显示「时分」，true=显示「年月日 时分」。
    // 触碰任意一条的时间戳会整体切换。
    private boolean showFullDate = false;
    private View selBar;
    private TextView selCount;
    /** 用对象身份做 key：文本相同的两条也不会被一起选中。 */
    private final java.util.Set<ChatStore.Msg> selected =
            java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<ChatStore.Msg, Boolean>());
    /** 每条消息对应的勾选圈，切换选中时直接改背景，不用整页重绘。 */
    private final java.util.Map<ChatStore.Msg, TextView> picks =
            new java.util.IdentityHashMap<>();

    /*
     * ⚠️ 已废弃的固定身份（曾经在这里写死一个已绑定的 device-id）。
     *
     * 好处是重装还能找回绑定，代价是**任何一台新设备装上这个 APK 都会继承同一个身份** →
     * 两台设备抢同一个 device-id 互相顶号，OTA 还会误判"已绑定"跳过验证码流程。
     *
     * 现在改为：首启生成随机 MAC，迁移/重装靠设置页「设备身份」填回原值（见 SettingsActivity）。
     * 已绑定设备的 SP 里本来就有 deviceId，不受此改动影响。
     */
    private String deviceId;
    private String clientId;
    private volatile boolean talking = false;
    private volatile boolean destroyed = false;

    /* ---- 软件层开关（持久化在 xz 里，由设置页写入） ---- */
    private volatile boolean continuous = false;
    private volatile boolean micEnabled = true;
    private volatile boolean cameraEnabled = true;

    /* ---- 连续对话的本地 VAD 状态 ---- */
    private volatile boolean autoListening = false;
    private volatile boolean voiceHeard = false;
    private volatile int voiceFrames = 0;
    private volatile long lastVoiceAt = 0;
    private volatile long autoStartAt = 0;

    private int pollCount = 0;
    private static final long POLL_INTERVAL_MS = 5000;
    private static final int POLL_MAX = 240;   // 20 分钟

    /** 当前已经生效的主界面布局（外观设置里改动后重建）。 */
    private int appliedLayout = 0;

    /** 按外观设置决定用哪套布局文件。 */
    private static int desiredLayout(SharedPreferences sp) {
        return AppearanceActivity.MODE_SPLIT.equals(
                sp.getString(AppearanceActivity.KEY_LAYOUT, AppearanceActivity.MODE_STACK))
                ? R.layout.activity_main_split
                : R.layout.activity_main;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable stopWatchdog = new Runnable() {
        @Override
        public void run() {
            if (talking) {
                persistStatus("看门狗：达到上限，自动结束录音");
                stopTalking();
            }
        }
    };

    /** 连续对话：等这一句播完再开麦（避免麦克风听到自己的声音）。 */
    private int continuousTries = 0;
    private final Runnable continuousListen = new Runnable() {
        @Override
        public void run() {
            if (destroyed || !continuous || !micEnabled || talking) return;
            if (refilling) return;                     // 补流期间不自激
            if (!client.isReady()) return;
            if (notifyPlayer != null && notifyPlayer.isPlaying() && continuousTries++ < 2000) {
                ui.postDelayed(this, 300);
                return;
            }
            if (player != null && !player.idleFor(450) && continuousTries++ < 25) {
                ui.postDelayed(this, 200);
                return;
            }
            continuousTries = 0;
            startTalking(true);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 调试：adb 启动时可直接写入远程音乐库配置（--es smb_host ... --es smb_share ... --ez smb_on true）
        android.content.Intent dbg = getIntent();
        if (dbg != null) {
            android.content.SharedPreferences.Editor dsp = getSharedPreferences("xz", MODE_PRIVATE).edit();
            if (dbg.hasExtra("smb_host"))  dsp.putString("smbHost", dbg.getStringExtra("smb_host"));
            if (dbg.hasExtra("smb_share")) dsp.putString("smbShare", dbg.getStringExtra("smb_share"));
            if (dbg.hasExtra("smb_user"))  dsp.putString("smbUser", dbg.getStringExtra("smb_user"));
            if (dbg.hasExtra("smb_pass"))  dsp.putString("smbPass", dbg.getStringExtra("smb_pass"));
            if (dbg.hasExtra("smb_on"))    dsp.putBoolean("smbEnabled", dbg.getBooleanExtra("smb_on", false));
            // 调试：直接指定「播完之后」的行为（0 续播 / 1 重播 / 2 停止），省得进设置页点
            if (dbg.hasExtra("play_mode")) dsp.putString(PlayMode.KEY, dbg.getStringExtra("play_mode"));
            dsp.apply();
        }
        appliedLayout = desiredLayout(getSharedPreferences("xz", MODE_PRIVATE));
        setContentView(appliedLayout);

        // 清空上一轮状态文件，保证本次落盘是最新结果
        try { new java.io.File(getExternalFilesDir(null), "status.txt").delete(); } catch (Exception ignore) {}

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        bindViews();

        store = new ChatStore(this);
        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
        // 待机悬浮入口：开 app 即浮出小智按钮。需「显示在其他应用上层」权限，
        // 安装后用 adb shell appops set com.altc.xiaozhi SYSTEM_ALERT_WINDOW allow 授予。
        if (sp.getBoolean("floatEntry", true)
                && (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M
                    || android.provider.Settings.canDrawOverlays(this))) {
            startService(new Intent(this, FloatEntryService.class));
        }
        if (!sp.contains("clientId")) {
            sp.edit().putString("clientId", UUID.randomUUID().toString()).apply();
        }
        if (!sp.contains("deviceId")) {
            // 首启身份：生成随机 MAC。绝不沿用某台已绑定设备的身份（会两台顶号）；
            // 也读不到本机真实 MAC（Android 6+ 应用层被屏蔽，恒返回 02:00:00:00:00:00）。
            // 重装后想找回原绑定，到设置页把原 device-id 填回去即可。
            String fresh = OtaClient.randomDeviceId();
            sp.edit().putString("deviceId", fresh).apply();
            Log.i(TAG, "首启：生成设备身份 " + fresh + "（需到 xiaozhi.me 绑定）");
        }
        clientId = sp.getString("clientId", "");
        deviceId = sp.getString("deviceId", "");
        String url = sp.getString("url", XiaoZhiClient.DEFAULT_URL);
        String token = sp.getString("token", OtaClient.PUBLIC_TOKEN);

        readToggles();
        renderHistory();

        client = new XiaoZhiClient(deviceId, clientId);
        client.setListener(this);
        client.setServer(url, token);

        mcp = new McpHandler(this, client, deviceId, clientId,
                s -> ui.post(() -> persistStatus("MCP: " + s)));
        // P0：把本地/SMB 清单暴露成 MCP 工具，云端调用后就能真的把歌名念出来
        mcp.setMusicProvider(new McpHandler.MusicProvider() {
            @Override
            public String libraryOverview(String kind) {
                return buildLibraryOverview(surveyKindOf(kind));
            }

            @Override
            public String playByQuery(String query) {
                return playByQueryFromMcp(query);
            }

            @Override
            public void onLibraryToolCalled(String kind) {
                noteLibraryToolCalled(kind);
            }
        });
        client.setMcpHandler(mcp);

        persistStatus("身份 " + client.describe());

        player = new AudioPlayer();
        library = new MusicLibrary(this);
        // 下行 PCM 全部过一遍缓存：放歌时把整首歌存下来，「继续播放」才有东西可续
        player.setPcmSink((pcm, n) -> music.appendPcm(pcm, n));
        notifyPlayer = new NotifyPlayer();
        // 本地音频文件（mp3/flac/wav/m4a/ogg…）：走 MediaPlayer，天然支持暂停续播
        localFiles = new LocalFiles(this);
        filePlayer = new FileMusicPlayer();
        capture = new AudioCapture(this);
        remote = new RemoteLibrary(this);
        netDisk = new NetDiskLibrary(this);
        // 音量：统一出口 + 盯住机身音量键（按键改了音量，界面立刻跟上）
        volume = new VolumeCtl(this);
        watchSystemVolume();
        // 远程音乐库清单预热：避免第一句语音点歌时才去网络拉
        new Thread(() -> remote.tracks(), "remote-prefetch").start();
        // 网络盘同样预热：网关要过 HTTP 逐层列目录，等用户开口再扫就晚了
        new Thread(() -> { try { netDisk.prewarm(); } catch (Throwable ignore) {} },
                "netdisk-prefetch").start();
        registerReceiver(stopLocalReceiver,
                new IntentFilter("com.altc.xiaozhi.action.STOP_LOCAL_MUSIC"));
        // 视频页（P2）：画面在那边，语音在这边，靠广播同步
        registerReceiver(videoEventReceiver, new IntentFilter(A_VIDEO_EVENT));

        // 按住说话：全程接管触摸序列，避免 Button 自己处理 MOVE/UP
        talkBtn.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    // 这里 return true 等于把整条事件序列吃掉了，View 自己的 onTouchEvent
                    // 不会执行 → pressed 状态永远不会被设上，circle_button 里的
                    // state_pressed 分支就白写了（按下颜色一点都不变）。必须手动置位。
                    v.setPressed(true);
                    v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    startTalking(false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                    if (!autoListening) stopTalking();
                    return true;
                default:
                    return true;
            }
        });

        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // 长按摄像头图标 = 本地拍照自检（不经过云端）
        btnCamera.setOnLongClickListener(v -> { testPhoto(); return true; });
        btnCamera.setOnClickListener(v -> {
            cameraEnabled = !cameraEnabled;
            getSharedPreferences("xz", MODE_PRIVATE).edit()
                    .putBoolean("cameraEnabled", cameraEnabled).apply();
            updateToggleUi();
            Toast.makeText(this, cameraEnabled ? "摄像头已开启（下一轮对话生效）"
                    : "摄像头已关闭", Toast.LENGTH_SHORT).show();
            persistStatus("摄像头软件开关=" + cameraEnabled);
        });

        btnContinuous.setOnClickListener(v -> {
            continuous = !continuous;
            getSharedPreferences("xz", MODE_PRIVATE).edit()
                    .putBoolean("continuous", continuous).apply();
            updateToggleUi();
            persistStatus("连续对话=" + continuous);
            if (continuous) {
                Toast.makeText(this, "连续对话已开启，可直接说话", Toast.LENGTH_SHORT).show();
                if (!talking) ui.post(continuousListen);
            } else {
                ui.removeCallbacks(continuousListen);
                if (autoListening) stopTalking();
                Toast.makeText(this, "连续对话已关闭", Toast.LENGTH_SHORT).show();
            }
        });

        micChip.setOnClickListener(v -> {
            micEnabled = !micEnabled;
            getSharedPreferences("xz", MODE_PRIVATE).edit()
                    .putBoolean("micEnabled", micEnabled).apply();
            if (!micEnabled) {
                ui.removeCallbacks(continuousListen);
                if (talking) stopTalking();
            }
            updateToggleUi();
            if (!talking) {
                statusText.setText(micEnabled
                        ? (continuous ? "连续对话已开启 · 直接说话" : "就绪")
                        : "麦克风已关闭 · 点底部图标开启");
            }
            if (micEnabled && continuous && !talking) {
                ui.removeCallbacks(continuousListen);
                continuousTries = 0;
                ui.postDelayed(continuousListen, 600);
            }
            Toast.makeText(this, micEnabled ? "麦克风已开启" : "麦克风已关闭（软件层）",
                    Toast.LENGTH_SHORT).show();
            persistStatus("麦克风软件开关=" + micEnabled);
        });

        // 点状态栏：未连上→重新注册；已连上→重连
        statusText.setOnClickListener(v -> {            if (talking) {
                stopTalking();
            } else if (!client.isReady()) {
                ui.removeCallbacks(pollTask);
                pollCount = 0;
                persistStatus("点击状态栏重新注册");
                startOta();
            } else {
                client.reconnectNow();
            }
        });

        // 长按状态栏 = 发一段文字直通服务端（无麦克风环境下验证 ASR→LLM→TTS 与连续对话）
        statusText.setOnLongClickListener(v -> {
            if (!client.isReady()) {
                Toast.makeText(this, "还没连上服务器", Toast.LENGTH_SHORT).show();
                return true;
            }
            sendTextToCloud("你好，用一句话介绍一下你自己");
            persistStatus("发送文字测试");
            return true;
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.CAMERA}, REQ_REC);
        } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_REC);
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            // 读 /sdcard/Music 里的本地音乐要用
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_REC);
        }

        startOta();
    }


    private void bindViews() {
        statusText = findViewById(R.id.status);
        talkBtn = findViewById(R.id.talk);
        btnContinuous = findViewById(R.id.btn_continuous);
        btnCamera = findViewById(R.id.btn_camera);
        settingsBtn = findViewById(R.id.settings);
        micChip = findViewById(R.id.mic_chip);
        micIcon = findViewById(R.id.mic_icon);
        micText = findViewById(R.id.mic_text);

        chatScroll = findViewById(R.id.chat_scroll);
        chatList = findViewById(R.id.chat_list);
        chatEmpty = findViewById(R.id.chat_empty);

        // 「记录」：进管理页（时间筛选 / 批量删除 / 导出）
        findViewById(R.id.btn_records).setOnClickListener(v -> {
            if (selectMode) exitSelectMode();
            startActivity(new android.content.Intent(this, ChatManagerActivity.class));
        });

        // 多选操作条
        selBar = findViewById(R.id.chat_sel_bar);
        selCount = findViewById(R.id.sel_count);
        findViewById(R.id.sel_all).setOnClickListener(v -> toggleSelectAll());
        findViewById(R.id.sel_copy).setOnClickListener(v -> copySelected());
        findViewById(R.id.sel_export).setOnClickListener(v -> exportSelected());
        findViewById(R.id.sel_del).setOnClickListener(v -> deleteSelected());
        findViewById(R.id.sel_cancel).setOnClickListener(v -> exitSelectMode());
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
        // 外观设置里换了排列方式：重建 Activity 用新布局（连接会在 onCreate 里自动恢复）
        int want = desiredLayout(sp);
        if (appliedLayout != 0 && want != appliedLayout) {
            appliedLayout = want;
            recreate();
            return;
        }
        // 设置页里点了「换绑设备」：重建 Activity，让新的 device-id 生效
        if (sp.getBoolean("rebindPending", false)) {
            sp.edit().putBoolean("rebindPending", false).apply();
            recreate();
            return;
        }
        boolean wasMic = micEnabled;
        boolean wasContinuous = continuous;
        readToggles();
        updateToggleUi();
        // 设置页里改了「额外扫描目录」：缓存失效，下次查询/清单按新目录重扫
        String dirs = sp.getString(LocalFiles.KEY_MEDIA_DIRS, "");
        if (appliedMediaDirs != null && !appliedMediaDirs.equals(dirs) && localFiles != null) {
            localFiles.invalidate();
            // 这里只记一条日志：重扫可能上千个文件，不能卡在 onResume 里
            persistStatus("扫描目录已更新，本地缓存已失效");
        }
        appliedMediaDirs = dirs;
        applyStartVolume(sp);
        if (wasMic && !micEnabled && talking) stopTalking();
        if (wasContinuous && !continuous) ui.removeCallbacks(continuousListen);
        // 聊天记录可能在设置页/记录管理页被清空或删过
        if (ChatStore.dirty) {
            ChatStore.dirty = false;
            if (selectMode) exitSelectMode();
            renderHistory();
        } else if (chatList.getChildCount() > 0 && store.load().isEmpty()) {
            renderHistory();
        }
        // 多设备同步：前台启动（按设置里的模式决定自动/手动），并把同步结果刷新回聊天界面
        startSyncIfNeeded();
    }

    // ==================== 多设备同步 ====================
    private final SyncManager.Callback syncCb = new SyncManager.Callback() {
        @Override public void onSyncDone(int added, int peerCount) {
            if (added > 0) {
                runOnUiThread(() -> {
                    renderHistory();
                    persistStatus("已从同账号设备同步 " + added + " 条聊天（共 " + peerCount + " 台在线）");
                });
            }
        }
        @Override public void onPeersChanged(int peerCount) {
            persistStatus("发现 " + peerCount + " 台同账号设备（同局域网）");
        }
    };

    private void startSyncIfNeeded() {
        SyncManager sm = SyncManager.init(MainActivity.this);
        sm.addCallback(syncCb);
        sm.start();
    }

    /** 本机产生新消息时，若同步开着，主动推/拉一次给同局域网伙伴。 */
    private void notifySyncPeers() {
        SyncManager sm = SyncManager.get();
        if (sm != null && sm.getMode() != SyncManager.OFF) sm.triggerSyncOnce();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SyncManager sm = SyncManager.get();
        if (sm != null) sm.removeCallback(syncCb);
    }

    /**
     * 启动时把音量设成用户设定的默认值（默认关闭）。
     *
     * 做成开关是因为：用硬件键调过音量的人，不希望每次开 App 又被拽回固定值。
     * 只在本进程第一次 onResume 时做，之后进出设置页不再动音量。
     */
    private void applyStartVolume(SharedPreferences sp) {
        if (startVolumeApplied) return;
        startVolumeApplied = true;
        if (!sp.getBoolean("volApplyOnStart", false)) return;
        if (volume == null) volume = new VolumeCtl(this);
        if (!volume.ok()) return;
        final int pct = Math.max(0, Math.min(100, sp.getInt("volDefault", 60)));
        if (!volume.setPct(pct)) {
            persistStatus("启动音量设置失败");
            return;
        }
        noteSystemVolumeTouch();
        persistStatus("启动音量 要求 " + pct + "% → 实际 " + volume.label());
    }

    private void readToggles() {
        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
        continuous = sp.getBoolean("continuous", false);
        micEnabled = sp.getBoolean("micEnabled", true);
        cameraEnabled = sp.getBoolean("cameraEnabled", true);
    }

    private void updateToggleUi() {
        btnContinuous.setBackgroundResource(
                continuous ? R.drawable.bg_pill_on : R.drawable.bg_pill_off);
        btnContinuous.setTextColor(continuous ? 0xFFFFFFFF : 0xFF8C8C8C);

        btnCamera.setBackgroundResource(
                cameraEnabled ? R.drawable.bg_cam_on : R.drawable.bg_cam_off);
        btnCamera.setColorFilter(cameraEnabled ? 0xFFFFFFFF : 0xFF9E9E9E);

        micIcon.setColorFilter(micEnabled ? 0xFF07C160 : 0xFFB0B0B0);
        micText.setText(micEnabled ? R.string.mic_on : R.string.mic_off);
        micText.setTextColor(micEnabled ? 0xFF1A1A1A : 0xFF8C8C8C);
        // 胶囊底色一起变（白 ↔ 浅灰）。只改图标和文字的话，站在两步外根本看不出收音关着。
        micChip.setBackgroundResource(micEnabled ? R.drawable.bg_chip : R.drawable.bg_chip_off);
        // 麦克风关了就把按钮压暗，别让人以为是故障
        talkBtn.setAlpha(micEnabled ? 1f : 0.45f);
    }

    /* ==================== 聊天区（微信风气泡，可上下滚动看历史） ====================
     *
     * 屏幕语义分三类，**别再混在一起**（曾经播放提示也写成 AI 气泡，看着像 AI 答了一句
     * 「♪ 世界顶级纯音乐八首3（电脑直读）」，根本分不清那是回答还是播放事件）：
     *   me    我说的 → 右对齐绿气泡
     *   ai    小智回答 → 左对齐白气泡
     *   event 播放/音量/失败 → 居中浅灰小条，前面带 ▶ ✕ 这类符号
     */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void renderHistory() {
        renderHistory(false);
    }

    /**
     * @param keepScroll true=保持当前滚动位置（触碰时间戳切换格式时用），
     *                   false=滚到底部（进页面/删除后）
     */
    private void renderHistory(boolean keepScroll) {
        int keepY = chatScroll.getScrollY();
        chatList.removeAllViews();
        picks.clear();
        history.clear();
        history.addAll(store.load());
        selected.retainAll(history);
        currentAiMsg = null;
        currentAiBubble = null;
        if (history.isEmpty()) {
            chatEmpty.setVisibility(selectMode ? View.GONE : View.VISIBLE);
            updateSelBar();
            restoreNowCard();      // 聊天区重建了，「正在播放」卡片要跟着接回来
            return;
        }
        chatEmpty.setVisibility(View.GONE);
        for (ChatStore.Msg m : history) {
            chatList.addView(buildBubbleRow(m));
        }
        updateSelBar();
        restoreNowCard();          // 重建后把还在播的那首接回卡片
        if (keepScroll) {
            // 内容重建前后高度几乎不变，直接把视口放回原处；
            // 不能走 scrollToBottom（它在 post 队列里会和这里的 scrollTo 抢执行顺序）
            chatScroll.post(() -> chatScroll.scrollTo(0, keepY));
        } else {
            scrollToBottom();
        }
    }

    /** 时间戳文案：触碰后整体切换「年月日 / 只时分」。 */
    private String fmtTime(long ts) {
        if (ts <= 0) return "时间未知";
        java.text.DateFormat f = showFullDate
                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                : new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(ts));
    }

    /** 触碰任意一条的时间戳：切换年月日显隐，整页一起刷新（保持当前滚动位置）。 */
    private void toggleDate() {
        showFullDate = !showFullDate;
        renderHistory(true);
    }

    private View buildBubbleRow(ChatStore.Msg m) {
        boolean me = m.isMe();
        boolean event = m.isEvent();

        // 两层结构：[勾选圈][气泡区]
        // 圈固定在最左侧，所有行共用同一条竖线；气泡的左/右/居中只在气泡区里生效，
        // 所以多选时圈能对齐，气泡也不会因为圈的出现被挤得换行。
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(event ? 3 : 4), 0, dp(event ? 3 : 4));

        // 勾选圈：只在多选模式显示；未选中时「✓」透明，看着就是个空心圈
        TextView pick = new TextView(this);
        LinearLayout.LayoutParams plp =
                new LinearLayout.LayoutParams(dp(24), dp(24));
        plp.gravity = Gravity.CENTER_VERTICAL;
        plp.setMarginStart(dp(8));
        plp.setMarginEnd(dp(6));
        pick.setLayoutParams(plp);
        pick.setGravity(Gravity.CENTER);
        pick.setText("✓");
        pick.setTextSize(14);
        applyPickStyle(pick, selected.contains(m));
        pick.setVisibility(selectMode ? View.VISIBLE : View.GONE);
        picks.put(m, pick);

        TextView tv = new TextView(this);
        tv.setText(m.text);
        tv.setLineSpacing(dp(3), 1f);
        int maxW;
        if (event) {
            tv.setTextSize(13);
            tv.setTextColor(0xFF5F5F5F);
            tv.setBackgroundResource(R.drawable.bg_event);
            maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.78f);
        } else {
            tv.setTextSize(17);
            tv.setTextColor(0xFF111111);
            tv.setBackgroundResource(me ? R.drawable.bg_bubble_user : R.drawable.bg_bubble_ai);
            maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
        }
        tv.setMaxWidth(maxW);
        // 关键：气泡宽度随文字长度（不撑满整行）；左右对齐由 content 的 gravity 决定。
        // 不设 LayoutParams 时默认会填满整行，导致「我说的」和「小智」都贴左、看起来一样宽。
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 点击/长按监听器：time、tv、row 都要挂，先定义好
        View.OnClickListener click = v -> {
            if (selectMode) toggleSelected(m);
        };
        View.OnLongClickListener hold = v -> {
            onBubbleLongPress(m, v);
            return true;
        };

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(event ? Gravity.CENTER_HORIZONTAL
                : (me ? Gravity.END : Gravity.START));
        content.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        // 每条消息上方的时间戳（像微信）。触碰它切换「带年月日 / 只时分」
        if (m.ts > 0) {
            TextView time = new TextView(this);
            time.setText(fmtTime(m.ts));
            time.setTextSize(12);
            time.setTextColor(0xFF9E9E9E);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = dp(2);
            time.setLayoutParams(tlp);
            time.setOnClickListener(v -> toggleDate());
            time.setOnLongClickListener(hold);
            content.addView(time);
        }
        content.addView(tv);

        row.addView(pick);
        row.addView(content);
        row.setTag(tv);

        row.setOnClickListener(click);
        row.setOnLongClickListener(hold);
        tv.setOnClickListener(click);
        tv.setOnLongClickListener(hold);

        if (!me && !event) {
            ChatStore.Msg last = history.isEmpty() ? null : history.get(history.size() - 1);
            if (last == m) {
                currentAiMsg = m;
                currentAiBubble = tv;
            }
        }
        return row;
    }

    private void applyPickStyle(TextView pick, boolean on) {
        pick.setBackgroundResource(on ? R.drawable.bg_pick_on : R.drawable.bg_pick_off);
        pick.setTextColor(on ? 0xFFFFFFFF : 0x00000000);
    }

    private void addMessage(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        addMessage(new ChatStore.Msg(role, text.trim()));
    }

    /**
     * 系统事件（播放、音量、失败提示…）。**不是回答**，只上屏、不进 AI 气泡，
     * 也不会被下一句 AI 字幕追加进去。
     */
    private void addEvent(String text) {
        if (text == null || text.trim().isEmpty()) return;
        addMessage(new ChatStore.Msg(ChatStore.ROLE_AI, text.trim(),
                System.currentTimeMillis(), ChatStore.KIND_EVENT));
    }

    private void addMessage(ChatStore.Msg m) {
        history.add(m);
        store.save(history);
        // 「正在播放」卡片始终压在聊天流最底下：新消息插到它上面，
        // 这样不论聊了多少句，进度条一直在眼前（否则说两句话它就被顶出屏幕）。
        final boolean cardOn = nowCard != null && nowCard.getParent() instanceof android.view.ViewGroup;
        if (cardOn) ((android.view.ViewGroup) nowCard.getParent()).removeView(nowCard);
        final android.view.View row = buildBubbleRow(m);
        chatList.addView(row);
        if (cardOn) chatList.addView(nowCard);
        chatEmpty.setVisibility(View.GONE);
        if (!m.isMe() && !m.isEvent()) {
            currentAiMsg = m;
            // 必须用刚建的那一行拿气泡：卡片贴回末尾后，最后一个 child 是卡片不是气泡
            currentAiBubble = (TextView) row.getTag();
        }
        scrollToBottom();
        notifySyncPeers();
    }

    /**
     * 本地自己生成的一条 AI 消息（清单、能力说明这类）。
     *
     * 不能只调 addMessage：那会把 {@link #currentAiMsg} 指到这条上，而云端上一条
     * 回答的后续句子此时可能还在流式下发，{@link #appendAiSentence} 就会把它们
     * 接在这条后面 —— 屏幕上于是出现「视频清单后面跟着音频清单的残句」这种缝合怪，
     * 看着就是「取到的文件信息不对」。
     */
    private void addLocalAiMessage(String text) {
        if (text == null || text.trim().isEmpty()) return;
        addMessage(ChatStore.ROLE_AI, text.trim());
        currentAiMsg = null;
        currentAiBubble = null;
    }

    /** AI 逐句下发：同一轮里追加到同一个气泡，避免一句话一个气泡。 */
    private void appendAiSentence(String sentence) {
        if (sentence == null) return;
        // 云端偶尔把清单改写成一大段（一个换行都没有），这里兜底拆成一行一条
        String s = MediaSurvey.reflowNumbered(sentence.trim());
        if (s == null || s.isEmpty()) return;
        if (currentAiMsg == null || currentAiBubble == null || currentAiMsg.isEvent()) {
            addMessage(ChatStore.ROLE_AI, s);
            return;
        }
        currentAiMsg.text = (currentAiMsg.text + s).trim();
        currentAiBubble.setText(currentAiMsg.text);
        store.save(history);
        scrollToBottom();
    }

    private void scrollToBottom() {
        chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    /* -------------------- 长按菜单 / 多选 -------------------- */

    private void onBubbleLongPress(final ChatStore.Msg m, View anchor) {
        if (selectMode) {
            toggleSelected(m);
            return;
        }
        final String[] items = {
                getString(R.string.menu_copy),
                getString(R.string.menu_multi),
                getString(R.string.menu_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(shortPreview(m))
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        ChatExport.copy(this, lineOf(m));
                        flashHint("已复制这条记录");
                    } else if (which == 1) {
                        enterSelectMode(m);
                    } else {
                        confirmDelete(java.util.Collections.singletonList(m));
                    }
                })
                .show();
    }

    private static String lineOf(ChatStore.Msg m) {
        if (m.isEvent()) return m.text;
        return (m.isMe() ? "我：" : "小智：") + m.text;
    }

    private static String shortPreview(ChatStore.Msg m) {
        String s = m.text == null ? "" : m.text.replace("\n", " ");
        return s.length() <= 18 ? s : s.substring(0, 18) + "…";
    }

    /** 底部那行提示是唯一稳定可见的反馈位（这台机器 Toast 合成后看不见）。 */
    private void flashHint(final String msg) {
        final TextView hint = findViewById(R.id.hint);
        if (hint == null) return;
        if (hint.getTag() == null) hint.setTag(hint.getText());
        hint.setText(msg);
        ui.removeCallbacks(restoreHint);
        ui.postDelayed(restoreHint, 2600);
    }

    private final Runnable restoreHint = new Runnable() {
        @Override
        public void run() {
            TextView hint = findViewById(R.id.hint);
            if (hint != null && hint.getTag() != null) hint.setText((CharSequence) hint.getTag());
        }
    };

    private void enterSelectMode(ChatStore.Msg initial) {
        selectMode = true;
        selected.clear();
        if (initial != null) selected.add(initial);
        for (java.util.Map.Entry<ChatStore.Msg, TextView> e : picks.entrySet()) {
            e.getValue().setVisibility(View.VISIBLE);
            applyPickStyle(e.getValue(), selected.contains(e.getKey()));
        }
        // 底部操作条是浮层，给列表补一段下边距，免得最后一条被压住
        chatList.setPadding(0, 0, 0, dp(64));
        selBar.setVisibility(View.VISIBLE);
        chatEmpty.setVisibility(View.GONE);
        updateSelBar();
    }

    private void exitSelectMode() {
        selectMode = false;
        selected.clear();
        for (TextView p : picks.values()) p.setVisibility(View.GONE);
        chatList.setPadding(0, 0, 0, 0);
        selBar.setVisibility(View.GONE);
        if (history.isEmpty()) chatEmpty.setVisibility(View.VISIBLE);
    }

    private void toggleSelected(ChatStore.Msg m) {
        if (!selected.remove(m)) selected.add(m);
        TextView p = picks.get(m);
        if (p != null) applyPickStyle(p, selected.contains(m));
        updateSelBar();
    }

    private void updateSelBar() {
        if (selCount == null) return;
        selCount.setText("已选 " + selected.size() + " 条");
    }

    private void toggleSelectAll() {
        if (selected.size() >= history.size()) {
            selected.clear();
        } else {
            selected.addAll(history);
        }
        for (java.util.Map.Entry<ChatStore.Msg, TextView> e : picks.entrySet()) {
            applyPickStyle(e.getValue(), selected.contains(e.getKey()));
        }
        updateSelBar();
    }

    /** 按历史顺序取出选中的记录（导出的顺序要跟屏幕上一致）。 */
    private List<ChatStore.Msg> selectedInOrder() {
        List<ChatStore.Msg> out = new ArrayList<>();
        for (ChatStore.Msg m : history) {
            if (selected.contains(m)) out.add(m);
        }
        return out;
    }

    private void copySelected() {
        List<ChatStore.Msg> list = selectedInOrder();
        if (list.isEmpty()) {
            flashHint("先长按消息选中几条");
            return;
        }
        ChatExport.copy(this, ChatExport.toText(list, "选中 " + list.size() + " 条"));
        flashHint("已复制 " + list.size() + " 条到剪贴板");
    }

    private void exportSelected() {
        List<ChatStore.Msg> list = selectedInOrder();
        if (list.isEmpty()) {
            flashHint("先长按消息选中几条");
            return;
        }
        java.io.File f = ChatExport.write(this, list, "选中 " + list.size() + " 条");
        flashHint(f == null ? "导出失败，换个位置再试"
                : "已导出 " + list.size() + " 条 → " + f.getAbsolutePath());
    }

    private void deleteSelected() {
        List<ChatStore.Msg> list = selectedInOrder();
        if (list.isEmpty()) {
            flashHint("先长按消息选中几条");
            return;
        }
        confirmDelete(list);
    }

    private void confirmDelete(final List<ChatStore.Msg> victims) {
        new AlertDialog.Builder(this)
                .setTitle("删除记录")
                .setMessage("删除 " + victims.size() + " 条记录？删掉就找不回来了。")
                .setPositiveButton("删除", (d, w) -> doDelete(victims))
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 删除记录。
     *
     * ⚠️ 一定要在**当前 history 列表**上删：store.load() 每次都会 new 出新对象，
     * 拿重新 load 出来的列表跟 victims 比对象身份永远对不上（踩过一次：提示删了 1 条，
     * 界面上那条还在）。
     */
    private void doDelete(java.util.Collection<ChatStore.Msg> victims) {
        int before = history.size();
        ChatStore.removeAll(history, victims);
        int removed = before - history.size();
        store.save(history);
        selected.removeAll(victims);
        renderHistory();
        if (selectMode && history.isEmpty()) exitSelectMode();
        flashHint(removed <= 0 ? "没有可删的记录（可能已被清空）" : "已删除 " + removed + " 条");
    }


    /* ==================== 官方 OTA 注册 / 激活 ==================== */

    private void startOta() {
        if (destroyed) return;
        statusText.setText("正在向官方服务器注册…");
        persistStatus("OTA 开始 device-id=" + deviceId + " client-id=" + clientId);
        new Thread(() -> {
            OtaClient.Result r = OtaClient.fetch(deviceId, clientId);
            ui.post(() -> onOtaResult(r));
        }, "ota").start();
    }

    private void onOtaResult(OtaClient.Result r) {
        if (destroyed) return;
        persistStatus("OTA 结果: " + r);

        if (r.error != null) {
            statusText.setText("注册失败（" + r.error + "），使用上次配置");
            client.reconnectNow();
            return;
        }

        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
        sp.edit().putString("url", r.wsUrl)
                .putLong("lastOtaAt", System.currentTimeMillis())
                .putBoolean("bound", !r.needActivation())
                .apply();

        if (r.needActivation()) {
            showActivation(r.activationCode);
            sp.edit().putString("token", r.token).apply();
            client.setServer(r.wsUrl, r.token);
            if (!client.isReady()) client.reconnectNow();
            pollCount = 0;
            ui.removeCallbacks(pollTask);
            ui.postDelayed(pollTask, POLL_INTERVAL_MS);
            return;
        }

        sp.edit().putString("token", r.token).apply();
        client.setServer(r.wsUrl, r.token);
        persistStatus("OTA 完成（已绑定），连接中 url=" + r.wsUrl);
        client.reconnectNow();
    }

    /** 未绑定时：把验证码作为一条系统消息放进聊天区（不刷屏大字）。 */
    private void showActivation(String code) {
        statusText.setText("未绑定 · 到 xiaozhi.me 添加设备并输入验证码");
        addLocalAiMessage(
                "设备还没有绑定。请到 xiaozhi.me 控制台添加设备，验证码：" + code);
        getSharedPreferences("xz", MODE_PRIVATE).edit().putString("lastCode", code).apply();
        persistStatus("等待绑定，验证码=" + code);
    }

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (destroyed) return;
            pollCount++;
            if (pollCount > POLL_MAX) {
                statusText.setText("等待绑定超时 · 点状态栏重新注册");
                persistStatus("绑定轮询超时");
                return;
            }
            new Thread(() -> {
                OtaClient.Result r = OtaClient.fetch(deviceId, clientId);
                final OtaClient.Result rr = r;
                ui.post(() -> {
                    if (destroyed) return;
                    boolean bound = rr.error == null && !rr.needActivation();
                    if (bound) {
                        persistStatus("绑定成功，已切换连接");
                        SharedPreferences sp = getSharedPreferences("xz", MODE_PRIVATE);
                        sp.edit().putString("url", rr.wsUrl).putString("token", rr.token)
                                .putBoolean("bound", true)
                                .putLong("lastOtaAt", System.currentTimeMillis()).apply();
                        client.setServer(rr.wsUrl, rr.token);
                        addLocalAiMessage("绑定成功，可以开始对话了。");
                        client.disconnect();
                        client.connect();
                    } else {
                        if (rr.needActivation() && rr.activationCode != null) {
                            showActivation(rr.activationCode);
                        }
                        ui.postDelayed(pollTask, POLL_INTERVAL_MS);
                    }
                });
            }, "ota-poll").start();
        }
    };

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        final int action = ev.getActionMasked();
        // 连续对话由 VAD 自己收尾，这里只兜底手动按住说话的 UP/CANCEL
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && talking && !autoListening) {
            persistStatus("兜底结束录音（dispatchTouchEvent）");
            stopTalking();
        }
        return super.dispatchTouchEvent(ev);
    }

    /* ==================== 说话 ==================== */

    private void startTalking(boolean auto) {
        if (notifyPlayer != null && notifyPlayer.isPlaying()) notifyPlayer.stop();

        // 音乐正在放 → 走全双工：音乐不停，只压低音量。
        // 本地曲库播放时更是如此：服务端停不停 TTS 都与本地播放无关。
        final boolean fileOn = filePlaying && filePlayer != null && filePlayer.isPlaying();
        final boolean localOn = (localPlayback && resuming) || fileOn;
        final boolean musicOn = music.isActive() && !music.isSuspended() && !resuming && !refilling
                && (music.isCollecting() || music.isRecentlyPlaying(60000));
        duplex = localOn || musicOn;

        if (duplex) {
            if (player != null) player.setGain(DUCK_GAIN);
            if (fileOn) filePlayer.setGain(DUCK_GAIN);       // 音乐继续，只是小声
        } else {
            if (resuming) stopResumeAndMark();                    // 停掉本地续播线程，断点前移
            if (refilling) cancelRefill("用户开口，打断补流");
            markMusicInterrupted();                               // 被打断也要记断点，否则「继续」会从头
            if (player != null) player.flush();
            if (client != null && client.isReady() && client.isSpeaking()) {
                client.sendAbort();
            }
        }
        if (talking) return;
        if (!micEnabled) {
            Toast.makeText(this, "麦克风已关闭，点底部麦克风图标开启", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!client.isReady()) {
            client.reconnectNow();
            Toast.makeText(this, "正在连接服务器，请 2 秒后再试", Toast.LENGTH_SHORT).show();
            persistStatus("未就绪时说话，已触发立即重连");
            return;
        }
        talking = true;
        autoListening = auto;
        voiceHeard = false;
        voiceFrames = 0;
        lastVoiceAt = 0;
        autoStartAt = System.currentTimeMillis();
        client.setBusy(true);
        persistStatus(auto ? "连续对话：自动开麦" : "聆听开始");
        // 输入/输出同时存在会让这台机器的 HAL 卡住 routing patch；
        // 全双工时音乐必须继续，所以只在非全双工下释放输出流。
        if (!duplex && player != null) player.releaseOutput();
        unmuteMic();
        statusText.setText(auto ? "我在听…（停顿 1 秒自动发送）"
                : (duplex ? "聆听中…（音乐继续）" : "聆听中…（松开结束）"));
        talkBtn.setText("聆听中");
        // 「聆听中」不只是换字：圆钮切到深绿（state_selected），远看也知道正在收音。
        // 连续对话自动开麦时也走这里，所以用户不用盯着字看状态。
        talkBtn.setSelected(true);
        client.sendListenStart(duplex ? "realtime" : "manual");
        if (videoMode) sendVideoSub("（我在听…）");
        new Thread(() -> {
            try { Thread.sleep(250); } catch (InterruptedException ignore) {}
            capture.start();
            Throwable err = capture.getLastError();
            if (err != null) {
                persistStatus("录音启动失败: " + err);
                ui.post(() -> {
                    Toast.makeText(this, "录音启动失败: " + err, Toast.LENGTH_LONG).show();
                    stopTalking();
                });
            }
        }, "capture-start").start();
        ui.removeCallbacks(stopWatchdog);
        ui.postDelayed(stopWatchdog, auto ? AUTO_MAX_MS : MAX_TALK_MS);
    }

    /**
     * 音乐正被服务端流式播放时被打断（说话 / 新指令）：记下断点。
     *
     * 之前缺了这一步，音乐断掉后 music 仍处于「未暂停」状态，
     * 用户说「继续播放」时走不到本地续播分支，只能让服务端把整首从头再唱一遍。
     */
    private void markMusicInterrupted() {
        if (!music.isActive() || music.isSuspended()) return;
        if (!(music.isCollecting() || music.isRecentlyPlaying(120000))) return;
        final long played = player.playedSamples();
        music.suspend(played);
        music.rewindForBuffer(player.getSampleRate() / 10);
        persistStatus("说话打断音乐 · 断点 " + music.pausePoint()
                + " 缓存 " + music.totalSamples());
    }

    private void unmuteMic() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            boolean before = am.isMicrophoneMute();
            if (before) {
                am.setMicrophoneMute(false);
            } else {
                am.setMicrophoneMute(true);
                am.setMicrophoneMute(false);
            }
            persistStatus("静音检查 before=" + before + " after=" + am.isMicrophoneMute());
        } catch (Throwable t) {
            persistStatus("静音检查异常 " + t);
        }
    }

    private void stopTalking() {
        if (!talking) return;
        talking = false;
        final boolean wasAuto = autoListening;
        final boolean wasDuplex = duplex;
        autoListening = false;
        ui.removeCallbacks(stopWatchdog);
        statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
        talkBtn.setText(getString(R.string.talk_hint));
        talkBtn.setSelected(false);
        // 全双工说完：把音乐音量放回原样（音乐一直都在放，只是被压低了）
        if (wasDuplex) {
            ui.postDelayed(() -> {
                if (destroyed || talking) return;
                player.setGain(1f);
                if (filePlayer != null) filePlayer.setGain(1f);
            }, 500);
        }
        // 视频页：用户按下麦克风后画面是停着的。若云端一直没开口（比如只是噪点、
        // 没识别出内容），4 秒后自己把画面放回来，别让用户对着静止画面干等。
        if (videoMode && !videoHold) {
            ui.postDelayed(() -> {
                if (destroyed || !videoMode || videoHold || talking) return;
                if (client != null && client.isSpeaking()) return;   // 有回答，等 onSpeakEnd 接
                sendVideoCmd("resume");
                persistStatus("视频：无回答，自动恢复画面");
            }, 4000);
        }
        new Thread(() -> {
            final Throwable[] err = {null};
            Thread stopper = new Thread(() -> {
                try { capture.stop(); } catch (Throwable t) { err[0] = t; }
            }, "capture-stop-inner");
            stopper.setDaemon(true);
            stopper.start();
            try { stopper.join(1500); } catch (InterruptedException ignore) {}
            if (stopper.isAlive()) {
                persistStatus("录音停止超时（HAL 无响应）");
            } else if (err[0] != null) {
                persistStatus("录音停止异常: " + err[0]);
            }
            boolean nothing = capture.gotNothing();
            persistStatus((wasAuto ? "连续对话录音结束 " : "录音结束 ") + capture.stats());
            client.sendListenStop(wasDuplex ? "realtime" : "manual");
            duplex = false;
            client.setBusy(false);
            if (nothing && !wasAuto) {
                ui.post(() -> {
                    statusText.setText("麦克风无数据");
                    Toast.makeText(this, "麦克风没有数据（音频 HAL 卡死）\n"
                            + "请断电冷启动后再试", Toast.LENGTH_LONG).show();
                });
            }
        }, "capture-stop").start();
    }

    /** 长按摄像头图标：本地拍一张，核对朝向/清晰度。 */
    private void testPhoto() {
        if (!cameraEnabled) {
            Toast.makeText(this, "摄像头已关闭，先点摄像头图标开启", Toast.LENGTH_SHORT).show();
            return;
        }
        persistStatus("测试拍照开始 · " + CameraCapture.describe());
        new Thread(() -> {
            try {
                byte[] jpeg = CameraCapture.captureJpeg(this);
                String extra = "";
                if (getSharedPreferences("xz", MODE_PRIVATE).getBoolean("keepPhoto", false)) {
                    java.io.File f = new java.io.File(getExternalFilesDir(null), "last_photo.jpg");
                    java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
                    fo.write(jpeg);
                    fo.close();
                    extra = " -> " + f.getAbsolutePath();
                }
                persistStatus("测试拍照成功 " + jpeg.length + " bytes" + extra);
                if (mcp != null && mcp.hasVision()) {
                    String ans = mcp.selfTest(jpeg, "画面里是什么？用一句话说");
                    persistStatus("视觉自检结果: " + (ans == null ? "失败" : ans));
                }
                final String msg = "已拍照 " + (jpeg.length / 1024) + " KB";
                ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                persistStatus("测试拍照失败: " + t);
                ui.post(() -> Toast.makeText(this,
                        "拍照失败: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "test-photo").start();
    }

    /* ---- XiaoZhiClient.Listener ---- */
    @Override
    public void onStatus(String s) {
        persistStatus(s);
        if (s.startsWith("空闲保活")) return;
        runOnUiThread(() -> {
            if (!s.startsWith("就绪") || !continuous) statusText.setText(s);
        });
        if (talking && (s.startsWith("已断开") || s.startsWith("连接失败")
                || s.startsWith("连接断开") || s.startsWith("未激活"))) {
            stopTalking();
        }
    }

    @Override
    public void onAsr(String text) {
        if (silentQuery) { silentQuery = false; return; }   // 补流的文字查询，不算用户说的话
        // T3：清单重试的那条短指令是我们自己发的，云端会以 stt 回显。
        // 它既不是用户说的，也不该在对话里冒气泡 —— 否则一次查询会多出两条
        // 「用工具查视频清单」，用户会以为自己在跟复读机说话。
        final String rt = listRetryText;
        if (rt != null && !rt.isEmpty() && text != null
                && System.currentTimeMillis() - listRetryAt < 20000
                && text.replace(" ", "").equals(rt)) {
            Log.i(TAG, "list: 忽略重试指令回显（不上屏）");
            return;
        }
        // 先把「我说的」上屏，再分发意图。
        // 各本地分支（播放/音量/进度）会立刻把**事件**上屏，顺序反了就会出现
        // 「先冒出 ▶ 正在播放，后出现我说的话」的错乱 —— 屏幕上读起来像设备抢话。
        runOnUiThread(() -> addMessage(ChatStore.ROLE_ME, text));
        // 用户真正换了个话题 → 解除上一轮「清单字幕只出声不上屏」。
        // 同一句话的回显（本地主动发出的文本会被服务端以 stt 回显）不算，
        // 否则刚装上静默就被自己那句回显拆掉，云端复述又冒出一条重复气泡。
        final String asrKey = text == null ? "" : text.replace(" ", "").replace("，", "")
                .replace("。", "").replace("？", "").replace("?", "")
                .replace("！", "").replace("!", "");
        if (listSilenceUntil > 0 && !asrKey.equals(lastListQuery) && !isSelfEcho(asrKey)) {
            // 别一刀切放开：云端这一轮清单很可能还在念，残句（「七. 《宋冬野》…」）
            // 往往就在一两秒后到。留 3 秒尾巴把它们吃掉，之后的才算新问题的回答。
            listSilenceUntil = Math.min(listSilenceUntil, System.currentTimeMillis() + 3000);
            Log.i(TAG, "list: 用户换话题，清单字幕静默收尾 3s");
        }
        // 音量指令走本地：原项目里 set_volume 就是设备侧能力（只调 codec 输出音量，
        // 不碰播放）。这里直接改系统音量，音乐一秒都不停，也不用等 ASR→LLM→MCP 往返。
        if (handleVolumeIntent(text)) {
            return;
        }
        // 视频页在前台：暂停/继续/切片/退出一律本地做 —— 服务端控制不了这台机器的画面
        if (videoMode) {
            sendVideoSub("你：" + text);
            if (handleVideoVoiceIntent(text)) {
                return;
            }
        }
        // 进度：「跳到 2 分钟」「快进 30 秒」「放到一半」—— 视频 / 本地文件 / 云端缓存都能挪。
        // 必须排在点播之前：这句话里没有歌名，交给 tryLocalPlay 只会白找一轮。
        if (handlePositionIntent(text)) {
            return;
        }
        // 「播放第 3 个 / 第 5 首」—— 从当前队列或最近一次清单里按序号选
        if (handlePickIntent(text)) {
            return;
        }
        // 用户提了本机解不了的格式（avi / rmvb / flv…）：本地讲清楚，别绕到云端瞎猜
        if (handleFormatHint(text)) {
            return;
        }
        // 「查第 3 个 / 看看邓紫棋 / 返回上一层 / 下一批」—— 网络盘一层一层翻。
        // 必须排在清单问法之前：网络盘现在只报**当前这一层**，而「查邓紫棋」如果漏给
        // handleLibraryQuery，会被当成「把五个位置全列一遍」，进不去那个文件夹。
        if (handleNetNavIntent(text)) {
            return;
        }
        // 「有哪些歌 / 看看有什么音乐」必须排在**点播之前**：
        // 「音乐」「歌曲」是泛称，可共享盘里偏偏有《世界顶级纯音乐八首》《1920-1950 年中國經典歌曲》，
        // 于是「看看有什么音乐」被当成歌名匹配、直接开播，用户要的清单反倒没出来。
        if (handleLibraryQuery(text, false)) {
            return;
        }
        // 「播放XX」如果本地有，就直接本地播 —— 服务端那路流被 VAD 打断也不影响
        if (tryLocalPlay(text)) {
            return;
        }
        // 本地文件正在放：上一首 / 下一首 / 暂停 / 继续 全在本地处理
        if (handleFileIntent(text)) {
            return;
        }
        // 本地正在放，但这句是别的事（提问/聊天）：先让开，免得盖住服务端的回答
        if (localPlayback && resuming && !isMusicCommand(text)) {
            stopLocalPlayback();
        }
        if (filePlaying && !isMusicCommand(text)) {
            pauseFileForTalk();
        }
        handleMusicIntent(text);
    }

    /**
     * 「有哪些歌 / 有哪些视频 / 都能放什么」——把**五个位置**一起扫一遍再报。
     *
     * 五个位置是本机文件、家里共享文件夹（SMB）、网络盘（OpenList 网关）、
     * 云端唱过并缓存的曲目、下载缓存。
     * 只查一处等于骗人：用户明明拷在 Movies 里，回答说「没有视频」就是不干活。
     * 清单带全局序号，用户听完可以直接说「播放第 5 个」（见 {@link #handlePickIntent}）。
     *
     * @param forward 是否还要把这句话发给服务端：
     *                真实语音路径下 ASR 在云端做，云端本来就收到了（false）；
     *                adb 调试路径必须自己发，否则云端没听见、也就不会调用 self.music.list。
     */
    /** 归一化：去空白与常见标点，用来比对「这句话是不是我自己发出去的」的回显。 */
    private static String normKey(String t) {
        if (t == null) return "";
        return t.replace(" ", "").replace("\u3000", "").replace("，", "").replace("。", "")
                .replace("？", "").replace("?", "").replace("！", "").replace("!", "")
                .replace("、", "").replace("：", "").replace(":", "");
    }

    /**
     * 发一句文本给云端，并记下来。
     *
     * <p>服务端会把内容以 {@code stt} 回显，那会被当成一次新的用户开口 ——
     * 不认出来就会把清单轮次的字幕静默当成「用户换话题」提前解除。
     */
    private void sendTextToCloud(String text) {
        if (text == null || text.trim().isEmpty()) return;
        selfSentKey = normKey(text);
        selfSentAt = System.currentTimeMillis();
        if (client != null) client.sendTextQuery(text);
    }

    /** 这句「用户输入」是不是我们自己刚（60 秒内）发出去的文本的回显。 */
    private boolean isSelfEcho(String asrKey) {
        if (asrKey == null || asrKey.isEmpty() || selfSentKey.isEmpty()) return false;
        if (System.currentTimeMillis() - selfSentAt > 60000) return false;
        return normKey(asrKey).equals(selfSentKey);
    }

    /* ==================== 网络盘：一层一层看 ==================== */

    /*
     * 网盘跟本机、共享盘不一样：它是一棵树，根上 22 个文件夹，第二层就 2676 个文件
     * （「经典老歌1677首」一个目录 1677 条）。把整棵树压成一份清单，念不完也没法挑，
     * 所以这里给人一条「翻网盘」的路：
     *     说「查第 3 个」/「看看邓紫棋」 → 进那层，报这一层的文件夹 + 文件
     *     说「下一批」                 → 这一层还有 1600 多个时翻页
     *     说「返回上一层」/「回到根目录」→ 退回去
     * 而「放杜十娘」这种**说名字直接放**走的是另一条路（全量索引），不进目录也能播。
     *
     * 报清单本身复用 handleLibraryQuery：那套「本地版式 + 云端念 + 静默字幕」已经调好，
     * 另起一套只会两边不一致。
     */

    /**
     * 句子里有没有「翻目录」的意思。
     *
     * 必须窄：宽一点就会把「放轻音乐」当成「进轻音乐文件夹」，
     * 用户想听歌却只看到一串文件名。
     */
    private boolean handleNetNavIntent(String text) {
        if (text == null || netDisk == null || !netDisk.enabled()) return false;
        if (text.length() > 24) return false;                 // 长句多半是在聊天，不是在翻目录
        final String s = MediaFormats.norm(text);
        if (s.length() < 2) return false;

        // 1) 退回去
        if (containsAny(s, new String[]{"返回上一层", "返回上一级", "返回上级", "上一级", "上一层",
                "退回去", "退回上一层", "上级目录", "回到根", "回到最外层", "回到最开始",
                "返回根目录", "回根目录"})) {
            if (netDisk.atRoot()) {
                localHint("已经是最外面了，这里就是根目录。");
                return true;
            }
            if (netDisk.up()) {
                enterNetDir(netDisk.curPath());
                return true;
            }
            return false;
        }

        // 2) 翻页：这一层超过一页（20 条）时才有意义
        if (netDisk.pageCount() > 1 && containsAny(s, new String[]{
                "下一批", "下一页", "翻页", "后面的", "往下翻", "再看一些", "接着念"})) {
            if (!netDisk.nextPage()) {
                localHint("已经是最后一批了。说「回到根目录」可以重新开始。");
                return true;
            }
            reportNetDir();
            return true;
        }
        if (netDisk.pageCount() > 1 && containsAny(s, new String[]{"上一批", "前一页", "往回翻"})) {
            if (!netDisk.prevPage()) {
                localHint("已经是第一批了。");
                return true;
            }
            reportNetDir();
            return true;
        }

        // 3) 进目录：要有「翻」的动作词，否则「放邓紫棋」会被抢走
        final boolean navVerb = containsAny(s, new String[]{"查", "看", "打开", "进入", "进去",
                "浏览", "翻", "里面", "里头"});
        if (!navVerb) return false;
        // 去掉动作词和语气词再找目录名：「查一下邓紫棋」→「邓紫棋」。
        // 名字里可能本来就有「的」（「那些你熟悉却又不知道名字的轻音乐」），
        // 所以只去掉固定的那几个词，不用正则大扫。
        String key = s;
        for (String w : new String[]{"查一下", "查查", "查看", "查", "看一下", "看看", "看下", "看",
                "打开一下", "打开", "进入", "进去", "浏览", "翻翻", "翻", "里面有什么", "里面",
                "里头", "文件夹", "目录", "一下", "都有什么", "有什么", "有哪些", "什么"}) {
            key = key.replace(w, "");
        }
        NetDiskLibrary.Dir d = key.length() >= 2 ? netDisk.findDir(key) : null;
        if (d == null) d = netDisk.findDir(s);        // 兜底：整句再试一次（aliasScore 认得子串）
        if (d != null) {
            enterNetDir(d.path);
            return true;
        }
        // 说了「文件夹/目录」但没点出具体名字：把这一层再报一遍
        if (s.contains("文件夹") || s.contains("目录")) {
            reportNetDir();
            return true;
        }
        return false;
    }

    /**
     * 进一个网盘目录：切目录 → 后台列（1677 个文件要 4 秒）→ 报这一层。
     *
     * 列目录一定要在后台：主线程做网络会直接卡死界面（共享盘踩过 NetworkOnMainThreadException）。
     */
    private void enterNetDir(final String path) {
        if (netDisk == null) return;
        netDisk.cd(path);
        final String name = netDisk.curName();
        final String label = name.isEmpty() ? "根目录" : name;
        if (client != null && client.isReady()) client.sendAbort();
        suppressAiUntil = System.currentTimeMillis() + 2500;
        runOnUiThread(() -> addEvent("📁 进入「" + label + "」"));
        persistStatus("网络盘：进入「" + label + "」，正在读取…");
        new Thread(() -> {
            String err = null;
            try {
                netDisk.list(netDisk.curPath());
            } catch (Throwable t) {
                err = String.valueOf(t);
            }
            final String e = err;
            ui.post(() -> {
                if (destroyed) return;
                if (e != null) {
                    localHint("「" + label + "」打不开：" + e);
                    return;
                }
                reportNetDir();
            });
        }, "ol-dir-nav").start();
    }

    /**
     * 网盘当前这一层一个能放的都没有时，自动退回根目录。
     *
     * <p>什么时候会出现这种层：根上「现代民谣排行榜100首」「二胡曲谱」「流行歌曲-简谱」
     * 这些目录里全是曲谱图片（100 个文件，0 音频、0 视频、0 子目录）。一旦停在里面，
     * 清单里网络盘那组就永远只剩一句「另有 100 个放不了的」—— 用户看到的现象是
     * 「网盘里的东西拿不到了」（真机实测，10-31）。
     *
     * <p>只退**空壳层**：这一层如果还有子目录（dirs &gt; 0），那是可以继续往下翻的正常目录，
     * 不动它；列目录还没回来（listing 为 null）也不猜，免得把刚进去的目录又弹出来。
     *
     * @return 被退出的目录名；没动过返回 null
     */
    private String escapeEmptyNetDir() {
        if (netDisk == null || !netDisk.enabled() || netDisk.atRoot()) return null;
        NetDiskLibrary.Listing ls = null;
        try {
            ls = netDisk.listing();
        } catch (Throwable ignore) {
        }
        if (ls == null) return null;
        if (ls.playable > 0 || ls.videos > 0 || ls.dirs > 0) return null;
        final String left = netDisk.curName();
        netDisk.cdRoot();
        persistStatus("网络盘「" + left + "」里没有能放的，自动回到根目录");
        runOnUiThread(() -> addEvent("📁「" + left + "」里都是放不了的文件，已回到根目录"));
        return left;
    }

    /**
     * 把当前目录这一层报出来（上屏本地版式 + 让云端念）。
     *
     * 复用 {@link #handleLibraryQuery}：它已经处理了「云端调工具 → 念完清单 → 静默复述字幕」，
     * 自己再拼一套只会出现两份不一样的清单。
     */
    private void reportNetDir() {
        try {
            // 用户没说过这句话，但云端得有个由头才会去调 self.media.list 把这一层念出来
            // （本地没有 TTS，声音只能靠云端）。默认问「歌」；但进的是纯视频目录
            // （比如「Zan」里三个 mp4）时，问歌只会得到一句「这里没有音频」——
            // 那种目录要问「视频」，云端才会把片子念出来、序号也才对得上。
            // 「歌」还是「视频」见下；**目录名一定要念进去**。
            // 以前固定是「网盘里有哪些歌」，进第二个目录时云端以为「这句刚问过」，
            // 一句工具都不调，直接复述上一层的答案（实测：进了「邓紫棋」，屏幕上 43 个，
            // 它嘴上还在说「网盘根目录 22 个文件夹，一共 40 个」）。
            final String where = netDisk.atRoot() ? "根目录" : netDisk.curName();
            String q = "网盘" + (netDisk.atRoot() ? "根目录" : "「" + where + "」") + "里有哪些歌";
            NetDiskLibrary.Listing ls = netDisk.listing();
            if (ls != null && ls.playable == 0 && ls.videos > 0) {
                q = "网盘" + (netDisk.atRoot() ? "根目录" : "「" + where + "」") + "里有哪些视频";
            }
            handleLibraryQuery(q, true);
        } catch (Throwable t) {
            Log.e(TAG, "reportNetDir failed", t);
        }
    }

    private boolean handleLibraryQuery(String text, boolean forward) {        if (text == null) return false;
        final String s = text.replace(" ", "").replace("，", "").replace("。", "")
                .replace("？", "").replace("?", "").replace("！", "").replace("!", "");
        // T3：重试用的短指令是我们自己发出去的，服务端会以 stt 把它回显回来。
        // 它同样含「清单」两个字，不认出来就会再触发一轮查询 —— 套娃。
        // 用整句比对（而不是某个标记词），避免误吞用户真说的话。
        final String rt = listRetryText;
        if (rt != null && !rt.isEmpty() && s.equals(rt)
                && System.currentTimeMillis() - listRetryAt < 20000) {
            Log.i(TAG, "list: 忽略自己发的重试指令回显");
            return true;
        }
        // 明确的清单问法（音频）
        final boolean namedAudio = s.contains("有哪些歌") || s.contains("有什么歌")
                || s.contains("哪些音乐") || s.contains("本地音乐") || s.contains("曲库")
                || s.contains("歌单") || s.contains("会唱什么") || s.contains("能放什么");
        // 明确的清单问法（视频）
        final boolean namedVideo = s.contains("有哪些视频") || s.contains("有什么视频")
                || s.contains("哪些视频") || s.contains("有什么电影") || s.contains("有哪些电影")
                || s.contains("哪些片子") || s.contains("有什么片子") || s.contains("有哪些片")
                || s.contains("能看什么") || s.contains("有哪些剧") || s.contains("有什么剧");
        // 泛化的「有什么/有哪些 + 媒体语境」：覆盖「看看本地有什么音乐」
        // 「看看用SMB连接的本地文件夹里有什么」「都有什么能播的」这类说法。
        // ⚠️ 「能播 / 能放 / 播的 / 放的 / 听得 / 看得」一定要留在这儿：
        // 少了它们，「都有什么能播的」就漏到云端去了 —— 云端会把清单改写成一大段，
        // 屏幕上一堆名字堆在一起（用户实测反馈「格式又回到堆在一起的状态」）。
        final boolean ask = s.contains("有什么") || s.contains("有哪些")
                || s.contains("哪些") || s.contains("清单") || s.contains("列表")
                || s.contains("都有什么") || s.contains("都有啥");
        final boolean ctx = s.contains("歌") || s.contains("音乐") || s.contains("曲")
                || s.contains("文件夹") || s.contains("共享") || s.contains("歌单")
                || s.contains("媒体") || s.toLowerCase().contains("smb")
                || s.contains("能播") || s.contains("能放") || s.contains("可播")
                || s.contains("播的") || s.contains("放的") || s.contains("能听")
                || s.contains("能看") || s.contains("听什么") || s.contains("看什么")
                || s.contains("好听") || s.contains("好看")
                || s.contains("文件") || s.contains("目录") || s.contains("本机")
                || s.contains("电脑") || s.contains("缓存") || s.contains("曲库")
                // 「网盘里有什么 / 网络盘有什么」——这是这一版最自然的问法，
                // 少了这几个词它会被当成闲聊丢给云端，而云端只会瞎答。
                || s.contains("网盘") || s.contains("云盘") || s.contains("网络盘");
        final boolean videoCtx = s.contains("视频") || s.contains("电影") || s.contains("片子")
                || s.contains("影片") || s.contains("电视") || s.contains("短片")
                || s.contains("媒体") || s.contains("缓存");
        if (!namedAudio && !namedVideo && !(ask && (ctx || videoCtx))) return false;
        // 同一句话 2 秒内只处理一次：本地主动发出的文本会被服务端以 stt 回显，
        // 那句话会再走一遍 onAsr，导致清单插两份、abort 也发两轮。
        final long now = System.currentTimeMillis();
        if (s.equals(lastListQuery) && now - lastListAt < 2000) {
            Log.i(TAG, "list: 同一句话 2s 内重复，忽略");
            return true;
        }
        lastListQuery = s;
        lastListAt = now;
        // 排除「这首歌叫什么 / 谁唱的 / 有什么故事」这类问内容、不是要清单的说法
        if (s.contains("叫什么") || s.contains("谁唱") || s.contains("什么意思")
                || s.contains("歌词") || s.contains("故事") || s.contains("为什么")
                || s.contains("什么时候") || s.contains("多久")) {
            return false;
        }
        // 这次问的是音频、视频，还是都要
        final int kind = surveyKind(s);
        // 网盘这一层要是只剩曲谱图片（一个能放的都没有），别在这儿干耗 —— 直接退回根目录。
        // 用户问「网络盘有哪些文件」，该听到的是根上那 22 个文件夹，而不是「你站在一个
        // 图片目录里、这一层没有能放的」。实测他因此认定「网盘里的东西拿不到了」。
        escapeEmptyNetDir();
        // 五个位置一起扫（本机 → 共享文件夹 → 网络盘 → 云端缓存 → 下载缓存），按类型过滤 + 去重
        final java.util.List<MediaSurvey.Entry> items = buildSurvey(kind);
        // 存下来：用户下一句很可能是「播放第 3 个」，那句话里没有任何名字可匹配
        rememberSurvey(items, kind);
        final String overview = MediaSurvey.overview(items, kind, LIST_SCREEN_CHARS);
        // P0/T3：不再立刻 abort。先给云端 LIST_TOOL_WAIT_MS 的时间去调 self.media.list：
        // 真调了（noteLibraryToolCalled 会清掉等待）就让它把清单念完；
        // 期间它若开始说「看不到 / 没有」这类瞎答，onAiSentence 里的哨兵会就地掐掉；
        // 一直不调也不说话 → onListAttemptFailed 带强指令重试一次 → 仍不行则本地兜底。
        awaitListTool(kind, text, 1);
        if (forward) {
            final String q = text;
            ui.postDelayed(() -> {
                if (destroyed) return;
                if (client != null && client.isReady()) {
                    markMusicInterrupted();
                    sendTextToCloud(q);
                }
            }, 800);
        }
        listPostedAt = System.currentTimeMillis();
        runOnUiThread(() -> addLocalAiMessage(overview));
        persistStatus("媒体清单（" + kindName(kind) + "）：" + items.size() + " 个");
        return true;
    }

    /** 清单类型 → 中文（状态栏/日志用）。 */
    private static String kindName(int kind) {
        return kind == MediaKind.VIDEO ? "视频" : kind == MediaKind.AUDIO ? "音频" : "全部";
    }

    /**
     * 这句话问的是哪一类：音频 / 视频 / 都要。
     *
     * 「有哪些歌和视频」两个都提到 → ANY（两头都列出来，别自作主张）；
     * 「有哪些 mp4」→ 格式词直接定成视频。
     */
    private static int surveyKind(String s) {
        if (s == null) return MediaKind.ANY;
        boolean v = s.contains("视频") || s.contains("电影") || s.contains("片子")
                || s.contains("影片") || s.contains("电视") || s.contains("短片");
        boolean a = s.contains("歌") || s.contains("音乐") || s.contains("曲")
                || s.contains("音频");
        String ext = MediaFormats.extFromUtterance(s);
        if (ext != null) {
            if (MediaFormats.isVideoExt(ext)) v = true;
            else if (MediaFormats.isAudioExt(ext)) a = true;
        }
        if (v && !a) return MediaKind.VIDEO;
        if (a && !v) return MediaKind.AUDIO;
        return MediaKind.ANY;
    }

    /** MCP 参数 audio / video / all → {@link MediaKind}。 */
    private static int surveyKindOf(String kind) {
        if (kind == null) return MediaKind.ANY;
        if ("video".equalsIgnoreCase(kind)) return MediaKind.VIDEO;
        if ("audio".equalsIgnoreCase(kind)) return MediaKind.AUDIO;
        return MediaKind.ANY;
    }

    /** 扫五个位置（本机 / 共享文件夹 / 网络盘 / 云端缓存 / 下载缓存）。 */
    private java.util.List<MediaSurvey.Entry> buildSurvey(int kind) {
        try {
            return MediaSurvey.build(this, localFiles, remote, netDisk, library, kind);
        } catch (Throwable e) {
            Log.e(TAG, "buildSurvey failed", e);
            return new java.util.ArrayList<>();
        }
    }

    /**
     * 云端确实来取清单了：取消等待、放开 TTS；必要时**本地补一份清单上屏**。
     *
     * <p>为什么需要「补一份」：本地 {@link #handleLibraryQuery} 认不出用户那句话时
     * （例如「都有什么能播的」这种说法），请求会整轮交给云端。云端调了
     * {@code self.media.list} 之后会用自己的话把结果**改写成一大段**
     * （一条一行的换行全被它揉成逗号），屏幕上就成了「全堆在一起」（用户实测反馈）。
     * 所以只要这一轮本地没贴过清单，就按云端要的类型在本地拼一份贴上去，
     * 并把云端那段复述静默掉 —— 内容一样，版式是「一条一行」的那份。
     *
     * @param kind 云端要的类型（"audio"/"video"/"all"）；{@code null} 表示这次调的不是清单工具（如播放）。
     */
    private void noteLibraryToolCalled(String kind) {
        listToolCalled = true;
        listPendingUntil = 0;
        listAttempt = 0;
        suppressAiUntil = 0;
        if (kind == null) {                     // self.music.play / self.media.play：不是清单轮次
            Log.i(TAG, "list: 云端调用了播放工具，放行 TTS");
            return;
        }
        final int k = surveyKindOf(kind);
        listToolKind = k;
        final long now = System.currentTimeMillis();
        // 本地这一轮贴过清单就不用再贴；窗口给到 30 秒 —— 云端要等「重试那轮」才会
        // 调工具（本地等待 8s + 重试 8s），卡在 8 秒会贴出第二份一模一样的清单。
        if (now - listPostedAt > 30000) {
            // 先把云端复述的字幕压住，再慢慢去扫库拼本地版（SMB 扫描要一两秒，不能卡 WS 线程）
            listSilenceUntil = now + LIST_SILENCE_MS;
            listSilenceCap = now + LIST_SILENCE_CAP_MS;
            persistStatus("清单：云端自己调了工具，本地补一份一条一行的清单");
            new Thread(() -> {
                final java.util.List<MediaSurvey.Entry> items = buildSurvey(k);
                rememberSurvey(items, k);
                final String overview = MediaSurvey.overview(items, k, LIST_SCREEN_CHARS);
                ui.post(() -> {
                    if (destroyed) return;
                    listPostedAt = System.currentTimeMillis();
                    addLocalAiMessage(overview);
                });
            }, "list-supply").start();
        }
        Log.i(TAG, "list: 云端调用了清单工具，放行 TTS");
    }

    /* ==================== T3：云端不调工具时的兜底 ==================== */

    /**
     * 进入第 attempt 轮等待。云端会不会调 self.media.list 是 LLM 的决定，我们只能等；
     * 这一轮等不到就交给 {@link #onListAttemptFailed}。
     *
     * @param deadline 捕获进 lambda：用来区分「这一轮的闹钟」和「上一轮迟到的闹钟」。
     *                 没有它的话，否认式回答提前收尾后，上一轮的延时任务会在新一轮
     *                 刚开始时误判超时，把重试次数白白烧掉。
     */
    private void awaitListTool(final int kind, final String query, final int attempt) {
        listKind = kind;
        listQueryText = query;
        listAttempt = attempt;
        listToolCalled = false;
        final long startedAt = System.currentTimeMillis();
        final long wait = attempt <= 1 ? LIST_TOOL_WAIT_MS : LIST_RETRY_WAIT_MS;
        final long deadline = startedAt + wait;
        listPendingUntil = deadline;
        suppressAiUntil = startedAt + 1500;      // 只吞掉开头那半句过渡话
        // 从这一刻起，本轮云端的字幕一律不上屏（声音照放）。屏幕上的清单是本地那份：
        // 带序号、能直接「播放第几个」，比云端复述版更好用，两版叠在一起就是重复回复。
        listSilenceUntil = startedAt + LIST_SILENCE_MS;
        listSilenceCap = startedAt + LIST_SILENCE_CAP_MS;
        ui.postDelayed(() -> {
            if (listToolCalled) return;
            if (listPendingUntil != deadline) return;   // 已被新一轮顶掉 / 已收尾
            listPendingUntil = 0;
            onListAttemptFailed("等待超时");
        }, wait);
    }

    /**
     * 这一轮没成（等超时，或云端张嘴就否认）。要么带强指令重试，要么本地兜底。
     *
     * 关键是**任何情况下都不许静默**：清单已经在屏幕上，喇叭却一声不响，
     * 用户只会认为设备死了 —— 这正是 T3 要修的问题。
     */
    private void onListAttemptFailed(String why) {
        if (listToolCalled) return;
        listPendingUntil = 0;
        if (client != null && client.isReady()) client.sendAbort();
        final int kind = listKind;
        if (listAttempt < LIST_MAX_ATTEMPTS) {
            final int next = listAttempt + 1;
            listAttempt = next;
            suppressAiUntil = System.currentTimeMillis() + 600;
            persistStatus("清单：" + why + "，第 " + next + " 轮尝试（强指令）");
            Log.i(TAG, "list: " + why + " -> 第 " + next + " 轮（强指令）");
            ui.postDelayed(() -> {
                if (destroyed) return;
                // 发得出去就把闹钟重新装上 —— 少了这一步，第二轮之后没有任何收尾，
                // 用户会得到一次「重试了但永远等不到结论」的静默（实测踩到过）。
                if (sendListRetry(kind)) awaitListTool(kind, listQueryText, next);
                else listFallback(kind);
            }, 600);
            return;
        }
        suppressAiUntil = System.currentTimeMillis() + 3000;
        persistStatus("清单：" + why + "，重试用尽 → 本地兜底");
        Log.i(TAG, "list: " + why + " -> 重试用尽，本地兜底");
        listFallback(kind);
    }

    /**
     * 第二轮的杀手锏：不再问「有哪些歌」，改成**短的**祈使句点名要用工具。
     *
     * 长度是硬约束：服务端对 {@code listen/detect} 只收短唤醒词，
     * 长文本会直接回 {@code alert: Detect is only for wake words, do not send long texts.}
     * （实测 22 字就被拒）。所以这里必须压到 10 字以内 ——
     * 实测「用工具查视频清单」8 字能让模型真的去调 self.media.list。
     *
     * @return true = 真的发出去了；false = 连接不可用（调用方直接走本地兜底）
     */
    private boolean sendListRetry(final int kind) {
        if (destroyed) return false;
        if (client == null || !client.isReady()) {
            Log.w(TAG, "list: 连接不可用，强指令发不出去");
            return false;
        }
        final String cmd = kind == MediaKind.VIDEO ? "用工具查视频清单"
                : kind == MediaKind.AUDIO ? "用工具查歌曲清单" : "用工具查媒体清单";
        listRetryText = cmd;
        listRetryAt = System.currentTimeMillis();
        sendTextToCloud(cmd);
        Log.i(TAG, "list: 已发出短指令重试「" + cmd + "」");
        return true;
    }

    /**
     * 最后一招：云端彻底没念 → 本地明确告诉用户「清单在屏幕上」。
     *
     * 这台机器没有本地 TTS，只能上屏 + 一声提示音。提示音不是装饰：
     * 它负责把用户的眼睛叫到屏幕上，否则一屏字配一片安静，看着就是死机。
     */
    private void listFallback(int kind) {
        int n = 0;
        try {
            n = surveyItems(kind).size();
        } catch (Throwable t) {
            Log.e(TAG, "listFallback count failed", t);
        }
        final int count = n;
        final String tip = count == 0
                ? "云端这次没接上话。五个位置都没找到能放的音视频，先拷到 /sdcard/Music 或 /sdcard/Movies，或者在设置里打开共享/网络盘再问一次。"
                : "云端这次没念出来，清单已经显示在屏幕上（共 " + count + " 个）。直接说「播放第几个」就能放。";
        beep();
        persistStatus("清单兜底（" + kindName(kind) + "）：" + count + " 个");
        runOnUiThread(() -> {
            addEvent("ⓘ " + tip);
            statusText.setText("云端没念清单，已上屏");
        });
    }

    /** 一声短提示音：本地没有 TTS，这是唯一能把视线叫到屏幕上的手段。 */
    private void beep() {
        ui.post(() -> {
            try {
                if (tone == null) tone = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 160);
            } catch (Throwable t) {
                Log.w(TAG, "beep failed", t);
            }
        });
    }

    /**
     * 是不是「我看不到 / 我这边没有」这类与事实不符的否认式回答。
     * 只在等云端调工具的窗口内使用，用来第一句就掐掉它，而不是等它念完。
     */
    private static boolean isDenialAnswer(String text) {
        if (text == null) return false;
        String s = text.replace(" ", "");
        for (String p : new String[]{"看不到", "无法访问", "访问不到", "打不开", "没法访问",
                "不能访问", "无法查看", "没有这首", "曲库里没", "没有找到", "没有相关的",
                "我这边没有", "我这儿没有", "不在我的曲库", "没这首歌", "没有这首歌"}) {
            if (s.contains(p)) return true;
        }
        return false;
    }

    /**
     * 给 MCP 工具 {@code self.media.list} / {@code self.music.list} 用的清单文本
     * （自然语言，供云端 TTS 朗读）。
     *
     * 长度有硬约束：官方建议单次工具结果 ≤1KB，太长会被模型摘要压缩（10 首念成
     * 「有几首，包括…」），所以总长控制在 900 字内。
     * 调用线程 = MCP 单线程池，可以安全做 SMB 扫描（有 60s 缓存，通常瞬间返回）。
     *
     * @param kind {@link MediaKind#AUDIO} / VIDEO / ANY（四个来源一起扫）
     */
    /**
     * 云端念清单时最多念几条（屏幕上仍然是全部）。
     *
     * <p>一页 25 条念完要五十多秒，用户话早就说完了；只念前十条刚好够他决定
     * 「第几个」还是「下一批」。取 10 是实测下来一屏能扫完、耳朵也跟得上的量。
     */
    private static final int LIST_SPEAK_LINES = 10;

    /**
     * 屏幕上那份清单最多多少字。
     *
     * <p>和「念几条」是两码事：嘴上只念 10 条，屏幕上要全列 —— 用户扫一眼就能
     * 决定「第几个」，不用等它一条条念完。900 字太小，网络盘那一组 22 个文件夹
     * 才列到一半就被截了（实测）。
     */
    private static final int LIST_SCREEN_CHARS = 2400;

    private String buildLibraryOverview(int kind) {
        final java.util.List<MediaSurvey.Entry> items = buildSurvey(kind);
        // 顺手存一份：云端念完清单，用户下一句「播第 3 个」就能直接接上
        rememberSurvey(items, kind);
        // 只念前 10 条：屏幕上（handleLibraryQuery 那条本地消息）依旧是全列，两者各司其职
        return MediaSurvey.overview(items, kind, 900, LIST_SPEAK_LINES);
    }

    /**
     * MCP 工具 {@code self.music.play} 的执行体：把模型给的歌名在「本地文件 → SMB 共享 →
     * 服务端缓存」里逐级找出来，并真的播出去。
     *
     * 跑在 MCP 单线程池（不是 WS 回调线程，也不是主线程）：可以放心做 SMB 扫描，
     * 真正起播由 playAnyTrack 自己 post 回主线程。
     *
     * 找不到时把可选曲目回给模型 —— 让它改口问用户，而不是像以前那样
     * 自己从云端曲库捞一首完全无关的歌（实测出现过：要《禅乐》却给《疲劳消解》）。
     */
    private String playByQueryFromMcp(String query) {
        final String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return "没有给出歌名，请先告诉我要听哪一首。";

        // 0) 「播放第 3 个 / 第 5 首」：按最近一次清单（self.media.list）的序号直接选。
        //    这类话里没有任何名字，按名字匹配永远找不到。
        final int ord = MediaFormats.ordinal(q);
        if (ord > 0) {
            String r = playByIndex(ord);
            if (r != null) return r;
        }

        // 1) 音箱本地音乐（/sdcard/Music 等）
        try {
            if (localFiles != null) {
                LocalFiles.Track tk = localFiles.match(q, MediaKind.fromUtterance(q));
                if (tk == null) tk = localFiles.byTitle(q);
                if (tk != null && playFileTrack(tk, q)) {
                    Log.i(TAG, "mcp play -> local: " + tk.title);
                    return "已开始播放《" + tk.title + "》（音箱本地音乐库）。";
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "mcp play local failed", e);
        }

        // 2) 家里 Windows 共享 / NAS（SMB）
        try {
            if (remote != null && remote.enabled()) {
                RemoteLibrary.Track rt = remote.match(q, MediaKind.fromUtterance(q));
                if (rt != null && playRemoteTrack(rt, q)) {
                    Log.i(TAG, "mcp play -> remote: " + rt.displayTitle());
                    return "已开始播放《" + rt.displayTitle() + "》（家里共享文件夹）。";
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "mcp play remote failed", e);
        }

        // 2.5) 网络盘（OpenList 网关底下的夸克/阿里/百度/移动）
        try {
            if (netDisk != null && netDisk.enabled()) {
                NetDiskLibrary.Track nt = netDisk.match(q, MediaKind.fromUtterance(q));
                if (nt != null && playNetTrack(nt, q)) {
                    Log.i(TAG, "mcp play -> netdisk: " + nt.displayTitle());
                    return "已开始播放《" + nt.displayTitle() + "》（网络盘）。";
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "mcp play netdisk failed", e);
        }

        // 3) 服务端唱过并整首缓存下来的
        try {
            if (library != null) {                String key = LocalFiles.norm(q);
                for (String t : library.titles()) {
                    if (t == null || t.isEmpty()) continue;
                    String nt = LocalFiles.norm(t);
                    if (nt.length() < 2 || key.length() < 2) continue;
                    if (!nt.contains(key) && !key.contains(nt)) continue;
                    if (!playLocalTitle(t)) continue;
                    runOnUiThread(() -> addEvent("▶ 播放本机文件：" + t));
                    Log.i(TAG, "mcp play -> pcm cache: " + t);
                    return "已开始播放《" + t + "》（服务端缓存的整首）。";
                }
            }
        } catch (Throwable e) {
            Log.e(TAG, "mcp play pcm failed", e);
        }

        // 都没命中：把现有条目回给它，让它改口问用户
        StringBuilder sb = new StringBuilder();
        sb.append("没有找到叫《").append(q).append("》的内容。");
        java.util.List<String> candidates = new java.util.ArrayList<>();
        try {
            for (MediaSurvey.Entry e : surveyItems()) {
                candidates.add(e.title + (e.video ? "（视频）" : ""));
                if (candidates.size() >= 8) break;
            }
        } catch (Throwable ignore) {}
        if (!candidates.isEmpty()) {
            sb.append("这台音箱能放的有：");
            int n = 0;
            for (String t : candidates) {
                if (n > 0) sb.append("、");
                sb.append(t);
                n++;
            }
            sb.append("。请问用户想听/看哪一个，不要自己换一个放。");
        } else {
            sb.append("这台音箱上暂时没有可播放的内容。");
        }
        Log.i(TAG, "mcp play -> not found: " + q);
        return sb.toString();
    }

    /**
     * 清单里「（视频）」的标注已经搬进 {@link MediaSurvey}（它本来就要判类型，
     * 顺便标上就行，不必再读一遍文件头）。这里只留一句说明，避免以后有人找错地方。
     */

    private boolean isMusicCommand(String text) {
        if (text == null) return false;
        final String s = text.replace(" ", "");
        return containsAny(s, STOP_WORDS) || containsAny(s, RESUME_WORDS);
    }

    /**
     * 「播放逐梦」这类指令：本地文件 → PCM 曲库 → 服务端，逐级兜底。
     * @return true 表示已经接管（调用方不要再把这句话交给服务端）
     */
    private boolean tryLocalPlay(String text) {
        if (text == null) return false;
        final String s = text.replace(" ", "").replace("，", "").replace("。", "").replace("？", "");
        if (containsAny(s, STOP_WORDS)) return false;
        // 播放中的「继续 / 接着 / 往下放」是控制指令，不是点播。
        // 不挡这一下，「继续播放」会被当成一个两字名字去打分 —— 剥掉动词后剩「继续」，
        // hasRealName 认为它有 2 个字、够格当歌名，于是撞上曲库里随便哪一首
        // （真机实测：暂停后说「继续播放」，歌被换成了网络盘的《周末最好的50首…》）。
        // 只在「确实有东西在放」时挡：「接着放邓紫棋」在没有播放时仍按点播处理。
        if ((filePlaying || localPlayback || resuming || videoMode) && containsAny(s, RESUME_WORDS)) {
            return false;
        }
        if (!s.contains("播放") && !s.contains("放") && !s.contains("唱")
                && !s.contains("听") && !s.contains("来一首")
                && !s.contains("看") && !s.contains("视频")) return false;
        // 「音乐」「歌曲」是泛称，不是名字。共享盘里恰好有《世界顶级纯音乐八首》
        // 《1920-1950 年中國經典歌曲》，只凭这两个字就能撞上 ——「播放音乐」
        // 「看看有什么歌曲」于是变成随机播一首（用户实测）。剥掉动词/泛称/疑问词
        // 后不剩实质内容的，一律不按名字匹配，交给清单或云端。
        if (!LocalFiles.hasRealName(s)) return false;
        // 「看视频」/「只听声音」会改变末级判定（同一首 mp4 可以出画面也可以只出声）
        final int prefer = MediaKind.fromUtterance(text);

        // 1) 本地文件优先：用户自己导进来的歌是原版，音质和完整性都强于服务端唱的
        if (localFiles != null) {
            LocalFiles.Track tk = localFiles.match(s, prefer);
            if (tk != null && playFileTrack(tk, text)) return true;
        }
        // 1.4) 说的是网盘里的**文件夹名**（「播放现代民谣排行榜100首」）→ 进目录，别当成歌去播。
        // 必须挡在共享盘打分之前：共享盘里有《《宋冬野》20首民谣串烧》，跟「现代民谣排行榜100首」
        // 共着「民谣」两个字，同音容错刚好凑到 6 分命中线 —— 于是用户点网盘的文件夹，
        // 出来的却是共享盘里一首不相干的歌（真机实测 15:52、16:01 各一次）。
        if (netDisk != null && netDisk.enabled()) {
            NetDiskLibrary.Dir fd = netDisk.findDirNamed(s);
            if (fd != null) {
                persistStatus("网络盘：这句说的是文件夹「" + fd.name + "」，进目录");
                enterNetDir(fd.path);
                return true;
            }
        }
        // 1.5) 远程音乐库：电脑上用 Windows 文件共享出去的文件夹（设置里开启后生效）
        if (remote != null && remote.enabled()) {
            RemoteLibrary.Track rt = remote.match(s, prefer);
            if (rt != null && playRemoteTrack(rt, text)) return true;
        }
        // 1.6) 网络盘：家里 PC 的 OpenList 网关（夸克等网盘里的音乐）
        if (netDisk != null && netDisk.enabled()) {
            NetDiskLibrary.Track nt = netDisk.match(s, prefer);
            if (nt != null && playNetTrack(nt, text)) return true;
        }
        // 2) PCM 曲库：服务端唱过、整首缓存在本地的
        if (library == null) return false;
        for (String t : library.titles()) {
            if (t.isEmpty() || !s.contains(t)) continue;
            if (!playLocalTitle(t)) continue;
            runOnUiThread(() -> addEvent("▶ 播放本机文件：" + t));
            return true;
        }
        return false;
    }

    /* ==================== 本地音频文件（mp3/flac/wav…） ==================== */

    /** 播一个本地文件；先掐掉所有其它音源，避免两份声音叠在一起。 */
    private boolean playFileTrack(LocalFiles.Track tk) {
        return playFileTrack(tk, null);
    }

    /**
     * 播一个本地文件，并在它是视频容器时决定「出画面」还是「只出声」。
     *
     * @param utterance 触发这次播放的那句话（「看视频」/「只听声音」会改变判定），可为 null
     */
    private boolean playFileTrack(LocalFiles.Track tk, String utterance) {
        if (tk == null) return false;
        final java.util.List<PlayItem> q =
                new java.util.ArrayList<PlayItem>(localFiles.tracks());
        if (tk.video) {
            int kind = MediaKind.decide(this, tk.path(), tk.file.getName(), utterance, true);
            persistStatus("视频文件判定《" + tk.title + "》→ " + MediaKind.name(kind));
            if (kind == MediaKind.VIDEO) return playVideoItem(tk, q);
        }
        return playAnyTrack(tk, q);
    }

    /** 播远程（电脑共享文件夹）里的一首；视频出画面，音频下载/直读。 */
    private boolean playRemoteTrack(RemoteLibrary.Track t) {
        return playRemoteTrack(t, null);
    }

    private boolean playRemoteTrack(final RemoteLibrary.Track t, final String utterance) {
        if (t == null) return false;
        final java.util.List<PlayItem> q =
                new java.util.ArrayList<PlayItem>(remote.tracks());
        if (!t.isVideo()) return playAnyTrack(t, q);

        // 远程文件读不了文件头吗？能 —— jcifs 支持随机读，MediaMetadataRetriever 也能吃
        // MediaDataSource，所以先按「指令词 → 上次选择 → 目录」快速判，判不出来再读文件头。
        int kind = MediaKind.fromUtterance(utterance);
        if (kind == MediaKind.ANY) kind = MediaKind.remembered(this, t.name);
        if (kind == MediaKind.ANY) kind = MediaKind.fromPath(t.smbUrl);
        if (kind != MediaKind.ANY) {
            persistStatus("远程视频文件判定《" + t.title + "》→ " + MediaKind.name(kind));
            return kind == MediaKind.VIDEO ? playVideoItem(t, q) : playAnyTrack(t, q);
        }
        // 还不知道：读一下远程文件头（只读几 KB，不用整首下载）
        new Thread(() -> {
            int k = MediaKind.ANY;
            android.media.MediaDataSource ds = remote.openDataSource(t.smbUrl);
            if (ds != null) k = MediaKind.probeSource(ds);
            final int kk = (k == MediaKind.ANY) ? MediaKind.VIDEO : k;
            ui.post(() -> {
                MediaKind.remember(this, t.name, kk);
                persistStatus("远程视频文件判定《" + t.title + "》→ "
                        + MediaKind.name(kk) + "（读文件头）");
                if (kk == MediaKind.VIDEO) playVideoItem(t, q);
                else playAnyTrack(t, q);
            });
        }, "smb-probe").start();
        return true;
    }

    /**
     * 播网络盘里的一个条目：视频出画面（交给 VideoActivity 边下边播），音频走本机播放器。
     *
     * <p>类型判定和共享盘一样：读不了文件头（读一次就是几 MB 的往返），
     * 只能 印象 → 路径 → 扩展名。mp4 里只有一条音轨的（MV 音频版）仍然只在音频清单里，
     * 因为它会被 {@link MediaKind} 记住成音频。
     */
    private boolean playNetTrack(final NetDiskLibrary.Track t, final String utterance) {
        if (t == null) return false;
        final java.util.List<PlayItem> q =
                new java.util.ArrayList<PlayItem>(netDisk.tracks());
        int kind = MediaKind.fromUtterance(utterance);
        if (kind == MediaKind.ANY) kind = MediaKind.remembered(this, t.name);
        if (kind == MediaKind.ANY) kind = MediaKind.fromPath(t.path);
        if (kind == MediaKind.ANY) kind = t.isVideo() ? MediaKind.VIDEO : MediaKind.AUDIO;
        if (kind == MediaKind.VIDEO) {
            persistStatus("网络盘视频《" + t.title + "》→ 视频页（边下边播）");
            return playVideoItem(t, q);
        }
        return playAnyTrack(t, q);
    }

    /**
     * 统一播放入口：本地文件 / 远程 SMB 都走 PlayItem。
     * 先掐掉所有其它音源，再按队列播放；远程条目在 playFileAt 里现拷现播。
     */
    private boolean playAnyTrack(final PlayItem item, final java.util.List<PlayItem> queue) {
        if (item == null || filePlayer == null) return false;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post(() -> playAnyTrack(item, queue));      // 播放器操作统一在主线程
            return true;
        }
        if (client != null && client.isReady()) client.sendAbort();
        suppressAiUntil = System.currentTimeMillis() + 3000;   // 掐掉服务端“曲库里没有”的字幕
        // 用户这次点的是「听」：把视频页整个关掉，而不是暂停 ——
        // 暂停的视频页还留在栈里，一旦再进前台（或说「继续」）它就接着出声，
        // 于是又变成两路声音叠在一起。
        if (videoMode) {
            videoHold = false;
            videoMode = false;
            sendVideoCmd("exit");
            addEvent("■ 已关闭视频，改放音频");
        }
        if (resuming) { resumeAbort = true; resuming = false; }
        if (refilling) cancelRefill("本地/远程文件接管");
        if (notifyPlayer != null && notifyPlayer.isPlaying()) notifyPlayer.stop();
        player.flush();
        music.clear();                       // 服务端那首歌的会话作废，别再拿它续播
        localPlayback = false;

        // 队列 = 当前扫描到的全部文件，方便说「下一首」
        fileQueue = queue;
        fileIndex = -1;
        for (int i = 0; i < fileQueue.size(); i++) {
            if (fileQueue.get(i).source().equals(item.source())) { fileIndex = i; break; }
        }
        if (fileIndex < 0) { fileQueue.clear(); fileQueue.add(item); }
        fileAutoResume = false;
        filePlaying = true;                  // 先占住：远程拷贝期间也别被 VAD / 提问顶掉
        return playFileAt(fileIndex >= 0 ? fileIndex : 0);
    }

    /** 播放队列里第 idx 首。 */
    private boolean playFileAt(final int idx) {
        if (idx < 0 || idx >= fileQueue.size()) return false;
        // MediaPlayer 必须在有 Looper 的线程上创建，否则回调（播完/报错）根本不会派发。
        // 语音指令走的是 WebSocket 回调线程，这里统一转到主线程执行。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post(() -> playFileAt(idx));
            return true;
        }
        final PlayItem it = fileQueue.get(idx);
        fileIndex = idx;
        // 从进入「正在连接电脑 / 正在取流」这一刻就算接管：换歌期间 filePlaying 若为 false，
        // 用户这时候喊「停止」会被当成普通提问，白等一趟云端往返。
        filePlaying = true;
        // 换歌：先收起上一首的进度条，新曲 prepare 完再亮出来（中间那段是「正在连接…」）
        hideNowBar();

        // 网络盘（OpenList 网关）：同样边下边播。直链带签名、会过期，所以每次现取
        if (it.source().startsWith("ol:")) {
            final NetDiskLibrary.Track nt = (NetDiskLibrary.Track) it;
            final java.io.File hit = netDisk.findCache(nt);
            if (hit != null) {
                startFilePlayback(hit.getAbsolutePath(), it.displayTitle(), "网络盘（已缓存）");
                return true;
            }
            statusText.setText("正在连接网络盘：" + it.displayTitle());
            new Thread(() -> {
                final android.media.MediaDataSource src = netDisk.openDataSource(nt);
                ui.post(() -> {
                    if (src != null) {
                        streamFallbackItem = it;      // 中途断流时靠它退回整包下载
                        statusText.setText("正在从网络盘播放：" + it.displayTitle());
                        startStreamPlayback(src, it.displayTitle(), "网络盘");
                    } else {
                        persistStatus("网络盘取流打不开，改为下载《" + it.displayTitle() + "》");
                        downloadNetAndPlay(nt, it);
                    }
                });
            }, "ol-open").start();
            return true;
        }

        // 远程（电脑共享盘）曲目：P3 先试「边下边播」，失败才退回「先整首下载」
        if (it.source().startsWith("smb://")) {
            final RemoteLibrary.Track rt = (RemoteLibrary.Track) it;
            // 整包下过的就不必再下一遍：直接放本地那份，起播是毫秒级
            final java.io.File hit = remote.findCache(rt);
            if (hit != null) {
                startFilePlayback(hit.getAbsolutePath(), it.displayTitle(), "电脑共享（已缓存）");
                return true;
            }
            statusText.setText("正在连接电脑：" + it.displayTitle());
            // 建 SMB 上下文要 DNS，只能在后台线程做
            new Thread(() -> {
                final android.media.MediaDataSource src = remote.openDataSource(rt.smbUrl);
                ui.post(() -> {
                    if (src != null) {
                        streamFallbackItem = it;      // 中途断流时靠它退回下载
                        statusText.setText("正在从电脑直接播放：" + it.displayTitle());
                        startStreamPlayback(src, it.displayTitle(), "电脑共享");
                    } else {
                        persistStatus("SMB 直读打不开，改为下载《" + it.displayTitle() + "》");
                        downloadAndPlay(rt, it);
                    }
                });
            }, "smb-open").start();
            return true;
        }

        startFilePlayback(it.source(), it.displayTitle(), "本机");
        return true;
    }

    /**
     * 远程边下边播：把数据源交给 MediaPlayer，读到哪里 seek 到哪里。
     * 几十 MB 的歌不用等整首下载，点下去一两秒就出声。
     *
     * @param label 「正在播放」卡片上的来源名（电脑共享 / 网络盘）
     */
    private void startStreamPlayback(final android.media.MediaDataSource src, final String title,
                                     final String label) {
        final boolean ok = filePlayer.play(src, title, new FileMusicPlayer.Listener() {
            @Override public void onDone() { ui.post(() -> onFileTrackDone()); }
            @Override public void onError(String why) {
                persistStatus("直读失败《" + title + "》" + why);
                final PlayItem it = streamFallbackItem;
                streamFallbackItem = null;
                if (it instanceof RemoteLibrary.Track) {
                    // 直读失败（网络抖动 / 服务端不让随机读）→ 退回整首下载再播
                    ui.post(() -> downloadAndPlay((RemoteLibrary.Track) it, it));
                    return;
                }
                if (it instanceof NetDiskLibrary.Track) {
                    ui.post(() -> downloadNetAndPlay((NetDiskLibrary.Track) it, it));
                    return;
                }
                ui.post(() -> {
                    addEvent("✕ 放不了：" + title);
                    if (fileQueue.size() > 1) nextFile(true); else resetFileState();
                });
            }
        });
        if (!ok) {
            final PlayItem it = streamFallbackItem;
            streamFallbackItem = null;
            if (it instanceof RemoteLibrary.Track) downloadAndPlay((RemoteLibrary.Track) it, it);
            else if (it instanceof NetDiskLibrary.Track) downloadNetAndPlay((NetDiskLibrary.Track) it, it);
            else resetFileState();
            return;
        }
        filePlaying = true;
        persistStatus(label + " 直读播放《" + title + "》");
        runOnUiThread(() -> showNowBar(title, label));
    }

    /** 远程曲目：先整首下到本地缓存再播（直读不可用时的兜底）。 */
    private void downloadAndPlay(final RemoteLibrary.Track rt, final PlayItem it) {
        statusText.setText("正在从电脑读取：" + it.displayTitle());
        new Thread(() -> {
            java.io.File cacheDir = getExternalFilesDir(null);
            if (cacheDir == null) cacheDir = getCacheDir();
            final String local = remote.fetchToCache(rt, cacheDir);
            ui.post(() -> {
                if (local == null) {
                    persistStatus("远程读取失败《" + it.displayTitle() + "》");
                    addEvent("✕ 电脑共享里读不到：" + it.displayTitle());
                    if (fileQueue.size() > 1) nextFile(true); else resetFileState();
                    return;
                }
                startFilePlayback(local, it.displayTitle(), "电脑共享");
            });
        }, "smb-fetch").start();
    }

    /** 网络盘曲目：先整首下到本地缓存再播（取流不可用时的兜底）。 */
    private void downloadNetAndPlay(final NetDiskLibrary.Track nt, final PlayItem it) {
        statusText.setText("正在从网络盘读取：" + it.displayTitle());
        new Thread(() -> {
            java.io.File cacheDir = getExternalFilesDir(null);
            if (cacheDir == null) cacheDir = getCacheDir();
            final String local = netDisk.fetchToCache(nt, cacheDir);
            ui.post(() -> {
                if (local == null) {
                    persistStatus("网络盘读取失败《" + it.displayTitle() + "》");
                    addEvent("✕ 网络盘里读不到：" + it.displayTitle());
                    if (fileQueue.size() > 1) nextFile(true); else resetFileState();
                    return;
                }
                startFilePlayback(local, it.displayTitle(), "网络盘");
            });
        }, "ol-fetch").start();
    }

    /** 用本地路径真正起播（本地文件，或已从远程拷下来的缓存）。 */
    private void startFilePlayback(String path, String title, String source) {
        final boolean ok = filePlayer.play(path, new FileMusicPlayer.Listener() {
            @Override public void onDone() { ui.post(() -> onFileTrackDone()); }
            @Override public void onError(String why) {
                persistStatus("文件播放失败《" + title + "》" + why);
                ui.post(() -> {
                    addEvent("✕ 放不了：" + title);
                    // 跳到下一首，别卡在这一首上
                    if (fileQueue.size() > 1) nextFile(true);
                    else resetFileState();
                });
            }
        });
        if (!ok) {
            persistStatus("文件打不开：" + path);
            filePlaying = false;             // 起不来就别占着「在放」的位置
            return;
        }
        // 播完自动换歌（onFileTrackDone → nextFile → playFileAt）走的就是这条路径，
        // 而 onFileTrackDone 里已经把 filePlaying 置 false 了。这里不补回来，就会出现
        // 「声音在放、状态却是停」—— 之后 handleFileIntent 第一行的
        // `if (!filePlaying) return false` 会把「暂停 / 停止 / 下一首」全部甩给云端，
        // 云端只会嘴上答应「已经停下来了」，歌照放。
        filePlaying = true;
        persistStatus("文件播放《" + title + "》· " + source + " · " + path);
        runOnUiThread(() -> showNowBar(title, source));
    }

    private void onFileTrackDone() {
        if (!filePlaying) return;
        filePlaying = false;
        final String done = (fileIndex >= 0 && fileIndex < fileQueue.size())
                ? fileQueue.get(fileIndex).displayTitle() : "?";
        persistStatus("本地文件播放结束《" + done + "》");
        final int mode = PlayMode.get(this);
        if (mode == PlayMode.REPEAT) {
            addEvent("» 重播：" + done);
            filePlaying = true;
            playFileAt(fileIndex);
            return;
        }
        if (mode == PlayMode.STOP) {
            resetFileState();
            addEvent("■ 播放结束 · 按设置停在这里");
            return;
        }
        // 队列里还有就接着放；到最后一首就收工
        nextFile(true);
    }

    private void nextFile(boolean auto) {
        if (fileQueue.isEmpty() || fileIndex + 1 >= fileQueue.size()) {
            resetFileState();
            if (auto) ui.post(() -> {
                statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
                addEvent("■ 列表已播完");
            });
            return;
        }
        playFileAt(fileIndex + 1);
    }

    private void prevFile() {
        if (fileQueue.isEmpty()) return;
        // 语音说「上一首」就是真想换歌，不做手机播放器那种「先回本首开头」的判定
        playFileAt(fileIndex > 0 ? fileIndex - 1 : 0);
    }

    private void resetFileState() {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post(this::resetFileState);
            return;
        }
        filePlayer.stop();
        hideNowBar();
        streamFallbackItem = null;
        filePlaying = false;
        fileAutoResume = false;
        fileQueue.clear();
        fileIndex = -1;
        statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
    }

    /* ==================== 「正在播放」卡片（聊天流里的事件条 + 进度条） ==================== */

    /**
     * 起播 / 换歌：把聊天流末尾那张「▶ 正在播放 · 来源：歌名」事件条升级成卡片，
     * 下面挂一条进度条，时间（当前 / 总长）骑在条上即时走。
     *
     * 为什么不写进 ChatStore：进度每秒在刷、切歌要改标题，进历史只会在下次打开时
     * 留下一串「正在播放」的化石记录。它只是一块跟着播放状态活的视图，
     * 停止 / 播完就整块收走（原来那条「■ 播放结束…」事件照旧由 addEvent 记）。
     */
    private void showNowBar(String title, String source) {
        if (chatList == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post(() -> showNowBar(title, source));
            return;
        }
        buildNowCardIfNeeded();
        nowSource = source == null ? "" : source;
        final String t = (title == null || title.isEmpty()) ? "正在播放" : title;
        // 标题沿用原来那条事件信息的写法：▶ 正在播放 · 电脑共享：歌名
        nowCardBase = "▶ 正在播放"
                + (nowSource.isEmpty() ? "" : " · " + nowSource)
                + "：" + t;
        nowCardTitle.setText(nowCardBase);
        nowCardBar.setVisibility(View.VISIBLE);
        if (nowCard.getParent() == null) {
            nowCard.getLayoutParams().width = nowCardWidth();
            chatList.addView(nowCard);
            chatEmpty.setVisibility(View.GONE);
            // 等卡片自己量完高度再滚，否则 fullScroll 算的还是加卡片之前的高度，
            // 卡片下半截（进度条）会被聊天区底边裁掉。
            nowCard.post(this::scrollToBottom);
        }
        // 顶栏那行「正在连接电脑 / 正在从电脑直接播放」在卡片出现后就是重复信息了
        final String s = statusText.getText().toString();
        if (s.startsWith("正在")) {
            statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
        }
        startNowTicker();
    }

    private void hideNowBar() {
        if (nowCard == null) return;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post(this::hideNowBar);
            return;
        }
        ui.removeCallbacks(nowTick);
        if (nowCard.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) nowCard.getParent()).removeView(nowCard);
        }
    }

    /**
     * 构建卡片骨架（只建一次）。
     *
     * 外层照抄事件气泡的样式：浅灰圆角（bg_event）、居中、和「回答」明确分开；
     * 宽度固定 —— 进度条要有确定的长度才看得出走到哪儿，不能像气泡那样随文字伸缩。
     */
    private void buildNowCardIfNeeded() {
        if (nowCard != null) return;

        nowCard = new LinearLayout(this);
        nowCard.setOrientation(LinearLayout.VERTICAL);
        nowCard.setBackgroundResource(R.drawable.bg_event);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                nowCardWidth(), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(4);
        lp.bottomMargin = dp(4);
        nowCard.setLayoutParams(lp);

        nowCardTitle = new TextView(this);
        nowCardTitle.setTextSize(13);
        nowCardTitle.setTextColor(0xFF5F5F5F);
        nowCardTitle.setSingleLine(true);
        nowCardTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nowCard.addView(nowCardTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        nowCardBar = new NowPlayingBar(this);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        blp.topMargin = dp(6);
        nowCard.addView(nowCardBar, blp);
        nowCardBar.setSeekListener(this::seekFileByFraction);
    }

    /** 卡片宽度：跟着聊天区走（左右分栏 / 上下堆叠两种外观都别撑破）。 */
    private int nowCardWidth() {
        final int avail = (chatScroll != null && chatScroll.getWidth() > 0)
                ? chatScroll.getWidth() - dp(36)
                : (int) (getResources().getDisplayMetrics().widthPixels * 0.62f);
        return Math.max(dp(240), Math.min(avail, dp(560)));
    }

    private void startNowTicker() {
        ui.removeCallbacks(nowTick);
        ui.post(nowTick);
    }

    /** 每 400ms 刷一次进度 + 状态后缀（准备中 / 已暂停）。 */
    private final Runnable nowTick = new Runnable() {
        @Override public void run() {
            if (nowCard == null || nowCard.getParent() == null) return;
            if (filePlayer == null) { hideNowBar(); return; }
            nowCardBar.setProgress(filePlayer.positionMs(), filePlayer.durationMs());
            final String st;
            if (!filePlayer.isPrepared()) st = "准备中";
            else if (!filePlayer.isPlaying()) st = "已暂停";
            else st = "";
            final String t = st.isEmpty() ? nowCardBase : nowCardBase + " · " + st;
            if (!t.contentEquals(nowCardTitle.getText())) nowCardTitle.setText(t);
            ui.postDelayed(this, 400);
        }
    };

    /**
     * seek 之后立刻刷进度。
     *
     * MediaPlayer 的 getCurrentPosition 有几十毫秒滞后，紧跟着读还是旧位置，
     * 所以立即刷一次、260ms 后再补一次 —— 语音说「跳到 2 分钟」时眼看着条就挪过去了。
     */
    private void pokeNowBar() {
        if (nowCard == null || nowCard.getParent() == null) return;
        ui.removeCallbacks(nowTick);
        ui.post(nowTick);
        ui.postDelayed(() -> {
            if (nowCard == null || nowCard.getParent() == null || filePlayer == null) return;
            nowCardBar.setProgress(filePlayer.positionMs(), filePlayer.durationMs());
        }, 260);
    }

    /** 页面重建（进设置页回来 / 删记录）后，把还在播的那首的卡片接回去。 */
    private void restoreNowCard() {
        if (filePlaying && filePlayer != null && filePlayer.hasTrack()) {
            showNowBar(currentFileTitle(), nowSource);
        }
    }

    /** 点/拖进度条跳转（语音之外的第二条路）。 */
    private void seekFileByFraction(float f) {
        if (!filePlaying || filePlayer == null || !filePlayer.hasTrack()) return;
        final int dur = filePlayer.durationMs();
        if (dur <= 0) return;
        if (!filePlayer.isPrepared()) { localHint("还在准备，等一秒再跳"); return; }
        final int target = (int) (dur * Math.max(0f, Math.min(1f, f)));
        if (!filePlayer.seekTo(target)) { localHint("这个文件跳不了"); return; }
        pokeNowBar();
        persistStatus("点进度条跳转 " + target + "ms");
        final String title = currentFileTitle();
        ui.postDelayed(() -> addEvent("» " + title + " 已跳到 "
                + MediaFormats.fmt(filePlayer.positionMs())
                + " / " + MediaFormats.fmt(dur)), 260);
    }

    /* ==================== 视频（P2：底栏播放页 + 半双工语音） ==================== */

    /**
     * 把一条视频交给 VideoActivity 播。
     *
     * 语音没搬过去：VideoActivity 只负责画面和按钮，麦克风/ASR/WebSocket 全留在这边，
     * 两边用广播对话（点麦克风 → 停画面 → 这边开麦 → 答完这边广播「继续」）。
     */
    private boolean playVideoItem(final PlayItem item, final java.util.List<PlayItem> queue) {
        if (item == null) return false;
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            ui.post(() -> playVideoItem(item, queue));
            return true;
        }
        // 已经在看片：先把当前视频页关掉再开新的。
        // 不关的话会同时存在两个 VideoActivity、两个 MediaPlayer，
        // 旧那个在后台继续出声 —— 这就是「播 mp4 时又响着另一路声音」的来源。
        if (videoMode) {
            videoMode = false;
            videoHold = false;
            sendVideoCmd("exit");
            persistStatus("切换视频：先关掉当前播放页");
            ui.postDelayed(() -> playVideoItem(item, queue), 550);
            return true;
        }
        // 视频一开，本地文件 / 曲库 / 服务端流全部让出，避免两路声音叠在一起。
        // 判 hasTrack 而不是 filePlaying：暂停中的那首 filePlaying 可能仍是 true，
        // 但反过来「已停但播放器还在手里」的情况也必须一起收掉。
        if (filePlayer != null && filePlayer.hasTrack()) resetFileState();
        if (localPlayback && resuming) stopLocalPlayback();
        if (refilling) cancelRefill("视频接管");
        if (client != null && client.isReady()) client.sendAbort();
        suppressAiUntil = System.currentTimeMillis() + 2500;
        notifyPlayerStopIfPlaying();
        player.flush();

        // 队列只保留「装得下画面」的条目：说「下一首」时不该跳到一首 mp3 上去开个黑屏
        videoQueue = new java.util.ArrayList<PlayItem>();
        if (queue != null) {
            for (PlayItem q : queue) if (q != null && q.isVideo()) videoQueue.add(q);
        }
        if (videoQueue.isEmpty()) videoQueue.add(item);
        videoIndex = -1;
        for (int i = 0; i < videoQueue.size(); i++) {
            if (videoQueue.get(i).source().equals(item.source())) { videoIndex = i; break; }
        }
        if (videoIndex < 0) { videoQueue.clear(); videoQueue.add(item); videoIndex = 0; }

        Intent i = new Intent(this, VideoActivity.class);
        i.putExtra("title", item.displayTitle());
        String origin = "直读";
        if (item.source().startsWith("smb://")) {
            // 先查整包缓存（smb_*）：看完的一集片子已经在本机了，再连一次共享盘纯属白等
            // （实测 SMB 冷启要十几秒，缓存命中直接本地打开）。
            final String url = item.source();
            final String nm = url.substring(url.lastIndexOf('/') + 1);
            java.io.File hit = null;
            try { hit = new RemoteLibrary(this).findCache(nm, sizeOfItem(item)); } catch (Throwable ignore) {}
            if (hit != null && hit.length() > 0) {
                i.putExtra("path", hit.getAbsolutePath());
                origin = "本机缓存";
                persistStatus("共享盘视频缓存命中 " + hit.getName() + "（不再连电脑）");
            } else {
                i.putExtra("smb", url);
            }
        } else if (item.source().startsWith("ol:")) {
            // 网络盘：把网关内路径和字节数带过去，视频页自己建 NetDiskLibrary +
            // OlStreamSource（边下边播）。字节数是必需的 —— 拿不到总长就没法拖进度条，
            // MediaPlayer 也会把「不知道多长」的流当直播处理，seek 直接失效。
            i.putExtra("ol", item.source().substring(3));
            i.putExtra("olsize", sizeOfItem(item));
        } else {
            i.putExtra("path", item.source());
        }
        // 单实例：VideoActivity 在 manifest 里是 singleTask，这里再带 SINGLE_TOP，
        // 保证全机只有一个视频页（多个实例 = 多路音频同时响）。
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
        videoHold = false;
        addEvent("▶ 播放视频：" + item.displayTitle());
        persistStatus("视频起播《" + item.displayTitle() + "》· " + origin + " · " + item.source());
        return true;
    }

    /** 条目字节数（本地文件 / 共享盘 / 网络盘 / 清单条目各有各的存法）。 */
    private static long sizeOfItem(PlayItem it) {
        try {
            if (it instanceof MediaSurvey.Entry) return ((MediaSurvey.Entry) it).size;
            if (it instanceof NetDiskLibrary.Track) return ((NetDiskLibrary.Track) it).size;
            if (it instanceof RemoteLibrary.Track) return ((RemoteLibrary.Track) it).size;
            if (it instanceof LocalFiles.Track) {
                LocalFiles.Track t = (LocalFiles.Track) it;
                return t.file != null ? t.file.length() : 0;
            }
        } catch (Throwable ignore) {}
        return 0;
    }

    private void notifyPlayerStopIfPlaying() {
        try {
            if (notifyPlayer != null && notifyPlayer.isPlaying()) notifyPlayer.stop();
        } catch (Throwable ignore) {}
    }

    /** 视频页到前台：所有音频让位；语音继续在本页工作。 */
    private void enterVideoMode() {
        if (client != null && client.isReady()) client.sendAbort();
        if (filePlayer != null && filePlayer.hasTrack()) resetFileState();
        if (localPlayback && resuming) stopLocalPlayback();
        notifyPlayerStopIfPlaying();
        player.flush();
        player.setSilent(true);
        videoHold = false;
        statusText.setText("视频播放中 · 点视频页麦克风说话");
        persistStatus("进入视频模式（语音仍可用）");
    }

    /** 视频页退出：把音频链路恢复可用。 */
    private void exitVideoMode() {
        player.setSilent(false);
        statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
        persistStatus("退出视频模式");
        // 队列留着：接着说「下一首」还能往下放
    }


    private void sendVideoCmd(String cmd) {
        Intent i = new Intent(A_VIDEO_CMD);
        i.putExtra("cmd", cmd);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        Log.i(TAG, "video cmd -> " + cmd);
    }

    private void sendVideoSub(String text) {
        Intent i = new Intent(A_VIDEO_SUB);
        i.putExtra("text", text == null ? "" : text);
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private static final String[] VIDEO_EXIT_WORDS = {
            "退出视频", "关掉视频", "关闭视频", "退出播放", "不看了", "关闭播放",
            "退出电影", "别看了", "关视频", "退出"
    };

    /**
     * 视频播放期间的语音指令：暂停 / 继续 / 上一首 / 下一首 / 退出。
     *
     * 全部本地处理 —— 服务端听懂了也没用，它控制不了这台机器的画面，
     * 反而会自己开一段 TTS 把视频声音盖住，所以一律 abort 掉。
     *
     * @return true 表示这句话已被视频接管
     */
    private boolean handleVideoVoiceIntent(String text) {
        if (!videoMode || text == null) return false;
        // 「从 2 分钟继续播放」既是继续也是跳转 —— 交给进度逻辑（它跳完会接着放）
        MediaFormats.TimeSpec ts = MediaFormats.parseTime(text);
        if (ts.valid() && MediaFormats.looksLikeSeek(text)) return false;
        final String s = text.replace(" ", "").replace("，", "").replace("。", "")
                .replace("！", "").replace("!", "").replace("？", "");
        final boolean exit = containsAny(s, VIDEO_EXIT_WORDS);
        final boolean next = containsAny(s, NEXT_WORDS);
        final boolean prev = containsAny(s, PREV_WORDS);
        final boolean resume = containsAny(s, RESUME_WORDS);
        final boolean stop = containsAny(s, STOP_WORDS);
        if (!exit && !next && !prev && !resume && !stop) return false;
        if (client != null && client.isReady()) client.sendAbort();
        suppressAiUntil = System.currentTimeMillis() + 3000;
        if (exit) {
            videoHold = true;
            sendVideoCmd("exit");
            persistStatus("视频：退出");
        } else if (next) {
            switchVideo(1);
        } else if (prev) {
            switchVideo(-1);
        } else if (resume) {
            videoHold = false;
            sendVideoCmd("resume");
            persistStatus("视频：继续");
        } else {
            videoHold = true;          // 用户主动暂停，答完不要自动接上
            sendVideoCmd("pause");
            persistStatus("视频：暂停");
        }
        return true;
    }

    /** 队列里前后翻一个片子：playVideoItem 会先关掉当前页再开新的。 */
    private void switchVideo(int dir) {
        if (videoQueue.size() <= 1) {
            sendVideoCmd("resume");
            persistStatus("视频：列表里只有一个，忽略切歌");
            return;
        }
        int n = videoIndex + dir;
        if (n < 0 || n >= videoQueue.size()) {
            sendVideoCmd("resume");
            persistStatus("视频：已到列表边缘");
            return;
        }
        videoIndex = n;
        playVideoItem(videoQueue.get(n), videoQueue);
    }

    /**
     * 一部片子自然播完（VideoActivity 发来 ev=done）：按「播完之后」的设置决定下一步。
     * 重播由视频页自己处理（不关页面更快），这里只负责续播/停。
     */
    private void onVideoDone() {
        if (!videoMode) return;
        final int mode = PlayMode.get(this);
        if (mode == PlayMode.REPEAT) return;         // 视频页已在原地重播
        if (mode == PlayMode.STOP) {
            addEvent("■ 视频播放结束 · 按设置停在这里");
            return;
        }
        final int n = videoIndex + 1;
        if (n >= videoQueue.size()) {
            addEvent("■ 视频列表已播完");
            persistStatus("视频列表已播完");
            return;
        }
        videoIndex = n;
        final PlayItem next = videoQueue.get(n);
        addEvent("▶ 续播下一个：" + next.displayTitle());
        persistStatus("视频续播《" + next.displayTitle() + "》· " + PlayMode.name(mode));
        playVideoItem(next, videoQueue);
    }

    /* ==================== 媒体清单：按序号选 / 按时间跳 ==================== */

    /**
     * 「播放第 3 个 / 第 5 首」。
     *
     * 三个候选池，越靠前越贴近当前语境：
     *   1) 视频页在前台 → 视频队列
     *   2) 本地文件在播 → 当前播放队列（按这句话的音频/视频倾向过滤）
     *   3) 最近一次清单（3 分钟内有效）→ 没有就现扫一遍全量清单
     */
    private boolean handlePickIntent(String text) {
        final int n = MediaFormats.ordinal(text);
        if (n <= 0) return false;
        // 「第 3 首」= 在音频里数，「第 2 集」= 在视频里数；量词不表态时再看整句话的倾向
        int prefer = MediaFormats.ordinalKind(text);
        if (prefer == MediaKind.ANY) prefer = MediaKind.fromUtterance(text);
        final int preferKind = prefer;

        // 刚报过清单（90 秒内）时，序号指的是**刚才那份清单** —— 这是用户此刻脑子里的
        // 「第几个」。实测过：问完「有哪些视频」接着说「播放第二个」，却因为正在放歌
        // 而被当成"队列里第二首"，放了首完全无关的歌。
        final boolean surveyFresh = !lastSurvey.isEmpty()
                && System.currentTimeMillis() - lastSurveyAt < 90_000L;
        // 「视频里的第三个」——正在看片时按视频队列数，别去数音频清单
        // （那会把用户正在看的片子换掉，放首歌出来）
        final String norm = MediaFormats.norm(text);
        // 正在看片时，序号默认指**这个视频合集**：用户说「播放第三个」「播放第三首」
        // 都是想换一部片，不是在点歌。以前被「刚报过清单」或「量词=首」抢走，
        // 结果视频合集里怎么说都点不动（真机反馈）。
        final boolean mentionsAudio = norm.contains("歌") || norm.contains("音乐")
                || norm.contains("曲") || norm.contains("音频");
        if (videoMode && !videoQueue.isEmpty() && !mentionsAudio) {
            return pickVideoQueue(n);
        }

        if (surveyFresh) {
            java.util.List<MediaSurvey.Entry> items = surveyItems(preferKind);
            if (n <= items.size()) {
                MediaSurvey.Entry e = items.get(n - 1);
                if (openSurveyEntry(e, n)) {
                    if (!(e.item instanceof NetDiskLibrary.Dir)) {
                        persistStatus("按清单播放第 " + n + " 个《" + e.title + "》");
                    }
                    return true;
                }
            } else {
                localHint("刚才的清单一共 " + items.size() + " 个，没有第 " + n + " 个");
                return true;
            }
        }

        if (videoMode) {
            return pickVideoQueue(n);
        }

        if (filePlaying && !fileQueue.isEmpty()) {
            java.util.List<Integer> idxs = new java.util.ArrayList<>();
            for (int i = 0; i < fileQueue.size(); i++) {
                PlayItem it = fileQueue.get(i);
                if (preferKind == MediaKind.VIDEO && !it.isVideo()) continue;
                if (preferKind == MediaKind.AUDIO && it.isVideo()) continue;
                idxs.add(i);
            }
            if (!idxs.isEmpty()) {
                if (n > idxs.size()) {
                    localHint("播放列表里只有 " + idxs.size() + " 个");
                    return true;
                }
                final PlayItem picked = fileQueue.get(idxs.get(n - 1));
                if (client != null && client.isReady()) client.sendAbort();
                suppressAiUntil = System.currentTimeMillis() + 2500;
                // 走 playQueueItem 而不是 playFileAt：视频容器要开画面，
                // 不能因为"从播放列表里挑的"就只出声。
                ui.post(() -> playQueueItem(picked));
                persistStatus("播放队列：跳到第 " + n + " 个《" + picked.displayTitle() + "》");
                return true;
            }
        }

        java.util.List<MediaSurvey.Entry> items = surveyItems(preferKind);
        if (items.isEmpty()) { localHint("现在没有可播放的内容"); return true; }
        if (n > items.size()) {
            localHint("一共只有 " + items.size() + " 个，没有第 " + n + " 个");
            return true;
        }
        MediaSurvey.Entry e = items.get(n - 1);
        if (!openSurveyEntry(e, n)) localHint("《" + e.title + "》播放失败，换一个试试");
        else if (!(e.item instanceof NetDiskLibrary.Dir)) {
            persistStatus("按清单播放第 " + n + " 个《" + e.title + "》");
        }
        return true;
    }

    /** 视频页在前台时按序号换片（队列只含能出画面的条目）。 */
    private boolean pickVideoQueue(int n) {
        if (videoQueue.isEmpty()) { localHint("现在没有在放视频"); return true; }
        if (n > videoQueue.size()) {
            localHint("视频只有 " + videoQueue.size() + " 个，没有第 " + n + " 个");
            return true;
        }
        final PlayItem it = videoQueue.get(n - 1);
        videoIndex = n - 1;
        if (client != null && client.isReady()) client.sendAbort();
        suppressAiUntil = System.currentTimeMillis() + 2500;
        addEvent("▶ 视频切到第 " + n + " 个：" + it.displayTitle());
        playVideoItem(it, videoQueue);
        persistStatus("视频：跳到第 " + n + " 个《" + it.displayTitle() + "》");
        return true;
    }

    /** 最近一次清单；过期、为空或类型不符就现扫。 */
    private java.util.List<MediaSurvey.Entry> surveyItems() {
        return surveyItems(MediaKind.ANY);
    }

    /**
     * @param kind 想要哪一类：清单里若混着别的类型（比如上次问的是「全部」），
     *             就按这个再过滤一遍 —— 「第 3 首」不能数到一部电影上去。
     */
    private java.util.List<MediaSurvey.Entry> surveyItems(int kind) {
        java.util.List<MediaSurvey.Entry> items;
        synchronized (lastSurvey) { items = new java.util.ArrayList<>(lastSurvey); }
        boolean stale = items.isEmpty()
                || System.currentTimeMillis() - lastSurveyAt > SURVEY_TTL_MS;
        if (stale) return buildSurvey(kind);
        if (kind == MediaKind.ANY) return items;
        // 上次清单的类型和这次要的一致就直接用，否则重扫
        if (lastSurveyKind == kind || lastSurveyKind == MediaKind.ANY) {
            boolean hasOther = false;
            for (MediaSurvey.Entry e : items) {
                if (kind == MediaKind.AUDIO && e.video) { hasOther = true; break; }
                if (kind == MediaKind.VIDEO && !e.video) { hasOther = true; break; }
            }
            if (!hasOther) return items;
        }
        return buildSurvey(kind);
    }

    /** 按清单序号播（给 MCP 用）；清单空返回 null，让调用方继续按名字找。 */
    private String playByIndex(int n) {
        java.util.List<MediaSurvey.Entry> items = surveyItems();
        if (items.isEmpty()) return null;
        if (n > items.size()) return "清单里只有 " + items.size() + " 个，没有第 " + n + " 个。";
        MediaSurvey.Entry e = items.get(n - 1);
        if (e.item instanceof NetDiskLibrary.Dir) {
            enterNetDir(((NetDiskLibrary.Dir) e.item).path);
            return "第 " + n + " 个「" + e.title + "」是网络盘里的文件夹，已经进去，下面是里面的内容。";
        }
        if (!playAnyEntry(e)) return "第 " + n + " 个《" + e.title + "》播放失败，换一个试试。";
        return "已开始播放清单里第 " + n + " 个《" + e.title + "》"
                + (e.video ? "（视频）" : "") + "，来源：" + MediaSurvey.srcName(e.src) + "。";
    }

    /** 清单条目 → 真正的动作：**文件夹是「进去」，其余是「播放」**。 */
    private boolean openSurveyEntry(MediaSurvey.Entry e, int n) {
        if (e == null) return false;
        if (e.item instanceof NetDiskLibrary.Dir) {
            enterNetDir(((NetDiskLibrary.Dir) e.item).path);
            return true;
        }
        return playAnyEntry(e);
    }

    /** 清单条目 → 真正的播放调用（三种来源各走原来的路径）。 */
    private boolean playAnyEntry(MediaSurvey.Entry e) {
        if (e == null) return false;
        if (e.src == MediaSurvey.SRC_CLOUD) {
            try { return playLocalTitle(e.pcmTitle); } catch (Throwable t) { return false; }
        }
        return playQueueItem(e.item);
    }

    /**
     * 播一个「队列里的条目」：视频容器开画面，音频容器只出声。
     *
     * 注意这里传的 utterance 是 null —— 类型交给 {@link MediaKind} 判（会用文件头复核），
     * 而不是照扩展名硬来：mp4 里只有一条音轨的那种会自动落回只听模式，不会开个黑屏。
     */
    private boolean playQueueItem(PlayItem it) {
        if (it == null) return false;
        try {
            // 文件夹不是能播的东西：进目录，别拿它去 openDataSource（会一路空转到报错）
            if (it instanceof NetDiskLibrary.Dir) {
                enterNetDir(((NetDiskLibrary.Dir) it).path);
                return true;
            }
            if (it instanceof LocalFiles.Track) return playFileTrack((LocalFiles.Track) it, null);
            if (it instanceof RemoteLibrary.Track) return playRemoteTrack((RemoteLibrary.Track) it, null);
            if (it instanceof NetDiskLibrary.Track) return playNetTrack((NetDiskLibrary.Track) it, null);
        } catch (Throwable t) {
            Log.e(TAG, "playQueueItem failed", t);
        }
        return false;
    }

    /**
     * 进度控制：「跳到 2 分钟」「快进 30 秒」「放到一半」「现在放到哪了」。
     *
     * 三种播放器各有各的 seek 方式（视频靠广播让 VideoActivity 自己跳、本地文件走
     * MediaPlayer.seekTo、云端缓存改采样断点重喂），共同点是**都不经过服务端**：
     * 服务端既没有 seek 能力，还会顺手把这路声音打断。
     */
    private boolean handlePositionIntent(String text) {
        if (text == null) return false;
        final String s = MediaFormats.norm(text);

        // 「放到哪了 / 还剩多久」：只报进度，不动播放
        if (containsAny(s, new String[]{"放到哪", "到哪了", "播到哪", "进度", "还剩", "还有多久"})) {
            if (videoMode) { sendVideoCmd("where"); return true; }
            if (filePlaying && filePlayer != null && filePlayer.hasTrack()) {
                localHint("已放到 " + MediaFormats.fmt(filePlayer.positionMs())
                        + " / " + MediaFormats.fmt(filePlayer.durationMs()));
                return true;
            }
            if (localPlayback) {
                int sr = player.getSampleRate();
                if (sr > 0) {
                    localHint("已放到 " + MediaFormats.fmt((int) (player.playedSamples() * 1000 / sr))
                            + " / " + MediaFormats.fmt((int) (music.totalSamples() * 1000 / sr)));
                    return true;
                }
            }
        }

        final MediaFormats.TimeSpec t = MediaFormats.parseTime(text);
        if (!t.valid()) return false;
        if (!MediaFormats.looksLikeSeek(text)) return false;
        final boolean wantPlay = containsAny(s, new String[]{"开始", "播放", "继续", "接着"});

        // 1) 视频（画面在 VideoActivity，位置也只有它知道）
        if (videoMode) {
            if (client != null && client.isReady()) client.sendAbort();
            suppressAiUntil = System.currentTimeMillis() + 3000;
            // 云端对「调进度」这类话只会回「我没有这个功能」——它压根不知道本地在放什么。
            // 那条字幕必须掐掉，但只掐一条（见 suppressAiOnceUntil），
            // 免得用户紧接着问别的问题也听不到答案。
            suppressAiOnceUntil = System.currentTimeMillis() + 15000;
            if (t.hasDelta) sendVideoCmdRel(t.deltaMs, wantPlay);
            else if (t.pct >= 0) sendVideoCmdPct(t.pct, wantPlay);
            else sendVideoCmdSeek(t.absMs, wantPlay);
            persistStatus("视频：跳转 " + t);
            return true;
        }

        // 2) 本地音视频文件：MediaPlayer 自己记位置，seek 后位置不丢
        if (filePlaying && filePlayer != null && filePlayer.hasTrack()) {
            final int dur = filePlayer.durationMs();
            int target;
            if (t.hasDelta) target = filePlayer.positionMs() + t.deltaMs;
            else if (t.pct >= 0) target = dur * t.pct / 100;
            else target = t.absMs;
            if (dur > 0) target = Math.min(target, Math.max(0, dur - 800));
            final int tgt = Math.max(0, target);
            final boolean play = wantPlay;
            final String curTitle = currentFileTitle();
            suppressAiOnceUntil = System.currentTimeMillis() + 15000;
            ui.post(() -> {
                if (destroyed || !filePlaying) return;
                if (client != null && client.isReady()) client.sendAbort();
                suppressAiUntil = System.currentTimeMillis() + 3000;
                if (!filePlayer.isPrepared()) { localHint("还在准备，等一秒再跳"); return; }
                if (filePlayer.seekTo(tgt)) {
                    if (play) filePlayer.resume();
                    // 进度条立刻跟到新位置（见 pokeNowBar：MediaPlayer 读数有几十毫秒滞后）
                    pokeNowBar();
                    persistStatus("本地文件跳转 " + tgt + "ms" + (play ? "（继续播）" : ""));
                    // 落点只记进聊天记录：时间已经在进度条上了，顶栏再写一遍
                    // 屏幕上就有两条不同的进度，反而容易看混。
                    ui.postDelayed(() -> addEvent("» " + curTitle + " 已跳到 "
                            + MediaFormats.fmt(filePlayer.positionMs())
                            + (dur > 0 ? " / " + MediaFormats.fmt(dur) : "")), 300);
                } else {
                    localHint("这个文件跳不了");
                }
            });
            return true;
        }

        // 3) 云端缓存（PCM）：改断点后从新位置重新喂，不走服务端
        if (localPlayback) {
            final int sr = player.getSampleRate();
            if (sr <= 0) return false;
            final int dur = (int) (music.totalSamples() * 1000 / sr);
            if (dur <= 0) return false;
            int target;
            if (t.hasDelta) target = (int) (player.playedSamples() * 1000 / sr) + t.deltaMs;
            else if (t.pct >= 0) target = dur * t.pct / 100;
            else target = t.absMs;
            target = Math.max(0, Math.min(target, Math.max(0, dur - 1000)));
            if (client != null && client.isReady()) client.sendAbort();
            suppressAiUntil = System.currentTimeMillis() + 3000;
            suppressAiOnceUntil = System.currentTimeMillis() + 15000;
            seekPcm(target);
            persistStatus("云端缓存跳转 " + target + "ms");
            final int at = target;
            runOnUiThread(() -> addEvent("» 云端曲目 已跳到 " + MediaFormats.fmt(at)
                    + " / " + MediaFormats.fmt(dur)));
            return true;
        }
        return false;
    }

    /** 当前正在放的本地文件名（进度确认里要用）。 */
    private String currentFileTitle() {
        try {
            if (fileIndex >= 0 && fileIndex < fileQueue.size()) {
                return fileQueue.get(fileIndex).displayTitle();
            }
        } catch (Throwable ignore) {}
        return "当前文件";
    }

    /** 云端缓存的歌跳进度：按采样定位，重新从新断点往 AudioTrack 喂。 */
    private void seekPcm(int ms) {
        final int sr = player.getSampleRate();
        if (sr <= 0) return;
        long samples = Math.max(0, (long) ms * sr / 1000);
        if (resuming) { resumeAbort = true; resuming = false; }
        long at = music.seekTo(samples);
        player.flush();
        // 把「已播采样」也对齐到新位置，否则之后暂停时算出来的断点会跳回开头
        player.setPlayedSamples(at);
        localPlayback = true;
        kickResume();
    }

    /** 起播/续播两次尝试：上一次的续播线程可能还没退干净（resumeAbort 是异步的）。 */
    private void kickResume() {
        ui.postDelayed(() -> {
            if (!destroyed && !resuming && localPlayback && music.isSuspended()) startResume();
        }, 200);
        ui.postDelayed(() -> {
            if (!destroyed && !resuming && localPlayback && music.isSuspended()) startResume();
        }, 900);
    }

    /**
     * 用户提到了某个格式词（mp4 / avi / mp3…）。
     *
     * 分两种：
     *   · 本机解不了的（avi / rmvb / flv…）→ 直接说明，别丢给云端瞎聊；
     *   · 能放的，但这句话里没有具体名字（「播放 mp4」「放个 flac」）→ 把这种格式的
     *     条目列出来让用户挑（序号可直接说「播放第 2 个」）。
     * 句子里带名字的（「播放 mp4 版的我这一生」）不拦，交给正常点播。
     */
    private boolean handleFormatHint(String text) {
        if (text == null) return false;
        final String ext = MediaFormats.extFromUtterance(text);
        if (ext == null) return false;
        final String s = MediaFormats.norm(text);
        // 清单类问法交给 handleLibraryQuery（它会把五个位置都列出来）
        if (containsAny(s, new String[]{"有哪些", "有什么", "哪些", "清单", "列表", "能放什么"})) {
            return false;
        }

        final int kind = MediaFormats.isVideoExt(ext) ? MediaKind.VIDEO
                : MediaFormats.isAudioExt(ext) ? MediaKind.AUDIO : MediaKind.ANY;

        if (!MediaFormats.playable(ext)) {
            localHint("这台音箱解不了 " + ext + " 格式。能放的是" + MediaFormats.supportedText()
                    + "；其它格式先用电脑转成 mp4 再拷进来。");
            return true;
        }

        // 能放：这句话里要是还夹着具体名字，就走正常点播流程
        try {
            if (localFiles != null && localFiles.match(text, kind) != null) return false;
            if (remote != null && remote.enabled() && remote.match(text, kind) != null) return false;
            if (netDisk != null && netDisk.enabled() && netDisk.match(text, kind) != null) return false;
        } catch (Throwable ignore) {}

        // 纯格式请求：把这种格式的条目列出来
        java.util.List<MediaSurvey.Entry> all = buildSurvey(kind);
        java.util.List<MediaSurvey.Entry> hit = new java.util.ArrayList<>();
        for (MediaSurvey.Entry e : all) {
            if (ext.equals(MediaFormats.extOf(nameOfEntry(e)))) hit.add(e);
        }
        if (hit.isEmpty()) return false;

        rememberSurvey(hit, kind);
        StringBuilder sb = new StringBuilder();
        sb.append("这台音箱有 ").append(hit.size()).append(" 个 ").append(ext).append(" 格式的：");
        int n = Math.min(6, hit.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("、");
            sb.append(i + 1).append(".").append(hit.get(i).label);
        }
        if (hit.size() > n) sb.append(" 等");
        sb.append("。说「播放第几个」就能放。");
        localHint(sb.toString());
        return true;
    }

    /** 条目对应的文件名（含扩展名）。 */
    private static String nameOfEntry(MediaSurvey.Entry e) {
        if (e == null) return "";
        if (e.src == MediaSurvey.SRC_CLOUD) return e.pcmTitle == null ? "" : e.pcmTitle;
        String s = e.source();
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    /** 记下这次清单，供「播放第 N 个」使用。 */
    private void rememberSurvey(java.util.List<MediaSurvey.Entry> items, int kind) {
        synchronized (lastSurvey) {
            lastSurvey.clear();
            if (items != null) lastSurvey.addAll(items);
        }
        lastSurveyAt = System.currentTimeMillis();
        lastSurveyKind = kind;
    }

    /** 只在本机上屏/记状态的提示（没有本地 TTS，声音出不来）。 */
    private void localHint(String text) {
        if (text == null) return;
        persistStatus(text);
        runOnUiThread(() -> {
            addEvent("ⓘ " + text);
            statusText.setText(text);
        });
    }

    private void sendVideoCmdSeek(int posMs, boolean play) {
        Intent i = new Intent(A_VIDEO_CMD);
        i.putExtra("cmd", "seek");
        i.putExtra("pos", Math.max(0, posMs));
        i.putExtra("play", play);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        Log.i(TAG, "video cmd -> seek " + posMs + " play=" + play);
    }

    private void sendVideoCmdRel(int deltaMs, boolean play) {
        Intent i = new Intent(A_VIDEO_CMD);
        i.putExtra("cmd", "seekrel");
        i.putExtra("delta", deltaMs);
        i.putExtra("play", play);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        Log.i(TAG, "video cmd -> seekrel " + deltaMs);
    }

    private void sendVideoCmdPct(int pct, boolean play) {
        Intent i = new Intent(A_VIDEO_CMD);
        i.putExtra("cmd", "seekpct");
        i.putExtra("pct", pct);
        i.putExtra("play", play);
        i.setPackage(getPackageName());
        sendBroadcast(i);
        Log.i(TAG, "video cmd -> seekpct " + pct);
    }

    /**
     * 本地文件播放期间的音乐指令：上一首 / 下一首 / 暂停 / 继续。
     * 「暂停」走 MediaPlayer.pause()，位置由播放器自己记着，所以不存在「从头开始」。
     */
    private boolean handleFileIntent(String text) {
        if (!filePlaying || filePlayer == null) return false;
        final String s = text.replace(" ", "").replace("，", "").replace("。", "")
                .replace("！", "").replace("!", "");
        final boolean next = containsAny(s, NEXT_WORDS);
        final boolean prev = containsAny(s, PREV_WORDS);
        final boolean resume = containsAny(s, RESUME_WORDS);
        final boolean stop = containsAny(s, STOP_WORDS);
        if (!next && !prev && !resume && !stop) return false;
        // 所有播放器操作都甩到主线程
        ui.post(() -> {
            if (destroyed || !filePlaying) return;
            // 关键：本地已接管这条音乐指令，必须把服务端对同一句话的反应掐掉。
            // 否则服务端（它也在服务端自己做的 ASR 里「听见」了这句话）会自己开播/续播
            // 另一首歌，和本地这首叠成双声道。
            if (client != null && client.isReady()) client.sendAbort();
            suppressAiUntil = System.currentTimeMillis() + 3000;
            if (next) {
                persistStatus("本地文件：下一首");
                nextFile(false);
            } else if (prev) {
                persistStatus("本地文件：上一首");
                prevFile();
            } else if (resume) {
                if (filePlayer.isPaused()) {
                    filePlayer.resume();
                    fileAutoResume = false;
                    persistStatus("本地文件继续播放 · 位置 " + filePlayer.positionMs() + "ms");
                } else {
                    persistStatus("本地文件已在播放，忽略「继续」");
                }
            } else {
                filePlayer.pause();          // 暂停而非停止：位置留着，说「继续」能接上
                fileAutoResume = false;
                persistStatus("本地文件暂停 · 位置 " + filePlayer.positionMs() + "ms");
            }
        });
        return true;
    }

    /** 本地文件播放让位给一句提问：暂停，答完自动接上。 */
    private void pauseFileForTalk() {
        if (!filePlaying || filePlayer == null) return;
        ui.post(() -> {
            if (destroyed || !filePlaying || !filePlayer.isPlaying()) return;
            filePlayer.pause();
            fileAutoResume = true;
            persistStatus("本地文件暂停让位 · 位置 " + filePlayer.positionMs() + "ms");
        });
    }

    private static final String[] NEXT_WORDS = {
            "下一首", "下一个", "下首", "换一首", "换一个", "换首歌", "切歌", "下一曲"
    };
    private static final String[] PREV_WORDS = {
            "上一首", "上一个", "上首", "前一首", "上一曲"
    };

    /** 从本地曲库播放整首（走的是和断点续播同一条路径，只是数据来自文件）。 */
    private boolean playLocalTitle(String title) {
        final int[] meta = library.meta(title);
        if (meta == null) return false;
        final short[] pcm = library.load(title);
        if (pcm == null || pcm.length == 0) return false;

        // 本地文件可能在放，先收掉，别两路声音叠在一起
        if (filePlaying) resetFileState();
        // 掐掉服务端可能已经开始生成的那一份，避免两份声音叠加
        if (client != null && client.isReady()) client.sendAbort();
        if (resuming) { resumeAbort = true; resuming = false; }
        if (refilling) cancelRefill("本地曲库接管");

        player.setSampleRate(meta[0]);
        player.flush();
        player.resetPlayed();
        player.setSilent(false);
        music.loadFrom(title, pcm, pcm.length);
        localPlayback = true;
        persistStatus("本地曲库播放《" + title + "》· "
                + (pcm.length / Math.max(1, meta[0])) + "s");
        ui.postDelayed(this::startResume, 60);
        return true;
    }

    /** 本地曲库播放让位给服务端对话：暂停并记下断点，之后「继续播放」能接上。 */
    private void stopLocalPlayback() {
        if (!localPlayback || !resuming) return;
        resumeAbort = true;
        resuming = false;
        final long played = player.playedSamples();
        music.suspend(played);
        player.flush();
        ui.post(() -> player.setSilent(false));
        persistStatus("本地播放暂停 · 断点 " + played);
    }

    private static final String[] VOL_UP_WORDS = {
            "音量大", "声音大", "调大", "大一点", "大点声", "大声点", "放大声", "提高音量",
            "音量调高", "声音调高", "音量加", "加音量", "太小声", "调高点", "大声一点"
    };
    private static final String[] VOL_DOWN_WORDS = {
            "音量小", "声音小", "调小", "小一点", "小点声", "小声点", "放小声", "降低音量",
            "音量调低", "声音调低", "音量减", "减音量", "太大声", "调低点", "小一点声"
    };
    /** 「启动时应用默认音量」本进程只做一次（recreate / 返回设置页不重复压音量）。 */
    private static boolean startVolumeApplied = false;
    /** 上一次生效的「额外扫描目录」；设置页改了就失效本地缓存。 */
    private String appliedMediaDirs = null;
    /** 音量唯一出口：语音 / 启动音量 / MCP 全走它，保证和机身音量键同一把尺子。 */
    private VolumeCtl volume;

    /**
     * 音量类指令：本地立即执行。
     *
     * <p>全部走 {@link VolumeCtl}，和机身音量键同一把尺子：
     * 「大声点」= 往上一档（1 档 = 按一次机身键）；「音量调到 40」= 落到最近档位，
     * 报出来的是**实际**百分比（40 → 33% 就报 33%），不再出现屏幕上写 40%、实际停在 33% 的情况。
     *
     * @return true 表示这句是音量指令，已经处理完（调用方不要再走音乐/对话判断）
     */
    private boolean handleVolumeIntent(String text) {
        if (text == null || text.isEmpty()) return false;
        final String s = text.replace(" ", "").replace("，", "").replace("。", "")
                .replace("！", "").replace("!", "").replace("？", "");
        final boolean up = containsAny(s, VOL_UP_WORDS);
        final boolean down = containsAny(s, VOL_DOWN_WORDS);
        final boolean volWord = s.contains("音量") || s.contains("声音")
                || s.contains("大声") || s.contains("小声");
        if (!volWord && !up && !down) return false;
        if (volume == null) volume = new VolumeCtl(this);
        if (!volume.ok()) return false;

        int askedPct = -1;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d{1,3})").matcher(s);
        final boolean absolute = s.contains("调到") || s.contains("设为") || s.contains("设成")
                || s.contains("变成") || s.contains("百分之");
        if (m.find()) {
            try {
                int v = Integer.parseInt(m.group(1));
                if (v >= 0 && v <= 100 && (absolute || (!up && !down))) askedPct = v;
            } catch (Throwable ignore) {}
        }
        final int beforeIdx = volume.idx();
        final int beforePct = volume.pct();
        noteSystemVolumeTouch();          // 自己改的，别让监听器当成"用户按了机身键"再喊一遍

        // 到顶/到底先说清楚，别让它掉进「调不动」的失败分支
        final String edge = (up && !down) ? (beforeIdx >= volume.max() ? "已经最大了" : "")
                : (down && !up) ? (beforeIdx <= 0 ? "已经最小了" : "") : "";

        final boolean acted;
        if (askedPct >= 0) {
            acted = volume.setPct(askedPct);
        } else if (up && !down) {
            acted = volume.step(+1, volume.steps());
        } else if (down && !up) {
            acted = volume.step(-1, volume.steps());
        } else {
            return false;
        }
        if (!acted) {
            if (!edge.isEmpty()) {
                final String label = volume.label();
                persistStatus("本地调音量：已到边界（" + label + "）");
                runOnUiThread(() -> {
                    addEvent("音量 " + label + " · " + edge);
                    statusText.setText("音量 " + label);
                });
            } else {
                persistStatus("本地调音量失败");
                localHint("音量调不动");
            }
            return true;
        }
        final int afterIdx = volume.idx();
        final int afterPct = volume.pct();
        persistStatus("本地调音量 " + beforePct + "%→" + afterPct + "%（"
                + beforeIdx + "→" + afterIdx + " / " + volume.max() + " 档）");
        final String label = volume.label();
        runOnUiThread(() -> {
            addEvent("音量 " + label);
            statusText.setText("音量 " + label);
        });
        return true;
    }

    /* ==================== 机身音量键 → 界面同步 ==================== */

    /** 上一次「我们自己动过音量」的时间戳；监听器在这个窗口内不重复提示。 */
    private long volSelfTouchAt = 0L;
    private int volLastSeenIdx = -1;
    private android.database.ContentObserver volObserver;
    private Runnable volRestoreStatus;

    private void noteSystemVolumeTouch() {
        volSelfTouchAt = System.currentTimeMillis();
        if (volume != null) volLastSeenIdx = volume.idx();
    }

    /**
     * 盯着系统音量设置：用户按机身音量键时，这里立刻读到新档位并顶栏提示一下。
     *
     * <p>Android 8 的 STREAM_MUSIC 音量按输出设备分开持久化（`volume_music_speaker` 等），
     * 键按下会写 Settings.System，所以用 ContentObserver 比轮询省电也更即时。
     */
    private void watchSystemVolume() {
        if (volObserver != null || volume == null) return;
        if (volume.max() <= 0) return;
        volLastSeenIdx = volume.idx();
        volObserver = new android.database.ContentObserver(ui) {
            @Override public void onChange(boolean self) {
                // 拿不到具体 key（API 26 只给集合 Uri），改为「读一次档位、变了才算音量事件」，
                // 这样其它系统设置变化自然被过滤掉。
                if (volume == null || !volume.ok()) return;
                final int now = volume.idx();
                if (now == volLastSeenIdx) return;
                volLastSeenIdx = now;
                if (System.currentTimeMillis() - volSelfTouchAt < 1200) return;
                final String label = volume.label();
                persistStatus("系统音量变化 → " + now + "/" + volume.max() + " 档");
                ui.post(() -> flashVolume(label));
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.System.CONTENT_URI, true, volObserver);
        } catch (Throwable t) {
            persistStatus("音量监听注册失败 " + t);
            volObserver = null;
        }
    }

    /** 顶栏短暂显示音量，2.5 秒后恢复原来那句（就绪 / 连续对话已开启）。 */
    private void flashVolume(String label) {
        final String keep = statusText.getText().toString();
        statusText.setText("音量 " + label);
        if (volRestoreStatus != null) ui.removeCallbacks(volRestoreStatus);
        volRestoreStatus = () -> {
            if (statusText.getText().toString().startsWith("音量 ")) statusText.setText(keep);
        };
        ui.postDelayed(volRestoreStatus, 2500);
    }

    @Override
    public void onAiSentence(String text) {
        // 本地刚接管了这条指令（播放/清单）：服务端对同一句话的回应是与事实不符的，
        // 声音已被 abort 掐掉，这里把它的字幕也吞掉，免得屏幕上自相矛盾。
        if (System.currentTimeMillis() < suppressAiUntil) {
            persistStatus("吞掉服务端的无关回答：" + text);
            return;
        }
        // 进度类指令：只吞一条「我没有这个功能」式的错误回答（见 suppressAiOnceUntil）
        if (suppressAiOnceUntil > 0 && System.currentTimeMillis() < suppressAiOnceUntil) {
            suppressAiOnceUntil = 0;
            persistStatus("吞掉服务端对进度指令的错误回答：" + text);
            return;
        }
        // 清单查询还在等云端调工具：它一张嘴就是「我这边看不到 / 曲库里没有」，就地掐掉
        if (listPendingUntil > System.currentTimeMillis() && !listToolCalled) {
            if (isDenialAnswer(text)) {
                persistStatus("清单等待中：掐掉服务端否认式回答 " + text);
                Log.i(TAG, "list: 云端否认式回答，已 abort：" + text);
                // T3：掐掉之后必须接着走「重试 → 本地兜底」，不能就这么静默结束
                onListAttemptFailed("否认式回答");
                return;
            }
            // 它说的是别的（多半已经取过清单在正常回答），不再盯这一轮
            listPendingUntil = 0;
        }
        // 清单回答轮次：云端念的就是屏幕上那份清单，字幕再贴一遍就成了「两条重复回复」
        // （用户实测反馈）。声音照常放 —— 用户要的是「听得见」，屏幕上留本地那份带序号的。
        if (listSilenceUntil > System.currentTimeMillis()) {
            persistStatus("清单轮次：云端字幕只出声不上屏：" + text);
            Log.i(TAG, "list: 静默云端复述字幕 " + text);
            listSilenceUntil = Math.min(System.currentTimeMillis() + LIST_SILENCE_MS, listSilenceCap);
            return;
        }
        music.addLine(text);                  // 逐句进缓存（放歌时才有意义）
        if (videoMode) sendVideoSub(text);    // 字幕同步到视频页底栏
        if (refilling) return;                // 补流静默进行，不刷界面
        runOnUiThread(() -> appendAiSentence(text));
    }

    @Override
    public void onMarker(String raw) {
        if (raw == null || !raw.contains("play_music")) return;
        if (refilling) {
            // 补流请求生效了：整首歌重新下发，只填首发没拿到的句子，断点保持不变
            refillActive = true;
            ui.removeCallbacks(refillPendingWatchdog);
            music.refillStart();
            runOnUiThread(() -> statusText.setText("正在补齐这首歌后面的部分…"));
            persistStatus("补流：整首重新下发，只补空白的句子");
            return;
        }
        if (resuming) { resumeAbort = true; resuming = false; }
        music.begin();
        localPlayback = false;                  // 服务端这首歌接管播放
        refillFails = 0;
        refillSuppressUntil = 0;
        player.resetPlayed();
        player.setSilent(false);
        persistStatus("检测到 play_music：开始缓存这首歌");
    }

    @Override
    public void onSpeakStart() {
        runOnUiThread(() -> {
            inAiTurn = true;
            currentAiMsg = null;
            currentAiBubble = null;
        });
        if (videoMode) {
            sendVideoSub("…");
            // 半双工：云端一开口就把画面停住。不这么做的话，台词和回答会糊在一起，
            // 而且这台机器的喇叭只有一个，谁也不想听两路声音抢声道。
            if (!videoHold) sendVideoCmd("pause");
        }
    }

    @Override
    public void onSpeakEnd() {
        // 这一轮说完了 → 清单复述已经念完，字幕静默到此为止。
        // 用 tts stop 收尾比按用户是否开口判断准得多：云端念 14 条要二十几秒，
        // 用户完全可能在中间插话（那时清单还在念，残句不能放出来）。
        listSilenceUntil = 0;
        if (refilling) {
            // 补流请求发出去后，先会有别的回复（如「好啦，停下来了」）的 tts stop，
            // 那种不能算补流结束 —— 只有收到 play_music 之后的那一轮才算。
            if (refillActive) { finishRefill(); return; }
            runOnUiThread(() -> inAiTurn = false);
            return;
        }
        // 全双工时音乐还在往下发，这里的 tts stop 可能只是「音量已调大」那句回复结束，
        // 不能据此认为这首歌已经下完，否则后续歌词就不再进缓存了。
        if (!duplex) music.endTurn();
        // 这一轮放完：把缓存下来的整首存进本地曲库 —— 下次再放就完全本地，
        // 说话、调音量、断网都不会影响它（首发这一遍仍走服务端，是唯一会断的一次）。
        // 只存「自然放完」的：用户中途喊停的那份是残缺的，等补流补全再存。
        if (!duplex && !music.isSuspended()) saveMusicToLibrary();
        runOnUiThread(() -> {
            inAiTurn = false;
            // 连续对话：这一句播完就自动继续听
            if (continuous && micEnabled && !talking) {
                ui.removeCallbacks(continuousListen);
                continuousTries = 0;
                ui.postDelayed(continuousListen, 350);
            }
        });
        // 本地曲库的歌被一句提问让开了：服务端答完就自动接着放，不用用户再说「继续播放」
        if (localPlayback && music.isSuspended() && !resuming && !talking && !continuous) {
            ui.postDelayed(this::startResume, 600);
        }
        // 本地文件同理：让开的那一首答完自动接上（位置由 MediaPlayer 记着，不会从头）
        if (filePlaying && fileAutoResume && !talking && !continuous) {
            ui.postDelayed(() -> {
                if (destroyed || talking) return;
                if (filePlaying && filePlayer.isPaused()) {
                    filePlayer.resume();
                    fileAutoResume = false;
                    persistStatus("本地文件自动续播 · 位置 " + filePlayer.positionMs() + "ms");
                }
            }, 600);
        }
        // 暂停中的歌：等这轮回复说完，静默把还没拿到的句子补回来，
        // 这样用户下次说「继续播放」时能一路放下去，而不是放几秒就断。
        if (music.isSuspended() && !music.isComplete() && !resuming
                && !music.isGaveUp() && !talking) {
            ui.removeCallbacks(refillKick);
            ui.postDelayed(refillKick, 1200);
        }
        // 视频页：这一轮回答说完就自动接着放画面（用户自己喊过「暂停」的除外）
        if (videoMode && !videoHold && !talking) {
            ui.postDelayed(() -> {
                if (videoMode && !videoHold) {
                    sendVideoCmd("resume");
                    persistStatus("视频：回答结束，自动继续");
                }
            }, 500);
        }
    }

    @Override
    public void onBinary(byte[] data) {
        if (!destroyed) player.playOpus(data, data.length);
    }

    @Override
    public void onError(String msg) {
        persistStatus("错误: " + msg);
        runOnUiThread(() -> Toast.makeText(this, "错误: " + msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onServerAudio(int sampleRate, int channels) {
        persistStatus("下行音频: " + sampleRate + "Hz/" + channels + "ch");
        if (!destroyed) player.setSampleRate(sampleRate);
    }

    @Override
    public void onNotify(String audioUrl, String subtitle) {
        persistStatus("notify 远程音频 " + audioUrl);
        runOnUiThread(() -> {
            addEvent(!subtitle.isEmpty() ? "▶ 正在播放：" + subtitle : "▶ 正在播放…");
            statusText.setText("正在播放音频…");
        });
        notifyPlayer.play(audioUrl, () -> runOnUiThread(() -> {
            persistStatus("远程音频播放结束");
            statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
        }));
    }

    /* ==================== 音乐：断点续播 ==================== */

    private static final String[] STOP_WORDS = {
            "停止播放", "停下来", "停一下", "停下", "别唱", "不要唱", "别放了", "不要放",
            "关掉音乐", "关掉歌", "关音乐", "暂停", "停播", "不听了",
            // 「停止」单独说也得认：之前只收「停止播放」，用户喊「停止」会被当成普通提问，
            // 结果只做到「让位暂停」而不是真的停
            "停止", "别播了"
    };
    private static final String[] RESUME_WORDS = {
            "继续播放", "继续唱", "继续放", "接着播放", "接着唱", "接着放",
            "往下放", "往下唱", "别停", "继续", "接着"
    };

    /** 停下之后等一小会儿再静默补流（让服务端把这轮回复说完）。 */
    private final Runnable refillKick = new Runnable() {
        @Override
        public void run() {
            if (talking || resuming || destroyed) return;
            startRefill("暂停后自动补齐");
        }
    };

    /**
     * 补流看门狗：不看「总共花了多久」，只看「还在不在进展」。
     *
     * 固定超时会误杀纯音乐 —— 一首 5 分钟的曲子从头重发本来就要 5 分钟，
     * 掐掉之后又会重新补，形成循环。现在只要缓存还在增长就一直续期，
     * 连续 60 秒没长出任何一个采样才判定停滞。
     */
    private final Runnable refillWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!refilling) return;
            final long total = music.totalSamples();
            if (total > refillLastTotal) {
                refillLastTotal = total;
                refillStall = 0;
            } else {
                refillStall++;
            }
            if (music.isCapped()) {
                persistStatus("补流到缓存上限，按已缓存部分收工");
                finishRefill();
                return;
            }
            if (refillStall >= 4) {                 // 4 × 15s = 60s 毫无进展
                cancelRefill("补流停滞（60 秒无新增）", true);
                return;
            }
            ui.postDelayed(this, 15000);
        }
    };

    /** 补流请求发出去后 20 秒还没开始播歌（服务端没听懂/换歌了），放弃，别一直静音。 */
    private final Runnable refillPendingWatchdog = new Runnable() {
        @Override
        public void run() {
            if (refilling && !refillActive) cancelRefill("等不到 play_music", true);
        }
    };

    private static boolean containsAny(String s, String[] words) {
        for (String w : words) if (s.contains(w)) return true;
        return false;
    }

    /**
     * 从服务端回传的识别结果里判断「停下 / 继续」。
     *
     * 为什么要在客户端插手：服务端没有真正的 pause/resume，
     * 收到「继续播放」它只会把整首歌从头重新 TTS 一遍。
     * 所以这里在服务端刚要开口之前把它掐掉，改播本地缓存的断点部分。
     */
    private void handleMusicIntent(String text) {
        if (text == null || !music.isActive()) return;
        final String s = text.replace(" ", "").replace("，", "").replace("。", "");
        final boolean resumeWord = containsAny(s, RESUME_WORDS);
        final boolean stopWord = containsAny(s, STOP_WORDS);

        // 正在本地续播时喊停：停掉续播线程，把断点前移到真正播到的位置
        if (resuming && stopWord && !resumeWord) {
            stopResumeAndMark();
            persistStatus("续播中停止 · 断点 " + music.pausePoint());
            return;
        }

        if (music.isSuspended() && resumeWord) {
            // 继续：掐掉服务端即将开始的重播，从本地断点接着放
            if (!music.isComplete()) persistStatus("继续播放：缓存" + music.cachedMs(player.getSampleRate())
                    + "ms / 断点" + music.pausePoint());
            cancelRefill("继续播放，改用本地续播");
            resumeAbort = true;
            player.flush();
            if (client.isReady()) client.sendAbort();
            ui.postDelayed(this::startResume, 280);
            persistStatus("续播请求 · 从采样 " + music.pausePoint() + " 开始");
            return;
        }
        if (!music.isSuspended() && stopWord && !resumeWord
                && (music.isCollecting() || music.isRecentlyPlaying(120000))) {
            // 停下：记断点（往回让 100ms，宁可重一点也别漏掉还没放完的那点缓冲）
            long played = player.playedSamples();
            music.suspend(played);
            music.rewindForBuffer(player.getSampleRate() / 10);
            player.flush();
            persistStatus("音乐暂停 · 播放位置 " + played + "，断点 " + music.pausePoint()
                    + "，缓存共 " + music.totalSamples());
            // 全双工下服务端还在推音乐流，必须显式中止，否则声音照旧在放
            if (client.isReady()) client.sendAbort();
            ui.removeCallbacks(refillKick);
            ui.postDelayed(refillKick, 300);
        }
    }

    /**
     * 打断本地续播，并把断点前移到「真正播到的位置」。
     * 不这么做的话，用户中途喊停 / 直接开口说话，下次「继续播放」会把
     * 刚才已经听过的那一段再放一遍。
     */
    private void stopResumeAndMark() {
        if (!resuming) return;
        resumeAbort = true;
        resuming = false;
        final long played = player.playedSamples();
        music.suspend(played);
        player.flush();
        persistStatus("续播被打断 · 断点前移到 " + played);
    }

    /** 从缓存断点续播（阻塞线程，按播放速度把 PCM 喂给 AudioTrack）。 */    private void startResume() {
        if (!music.isSuspended() || resuming) return;
        final int sr = player.getSampleRate();
        // 缓存里剩下的不到 2 秒：先静静把整首补齐，补完自动接上，别让用户以为没反应
        if (!music.isComplete() && music.totalSamples() - music.pausePoint() < (long) sr * 2) {
            if (music.isGaveUp()) {
                // 已经熔断过（纯音乐补不齐）：别再补流了，直接告诉用户实情
                runOnUiThread(() -> {
                    statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
                    addEvent("⚠ 这首只缓存到停止的位置，后面的拿不到了。"
                            + "想再听就说「播放" + music.title() + "」从头开始。");
                });
                return;
            }
            if (!refilling) {
                autoResumeAfterRefill = true;
                startRefill("续播前缓存不足");
                if (refilling) {
                    runOnUiThread(() -> statusText.setText("正在准备这首歌后面的部分…"));
                    persistStatus("续播推迟：缓存不足，先补流");
                } else {
                    autoResumeAfterRefill = false;
                    runOnUiThread(() -> {
                        statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
                        addEvent("⚠ 后面的部分没取到，重新说「播放」再听一遍吧。");
                    });
                }
            }
            return;
        }
        resuming = true;
        resumeAbort = false;
        // 续播期间把服务端下发的音频静音掉（abort 后还可能有残留帧），
        // 只让本地缓存出声；writePcm 不受 silent 影响。
        player.setSilent(true);
        final long from = music.pausePoint();
        new Thread(() -> {
            boolean ok = false;
            try {
                ok = music.playFrom(from,
                        (pcm, off, len) -> player.writePcm(pcm, off, len),
                        () -> resumeAbort || destroyed);
            } catch (Throwable t) {
                persistStatus("续播异常: " + t);
            }
            if (resumeAbort) { resuming = false; player.setSilent(false); return; }
            resuming = false;
            player.setSilent(false);
            final boolean done = music.isComplete();
            final long total = music.totalSamples();
            persistStatus("续播结束 ok=" + ok + " · 已播 " + player.playedSamples()
                    + " / 缓存 " + total + " complete=" + done);
            if (done) {
                // 整首放完了，会话收尾：留着缓存只会让之后一句「停止」又去补流
                music.resumeDone();
                music.clear();
                localPlayback = false;
            } else {
                music.suspend(player.playedSamples());    // 断点前移，下次「继续」从这儿接
            }
            ui.post(() -> {
                statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
                if (!done) {
                    // 缓存只到停止点，后面的句子还没取回来 —— 说一声，别让用户以为卡住了
                    addEvent("⚠ 本地缓存放完了，后面的部分还在取。"
                            + "等几秒再说「继续播放」就能接着听。");
                }
            });
            if (!done) ui.postDelayed(refillKick, 1500);   // 继续静默补齐剩余部分
        }, "music-resume").start();
    }

    /** 静默重新请求同一首歌，只为把断点之后还没拿到的句子缓存下来（不出声）。 */
    private void startRefill(String why) {
        if (refilling || resuming || destroyed) return;
        if (!music.isSuspended() || music.isComplete()) return;
        if (music.isGaveUp()) {
            persistStatus("补流跳过：这首歌已判定补不齐（" + why + "）");
            return;
        }
        if (System.currentTimeMillis() < refillSuppressUntil) {
            persistStatus("补流冷却中，跳过（" + why + "）");
            return;
        }
        final String t = music.title();
        if (t == null || t.isEmpty()) {
            persistStatus("补流跳过：没拿到歌名");
            return;
        }
        refilling = true;
        refillActive = false;
        refillBase = music.totalSamples();
        refillLastTotal = refillBase;
        refillStall = 0;
        silentQuery = true;
        player.setSilent(true);
        persistStatus("补流开始（" + why + "）· 静默重取《" + t + "》");
        sendTextToCloud("播放" + t);
        ui.removeCallbacks(refillWatchdog);
        ui.postDelayed(refillWatchdog, 15000);           // 之后每 15 秒巡检一次进展
        ui.removeCallbacks(refillPendingWatchdog);
        ui.postDelayed(refillPendingWatchdog, 20000);    // 20 秒还没开始播就放弃
    }

    private void finishRefill() {
        if (!refilling) return;
        refilling = false;
        refillActive = false;
        ui.removeCallbacks(refillWatchdog);
        ui.removeCallbacks(refillPendingWatchdog);
        player.setSilent(false);
        music.endTurn();
        final int sr = player.getSampleRate();
        final long total = music.totalSamples();
        // 判据是「比补流前多攒了多少」，不能只看总量 —— 否则刚补了两秒就被打断也算补齐
        final long added = total - refillBase;
        final boolean ok = sr > 0 && added > (long) sr * 8;    // 至少真的多补了 8 秒
        music.markComplete(ok);
        if (ok) {
            refillFails = 0;
            // 整首补齐了 → 存进本地曲库。下次说「播放XX」就直接本地放，
            // 服务端那路 TTS 流就算被 VAD 打断也影响不到音乐了。
            try {
                if (library != null) library.remove(music.title());   // 补流后这份最全，覆盖旧的
            } catch (Throwable ignore) {}
            saveMusicToLibrary();
        } else {
            // 补了一轮毫无进展（纯音乐只有一句、服务端换了歌…）—— 计数熔断，
            // 否则 onSpeakEnd 会一直再排补流，形成无限静默循环。
            refillFails++;
            if (refillFails >= 2) {
                music.giveUp();
                persistStatus("补流连续 " + refillFails + " 次无进展，放弃这首歌（不再自动补流）");
            }
        }
        persistStatus("补流结束 · 缓存 " + (total / Math.max(1, sr)) + "s（+" +
                (added / Math.max(1, sr)) + "s）complete=" + ok + " fails=" + refillFails);
        if (autoResumeAfterRefill) {
            autoResumeAfterRefill = false;
            if (ok) {
                persistStatus("补流完成，自动接上续播");
                ui.postDelayed(this::startResume, 250);
            } else {
                ui.post(() -> {
                    statusText.setText(continuous ? "连续对话已开启 · 直接说话" : "就绪");
                    addEvent("⚠ 后面的部分没取到，等一会儿再说「继续播放」试试。");
                });
            }
        }
    }

    /** 把当前缓存下来的整首存进本地曲库（已存在则跳过）。 */
    private void saveMusicToLibrary() {
        try {
            if (library == null) return;
            final String t = music.title();
            final int sr = player.getSampleRate();
            if (t == null || t.isEmpty() || sr <= 0) return;
            final long total = music.totalSamples();
            if (total < (long) sr * 20) return;        // 不到 20 秒不值得存
            if (library.has(t)) return;                // 已经在库里
            final short[] all = music.exportAll();
            if (all.length > 0 && library.save(t, all, all.length, sr)) {
                persistStatus("已存入本地曲库《" + t + "》· "
                        + (all.length / Math.max(1, sr)) + "s");
            }
        } catch (Throwable ignore) {}
    }

    private void cancelRefill(String why) { cancelRefill(why, false); }

    /**
     * @param countAsFail true = 这是补流自身的失败（停滞 / 等不到 play_music），
     *                    计入熔断；用户主动打断（开口说话、要续播）不算失败。
     */
    private void cancelRefill(String why, boolean countAsFail) {
        if (!refilling) { autoResumeAfterRefill = false; return; }
        if (countAsFail) {
            refillFails++;
            if (refillFails >= 2) {
                music.giveUp();
                persistStatus("补流失败 " + refillFails + " 次（" + why + "），放弃这首歌");
                runOnUiThread(() -> addEvent("⚠ 这首歌后面的部分一直取不到，先听到这里。想再听就说「播放" + music.title() + "」。"));
            } else {
                // 服务端偶尔只回一句「这就给你放」却不真的下发歌 —— 隔一会儿再试一次
                ui.removeCallbacks(refillKick);
                ui.postDelayed(refillKick, 9000);
            }
        }
        // 用户打断补流后短时间内别再自动补回来：cancel 里发的 abort 会让服务端回一句
        // tts stop，那一轮的 onSpeakEnd 又会排 refillKick，不设冷却就会反复自激。
        refillSuppressUntil = System.currentTimeMillis() + 8000;
        refilling = false;
        refillActive = false;
        autoResumeAfterRefill = false;
        silentQuery = false;
        ui.removeCallbacks(refillWatchdog);
        ui.removeCallbacks(refillPendingWatchdog);
        player.setSilent(false);
        if (client.isReady()) client.sendAbort();
        persistStatus("补流中断（" + why + "）");
    }


    private void persistStatus(String s) {
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "status.txt");
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(new java.util.Date().toString() + "  " + s + "\n");
            w.close();
        } catch (Exception ignore) {}
    }

    /* ---- AudioCapture.Callback ---- */
    @Override
    public void onOpusFrame(byte[] frame, int len) {
        client.sendBinary(frame, len);
    }

    /** 每帧能量 → 连续对话的本地 VAD。 */
    @Override
    public void onLevel(int peak, int rms) {
        if (!autoListening || !talking) return;
        long now = System.currentTimeMillis();
        if (rms > VAD_RMS_SPEECH) {
            voiceFrames++;
            lastVoiceAt = now;
            if (voiceFrames >= VAD_FRAMES_NEEDED) voiceHeard = true;
            return;
        }
        if (voiceHeard) {
            if (now - lastVoiceAt > VAD_TAIL_MS) {
                autoListening = false;
                persistStatus("VAD：停顿 " + VAD_TAIL_MS + "ms，自动提交");
                ui.post(this::stopTalking);
            }
        } else if (now - autoStartAt > VAD_HEAD_MS) {
            autoListening = false;
            persistStatus("VAD：" + (VAD_HEAD_MS / 1000) + " 秒没听到说话，自动关闭麦克风");
            ui.post(this::stopTalking);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacks(stopWatchdog);
        ui.removeCallbacks(pollTask);
        ui.removeCallbacks(continuousListen);
        ui.removeCallbacks(nowTick);
        talking = false;
        // 主界面真被销毁了（不是切去视频页），视频页也别留在那儿
        if (videoMode) {
            videoMode = false;
            try { sendVideoCmd("exit"); } catch (Throwable ignore) {}
        }
        if (capture != null) capture.release();
        if (player != null) player.release();
        if (filePlayer != null) filePlayer.stop();
        if (notifyPlayer != null) notifyPlayer.stop();
        if (tone != null) {
            try { tone.release(); } catch (Throwable ignore) {}
            tone = null;
        }
        if (client != null) client.disconnect();
        try { unregisterReceiver(stopLocalReceiver); } catch (Throwable ignore) {}
        try { unregisterReceiver(videoEventReceiver); } catch (Throwable ignore) {}
        if (volObserver != null) {
            try { getContentResolver().unregisterContentObserver(volObserver); } catch (Throwable ignore) {}
            volObserver = null;
        }
        if (volRestoreStatus != null) {
            ui.removeCallbacks(volRestoreStatus);
            volRestoreStatus = null;
        }
        super.onDestroy();
    }
}
