# MorphXposed

Automatic Morphe patching for Android, with optional LSPosed acceleration.

MorphXposed detects supported apps on your device, fetches the official Morphe patch
bundle (or any Morphe-compatible source), patches the app with **Morphe's own patching
engine**, signs it, installs it, and repeats automatically whenever an app or a patch
bundle changes.

> **Patches are applied by rewriting the APK — not by Xposed hooks.** See
> [Why not an Xposed patcher](#why-not-an-xposed-patcher) below. This is not a caveat;
> it is how Morphe works.

---

## What it does

| Feature | Detail |
|---|---|
| **Automatic app detection** | Scans installed packages and matches them against each bundle's declared compatibility. Only apps a bundle can actually patch are ever shown or touched. |
| **Patch management** | Resolves GitHub releases, downloads the `.mpp` bundle, verifies its **SHA-256** against the published digest, validates the container, and caches it atomically. |
| **One-click patching** | Patches with the official engine, signs with a stable keystore, installs, and records the run. |
| **Full automation** | `WorkManager` periodic checks plus package-change handling; per-app opt-in; never repatches when the app version *and* bundle are unchanged. |
| **Rollback** | Original APK/split set is backed up before patching, keyed by version code. |
| **LSPosed integration** | Optional companion module forwards real-time package events. The app works fully without it. |
| **Six-screen Compose UI** | Dashboard, Installed apps, Patches, Automation, Logs, Settings — Material 3, dark mode. |

## Supported apps

Whatever the installed patch bundles declare. The official Morphe bundle supports
**YouTube**, **YouTube Music**, and **Reddit**; community sources add more.

---

## Why not an Xposed patcher

The usual premise — "apply Morphe patches through Xposed" — cannot be implemented,
and building it would mean shipping a fake API.

* **Xposed hooks a running process.** It changes behaviour at runtime, for code that is
  already installed.
* **Morphe patches the artifact.** It disassembles DEX with smali, rewrites bytecode and
  `resources.arsc`, edits the manifest, merges extension DEX, then **re-signs and
  reinstalls** the APK. The output is a different file with a different signature.
* **A module cannot re-sign another app's APK.** That needs `INSTALL_PACKAGES` (system
  only), an installer session with user consent, or root.
* **There is no Xposed API for `.mpp` bundles.** Morphe is not an Xposed project — it is
  a ReVanced-lineage APK patcher, and its own manager uses no hooks at all.

So MorphXposed does the patching the way Morphe does, and uses LSPosed only for the thing
the platform genuinely makes hard: **knowing that a package changed**.

### What the LSPosed module actually does

Since Android 8.0, `PACKAGE_ADDED` / `PACKAGE_REPLACED` are not delivered to
manifest-declared receivers of ordinary apps. Without privilege you can only poll.

`MorphXposed Events` hooks `PackageManagerService` inside `system_server`, scoped to
`android` only, and forwards events to the app. It never modifies any app, carries no
patcher, and is not required — the scheduled check is the guaranteed path.

---

## Documentation

* [REQUIREMENTS.md](REQUIREMENTS.md) - every requirement in the project brief, mapped to the code
  that implements it and the test or build check that proves it. Read this if you want to know
  what is verified and what is not.
* [ARCHITECTURE.md](ARCHITECTURE.md) - how Morphe patching actually works and why the app is
  built this way.
* [INSTALL.md](INSTALL.md) - installation, LSPosed activation and scope.
* [TESTING.md](TESTING.md) - test results and the bugs each harness found.

---

## Requirements

* Android 8.0+ (API 26), targeting API 37
* For **root mount** installs (recommended): Magisk, KernelSU, or APatch
* For unrooted use: the session installer (see the data-loss note below)

### Installer trade-offs

| Strategy | Root | Preserves data | Notes |
|---|---|---|---|
| **Root mount** | required | **yes** | Patched APK is bind-mounted over the stock app at boot via a generated module. Stock signature and data survive. |
| **Session installer** | no | **no** | The patched APK is signed with MorphXposed's key, so Android forces an uninstall of the stock app first, clearing its data. |
| **Shizuku** | no | depends | Uses Shizuku when running; falls back to the session installer. |

---

## Building

### Prerequisites

* JDK 21+
* Android SDK with **platform 37** and **build-tools 37.0.0**
* A GitHub token with **`read:packages`** — Morphe publishes `morphe-patcher` and
  `morphe-library` to GitHub Packages, which requires auth even for public artifacts.

```properties
# local.properties
sdk.dir=/path/to/android-sdk
gpr.user=your-github-username
gpr.key=ghp_xxxxxxxxxxxxxxxxxxxx
```

Or set `GITHUB_ACTOR` / `GITHUB_TOKEN` in the environment.

### Build

```bash
./gradlew :app:assembleDebug     # debug APK
./scripts/run-tests.ps1          # every suite, always re-run (see below)
./scripts/build-release.ps1      # signed release APKs into dist/
```

### Running the tests

The suites that use the official Morphe bundle skip themselves when
`testdata/patches.mpp` is absent, and **a skip is not a pass**. Fetch the fixture first if you
want the full run:

```bash
./scripts/fetch-testdata.ps1     # downloads + checksum-verifies the bundle
./scripts/run-tests.ps1 -WithBundle
```

`run-tests.ps1` always passes `--rerun-tasks`. Gradle will otherwise replay cached results and
report success without executing anything, which makes a "0 skipped" summary meaningless.

Generate a signing keystore once with `./scripts/gen-keystore.ps1`. The keystore is
**never committed** — it is the app's update identity.

### Outputs

```
dist/MorphXposed.apk           the main app
dist/MorphXposed-Events.apk    optional LSPosed module
```

---

## Usage

1. **Install** `MorphXposed.apk`.
2. **Home** → the official Morphe source is seeded; tap the sync icon to fetch the bundle.
3. **Apps** → supported apps appear with a compatibility verdict. Enable **Auto** on the
   apps you want handled automatically.
4. **Settings → Installer** → choose *Root mount* if rooted.
5. **Patch** an app from its row, or let automation run.
6. **Automation** → configure triggers and the check interval.

### Optional: real-time events

1. Install `MorphXposed-Events.apk`.
2. Open **LSPosed Manager** → enable **MorphXposed Events**.
3. Set its scope to **System Framework** (`android`) only.
4. Reboot or restart the system framework.

---

## How it works

```
UI (Compose) → ViewModel → Domain → { Room, DataStore, OkHttp }
                                   → Morphe patcher (official)
                                   → Installer (session / root mount)
Automation: WorkManager + package events → PatchCoordinator → PatchOrchestrator
```

A run is: scan the installed app → resolve a compatible bundle → verify compatibility →
snapshot the original → run Morphe's patcher → sign → install → record.

`PatchEngine` contains **no patch logic**. It wires
`app.morphe:morphe-patcher` (`loadPatchesFromJar` → `PatcherConfig` → `Patcher` →
`writeApk`) and streams the engine's own results. `PatcherLinkProbe` fails the *build* if
the engine cannot be linked, so the app can never silently substitute its own patching.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full research notes.

## Security posture

* Bundles come from allow-listed sources; third-party sources are added explicitly.
* Every download is checked against the release's published SHA-256.
* Bundles are validated as readable patch containers and cached atomically — a corrupt
  download never replaces a working one.
* **No downloaded script is ever executed.** Only `.mpp` data and APKs are read.
* Root is requested only when the root-mount installer is selected.
* Nothing outside the apps you selected is ever modified.

## Project layout

```
app/      main application
xposed/   optional LSPosed events module (separate APK)
scripts/  keystore generation, release build
testdata/ synthetic APK + bundle used by the integration tests
```

## Licence and attribution

MorphXposed uses Morphe's official libraries:

* [`morphe-patcher`](https://github.com/MorpheApp/morphe-patcher) — GPL-3.0
* [`morphe-library`](https://github.com/MorpheApp/morphe-library) — GPL-3.0

Both are GPL-3.0, so this project is distributed under **GPL-3.0** as well.

MorphXposed is an independent project. It is **not** affiliated with or endorsed by the
Morphe team, and it does not use Morphe's name or logos as its own branding, per the
Morphe licence's Section 7 branding conditions.
