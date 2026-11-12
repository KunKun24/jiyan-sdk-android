# Component Protocol

This document covers only client-visible protocol behavior, not server internals. Every field comes from the current sources of `ComponentManager`, `Http`, `ShellConfig`, `ShellApps`, and `Signer`; string values are placeholders.

## Transport and Envelope
The shell sends JSON with `POST` to the component endpoint under `ShellConfig.API_BASE`. It sets `Content-Type: application/json; charset=utf-8`, forces `Accept-Encoding: identity`, and sends `User-Agent: JiYan_CloudShell/<SHELL_BUILD>`.

| Behavior | Source fact |
| --- | --- |
| Response body | `{ok, payload, sig}`, where `payload` is a JSON string, not a nested object |
| Success | HTTP 2xx, `ok == true`, and a non-empty `payload` |
| Failure | Non-2xx throws `HTTP <code>`; `ok != true` throws `msg`; empty `payload` throws "empty response"; malformed JSON throws "bad response format" |
| Timeouts | 15 s connect / 30 s read by default; the startup check uses 4 s / 6 s |

```json
{"ok":true,"payload":"{\"active\":{\"version\":12,\"min_shell\":1,\"sha256\":\"<64_HEX>\",\"size\":524288,\"label\":\"v1.2\",\"content\":\"...\"}}","sig":"<BASE64>"}
```
```json
{"ok":false,"msg":"<error message>"}
```

`Http.post` currently reads only `ok`, `msg`, and `payload`; it does not verify the response `sig`, despite a comment claiming it does. The source is authoritative.

## The Three Actions

The client sends only three actions: `component_manifest` (`version=0`) fetches the available manifest, `component_url` (target version) returns a one-time download path and package signature, and `component_report` (active version) reports load, download, and initialization results.

## Request Fields

| Field | Source and meaning |
| --- | --- |
| `action` | One of the three values above |
| `developer_username` | Decrypted config `uid` |
| `app_id` | `app_id` of the first valid entry in `apps[]`; must match `[1-9][0-9]*` |
| `device_id` | `ANDROID_ID`, then `Build.SERIAL`, then `"unknown"` |
| `shell_version` | `ShellConfig.SHELL_VERSION` (currently `1`), not `SHELL_BUILD` |
| `current_version` | Local active version, or `"0"` when none |
| `timestamp` | Unix time in seconds, as a string |
| `nonce` | Base64url of 18 random bytes, no padding, 24 characters |
| `version` | See above; `0` for manifest, active version for report |
| `status` / `detail` | Empty on normal requests; report fills the result and a single-line detail capped at 180 characters |
| `sig` | Lowercase HMAC-SHA256 hex; see [Signing and Verification](signing.md) |

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

The current open-source `call()` assembles only the first 11 fields and posts them directly; it does not put `sig` in the body, and `Signer` is used only by tests. If the backend enforces `sig`, add it as described in the signing document, subject to the server contract.

## Manifest Response

The shell reads only `payload.active`. A `null` `active` or a failed response means "no component available" and does not block component loading.

```json
{
  "active": {
    "version": 12, "min_shell": 1, "sha256": "<64_HEX>",
    "size": 524288, "label": "v1.2", "content": "release notes"
  }
}
```

| Field | Type | Shell usage |
| --- | --- | --- |
| `version` | int | Directory `components/vN`; must be greater than 0 |
| `min_shell` | int | Compared with `ShellConfig.SHELL_VERSION`; higher values are rejected |
| `sha256` | string | Must be 64 characters; compared with the local active SHA to decide updates |
| `size` | long | Expected download size; must be greater than 0 |
| `content` | string | Card text, truncated to 4000 characters |
| `label` | string | Display name; falls back to `v<version>` |
| `rollback` / `paused` | - | Not read by the current source |

Update checks compare only `sha256`: a higher `version` with the same `sha256` does not trigger an update. Before installing, the shell validates `version > 0`, a 64-character `sha256`, and `size > 0`; otherwise it throws "incomplete manifest fields".

## component_url Response

```json
{"path": "<RELATIVE_DOWNLOAD_PATH>", "signature": "<BASE64_ECDSA_SIGNATURE>"}
```

| Field | Shell usage |
| --- | --- |
| `path` | Concatenated directly with `API_BASE`; empty throws "download URL missing" |
| `signature` | ECDSA P-256 signature of the component DEX; empty skips verification |

Downloads support HTTP Range: if `.part` exists, the request sends `Range: bytes=<downloaded>-`; `206` appends and `200` restarts from zero. The shell checks size and SHA-256, then renames the file to `classes.dex`. Download tickets are device-bound and expire after 15 minutes by server contract; the client does not parse the ticket.

## component_report

Reports are sent on a separate thread; failures are logged and never block startup. Both `current_version` and `version` use the active version, and `detail` is flattened to one line and truncated to 180 characters.

| `status` | Meaning | Sent by current source |
| --- | --- | --- |
| `ok` | Load or hot swap succeeded | Yes |
| `init_failed` | Component init failed, fallback, or hot swap failed | Yes |
| `download_failed` | Manifest fetch failed during first install | Yes |
| `install_failed` | Installation failed | No; there is no call site |
| `crash` | Component crashed | No; there is no call site |

## Retries and Timeouts

| Stage | Behavior |
| --- | --- |
| Startup / regular manifest | Startup requests once (4 s / 6 s); regular checks retry twice with 800 ms gaps |
| Download | 15 s connect / 30 s read; progress callback every 200 ms |
| JSON response | At most 256 KB is read; the rest is discarded |
| Update check | Runs 2500 ms after a successful component load |
