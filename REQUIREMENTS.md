# Requirement traceability

Every requirement in the brief, mapped to the code that implements it and the test that proves
it. This exists because three specified features were found **missing** in rounds 21–23 while the
test suite was green: tests can only verify what exists, so they are structurally incapable of
noticing absence.

Legend for **Evidence**:

* **unit** — an automated test in `app/src/test` or `xposed/src/test`
* **shell** — executed by `scripts/verify-root-scripts.sh`
* **e2e** — a real end-to-end run against the official bundle
* **verify** — enforced by `scripts/verify-apk.ps1` during every release build
* **manual** — cannot be automated here; see "Not verifiable in this environment"

Totals are in [TESTING.md](TESTING.md).

---

## 1. Research and architecture

| Requirement | Implementation | Evidence |
|---|---|---|
| Investigate the Morphe ecosystem | [ARCHITECTURE.md](ARCHITECTURE.md) §1 — official repositories, `.mpp` bundle format, `morphe-patcher` API surface | documented from the published artifacts |
| Determine whether Morphe supports runtime hooks or requires APK modification | [ARCHITECTURE.md](ARCHITECTURE.md) §2 — patches are static rewrites (smali + resources + manifest), re-signed and reinstalled. No Xposed API exists for `.mpp` | `PatchThenSignPipelineTest`, `EndToEndPatchTest` |
| Do not invent APIs | Only published symbols are used: `loadPatchesFromJar`, `PatcherConfig`, `Patcher`, `PatchResult`, `ApkUtils.signApk`, `CpuArchitecture` | the engine compiles against `morphe-patcher` 2.4.0 and runs in `EndToEndPatchTest` |
| Choose the correct architecture | Xposed is used **only** to forward package events from `system_server`; patching is out-of-process | `LsposedBridgeContractTest` |

## 2. Automatic app detection

| Requirement | Implementation | Evidence |
|---|---|---|
| Scan installed applications | `AppScanner.scan` | `DatabaseAndScannerTest`, `DiscoverThenPatchEndToEndTest` (a package the platform reports as installed) |
| Identify applications supported by Morphe | `CompatibilityResolver` against real bundle metadata | `CompatibilityResolverTest` (11), `RealBundleIntegrationTest` |
| Display package names, versions, compatibility | `AppsScreen`: `"${versionName} · ${packageName}"` and a `DetailRow("Package", …)` | `ScreenCompositionTest` |
| Automatically detect newly installed supported apps | `PackageChangeReceiver` → `PatchCoordinator` on `APP_INSTALLED` | `SourceAndReceiverTest`, `AutoPatchPolicyTest` |
| Automatically detect updates | same, on `APP_UPDATED`, plus LSPosed bridge | `LsposedBridgeContractTest`, `DiscoverThenPatchEndToEndTest` ("a version change is visible") |

## 3. Automatic patch management

| Requirement | Implementation | Evidence |
|---|---|---|
| Retrieve patches from official sources | `PatchSourceRepository.refresh` → GitHub Releases API, seeded with the official source | `SourceAndReceiverTest`, `RealBundleIntegrationTest` |
| Check compatibility against installed versions | `CompatibilityResolver`; `UNIVERSAL_ONLY` prevents an unsupported version from resolving as supported | `CompatibilityResolverTest` (3 regression tests for the universal-patch bug) |
| Download and verify patch files | `PatchSourceClient` — SHA-256 of the release asset, atomic staging, container validation | `BundleDownloadVerificationTest` (12, MockWebServer) |
| Maintain a local patch cache | `PatchSourceClient` bundle directory; two bundles kept per source, older ones pruned | `BundleDownloadVerificationTest` |
| Automatically detect new patch releases | `UpdateCheckWorker` → `PatchCoordinator.checkForPatchUpdates`, notifies on change | `UpdateCheckPlanTest`, `WorkSchedulingTest` |
| Bounded storage | `MAX_RUNS_RETAINED` for history, `KEEP_BUNDLES` for cached bundles | `RunRetentionTest` |
| Support rollback and recovery | `RollbackService` (restores the retained original APK) | `RollbackServiceTest` (8) |
| Show available / installed / **outdated** | `patch_sources.latestTag` vs `installedTag`, distinct chip in `PatchesScreen` | `PatchSourceUpdateStateTest` (7) |

## 4. One-click and automatic application

| Requirement | Implementation | Evidence |
|---|---|---|
| Option to automatically apply compatible patches | `AutomationScreen` + `SettingsStore` flags | `SettingsAndScannerTest`, `ScreenCompositionTest` |
| Apply on install or update | `PatchCoordinator.runBatch(TriggerReason.APP_INSTALLED/APP_UPDATED)` | `AutoPatchPolicyTest` |
| Avoid unnecessary repatching | skip-if-unchanged compares `lastPatchedVersion` **and** `lastPatchedBundleSha256` against the resolved bundle | `OrchestratorLogicTest` |
| Display detailed progress and errors | `PatchEvent` → `RunPresentation` → `RunSheet` | `RunPresentationTest` (19) |

## 5. LSPosed integration

| Requirement | Implementation | Evidence |
|---|---|---|
| Implement a module if runtime hooks are appropriate | `xposed/` — hooks `PackageManagerService` in `system_server` | `LsposedBridgeContractTest`, built and verified as a second APK |
| Provide activation and scope guidance | `ModuleInfoActivity`, now reachable **from LSPosed Manager** via `OPEN_MODULE_SETTINGS` and `MODULE_SETTINGS` intent filters; also in [INSTALL.md](INSTALL.md) | `verify` checks the intent filters exist in the built APK |
| Detect whether LSPosed is installed | `LsposedStatus` — package lookup for `org.lsposed.manager` and forks | `LsposedStatusTest` (9) |
| The events bridge can be switched off | `lsposedBridgeEnabled` gates bridge events only; platform broadcasts unaffected. Toggle in Settings | `BridgeToggleTest` (8) |
| Detect whether the module is enabled | **Not possible.** LSPosed exposes no API for a module to read its own activation state. The app reports whether an event has actually arrived, which is the only trustworthy signal, and says "cannot be read" otherwise | `LsposedStatusTest` — "no state may imply the module is running without an observed event" |
| Never assume LSPosed can modify APKs or install | The module only broadcasts; it has no patching code and cannot install | module manifest has no install permission; `LsposedBridgeContractTest` |

## 6. User interface

| Requirement | Implementation | Evidence |
|---|---|---|
| Dashboard | `DashboardScreen` | `ScreenCompositionTest` |
| Installed Apps | `AppsScreen` | `ScreenCompositionTest` |
| Patches | `PatchesScreen` | `ScreenCompositionTest` |
| Automation | `AutomationScreen` | `ScreenCompositionTest` |
| Logs | `LogsScreen` | `ScreenCompositionTest` |
| Settings (repositories, intervals, backups, **permissions**) | `SettingsScreen` | `ScreenCompositionTest`, `PermissionStatusTest` (7) |
| Material 3 | `MorphXposedTheme`, `MaterialTheme` throughout | `ScreenCompositionTest` (14, renders every screen) |
| Dark mode | `darkTheme` follows the system setting | `ScreenCompositionTest` |
| Clear status indicators | `StatusChip` used for patch state, LSPosed state, permissions | `ScreenCompositionTest` |

## 7. Automation

| Requirement | Implementation | Evidence |
|---|---|---|
| Periodically check for patch updates | `UpdateCheckWorker`, interval clamped to WorkManager's minimum | `WorkSchedulingTest` (8), `UpdateCheckPlanTest` |
| Detect install and update events | `PackageChangeReceiver` (bridge + platform) | `SourceAndReceiverTest` |
| Automatically process eligible apps | `AutoPatchPolicy` — every trigger/flag combination | `AutoPatchPolicyTest` (11) |
| Retry failed operations safely | bounded at 3 attempts, then waits for the next schedule | `UpdateCheckPlanTest` |
| Notify when patches are applied | `Notifications.patchSucceeded` | `NotificationsTest` |
| Notify when intervention is needed | `Notifications.actionRequired` on patched-but-not-installed | `NotificationsTest` |
| Bounded storage | run history retained at `MAX_RUNS_RETAINED`, cached bundles at `KEEP_BUNDLES` | `RunRetentionTest` (6) |

## 8. Security and reliability

| Requirement | Implementation | Evidence |
|---|---|---|
| Verify downloaded artifacts | SHA-256 against the GitHub release digest before use | `BundleDownloadVerificationTest` |
| Prefer official repositories and signed releases | official source seeded and **cannot be removed** | `SourceAndReceiverTest` |
| Never execute untrusted downloaded scripts | `.mpp` files are loaded as DEX through `loadPatchesFromJar`; no `sh`, no `Runtime.exec` on downloaded content | no such call exists in `app/src/main` |
| Request root only when necessary | `RootMountInstaller` is opt-in; session install is the default | `OrchestratorLogicTest` |
| Do not modify unrelated applications | target must resolve `Compatibility.SUPPORTED` before any write | `CompatibilityResolverTest`, `OrchestratorLogicTest` |
| Maintain backups and rollback | `backupOriginal` writes the original APK (or `.apks` for splits) | `BackupRollbackTest` (8), `RollbackServiceTest` |
| Handle version differences | `VersionComparator` for Morphe version strings; minSdk 26 means every supported device has the platform APIs used. Patches declare app versions, not device SDKs, so there is no device-compatibility verdict to give (see below) | `VersionComparatorTest` (4) |
| Handle ABI differences | `ArchitectureSelection` → `PatcherConfig.keepArchitectures`, empty set = keep everything | `ArchitectureSelectionTest` (8) |

## 9. Technical requirements

| Requirement | Implementation | Evidence |
|---|---|---|
| Kotlin | all source | build |
| Jetpack Compose | all six screens | `ScreenCompositionTest` |
| MVVM / clean architecture | `ui/`, `domain/`, `data/` | [ARCHITECTURE.md](ARCHITECTURE.md) |
| WorkManager | `UpdateCheckWorker`, `AutoPatchWorker` | `WorkSchedulingTest` |
| Room + DataStore | `MorphDatabase` (v2), `SettingsStore` | `DatabaseAndScannerTest`, `SettingsAndScannerTest` |
| Hilt or Koin | Koin | `DependencyGraphTest` (6) |
| minSdk 26 (Android 8.0) | `minSdk = 26` | build |
| Target latest stable SDK | `compileSdk`/`targetSdk` 37 | build |

## 10. Development workflow

| Step | Status | Evidence |
|---|---|---|
| 1. Research official repositories | done | ARCHITECTURE.md |
| 2. Document the patching workflow | done | ARCHITECTURE.md §2 |
| 3. Design the architecture | done | ARCHITECTURE.md |
| 4. Implement the patch engine | done | `EndToEndPatchTest`, `PatchThenSignPipelineTest` |
| 5. Implement app detection | done | `DiscoverThenPatchEndToEndTest` |
| 6. Implement automatic patching | done, with one defect found and fixed | `OrchestratorLogicTest` |
| 7. Integrate LSPosed where appropriate | done, limited to event forwarding | `LsposedBridgeContractTest` |
| 8. Build the UI | done | `ScreenCompositionTest` |
| 9. Test on a rooted device or emulator with LSPosed | **NOT DONE** | see below |
| 10. Fix issues and produce a release APK | done | `dist/*.apk`, `verify` |

---

## Not verifiable in this environment

Step 9 of the workflow asks for testing on a rooted device or emulator with LSPosed. This machine
has no hardware virtualization (`VirtualizationFirmwareEnabled: False`,
`SecondLevelAddressTranslationExtensions: False`, `HypervisorPlatform` disabled), so no Android
emulator can run, and no physical device is attached. The following are therefore unverified:

1. The LSPosed hook executing inside a live `PackageManagerService`.
2. The `PendingIntent` confirmation dialog round-trip on a real device.
3. The root module being mounted at boot by a real root manager.
4. Pixel-level appearance of the UI on hardware.

Everything else on this page is covered by an automated test or a build-time check. Where a
requirement could not be met at all (reading module activation state), the app reports that
honestly instead of guessing — see `LsposedStatusTest`.
