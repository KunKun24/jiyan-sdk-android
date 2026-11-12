package com.jiyan.cloud.spi;

import android.content.Context;

/**
 * 业务组件接口。
 *
 * 壳只认识这一个接口：组件 dex 里必须有且仅有一个实现类，
 * 类名固定为 com.jiyan.cloud.comp.ComponentImpl，并提供无参构造函数。
 *
 * 注意：组件与壳运行在不同的 DexClassLoader 中，
 * 不能互相引用对方的类（除本接口外），所有数据通过参数传递。
 */
public interface Component {

    /**
     * 初始化组件（在子线程调用）。
     *
     * @param ctx          宿主 Context
     * @param data         宿主通过 Cloud.set_data 传入的密文
     * @param iv           宿主通过 Cloud.set_iv 传入的 IV
     * @param shellVersion 壳版本号，组件可据此做兼容
     */
    void init(Context ctx, String data, String iv, int shellVersion) throws Throwable;

    /** 壳切换/退出时调用，组件应停掉自己的线程与悬浮窗。 */
    void shutdown();
}
