package com.jiyan.cloud;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 编译壳自带的演示 Activity（只用于产出可安装的测试 APK，正式对接不经过它）。
 *
 * 正式对接仍由宿主在 onCreate 里 smali 注入三行：
 *   const-string v0, "data" → Cloud.set_data
 *   const-string v0, "iv"   → Cloud.set_iv
 *   invoke-static {p0}, Lcom/jiyan/cloud/Cloud;->init(Landroid/content/Context;)V
 *
 * 如果把 assets/jiyan/config.json 一起打进宿主（内容 {"data":"…","iv":"…"}），
 * 演示页会自动带配置启动，方便自测。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E0E10);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (getResources().getDisplayMetrics().density * 24);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("JiYan Cloud Shell");
        title.setTextColor(0xFFF2F2F7);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView ver = new TextView(this);
        ver.setText("壳 1.3 · 组件化");
        ver.setTextColor(0xFF8A8D91);
        ver.setTextSize(13);
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = pad / 2;
        root.addView(ver, vlp);

        TextView tip = new TextView(this);
        tip.setText("本 APK 仅用于验证组件库下载/热更新。\n"
                + "正式使用时把 classes.dex 注入宿主，\n"
                + "由宿主调用 Cloud.set_data / set_iv / init。");
        tip.setTextColor(0xFF6E6E73);
        tip.setTextSize(12);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(pad / 6, 1f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = pad;
        root.addView(tip, tlp);

        TextView btn = new TextView(this);
        btn.setText("用内置配置启动");
        btn.setTextColor(0xFF111111);
        btn.setTextSize(15);
        btn.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xFFF2F2F7);
        g.setCornerRadius(pad);
        btn.setBackground(g);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) (getResources().getDisplayMetrics().density * 44));
        blp.topMargin = pad;
        blp.leftMargin = pad;
        blp.rightMargin = pad;
        btn.setPadding(pad, 0, pad, 0);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                launchWithAssets();
            }
        });
        root.addView(btn, blp);

        setContentView(root);

        // 有内置配置就直接跑，省一次点击
        if (readAsset("jiyan/config.json") != null) {
            root.post(new Runnable() {
                @Override public void run() {
                    launchWithAssets();
                }
            });
        }
    }

    private void launchWithAssets() {
        String cfg = readAsset("jiyan/config.json");
        if (cfg == null) {
            android.widget.Toast.makeText(this,
                    "APK 内没有 assets/jiyan/config.json\n请在宿主里注入 data/iv",
                    android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(cfg);
            Cloud.set_data(o.optString("data", ""));
            Cloud.set_iv(o.optString("iv", ""));
            Cloud.init(this);
        } catch (Throwable t) {
            android.widget.Toast.makeText(this, "配置格式错误: " + t.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private String readAsset(String name) {
        InputStream in = null;
        try {
            in = getAssets().open(name);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static Context app(Context c) {
        return c.getApplicationContext();
    }
}
