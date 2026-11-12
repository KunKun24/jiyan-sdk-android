# 组件协议

本文只描述客户端可见的协议行为，不涉及服务端实现。字段来自 `ComponentManager`、`Http`、`ShellConfig`、`ShellApps` 与 `Signer` 的当前源码；字符串值一律使用占位符。

## 传输与信封

壳把 JSON `POST` 到 `ShellConfig.API_BASE` 下的组件接口，`Content-Type` 为 `application/json; charset=utf-8`，`Accept-Encoding` 固定 `identity`，`User-Agent` 为 `JiYan_CloudShell/<SHELL_BUILD>`。

| 行为 | 源码事实 |
| --- | --- |
| 响应体 | `{ok, payload, sig}`，`payload` 是 JSON 字符串，不是嵌套对象 |
| 成功判定 | HTTP 2xx、`ok == true`、`payload` 非空，三者同时满足 |
| 失败判定 | 非 2xx 抛 `HTTP <code>`；`ok != true` 抛 `msg`；空 `payload` 抛“响应内容为空”；JSON 格式错误抛“响应格式错误” |

```json
{"ok":true,"payload":"{\"active\":{\"version\":12,\"min_shell\":1,\"sha256\":\"<64_HEX>\",\"size\":524288,\"label\":\"v1.2\",\"content\":\"...\"}}","sig":"<BASE64>"}
```

```json
{"ok":false,"msg":"<错误信息>"}
```

`Http.post` 当前只读取 `ok`、`msg`、`payload`，不校验响应 `sig`；方法注释写着“校验响应签名”，但代码里没有对应分支。以源码为准。

客户端只发送三个 action：`component_manifest`（`version=0`）取当前可用清单，`component_url`（目标版本）取一次性下载路径与包签名，`component_report`（active 版本）上报加载、下载、初始化结果。

## 请求字段

| 字段 | 来源与说明 |
| --- | --- |
| `action` | 上表三个值之一 |
| `developer_username` | 解密后的配置 `uid` |
| `app_id` | 配置 `apps[]` 中第一个合法应用的 `app_id`，必须匹配 `[1-9][0-9]*` |
| `device_id` | `ANDROID_ID`，为空退回 `Build.SERIAL`，再为空用 `"unknown"` |
| `shell_version` | `ShellConfig.SHELL_VERSION`（当前为 `1`），不是 `SHELL_BUILD` |
| `current_version` | 本地 active 版本；没有则为 `"0"` |
| `timestamp` | 秒级 Unix 时间字符串 |
| `nonce` | 18 字节随机数的 base64url（无填充，24 字符） |
| `version` | 见上表；manifest 为 `0`，report 为 active 版本 |
| `status` / `detail` | 普通请求均为空字符串；report 填结果与单行说明，`detail` 最长 180 字符 |
| `sig` | HMAC-SHA256 的小写十六进制，见[签名与校验](signing.md) |

```json
{
  "action": "component_manifest",
  "developer_username": "<USER>",
  "app_id": "<APP_ID>",
  "device_id": "<DEVICE_ID>",
  "shell_version": "1",
  "current_version": "0",
  "timestamp": "<UNIX_SECONDS>",
  "nonce": "<24_CHAR_BASE64URL>",
  "version": "0",
  "status": "",
  "detail": "",
  "sig": "<64_HEX>"
}
```

当前开源壳的 `call()` 只组装前 11 个字段并直接 `Http.post`，没有把 `sig` 放进请求体，`Signer` 也只在自测中被调用。若后台强制校验 `sig`，需按签名文档补齐，最终以服务端约定为准。

## manifest 响应

壳只读取 `payload.active`；`active` 为 `null` 或响应失败时视为“暂无组件”，不阻塞进入组件加载。

```json
{
  "active": {
    "version": 12, "min_shell": 1, "sha256": "<64_HEX>",
    "size": 524288, "label": "v1.2", "content": "更新说明"
  }
}
```

| 字段 | 类型 | 壳的用法 |
| --- | --- | --- |
| `version` | int | 安装目录 `components/vN`；必须大于 0 |
| `min_shell` | int | 与 `ShellConfig.SHELL_VERSION` 比较，大于则拒绝 |
| `sha256` | string | 必须 64 位；与本地 active 比较决定是否更新 |
| `size` | long | 下载期望字节数，必须大于 0 |
| `content` | string | 卡片说明，显示前截断到 4000 字符 |
| `label` | string | 展示名；缺省时用 `v<version>` |
| `rollback` / `paused` | - | 当前源码未读取 |

更新判定只比较 `sha256`：`version` 变大但 `sha256` 相同不会触发更新。安装前会校验 `version > 0`、`sha256` 长度 64、`size > 0`，否则抛“清单字段不完整”。

## component_url 响应

```json
{"path": "<RELATIVE_DOWNLOAD_PATH>", "signature": "<BASE64_ECDSA_SIGNATURE>"}
```

| 字段 | 壳的用法 |
| --- | --- |
| `path` | 与 `API_BASE` 直接拼接；为空抛“下载地址缺失” |
| `signature` | 组件 DEX 的 ECDSA P-256 签名；为空则跳过验签 |

下载支持 HTTP Range：`.part` 已存在时带 `Range: bytes=<已下载>-`，`206` 追加、`200` 从头覆盖；完成后校验大小与 SHA-256，再改名为 `classes.dex`。下载票据绑定设备、15 分钟过期，这是服务端约定；客户端不解析票据内容。

## component_report

report 在独立线程发送，失败只写日志，不影响启动。`current_version` 与 `version` 都取 active 版本，`detail` 会把换行压成空格并截断到 180 字符。

| `status` | 含义 | 当前源码是否发送 |
| --- | --- | --- |
| `ok` | 加载成功或热切换成功 | 是 |
| `init_failed` | 组件初始化失败、回退、热切换失败 | 是 |
| `download_failed` | 首次安装取清单失败 | 是 |
| `install_failed` / `crash` | 安装失败 / 组件崩溃 | 否，源码中没有发送点 |

## 重试与超时

| 环节 | 行为 |
| --- | --- |
| 启动清单 / 常规清单 | 启动只请求一次（4 秒 / 6 秒）；常规最多 2 次、间隔 800 毫秒 |
| 下载 | 连接 15 秒、读取 30 秒；每 200 毫秒回调一次进度 |
| JSON 响应 | 最多读取 256 KB，超出部分丢弃 |
| 更新检查 | 组件加载成功后延迟 2500 毫秒再查一次 |
