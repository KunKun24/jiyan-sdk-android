package com.jiyan.cloud.comp;

import android.content.Context;

import com.jiyan.cloud.spi.Component;

/**
 * 组件入口示例。
 *
 * 约定：
 * 1. 类名必须是 com.jiyan.cloud.comp.ComponentImpl（可在后台改成别的，但壳按配置加载）；
 * 2. 必须 public、无参构造、实现 Component；
 * 3. 不要依赖宿主的类加载器去 find 业务类——组件自己就是业务的容器。
 */
public final class ComponentImpl implements Component {

    @Override
    public void init(Context ctx, String data, String iv, int shellVersion) throws Throwable {
        // data/iv 是后台「生成 Data 与 IV」的密文配置，
        // 在这里解密、初始化你的业务、启动界面或心跳。
        // 示例只打印，接入时替换成真实逻辑。
        android.util.Log.i("JiYan.Component",
                "init shell=" + shellVersion + " dataLen=" + (data == null ? 0 : data.length())
                        + " ivLen=" + (iv == null ? 0 : iv.length()));
    }

    @Override
    public void shutdown() {
        // 释放线程、定时器、监听器；壳热切换前会调用一次。
    }
}
