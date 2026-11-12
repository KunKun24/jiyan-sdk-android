package com.jiyan.cloud.shell;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 组件协议签名：HMAC-SHA256，canonical 用 \n 连接 11 个字段。 */
final class Signer {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private Signer() {}

    static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes("UTF-8"));
            char[] c = new char[out.length * 2];
            for (int i = 0; i < out.length; i++) {
                int v = out[i] & 0xff;
                c[i * 2] = HEX[v >>> 4];
                c[i * 2 + 1] = HEX[v & 0x0f];
            }
            return new String(c);
        } catch (Exception e) {
            throw new RuntimeException("HMAC 计算失败", e);
        }
    }

    static String nonce() {
        byte[] b = new byte[18];
        RND.nextBytes(b);
        return base64Url(b);
    }

    static String canonical(String action, String user, String appId, String device,
                            String shellVer, String curVer, String ts, String nonce,
                            String version, String status, String detail) {
        return action + "\n" + user + "\n" + appId + "\n" + device + "\n" + shellVer + "\n"
                + curVer + "\n" + ts + "\n" + nonce + "\n" + version + "\n" + status + "\n" + detail;
    }

    private static String base64Url(byte[] b) {
        String s = android.util.Base64.encodeToString(b,
                android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING);
        return s;
    }

    static byte[] utf8(String s) {
        try {
            return s.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s.getBytes();
        }
    }
}
