# Dependency CVE Scan — NeverOff 2.12

| | |
|---|---|
| Scope | `releaseRuntimeClasspath` — everything shipped in the release APK |
| Artifacts resolved | 227 unique Maven coordinates |
| Data source | OSV.dev (GitHub Security Advisory + NVD feeds, Maven ecosystem) |
| Date | 2026-08-01 |
| **Advisories found** | **3, across 2 libraries** |
| **Exploitable in this app** | **0** |

Raw output: `osv_results.json`.

> **Tool note.** You asked for OWASP Dependency-Check specifically. Dependency-Check 9+
> requires a free NVD API key; without one the first run is throttled to a multi-hour
> database build, and no key is set on this machine. OSV.dev queries the same GitHub
> Security Advisory and NVD data for the Maven ecosystem with no key, so I used it to get
> you answers now. See "Running the official tool" at the bottom — the results should
> agree, and I can run it once a key exists.

---

## 1. Ktor 2.3.11 — CVE-2024-49580 (Moderate) — not exploitable here

```
GHSA-8qv4-773j-c979 / CVE-2024-49580
CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N
"Improper caching in JetBrains Ktor before 3.0.0 in the HttpCache Plugin
 could lead to response information disclosure."
Fixed in: 2.3.13
```

**Why it does not apply:** the vulnerability is confined to the `HttpCache` plugin, and this
app never installs it. [SupabaseClient.kt](app/src/main/java/com/neveroff/data/remote/SupabaseClient.kt)
installs `HttpTimeout` and nothing else on the Ktor client, plus the Supabase modules
(`Auth`, `Postgrest`, `Realtime`, `Storage`). With no `HttpCache` installed there is no
response cache to disclose from.

The plugin's classes *are* present in the APK — MobSF saw
`io/ktor/client/plugins/cache/storage/FileCacheStorage.java` in its MD5 finding — but only
because [proguard-rules.pro](app/proguard-rules.pro) keeps `io.ktor.**` wholesale for
reflection. Present but never instantiated.

**Still worth fixing.** `ktor = "2.3.11"` → `"2.3.13"` in
[libs.versions.toml](gradle/libs.versions.toml) is a patch bump inside the same minor line,
API-compatible, and supabase-kt 2.4.0 is unaffected. It removes the finding and costs a
rebuild.

## 2. Guava 31.1-android — CVE-2020-8908 (Low), CVE-2023-2976 (Moderate) — not exploitable on Android

```
GHSA-5mg8-w23w-74h3 / CVE-2020-8908  "Information Disclosure in Guava"
GHSA-7g45-4rm6-3mm3 / CVE-2023-2976  "insecure use of temporary directory"
Fixed in: 32.0.0-android
```

**Where it comes from:** not a direct dependency — `androidx.media3:media3-exoplayer:1.1.1`
→ `media3-common:1.1.1` → `guava:31.1-android`.

**Why it does not apply:** both are the same class of bug — `Files.createTempDir()` and
`FileBackedOutputStream` creating temp directories with permissive modes on a shared Unix
`/tmp`. Android has no shared temp directory; `java.io.tmpdir` resolves inside the app's
own private sandbox, where other apps cannot read regardless of file mode. These are
desktop/server-JVM issues that do not translate to the Android security model.

**Fix path is heavier.** Guava 32+ arrives by bumping media3, which is currently pinned at
`1.1.1` directly in [build.gradle.kts:139](app/build.gradle.kts:139). Newer media3 lines
carry ExoPlayer API changes, so this needs a real test pass on playback — not a
drive-by bump. Given both CVEs are inapplicable on Android, this is housekeeping, not
urgent.

---

## Not CVEs, but worth your attention

The scan found no advisories against these, because none have been filed — not because
they are current:

| Dependency | Pinned | Concern |
|---|---|---|
| `androidx.camera:*` | `1.3.0-rc01` | A **release candidate** shipping in production. RCs get no security backports; move to a stable 1.3.x/1.4.x. |
| `androidx.biometric` | `1.2.0-alpha05` | An **alpha**, and it gates authentication. Alphas can change behaviour between builds and receive no security support. This one guards app access — I would prioritise it. |
| `net.zetetic:android-database-sqlcipher` | `4.5.4` | The artifact is superseded by `net.zetetic:sqlcipher-android`. 4.5.4 dates to 2022 and bundles its own SQLite build. |
| `androidx.media3` | `1.1.1` | Media parsers are a classic Android attack surface, and this app plays attacker-supplied media from chat. |

**A real limitation of this scan, stated plainly:** coordinate-based scanning only sees what
is declared in the Gradle graph. It cannot see vulnerabilities in code *bundled inside* an
artifact — most relevantly the SQLite build compiled into `libsqlcipher.so`. A SQLite CVE
would never surface under the `android-database-sqlcipher` coordinate. The same blind spot
applies to `libimage_processing_util_jni.so`. OWASP Dependency-Check partially closes this
with evidence-based file analysis, which is one concrete reason to run it in addition.

Media3 and SQLCipher both parse untrusted input — chat media and database files — so their
age matters more than the empty CVE list suggests.

---

## Recommendation

1. **Bump Ktor to 2.3.13.** One line, patch-level, closes the only CVE with any plausible
   path. Safe to do now.
2. **Move biometric off `1.2.0-alpha05`** to a stable release. It guards app entry; an alpha
   is the wrong dependency class for that job.
3. **Move CameraX off `1.3.0-rc01`** to stable.
4. **Plan a media3 upgrade** with playback regression testing. Clears the Guava CVEs as a
   side effect and refreshes a parser exposed to hostile input.

## Running the official tool

Once an NVD API key exists (free, self-service at
`https://nvd.nist.gov/developers/request-an-api-key`), Dependency-Check runs without
touching the build files, via an init script:

```bash
./gradlew --init-script dependency-check.gradle dependencyCheckAnalyze -Dnvd.api.key=YOUR_KEY
```

First run builds a local NVD cache (~10 min with a key, hours without). Subsequent runs are
fast. Its added value over this scan is the evidence-based analysis of bundled binaries
noted above.
