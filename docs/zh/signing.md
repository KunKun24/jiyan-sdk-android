# 签名与校验

组件协议有两层密码学保护：请求用 HMAC-SHA256 证明调用方持有应用密钥，组件包用 ECDSA P-256 证明二进制确实由后台私钥签发。两层都只依赖客户端可见字段与内置公钥。

## 签名密钥

| 项目 | 说明 |
| --- | --- |
| 首选字段 | 应用配置里的 `sign_key` |
| 回退字段 | `sign_key` 为空时用 `encryption_key`（解析后映射为 `App.apiKey`） |
| 来源 | 后台下发的加密配置，经 `ShellApps` 用 AES/CBC 解密后取得 |
| 算法 | `HmacSHA256`，key 与 data 均按 UTF-8 取字节 |
| 输出 | 小写十六进制，固定 64 字符 |

`App.secret()` 的顺序是“`sign_key` 非空优先，否则 `encryption_key`”。如果后台约定使用 `api_key` 而不是 `encryption_key`，以后台配置为准；客户端不要硬编码任何密钥。

## canonical 字符串

canonical 是 11 个字段按固定顺序用单个 `\n` 连接的结果，末尾没有换行。字段顺序来自 `Signer.canonical`：

| # | 参数 | 请求字段 | 说明 |
| --- | --- | --- | --- |
| 1 | `action` | `action` | 三个 action 之一 |
| 2 | `user` | `developer_username` | 配置 `uid` |
| 3 | `appId` | `app_id` | 第一个应用的 ID |
| 4 | `device` | `device_id` | 设备标识 |
| 5 | `shellVer` | `shell_version` | 壳版本，当前为 `1` |
| 6 | `curVer` | `current_version` | active 版本或 `0` |
| 7 | `ts` | `timestamp` | 秒级时间戳 |
| 8 | `nonce` | `nonce` | 24 字符 base64url |
| 9 | `version` | `version` | 目标版本或 `0` |
| 10 | `status` | `status` | 普通请求为空 |
| 11 | `detail` | `detail` | 普通请求为空 |

对应字符串是 `component_report\n<USER>\n<APP_ID>\n<DEVICE_ID>\n1\n12\n<UNIX_SECONDS>\n<NONCE>\n12\nok\n<DETAIL>`。空字段也要保留分隔符；`status`、`detail` 为空时 canonical 仍以两个 `\n` 结尾，不能再追加换行。

```java
String canonical = action + "\n" + user + "\n" + appId + "\n" + device
        + "\n" + shellVer + "\n" + curVer + "\n" + ts + "\n" + nonce
        + "\n" + version + "\n" + status + "\n" + detail;
```

`detail` 在 `component_report` 里会先压成单行并截断到 180 字符；如果给 report 补签名，签名用的必须是这个归一化后的值，否则服务端重算 canonical 会对不上。

## HMAC 计算

| 检查项 | 期望 |
| --- | --- |
| 编码 | UTF-8，不做 URL 编码 |
| 大小写 | 小写 hex；大写会被服务端视为不同值 |
| 长度 | 64 字符；不足说明算法或编码用错 |
| 失败 | 壳内抛 `RuntimeException("HMAC 计算失败")` |

## nonce 与时间戳

| 项目 | 事实 |
| --- | --- |
| nonce 长度 | 18 字节随机数 |
| 编码 | `Base64.NO_WRAP \| URL_SAFE \| NO_PADDING`，24 字符、无填充 |
| 生成器 | `SecureRandom`，每次请求重新生成 |
| 时间戳 | 毫秒除以 1000 后的十进制字符串 |

nonce 必须一次性使用；同一 nonce 重放会被服务端拒绝（窗口策略以服务端为准）。客户端不校验时间戳窗口，只保证使用当前时间；设备时钟偏差过大会导致请求被拒，必要时提示用户校准时间。

## 组件包签名验证

流程如下：

1. 从 manifest 取 `sha256` 与 `size`，从 `component_url` 取 `path` 与 `signature`。
2. 下载到 `component.dex.part`，先校验文件长度等于 `size`。
3. 计算 SHA-256，与 manifest 的 `sha256` 比较。
4. 用内置公钥验证 `signature` 对同一份文件字节的 ECDSA 签名。
5. 两步都通过才改名为 `classes.dex` 并切换 active。

```java
PublicKey key = KeyFactory.getInstance("EC").generatePublic(
        new X509EncodedKeySpec(Base64.decode(COMPONENT_PUBKEY, Base64.DEFAULT)));
Signature v = Signature.getInstance("SHA256withECDSA");
v.initVerify(key);
// 源码用 64 KB 缓冲循环调用 v.update(buf, 0, n)
v.update(dexBytes);
boolean ok = v.verify(Base64.decode(signatureB64, Base64.DEFAULT));
```

| 项目 | 说明 |
| --- | --- |
| 曲线 | P-256（secp256r1） |
| 公钥格式 | X.509 `SubjectPublicKeyInfo` DER 的 Base64，内置在 `ShellConfig.COMPONENT_PUBKEY` |
| 签名格式 | ECDSA DER，Base64 文本随 `component_url` 下发 |
| 摘要 | SHA-256，`SHA256withECDSA` 内部完成 |
| 失败 | 任何异常都返回 `false`，不抛给调用方 |

## 不要关闭验签

SHA-256 只能证明文件没有损坏，不能证明来源。能改 manifest 的攻击者通常也能替换下载文件，只比 SHA-256 等于把信任交给同一个通道；ECDSA 私钥只在后台，攻击者无法伪造新组件的签名。

组件运行在宿主进程内，拥有宿主的权限。关掉验签等于允许任何能劫持下载链路的人在你的应用里执行任意代码，后果比一次更新失败严重得多。

> 当前源码在签名校验失败时只写一条 warning（“组件文件签名未通过，但 SHA-256 已匹配，继续安装”），并不阻断安装。这与 README 和 SECURITY 的要求不一致；发布前应按“验签失败即安装失败”处理，以源码为准。

## 常见错误

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| HMAC 对不上 | 字段顺序、空字段、编码或 hex 大小写不一致 | 用同一组占位值对照 canonical 字符串 |
| 签名总是 `false` | 公钥不是 X.509 DER，或签名不是 Base64 | 用 `openssl pkey -pubin -inform DER` 检查公钥 |
| 组件被拒 | 私钥与内置公钥不配对 | 确认发布环境使用的密钥对 |
| 时间戳被拒 | 设备时钟偏差 | 校准时间后重试，不要放宽窗口 |

换密钥后旧壳会失效，因为公钥编译在壳里且壳不能自更新；只能重发宿主包，不能只换后台密钥。

可以用 OpenSSL 独立复核签名：

```bash
openssl dgst -sha256 -verify component_pub.pem \
  -signature signature.bin component.dex
```

输出 `Verified OK` 才说明公钥、文件和签名三者匹配。
