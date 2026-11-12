# 快速开始

本文把极验云 Android 壳接入宿主工程，并跑通第一个组件。内容依据 `app/src/shell/java` 的当前源码；服务端、后台与业务组件不在本仓库。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android | 5.0（API 21）及以上 |
| Java 源码级别 | Java 8（壳只使用 Java 7/8 与 Android 基础 API） |
| 编译 JDK | JDK 17（`scripts/build-check.sh` 使用 `-source 8 -target 8`） |
| 构建工具 | `javac` + `d8`，或等价的自建脚本 |
| 运行时依赖 | 无 AndroidX、无第三方库；`org.json` 由 Android 提供 |
| 组件大小 | 建议不超过 4 MB（服务端默认上限） |
| 组件入口 | 默认 `com.jiyan.cloud.comp.ComponentImpl` |

## 接入步骤

1. 把 `app/src/shell/java` 整个复制进宿主工程，保持包名 `com.jiyan.cloud` 与 `com.jiyan.cloud.shell` 不变。
2. 保持 `Cloud.java` 的公开方法签名不变，宿主启动时传入后台生成的 `data` / `iv` 并调用 `init`。
3. 业务代码放在独立 source set 或独立 module，编译成 `component.dex`，不要与壳重复打包同一批类。
4. 组件入口实现 `com.jiyan.cloud.spi.Component`，类名默认 `com.jiyan.cloud.comp.ComponentImpl`，必须是 `public`、有无参构造函数。
5. 组件用后台私钥签名、上传并发布版本；壳从清单取 `component_url` 后下载。
6. 合并最终宿主 DEX：宿主仍只交付一个 DEX，组件由 `DexClassLoader` 从 `components/vN/classes.dex` 加载。

```java
// 宿主启动时调用；data / iv 为后台下发的密文配置
Cloud.set_data(data);
Cloud.set_iv(iv);
Cloud.init(context);
```

`ComponentManager.start` 用静态单例与 `synchronized` 保证重复调用不会重复初始化。

## 组件入口

```java
package com.jiyan.cloud.comp;

import android.content.Context;
import com.jiyan.cloud.spi.Component;

public final class ComponentImpl implements Component {
    @Override
    public void init(Context ctx, String data, String iv, int shellVersion) throws Throwable {
        // 解密 data / iv，初始化业务与界面；耗时任务请自行开线程
    }

    @Override
    public void shutdown() {
        // 释放线程、定时器、悬浮窗；热切换前会调用一次
    }
}
```

| 约定 | 说明 |
| --- | --- |
| 类名 | 固定为 `ShellConfig.COMPONENT_ENTRY`（`com.jiyan.cloud.comp.ComponentImpl`）；状态里的 `entry` 只用于加载，改入口需改壳并重发宿主包 |
| 构造函数 | 必须 `public` 且无参，壳使用 `newInstance()` |
| 接口 | 只能依赖 `com.jiyan.cloud.spi.Component`，不要引用壳内部类 |
| 线程 | 接口注释写“在子线程调用”，当前实现实际在主线程调用；`init` 不要阻塞 |
| 异常 | `init` 抛出的 `Throwable` 会被壳捕获，计入失败次数并触发回退 |

## 首次安装与重启

启动时壳先取一次清单（短超时），有可用版本就弹出确认卡片，用户点“立即下载”后才开始下载。安装完成后分三种情况：

| 场景 | 源码行为 |
| --- | --- |
| 启动阶段安装，且当前进程还没加载过组件 | `bootLoad = true`，安装后直接 `load()`，Toast 提示“已生效”，不弹重启 |
| 已加载过组件，热切换成功 | 调用旧组件 `shutdown()`，新建 `DexClassLoader` 加载新组件，不重启 |
| 热切换失败，或安装发生在非启动路径 | 弹出“组件库已就绪”要求重启；`pending_restart` 只在非启动分支写入，而 `bootLoad` 恒为 true，该分支当前不可达 |

需要重启的原因是：旧组件的类已经由前一个 `DexClassLoader` 加载，进程内无法真正卸载；组件后续要下载的 `.so` 也约定由“重启后接管的新组件”自己完成，壳不在同一次启动里继续下载云更新文件。`pending_restart` 的格式是 `<pid>|<组件版本>|<毫秒时间戳>`，由组件在入口处消费。

> README 写“首次安装组件后提示重启一次”，但当前启动预取路径会直接加载、不提示重启。两种分支都在源码中，以实际代码路径为准。

## 目录约定

| 路径 | 内容 |
| --- | --- |
| `filesDir/JiYan_Cloud/shell/state.json` | active / previous / fail / bad，原子写入 |
| `filesDir/JiYan_Cloud/shell/components/vN/classes.dex` | 已安装组件 |
| `filesDir/JiYan_Cloud/shell/components/vN/meta.json` | 版本、`min_shell`、SHA-256、大小、签名留档 |
| `filesDir/JiYan_Cloud/shell/components/vN/component.dex.part` | 下载中的临时文件，支持 Range 续传 |
| `filesDir/JiYan_Cloud/shell/odex/` | `DexClassLoader` 优化输出目录 |
| `filesDir/JiYan_Cloud/shell/pending_restart` | 待重启标记 |
| `Download/JiYan_shell.log` | 日志；外部存储不可写时退回 `filesDir/logs`，1 MB 轮转 |

除 active 与 previous 外的版本目录，超过 30 天未使用会被清理。`meta.json` 只落盘留档，当前源码从 `state.json` 恢复状态，不读取 `meta.json`。

## 壳不能自更新

壳被编进宿主 DEX，升级壳必须重新合并 DEX、重新签名并发布新宿主包；组件才能随时热更新。`min_shell` 用来阻止旧壳加载新组件：组件的 `min_shell` 大于 `ShellConfig.SHELL_VERSION` 时，壳会提示“需要先更新接入包”。

## 最小可运行示例

`sample-component/java/com/jiyan/cloud/comp/ComponentImpl.java` 已给出可编译的最小实现。构建时把壳源码与组件源码分开编译：

```bash
javac -source 8 -target 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR:$JSON_JAR" -d shell-classes \
  $(find app/src/shell/java -name '*.java')

javac -source 8 -target 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d component-classes \
  $(find sample-component/java -name '*.java')
d8 --lib "$ANDROID_JAR" --output component-out component-classes
```

`d8` 的输入、输出参数与打包方式以你的构建工具为准；关键是组件 DEX 内必须包含入口类。壳与组件不要重复打包同一批业务类，否则 `DexClassLoader` 可能出现类冲突。

## 自测

```bash
bash scripts/build-check.sh
```

脚本首次运行会下载 `android-all` 与 `org.json`，然后编译壳与测试并输出 4 个 `[PASS]`，覆盖 HMAC RFC 4231 向量、canonical 的 11 段、字段顺序与 64 位十六进制长度。测试不联网、不需要设备。下一步阅读[组件协议](protocol.md)与[签名与校验](signing.md)。
