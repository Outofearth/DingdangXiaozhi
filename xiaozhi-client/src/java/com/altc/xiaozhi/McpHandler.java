package com.altc.xiaozhi;

import android.content.Context;
import android.media.AudioManager;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 设备端 MCP Server（JSON-RPC 2.0 over WebSocket）。
 *
 * 服务端在看到 hello.features.mcp = true 后，会下发：
 *   1) {"method":"initialize","params":{"capabilities":{"vision":{url,token}}}}
 *      —— vision.url 是图片上传端点，token 是 Bearer 令牌。只有拿到了它，
 *         设备拍照后才知道把图送到哪里做视觉理解。
 *   2) {"method":"tools/list"}   —— 询问设备有哪些工具
 *   3) {"method":"tools/call","params":{"name":"self.camera.take_photo","arguments":{"question":"..."}}}
 *
 * 我们注册的工具与官方 ESP32 固件保持一致：self.camera.take_photo。
 * 执行流程：拍照 → JPEG POST 到 vision.url?question=xxx → 把分析文本作为
 * 工具结果（text content）回给模型。
 *
 * 若服务端没有下发 vision.url，则退化为直接回 image content block（base64），
 * 让支持多模态的服务端自己看图。
 */
public class McpHandler {
    private static final String TAG = "McpClient";

    public interface Callback {
        void onStatus(String text);
    }

    /**
     * 音乐库清单提供者（P0）。
     *
     * 云端大模型看不见本机的 /sdcard/Music，也看不见 SMB 共享，所以问「有哪些歌」
     * 它只会瞎答「我这边曲库里没有 / SMB 看不到」。把清单做成 MCP 工具后，
     * 模型调用 → 我们回文本 → 云端 TTS 把它念出来 —— 这是唯一能让本地清单
     * 「出声」的通道（本机没有 TTS 引擎，服务端也不收客户端主动 TTS 请求）。
     *
     * 注意：回调跑在 MCP 自己的单线程池里（不是 WS 回调线程），可以放心做 SMB 扫描。
     */
    public interface MusicProvider {
        /**
         * 供模型朗读的清单文本（自然语言，建议 ≤900 字，太长会被摘要或超时）。
         *
         * @param kind "audio"（只要音乐）/ "video"（只要视频）/ "all"（全都要）。
         *             清单必须覆盖本机、共享文件夹、网络盘、云端缓存、下载缓存五个位置。
         */
        String libraryOverview(String kind);

        /**
         * 按用户说的歌名（可能是简称 / 繁体 / 带艺人名）在本地 + SMB 库里找并真的播出来。
         *
         * 为什么要这个工具：{@link #libraryOverview()} 只让云端「看得见」清单，
         * 它本身没有播放能力 —— 于是会出现「没找到《禅月》…共享库好像没被索引到，我这边放不了」
         * 然后自己从云端曲库捞一首无关的歌塞给用户。给一个真正能播的工具，这条路才闭环。
         *
         * @return 给模型的结果文本（已播放 / 没找到），已带歌名
         */
        String playByQuery(String query);

        /**
         * 云端确实调用了清单工具 —— 调用方可据此取消自己原本要发的 abort。
         *
         * @param kind 云端要的清单类型（"audio"/"video"/"all"）。调用方需要它来自查
         *             「这一轮本地到底有没有贴过清单」，没有就补一份一条一行的版本上屏
         *             （云端会把工具结果改写成一大段话，全部揉在一起）。
         */
        void onLibraryToolCalled(String kind);
    }

    private final Context ctx;
    private final XiaoZhiClient client;
    private final String deviceId;
    private final String clientId;
    private final Callback callback;
    private final OkHttpClient http;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** 音量统一出口（和机身音量键同一把尺子），懒建一次。 */
    private VolumeCtl volCtl;

    /** initialize 下发的视觉上传端点；空串表示服务端没提供视觉能力。 */
    private volatile String visionUrl = "";
    private volatile String visionToken = "";
    /** 是否已经完成 MCP 初始化握手。 */
    private volatile boolean initialized = false;
    /** 清单提供者（MainActivity 注入）。 */
    private volatile MusicProvider musicProvider;

    public void setMusicProvider(MusicProvider p) { this.musicProvider = p; }

    public McpHandler(Context ctx, XiaoZhiClient client, String deviceId, String clientId, Callback cb) {
        this.ctx = ctx;
        this.client = client;
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.callback = cb;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public boolean hasVision() { return !visionUrl.isEmpty(); }
    public String visionUrl() { return visionUrl; }

    /** 是否允许把照片写到本地磁盘（默认关闭，隐私优先）。 */
    private boolean keepLocalPhoto() {
        try {
            return ctx.getSharedPreferences("xz", Context.MODE_PRIVATE)
                    .getBoolean("keepPhoto", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 摄像头是否被用户（软件层）允许：关闭后不再向服务端注册拍照工具。 */
    private boolean cameraAllowed() {
        try {
            return ctx.getSharedPreferences("xz", Context.MODE_PRIVATE)
                    .getBoolean("cameraEnabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 摄像头工具是否可用。
     *
     * {@code Camera.getNumberOfCameras()} 在 HAL 还没就绪时会偶发返回 0，
     * 而 tools/list 整个会话只发生一次 —— 一旦侥幸返回 0，「看看这是什么」就整轮失效。
     * 所以再用系统特性兜底。
     */
    private boolean cameraToolAvailable() {
        boolean viaCamera = false, viaFeature = false;
        try {
            viaCamera = CameraCapture.available();
            if (viaCamera) return true;
        } catch (Throwable ignore) {}
        try {
            viaFeature = ctx.getPackageManager()
                    .hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY);
            if (viaFeature) return true;
        } catch (Throwable t) {
            Log.w(TAG, "hasSystemFeature failed", t);
        }
        Log.i(TAG, "camera tool NOT registered: numCameras>0=" + viaCamera
                + " feature=" + viaFeature + " allowed=" + cameraAllowed());
        return false;
    }
    /** 自检：把一张已有 JPEG 走一遍完整上传链路，返回视觉服务文本（失败返回 null）。 */
    public String selfTest(byte[] jpeg, String question) {
        return uploadAndExplain(jpeg, question);
    }
    public boolean isInitialized() { return initialized; }

    /** 处理服务端下发的 MCP 消息（payload 已从 {"type":"mcp","payload":...} 拆出）。 */
    public void onServerMessage(JSONObject payload) {
        try {
            String method = payload.optString("method", "");
            Object id = payload.opt("id");
            Log.i(TAG, "server method=" + method + " id=" + id);

            if (method.startsWith("notifications/")) return;   // 通知类无需回包

            if ("initialize".equals(method)) {
                parseCapabilities(payload.optJSONObject("params"));
                JSONObject caps = new JSONObject();
                caps.put("tools", new JSONObject());
                JSONObject info = new JSONObject();
                info.put("name", "altc-xiaozhi-android");
                info.put("version", "1.0");
                JSONObject result = new JSONObject();
                result.put("protocolVersion", "2024-11-05");
                result.put("capabilities", caps);
                result.put("serverInfo", info);
                respond(id, result);
                initialized = true;
                Log.i(TAG, "MCP initialized, tools=" + buildToolList().length());
                return;
            }

            if ("tools/list".equals(method)) {
                JSONObject result = new JSONObject();
                result.put("tools", buildToolList());
                respond(id, result);
                return;
            }

            if ("tools/call".equals(method)) {
                JSONObject params = payload.optJSONObject("params");
                String name = params != null ? params.optString("name", "") : "";
                JSONObject args = params != null ? params.optJSONObject("arguments") : null;
                String question = args != null ? args.optString("question", "") : "";
                if ("self.camera.take_photo".equals(name)) {
                    handleTakePhoto(id, question);
                } else if ("self.audio_speaker.set_volume".equals(name)) {
                    int vol = args != null ? args.optInt("volume", -1) : -1;
                    handleSetVolume(id, vol);
                } else if ("self.audio_speaker.get_volume".equals(name)) {
                    respondText(id, "{\"success\": true, \"volume\": " + volumePercent() + "}", false);
                } else if ("self.get_device_status".equals(name)) {
                    respondText(id, deviceStatusJson(), false);
                } else if ("self.music.list".equals(name)) {
                    handleMusicList(id, "audio");
                } else if ("self.media.list".equals(name)) {
                    String kind = args != null ? args.optString("kind", "all") : "all";
                    if (!"video".equalsIgnoreCase(kind) && !"all".equalsIgnoreCase(kind)) kind = "audio";
                    handleMusicList(id, kind.toLowerCase());
                } else if ("self.music.play".equals(name)) {
                    handleMusicPlay(id, args != null ? args.optString("query", "") : "");
                } else if ("self.media.play".equals(name)) {
                    // index 是「清单里第几个」，query 是歌名/视频名；两个都给时 index 优先
                    int idx = args != null ? args.optInt("index", 0) : 0;
                    String q = args != null ? args.optString("query", "") : "";
                    handleMusicPlay(id, idx > 0 ? "第" + idx + "个" : q);
                } else {
                    respondError(id, -32601, "未知工具: " + name);
                }
                return;
            }

            // 未识别的方法：回标准错误，避免服务端一直等
            respondError(id, -32601, "method not found: " + method);
        } catch (Exception e) {
            Log.e(TAG, "handle mcp failed", e);
        }
    }

    private void parseCapabilities(JSONObject params) {
        if (params == null) return;
        JSONObject caps = params.optJSONObject("capabilities");
        if (caps == null) return;
        JSONObject vision = caps.optJSONObject("vision");
        if (vision != null) {
            visionUrl = vision.optString("url", "");
            visionToken = vision.optString("token", "");
            Log.i(TAG, "VISION endpoint=" + visionUrl
                    + " tokenLen=" + visionToken.length());
            if (callback != null && !visionUrl.isEmpty()) {
                callback.onStatus("视觉服务已就绪");
            }
        } else {
            Log.i(TAG, "server has NO vision capability; will fall back to image content");
        }
    }

    private JSONArray buildToolList() throws Exception {
        JSONArray tools = new JSONArray();
        if (cameraToolAvailable() && cameraAllowed()) {
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            JSONObject props = new JSONObject();
            JSONObject q = new JSONObject();
            q.put("type", "string");
            q.put("description", "关于这张照片想问的问题");
            props.put("question", q);
            schema.put("properties", props);
            JSONArray required = new JSONArray();
            required.put("question");
            schema.put("required", required);

            JSONObject tool = new JSONObject();
            tool.put("name", "self.camera.take_photo");
            tool.put("description",
                    "Take a photo and explain it. Use this tool after the user asks you to see something.\n"
                    + "Args:\n  `question`: The question that you want to ask about the photo.\n"
                    + "Return:\n  A JSON object that provides the photo information.");
            tool.put("inputSchema", schema);
            tools.put(tool);
        }

        // 音量 / 设备状态：官方智能体常用这两个工具名，注册后「调大音量」这类指令
        // 才会真的作用到本机（不注册的话服务端要么报错、要么只能干说）
        JSONObject volSchema = new JSONObject();
        volSchema.put("type", "object");
        JSONObject volProps = new JSONObject();
        JSONObject v = new JSONObject();
        v.put("type", "integer");
        v.put("description", "音量百分比 0-100");
        volProps.put("volume", v);
        volSchema.put("properties", volProps);
        JSONArray vreq = new JSONArray();
        vreq.put("volume");
        volSchema.put("required", vreq);

        JSONObject volTool = new JSONObject();
        volTool.put("name", "self.audio_speaker.set_volume");
        volTool.put("description", "Set the speaker volume of this device. Args: `volume` 0-100.");
        volTool.put("inputSchema", volSchema);
        tools.put(volTool);

        JSONObject emptySchema = new JSONObject();
        emptySchema.put("type", "object");
        emptySchema.put("properties", new JSONObject());

        JSONObject getVol = new JSONObject();
        getVol.put("name", "self.audio_speaker.get_volume");
        getVol.put("description", "Get the current speaker volume (0-100) of this device.");
        getVol.put("inputSchema", emptySchema);
        tools.put(getVol);

        JSONObject status = new JSONObject();
        status.put("name", "self.get_device_status");
        status.put("description",
                "Get this device's status: volume percent, mic/camera software switch, playing music.");
        status.put("inputSchema", emptySchema);
        tools.put(status);

        // 音乐库清单：本地文件 + 家里 NAS/Windows 共享（SMB）上的歌。
        // description 必须写得非常“强制”，否则模型会凭训练数据直接回「我这边看不到」。
        JSONObject musicList = new JSONObject();
        musicList.put("name", "self.music.list");
        musicList.put("description",
                "列出这台音箱实际能播放的音乐：本地音频文件，以及家里 NAS / Windows 共享（SMB）里的歌。\n"
                + "【强制】你无法凭空知道这个清单，不调用本工具就永远看不到。"
                + "只要用户问「有哪些歌 / 有什么音乐 / 曲库里有什么 / 本地文件夹里有什么 / "
                + "SMB 共享里有什么 / 能放什么」，都必须先调用本工具，"
                + "绝对不要回答「我这边看不到」「曲库里没有」。\n"
                + "【重要】清单里的每一首歌都由 self.music.play 真正播放。"
                + "你没有它们的播放能力，禁止说「没被索引到」「搜索里点不出来」「我这边放不了」"
                + "「共享库没被收录」这类话——想播就用 self.music.play。\n"
                + "Return: 纯文本清单（含数量与歌名），请原样念给用户，不要总结、不要省略歌名。");
        musicList.put("inputSchema", emptySchema);
        tools.put(musicList);

        // 播放本地/共享里的歌：清单工具只能让模型「看见」，这个才能真的「放出来」。
        // 不注册它，模型就会在「看得见却放不了」之间自相矛盾，甚至从云端曲库乱放一首。
        JSONObject playSchema = new JSONObject();
        playSchema.put("type", "object");
        JSONObject playProps = new JSONObject();
        JSONObject qp = new JSONObject();
        qp.put("type", "string");
        qp.put("description", "要播放的歌名，尽量用 self.music.list 里列出的原始歌名；可以是简称或片段");
        playProps.put("query", qp);
        playSchema.put("properties", playProps);
        JSONArray qreq = new JSONArray();
        qreq.put("query");
        playSchema.put("required", qreq);

        JSONObject playTool = new JSONObject();
        playTool.put("name", "self.music.play");
        playTool.put("description",
                "播放这台音箱上的音乐：本地音频文件，或家里 Windows 共享（SMB）/ NAS 里的歌。\n"
                + "【强制】只要用户想听某首歌（哪怕只是提到歌名、简称、繁体写法、带歌手名），"
                + "都必须调用本工具，不要自己从云端曲库找替代品，也不要说「放不了」。\n"
                + "歌名不确定时，先用 self.music.list 看清单，再挑最接近的一个传给本工具。\n"
                + "Args:\n  `query`: 歌名（可用简称/片段，设备侧会模糊匹配）。\n"
                + "Return:\n  文本，说明是否已开始播放以及实际播放的歌名。"
                + "成功时请用一句话简短确认（例如「好，正在播放」），不要重复歌名的一大串解释。");
        playTool.put("inputSchema", playSchema);
        tools.put(playTool);

        // 媒体清单（音频 + 视频）：四个来源一起列，带连续序号。
        // 为什么单独一个工具而不是给 music.list 加参数：模型对「video 参数」的把握很差，
        // 但看到工具名 self.media.list 就知道用户问视频也能用。
        JSONObject mediaSchema = new JSONObject();
        mediaSchema.put("type", "object");
        JSONObject mediaProps = new JSONObject();
        JSONObject kp = new JSONObject();
        kp.put("type", "string");
        kp.put("enum", new JSONArray().put("audio").put("video").put("all"));
        kp.put("description", "audio=只要音乐/歌曲；video=只要视频/电影；all=音频和视频都要");
        mediaProps.put("kind", kp);
        mediaSchema.put("properties", mediaProps);

        JSONObject mediaList = new JSONObject();
        mediaList.put("name", "self.media.list");
        mediaList.put("description",
                "列出这台音箱能播放的**全部媒体**，覆盖五个位置：本机（Music / Movies 目录）、"
                + "家里共享文件夹（SMB）、网络盘（OpenList 网关下的夸克等网盘）、云端唱过并缓存的曲目、下载缓存。"
                + "条目带连续序号。\n"
                + "【强制】用户问「有哪些歌 / 有什么视频 / 都有什么片子 / 能放什么」时"
                + "必须先调用本工具，禁止回答「我看不到本地文件」「共享库没被索引」。\n"
                + "【重要】清单里的每一项都能用 self.media.play（带 index）真正播出来，"
                + "用户说「播第几个」时把序号原样传给 index。\n"
                + "Return: 纯文本清单（含来源、数量、序号和名字），请原样念给用户。");
        mediaList.put("inputSchema", mediaSchema);
        tools.put(mediaList);

        // 播放（含序号）：清单念完之后用户常说「就放第 3 个」，那一步也需要工具接住。
        JSONObject mediaPlaySchema = new JSONObject();
        mediaPlaySchema.put("type", "object");
        JSONObject mpProps = new JSONObject();
        JSONObject mq = new JSONObject();
        mq.put("type", "string");
        mq.put("description", "要播放的名字（歌名或视频名，可用简称/片段）");
        mpProps.put("query", mq);
        JSONObject mi = new JSONObject();
        mi.put("type", "integer");
        mi.put("description", "清单里的序号（从 1 开始）；用户说「第 3 个」时传 3，此时 query 可省略");
        mpProps.put("index", mi);
        mediaPlaySchema.put("properties", mpProps);

        JSONObject mediaPlay = new JSONObject();
        mediaPlay.put("name", "self.media.play");
        mediaPlay.put("description",
                "播放这台音箱上的音频或视频（本机 / 共享文件夹 / 云端缓存）。\n"
                + "视频会自动开画面，音频只出声。\n"
                + "【强制】用户说「播放第 N 个 / 第 N 首」时，把 N 传给 index，"
                + "不要自己从云端曲库找替代品。\n"
                + "Args: `query` 名字，`index` 清单序号（可选）。");
        mediaPlay.put("inputSchema", mediaPlaySchema);
        tools.put(mediaPlay);

        // 把注册的工具名打出来，排查「云端没调用某工具」时一眼能看出是不是漏注册
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < tools.length(); i++) {
            if (i > 0) names.append(",");
            names.append(tools.optJSONObject(i).optString("name", "?"));
        }
        Log.i(TAG, "tools/list -> [" + names + "]");
        return tools;
    }

    /**
     * 清单查询：SMB 首次扫描可能要一两秒，放 worker 线程，别堵 WS 回调。
     *
     * @param kind "audio" / "video" / "all"
     */
    private void handleMusicList(final Object id, final String kind) {
        final MusicProvider mp = musicProvider;
        if (mp == null) {
            respondText(id, "这台设备的音乐库功能当前不可用。", true);
            return;
        }
        // 先告诉调用方「云端确实来取了」，让它取消原本要发的 abort（本地就不抢话了）
        try {
            mp.onLibraryToolCalled(kind);
        } catch (Throwable t) {
            Log.e(TAG, "onLibraryToolCalled failed", t);
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = mp.libraryOverview(kind);
                } catch (Throwable t) {
                    Log.e(TAG, "libraryOverview failed", t);
                    text = null;
                }
                if (text == null || text.trim().isEmpty()) {
                    text = "这台音箱上暂时没有可播放的内容。";
                }
                Log.i(TAG, "list(" + kind + ") -> " + text.length() + " chars");
                respondText(id, text, false);
                if (callback != null) callback.onStatus("已提供媒体清单");
            }
        });
    }

    /**
     * 播放请求：模型给出歌名 → 设备侧匹配本地/SMB 曲目 → 真的播出来。
     * 同样放 worker 线程（SMB 首扫可能要一两秒）。
     */
    private void handleMusicPlay(final Object id, final String query) {
        final MusicProvider mp = musicProvider;
        if (mp == null) {
            respondText(id, "这台设备的音乐库功能当前不可用。", true);
            return;
        }
        try {
            mp.onLibraryToolCalled(null);   // 云端要来干活了，本地别抢着 abort（不是清单请求）
        } catch (Throwable t) {
            Log.e(TAG, "onLibraryToolCalled failed", t);
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                String text;
                try {
                    text = mp.playByQuery(query);
                } catch (Throwable t) {
                    Log.e(TAG, "playByQuery failed", t);
                    text = "播放失败：" + t.getMessage();
                }
                if (text == null || text.trim().isEmpty()) text = "没有找到匹配的歌。";
                Log.i(TAG, "self.music.play(" + query + ") -> " + text);
                respondText(id, text, false);
                if (callback != null) callback.onStatus("播放请求：" + query);
            }
        });
    }

    private AudioManager audio() {
        return (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
    }

    private VolumeCtl volumes() {
        if (volCtl == null) volCtl = new VolumeCtl(ctx);
        return volCtl;
    }

    /** 0-100 百分比 → 系统音量档位（走 VolumeCtl，和机身键/语音同一条路）。 */
    private void handleSetVolume(Object id, int percent) {
        try {
            if (percent < 0) {
                respondText(id, "{\"success\": false, \"message\": \"音量参数无效\"}", true);
                return;
            }
            VolumeCtl vc = volumes();
            if (!vc.ok()) {
                respondText(id, "{\"success\": false, \"message\": \"音量不可用\"}", true);
                return;
            }
            int p = Math.max(0, Math.min(100, percent));
            if (!vc.setPct(p)) {
                respondText(id, "{\"success\": false, \"message\": \"设置音量失败\"}", true);
                return;
            }
            // 回读实际值：这台机器只有 16 档，「40%」实际会落在 33%，报实际值才不会和机身键对不上
            int actual = vc.pct();
            Log.i(TAG, "set volume ask " + p + "% -> actual " + vc.label());
            if (callback != null) callback.onStatus("音量 " + vc.label());
            respondText(id, "{\"success\": true, \"volume\": " + actual
                    + ", \"step\": " + vc.idx() + ", \"maxStep\": " + vc.max() + "}", false);
        } catch (Throwable t) {
            Log.e(TAG, "set volume failed", t);
            respondText(id, "{\"success\": false, \"message\": \"" + t.getMessage() + "\"}", true);
        }
    }

    private int volumePercent() {
        try {
            VolumeCtl vc = volumes();
            return vc.ok() ? vc.pct() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private String deviceStatusJson() {
        try {
            JSONObject o = new JSONObject();
            VolumeCtl vc = volumes();
            o.put("volume", vc.ok() ? vc.pct() : -1);
            if (vc.ok()) {
                o.put("volumeStep", vc.idx());
                o.put("volumeMaxStep", vc.max());
            }
            o.put("screen", "1280x800");
            o.put("camera", cameraAllowed() ? "on" : "off");
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 拍照 + 上传，整个过程放到单线程池，避免阻塞 WS 回调线程。 */
    private void handleTakePhoto(final Object id, final String question) {
        if (!cameraAllowed()) {
            // 用户在界面上关了摄像头：明确回绝，并让模型用一句话向用户解释
            respondText(id, "{\"success\": false, \"message\": \"摄像头已被用户在设备上关闭，"
                    + "请先请用户打开摄像头\"}", true);
            if (callback != null) callback.onStatus("摄像头已关闭，拒绝拍照");
            return;
        }
        if (callback != null) callback.onStatus("拍照中…");
        worker.execute(new Runnable() {
            @Override
            public void run() {
                byte[] jpeg;
                try {
                    jpeg = CameraCapture.captureJpeg(ctx);
                } catch (Throwable t) {
                    Log.e(TAG, "capture failed", t);
                    respondText(id, "{\"success\": false, \"message\": \"拍照失败: " + t.getMessage() + "\"}", true);
                    if (callback != null) callback.onStatus("拍照失败");
                    return;
                }

                // 隐私优先：默认不落盘，照片只在内存里过一遍就上传。
                // 需要排查摄像头朝向/清晰度时，在「服务器设置」里勾选「保留照片到本地」。
                if (keepLocalPhoto()) {
                    try {
                        File dir = ctx.getExternalFilesDir(null);
                        File f = new File(dir, "last_photo.jpg");
                        if (f.exists()) f.delete();   // 先删旧的：目录里最多 1 张，不累积历史
                        FileOutputStream fo = new FileOutputStream(f);
                        fo.write(jpeg);
                        fo.close();
                        Log.i(TAG, "saved " + f.getAbsolutePath());
                    } catch (Throwable ignore) {}
                }

                if (!visionUrl.isEmpty()) {
                    String answer = uploadAndExplain(jpeg, question);
                    if (answer != null) {
                        respondText(id, answer, false);
                        if (callback != null) callback.onStatus("已看到画面");
                        return;
                    }
                    Log.w(TAG, "vision upload failed, fallback to image content");
                }

                // 兜底：把图片本身作为 MCP image content 返回
                respondImage(id, jpeg);
                if (callback != null) callback.onStatus("已拍照（未启用视觉服务）");
            }
        });
    }

    /** POST JPEG 到 initialize 下发的视觉端点，返回分析文本；失败返回 null。 */
    private String uploadAndExplain(byte[] jpeg, String question) {
        try {
            HttpUrl u = HttpUrl.parse(visionUrl);
            if (u == null) return null;
            // 云端下发的端点是明文 http://，照片 + Bearer 令牌会被同网段任意设备嗅探。
            // 实测 https://api.xiaozhi.me/vision/explain 同样 200，故强制升级为 TLS。
            if ("http".equals(u.scheme())) {
                u = u.newBuilder().scheme("https").build();
                Log.i(TAG, "upgraded vision url to https");
            }
            // 官方 ESP32 固件用的是 multipart/form-data：
            //   question 字段 + file 字段(filename=camera.jpg, image/jpeg)
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("question", question == null ? "" : question)
                    .addFormDataPart("file", "camera.jpg",
                            RequestBody.create(MediaType.parse("image/jpeg"), jpeg))
                    .build();
            Request req = new Request.Builder()
                    .url(u)
                    .header("Device-Id", deviceId)
                    .header("Client-Id", clientId)
                    .header("Authorization", "Bearer " + visionToken)
                    .post(body)
                    .build();
            Log.i(TAG, "uploading " + jpeg.length + " bytes to " + u + " q=" + question);
            Response resp = http.newCall(req).execute();
            String rb = resp.body() != null ? resp.body().string() : "";
            Log.i(TAG, "vision resp code=" + resp.code() + " body=" + rb);
            if (!resp.isSuccessful()) return null;
            // 服务端一般回 {"success":true,"text":"..."}，字段名各版本略有差异，逐个试
            try {
                JSONObject o = new JSONObject(rb);
                for (String k : new String[]{"text", "answer", "content", "result", "message", "description"}) {
                    String v = o.optString(k, "");
                    if (!v.isEmpty()) return v;
                }
                return rb;
            } catch (Exception notJson) {
                return rb.isEmpty() ? null : rb;
            }
        } catch (Throwable t) {
            Log.e(TAG, "vision upload error", t);
            return null;
        }
    }

    // ---- JSON-RPC 回包 ----

    private void respond(Object id, JSONObject result) throws Exception {
        JSONObject o = new JSONObject();
        o.put("jsonrpc", "2.0");
        o.put("id", id == null ? JSONObject.NULL : id);
        o.put("result", result);
        client.sendMcp(o);
    }

    private void respondError(Object id, int code, String message) {
        try {
            JSONObject err = new JSONObject();
            err.put("code", code);
            err.put("message", message);
            JSONObject o = new JSONObject();
            o.put("jsonrpc", "2.0");
            o.put("id", id == null ? JSONObject.NULL : id);
            o.put("error", err);
            client.sendMcp(o);
        } catch (Exception ignore) {}
    }

    private void respondText(Object id, String text, boolean isError) {
        try {
            JSONArray content = new JSONArray();
            JSONObject t = new JSONObject();
            t.put("type", "text");
            t.put("text", text);
            content.put(t);
            JSONObject result = new JSONObject();
            result.put("content", content);
            result.put("isError", isError);
            respond(id, result);
        } catch (Exception e) {
            Log.e(TAG, "respondText failed", e);
        }
    }

    private void respondImage(Object id, byte[] jpeg) {
        try {
            JSONArray content = new JSONArray();
            JSONObject t = new JSONObject();
            t.put("type", "text");
            t.put("text", "已拍下画面（服务端未提供视觉分析端点，图片以附件形式返回）");
            content.put(t);
            JSONObject img = new JSONObject();
            img.put("type", "image");
            img.put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            img.put("mimeType", "image/jpeg");
            content.put(img);
            JSONObject result = new JSONObject();
            result.put("content", content);
            result.put("isError", false);
            respond(id, result);
        } catch (Exception e) {
            Log.e(TAG, "respondImage failed", e);
        }
    }
}
