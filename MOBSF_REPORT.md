# MobSF Static Analysis — NeverOff 2.12

| | |
|---|---|
| Scanner | MobSF 4.5.1 (static analyzer, jadx 1.5.0 + apktool) |
| Target | `app/build/outputs/apk/release/NeverOff.apk` |
| Package | `com.neveroff` — versionName 2.12, versionCode 7 |
| minSdk / targetSdk | 26 / 34 |
| Baseline scan | 2026-08-01, MD5 `33e511ac1aa4cf78a1a74f1b6c9d06a6` — **score 64/100**, 1 high · 11 warning |
| Post-fix scan | 2026-08-01, MD5 `8320720e2569be468d7b34c0a4b1c7f7` — **score 66/100**, 1 high · 9 warning |

Machine-readable output: `MobSF_report.json` (baseline), `MobSF_report_v2.json` (post-fix).

---

## Remediated

### Backup exfiltration — `allowBackup="true"` with no rules → closed

`allowBackup` is now `false` and
[data_extraction_rules.xml](app/src/main/res/xml/data_extraction_rules.xml) excludes every
domain from **both** cloud backup and device-to-device transfer. The second half matters
independently: on API 31+ `allowBackup="false"` does not by itself govern D2D transfer.

Previously the SQLCipher database and `neveroff_crypto_prefs` — which wraps the identity
keypair — were eligible for `adb backup`. Verified gone from the merged manifest:

```
android:allowBackup=false
android:dataExtractionRules=@xml/data_extraction_rules
```

### Update APK staged on world-readable storage → closed

[UpdateManager.kt:133](app/src/main/java/com/neveroff/util/UpdateManager.kt:133) wrote the
downloaded APK to `externalCacheDir`. App-specific external storage is readable *and
writable* by any app holding `WRITE_EXTERNAL_STORAGE` on API ≤ 28, which this app supports
at minSdk 26. Combined with `REQUEST_INSTALL_PACKAGES` that was an arbitrary-install path:

1. the updater verifies the SHA-256 of what it downloaded, then
2. hands the file to the package installer by URI.

Another app could overwrite `update.apk` between (1) and (2), so the bytes verified and the
bytes installed need not be the same file — a TOCTOU that turns a checksum-gated updater
into arbitrary APK installation.

Two changes close it: staging moved to `File(context.cacheDir, "updates")`, inside the
private sandbox where no other app can write; and the digest is now computed by re-reading
the file from disk after the write rather than hashing the stream in flight, so the value
checked is the value the installer reads.

The rest of the download path was already sound and is unchanged — HTTPS enforced, host
pinned to `BuildConfig.SUPABASE_URL`, and a missing checksum is a hard failure.

### FileProvider exposed all of external storage → closed

[file_paths.xml](app/src/main/res/xml/file_paths.xml) declared `<external-path path="."/>`,
`<cache-path path="."/>` and `<files-path path="."/>` — the whole of external storage, the
entire cache directory (where decrypted media is written) and the entire files directory,
all addressable as `content://` URIs.

The provider has exactly one consumer, `UpdateManager.installApk`, so it is now scoped to
the single directory that consumer needs:

```xml
<cache-path name="updates" path="updates/" />
```

### Crash logs written to shared storage → closed

[LoggerManager.kt](app/src/main/java/com/neveroff/util/LoggerManager.kt) wrote stack traces
and exception messages under `getExternalFilesDir()`. Its comment claimed that location is
"not world-readable" — true from API 29, but false on API 26–28, which this app supports.
Both log paths now use `filesDir`. Nothing depended on external access: `saveLogsToDisk`
has no callers, and the crash handler is the only writer.

### Logcat leakage (CWE-532, CVSS 7.5) → closed

[proguard-rules.pro](app/proguard-rules.pro) now strips `android.util.Log` via
`-assumenosideeffects`, covering `v/d/i/w/e/wtf`. Because the rule matches every caller, R8
removes the calls from bundled libraries too — MobSF's `android_logging` finding went from
121 files to **zero**. Error diagnostics are not lost: `LoggerManager.logError` still
persists full stack traces to internal storage, which is where field diagnostics actually
come from.

### v3 signature block added

`enableV3Signing = true` in [build.gradle.kts:49](app/build.gradle.kts:49). This does not
change the key — it is still the debug key, by design (see below) — but a v3 block carries a
signing lineage, and rotation reads the lineage from the *installed* APK. Shipping v3 now is
what makes a future migration to a real key possible without an uninstall; without it that
door stays shut. Verified on the built APK:

```
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

`v1` stays off deliberately — it is only consulted below API 24 and minSdk is 26.

---

## Accepted / not fixable in the app

### High — release signed with the Android debug certificate

Unchanged and deliberate. Switching keys forces an uninstall, which wipes
`neveroff_crypto_prefs` and the AndroidKeyStore entry, making every message already on the
server permanently undecryptable — the trade-off documented at
[build.gradle.kts:37](app/build.gradle.kts:37).

This remains the single most serious finding: the debug keystore's password is the public
constant `android`, so anyone can build an APK Android accepts as an update to this app.
The v3 block above is the prerequisite for eventually fixing it; the migration itself needs
an identity-key export path first.

**Back up `~/.android/debug.keystore` off this machine.** It exists only here, and losing it
means no existing install can ever be updated again.

### minSdk 26 — accepted

Kept at 26 by decision. Raising to 29 would close the warning but drop Android 8.0/8.1/9
users. The concrete risks it implied — world-readable external storage on API ≤ 28 — are now
fixed directly in code, which is the better mitigation than dropping users.

### Google API key in the APK — needs Cloud Console, not code

`AIzaSyD3yf57kvgG7Qp3sOMKB97jagT2hqtqpEE` ships from `google-services.json`. This is normal
for Firebase and it is an identifier, not a credential — it is only a problem if
unrestricted. Confirm in Google Cloud Console that it has an **Android app restriction**
(package `com.neveroff` + release SHA-1 `9832246ae28623088b1def3c37dd4e20b1e0a5ab`) and an
**API restriction** limiting it to the Firebase APIs in use. I cannot change this from here.

### Supabase anon JWT — server-side boundary

The project ref, anon key, storage bucket path and both Edge Function endpoints are all
recoverable from the APK by design; the anon key is meant to ship. That makes **Row Level
Security the actual security boundary**. Worth auditing separately, as static analysis
cannot see it: RLS enabled on every table with no permissive `USING (true)` policies, the
`chat_media` bucket not public-readable, and `push-notify` / `channel-notify` validating the
caller's JWT rather than trusting a body parameter for sender identity.

### Certificate pinning — deliberately not added

MobSF reports pinning as a passing check. That is **wrong** — the match is OkHttp's bundled
`CertificatePinner` class, which ships with the library whether or not it is used. No
`CertificatePinner`, `TrustManager` or `HostnameVerifier` exists in this project.

Adding it was considered and rejected: the app self-distributes updates through the same
Supabase host. A pinned build that outlives a certificate rotation cannot reach the server
*at all*, including to fetch the fix — and with no Play Store auto-update, that is an
unrecoverable brick requiring every user to sideload manually. Cleartext is already disabled
app-wide, so the residual exposure is limited to a device with an attacker-installed root CA.

---

## Remaining warnings — triage

All nine surviving warnings are either library-internal or false positives.

| Warning | Where it lives | Verdict |
|---|---|---|
| Insecure RNG | Ktor / Compose internals | 0 own-code files |
| SHA-1 | Ktor nonce generation | 0 own-code files |
| MD5 | Ktor HTTP cache file naming | 0 own-code files |
| Raw SQL query | Room / SQLCipher internals | 0 own-code files |
| 2 exported receivers | Firebase + AndroidX injected | signature-level permissions; not declared by us |
| minSdk 26 | — | accepted, above |
| Hardcoded secrets | `google-services.json` | Console restriction, above |
| "Files may contain hardcoded sensitive information" | see below | **false positive** |

The only own-code hit left is `android_hardcoded` against
[Channel.kt:89](app/src/main/java/com/neveroff/data/model/Channel.kt:89) (`ChannelInsert`)
and [Channel.kt:192](app/src/main/java/com/neveroff/data/model/Channel.kt:192)
(`ChannelUsernameRow`). Both are plain `@Serializable` data classes; the rule fires on the
literal string `"username"` that kotlinx.serialization emits into the descriptor. No secret
is present and there is nothing to fix.

## Still passing

Cleartext traffic disabled for all domains · SQLCipher AES-256 · no privacy trackers across
MobSF's 432-tracker database · all native libraries NX-enabled, stack-canary protected,
symbol-stripped, no RPATH · R8 minification active · no debuggable flag · no exported
activities, services or providers beyond the launcher.

## Permissions

7 dangerous permissions, each traceable to a feature: `CAMERA` + `RECORD_AUDIO` (circular
video/voice messages), `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`
(gallery picker, correctly gated to `maxSdkVersion="32"`), `POST_NOTIFICATIONS` (FCM).

`REQUEST_INSTALL_PACKAGES` backs the in-app updater. Its risk was the TOCTOU fixed above;
the remaining flow enforces HTTPS, host-pins to the Supabase origin, and refuses to install
without a matching SHA-256.
