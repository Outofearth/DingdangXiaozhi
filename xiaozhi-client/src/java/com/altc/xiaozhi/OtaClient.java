package com.altc.xiaozhi;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 小智官方 OTA 注册 / 激活客户端（协议对齐 xiaozhi-esp32 main/ota.cc）。
 *
 * 关键点（实测得出，缺一不可）：
 *  1. Device-Id 必须是「小写」MAC 格式；大写会被服务端拒绝。
 *  2. 请求体必须带完整的 application 描述，其中 elf_sha256 必须是 64 位 hex。
 *     只带 name/version 的简化体会被服务端当成非法固件，直接回 test-token 且不下发激活码。
 *  3. 同一 MAC 的激活码是持久化的 —— 只要没被绑定，反复请求返回同一个 code。
 *  4. **绝对不要**上报 Serial-Number 头、也不要调用 /ota/activate！
 *     一旦调用，云端会把设备登记成「已激活的 v2 设备」，之后在 xiaozhi.me 输入验证码时
 *     会去校验 eFuse 序列号 license，查不到就报「需要序列号，请检查设备是否已烧录」，
 *     绑定永久失败（这个设备身份就废了，只能换新 device-id）。
 *     我们走的是 v1 纯验证码流程：只拉码、只让用户在网页输码，绑定由网页端完成。
 *
 * 返回语义：
 *  - activation 存在  → 设备未绑定，界面上要显示 code，用户到 xiaozhi.me 输入。
 *  - 无 activation    → 已绑定，返回的 websocket.token 才能连到用户自己的智能体。
 *  - token=test-token → 官方公共测试通道（能聊天，但不是用户的智能体）。
 */
public class OtaClient {
    private static final String TAG = "OtaClient";

    public static final String OTA_URL = "https://api.tenclass.net/xiaozhi/ota/";
    /** 公共测试通道的令牌，出现即代表「尚未绑定任何账号」。 */
    public static final String PUBLIC_TOKEN = "test-token";

    /** 运行时组装，避免和"固件"混淆：这是我们客户端的版本号。 */
    private static final String APP_VERSION = "1.0.0";

    public static class Result {
        public String wsUrl;
        public String token;
        /** 非空表示未绑定，需要用户去 xiaozhi.me 输入这 6 位码。 */
        public String activationCode;
        public String activationMessage;
        public String challenge;
        public String error;

        public boolean needActivation() {
            return activationCode != null && !activationCode.isEmpty();
        }
        public boolean isPublicChannel() {
            return PUBLIC_TOKEN.equals(token);
        }
        @Override
        public String toString() {
            if (error != null) return "OTA失败: " + error;
            if (needActivation()) return "待激活 code=" + activationCode;
            return "已绑定 url=" + wsUrl + " tokenLen=" + (token == null ? 0 : token.length());
        }
    }

    public static Result fetch(String deviceId, String clientId) {
        Result r = new Result();
        HttpURLConnection conn = null;
        try {
            String body = buildBody(deviceId, clientId);
            URL u = new URL(OTA_URL);
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Device-Id", deviceId.toLowerCase());
            conn.setRequestProperty("Client-Id", clientId);
            conn.setRequestProperty("Activation-Version", "2");
            conn.setRequestProperty("User-Agent", "altc-xiaozhi/" + APP_VERSION);
            conn.setRequestProperty("Accept-Language", "zh-CN");

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(in);
            Log.i(TAG, "OTA http=" + code + " resp=" + resp);
            if (code != 200) {
                r.error = "HTTP " + code;
                return r;
            }

            JSONObject o = new JSONObject(resp);
            JSONObject ws = o.optJSONObject("websocket");
            if (ws != null) {
                r.wsUrl = ws.optString("url", null);
                r.token = ws.optString("token", null);
            }
            JSONObject act = o.optJSONObject("activation");
            if (act != null) {
                r.activationCode = act.optString("code", "");
                r.activationMessage = act.optString("message", "");
                r.challenge = act.optString("challenge", "");
            }
            if (r.wsUrl == null || r.wsUrl.isEmpty()) r.wsUrl = XiaoZhiClient.DEFAULT_URL;
            if (r.token == null) r.token = PUBLIC_TOKEN;
        } catch (Throwable t) {
            Log.e(TAG, "OTA exception: " + t, t);
            r.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            if (conn != null) conn.disconnect();
        }
        return r;
    }

    /**
     * 官方固件上报的「应用自描述」。elf_sha256 用固定的 64 位 hex 占位，
     * 服务端只做格式校验，不比对真实固件哈希。
     */
    private static String buildBody(String deviceId, String clientId) throws Exception {
        JSONObject app = new JSONObject();
        app.put("name", "bread-compact-wifi");
        app.put("version", APP_VERSION);
        app.put("compile_time", "2026-01-01T00:00:00Z");
        app.put("idf_version", "v5.3.1");
        app.put("elf_sha256", "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        JSONObject board = new JSONObject();
        board.put("type", "bread-compact-wifi");
        board.put("name", "bread-compact-wifi");
        app.put("board", board);

        JSONObject root = new JSONObject();
        root.put("application", app);
        root.put("mac_address", deviceId.toLowerCase());
        root.put("uuid", clientId);
        root.put("chip_model_name", "esp32s3");
        return root.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    /** 生成一个「本地管理」类型的随机 MAC（02: 前缀），小写。 */
    public static String randomDeviceId() {
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder("02");
        for (int i = 0; i < 5; i++) {
            sb.append(String.format(":%02x", rnd.nextInt(256)));
        }
        return sb.toString();
    }
}
