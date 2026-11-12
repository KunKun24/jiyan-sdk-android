package com.jiyan.cloud;

import android.content.Context;

import com.jiyan.cloud.shell.ComponentManager;

/**
 * 壳门面：**保持与 1.2 完全一致的公开签名**，宿主 smali 注入的三行不用改：
 *
 *   const-string v0, "data"
 *   invoke-static {v0}, Lcom/jiyan/cloud/Cloud;->set_data(Ljava/lang/String;)V
 *   const-string v0, "iv"
 *   invoke-static {v0}, Lcom/jiyan/cloud/Cloud;->set_iv(Ljava/lang/String;)V
 *   invoke-static {p0}, Lcom/jiyan/cloud/Cloud;->init(Landroid/content/Context;)V
 *
 * 壳只负责：读取/校验/加载组件、组件库热更新、最小进度提示。
 * 业务（卡密、云更新文件下载、所有弹窗）都在组件里，由组件接管。
 */
public final class Cloud {

    public static String Config_data = "";
    public static String Config_iv = "";

    private static volatile String sNotice = "";

    private Cloud() {}

    public static void set_data(String data) {
        Config_data = data == null ? "" : data;
    }

    public static void set_iv(String iv) {
        Config_iv = iv == null ? "" : iv;
    }

    /** 兼容旧 API：允许宿主下一次重新进入时重新检查。 */
    public static void allowRetry() {
        sNotice = "";
    }

    public static void init(Context context) {
        if (context == null) return;
        ComponentManager.start(context, Config_data, Config_iv);
    }

    public static String takeNotice() {
        String n = sNotice;
        sNotice = "";
        return n;
    }

    public static void setNotice(String notice) {
        sNotice = notice == null ? "" : notice;
    }
}
