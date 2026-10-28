package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多设备局域网同步总控（参考微信多设备消息同步语义）。
 *
 *  - 同账号（device-id 一致）+ 同配对码 的多个设备在同一局域网时互相发现。
 *  - 发现后拉取对端聊天记录，按消息 id 去重、ts 排序合并到本机（{@link ChatStore#merge}）。
 *  - 模式：OFF 关闭 / MANUAL 仅手动触发 / AUTO 周期性自动同步 + 本机新增消息时主动同步一次。
 *
 * 说明：同步只走局域网 P2P，不涉及 xiaozhi 服务端连接。两端若各自连服务端对话，
 * 同一 device-id 双连会被服务端互踢，由用户自行避免（见需求确认）。
 */
public class SyncManager {
    public static final int OFF = 0, MANUAL = 1, AUTO = 2;
    private static final String TAG = "SyncManager";
    private static final long AUTO_INTERVAL = 20000;

    private static SyncManager inst;
    public static SyncManager get() { return inst; }
    public static SyncManager init(Context ctx) {
        if (inst == null) inst = new SyncManager(ctx.getApplicationContext());
        return inst;
    }

    public interface Callback {
        /** 一次同步结束。added=本机新合并的条数，peerCount=当前发现的伙伴数。 */
        void onSyncDone(int added, int peerCount);
        /** 发现的伙伴数量变化。 */
        void onPeersChanged(int peerCount);
    }

    private final Context ctx;
    private final SharedPreferences sp;
    private SyncHttpServer server;
    private PresenceService presence;
    private final Map<String, PresenceService.Peer> peers = new ConcurrentHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable autoTask;
    private final java.util.List<Callback> callbacks = new java.util.ArrayList<>();
    private volatile boolean syncing;

    private SyncManager(Context ctx) {
        this.ctx = ctx;
        this.sp = ctx.getSharedPreferences("xz", Context.MODE_PRIVATE);
    }

    public void addCallback(Callback c) { if (c != null && !callbacks.contains(c)) callbacks.add(c); }
    public void removeCallback(Callback c) { callbacks.remove(c); }

    public int getMode() {
        try { return Integer.parseInt(sp.getString("syncMode", "0")); }
        catch (Exception e) { return OFF; }
    }
    public void setMode(int m) {
        sp.edit().putString("syncMode", String.valueOf(m)).apply();
    }

    public void start() {
        if (server != null) return;   // 已在运行
        String deviceId = sp.getString("deviceId", "");
        String pairCode = sp.getString("pairCode", "");
        String hash = PairCode.hash(deviceId, pairCode);
        peers.clear();
        server = new SyncHttpServer(ctx);
        server.start();
        presence = new PresenceService(deviceId, hash, SyncHttpServer.SYNC_PORT, peer -> {
            boolean isNew = !peers.containsKey(peer.ip);
            peers.put(peer.ip, peer);
            if (isNew) for (Callback c : callbacks) c.onPeersChanged(peers.size());
            triggerSyncOnce();   // 发现即拉取一次
        });
        presence.start();
        if (getMode() == AUTO) scheduleAuto();
    }

    public void stop() {
        if (autoTask != null) handler.removeCallbacks(autoTask);
        if (presence != null) presence.stop();
        if (server != null) { server.stop(); server = null; }
        presence = null;
        peers.clear();
    }

    private void scheduleAuto() {
        autoTask = new Runnable() {
            @Override public void run() {
                triggerSyncOnce();
                handler.postDelayed(this, AUTO_INTERVAL);
            }
        };
        handler.postDelayed(autoTask, AUTO_INTERVAL);
    }

    /** 立即对所有已知伙伴拉取并合并一次。 */
    public void triggerSyncOnce() {
        if (syncing || peers.isEmpty()) return;
        syncing = true;
        new Thread(() -> {
            int totalAdded = 0;
            int peerCount = peers.size();
            for (PresenceService.Peer p : new ArrayList<>(peers.values())) {
                List<ChatStore.Msg> remote = SyncClient.pull(p.ip, p.port);
                if (remote != null) totalAdded += new ChatStore(ctx).merge(remote);
            }
            syncing = false;
            final int fAdded = totalAdded, fPeers = peerCount;
            handler.post(() -> { for (Callback c : callbacks) c.onSyncDone(fAdded, fPeers); });
        }, "sync-pull").start();
    }

    public int peerCount() { return peers.size(); }
}
