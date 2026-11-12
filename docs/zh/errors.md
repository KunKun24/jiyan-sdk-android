# 失败类型与排查

壳的所有失败都会落到一张错误卡片、一条通知，或一次 `component_report`。本文按用户能看到的文案分类，给出触发点与排查顺序；文案取自 `ComponentManager`、`ShellUi`、`Http` 的当前源码。

## 失败类型总览

| 类型 | 触发点 | 用户可见文案 |
| --- | --- | --- |
| 下载失败 | 网络、票据、磁盘或 HTTP 状态异常 | “组件下载失败”“下载失败 HTTP `<code>`” |
| 校验失败 | 大小或 SHA-256 与清单不符 | “SHA-256 校验失败”“大小不符” |
| 签名失败 | ECDSA 验签不通过 | 当前只写 logcat，不弹卡片 |
| 清单字段不完整 | `version`、`sha256`、`size` 非法 | “清单字段不完整” |
| 安装失败 | 目录创建、删除旧包、改名失败 | “组件目录创建失败”“旧组件删除失败”“组件落盘失败” |
| 初始化失败 | 入口类加载或 `init` 抛异常 | “组件初始化异常”“组件启动失败” |
| 回退 | 新组件失败后切回 previous 仍不可用 | “已尝试回退仍不可用。” |
| 被禁用版本 | 同一版本累计失败 3 次 | 无独立文案，表现为反复失败 |

## 下载失败

常见原因：设备无网络、下载票据过期（约 15 分钟）、URL 被 CDN 拒绝、`Range` 续传拿到不一致的长度、磁盘空间不足。

排查顺序：

1. 看 logcat 的 `Http` 输出，确认是连接超时、HTTP 4xx/5xx，还是“下载不完整 `<done>/<expect>`”。
2. 确认系统时间正确；票据过期后重新触发一次更新即可换新票据。
3. 检查 `filesDir` 剩余空间，删除残留的 `component.dex.part` 再试。
4. 若每次都停在同一个百分比，重点查 CDN 是否支持 `Range` 且返回稳定长度。
5. 用户点“取消”会得到“已取消更新”，这不是故障，重新下载即可。

## 校验失败

`size` 不符抛“大小不符”；SHA-256 不符抛“SHA-256 校验失败”，并把这次失败记为 `file_corrupt`。

常见原因：CDN 返回了旧文件、传输被截断、后台清单里的 `sha256` 与实际上传文件不一致、发布时改过 DEX 但没更新清单。

排查：对 `.part` 或 `classes.dex` 执行 `sha256sum`，与 manifest 的 `sha256` 逐字符比较；再核对 `size`。如果本地一致而设备不一致，检查是否有中间层改写了响应体。校验失败的文件不会被激活。

## 签名失败

当前源码在 SHA-256 匹配后调用 `verify()`；验签失败只写一条 warning：“组件文件签名未通过，但 SHA-256 已匹配，继续安装”，不会阻断安装。因此用户可能看不到任何提示，但安全性已经下降。

常见原因：后台用了错误的私钥、`signature` 不是 Base64、公钥与私钥不配对、签名针对的是未压缩前的另一份文件。

排查：用 `openssl dgst -sha256 -verify` 对同一份 DEX 复核；确认 `ShellConfig.COMPONENT_PUBKEY` 是 X.509 DER 的 Base64。不要通过关闭验签来“解决”问题。

## 清单字段不完整

安装前会检查 `version > 0`、`sha256.length() == 64`、`size > 0`，任一不满足就抛“清单字段不完整”。

常见原因：后台把 `sha256` 写成短 hash、`size` 写成 0 或字符串、`version` 为 0、字段名拼错。排查时直接打印 `payload.active`，确认类型是数字/字符串且没有空值；不要依赖 `label` 或 `content`，它们缺失不影响安装。

## 安装失败

三种落盘错误：创建 `components/vN` 失败（“组件目录创建失败”）、删除同版本旧 `classes.dex` 失败（“旧组件删除失败”）、改名失败（“组件落盘失败”）。

常见原因：存储只读、剩余空间不足、文件被其它进程占用、路径不是目录。

排查：确认 `filesDir/JiYan_Cloud/shell/components` 可写；删除损坏的版本目录后重试；不要手动改 `state.json`，它的写入是 tmp + rename 的原子流程，手改容易让状态与磁盘不一致。

## 初始化失败

`load()` 捕获 `Throwable` 后上报 `init_failed`，卡片标题是“组件启动失败”，内容含“已尝试回退仍不可用。”与异常原因；`run` 外层还会显示“组件初始化异常”。

常见原因：入口类名与状态里的 `entry` 不符、类不是 `public`、没有无参构造、`init` 内抛异常、组件引用了壳里不存在的类、组件 DEX 缺少依赖。

排查：先用 `sample-component` 替换验证链路；确认入口类在 DEX 中；看 logcat 的完整堆栈。`init` 当前在主线程调用，阻塞操作会直接触发 ANR，耗时任务必须自行开线程。

## 回退

新组件第一次失败时，如果 `previous` 存在、版本不同、`previous.min_shell <= SHELL_VERSION` 且旧 DEX 还在，壳会立即把 active 切回 `previous` 并重新加载；上报 detail 形如 `...; fallback=<version>`。

回退只尝试一次。回退后仍失败会显示“已尝试回退仍不可用。\n<原因>\n\n将重新下载组件库。”，点重试会重新走 `bootstrap()`。若 previous 的 DEX 已被清理或 `min_shell` 过高，回退条件不成立，直接进入重新下载。

## 被禁用版本

`fail()` 每次失败把 `state.fail` 加一；累计到 3 次时把当前版本写入 `state.bad`。设计意图是“重复失败的版本不再自动安装”，但 `check()` 里的 `badVersion` 判断位于 `if (!contentChanged) return;` 之后，实际是死代码，`badVersion` 不会阻止检查。

状态文件形状如下，只用于排查，不要手改：

```json
{
  "active": {"v": 12, "min_shell": 1, "sha256": "<64_HEX>", "size": 524288, "entry": "com.jiyan.cloud.comp.ComponentImpl"},
  "previous": null,
  "fail": 0
}
```

如果同一版本反复失败：先修组件并发布更高的 `version`，不要只改 `sha256`；必要时让用户清除应用数据（会同时清掉配置，需要重新初始化），或卸载重装。上报里没有专门的“被禁用”状态。

## 其它启动提示

| 文案 | 原因 | 处理 |
| --- | --- | --- |
| “配置解析失败” | `data` / `iv` 解密失败或格式不符 | 确认后台生成的配置与当前壳版本匹配 |
| “没有可用应用” | 配置里没有合法 `app_id` | 检查 `apps[]` 的 `app_id` 是否匹配 `[1-9][0-9]*` |
| “需要先更新接入包” | 组件 `min_shell` 大于 `SHELL_VERSION` | 发新宿主包，`min_shell` 当前不能大于 1 |
| “暂无可用组件” | 清单没有 `active` | 后台先发布一个组件版本 |
| “组件库未就绪” | 首次安装点了“稍后” | 联网后重启应用，重新触发下载 |

## report 状态

| `status` | 何时发送 | detail 示例 |
| --- | --- | --- |
| `ok` | 加载成功或热切换成功 | `v12`、`hot_swap=v13` |
| `init_failed` | 初始化失败、回退、热切换失败 | `IllegalStateException: ...; fallback=12` |
| `download_failed` | 首次安装取清单失败 | `IOException: ...` |
| `install_failed` | 源码预留，当前没有发送点 | 无 |
| `crash` | 源码预留，当前没有发送点 | 无 |

report 在独立线程发送，失败只写日志。`detail` 会压成单行并截断到 180 字符；排查时不要依赖完整堆栈，完整信息看 logcat。

## 日志

`ShellLog` 设计为写 `Download/JiYan_shell.log` 并按 1 MB 轮转，但全仓库没有调用 `ShellLog.init()`，`sFile` 始终为 `null`，`path()` 返回空字符串，文件日志当前不会落盘，只有 logcat 可见。

调试时用 `adb logcat` 过滤调用处传入的 tag（如 `Comp`、`Http`、`State`、`Ui`，具体以源码为准）。若需要文件日志，应先补上 `ShellLog.init(context)` 的调用，再依赖日志路径。
