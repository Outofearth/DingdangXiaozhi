package com.altc.xiaozhi;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 向伙伴拉取聊天历史（GET http://ip:port/sync），解析为 Msg 列表。 */
public class SyncClient {
    private static final String TAG = "SyncClient";

    public static List<ChatStore.Msg> pull(String ip, int port) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL("http://" + ip + ":" + port + "/sync");
            conn = (HttpURLConnection) u.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            if (conn.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return ChatStore.fromJson(sb.toString());
        } catch (Exception e) {
            Log.w(TAG, "拉取 " + ip + ":" + port + " 失败：" + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
