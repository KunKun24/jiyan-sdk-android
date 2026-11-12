package com.jiyan.cloud.shell;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;

/** 组件目录与状态文件（原子写）。 */
final class ComponentState {

    static final class Entry {
        int version;
        int minShell;
        String sha256 = "";
        long size;
        String entry = ShellConfig.COMPONENT_ENTRY;
        String label = "";

        boolean valid() {
            return version > 0 && sha256.length() == 64 && size > 0;
        }

        File dex(Context ctx) {
            return dexFor(ctx, version);
        }

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("v", version);
            o.put("min_shell", minShell);
            o.put("sha256", sha256);
            o.put("size", size);
            o.put("entry", entry);
            o.put("label", label == null ? "" : label);
            return o;
        }

        static Entry from(JSONObject o) {
            if (o == null) return null;
            Entry e = new Entry();
            e.version = o.optInt("v", 0);
            e.minShell = o.optInt("min_shell", 1);
            e.sha256 = o.optString("sha256", "");
            e.size = o.optLong("size", 0);
            e.entry = o.optString("entry", ShellConfig.COMPONENT_ENTRY);
            e.label = o.optString("label", "");
            return e.valid() ? e : null;
        }
    }

    Entry active;
    Entry previous;
    int fail;
    /** 连续失败被判定为坏包的版本，收到更高版本前不再重试 */
    int badVersion;

    private ComponentState() {}

    static File shellDir(Context ctx) {
        File d = new File(new File(ctx.getFilesDir(), ShellConfig.ROOT_DIR), ShellConfig.SHELL_DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    static File componentsDir(Context ctx) {
        File d = new File(shellDir(ctx), ShellConfig.COMPONENTS_DIR);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    static File versionDir(Context ctx, int version) {
        File d = new File(componentsDir(ctx), "v" + version);
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    static File dexFor(Context ctx, int version) {
        return new File(versionDir(ctx, version), "classes.dex");
    }

    static ComponentState load(Context ctx) {
        ComponentState s = new ComponentState();
        File f = new File(shellDir(ctx), ShellConfig.STATE_FILE);
        if (!f.isFile()) return s;
        try {
            String text = readText(f);
            JSONObject o = new JSONObject(text);
            s.active = Entry.from(o.optJSONObject("active"));
            s.previous = Entry.from(o.optJSONObject("previous"));
            s.fail = o.optInt("fail", 0);
            s.badVersion = o.optInt("bad", 0);
        } catch (Throwable t) {
            ShellLog.e("State", "读取状态失败", t);
        }
        return s;
    }

    void save(Context ctx) {
        try {
            JSONObject o = new JSONObject();
            if (active != null) o.put("active", active.toJson());
            if (previous != null) o.put("previous", previous.toJson());
            o.put("fail", fail);
            if (badVersion > 0) o.put("bad", badVersion);
            atomicWrite(new File(shellDir(ctx), ShellConfig.STATE_FILE), o.toString());
        } catch (Throwable t) {
            ShellLog.e("State", "写入状态失败", t);
        }
    }

    static void atomicWrite(File dst, String text) throws IOException {
        File tmp = new File(dst.getParentFile(), dst.getName() + ".tmp");
        FileOutputStream fos = new FileOutputStream(tmp, false);
        try {
            fos.write(text.getBytes(Charset.forName("UTF-8")));
            fos.flush();
            try {
                fos.getFD().sync();
            } catch (Throwable ignored) {
            }
        } finally {
            fos.close();
        }
        if (dst.exists() && !dst.delete()) {
            throw new IOException("旧文件无法删除");
        }
        if (!tmp.renameTo(dst)) {
            throw new IOException("状态文件改名失败");
        }
    }

    static String readText(File f) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(f, "r");
        try {
            byte[] b = new byte[(int) Math.min(Integer.MAX_VALUE, raf.length())];
            raf.readFully(b);
            return new String(b, "UTF-8");
        } finally {
            raf.close();
        }
    }

    /** 清理除 keep 以外、超过 30 天未使用的组件目录。 */
    static void prune(Context ctx, Entry keep, Entry keep2) {
        File dir = componentsDir(ctx);
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long now = System.currentTimeMillis();
        for (File f : fs) {
            if (!f.isDirectory()) continue;
            if (keep != null && f.getName().equals("v" + keep.version)) continue;
            if (keep2 != null && f.getName().equals("v" + keep2.version)) continue;
            if (now - f.lastModified() > 30L * 24 * 3600 * 1000) {
                deleteTree(f);
            }
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] fs = f.listFiles();
        if (fs != null) {
            for (File c : fs) deleteTree(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    static String sha256(File f) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[65536];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            } finally {
                in.close();
            }
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(64);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }
}
