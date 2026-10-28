package com.altc.xiaozhi;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket client implementing the XiaoZhi (xiaozhi-esp32) protocol v1.
 *
 * Protocol summary (verified against the official server reference client):
 *  - Connect to wss URL with handshake headers:
 *      device-id / client-id / protocol-version: 1 / Authorization: Bearer <token>
 *  - Send JSON "hello" (audio_params is an OBJECT for protocol v1).
 *  - Binary frames are RAW Opus (no 16-byte header) in both directions.
 *  - Control messages (stt / tts / llm / listen) are JSON text frames.
 *
 * 保活说明：官方云前置网关（nginx proxy_read_timeout）约 60 秒无「应用层数据」
 * 就会掐断连接，WebSocket 协议级 ping 帧不计入，因此这里每 20 秒发一条应用层
 * 心跳 JSON；同时任何非主动断开都会自动重连（指数退避 2s→30s）。
 */
public class XiaoZhiClient {
    public static final String DEFAULT_URL = "wss://api.tenclass.net/xiaozhi/v1/";
    private static final String TAG = "XiaoZhiClient";

    /** 空闲多少毫秒后主动换一条新连接（服务端约 60s 就会掐断空闲连接）。 */
    private static final long IDLE_ROTATE_MS = 45000;
    /** 保活检查间隔。 */
    private static final int KEEPALIVE_TICK_MS = 10000;
    private static final int RECONNECT_MIN_MS = 1000;
    private static final int RECONNECT_MAX_MS = 30000;

    private final OkHttpClient http;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile WebSocket ws;
    private final String deviceId;
    private final String clientId;
    private String token = "";
    private String url = DEFAULT_URL;
    private volatile boolean ready = false;
    /** true 表示是用户/程序主动断开，不再自动重连。 */
    private volatile boolean manualClose = true;
    private int reconnectAttempt = 0;
    /** 最近一次收发数据的时间戳，用于空闲保活判断。 */
    private volatile long lastActivityAt = 0;
    /** 最近一次收到的服务端消息类型（诊断用）。 */
    private volatile String lastServerType = "";
    /** 服务端是否正在下发语音（tts start..stop），用于决定要不要发 abort。 */
    private volatile boolean speaking = false;
    /** 空闲轮换期间静默：不往界面推状态，做到用户无感。 */
    private volatile boolean quietRotate = false;
    private Listener listener;

    public interface Listener {
        void onStatus(String status);
        /** 用户这句话的最终识别结果（服务端 ASR 回传）。 */
        void onAsr(String text);
        /** AI 回答中逐句下发的文本。 */
        void onAiSentence(String text);
        /** 一轮回答开始（tts start）。 */
        void onSpeakStart();
        /** 一轮回答结束（tts stop）：连续对话模式据此自动重开麦克风。 */
        void onSpeakEnd();
        void onBinary(byte[] data);
        void onError(String msg);
        /** 下行音频参数（来自 server hello），换音色时采样率可能变化。 */
        void onServerAudio(int sampleRate, int channels);
        /**
         * 服务端 notify 消息：官方音乐/长音频走这里 —— 给一个 audio_url 让设备自己播，
         * subtitles 是同步字幕（歌词）。
         */
        void onNotify(String audioUrl, String subtitle);
        /**
         * 服务端把「工具调用标记」当成一句话下发（<code>% play_music...</code> 等）。
         * 官方固件不解析，客户端拿它来识别「这一轮是在放歌」。
         */
        void onMarker(String raw);
    }

    /**
     * 保活策略：官方云（及其前置网关）约 60 秒收不到「业务数据」就断开，
     * 实测它对 {"type":"ping"} / {"type":"heartbeat"} 这类应用层心跳不买账。
     * 所以不跟它硬抗，而是在空闲 45 秒时主动换一条新连接 —— 用户下次开口时
     * 连接永远是新鲜的，不会正好撞上 60 秒超时。
     */
    private final Runnable keepaliveTask = new Runnable() {
        @Override
        public void run() {
            WebSocket w = ws;
            if (w == null) return;
            long idle = System.currentTimeMillis() - lastActivityAt;
            // 用户正在说话时绝不轮换：换连接会让这一轮的音频直接丢进旧 socket。
            if (idle > IDLE_ROTATE_MS && !busy) {
                Log.i(TAG, "idle " + idle + "ms, rotating connection before server timeout");
                if (listener != null) listener.onStatus("空闲保活：切换新连接");
                rotateNow();
                return;   // rotateNow 会重新安排检查
            }
            handler.postDelayed(this, KEEPALIVE_TICK_MS);
        }
    };

    /** 说话期间置 true，保活轮换会跳过，避免正说话时被换掉连接。 */
    private volatile boolean busy = false;

    public void setBusy(boolean b) { this.busy = b; }

    /** MCP 消息处理器（设备端 MCP Server），由 MainActivity 注入。 */
    private McpHandler mcp;

    public void setMcpHandler(McpHandler h) { this.mcp = h; }

    /**
     * 向服务端发送一条 MCP 消息（JSON-RPC 2.0 包在 {"type":"mcp","payload":...} 里）。
     */
    public void sendMcp(JSONObject payload) {
        try {
            JSONObject o = new JSONObject();
            o.put("type", "mcp");
            o.put("payload", payload);
            sendJson(o.toString());
        } catch (JSONException e) {
            Log.e(TAG, "sendMcp failed", e);
        }
    }

    public XiaoZhiClient(String deviceId, String clientId) {
        this.deviceId = deviceId;
        this.clientId = clientId;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)   // WS 长连接不设读超时
                .build();
    }

    public void setListener(Listener l) { this.listener = l; }
    public void setServer(String url, String token) {
        if (url != null && !url.isEmpty()) this.url = url;
        if (token != null) this.token = token;
    }
    public boolean isReady() { return ready; }
    public String describe() {
        // 令牌只报长度：这行会进日志和 status.txt，明文留着等于把凭据写进设备存储。
        return "device-id=" + deviceId + " client-id=" + clientId
                + " url=" + url + " tokenLen=" + (token == null ? 0 : token.length());
    }

    public void connect() {
        manualClose = false;
        if (ws != null) return;
        Log.i(TAG, "connect() -> " + url + " device=" + deviceId
                + " attempt=" + reconnectAttempt);
        Request req = new Request.Builder().url(url)
                .addHeader("device-id", deviceId)
                .addHeader("client-id", clientId)
                .addHeader("protocol-version", "1")
                .addHeader("Authorization", "Bearer " + token)
                .build();
        ws = http.newWebSocket(req, new WsListener());
        if (listener != null && !quietRotate) {
            listener.onStatus(reconnectAttempt > 0
                    ? ("重连中…(第 " + reconnectAttempt + " 次)")
                    : "连接中…");
        }
    }

    public void disconnect() {
        manualClose = true;
        ready = false;
        handler.removeCallbacks(keepaliveTask);
        WebSocket old = ws;
        ws = null;
        if (old != null) {
            try { old.close(1000, "bye"); } catch (Exception ignore) {}
        }
    }

    /** 立即重连（用于用户点击/按键时代替等待退避重连）。 */
    public void reconnectNow() {
        manualClose = false;
        reconnectAttempt = 0;
        handler.removeCallbacks(keepaliveTask);
        WebSocket old = ws;
        ws = null;            // 先置空：旧连接的 onClosed 会被 "w != ws" 判断忽略
        if (old != null) {
            try { old.close(1000, "retry"); } catch (Exception ignore) {}
        }
        connect();
    }

    /** 空闲轮换：换一条新连接，但不在界面上显示任何状态变化（用户无感）。 */
    public void rotateNow() {
        quietRotate = true;
        reconnectNow();
    }

    /** 非主动断开时按指数退避重连，保证长时间待机也能随时说话。 */
    private void scheduleReconnect(String why) {
        if (manualClose) return;
        reconnectAttempt++;
        int delay = RECONNECT_MIN_MS << Math.min(reconnectAttempt - 1, 4);
        if (delay > RECONNECT_MAX_MS) delay = RECONNECT_MAX_MS;
        Log.i(TAG, "scheduleReconnect after " + why + " in " + delay + "ms (#" + reconnectAttempt + ")");
        if (listener != null) listener.onStatus("连接断开，" + (delay / 1000) + " 秒后自动重连…");
        handler.postDelayed(() -> {
            if (!manualClose && ws == null) connect();
        }, delay);
    }

    public void sendHello() {
        WebSocket w = ws;
        if (w == null) return;
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", 1);
            hello.put("transport", "websocket");
            JSONObject ap = new JSONObject();
            ap.put("format", "opus");
            ap.put("sample_rate", 16000);
            ap.put("channels", 1);
            ap.put("frame_duration", 60);
            hello.put("audio_params", ap);
            // 声明支持 MCP：官方后端看到 features.mcp=true 才会下发 initialize，
            // 并可能在 initialize.params.capabilities.vision 里给出视觉上传端点。
            JSONObject features = new JSONObject();
            features.put("mcp", true);
            hello.put("features", features);
            w.send(hello.toString());
        } catch (JSONException e) {
            if (listener != null) listener.onError(e.getMessage());
        }
    }

    /** 打断：服务端收到后立即中止当前 TTS（音乐朗读等）。 */
    public void sendAbort() {
        sendJson("{\"type\":\"abort\"}");
        Log.i(TAG, "sent abort");
    }

    /** 把 % 开头的工具标记翻成人话，给状态栏用。 */
    private static String markerHint(String st) {
        String s = st.replace("%", "").replace("...", "").trim();
        if (s.contains("search_music")) return "正在搜索音乐…";
        if (s.contains("play_music")) return "正在播放音乐…";
        if (s.contains("stop_music") || s.contains("pause_music")) return "正在停止播放…";
        if (s.contains("set_volume")) return "正在调节音量…";
        if (s.contains("get_volume")) return "正在读取音量…";
        if (s.contains("take_photo")) return "正在拍照…";
        if (s.contains("device_status")) return "正在读取设备状态…";
        return "正在执行 " + s + "…";
    }

    public void sendListenStart() {
        sendListenStart("manual");
    }

    /**
     * mode:
     *  - manual   按住说话（半双工，服务端收到就停当前 TTS）
     *  - auto     自动 VAD
     *  - realtime 全双工：不停 TTS，边播边说（ESP32 的 AEC 模式走这条）
     */
    public void sendListenStart(String mode) {
        sendJson("{\"type\":\"listen\",\"state\":\"start\",\"mode\":\"" + mode + "\"}");
    }

    /** 调试用：原样发一条 JSON 给服务端。 */
    public void sendRaw(String json) {
        sendJson(json);
    }

    /**
     * 直接发一段文字当作「用户说的话」交给服务端（协议里的 detect 状态）。
     * 无麦克风环境下用来验证 ASR→LLM→TTS 全链路与连续对话自动接续。
     */
    public void sendTextQuery(final String text) {
        final Runnable send = new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject o = new JSONObject();
                    o.put("type", "listen");
                    o.put("state", "detect");
                    o.put("text", text);
                    sendJson(o.toString());
                    Log.i(TAG, "send text query: " + text);
                } catch (JSONException e) {
                    Log.e(TAG, "sendTextQuery failed", e);
                }
            }
        };
        // abort 必须在指令之前发，发在后面会把刚生成的回复一起掐掉（踩过）。
        // 而且只在真的正在播时才需要 abort。
        if (speaking) {
            sendAbort();
            handler.postDelayed(send, 350);
        } else {
            send.run();
        }
    }

    public boolean isSpeaking() {
        return speaking;
    }

    public void sendListenStop() {
        sendListenStop("manual");
    }

    public void sendListenStop(String mode) {
        sendJson("{\"type\":\"listen\",\"state\":\"stop\",\"mode\":\"" + mode + "\"}");
    }

    public void sendBinary(byte[] data, int len) {
        WebSocket w = ws;
        if (w != null) {
            lastActivityAt = System.currentTimeMillis();
            w.send(ByteString.of(data, 0, len));
        }
    }

    private void sendJson(String s) {
        WebSocket w = ws;
        if (w != null) {
            lastActivityAt = System.currentTimeMillis();
            w.send(s);
        }
    }

    private class WsListener extends WebSocketListener {
        @Override
        public void onOpen(WebSocket w, Response r) {
            if (w != ws) return;   // 已被新连接替换，忽略旧回调
            Log.i(TAG, "onOpen code=" + (r != null ? r.code() : "null"));
            if (listener != null && !quietRotate) listener.onStatus("已连接，发送 hello");
            lastActivityAt = System.currentTimeMillis();
            sendHello();
            handler.removeCallbacks(keepaliveTask);
            handler.postDelayed(keepaliveTask, KEEPALIVE_TICK_MS);
        }

        @Override
        public void onMessage(WebSocket w, String text) {
            if (w != ws) return;
            lastActivityAt = System.currentTimeMillis();
            handleText(text);
        }

        @Override
        public void onMessage(WebSocket w, ByteString bytes) {
            if (w != ws) return;
            lastActivityAt = System.currentTimeMillis();
            if (listener != null) listener.onBinary(bytes.toByteArray());
        }

        @Override
        public void onClosing(WebSocket w, int code, String reason) {
            try { w.close(1000, null); } catch (Exception ignore) {}
        }

        @Override
        public void onClosed(WebSocket w, int code, String reason) {
            if (w != ws) return;   // 旧连接回调，别把新连接状态清掉
            ws = null;
            ready = false;
            handler.removeCallbacks(keepaliveTask);
            Log.i(TAG, "onClosed code=" + code + " reason=" + reason);
            if (listener != null) {
                listener.onStatus("已断开(code=" + code
                        + (reason != null && !reason.isEmpty() ? "," + reason : "") + ")");
            }
            scheduleReconnect("closed");
        }

        @Override
        public void onFailure(WebSocket w, Throwable t, Response r) {
            if (w != ws) return;
            ws = null;
            ready = false;
            quietRotate = false;
            handler.removeCallbacks(keepaliveTask);
            String msg = (t != null ? t.getMessage() : "null")
                    + " resp=" + (r != null ? r.code() : "null");
            Log.e(TAG, "onFailure: " + msg, t);
            if (listener != null) {
                listener.onError(msg);
                listener.onStatus("连接失败: " + msg);
            }
            scheduleReconnect("failure");
        }
    }

    private void handleText(String text) {
        if (listener == null) return;
        try {
            JSONObject o = new JSONObject(text);
            String type = o.optString("type");
            Log.i(TAG, "recv type=" + type);
            // 原样记录服务端消息，排查「指令不生效」这类问题全靠它
            Log.i(TAG, "RAW " + (text.length() > 900 ? text.substring(0, 900) + "…" : text));
            switch (type) {
                case "hello":
                    // 未激活的设备：服务端下发 activation.code（6 位验证码），
                    // 需要到 xiaozhi.me → 智能体 → 添加设备 输入该验证码完成绑定。
                    JSONObject act = o.optJSONObject("activation");
                    if (act != null) {
                        String code = act.optString("code", "?");
                        String message = act.optString("message", "");
                        Log.i(TAG, "device not activated, code=" + code);
                        quietRotate = false;
                        listener.onStatus("未激活：验证码 " + code + "（到 xiaozhi.me 添加设备）");
                        return;   // 未激活不算 ready
                    }
                    try {
                        JSONObject sap = o.optJSONObject("audio_params");
                        if (sap != null) {
                            int sr = sap.optInt("sample_rate", 0);
                            int ch = sap.optInt("channels", 1);
                            Log.i(TAG, "server audio: sample_rate=" + sr + " channels=" + ch);
                            if (sr > 0) listener.onServerAudio(sr, ch);
                        }
                    } catch (Exception ignore) {}
                    ready = true;
                    reconnectAttempt = 0;   // 真正握手成功才重置退避
                    if (listener != null && !quietRotate) {
                        listener.onStatus("握手成功 · 按住说话（可问「看看这是什么」）");
                    }
                    quietRotate = false;
                    break;
                case "stt":
                    String asr = o.optString("text", "");
                    if (!asr.isEmpty()) listener.onAsr(asr);
                    break;
                case "tts":
                    String state = o.optString("state");
                    if ("sentence_start".equals(state)) {
                        String st = o.optString("text", "");
                        if (st.startsWith("%")) {
                            // 服务端把「工具调用标记」当一句话下发（如 % search_music...），
                            // 官方固件不解析，只是原样朗读/显示。这里不放进对话气泡，
                            // 只当作状态提示，避免用户看到一堆 % 开头的乱码。
                            Log.i(TAG, "skip marker sentence: " + st);
                            listener.onStatus(markerHint(st));
                            listener.onMarker(st);
                        } else {
                            listener.onAiSentence(st);
                        }
                    } else if ("start".equals(state)) {
                        speaking = true;
                        listener.onSpeakStart();
                        listener.onStatus("小智回答中…");
                    } else if ("stop".equals(state)) {
                        speaking = false;
                        listener.onSpeakEnd();
                        listener.onStatus("就绪");
                    }
                    break;
                case "pong":
                    // 心跳应答，连接健康
                    break;
                case "llm":
                    // emotion / emoji — currently ignored
                    break;
                case "mcp":
                    JSONObject mcpPayload = o.optJSONObject("payload");
                    Log.i(TAG, "MCP << " + (mcpPayload != null ? mcpPayload.toString() : text));
                    if (mcp != null && mcpPayload != null) {
                        mcp.onServerMessage(mcpPayload);
                    }
                    break;
                case "listen":
                    // server-driven listen state changes
                    break;
                case "notify": {
                    // 官方长音频（音乐、评书等）：服务端给一个 audio_url，设备自己下载播放
                    String audioUrl = o.optString("audio_url", "");
                    StringBuilder sb = new StringBuilder();
                    org.json.JSONArray subs = o.optJSONArray("subtitles");
                    if (subs != null) {
                        for (int i = 0; i < subs.length(); i++) {
                            Object it = subs.opt(i);
                            if (it instanceof JSONObject) {
                                String t = ((JSONObject) it).optString("text", "");
                                if (!t.isEmpty()) sb.append(t).append(' ');
                            } else if (it != null && it != JSONObject.NULL) {
                                sb.append(it.toString()).append(' ');
                            }
                        }
                    }
                    Log.i(TAG, "notify audio_url=" + audioUrl
                            + " subtitleLen=" + sb.length());
                    if (!audioUrl.isEmpty()) {
                        listener.onNotify(audioUrl, sb.toString().trim());
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (JSONException e) {
            listener.onError(e.getMessage());
        }
    }
}
