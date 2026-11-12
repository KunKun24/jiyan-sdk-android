# Failure Types and Troubleshooting

Every shell failure ends up in an error card, a notification, or a `component_report`. This document groups failures by the message users see and gives the trigger plus a troubleshooting order; strings come from the current `ComponentManager`, `ShellUi`, and `Http` sources.

## Failure Overview

| Type | Trigger | User-visible message |
| --- | --- | --- |
| Download failure | Network, ticket, disk, or HTTP failure | "组件下载失败" (component download failed), "下载失败 HTTP `<code>`" |
| Integrity failure | Size or SHA-256 mismatch | "SHA-256 校验失败", "大小不符" (size mismatch) |
| Signature failure | ECDSA verification fails | Logcat only; no card is shown |
| Incomplete manifest | Invalid `version`, `sha256`, or `size` | "清单字段不完整" |
| Install failure | Directory creation, old-file delete, or rename fails | "组件目录创建失败", "旧组件删除失败", "组件落盘失败" |
| Init failure | Entry class load or `init` throws | "组件初始化异常", "组件启动失败" |
| Fallback | Previous version also fails after switching back | "已尝试回退仍不可用。" |
| Disabled version | Same version failed three times | No dedicated message; repeated failures |

## Download Failures

Common causes: no network, an expired download ticket (about 15 minutes), a CDN rejecting the URL, a `Range` resume with an inconsistent length, or insufficient disk space.

Troubleshooting order:

1. Check the `Http` logcat output: connection timeout, HTTP 4xx/5xx, or "下载不完整 `<done>/<expect>`" (incomplete download).
2. Verify the system clock; an expired ticket is replaced on the next update attempt.
3. Check free space in `filesDir` and delete a stale `component.dex.part` before retrying.
4. If every attempt stalls at the same percentage, focus on CDN `Range` support and stable content length.
5. A user tap on "取消" (cancel) produces "已取消更新" (update cancelled); this is not a fault, just retry.

## Integrity Failures

A size mismatch throws "大小不符"; a SHA-256 mismatch throws "SHA-256 校验失败" and records the failure as `file_corrupt`.

Common causes: the CDN serves an old file, the transfer is truncated, the manifest `sha256` does not match the uploaded file, or the DEX was rebuilt without updating the manifest.

Compare `sha256sum` of the `.part` or `classes.dex` with the manifest value, then check `size`. If the local copy matches but the device copy does not, look for a middlebox rewriting the response body. A file that fails these checks is never activated.

## Signature Failures

After a SHA-256 match the shell calls `verify()`. On failure it logs only: "组件文件签名未通过，但 SHA-256 已匹配，继续安装" (signature did not match, but SHA-256 did; continuing). Installation is not blocked, so users may see nothing even though security is reduced.

Common causes: the backend signed with the wrong private key, `signature` is not Base64, the public key and private key do not match, or the signature covers a different file than the one served.

Run `openssl dgst -sha256 -verify` against the same DEX and confirm that `ShellConfig.COMPONENT_PUBKEY` is Base64 of X.509 DER. Do not "fix" this by disabling verification.

## Incomplete Manifest

Before installing, the shell checks `version > 0`, `sha256.length() == 64`, and `size > 0`; any failure throws "清单字段不完整" (incomplete manifest fields).

Common causes: a short hash, `size` set to 0 or sent as an unexpected type, `version` 0, or a misspelled key. Print `payload.active` and verify the JSON types; `label` and `content` may be missing without affecting installation.

## Install Failures

Three filesystem errors exist: creating `components/vN` fails ("组件目录创建失败"), deleting an old `classes.dex` fails ("旧组件删除失败"), or the rename fails ("组件落盘失败").

Common causes: read-only storage, no free space, a file held by another process, or a path that is not a directory.

Confirm that `filesDir/JiYan_Cloud/shell/components` is writable, remove a corrupted version directory, and retry. Do not hand-edit `state.json`: its writer uses tmp + rename, and manual edits easily desynchronize state and disk.

## Initialization Failures

`load()` catches `Throwable`, reports `init_failed`, and shows a card titled "组件启动失败" (component failed to start) containing "已尝试回退仍不可用。" and the reason. The outer `run` path may also show "组件初始化异常" (component init exception).

Common causes: the entry class name differs from the stored `entry`, the class is not `public`, there is no no-argument constructor, `init` throws, the component references classes absent from the shell, or the component DEX lacks a dependency.

Start by replacing the component with `sample-component` to validate the pipeline, confirm the entry class exists in the DEX, and read the full stack trace in logcat. `init` currently runs on the main thread, so blocking work causes an ANR; move it to your own thread.

## Fallback

On the first failure of a new component, if `previous` exists, differs in version, has `previous.min_shell <= SHELL_VERSION`, and its DEX is present, the shell switches active back to `previous` and loads it. The report detail looks like `...; fallback=<version>`.

Fallback is attempted once. If it also fails, the card says "已尝试回退仍不可用。\n<reason>\n\n将重新下载组件库。" (fallback unavailable; the component library will be downloaded again). Retry starts `bootstrap()` from scratch. If the previous DEX was pruned or its `min_shell` is too high, the fallback condition fails and the shell goes straight to re-download.

## Disabled Versions

`fail()` increments `state.fail` on every failure; at three failures it records the current version in `state.bad`. The intent is to stop reinstalling a repeatedly failing version, but the `badVersion` check in `check()` sits after `if (!contentChanged) return;`, making it dead code. A `badVersion` value does not actually block checks.

The state file looks like this; read it for diagnosis, never edit it by hand:

```json
{
  "active": {"v": 12, "min_shell": 1, "sha256": "<64_HEX>", "size": 524288, "entry": "com.jiyan.cloud.comp.ComponentImpl"},
  "previous": null,
  "fail": 0
}
```

If one version keeps failing, fix the component and publish a higher `version`; do not only change `sha256`. As a last resort the user can clear app data (which also clears the config and requires re-initialization) or reinstall. There is no dedicated "disabled" report status.

## Other Startup Messages

| Message | Cause | Action |
| --- | --- | --- |
| "配置解析失败" | `data` / `iv` decryption or format failure | Confirm the config matches this shell version |
| "没有可用应用" | No valid `app_id` in config | Check that `apps[]` IDs match `[1-9][0-9]*` |
| "需要先更新接入包" | Component `min_shell` exceeds `SHELL_VERSION` | Ship a new host build; `min_shell` currently cannot exceed 1 |
| "暂无可用组件" | Manifest has no `active` | Publish a component version first |
| "组件库未就绪" | User tapped "Later" on the first install | Go online and restart the app to retrigger the download |

## Report Status

| `status` | When sent | Example detail |
| --- | --- | --- |
| `ok` | Load or hot swap succeeded | `v12`, `hot_swap=v13` |
| `init_failed` | Init failed, fallback, or hot swap failed | `IllegalStateException: ...; fallback=12` |
| `download_failed` | First-install manifest fetch failed | `IOException: ...` |
| `install_failed` | Reserved; no call site in the current source | None |
| `crash` | Reserved; no call site in the current source | None |

Reports run on a separate thread and only log on failure. `detail` is flattened and capped at 180 characters, so do not rely on it for a full stack trace; use logcat for that.

## Logs

`ShellLog` is designed to write `Download/JiYan_shell.log` with 1 MB rotation, but nothing in the repository calls `ShellLog.init()`. `sFile` stays `null`, `path()` returns an empty string, and file logging never happens; only logcat is available.

Filter logcat by the tag passed at each call site (for example `Comp`, `Http`, `State`, `Ui`; check the source for the exact tags). To rely on file logs, first add a `ShellLog.init(context)` call.
