package com.altc.xiaozhi;

import java.security.MessageDigest;

/**
 * 配对码工具。
 *
 * 多设备同步的信任模型：两端填同一个 device-id（MAC）且设置同一个「同步配对码」，
 * 才认为是对端可信伙伴。presence 广播与同步拉取都以
 * {@link #hash(String, String)} 派生的哈希作为信任凭据，避免同局域网内他人同名设备误同步。
 */
public final class PairCode {
    private PairCode() {}

    /** device-id + 配对码 -> SHA-256 十六进制串。两端一致才相等。 */
    public static String hash(String deviceId, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String seed = (deviceId == null ? "" : deviceId) + "|" + (code == null ? "" : code);
            byte[] d = md.digest(seed.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
