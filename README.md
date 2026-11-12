# 极验云 Android DEX SDK（壳 + 组件）

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/KunKun24/jiyan-sdk-android?display_name=tag)](https://github.com/KunKun24/jiyan-sdk-android/releases)
![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84)
![Java](https://img.shields.io/badge/Java-8-orange)

**中文** · [English](README.en.md)

极验云（JiYan Cloud）Android 的**壳 + 组件**双层结构：壳负责组件下载、校验、热切换与回退；
业务与 UI 放在 `component.dex` 里，由壳用 `DexClassLoader` 加载。
接入方仍然只交付**一个 DEX**，业务可以独立热更新。

> 服务端、后台、卡密业务、注入模块均不开源。本仓库只包含壳、协议与组件接口。

---

## 开源范围

| 内容 | 状态 |
| --- | --- |
| 壳（`ComponentManager` / `ShellUi` / `Http` / `ComponentState` / `Signer`） | ✅ MIT |
| 组件接口 `com.jiyan.cloud.spi.Component` | ✅ |
| 组件协议（manifest / url / report、ECDSA 校验；HMAC 请求签名已预留） | ✅ |
| 后台发布页、数据库、服务端 | ❌ 闭源 |
| 业务组件（卡密、云控、注入等） | ❌ 不在本仓库 |

## 工作方式

```
merge → 用户启动 → 壳读配置 → 取 manifest
                                 ├─ 无更新 → 直接加载本地组件
                                 └─ 有更新 → 下载 → SHA-256 + ECDSA 校验
                                              → 原子切换 components/vN → DexClassLoader 加载
                                              → 失败回退上一版（最多 3 次）
```

- 组件包由后台用 ECDSA P-256 私钥签名，壳内置公钥校验；**签名缺失或校验失败一律拒绝安装**（v1.0.1 起），公钥不是秘密。
- 下载地址是一次性票据（绑定设备、15 分钟过期），支持 Range 断点续传。
- `state.json` 原子写入（tmp + rename），记录 active / previous / badVersion。
- 首次安装直接加载组件；后续更新在下次启动或热切换时生效，热切换失败会提示重启。

## 目录

```
app/src/shell/java/com/jiyan/cloud/
  Cloud.java             门面：set_data/set_iv/init/allowRetry/notice
  MainActivity.java      壳的演示宿主
  spi/Component.java     组件接口
  shell/                 ComponentManager、ShellUi、Http、ComponentState、Signer…
tests/                   纯 JVM 自测（HMAC/canonical）
sample-component/        最小组件入口示例
scripts/build-check.sh   编译 + 自测
```

## 编译与自测

要求 JDK 17（编译目标 Java 8）。脚本会自动下载 `android-all` 与 `org.json`。

```bash
bash scripts/build-check.sh
```

输出：

```
[PASS] HMAC-SHA256 RFC 4231 vector
[PASS] canonical has 11 fields
[PASS] canonical field order
[PASS] hex length 64
```

## 接入

1. 把 `app/src/shell/java` 拷进你的工程，作为**壳**的源码；
2. 业务代码放在另一个 source set / 另一个 module，编译成 `component.dex`；
3. 组件入口实现 `com.jiyan.cloud.spi.Component`（见 `sample-component/`）；
4. 壳与组件分别用 `d8` 编译，组件用后台私钥签名后上传；
5. 合并成最终 DEX 时，壳与组件不要重复打包同一批业务类。

> 注意：壳一旦发布就无法自更新；升级壳必须重新合并 DEX 并发布新包。
> 组件可以随时热更，失败会自动回退。

## 组件接口

```java
public interface Component {
    void init(Context ctx, String data, String iv, int shellVersion) throws Throwable;
    void shutdown();
}
```

`data` / `iv` 是后台下发的加密配置；`shellVersion` 是壳的构建号，用于兼容判断。
`init` 抛异常会被壳捕获并计入失败次数，连续失败会禁用该版本并回退。

## 安全

- 组件必须验签，不要在壳里关掉 `verify()`；公钥可以换，但私钥只在后台。
- 不要把自己的业务密钥写进组件明文字符串，至少做一层运行时派生。
- 设备码必须稳定且非空；票据与设备绑定，换设备需重新取 manifest。
- 漏洞请勿公开提 issue，见 [SECURITY.md](SECURITY.md)。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| Android | 5.0（API 21）及以上 |
| Java | 8（编译目标），JDK 17 编译 |
| ABI | 不限（壳是纯 Java，SO 由业务自己带） |
| 组件大小 | 建议 ≤ 4 MB（服务端默认上限） |

## 文档

- [快速开始](docs/zh/quickstart.md) · [Quick start](docs/en/quickstart.md)
- [组件协议](docs/zh/protocol.md) · [Protocol](docs/en/protocol.md)
- [签名与校验](docs/zh/signing.md) · [Signing](docs/en/signing.md)
- [错误与排查](docs/zh/errors.md) · [Errors](docs/en/errors.md)
- [兼容性](docs/zh/compat.md) · [Compatibility](docs/en/compat.md)

## 官方渠道

- 官网：<https://jiyan.xn--0try77c6um82w.cn>
- 接口文档：<https://jiyan.xn--0try77c6um82w.cn/docs.html>
- 控制台：<https://jiyan.xn--0try77c6um82w.cn/前台/开发者登录.php>
- 社区：<http://shequ.xn--0try77c6um82w.cn>
- 开发者主页：<http://www.xn--0try77c6um82w.cn>
- Telegram：[@sad1881](https://t.me/sad1881) · 频道 [@JiYanWangLuo](https://t.me/JiYanWangLuo)
- QQ 群：1106600707

> 极验云与 Geetest（极验）无关。

## 许可证

[MIT](LICENSE) © 2026 JiYan Cloud（极验云）· 和泉纱雾

> 项目包含 AIGC 内容；AI 减少了开发时间，每条改动都经过人工审核。
