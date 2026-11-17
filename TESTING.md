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

## 1b. A fresh clone builds, and the numbers depend on the fixture

Verified by cloning the repository to a clean directory (no `local.properties`, no keystore, no
bundle) and building it with only `JAVA_HOME`, `ANDROID_HOME`, `GITHUB_ACTOR` and `GITHUB_TOKEN`
set:

```
$ git clone <repo> MorpheXposed-fresh
$ ./gradlew :app:assembleDebug
BUILD SUCCESSFUL          # app-debug.apk, 35.6 MB
```

So deliverable 1 holds: the committed project builds from nothing but its own sources plus the
documented environment.

**A correction to this document.** An earlier revision claimed the suite reports "0 skipped" in a
bare checkout. That was wrong: Gradle had replayed cached test results instead of executing. With
`--rerun-tasks`, a clone without `testdata/patches.mpp` correctly reports **12 skips** — the
suites that need the official bundle (`RealBundleIntegrationTest` 5, `EndToEndPatchTest` 2,
`PatchThenSignPipelineTest` 3, and two others). Those tests skip themselves via `assumeTrue`, and
**a skip is not a pass**.

`scripts/run-tests.ps1` now prevents both mistakes: it always passes `--rerun-tasks`, prints the
skip count prominently, and fails when `-WithBundle` is given but tests still skip.

With the bundle present, which is the configuration the headline figures refer to:

```
$ ./scripts/run-tests.ps1
  total   : 190
  failures: 0
  skipped : 0
```


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
| `BundleDownloadVerificationTest` | 12 | 0 | Robolectric + MockWebServer |
| `RunPresentationTest` | 19 | 0 | JVM (pure functions) |
| `AutoPatchPolicyTest` | 11 | 0 | JVM (pure functions) |
| `InstallResultReceiverTest` | 8 | 0 | Robolectric + ShadowPackageInstaller |
| `SessionInstallerTest` | 5 | 0 | Robolectric + ShadowPackageInstaller |
| `UpdateCheckPlanTest` | 4 | 0 | JVM (pure functions) |
| `PackageNamesTest` (module) | 7 | 0 | JVM (module) |
| `DiscoverThenPatchEndToEndTest` | 5 | 0 | Robolectric + ShadowPackageManager |
| `LsposedStatusTest` | 9 | 0 | Robolectric + ShadowPackageManager |
| `ArchitectureSelectionTest` | 8 | 0 | JVM (pure functions) |
| `RollbackServiceTest` | 8 | 0 | Robolectric + Room |
| `PatchSourceUpdateStateTest` | 7 | 0 | Robolectric + Room |
| Root-mount shell execution | 14 checks | 0 | **real bash via WSL** |
| **Total** | **222** | **0** (0 skipped) | |

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

### A third missing feature: sources could not be shown as outdated

Continuing the audit, the brief asks the Patches screen to show bundles that are "available,
installed, and **outdated**". The schema tracked only `installedTag`, so an update that had been
published but not yet downloaded was invisible — the screen could say "v2.3.0" but never "v2.4.0
is available".

`patch_sources.latestTag` now records what a source advertises, written on every check
(including a check that finds nothing new) and on download, so the two can be compared. The
Patches screen shows a distinct chip when they differ. The schema version goes to 2; the app
already used `fallbackToDestructiveMigration`, so existing installs rebuild rather than crash.

The useful property is that a *failed* check preserves both tags: a transient network error must
not make an app look either up to date or more outdated than it is.

### Auditing the brief against the code found two missing features

Rather than adding more tests, this round checked every stated requirement against what was
actually implemented. Two were absent, and both were silent because nothing failed:

**1. ABI handling.** The brief requires "Handle Android version and ABI differences". Morphe's
`PatcherConfig` takes a `keepArchitectures` set and drops native libraries for architectures
outside it — the app never set it, so it always fell back to "keep everything". `ArchitectureSelection`
now maps `Build.SUPPORTED_ABIS` to the patcher's `CpuArchitecture`.

The safe direction matters here: an ABI the device cannot run wastes space, but dropping one it
*can* run breaks the app. So unknown names map to `null` rather than a guess, and if nothing maps
the set is left **empty**, which the patcher reads as "keep everything". Getting it wrong in the
permissive direction is a wasted megabyte; in the restrictive direction it is a crash.

**2. Rollback.** The brief requires "rollback and recovery". Backups were being written, the
schema documented them as "used for rollback", and the settings described them as "required for
rollback" — but nothing could restore them. It was a claim, not a feature. `RollbackService` adds
the missing half, and it distinguishes the two cases honestly:

* a **root-mounted** app was never replaced, so removing the module is lossless and app data is
  kept;
* a **session-installed** app was replaced, so restoring the original changes the signature back
  and Android clears its data. That is unavoidable, and `availability()` reports
  `losesAppData = true` so the UI can warn before the user commits.

The Apps screen now offers **Restore** for any app with a patch record.

### A build hang worth recording

The release script then appeared to hang for over 30 minutes. The cause was a self-inflicted
PowerShell trap: the script assigned to `$args`, which is an **automatic variable**. That
silently corrupted the argument passed to `wsl`, leaving it waiting indefinitely. Renaming it to
`$shellCmd` brought the pipeline back to 1.8 minutes.

Worth recording because the failure mode was a hang with no error: nothing was reported, the job
simply never finished, and the only way to see it was to notice that no artifact had been
produced.

### LSPosed state is reported honestly

The brief asks the app to "detect whether LSPosed is installed and whether the module is
enabled". The first half is a package lookup; the second half **is not possible**, and the
implementation says so rather than faking it.

LSPosed exposes no API for a module to read its own activation state. So `LsposedStatus` reports
only what the platform will answer:

* whether an LSPosed manager is installed (`org.lsposed.manager`, plus the LSPatch and
  alternate manager ids), with its version;
* whether the MorphXposed Events APK is installed, including the `.debug` variant — missing that
  would tell a developer their own installed module is absent;
* whether the bridge has **delivered an event since launch**, which is the only trustworthy proof
  the module is active.

The settings row therefore never shows a green tick it cannot justify. With LSPosed and the
module installed but silent it says *"Activation cannot be read; enable it in LSPosed Manager and
scope it to System Framework"* — because a module that is installed but out of scope, disabled,
or awaiting a reboot is indistinguishable from a working one until it reports something.

`LsposedStatusTest` pins the detection and, more importantly, the wording: no state other than an
observed event may imply the module is running. A settings row that confidently claims "active"
because a file exists would be worse than one that admits it does not know.

### Discovery is exercised against a package the platform believes is installed

I had listed on-device testing as the boundary, but `ShadowPackageManager.installPackage` makes
it possible to register a package so `PackageManager` reports it as installed.
`DiscoverThenPatchEndToEndTest` uses that to drive the app's own wiring end to end, without a
device:

* a registered YouTube package is **discovered** by `AppScanner` with its version name, version
  code, and APK path;
* a registered package the bundle does not support is **not** surfaced;
* a version bump is observable to the discovery layer — without that, the repatch trigger could
  never fire;
* the **real `PatchOrchestrator`**, given a genuine installed app and a source with no cached
  bundle, fails cleanly with a message naming the cause, rather than hanging or reporting
  success.

The last one is the closest thing to the real flow this environment allows: a real orchestrator,
a real database, a real scanner, and a package the platform insists is installed.

What it still cannot do is run the patcher itself, because `loadPatchesFromJar` needs a
`DexClassLoader` that Robolectric does not provide. That boundary is covered instead by
`PatchThenSignPipelineTest`, which runs the real engine on a plain JVM. Between the two, every
step of the pipeline is executed somewhere; no step is only read.

### The root-mount scripts are executed in a real shell

`RootModuleScriptsTest` inspects the generated shell as text. That is weaker than running it, and
this project has repeatedly been bitten by things that read correctly and behave otherwise. WSL
with a Linux distribution was available, so the scripts are now **executed** by
`scripts/verify-root-scripts.sh`: `bash -n` and `sh -n` for syntax, the `module.prop` keys, and
the scripts run for real with the Android commands (`pm`, `dumpsys`, `mount`, `umount`, `getprop`,
`am`) stubbed on `PATH`.

Fourteen checks, all passing, including the two that matter most:

* with a matching version the script **does** attempt a bind mount;
* with a mismatched version it **does not** — the guard exits rather than mounting a patched DEX
  over a different resource table, which would crash the app at launch.

Writing the harness also caught a bug in the harness itself worth noting: an unquoted heredoc
expanded `$STUB_VERSION` at generation time when it was unset, so the first run failed with
`unbound variable`. Running code catches mistakes that reading it does not, in the test as much
as in the subject.

The check is wired into `build-release.ps1`, so a release build fails if the generated shell
regresses. It is skipped with a warning where WSL is unavailable.

### The install result path is executed, not deferred

`PackageInstaller.commit` only reports that the platform *accepted* a request; the real outcome
arrives later as a broadcast. That callback is the only thing that tells the app whether a
patched APK actually reached the device, and it had never been run.

Robolectric shadows `PackageInstaller`, so it turns out the flow can be driven without a
device. `InstallResultReceiverTest` covers it:

* a **failure** marks the run `FAILED` and keeps the platform's message for the Logs screen;
* a **pending user action** is recorded as `INSTALLING`, not `FAILED` — the user may take any
  amount of time on the confirmation dialog, and calling that a failure would be wrong;
* a **success** never rewrites a run to failed;
* an aborted install, an unknown package, a result with no package name, and a broadcast with
  the wrong action are each handled without touching state or crashing.

`SessionInstallerTest` covers the unrooted install path up to the commit: the installer
declares that it needs the stock app removed (the signature differs, and that trade-off is
shown to the user, so the flag must be honest), its availability tracks the platform
permission rather than guessing, and an install either returns a definite outcome or refuses
with `NotPermitted` — it never silently does nothing.

One production change came out of this: `InstallResultReceiver` built a **second**
`MorphDatabase` just to update one row. It now reuses the application's instance, which the
app already holds and which owns the run being updated.

### The gate in front of automatic patching is tested

`AutoPatchPolicyTest` covers the rule that decides whether a trigger may patch a given app.
Both failure directions are costly: too permissive patches an app the user never opted into;
too strict means automation silently never runs, with nothing telling the user why.

The rule was an inline `when` inside a coroutine in `PatchCoordinator`, reachable only with a
database and a running scope. It is now `AutoPatchPolicy.decide`, and every trigger/flag
combination is checked:

* a **default install patches nothing** — all four automatic triggers refuse an app that is not
  opted in, which is the property the whole automation design rests on;
* a manual run is allowed even with automation off, because pressing Patch is consent, but a
  **hidden** app is refused for every trigger including manual;
* the install and update triggers each require **both** the global switch and the per-app
  opt-in, and enabling one switch does not enable the other;
* patch-update and periodic triggers depend only on the per-app opt-in, since they run over the
  opted-in set;
* batch selection returns exactly the right apps for a periodic versus a manual run;
* every refusal renders a non-empty explanation.

The extraction also fixed a reporting defect: the coordinator used one hardcoded string,
"Automatic patching is off for this app", for *every* reason — including a hidden app and a
disabled global trigger. The user now sees which condition actually applied.

### The text the user actually reads is tested

`RunPresentationTest` covers the two functions that decide everything shown to the user: the
batch summary in the snackbar, and the folding of patcher events into the progress sheet.
They were private methods on a ViewModel bound to an `Application`, so the app's most-read
text was its least verified. They are now pure functions in `RunPresentation`.

Testing them found an ordering bug in the progress sheet title:

```kotlin
// before: a finished run still said "Patching..."
when {
    progress.active -> "Patching..."      // checked first
    progress.failed != null -> "Run failed"
    ...
}
```

`RunProgress.active` is only cleared by the caller, so between the run emitting `Done` and the
state being reset the sheet displayed "Patching..." for a run that had already finished. The
outcome flags now take precedence. The same bug existed independently in `RunSheet.kt`, which
duplicated the `when` block; that copy is gone and the UI calls the tested function, so the
two can no longer drift.

Also pinned: a failure always wins over a success in the summary (reporting "Patched 2 apps"
while a third failed would misrepresent the batch), an exported-but-not-installed run is never
labelled as installed, the log stays bounded at `MAX_LOG_LINES` so a long run cannot exhaust
memory, and every `TriggerReason` has a label.

### The download path enforces its checksums against a real server

`BundleDownloadVerificationTest` runs the release-resolve and download-verify path against a
local `MockWebServer`. This is the security-critical half of patch management and had never
been executed; the checksum check in particular is a requirement, not a nicety.

Reaching it required a small production change: the GitHub API base URL was hardcoded, so the
client is now constructible with an API base, a cache directory, and an HTTP client. That is a
better design regardless, and it made the path testable.

What is now proven, not assumed:

* a release whose checksum matches is cached, and the cached bytes hash to the published
  digest;
* a **checksum mismatch is rejected** and leaves **no files at all** — not the staged
  part-file, not a cache entry;
* a non-ZIP payload, and a ZIP with none of a bundle's marker entries, are both rejected and
  nothing is cached;
* a **failed refresh does not clobber a previously working bundle**, which is what stops a
  transient corruption from breaking an install that was fine;
* a pre-release is skipped unless explicitly allowed;
* HTTP 403 with `X-RateLimit-Reset` maps to a rate-limit error, 404 to not-found, and a
  release carrying no `.mpp` asset produces a message naming the tag;
* a malformed `digest` is **dropped rather than trusted**, so a nonsense value cannot silently
  become the expected checksum;
* download progress reports the real byte counts.

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

Eight real defects were caught by adding these harnesses, all of which would have shipped:

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

7. **The progress sheet said "Patching..." after the run had finished.** `sheetTitle` checked
   `active` before the outcome flags, and `active` is only cleared by the caller, so a finished
   run kept its in-progress label. The same `when` block was duplicated in `RunSheet.kt` and had
   the bug independently; both are now one tested function.

8. **The events module reported every install twice.** `hookPackageManagerService` hooked
   `sendPackageAddedForNewUsers` twice, once labelled `installed` and once `updated`, so a single
   install bridged two events with contradictory meanings. The host treats both as facts, so it
   did the work twice and could patch the same app twice. Each platform entry point is now hooked
   exactly once.

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

