# Signing and Verification

The component protocol has two cryptographic layers: HMAC-SHA256 proves that the caller holds the app secret, and ECDSA P-256 proves that the component binary was signed by the backend private key. Both rely only on client-visible fields and the built-in public key.

## Signing Key

| Item | Detail |
| --- | --- |
| Preferred field | `sign_key` in the app config |
| Fallback | `encryption_key` when `sign_key` is empty (mapped to `App.apiKey`) |
| Source | Encrypted config from the backend, decrypted by `ShellApps` with AES/CBC |
| Algorithm | `HmacSHA256`, key and data encoded as UTF-8 |
| Output | Lowercase hex, exactly 64 characters |

`App.secret()` uses `sign_key` when non-empty, otherwise `encryption_key`. If the backend contract names the secret `api_key` rather than `encryption_key`, follow the backend configuration; never hardcode a key in the client.

## Canonical String

The canonical string is 11 fields joined in a fixed order by a single `\n`, with no trailing newline. The order comes from `Signer.canonical`:

| # | Parameter | Request field | Meaning |
| --- | --- | --- | --- |
| 1 | `action` | `action` | One of the three actions |
| 2 | `user` | `developer_username` | Config `uid` |
| 3 | `appId` | `app_id` | First app ID |
| 4 | `device` | `device_id` | Device identifier |
| 5 | `shellVer` | `shell_version` | Shell version, currently `1` |
| 6 | `curVer` | `current_version` | Active version or `0` |
| 7 | `ts` | `timestamp` | Unix seconds |
| 8 | `nonce` | `nonce` | 24-character base64url |
| 9 | `version` | `version` | Target version or `0` |
| 10 | `status` | `status` | Empty on normal requests |
| 11 | `detail` | `detail` | Empty on normal requests |

The matching string is `component_report\n<USER>\n<APP_ID>\n<DEVICE_ID>\n1\n12\n<UNIX_SECONDS>\n<NONCE>\n12\nok\n<DETAIL>`. Empty fields still keep their separators; when `status` and `detail` are empty, the canonical string ends with two `\n`, and no extra newline may be appended.

```java
String canonical = action + "\n" + user + "\n" + appId + "\n" + device
        + "\n" + shellVer + "\n" + curVer + "\n" + ts + "\n" + nonce
        + "\n" + version + "\n" + status + "\n" + detail;
```

For `component_report`, `detail` is first flattened to one line and truncated to 180 characters. If report signing is added, sign that normalized value; otherwise the server will compute a different canonical string.

## HMAC Calculation

| Check | Expectation |
| --- | --- |
| Encoding | UTF-8; no URL encoding |
| Case | Lowercase hex; uppercase is a different value to the server |
| Length | 64 characters; anything shorter means the wrong algorithm or encoding |
| Failure | The shell throws `RuntimeException("HMAC 计算失败")` |

## Nonce and Timestamp

| Item | Fact |
| --- | --- |
| Nonce length | 18 random bytes |
| Encoding | `Base64.NO_WRAP \| URL_SAFE \| NO_PADDING`, 24 characters, no padding |
| Generator | `SecureRandom`, regenerated for every request |
| Timestamp | Milliseconds divided by 1000, as a decimal string |

A nonce must be used once; reusing one is rejected by the server (the exact window is server-defined). The client does not enforce a timestamp window; it only sends the current time. A large device clock skew will cause rejections, so prompt the user to fix the clock when needed.

## Component Package Verification

The flow is:

1. Read `sha256` and `size` from the manifest, and `path` plus `signature` from `component_url`.
2. Download to `component.dex.part` and check that its length equals `size`.
3. Compute SHA-256 and compare it with the manifest `sha256`.
4. Verify `signature` over the same file bytes with the built-in public key.
5. Rename to `classes.dex` and switch the active version only if both checks pass.

```java
PublicKey key = KeyFactory.getInstance("EC").generatePublic(
        new X509EncodedKeySpec(Base64.decode(COMPONENT_PUBKEY, Base64.DEFAULT)));
Signature v = Signature.getInstance("SHA256withECDSA");
v.initVerify(key);
// The source feeds a 64 KB buffer through v.update(buf, 0, n)
v.update(dexBytes);
boolean ok = v.verify(Base64.decode(signatureB64, Base64.DEFAULT));
```

| Item | Detail |
| --- | --- |
| Curve | P-256 (secp256r1) |
| Public key format | Base64 of X.509 `SubjectPublicKeyInfo` DER, built into `ShellConfig.COMPONENT_PUBKEY` |
| Signature format | ECDSA DER, delivered as Base64 by `component_url` |
| Digest | SHA-256, handled inside `SHA256withECDSA` |
| Failure | Any exception returns `false`; nothing is thrown to the caller |

## Do Not Disable Verification

SHA-256 proves that a file was not corrupted, not where it came from. An attacker who can modify the manifest can usually replace the download too, so checking only the hash delegates trust to the same channel. The ECDSA private key remains on the backend, so an attacker cannot forge a signature for a new component.

Components run inside the host process with the host's permissions. Disabling signature verification lets anyone who can hijack the download path execute arbitrary code in your app, which is far worse than a failed update.

> The current source only logs a warning when signature verification fails ("component signature did not match, but SHA-256 matched, continuing") and still installs the file. That contradicts the README and SECURITY guidance; treat a verification failure as a failed install before release. The source is authoritative.

## Common Problems

| Symptom | Cause | Action |
| --- | --- | --- |
| HMAC mismatch | Wrong field order, missing empty field, wrong encoding, or hex case | Compare the canonical string with the same placeholder values |
| Verification always `false` | Key is not X.509 DER, or signature is not Base64 | Inspect the key with `openssl pkey -pubin -inform DER` |
| Component rejected | Private key and built-in public key do not match | Confirm the key pair used by the release pipeline |
| Timestamp rejected | Device clock skew | Fix the clock; do not widen the window |

Rotating the key invalidates old shells because the public key is compiled into the shell and the shell cannot self-update. Ship a new host build; changing only the backend key is not enough.

You can verify a signature independently with OpenSSL:

```bash
openssl dgst -sha256 -verify component_pub.pem \
  -signature signature.bin component.dex
```

`Verified OK` means the public key, file, and signature all match.
