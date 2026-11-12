# Quick Start

This guide wires the JiYan Cloud Android shell into a host app and gets the first component running. All statements are based on the current sources under `app/src/shell/java`; the server, admin console, and business components live elsewhere.

## Requirements

| Item | Requirement |
| --- | --- |
| Android | 5.0 (API 21) or newer |
| Java source level | Java 8 (the shell only uses Java 7/8 language features and Android APIs) |
| Build JDK | JDK 17 (`scripts/build-check.sh` passes `-source 8 -target 8`) |
| Toolchain | `javac` + `d8`, or an equivalent custom build step |
| Runtime deps | No AndroidX or third-party libraries; `org.json` comes from Android |
| Component size | 4 MB or less is recommended (server-side default) |
| Component entry | Fixed to `com.jiyan.cloud.comp.ComponentImpl` |

## Integration Steps

1. Copy `app/src/shell/java` into the host app and keep the `com.jiyan.cloud` and `com.jiyan.cloud.shell` package names unchanged.
2. Keep the public method signatures of `Cloud.java` unchanged. Pass the server-generated `data` / `iv` at startup and call `init`.
3. Put business code in a separate source set or module and compile it into `component.dex`. Do not ship the same classes in both the shell and the component.
4. Implement `com.jiyan.cloud.spi.Component`. The class is fixed to `com.jiyan.cloud.comp.ComponentImpl`; it must be `public` and have a no-argument constructor.
5. Sign the component with the server-side private key, upload it, and publish a version. The shell downloads it from the URL returned by `component_url`.
6. Merge the final host DEX. The host still ships one DEX; the component is loaded at runtime by `DexClassLoader` from `components/vN/classes.dex`.

```java
// Call at host startup; data / iv are the encrypted config from the backend
Cloud.set_data(data);
Cloud.set_iv(iv);
Cloud.init(context);
```

`ComponentManager.start` uses a static singleton and `synchronized`, so repeated calls do not initialize twice.

## Component Entry Point

```java
package com.jiyan.cloud.comp;

import android.content.Context;
import com.jiyan.cloud.spi.Component;

public final class ComponentImpl implements Component {
    @Override
    public void init(Context ctx, String data, String iv, int shellVersion) throws Throwable {
        // Decrypt data / iv and set up UI and business logic.
        // Run blocking work on your own thread.
    }

    @Override
    public void shutdown() {
        // Release threads, timers, and overlays. Called before a hot swap.
    }
}
```

| Rule | Detail |
| --- | --- |
| Class name | Fixed to `ShellConfig.COMPONENT_ENTRY` (`com.jiyan.cloud.comp.ComponentImpl`); the `entry` stored in state is only used for loading, so changing it requires a shell change and a new host build |
| Constructor | Must be `public` and no-argument; the shell calls `newInstance()` |
| API surface | Depend only on `com.jiyan.cloud.spi.Component`, never on shell internals |
| Threading | The interface comment says "called on a background thread", but the current implementation calls `init` on the main thread; do not block |
| Failures | A `Throwable` from `init` is caught, counted, and may trigger a fallback |

## First Install and Restart

At startup the shell fetches the manifest once with short timeouts. If a version is available, it shows a confirmation card; the download starts only after the user taps "Download now". Installation then follows one of three paths:

| Scenario | Source behavior |
| --- | --- |
| Installed during startup, before any component was loaded in this process | `bootLoad = true`; the shell calls `load()` directly and shows "active" toast, no restart prompt |
| Component already loaded, hot swap succeeds | The old component gets `shutdown()`, a new `DexClassLoader` loads the new component, no restart |
| Hot swap fails, or installation runs outside the startup path | Shows "component library ready" and asks for a restart; `pending_restart` is written only in the non-boot branch, which is unreachable today because `bootLoad` is always true |

A restart lets a first install take over the UI from the process entry point. After a failed hot swap, classes from the previous component are already loaded by the old `DexClassLoader` and cannot be unloaded in-process. Native libraries that the component wants to download are also expected to be handled by the new component after restart; the shell does not continue downloading cloud-update files in the same launch. The marker format is `<pid>|<component version>|<epoch millis>` and is consumed by the component at its entry point.

> The README says the first install asks for one restart, but the current startup prefetch path loads the component directly without prompting. Both branches exist in the source; the actual code path is authoritative.

## Directory Layout

| Path | Contents |
| --- | --- |
| `filesDir/JiYan_Cloud/shell/state.json` | active / previous / fail / bad, written atomically |
| `filesDir/JiYan_Cloud/shell/components/vN/classes.dex` | Installed component |
| `filesDir/JiYan_Cloud/shell/components/vN/meta.json` | Version, `min_shell`, SHA-256, size, signature record |
| `filesDir/JiYan_Cloud/shell/components/vN/component.dex.part` | Partial download, resumed with Range |
| `filesDir/JiYan_Cloud/shell/odex/` | `DexClassLoader` optimization output |
| `filesDir/JiYan_Cloud/shell/pending_restart` | Restart marker |
| `Download/JiYan_shell.log` | Log; falls back to `filesDir/logs`, rotates at 1 MB |

Version directories other than active and previous are deleted after 30 days of disuse. `meta.json` is write-only in the current source; state is restored from `state.json`, not from `meta.json`.

## The Shell Cannot Self-Update

The shell is compiled into the host DEX. Upgrading it requires merging a new DEX, re-signing, and shipping a new host build; only components can be updated at runtime. `min_shell` blocks old shells from loading new components: when a component's `min_shell` is greater than `ShellConfig.SHELL_VERSION`, the shell asks the user to update the integration package.

## Minimal Runnable Example

`sample-component/java/com/jiyan/cloud/comp/ComponentImpl.java` is a compilable minimal implementation. Compile the shell and the component separately:

```bash
javac -source 8 -target 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR:$JSON_JAR" -d shell-classes \
  $(find app/src/shell/java -name '*.java')

javac -source 8 -target 8 -encoding UTF-8 \
  -cp "$ANDROID_JAR" -d component-classes \
  $(find sample-component/java -name '*.java')
d8 --lib "$ANDROID_JAR" --output component-out component-classes
```

The exact `d8` inputs and packaging depend on your build tool; the key requirement is that the entry class is inside the component DEX. Do not package the same business classes into both the shell and the component, or `DexClassLoader` may hit duplicate-class conflicts.

## Self-Test

```bash
bash scripts/build-check.sh
```

The first run downloads `android-all` and `org.json`, compiles the shell and tests, and prints four `[PASS]` lines covering the HMAC RFC 4231 vector, the 11 canonical fields, their order, and the 64-character hex length. The tests need no network or device. Continue with the [component protocol](protocol.md) and [signing and verification](signing.md).
