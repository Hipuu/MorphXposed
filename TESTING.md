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

| Suite | Tests | Failures | Runtime |
|---|---:|---:|---|
| `CompatibilityResolverTest` | 11 | 0 | JVM |
| `VersionComparatorTest` | 4 | 0 | JVM |
| `BundleShapeDiagnosticsTest` | 1 | 0 | JVM |
| `RealBundleIntegrationTest` | 5 | 0 | JVM + real bundle |
| `EndToEndPatchTest` | 2 | 0 | JVM + real engine |
| `DatabaseAndScannerTest` | 6 | 0 | Robolectric |
| `SettingsAndScannerTest` | 7 | 0 | Robolectric |
| `ScreenCompositionTest` | 14 | 0 | Robolectric + Compose |
| `WorkSchedulingTest` | 8 | 0 | Robolectric + WorkManager |
| `OrchestratorLogicTest` | 8 | 0 | Robolectric + Room |
| `SourceAndReceiverTest` | 7 | 0 | Robolectric + Room |
| `LsposedBridgeContractTest` | 5 | 0 | Robolectric + Room |
| `RootModuleScriptsTest` | 9 | 0 | JVM (shell generation) |
| `DependencyGraphTest` | 6 | 0 | Robolectric + Koin |
| `PatchSignerTest` | 9 | 0 | Robolectric + real apksig |
| `PatchThenSignPipelineTest` | 3 | 0 | JVM + real engine |
| `SplitArchivesTest` | 5 | 0 | JVM |
| `BackupRollbackTest` | 8 | 0 | Robolectric + Room |
| **Total** | **118** | **0** (0 skipped) | |

### Compose UI is actually composed

`ScreenCompositionTest` renders all six screens through a real
`createComposeRule`, then asserts on what a user would see and do:

* **Dashboard** — metric tiles, app rows, the empty-run hint, and a failed run
  showing its error.
* **Apps** — a compatible app shows a `Compatible` chip and an enabled `Patch`
  action; an unsupported version shows `Version unsupported` with the button
  **disabled**; the per-app `Auto` switch is clickable and reports the right
  package and new value.
* **Patches** — source name, official badge, installed tag, and a surfaced source
  error.
* **Automation** — switches reflect settings and are interactive; clicking
  "Patch newly installed apps" reports `true`.
* **Logs** — a run row with its status, and the empty state.
* **Settings** — installer choices, the built-in source, and both installer
  caveats (the data-loss warning for session installs, the mount explanation for
  root mount).

Composition is run at a phone-sized qualifier (`w411dp-h891dp`) so lazy-list
content is laid out rather than clipped, and assertions scroll where needed.

### WorkManager scheduling is actually dispatched

`WorkSchedulingTest` initialises a real test `WorkManager` and inspects the
enqueued work:

* the periodic check is enqueued exactly once, with a `CONNECTED` constraint;
* rescheduling (6h -> 3h -> 12h) replaces rather than duplicating;
* cancellation removes it;
* an interval of `0` is clamped and does not throw;
* auto-patch work is enqueued per package, keyed by package name;
* re-enqueueing the same package replaces instead of stacking;
* toggling the setting on then off round-trips through real scheduling.

### The orchestrator's decision logic is executed

`OrchestratorLogicTest` drives the real `PatchOrchestrator` against real Room and DataStore,
with the device boundaries (scanner, installers) as they actually behave. It covers the
early-exit paths, which are the easiest to get wrong and the hardest to notice:

* a package that is not installed is refused, and **no run row is written** for it;
* with no cached bundle the run fails cleanly with an explanation naming the cause;
* a **disabled** source is not considered;
* the installer-defaults assertion confirms session installs are the unprivileged default;
* root mount reports unavailable without root and returns `NotPermitted` rather than throwing;
* root mount never requires uninstalling the stock app, while the session installer does;
* **every run terminates** in `Done` or `Error` and never ends silently, because the UI
  waits on exactly those two events.

`SourceAndReceiverTest` covers the package-event path and source bookkeeping:

* the receiver ignores malformed intents, its own package, and packages no source supports;
* a removal broadcast actually drops the row from the Application's database, so a stale
  entry cannot later trigger a run;
* every form of a repository spec a user might paste (`owner/repo`, with `https://github.com/`,
  with a trailing slash, with `.git`) normalizes to the same source;
* a failed refresh records the error **without erasing the working cached bundle**;
* only enabled sources are read.

### The install pipeline is verified as a whole, plus rollback

Individual steps being correct does not mean the sequence is. `PatchThenSignPipelineTest`
runs **patch -> sign -> verify** in the order a real run does, which is the composition an
install actually depends on: a patched APK that cannot be signed, or a signed APK that does
not verify, is indistinguishable from a working one until install time. It also asserts the
re-signed product carries **our** identity rather than the fixture's, since re-signing must
replace the original signature rather than inherit it.

A note on test placement, because it produced a false green: `loadPatchesFromJar` loads the
bundle through a `DexClassLoader`, which **Robolectric's sandboxed classloader does not
support**. Under Robolectric the bundle loads as *zero patches* and the test skips itself
rather than failing. That is a silent-pass hazard, so the pipeline test is a plain JVM test;
`PatchSigner` needs no `Context`, so nothing is lost. `SplitArchivesTest` is JVM-only for the
same reason (it is pure `java.util.zip`).

`BackupRollbackTest` covers the recovery path. Rollback is a stated requirement, and the
orchestrator wraps its backup write in `runCatching`, so a silent failure there would leave
the user with no way back and no warning. It pins one backup per version, newest-first
ordering for rollback, per-package scoping, exact-version lookup for repatching, storage
accounting, and that a deleted backup does not affect its siblings.

`SplitArchivesTest` covers both directions a Play-distributed app needs: merging splits into
one patchable APK (base wins on duplicate names, a ZIP with duplicate entries is invalid) and
packing a split install into one archive so a rollback restores exactly what was installed.

### APK signing is executed and its output verified

`PatchSignerTest` signs a real APK with the app's own signer and verifies the result with
`apksig` — the same library the patcher signs with. Signing is on the critical path of every
install and had never been run.

It asserts the keystore is generated, the signature verifies, v2 signing is present (the
minimum for API 24+), the signing identity is **stable across runs** (the property that makes
an update installable), two different keystores do not share an identity, and failures return
`false` rather than throwing.

Running it found a defect that would have broken **every install**:

> `ensureKeyStore()` called `MorpheApkSigner.writeKeyStore(keystoreFile.outputStream().buffered(), ...)`
> and never closed the stream. A buffered stream that is only abandoned is never flushed, so
> the keystore file stayed **0 bytes**. Every later read then failed with
> `Unrecognized keystore format`, and since `sign()` swallows the cause into `Log.e`, the run
> would have failed with no explanation.

The test asserts the **round trip** — that Morphe's own `readKeyStore` can read back what its
own `writeKeyStore` produced — which is what catches this class of defect. A test that only
checked "the file is non-empty" would have passed on a flushed-but-unreadable file.

Diagnosing it also established a fixture requirement worth recording: `ApkUtils.signApk`
reads `minSdkVersion` from the **binary** manifest, so a placeholder manifest fails with
`MinSdkVersionException: malformed binary resource`. The test therefore signs
`testdata/target.apk`, the real compiled APK, rather than a synthetic zip.

### The dependency graph resolves, and the root-mount shell is inspected

Two more "compiles fine, fails on the device" areas are now covered.

`DependencyGraphTest` boots the real Koin graph and asks for every type the app asks for.
A DI graph is the one thing that can be entirely broken while all unit tests pass, because
each collaborator is constructed by hand in tests; a missing binding only surfaces when a
screen is first opened. It also asserts that collaborators are singletons, since a second
`PatchEngine` or database instance would silently split state between the coordinator and
the repository.

`RootModuleScriptsTest` inspects the shell that is written into `/data/adb/modules/...`.
This is the riskiest output the app produces and it cannot be exercised without root, so it
is verified by inspection:

* the module id is a legal directory name and cannot contain a path separator;
* `module.prop` carries every key the root manager needs, and a value containing a newline
  cannot inject an extra key;
* `service.sh` keeps the version guard — it compares the installed version and exits
  instead of mounting a mismatch, because a patched DEX over a different resource table
  crashes at launch;
* every interpolated value is single-quoted, and `quote()` escapes an embedded quote;
* the apply-now command exits non-zero when the stock path or APK is missing rather than
  mounting nothing.

Extracting that generator out of `RootMountInstaller` was necessary to test it at all, and
it immediately paid for itself: `moduleId` replaced only `.` and `-`, so a package name
containing `/` would have produced a module path that escaped `/data/adb/modules`. Package
names on a device are not attacker-controlled in a practical sense, but the invariant is
now explicit and enforced by sanitizing everything outside `[A-Za-z0-9_]`.

### The LSPosed bridge is verified to actually connect

`LsposedBridgeContractTest` exists because the bridge was **silently broken** and nothing
caught it until it was traced by hand. Three independent mismatches each dropped every event:

1. the module sent `dev.morphxposed.LSPOSED_PACKAGE_EVENT`, which the receiver did not filter for;
2. the receiver demanded a `package:` data URI, and the module's intent carries none, so it
   returned before doing anything;
3. the extra was named `event` on one side and `action` on the other.

Fixed by giving the receiver an explicit bridge route keyed on the action, reading the package
from its extra, and pinning both sides to one documented contract. The bridge is now also
guarded by a **signature-level permission** (`dev.morphxposed.permission.BRIDGE`), so only an
APK signed with this app's key — in practice only the events module — can drive it. Verified in
the built manifests: the app declares the permission at `protectionLevel=signature` and the
module requests it.

The tests pin the wire format (action, both extra names, permission) so a rename on either side
fails a test instead of silently orphaning the other APK, and they confirm a bridge removal
reaches the receiver and drops the row while malformed and unknown-name intents are ignored.

`verify-apk.ps1` was also extended to catch the class of defect this exposed: the declared
receiver must resolve to a real class *and* the manifest must declare the bridge permission and
action, so a regression here fails the release build.

### Earlier Robolectric coverage

`DatabaseAndScannerTest` and `SettingsAndScannerTest` run the real Android
framework on the JVM, so Room schema creation, DAO queries, `DataStore`
read/write, `PackageManager` interaction and Application startup are genuinely
executed. They assert:

* the shipped defaults are safe — automatic patching is **off** for both install and update,
  and the installer defaults to the unprivileged session strategy;
* written settings persist and are read back;
* the check interval is clamped (0h -> 1h, 10000h -> 168h);
* the GitHub token is stored trimmed;
* `reconcile` preserves user-owned state (auto-patch opt-in, selected patches, last-patch
  record, mount flag) across a rescan while refreshing the detected version;
* `reconcile` removes apps that are no longer installed;
* the official source cannot be deleted while a third-party one can;
* `markDownloaded` clears a previous error and stores bundle metadata;
* the skip-if-unchanged rule compares the bundle *actually used*, and a null checksum never
  matches a real one.

### Bugs these tests found

Six real defects were caught by adding these harnesses, all of which would have shipped:

1. **`startKoin` was not idempotent.** Recreating the `Application` threw
   `KoinApplicationAlreadyStartedException`, crashing startup. Fixed by guarding on
   `GlobalContext.getOrNull()`.

2. **`currentBundleSha()` returned the wrong bundle.** It read whichever source was listed
   first, so the skip-if-unchanged guard could skip a repatch even though the bundle that
   patches that app had changed. `resolveBundle` now returns a `ResolvedBundle` carrying its
   own source id, checksum and tag.

3. **`PatchEvent.Done` implied installation succeeded** even when the installer was
   unavailable or failed, so an exported-only APK was reported as installed. `Done` now
   carries `installed`/`pendingInstall`, and such runs are recorded as `PATCHED`.

4. **`RootShell.pushFile` buffered the whole APK twice.** It called `readBytes()` and also
   wrote a redundant temporary copy, so copying a 100 MB patched APK transiently needed over
   200 MB — enough to OOM a low-end phone mid-install. It now streams the file into the root
   shell and drains the child's output so it cannot block on a full pipe.

5. **`moduleId` did not sanitize path separators.** It replaced `.` and `-` only, so a package
   name containing `/` would have produced a module directory outside `/data/adb/modules`.
   Now everything outside `[A-Za-z0-9_]` is replaced.

Additionally, the bridge verification added in the previous round was itself too strict: its
`PackageChangeReceiver`-to-`permission` match allowed only 400 characters between the two
attributes, so a legitimate manifest failed the check. The window was widened rather than the
app being changed to satisfy a brittle test.

6. **The signing keystore was written empty.** `ensureKeyStore()` never closed its buffered
   stream, so nothing was flushed and the file stayed 0 bytes. Every install would have failed
   at signing with `Unrecognized keystore format`, with the cause swallowed into `Log.e`.
   Fixed by using the stream and asserting it is non-empty.

### A real bug found earlier by the bundle diagnostics

An early version of `CompatibilityResolver` reported *any* version as `SUPPORTED` whenever
the bundle contained **universal** patches (signature/installer spoofing), since those apply
to every app. Combined with automation, that would have patched arbitrary unsupported
versions of YouTube.

`BundleShapeDiagnosticsTest` printed `resolver says for bogus version: SUPPORTED`, which
exposed it. Fixed by adding `Compatibility.UNIVERSAL_ONLY`: universal patches are offered,
but they do not make a version *supported*, and the orchestrator's gate
(`status == SUPPORTED`) still refuses to auto-patch. Three regression tests cover it.

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

## 6. Artefact verification (no device required)

Every release artefact is checked before it is considered done, because these catch the
failures that otherwise only appear as a crash on launch.

```
$ apksigner verify dist/MorphXposed.apk ; echo $?
0                      # exit 0 == verified

$ apksigner verify --verbose dist/MorphXposed.apk
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
```

```
$ ./scripts/verify-apk.ps1 -Apk dist/MorphXposed.apk
Package: dev.morphxposed
Declared classes: 4, dex classes: 51728
OK: every manifest component resolves to a class in the dex

$ ./scripts/verify-apk.ps1 -Apk dist/MorphXposed-Events.apk
Package: dev.morphxposed.events
Declared classes: 1, dex classes: 1127
OK: every manifest component resolves to a class in the dex
```

The components checked are `MorphXposedApp`, `MainActivity`,
`PackageChangeReceiver`, `InstallResultReceiver` (main app) and `ModuleInfoActivity`
(events module). A renamed or moved class with a stale manifest entry would fail here
rather than at runtime.

Both checks are wired into `scripts/build-release.ps1`, so a release build cannot succeed
with an unsigned or structurally broken APK.

---

## 7. What was *not* tested, and why

**On-device execution.** No Android device, and no emulator was possible: the host reports
`VirtualizationFirmwareEnabled: False` and
`SecondLevelAddressTranslationExtensions: False` (no hardware virtualisation), and
`HypervisorPlatform` is disabled, so the Android Emulator cannot run with acceleration
here.

Robolectric closes much of that gap by running real Android framework code on the JVM
(section 3): composition and click handling for all six screens, WorkManager scheduling,
Room, DataStore, `PackageManager`, and Application startup are genuinely executed. What it
still cannot prove, and what remains **unverified**:

* the session installer's `PendingIntent` round-trip through a real `PackageInstaller`
  (the session is created and committed, but the platform's confirmation UI and result
  callback need a device),
* root-mount module mounting at boot by a real root manager,
* the LSPosed module hooking `PackageManagerService` on a live framework,
* how the Compose screens look pixel-for-pixel on real hardware.

Those paths are implemented against the documented platform APIs, and the manifest and dex
were verified to agree (section 6), but "compiled, packaged, composed and statically
verified" is a weaker claim than "ran on a device", and is reported as such rather than
overstated.

**Patching a real YouTube/Reddit APK.** Those APKs are copyrighted and were not downloaded.
The engine was instead proven on a synthetic APK (section 4), which exercises the same code
path without redistributing anyone's app.

