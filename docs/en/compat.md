# Compatibility

This document covers the compatibility boundaries of the shell and components across Android versions, Java, size, ABI, multiple apps, and legacy DEX versions. The repository does not retain complete 1.2/1.3 implementations; claims about legacy behavior rely only on surviving comments and the CHANGELOG, and the source is authoritative elsewhere.

## System and Toolchain

| Item | Conclusion | Basis |
| --- | --- | --- |
| Minimum OS | Android 5.0 (API 21) | README statement |
| Build JDK | JDK 17 | `scripts/build-check.sh` |
| Source/bytecode target | Java 8 (`-source 8 -target 8`) | Build script |
| Runtime deps | No AndroidX or third-party libraries | Shell uses only Android and `org.json` |
| Artifacts | Shell classes merge into the host DEX; the component is a separate `component.dex` | `DexClassLoader` usage in `ComponentManager` |
| Obfuscation | Preserve entry class names for both shell and component | Reflection via `loadClass(entry)` |

The shell uses `Dialog`, `Notification`, `DexClassLoader`, and `Settings.Secure`, all available on API 21. From API 23 overlays need `canDrawOverlays`; from API 26 notification channels and `TYPE_APPLICATION_OVERLAY` apply. The shell branches for both and silently falls back to a notification or dialog on failure.

## Component Size and ABI

| Item | Detail |
| --- | --- |
| Recommended limit | 4 MB (server-side default) |
| Client hard limit | No 4 MB constant; only the manifest `size` and actual file are checked |
| DEX method count | Still bounded by the 65,536 method-reference limit per DEX |
| ABI | Independent of ABI; the component is pure Java bytecode |
| Native libraries | The shell does not download `.so` files with the component; the component handles them |

A component over 4 MB may be rejected by the server on upload or delivery. The client does not raise a separate "too large" error; it reports "size mismatch" or "SHA-256 verification failed" only when the values disagree. Large assets also lengthen download and verification, so host them on your own CDN instead.

The shell and component are loaded by different `DexClassLoader` instances and must not reference each other's classes, except for `com.jiyan.cloud.spi.Component`. If a component needs native code, it must extract and `System.load` the library itself. The shell does not continue cloud-update downloads in the same launch; the shell reserves a `pending_restart` marker, but no reachable path writes it today, so cross-restart work still needs component-side handling.

## Multiple Apps

| Item | Current behavior |
| --- | --- |
| Config parsing | `ShellApps` parses every entry in `apps[]` |
| Actual use | Only `firstApp()` is used; `call` and `report` always use the first app |
| Isolation | Each host package has its own `filesDir`, `state.json`, and component directory |
| Device identity | Hosts on one device share `ANDROID_ID` |
| Keys | Each `app_id` has its own `sign_key` / `encryption_key` |

The decrypted config is an excerpt below:

```json
{
  "uid": "<USER>",
  "apps": [
    {"app_id": "<APP_ID>", "app_name": "<NAME>", "sign_type": "<SIGN_TYPE>",
     "sign_key": "<SECRET>", "encryption_key": "<SECRET>"}
  ]
}
```

One process supports one app context. If a host configures several `app_id` values, the second and later entries are never used for requests. For multiple apps, give each app its own package and process and initialize the shell separately; do not try to switch `app_id` inside one `Cloud.init`.

## Differences from Legacy DEX (1.2/1.3)

| Version clue | Fact | Impact |
| --- | --- | --- |
| 1.2 | A `Cloud.java` comment says the public signatures are "fully compatible with 1.2" | Existing three-line host injection needs no change |
| 1.3 | `MainActivity` displays "壳 1.3 · 组件化" (shell 1.3, componentized) | Business code moved out of the shell into a component behind `Component` |
| Open source | CHANGELOG lists 1.0.0 (2026-11-13, shell build 26) | The public repository starts at this version |
| Legacy code | The repository keeps no 1.2/1.3 source | No line-by-line comparison is possible; use historical release notes |

When migrating, keep the `Cloud.set_data` / `set_iv` / `init` entry points and move the old in-shell business code into `ComponentImpl`. Do not leave the same business classes in both the host DEX and the component DEX, or you will get two copies with separate state. If legacy code referenced shell-internal classes, change it to depend only on `com.jiyan.cloud.spi.Component`.

## Build Number and min_shell

| Field | Current value | Purpose |
| --- | --- | --- |
| `SHELL_BUILD` | `26` | Build number used in `User-Agent` and display; not a compatibility check |
| `SHELL_VERSION` | `1` | Protocol/API generation; `min_shell` is compared against it |
| Component `min_shell` | Set at publish time | Loading is rejected when it exceeds `SHELL_VERSION` |

The key point is that `min_shell` is compared with `SHELL_VERSION=1`, not `SHELL_BUILD=26`. Setting `min_shell` to 26 makes every existing shell report "update the integration package", even shells at build 26. A component published today must keep `min_shell` at most 1; raising it requires first shipping a shell with a higher `SHELL_VERSION`.

## Upgrade Paths

1. Component update: raise `version`, recompute `sha256`, re-sign, and publish. The shell prompts, then hot-swaps or asks for a restart.
2. Shell update: merge the new shell source into the host, re-sign, and ship a full package. The shell cannot self-update or be replaced by a component.
3. Old-shell compatibility: keep `min_shell` within the range supported by existing shells; raise `SHELL_VERSION` only for a breaking change.
4. Fallback safety: ensure `previous` points to a working older version with its DEX still on disk, or the first failure goes straight to re-download.
5. Cleanup: version directories other than active and previous are deleted after 30 days. Active and previous are always kept, but an old directory enters the cleanup window once `previous` is replaced or cleared.
6. Rollout: increment `version` for every component release. The shell detects changes by `sha256` alone, so replacing content under the same version is still treated as an update, but it removes the version trail needed for fallback and diagnosis.

## Compatibility Checklist

| Check | Pass criteria |
| --- | --- |
| Minimum OS | First download and load succeed on API 21 |
| Build | JDK 17 compiles with `-source 8 -target 8` |
| Entry point | `public`, no-argument constructor, name matches backend config |
| Size | Component DEX is at most 4 MB and below 65,536 methods |
| ABI | No `.so` loaded by the shell; native code is extracted by the component |
| Multiple apps | Each package initializes independently and does not share `filesDir` |
| Old shells | Component `min_shell` does not exceed the target shell's `SHELL_VERSION` |
