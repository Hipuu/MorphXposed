# Installation and Configuration

## 1. Install the app

```bash
adb install -r dist/MorphXposed.apk
```

Minimum Android 8.0 (API 26). No root is required to use the session installer.

### Permissions the app asks for, and why

| Permission | Why |
|---|---|
| `INTERNET` | Fetch patch bundles and GitHub releases. |
| `ACCESS_NETWORK_STATE` | Honour the "Wi-Fi only" preference. |
| `REQUEST_INSTALL_PACKAGES` | Install a patched APK over the selected app. |
| `POST_NOTIFICATIONS` | Report patch success/failure. |
| `FOREGROUND_SERVICE` + `…_DATA_SYNC` | Keep a long patch run alive. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm the periodic check after reboot. |

The app declares `<queries>` for YouTube, YouTube Music, and Reddit so it can read their
installed versions. It does not request broad package-visibility beyond what patching needs.

---

## 2. First run

1. Open **MorphXposed**.
2. The **official Morphe source** (`MorpheApp/morphe-patches`) is created automatically.
3. Tap the **sync icon** on Home, or open **Patches** and refresh, to download the bundle
   (~16 MB). Its SHA-256 is checked against the release's published digest.
4. Open **Apps**. Apps the bundle can patch are listed with a compatibility verdict:
   * **Compatible** — the installed version is supported; patching will proceed.
   * **Universal only** — only app-agnostic patches (signature/installer spoofing) apply.
     Treated as *not* supported for automatic patching.
   * **Version unsupported** — the bundle lists other versions. The row shows which.
5. Choose an installer (below), then tap **Patch**.

---

## 3. Choosing an installer

**Settings → Installer**

### Root mount — recommended if rooted

The patched APK is bind-mounted over the stock app at boot by a generated root-manager
module, so the app keeps its stock signature and its data.

* Requires Magisk, KernelSU, or APatch.
* Root is requested only when you actually patch.
* The module lives at `/data/adb/modules/morphxposed_<package>/` and contains
  `module.prop`, `post-fs-data.sh`, `service.sh`, and the patched APK.
* `service.sh` refuses to mount if the stock app's version no longer matches the version
  the patch was built for — mounting a mismatched APK would crash the app.

### Session installer — works without root

Uses the standard Android package installer.

> **Data loss warning.** The patched APK is signed with MorphXposed's own key, which
> cannot match Google's signature on the stock app. Android therefore requires the stock
> app to be uninstalled before the patched one can be installed, and that clears its data.
> Use root mount if you want to keep app data.

### Shizuku

Uses Shizuku when it is running; otherwise falls back to the session installer.

---

## 4. Automation

**Automation** screen.

| Setting | Effect |
|---|---|
| **Patch newly installed apps** | When a supported app is installed *and* you enabled Auto for it, patch it. |
| **Repatch updated apps** | Same, for app updates from the store. |
| **Check for new patch bundles** | Periodic bundle refresh; a newer bundle can fix patch bugs without the app changing. |
| **Check interval** | 6h … 1 week. |
| **Notify on success/failure** | Notifications per run. |

Per-app opt-in lives on each row of **Apps** (the **Auto** switch). Nothing is patched
automatically unless you enable it for that app *and* the global trigger is on.

The app also skips a run when the app version and the bundle checksum are both unchanged,
so a check that finds nothing costs nothing.

---

## 5. Optional: the LSPosed events module

**Only needed for real-time reactions.** Without it, automation runs on the schedule and
still works.

### Why it exists

Android 8.0+ does not deliver `PACKAGE_ADDED` / `PACKAGE_REPLACED` to manifest receivers of
ordinary apps. The module observes those events inside `system_server` and forwards them.

It does **not** patch anything and does **not** modify any app.

### Install and activate

1. Install the module:
   ```bash
   adb install -r dist/MorphXposed-Events.apk
   ```
2. Open **LSPosed Manager**.
3. **Modules** → enable **MorphXposed Events**.
4. Set its scope to **System Framework** (`android`) **only**. Do not add other apps.
5. Reboot, or restart the system framework.

### Verifying it works

```bash
adb logcat | grep MorphXEvents
```

You should see `MorphXposed events module active in system_server`, then
`Forwarded <event> for <package>` lines as apps change.

The module app's launcher icon opens a screen restating the activation steps. LSPosed
provides no API for a module to read its own activation state, so the app cannot detect it
for you — this is a platform limitation, not an omission.

---

## 6. Adding a community patch source

**Settings → Patch sources → Add source**, then enter `owner/repo` (e.g.
`someuser/morphe-patches`).

Requirements for a source to work:

* it publishes a `.mpp` patch bundle as a **release asset**;
* the bundle targets apps you actually have installed.

The source is only trusted for downloads it publishes; each bundle is checksum-verified
when the release provides a digest and is structurally validated regardless.

---

## 7. Logs and troubleshooting

**Logs** lists every run with status, versions, applied patches, installer, and the
**patcher's own log output** — so a failure can be diagnosed rather than guessed at.

### Common issues

| Symptom | Cause and fix |
|---|---|
| App shows *Version unsupported* | The installed version is not one the bundle supports. Either install a supported version, or wait for the bundle to add yours. |
| *Universal only* | Only app-agnostic patches match. This is normal for an app the bundle does not specifically target. |
| Patch fails with a morphe resource error | The patch genuinely does not fit this APK. Check the Logs entry; Morphe's message names the file it expected. |
| Install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signature differs from the installed app. Uninstall the stock app first (session installer), or switch to root mount. |
| Nothing happens automatically | Check per-app **Auto** *and* the global trigger, and that the app is not hidden. |
| Root mount applied but app unchanged | The stock version changed after patching. `service.sh` refuses to mount a mismatched APK on purpose; repatch. |
| Bundle refresh fails with 403 | GitHub API rate limit. Add a token in **Settings → Advanced**. |

### Reset

Clearing app data removes settings, cached bundles, backups, and logs. Patched apps are
unaffected. For root-mount installs, delete `/data/adb/modules/morphxposed_*` and reboot.

---

## 8. Uninstalling

1. Uninstall patched apps, or for root-mount installs remove the module directory and
   reboot so the stock APK is used again.
2. Disable **MorphXposed Events** in LSPosed Manager, then uninstall both APKs.

Rollback: backups are at `files/backups/<package>/` inside the app's private storage. Use
**Apps → app → details** to see the recorded version before uninstalling anything.
