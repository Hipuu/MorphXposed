# MorphXposed — Architecture

This document records what the Morphe ecosystem actually does, why the original
brief's premise needed correcting, and the architecture this project implements
as a result.

## 1. What Morphe actually is

Morphe is an Android **app patcher**. It is distributed by the
[MorpheApp](https://github.com/MorpheApp) organisation and consists of:

| Component | Repository | Role |
|---|---|---|
| Morphe Patcher | [`morphe-patcher`](https://github.com/MorpheApp/morphe-patcher) | Library that applies patches to an APK |
| Morphe Patches | [`morphe-patches`](https://github.com/MorpheApp/morphe-patches) | The shipped patch bundle (`patches-<version>.mpp`) |
| Morphe (Manager) | [`morphe-manager`](https://github.com/MorpheApp/morphe-manager) | On-device Android patching app |
| Morphe Desktop | [`morphe-desktop`](https://github.com/MorpheApp/morphe-desktop) | CLI + GUI desktop patcher |
| Morphe Library | [`morphe-library`](https://github.com/MorpheApp/morphe-library) | Signing, install, and shared utilities |

Morphe is a hard fork of the ReVanced lineage. It is **not** affiliated with
ReVanced, and it is **not** an Xposed/LSPosed project.

### How patches are applied

Morphe patches are **compile-time transformations of an APK**, not runtime
hooks:

1. **Load** a patch bundle (`.mpp`) — a JAR/DEX container of patch definitions.
2. **Decode** the target APK: DEX is disassembled with
   [smali](https://github.com/MorpheApp/smali), resources are decoded with
   [ARSCLib](https://github.com/MorpheApp/ARSCLib).
3. **Locate** target code using *fingerprints* — structural matchers over method
   access flags, return types, parameters, strings, and instruction sequences.
4. **Rewrite** the DEX bytecode / resources in place.
5. **Repackage, align, and sign** a new APK.
6. **Install** it (session installer, root mount, or Shizuku).

Verified directly from the official source:

```kotlin
// morphe-patcher/docs/1_patcher_intro.md
val patches = loadPatchesFromJar(setOf(File("patches.mpp")))
val patcher = Patcher(PatcherConfig(apkFile = File("some.apk")))
patcher += patches
patcher().collect { patchResult -> /* per-patch success/failure */ }
patcher.writeApk(output)   // repackages + aligns; signing is the host's job
```

The public API dump (`morphe-patcher/api/morphe-patcher.api`) confirms the
surface used by this project: `Patcher`, `PatcherConfig`, `PatchLoader`,
`loadPatchesFromJar`, `PatcherResult`, and `writeApk(File)`.

## 2. Why the "Xposed module" premise had to be corrected

The brief asked for an app that "applies Morphe patches to supported applications
using the Xposed/LSPosed framework". **That specific mechanism does not exist.**

- Xposed/LSPosed hooks methods **inside a running process**. It can only alter
  behaviour at runtime, for code that is already installed.
- Morphe patches **change the installed artifact itself**: they rewrite DEX
  bytecode, decode and re-encode `resources.arsc`, edit the manifest, and add
  extension DEX plus native libraries. The result must be **re-signed and
  (re)installed**.
- A module cannot re-sign or replace an APK on behalf of another app. That
  requires `INSTALL_PACKAGES` (system-only), an installer session with user
  consent, or root.
- There is no Xposed API that applies an `.mpp` bundle. Inventing one would mean
  shipping a fake API — explicitly forbidden by the brief.

**Consequence:** an Xposed module can never be the patch applicator. Morphe's own
manager does not use Xposed at all; it patches and installs APKs directly.

## 3. Role LSPosed *does* legitimately play

Xposed is still useful for something the platform genuinely makes hard:
**reliably learning that a supported app was installed or updated**, and
**reading its live state**.

Since Android 8.0, `PACKAGE_ADDED` / `PACKAGE_REPLACED` are not delivered to
manifest-declared receivers of ordinary apps. A manager must otherwise poll, or
use `JobScheduler`/`WorkManager` heuristics. An LSPosed module hooked into
`system_server`'s `PackageManagerService` sees these events authoritatively and
in real time.

So MorphXposed ships **two artifacts**, connected by a signature-guarded broadcast
(`dev.morphxposed.LSPOSED_PACKAGE_EVENT`, permission `dev.morphxposed.permission.BRIDGE`):

| Artifact | Purpose |
|---|---|
| **MorphXposed** (main app) | Detect, fetch, verify, patch, install, roll back |
| **MorphXposed Events** (LSPosed module) | Optional accelerator: forwards real-time package events from `system_server` to the main app |

The main app is fully functional **without** the module. The module is a
performance/latency optimisation, never a correctness dependency, and never
touches the patching engine.

## 4. Architecture

```
┌────────────────────────────────────────────────────────────┐
│  UI (Jetpack Compose + Material 3)                         │
│  Dashboard · Apps · Patches · Automation · Logs · Settings  │
└───────────────────────────┬────────────────────────────────┘
                            │ StateFlow
┌───────────────────────────▼────────────────────────────────┐
│  ViewModels (MVVM)                                          │
└───────────────────────────┬────────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────────┐
│  Domain                                                     │
│  • PatchSourceRepository   (GitHub releases → .mpp)         │
│  • AppScanner              (PackageManager → candidates)    │
│  • CompatibilityResolver   (bundle metadata ↔ installed ver)│
│  • PatchEngine             (morphe-patcher, official)       │
│  • Installer               (session / root mount / Shizuku) │
│  • BackupManager           (original APK + rollback)        │
└───────────┬───────────────────────────┬────────────────────┘
            │                           │
┌───────────▼──────────┐   ┌────────────▼───────────────────┐
│  Data                │   │  Automation                     │
│  • Room (apps,       │   │  • WorkManager periodic check   │
│    bundles, runs,    │   │  • PackageChangeReceiver        │
│    backups)          │   │  • LSPosed event bridge (opt.)  │
│  • DataStore prefs   │   │  • Foreground service           │
│  • OkHttp + cache    │   └─────────────────────────────────┘
└──────────────────────┘
```

### Dependency injection

Koin. Chosen over Hilt because AGP 9.x + KSP 2.3.x is the combination Morphe
Manager itself validates, and Koin avoids a second annotation processor in that
matrix.

### Patching engine

**The official `app.morphe:morphe-patcher` is used directly. No patch logic is
reimplemented.** This is enforced at compile time by `PatcherLinkProbe`, so the
build fails if the engine cannot be linked rather than failing on a device.

Patch execution runs in a **separate `:patch` process** with a foreground
service, so:
- a patcher OOM or crash does not take down the UI,
- Android can reclaim the heavy process independently,
- the UI keeps streaming progress over a `Messenger`.

This mirrors why Morphe Manager runs patching out-of-process.

### Install strategies

| Strategy | Requirement | Notes |
|---|---|---|
| **Session installer** | `REQUEST_INSTALL_PACKAGES` | Works unrooted. Signature change forces uninstall of the stock app, which loses its data. |
| **Root mount** | Root + Magisk/KernelSU/APatch | Patched APK is bind-mounted over the stock one at boot via a generated Magisk module. Stock signature and data are preserved. This is the recommended path and what Morphe calls "Root mount mode". |
| **Shizuku** | Shizuku/Sui running | Install/PM operations via `Shizuku` where available. |

The root-mount module format is taken from Morphe Manager's own generator
(`app/src/main/assets/root/{module.prop,post-fs-data.sh,service.sh}`), so the
produced module behaves identically to one Morphe itself would create.

### Security posture

- Bundles are downloaded **only** from allow-listed official sources by default;
  third-party sources must be added explicitly.
- Every download is checked against the release's published **SHA-256**.
- Bundles are verified as valid ZIP/JAR containers with a readable manifest
  before being cached, and are written atomically (staging file + rename) so a
  partially written bundle is never loaded.
- **No downloaded script is ever executed.** Only data (`.mpp` containers)
  and APKs are read.
- Root is requested *only* when the user selects the root-mount installer.
- Nothing outside the explicitly selected package list is ever modified.

## 5. What this project does *not* do

- It does not reimplement Morphe's patching logic, fingerprinting, or resource
  editing.
- It does not fabricate a "Morphe Xposed API".
- It does not silently install or modify unrelated apps.
- It does not claim Xposed can apply patches.
