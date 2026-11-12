# 兼容性

本文说明壳与组件在 Android 版本、Java、体积、ABI、多应用和旧版 DEX 上的边界。仓库没有保留 1.2/1.3 的完整旧实现，涉及旧版行为的结论只以现存注释和 CHANGELOG 为据，其余以源码为准。

## 系统与工具链

| 项目 | 结论 | 依据 |
| --- | --- | --- |
| 最低系统 | Android 5.0（API 21） | README 声明 |
| 编译 JDK | JDK 17 | `scripts/build-check.sh` |
| 源码/字节码目标 | Java 8（`-source 8 -target 8`） | 构建脚本 |
| 运行时依赖 | 无 AndroidX、无第三方库 | 壳只引用 Android 基础 API 与 `org.json` |
| 构建产物 | 壳 class 合并进宿主 DEX；组件单独 `component.dex` | `ComponentManager` 的 `DexClassLoader` |
| 混淆 | 壳与组件需各自保留入口类名 | 反射 `loadClass(entry)` |

壳用到 `Dialog`、`Notification`、`DexClassLoader`、`Settings.Secure` 等 API 21 已存在的接口。API 23 起悬浮窗需要 `canDrawOverlays` 授权，API 26 起通知渠道与 `TYPE_APPLICATION_OVERLAY` 生效；壳对这两处做了分支，失败时静默降级到通知栏或对话框。

## 组件体积与 ABI

| 项目 | 说明 |
| --- | --- |
| 建议上限 | 4 MB（服务端默认限制） |
| 客户端硬限制 | 没有 4 MB 常量；只校验 manifest 的 `size` 与实际文件 |
| DEX 方法数 | 单个 DEX 仍受 65,536 方法引用限制 |
| ABI | 与 ABI 无关，组件是纯 Java 字节码 |
| SO 库 | 壳不随组件下载 `.so`；需要 native 时由组件自己处理 |

组件超过 4 MB 时，服务端可能直接拒绝上传或下发；客户端不会因为“大于 4 MB”单独报错，只会在大小或 SHA-256 不匹配时报“大小不符”“SHA-256 校验失败”。把大资源放进组件会拉长下载与校验时间，建议资源走业务自己的 CDN。

组件与壳由不同的 `DexClassLoader` 加载，不能互相引用对方的类（`com.jiyan.cloud.spi.Component` 除外）。如果组件自带 `.so`，要在 `init` 后自行释放并通过 `System.load` 加载；壳不会在同一次启动里继续下载云更新文件，壳预留了 `pending_restart` 标记，但当前可达路径不会写入，跨重启动作仍需组件自己衔接。

## 多应用

| 项目 | 当前行为 |
| --- | --- |
| 配置解析 | `ShellApps` 会解析 `apps[]` 的全部条目 |
| 实际使用 | 只取 `firstApp()`，`call` / `report` 都只用第一个应用 |
| 应用隔离 | 每个宿主包有自己的 `filesDir`、`state.json` 与组件目录 |
| 设备标识 | 同一设备上的多个宿主共用 `ANDROID_ID` |
| 密钥 | 每个 `app_id` 有自己的 `sign_key` / `encryption_key` |

```json
{
  "uid": "<USER>",
  "apps": [
    {"app_id": "<APP_ID>", "app_name": "<NAME>", "sign_type": "<SIGN_TYPE>",
     "sign_key": "<SECRET>", "encryption_key": "<SECRET>"}
  ]
}
```

一个进程内只支持一个应用上下文；同一个宿主里配置多个 `app_id` 时，第二个及之后的条目不会被请求使用。需要多应用时，让每个应用使用独立包名与独立进程，分别初始化壳；不要试图在同一个 `Cloud.init` 里切换 `app_id`。

## 与旧版 DEX（1.2/1.3）的差异

| 版本线索 | 事实 | 影响 |
| --- | --- | --- |
| 1.2 | `Cloud.java` 注释称公开签名“与 1.2 完全一致” | 宿主原来注入的三行调用不用改 |
| 1.3 | `MainActivity` 显示“壳 1.3 · 组件化” | 业务从壳内移到组件，通过 `Component` 接口接入 |
| 开源版 | CHANGELOG 记 1.0.0（2026-11-13，对应 build 26） | 开源仓库从该版本起 |
| 旧版实现 | 仓库未保留 1.2/1.3 源码 | 无法逐条比对，旧版细节以历史发布说明为准 |

迁移时保留 `Cloud.set_data` / `set_iv` / `init` 三个入口，把原壳内的业务代码搬进 `ComponentImpl`；不要同时把旧业务类留在宿主 DEX 和组件 DEX 中，否则会出现两份类、状态互不可见。旧版若直接依赖壳内部类，需要改为只依赖 `com.jiyan.cloud.spi.Component`。

## build 号与 min_shell

| 字段 | 当前值 | 用途 |
| --- | --- | --- |
| `SHELL_BUILD` | `26` | 构建号，进入 `User-Agent` 与展示，不参与兼容判断 |
| `SHELL_VERSION` | `1` | 协议/接口代际，`min_shell` 与它比较 |
| 组件 `min_shell` | 发布时填写 | 大于 `SHELL_VERSION` 则拒绝加载 |

关键点是 `min_shell` 比较的是 `SHELL_VERSION=1`，不是 `SHELL_BUILD=26`。把 `min_shell` 写成 26 会让所有现有壳提示“需要先更新接入包”，即使它们就是 build 26。当前发布组件时 `min_shell` 不能大于 1；要抬高它，必须先发布 `SHELL_VERSION` 更高的新壳。

## 升级路径

1. 组件更新：提高 `version`、重新计算 `sha256`、重新签名并发布；壳按清单提示下载，成功后热切换或提示重启。
2. 壳更新：把新壳源码合并进宿主、重新签名并发布整包；壳不能自更新，不能只替换组件。
3. 兼容旧壳：新组件继续把 `min_shell` 保持在旧壳支持范围内；必须不兼容时再抬高 `SHELL_VERSION`。
4. 回退保障：发布前确认 `previous` 指向一个可用的旧版本，且其 DEX 未被清理，否则首次失败会直接进入重新下载。
5. 清理策略：非 active/previous 的版本目录超过 30 天会被删除；active 与 previous 始终保留，但 previous 被替换或清空后，旧目录也会进入清理范围。
6. 灰度：组件每次发布都递增 `version`；壳只按 `sha256` 判断变化，同版本换包也会被识别为更新，但会让回退与排查失去版本依据。

## 兼容性检查清单

| 检查项 | 通过标准 |
| --- | --- |
| 最低版本 | 在 API 21 设备/模拟器上能完成首次下载与加载 |
| 编译 | JDK 17 下 `-source 8 -target 8` 无警告失败 |
| 入口 | 组件入口 `public`、无参构造、类名与后台配置一致 |
| 体积 | 组件 DEX 不超过 4 MB，方法数不超过 65,536 |
| ABI | 组件不依赖壳加载 `.so`；如需 native，自行释放并加载 |
| 多应用 | 每个包独立初始化，不共享 `filesDir` |
| 旧壳 | 组件 `min_shell` 不大于目标壳的 `SHELL_VERSION` |
