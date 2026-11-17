# Releasing

Releases are built and published by GitHub Actions. You do not need a local Android SDK to cut a
release, but you do need the signing key.

## Cutting a release

```bash
git tag v1.0.0
git push origin v1.0.0
```

That triggers `.github/workflows/release.yml`, which:

1. builds both APKs with `scripts/build-release.ps1` on a Linux runner;
2. verifies them - signed, every manifest component present in the dex, the LSPosed bridge contract,
   the module's settings actions, the executed root-mount scripts, and no leftover pre-rename
   package references;
3. refuses to publish if any of that fails or if either APK is unsigned;
4. attaches `MorpheXposed.apk` and `MorpheXposed-Events.apk` to a GitHub Release.

You can also run it manually from the Actions tab (`workflow_dispatch`); a manual run builds and
uploads artifacts but only creates a Release when the ref is a tag.

## Required secrets

Add these under **Settings → Secrets and variables → Actions**. Without them the workflow still
builds, but the *Confirm the APKs are signed* step fails, so nothing is published.

| Secret | What it is |
|---|---|
| `KEYSTORE_BASE64` | `keystore/release.jks`, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias inside the keystore |
| `KEY_PASSWORD` | key password |

To produce `KEYSTORE_BASE64`:

```bash
base64 -w0 keystore/release.jks     # Linux
[Convert]::ToBase64String([IO.File]::ReadAllBytes("keystore\release.jks"))   # Windows
```

On Windows, copy the result with `Set-Clipboard` rather than by hand: a wrapped or truncated base64
string decodes to a corrupt keystore, and the failure appears later as "keystore password was
incorrect".

### The alias and password are not the project name

The keystore's alias and password still read `morphxposed`. They are properties of the key, not of
the project, and were deliberately left unchanged by the rename - changing them would have
invalidated every signature the app has produced.

If you regenerate the keystore with `scripts/gen-keystore.ps1`, it produces the same alias and
password, so `KEY_ALIAS=morphxposed` and `KEYSTORE_PASSWORD=morphxposed-release` stay correct.

## What the workflow deliberately does not do

* **It does not run the tests.** `scripts/run-tests.ps1` downloads the 16 MB Morphe bundle and takes
  several minutes; the release job fetches the bundle only so the pipeline's own verification has
  what it needs. Run `./scripts/run-tests.ps1 -WithBundle` before tagging if you want that gate.
* **It does not build on Windows.** The scripts are cross-platform, but the runner is Linux because
  that is cheaper and has `bash` available for the root-script execution step. PowerShell 7 is
  preinstalled on `ubuntu-latest`, so nothing installs it.
* **It does not sign with a throwaway key.** A release that changes signature between runs cannot
  update an installed app.

## If the workflow fails

| Symptom | Cause |
|---|---|
| `keystore password was incorrect` | `KEYSTORE_BASE64` decoded to something other than the original file; re-encode with a single-line output |
| `is not signed` | the signing secrets are absent |
| `no references to the pre-rename package remain` | a `dev.morphxposed` string survived somewhere; the check names the entry it was found in |
| `does not declare ...` | a manifest intent filter or permission was removed |
| shell verification non-zero | the generated root-mount scripts regressed; run `scripts/verify-root-scripts.sh` locally |
| `Could not find package android-37` | the SDK package id is `platforms;android-37.0`; check `sdkmanager --list` before changing it |
| `gradlew: Permission denied` | the executable bit was lost; `git update-index --chmod=+x gradlew` |
