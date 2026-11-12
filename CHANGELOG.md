# 更新日志 / Changelog

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
