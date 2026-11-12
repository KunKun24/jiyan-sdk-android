# 更新日志 / Changelog

## 1.0.1 — 2026-11-13

### 安全

- **修复**：组件签名缺失或 ECDSA 校验失败时，旧实现只记一条警告并继续安装（SHA-256 通过即放行）。
  现在一律抛出异常拒绝安装，与 README / SECURITY 的“必须验签”一致。
- `ComponentManager.start()` 现调用 `ShellLog.init()`，文件日志与错误卡里的日志路径真正可用。

### 文档

- 明确 HMAC 请求签名属于协议预留，当前壳版本不发送 `sig`；组件真实性由 ECDSA 保证。
- 首次安装为直接加载组件；重启提示只在热切换失败时出现。

## 1.0.0 — 2026-11-13

首个开源版本（对应壳 build 26）。

### 包含

- `ComponentManager`：manifest 拉取、下载（Range 续传）、SHA-256 + ECDSA 校验、
  原子安装（`components/vN`）、`DexClassLoader` 加载、热切换、失败回退（最多 3 次）
- `ComponentState`：`state.json` 原子写，记录 active / previous / badVersion，30 天清理
- `ShellUi`：玻璃卡片、下载进度、通知栏、重启提示、错误卡片；不依赖布局 XML
- `Http`：组件协议 POST（payload + HMAC）、票据下载
- `Signer`：HMAC-SHA256、11 段 canonical、18 字节 nonce
- `Notify`、`ShellLog`、`ShellApps`
- `spi/Component` 组件接口
- 纯 JVM 自测与 CI
