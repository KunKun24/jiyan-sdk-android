package com.jiyan.cloud.shell;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 壳日志：logcat + Download 目录（失败回退私有目录），1MB 轮转。 */
public final class ShellLog {

    private static final Object LOCK = new Object();
    private static File sFile;

    private ShellLog() {}

    public static synchronized void init(Context ctx) {
        if (sFile != null || ctx == null) return;
        File dir = null;
        try {
            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null || (!dir.exists() && !dir.mkdirs()) || !dir.canWrite()) {
                dir = new File(ctx.getFilesDir(), "logs");
            }
        } catch (Throwable t) {
            dir = new File(ctx.getFilesDir(), "logs");
        }
        if (dir != null && !dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        sFile = new File(dir, ShellConfig.LOG_FILE);
    }

    public static String path() {
        return sFile == null ? "" : sFile.getAbsolutePath();
    }

    public static void i(String tag, String msg) { write("I", tag, msg, null); }

    public static void w(String tag, String msg) { write("W", tag, msg, null); }

    public static void e(String tag, String msg) { write("E", tag, msg, null); }

    public static void e(String tag, String msg, Throwable t) { write("E", tag, msg, t); }

    private static void write(String level, String tag, String msg, Throwable t) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " " + level + "/" + tag + ": " + (msg == null ? "" : msg);
        if ("E".equals(level)) {
            android.util.Log.e(tag, msg == null ? "" : msg, t);
        } else if ("W".equals(level)) {
            android.util.Log.w(tag, msg == null ? "" : msg);
        } else {
            android.util.Log.i(tag, msg == null ? "" : msg);
        }
        if (t != null) {
            line = line + " | " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        synchronized (LOCK) {
            if (sFile == null) return;
            try {
                if (sFile.length() > ShellConfig.LOG_MAX_BYTES) {
                    File old = new File(sFile.getParentFile(), sFile.getName() + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    if (old.exists()) old.delete();
                    //noinspection ResultOfMethodCallIgnored
                    sFile.renameTo(old);
                }
                FileWriter w = new FileWriter(sFile, true);
                try {
                    w.write(line + "\n");
                } finally {
                    w.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }
}
