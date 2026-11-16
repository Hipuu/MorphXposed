# Test Results

Everything below was executed in this environment. Commands are reproducible;
no result is estimated.

Environment:

| | |
|---|---|
| JDK | Temurin 21.0.13+9 |
| Android SDK | platform 37 (API 37), build-tools 37.0.0 |
| Gradle | 9.9.1 |
| AGP | 9.4.1 |
| Kotlin | 2.4.21 |
| morphe-patcher | 2.4.0 (GitHub Packages) |
| morphe-library | 1.4.0 (GitHub Packages) |
| Bundle under test | `patches-2.3.0.mpp` (16,663,050 bytes) |

---

## 1. Engine linkage

The official patching engine must be linkable from Android before anything else matters.

```
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL
app-debug.apk  35.8 MB
```

`PatcherLinkProbe` references `app.morphe.patcher.{Patcher, PatcherConfig,
PatcherResult, patch.Patch, patch.PatchLoader}`. The build fails if any of them cannot be
linked, so a successful `assembleDebug` is itself the assertion that Morphe's engine is
present and usable.

---

## 2. Bundle integrity

Verifies the SHA-256 path against the real release.

```
$ curl -L -o patches.mpp .../morphe-patches/releases/download/v2.3.0/patches-2.3.0.mpp
$ sha256sum patches.mpp
1c37549274a2b6cf7f2e3e099d9b8cb641880b8a7dac323a95638a06bb4509a9

published digest (GitHub release asset "digest"):
sha256:1c37549274a2b6cf7f2e3e099d9b8cb641880b8a7dac323a95638a06bb4509a9
```

**Match.** The download-verification path is validated against Morphe's own published
digest, not a self-generated one.

---

## 3. Unit and integration tests

```
$ ./gradlew :app:testDebugUnitTest
BUILD SUCCESSFUL
```

| Suite | Tests | Failures |
|---|---:|---:|
| `CompatibilityResolverTest` | 11 | 0 |
| `VersionComparatorTest` | 4 | 0 |
| `BundleShapeDiagnosticsTest` | 1 | 0 |
| `RealBundleIntegrationTest` | 5 | 0 |
| `EndToEndPatchTest` | 2 | 0 |
| **Total** | **23** | **0** |

### Real-bundle integration (against `patches-2.3.0.mpp`)

`RealBundleIntegrationTest` loads the official bundle through the official
`loadPatchesFromJar` and asserts:

* the bundle declares **212 patches** (>50 threshold asserted);
* it declares compatibility for `com.google.android.youtube`,
  `com.google.android.apps.youtube.music`, and `com.reddit.frontpage`;
* a declared YouTube version resolves as `SUPPORTED` with applicable patches;
* an invented version (`0.0.1-not-a-real-version`) does **not** resolve as `SUPPORTED`;
* the manifest declares a `Patcher-Version`, and universal patches are detected.

`BundleShapeDiagnosticsTest` prints the real shape of the bundle's YouTube declarations:

```
=== YouTube compatibility declarations ===
total entries     : 118
empty targets     : 0
with targets      : 118
```

### A real bug this testing found

An early version of `CompatibilityResolver` reported *any* version as `SUPPORTED`
whenever the bundle contained **universal** patches (signature/installer spoofing), since
those apply to every app. Combined with automation, that would have patched arbitrary
unsupported versions of YouTube.

The diagnostic printed `resolver says for bogus version: SUPPORTED`, which exposed it.

Fixed by adding `Compatibility.UNIVERSAL_ONLY`: universal patches are offered, but they do
not make a version *supported*, and the orchestrator's gate (`status == SUPPORTED`) still
refuses to auto-patch. Three regression tests cover it:

* `universal patch alone does not make an unsupported version patchable`
* `universal patch on a package no patch targets is universal only`
* `universal patch applies alongside a supported version`

---

## 4. End-to-end patching (the important one)

Proves the app actually patches a real APK with Morphe's engine, rather than only
inspecting metadata.

A real signed APK is built from scratch with the Android toolchain:

```
javac (API 37 android.jar) → d8 → aapt2 compile/link → zipalign → apksigner
→ testdata/target.apk (16,787 bytes, package com.example.target, v2+v3 signed)
```

`EndToEndPatchTest` then runs the app's code path — `loadPatchesFromJar` →
`PatcherConfig` → `Patcher` → `writeApk` — and asserts the output is a structurally valid
repackaged APK.

```
Applied "Clone app" -> 2244 bytes
End-to-end patch verified with "Clone app"
BUILD SUCCESSFUL
```

Independent verification with `aapt2 dump badging`:

```
original : package: name='com.example.target'
patched  : package: name='com.example.target.morphe'
```

**Morphe's `Clone app` patch rewrote the APK's manifest and renamed the package.** That is
a genuine bytecode/manifest transformation performed by the official engine, verified by
an external tool rather than by our own assertions.

Side observation confirming real patch execution: when a YouTube-only patch
(`Override certificate pinning`) was run against the synthetic app, Morphe itself failed
inside its own patch code with
`.../res/xml/network_security_config.xml (The system cannot find the path specified)`,
while `Change installer source` on the same APK succeeded. That is Morphe's fingerprinting
and resource logic running, and correctly rejecting an app it was not written for.

---

## 5. Build artifacts

```
$ ./gradlew :app:assembleRelease :xposed:assembleRelease
BUILD SUCCESSFUL
```

| Artifact | Size | Signature |
|---|---:|---|
| `MorphXposed.apk` | 26.6 MB | v2, RSA-4096, `CN=MorphXposed` |
| `MorphXposed-Events.apk` | 0.7 MB | v2, RSA-4096, `CN=MorphXposed` |

```
$ apksigner verify --print-certs dist/MorphXposed.apk
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
V2 Signer: certificate DN: CN=MorphXposed, OU=Release, O=MorphXposed, ...
V2 Signer: key size (bits): 4096
```

### Xposed module correctness

An LSPosed module **must not bundle** the Xposed API, or it clashes with the framework's
copy. Verified via the runtime classpath:

```
$ ./gradlew :xposed:dependencies --configuration releaseRuntimeClasspath
+--- org.jetbrains.kotlin:kotlin-stdlib:2.4.21
```

The API jar is absent from the runtime classpath (it is `compileOnly`), and present only at
compile time. The module APK also ships `assets/xposed_init` declaring
`dev.morphxposed.events.EventsModule`, and manifest metadata
`xposedmodule=true`, `xposedminversion=82`, scope `android`.

---

## 6. What was *not* tested, and why

**On-device execution.** No Android device or emulator with LSPosed was available in this
environment, so these were verified by build/link/artefact inspection rather than by
running:

* the Compose UI rendering and navigation,
* `WorkManager` scheduling actually firing,
* the session installer's `PendingIntent` round-trip,
* root-mount module mounting at boot,
* the LSPosed module hooking `PackageManagerService` on a real framework.

Those paths are implemented against the documented platform APIs and the manifest is
complete, but "compiled and packaged" is a weaker claim than "ran on a device", and is
reported as such rather than overstated.

**Patching a real YouTube/Reddit APK.** Those APKs are copyrighted and were not
downloaded. The engine was instead proven on a synthetic APK (section 4), which exercises
the same code path without redistributing anyone's app.
