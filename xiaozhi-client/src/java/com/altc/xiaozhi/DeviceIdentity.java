/**
 * 【已停用，仅保留代码备查】
 *
 * 原用途：给官方 v2 激活（Serial-Number + HMAC /ota/activate）造一个本地自签序列号。
 *
 * 为什么停用（2026-10-24 实测）：一旦客户端上报 Serial-Number 并调用 /ota/activate，
 * 云端就把设备登记成「已激活的 v2 设备」，之后在 xiaozhi.me 输入验证码绑定时会去
 * 校验 eFuse 序列号 license，查不到直接报「需要序列号，请检查设备是否已烧录」，
 * 该身份永久无法绑定。
 *
 * 正确路径：只走 v1 纯验证码流程（OtaClient.fetch，不带 Serial-Number、不调 activate），
 * 绑定由网页端完成，绑定成功后 OTA 返回「无 activation」。MainActivity 已不再引用本类。
 */

package com.altc.xiaozhi;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * 设备身份（对齐官方 v2 激活的「eFuse 序列号 + HMAC 密钥」）。
 *
 * 官方 ESP32 固件把序列号烧在 eFuse 的 USER_DATA、密钥烧在 BLOCK_KEY0，
 * 设备激活时用密钥对 challenge 做 HMAC-SHA256 签名提交给云端。
 * Android 上没有 eFuse，这里做成「本地自签」：首次启动生成一次并持久化，
 * 之后永不变更（序列号是云端识别设备的唯一凭据，变了就等于换了一台设备）。
 *
 * 实测结论（2026-10-24）：
 *  - 不带 Serial-Number 的 OTA 记录，在 xiaozhi.me 绑定时会被拒绝，
 *    报「需要序列号，请检查设备是否已烧录」。
 *  - 带上 Serial-Number 后，/xiaozhi/ota/activate 返回 202（等待用户在网页输码），
 *    说明云端接受本地自签序列号；HTTP 200 即激活完成。
 */
public class DeviceIdentity {
    private static final String PREF = "xz";
    private static final String K_SERIAL = "serialNumber";
    private static final String K_HMAC = "hmacKey";

    public final String serialNumber;
    public final String hmacKey;

    private DeviceIdentity(String sn, String key) {
        this.serialNumber = sn;
        this.hmacKey = key;
    }

    /** 读取已有身份；没有则生成并持久化。 */
    public static DeviceIdentity load(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String sn = sp.getString(K_SERIAL, null);
        String key = sp.getString(K_HMAC, null);
        SecureRandom rnd = new SecureRandom();
        boolean dirty = false;
        if (sn == null || sn.length() < 8) {
            sn = newSerial(rnd);
            dirty = true;
        }
        if (key == null || key.length() != 64) {
            key = hex(rnd, 32);
            dirty = true;
        }
        if (dirty) {
            sp.edit().putString(K_SERIAL, sn).putString(K_HMAC, key).apply();
        }
        return new DeviceIdentity(sn, key);
    }

    /** 强制换一台「新设备」：重新生成序列号（旧记录在云端作废）。 */
    public static DeviceIdentity regenerate(Context ctx) {
        SecureRandom rnd = new SecureRandom();
        String sn = newSerial(rnd);
        String key = hex(rnd, 32);
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(K_SERIAL, sn).putString(K_HMAC, key).apply();
        return new DeviceIdentity(sn, key);
    }

    /** 形如 SN-5CD8467B47FB4921，与官方固件烧录格式一致。 */
    private static String newSerial(SecureRandom rnd) {
        byte[] b = new byte[8];
        rnd.nextBytes(b);
        return "SN-" + hexUpper(b);
    }

    public static String hex(SecureRandom rnd, int bytes) {
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        return hexLower(b);
    }

    private static String hexLower(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String hexUpper(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }
}
