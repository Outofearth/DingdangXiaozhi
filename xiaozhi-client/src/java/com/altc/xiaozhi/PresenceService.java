package com.altc.xiaozhi;

import android.util.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 局域网发现：UDP 广播本机 presence（device-id + 配对码哈希 + HTTP 同步端口），
 * 同时监听同端口接收他人 presence。只有「device-id 与配对哈希都与本机一致」的对端
 * 才被视为可信伙伴，回调上层其 IP 与端口。
 */
public class PresenceService {
    public static final int PRESENCE_PORT = 18767;
    private static final String TAG = "Presence";
    private static final long INTERVAL = 3000;

    public static class Peer {
        public final String ip;
        public final int port;
        Peer(String ip, int port) { this.ip = ip; this.port = port; }
    }

    public interface Listener {
        void onPeerFound(Peer peer);
    }

    private final String myDeviceId;
    private final String myHash;
    private final int myHttpPort;
    private final Listener listener;

    private DatagramSocket sock;
    private Thread tx;
    private Thread rx;
    private volatile boolean running;

    public PresenceService(String deviceId, String pairHash, int httpPort, Listener l) {
        this.myDeviceId = deviceId;
        this.myHash = pairHash;
        this.myHttpPort = httpPort;
        this.listener = l;
    }

    public void start() {
        if (running) return;
        try {
            sock = new DatagramSocket(PRESENCE_PORT);
            sock.setBroadcast(true);
            sock.setSoTimeout(1000);
        } catch (SocketException e) {
            Log.e(TAG, "无法启动 presence：" + e.getMessage());
            return;
        }
        running = true;
        rx = new Thread(this::rxLoop, "presence-rx");
        rx.setDaemon(true);
        rx.start();
        tx = new Thread(this::txLoop, "presence-tx");
        tx.setDaemon(true);
        tx.start();
    }

    public void stop() {
        running = false;
        if (sock != null) sock.close();
        if (rx != null) rx.interrupt();
        if (tx != null) tx.interrupt();
    }

    private String packet() {
        return "XIAOZHI-SYNC|" + myDeviceId + "|" + myHash + "|" + myHttpPort;
    }

    private void txLoop() {
        byte[] buf = packet().getBytes(StandardCharsets.UTF_8);
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length,
                        InetAddress.getByName("255.255.255.255"), PRESENCE_PORT);
                try { sock.send(p); } catch (Exception ignore) {}
                for (InetAddress b : broadcastAddresses()) {
                    try {
                        sock.send(new DatagramPacket(buf, buf.length, b, PRESENCE_PORT));
                    } catch (Exception ignore) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "tx 出错：" + e.getMessage());
            }
            try { Thread.sleep(INTERVAL); } catch (InterruptedException e) { break; }
        }
    }

    private void rxLoop() {
        byte[] buf = new byte[256];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                Peer peer = parse(p.getAddress().getHostAddress(), msg);
                if (peer != null && listener != null) listener.onPeerFound(peer);
            } catch (java.net.SocketTimeoutException ignore) {
                // 周期性超时，继续
            } catch (Exception e) {
                if (running) Log.w(TAG, "rx 出错：" + e.getMessage());
            }
        }
    }

    private Peer parse(String fromIp, String msg) {
        if (msg == null || !msg.startsWith("XIAOZHI-SYNC|")) return null;
        String[] p = msg.split("\\|");
        if (p.length < 4) return null;
        if (!myDeviceId.equals(p[1])) return null;   // 同账号
        if (!myHash.equals(p[2])) return null;        // 同配对码
        try {
            return new Peer(fromIp, Integer.parseInt(p[3]));
        } catch (NumberFormatException e) { return null; }
    }

    private Set<InetAddress> broadcastAddresses() {
        Set<InetAddress> out = new HashSet<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
        } catch (SocketException ignore) {}
        return out;
    }
}
