package com.altc.xiaozhi;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 本地同步 HTTP 服务。监听 {@link #SYNC_PORT}，收到 GET /sync 时把本机聊天记录
 * （含消息 id）以 JSON 返回，供同局域网、同账号、同配对码的伙伴拉取。
 * 纯 Java ServerSocket 实现，无第三方依赖。
 */
public class SyncHttpServer {
    public static final int SYNC_PORT = 18766;
    private static final String TAG = "SyncHttpServer";

    private final Context ctx;
    private ServerSocket ss;
    private Thread thread;
    private volatile boolean running;

    public SyncHttpServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::loop, "sync-http");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        try { if (ss != null) ss.close(); } catch (IOException ignore) {}
        if (thread != null) thread.interrupt();
    }

    private void loop() {
        try {
            ss = new ServerSocket(SYNC_PORT);
        } catch (IOException e) {
            Log.e(TAG, "无法在 " + SYNC_PORT + " 启动同步服务：" + e.getMessage());
            running = false;
            return;
        }
        while (running) {
            try {
                Socket s = ss.accept();
                handle(s);
            } catch (IOException e) {
                if (running) Log.w(TAG, "accept 出错：" + e.getMessage());
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(5000);
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = br.readLine();
            if (line == null || !line.startsWith("GET /sync")) {
                s.close();
                return;
            }
            while ((line = br.readLine()) != null && !line.isEmpty()) { /* 跳过请求头 */ }

            String json = ChatStore.toJson(new ChatStore(ctx).load());
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.0 200 OK\r\n");
            head.append("Content-Type: application/json\r\n");
            head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream os = s.getOutputStream();
            os.write(head.toString().getBytes(StandardCharsets.UTF_8));
            os.write(body);
            os.flush();
        } catch (IOException e) {
            Log.w(TAG, "handle 出错：" + e.getMessage());
        } finally {
            try { s.close(); } catch (IOException ignore) {}
        }
    }
}
