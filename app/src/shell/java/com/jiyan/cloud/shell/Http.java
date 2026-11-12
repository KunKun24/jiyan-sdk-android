package com.jiyan.cloud.shell;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

/** 组件协议网络层：POST(JSON) 与 断点续传下载。 */
final class Http {

    interface Progress {
        void onProgress(long done, long total, long resumed);
    }

    interface Cancel {
        boolean cancelled();
    }

    private Http() {}

    /** 发一个已签名的请求，校验响应签名，返回 payload。 */
    static JSONObject post(JSONObject body) throws IOException {
        return post(body, ShellConfig.CONNECT_TIMEOUT, ShellConfig.READ_TIMEOUT);
    }

    /** 指定超时（启动阶段用短超时，网络差时不要卡住组件加载） */
    static JSONObject post(JSONObject body, int connectMs, int readMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ShellConfig.API_BASE + "component.php");
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                ((HttpsURLConnection) conn).setSSLSocketFactory(
                        (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault());
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent", ShellConfig.USER_AGENT);
            byte[] out = body.toString().getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(out.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(out);
            } finally {
                os.close();
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            ShellLog.i("Http", "action=" + body.optString("action") + " code=" + code
                    + " bytes=" + text.length());
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " (" + text.length() + "B)");
            }
            JSONObject root = new JSONObject(text);
            if (!root.optBoolean("ok", false)) {
                throw new IOException(root.optString("msg", "接口返回失败"));
            }
            String payload = root.optString("payload", "");
            if (payload.length() == 0) {
                throw new IOException("响应内容为空");
            }
            return new JSONObject(payload);
        } catch (org.json.JSONException e) {
            throw new IOException("响应格式错误", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 断点续传下载。part 文件保留，成功后由调用方校验并改名。
     */
    static void download(String path, File part, long expectSize, Progress p, Cancel c) throws IOException {
        long already = part.isFile() ? part.length() : 0;
        if (expectSize > 0 && already == expectSize) {
            return;
        }
        if (expectSize > 0 && already > expectSize) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            already = 0;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ShellConfig.API_BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(ShellConfig.CONNECT_TIMEOUT);
            conn.setReadTimeout(ShellConfig.READ_TIMEOUT);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", ShellConfig.USER_AGENT);
            conn.setRequestProperty("Accept-Encoding", "identity");
            if (already > 0) {
                conn.setRequestProperty("Range", "bytes=" + already + "-");
            }
            int code = conn.getResponseCode();
            boolean append;
            if (code == 206) {
                append = true;
            } else if (code == 200) {
                append = false;
                already = 0;
            } else {
                throw new IOException("下载失败 HTTP " + code);
            }
            long total = expectSize > 0 ? expectSize : conn.getContentLength() + already;
            InputStream in = new BufferedInputStream(conn.getInputStream(), 65536);
            FileOutputStream fos = new FileOutputStream(part, append);
            long done = already;
            byte[] buf = new byte[65536];
            long last = 0;
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (c != null && c.cancelled()) {
                        throw new IOException("已取消");
                    }
                    fos.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (p != null && (now - last > 200 || done == total)) {
                        last = now;
                        p.onProgress(done, total, already);
                    }
                }
                fos.flush();
                if (fos.getFD() != null) {
                    try {
                        fos.getFD().sync();
                    } catch (Throwable ignored) {
                    }
                }
            } finally {
                try {
                    fos.close();
                } catch (Throwable ignored) {
                }
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (expectSize > 0 && done < expectSize) {
                throw new IOException("下载不完整 " + done + "/" + expectSize);
            }
            if (expectSize > 0 && part.length() != expectSize) {
                throw new IOException("文件大小不一致");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 256 * 1024) break;
            }
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
        return new String(bos.toByteArray(), "UTF-8");
    }
}
