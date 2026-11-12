package com.jiyan.cloud.shell;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 壳只做最小解析：解出 uid / password / token / 应用列表（含签名密钥），
 * 用于组件接口的签名请求。真正的业务配置仍由组件自己解析。
 */
final class ShellApps {

    static final class App {
        final String id;
        final String name;
        final String signType;
        final String signKey;
        final String apiKey;

        App(String id, String name, String signType, String signKey, String apiKey) {
            this.id = id;
            this.name = name;
            this.signType = signType;
            this.signKey = signKey;
            this.apiKey = apiKey;
        }

        /** 与服务端一致：优先 sign_key，空则 api_key */
        String secret() {
            if (signKey != null && !signKey.isEmpty()) return signKey;
            return apiKey == null ? "" : apiKey;
        }
    }

    final String uid;
    final String password;
    final String token;
    final String deviceId;
    final List<App> apps;

    private ShellApps(String uid, String password, String token, String deviceId, List<App> apps) {
        this.uid = uid;
        this.password = password;
        this.token = token;
        this.deviceId = deviceId;
        this.apps = apps;
    }

    App firstApp() {
        return apps.isEmpty() ? null : apps.get(0);
    }

    static ShellApps decode(String data, String iv, String deviceId) throws Exception {
        if (data == null || data.isEmpty()) throw new IllegalArgumentException("配置为空");
        byte[] key = Base64.decode(ShellConfig.CONFIG_KEY, Base64.DEFAULT);
        byte[] ivb = Base64.decode(iv == null ? "" : iv, Base64.DEFAULT);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(fit(key), "AES"), new IvParameterSpec(fit8(ivb, 16)));
        String plain = new String(c.doFinal(Base64.decode(data, Base64.DEFAULT)), "UTF-8");
        JSONObject root = new JSONObject(plain);
        String uid = root.optString("uid", "");
        String password = root.optString("password", "");
        String token = root.optString("token", "");
        List<App> apps = new ArrayList<>();
        JSONArray arr = root.optJSONArray("apps");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject a = arr.optJSONObject(i);
                if (a == null) continue;
                String id = a.optString("app_id", "").trim();
                if (!id.matches("[1-9][0-9]*")) continue;
                apps.add(new App(id, a.optString("app_name", ""),
                        a.optString("sign_type", "0"),
                        a.optString("sign_key", ""),
                        a.optString("encryption_key", "")));
            }
        }
        if (uid.isEmpty() || apps.isEmpty()) throw new IllegalArgumentException("配置缺少账号或应用");
        return new ShellApps(uid, password, token, deviceId, apps);
    }

    private static byte[] fit(byte[] k) {
        if (k.length == 32) return k;
        byte[] out = new byte[32];
        System.arraycopy(k, 0, out, 0, Math.min(k.length, 32));
        return out;
    }

    private static byte[] fit8(byte[] iv, int n) {
        byte[] out = new byte[n];
        System.arraycopy(iv, 0, out, 0, Math.min(iv.length, n));
        return out;
    }
}
