# JiYan Cloud Android DEX SDK (shell + component)

[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Release](https://img.shields.io/github/v/release/KunKun24/jiyan-sdk-android?display_name=tag)](https://github.com/KunKun24/jiyan-sdk-android/releases)
![Android](https://img.shields.io/badge/Android-5.0%2B-3DDC84)

[中文](README.md) · **English**

A two-layer runtime for JiYan Cloud on Android: a small **shell** handles component
download, verification, hot swap and rollback, while business code and UI live in
`component.dex` and are loaded with `DexClassLoader`. You still ship **one DEX**.

> Server, admin panel, card business and injection modules are closed source.
> This repository contains the shell, protocol and component interface only.

---

## What is open

| Component | Status |
| --- | --- |
| Shell (`ComponentManager`, `ShellUi`, `Http`, `ComponentState`, `Signer`) | ✅ MIT |
| `com.jiyan.cloud.spi.Component` | ✅ |
| Component protocol (manifest / url / report, ECDSA; HMAC request signing reserved) | ✅ |
| Backend release page, database, server | ❌ |
| Business components (cards, cloud control, injection) | ❌ |

## How it works

```
merge → launch → read config → fetch manifest
                                ├─ up to date → load local component
                                └─ update → download → SHA-256 + ECDSA verify
                                            → atomically switch components/vN
                                            → DexClassLoader → fallback on failure (max 3)
```

- Components are signed with an ECDSA P-256 private key held by the backend; missing or invalid signatures are rejected (v1.0.1+);
  the shell embeds only the public key.
- Download URLs are one-time tickets bound to a device, valid for 15 minutes,
  with HTTP Range resume.
- `state.json` is written atomically; it stores active / previous / badVersion.
- The first install loads the component directly; later updates hot-swap, with a restart prompt only on failure.

## Layout

```
app/src/shell/java/com/jiyan/cloud/   shell sources + spi/Component
tests/                                pure-JVM tests (HMAC / canonical)
sample-component/                     minimal component entry point
scripts/build-check.sh                compile + test
```

## Build & test

JDK 17 (target Java 8). The script downloads `android-all` and `org.json`.

```bash
bash scripts/build-check.sh
```

## Integration

1. Copy `app/src/shell/java` into your app as the shell.
2. Put business code in another source set and compile it to `component.dex`.
3. Implement `com.jiyan.cloud.spi.Component` (see `sample-component/`).
4. Sign the component with the backend key, upload it, publish a version.
5. Do not bundle the same business classes in both shell and component.

> The shell cannot update itself; shipping a new shell requires re-merging and
> republishing the host DEX. Components can update at any time.

## Component interface

```java
public interface Component {
    void init(Context ctx, String data, String iv, int shellVersion) throws Throwable;
    void shutdown();
}
```

`init` failures are counted; repeated failures disable the version and fall back.

## Security

- Keep ECDSA verification enabled; the public key is not a secret, the private key never ships.
- Do not hardcode business secrets as plaintext; derive them at runtime.
- Device IDs must be stable and non-empty.
- Report vulnerabilities privately — see [SECURITY.md](SECURITY.md).

## Compatibility

Android 5.0 (API 21)+, Java 8 target, JDK 17 build, component size ≤ 4 MB by default.

## Docs

[Quick start](docs/en/quickstart.md) · [Protocol](docs/en/protocol.md) ·
[Signing](docs/en/signing.md) · [Errors](docs/en/errors.md) · [Compatibility](docs/en/compat.md)

## Official channels

- Website: <https://jiyan.xn--0try77c6um82w.cn>
- Docs: <https://jiyan.xn--0try77c6um82w.cn/en/docs.html>
- Community: <http://shequ.xn--0try77c6um82w.cn>
- Telegram: [@sad1881](https://t.me/sad1881)
- QQ group: 1106600707

> Not affiliated with Geetest.

## License

[MIT](LICENSE) © 2026 JiYan Cloud · 和泉纱雾
