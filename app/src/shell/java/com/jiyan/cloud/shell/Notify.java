package com.jiyan.cloud.shell;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知栏进度（缺少通知权限时静默降级）。 */
final class Notify {

    private static final int ID = 0x4a59;
    private static final String CHANNEL = "jiyan_component";

    private static NotificationManager nm(Context c) {
        return (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static void ensureChannel(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager m = nm(c);
                if (m != null && m.getNotificationChannel(CHANNEL) == null) {
                    NotificationChannel ch = new NotificationChannel(
                            CHANNEL, "组件更新", NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    m.createNotificationChannel(ch);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static Notification.Builder builder(Context c, String title, String text) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(c, CHANNEL);
        } else {
            b = new Notification.Builder(c);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
            if (i != null) {
                b.setContentIntent(PendingIntent.getActivity(c, 0, i,
                        Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            }
        } catch (Throwable ignored) {
        }
        return b;
    }

    static void start(Context c, String title, String text) {
        ensureChannel(c);
        try {
            Notification.Builder b = builder(c, title, text)
                    .setProgress(100, 0, true);
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    static void progress(Context c, int percent, String text) {
        ensureChannel(c);
        try {
            Notification.Builder b = builder(c, "组件库更新", text)
                    .setProgress(100, Math.max(0, Math.min(100, percent)), false);
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    static void done(Context c, String text) {
        ensureChannel(c);
        try {
            Notification.Builder b = builder(c, "组件库已更新", text).setOngoing(false);
            NotificationManager m = nm(c);
            if (m != null) {
                m.notify(ID, b.build());
                final NotificationManager mm = m;
                new android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed(new Runnable() {
                            @Override public void run() {
                                try {
                                    mm.cancel(ID);
                                } catch (Throwable ignored) {
                                }
                            }
                        }, 4000);
            }
        } catch (Throwable ignored) {
        }
    }

    static void fail(Context c, String text) {
        ensureChannel(c);
        try {
            Notification.Builder b = builder(c, "组件库更新失败", text).setOngoing(false);
            NotificationManager m = nm(c);
            if (m != null) m.notify(ID, b.build());
        } catch (Throwable ignored) {
        }
    }

    static void cancel(Context c) {
        try {
            NotificationManager m = nm(c);
            if (m != null) m.cancel(ID);
        } catch (Throwable ignored) {
        }
    }
}
