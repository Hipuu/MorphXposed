package dev.morphxposed.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.morphxposed.data.db.PatchRunEntity
import dev.morphxposed.data.db.PatchSourceEntity
import dev.morphxposed.data.db.SupportedAppEntity
import dev.morphxposed.data.prefs.InstallStrategy
import dev.morphxposed.data.prefs.ThemeMode
import dev.morphxposed.domain.apps.Compatibility
import dev.morphxposed.domain.apps.CompatibilityReport
import dev.morphxposed.ui.screens.AppsScreen
import dev.morphxposed.ui.screens.AutomationScreen
import dev.morphxposed.ui.screens.DashboardScreen
import dev.morphxposed.ui.screens.LogsScreen
import dev.morphxposed.ui.screens.PatchesScreen
import dev.morphxposed.ui.screens.SettingsScreen
import dev.morphxposed.ui.theme.MorphXposedTheme
import dev.morphxposed.ui.viewmodel.AppUiModel
import dev.morphxposed.ui.viewmodel.SettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renders the real Compose screens and drives their controls.
 *
 * This executes composition, layout, and click handling for every screen â€” the path that was
 * previously only compiled. Each test asserts on what a user would actually see and do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ScreenCompositionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun app(
        pkg: String = "com.google.android.youtube",
        label: String = "YouTube",
        version: String = "21.45.82",
        status: Compatibility = Compatibility.SUPPORTED,
        autoPatch: Boolean = false,
        lastPatchedVersion: String? = null
    ): AppUiModel {
        val row = SupportedAppEntity(
            packageName = pkg,
            label = label,
            versionName = version,
            versionCode = 1,
            apkPath = "/data/app/base.apk",
            autoPatchEnabled = autoPatch,
            sourceId = 1,
            lastPatchedVersion = lastPatchedVersion,
            lastPatchedAt = if (lastPatchedVersion != null) 1_000L else 0
        )
        val report = CompatibilityReport(
            packageName = pkg,
            installedVersion = version,
            status = status,
            supportedVersions = listOf(version),
            applicablePatches = emptyList(),
            skippedPatches = emptyList(),
            reason = "test"
        )
        return AppUiModel(row, report)
    }

    private fun settings(
        installStrategy: InstallStrategy = InstallStrategy.SESSION,
        autoInstall: Boolean = false,
        autoUpdate: Boolean = false
    ) = SettingsUiState(
        installStrategy = installStrategy,
        autoPatchOnInstall = autoInstall,
        autoPatchOnUpdate = autoUpdate,
        autoCheckForPatchUpdates = true,
        updateCheckIntervalHours = 12,
        notifyOnSuccess = true,
        notifyOnFailure = true
    )

    @Test
    fun `dashboard renders metrics and app rows`() {
        compose.setContent {
            MorphXposedTheme {
                DashboardScreen(
                    apps = listOf(app(), app(pkg = "com.reddit.frontpage", label = "Reddit")),
                    sources = listOf(officialSource()),
                    runs = emptyList(),
                    busy = null,
                    onRefreshSources = {},
                    onRefreshApps = {},
                    onPatchAll = {},
                    onOpenApp = {}
                )
            }
        }

        compose.onNodeWithText("MorphXposed").assertIsDisplayed()
        compose.onNodeWithText("Patchable apps").assertIsDisplayed()
        compose.onNodeWithText("YouTube").assertIsDisplayed()
        compose.onNodeWithText("Reddit").assertIsDisplayed()
        // No runs yet -> the empty-state hint is shown.
        compose.onNodeWithText("No patch runs yet. Open Apps to patch one.").assertIsDisplayed()
    }

    @Test
    fun `dashboard shows an outdated patched app as needing a repatch`() {
        compose.setContent {
            MorphXposedTheme {
                DashboardScreen(
                    apps = listOf(app(version = "21.46.0", lastPatchedVersion = "21.45.82")),
                    sources = listOf(officialSource()),
                    runs = emptyList(),
                    busy = null,
                    onRefreshSources = {},
                    onRefreshApps = {},
                    onPatchAll = {},
                    onOpenApp = {}
                )
            }
        }
        // The metric tile goes from 0 to 1 when the installed version differs from the patched one.
        compose.onNodeWithText("Need repatch").assertIsDisplayed()
    }

    @Test
    fun `dashboard renders a failed run with its error`() {
        val run = PatchRunEntity(
            id = 1,
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            startedAt = System.currentTimeMillis() - 5_000,
            status = "FAILED",
            sourceVersionName = "21.45.82",
            sourceVersionCode = 1,
            bundleTag = "v2.3.0",
            error = "Patch \"Hide ads\" failed: fingerprint not found"
        )
        compose.setContent {
            MorphXposedTheme {
                DashboardScreen(
                    apps = listOf(app()),
                    sources = listOf(officialSource()),
                    runs = listOf(run),
                    busy = null,
                    onRefreshSources = {},
                    onRefreshApps = {},
                    onPatchAll = {},
                    onOpenApp = {}
                )
            }
        }
        compose.onAllNodesWithText("YouTube")[0].assertIsDisplayed()
        compose.onNodeWithText("Failed", substring = true).assertIsDisplayed()
    }

    @Test
    fun `apps screen lists a compatible app and its patch action`() {
        compose.setContent {
            MorphXposedTheme {
                AppsScreen(
                    apps = listOf(app()),
                    busy = null,
                    onRescan = {},
                    onPatch = {},
                    onToggleAutoPatch = { _, _ -> },
                    onToggleHidden = { _, _ -> }
                )
            }
        }

        compose.onNodeWithText("Installed apps").assertIsDisplayed()
        compose.onNodeWithText("YouTube").assertIsDisplayed()
        compose.onAllNodesWithText("Compatible")[0].assertIsDisplayed()
        // A never-patched app offers "Patch", not "Repatch".
        compose.onNodeWithText("Patch").assertIsDisplayed()
    }

    @Test
    fun `apps screen disables patching for an unsupported version`() {
        compose.setContent {
            MorphXposedTheme {
                AppsScreen(
                    apps = listOf(app(status = Compatibility.UNSUPPORTED_VERSION)),
                    busy = null,
                    onRescan = {},
                    onPatch = {},
                    onToggleAutoPatch = { _, _ -> },
                    onToggleHidden = { _, _ -> }
                )
            }
        }
        compose.onNodeWithText("Version unsupported").assertIsDisplayed()
        // The Patch button exists but must be disabled when the version is unsupported.
        compose.onNodeWithText("Patch").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `apps screen toggles the auto switch`() {
        var toggled: Pair<String, Boolean>? = null
        compose.setContent {
            MorphXposedTheme {
                AppsScreen(
                    apps = listOf(app(autoPatch = false)),
                    busy = null,
                    onRescan = {},
                    onPatch = {},
                    onToggleAutoPatch = { pkg, on -> toggled = pkg to on },
                    onToggleHidden = { _, _ -> }
                )
            }
        }

        compose.onNodeWithText("Auto").assertIsDisplayed()
        // The row's switch is the only checkable node.
        compose.onNode(isToggleable()).performClick()
        assertEquals("com.google.android.youtube" to true, toggled)
    }

    @Test
    fun `patches screen renders a source with its bundle details`() {
        compose.setContent {
            MorphXposedTheme {
                PatchesScreen(
                    sources = listOf(officialSource().copy(installedTag = "v2.3.0")),
                    bundleInfo = emptyMap(),
                    busy = null,
                    onRefresh = {},
                    onToggleEnabled = { _, _ -> },
                    onTogglePrerelease = { _, _ -> }
                )
            }
        }
        compose.onNodeWithText("Patches").assertIsDisplayed()
        compose.onNodeWithText("Morphe (official)").assertIsDisplayed()
        compose.onNodeWithText("Official").assertIsDisplayed()
        compose.onNodeWithText("v2.3.0").assertIsDisplayed()
    }

    @Test
    fun `patches screen shows a source error`() {
        compose.setContent {
            MorphXposedTheme {
                PatchesScreen(
                    sources = listOf(officialSource().copy(lastError = "GitHub rate limit reached")),
                    bundleInfo = emptyMap(),
                    busy = null,
                    onRefresh = {},
                    onToggleEnabled = { _, _ -> },
                    onTogglePrerelease = { _, _ -> }
                )
            }
        }
        compose.onNodeWithText("GitHub rate limit reached").assertIsDisplayed()
    }

    @Test
    fun `automation screen toggles are interactive`() {
        var installToggle: Boolean? = null
        compose.setContent {
            MorphXposedTheme {
                AutomationScreen(
                    settings = settings(autoInstall = false),
                    onAutoPatchOnInstall = { installToggle = it },
                    onAutoPatchOnUpdate = {},
                    onAutoCheck = {},
                    onIntervalHours = {},
                    onRunUpdateCheck = {},
                    onPatchAllNow = {}
                )
            }
        }

        compose.onNodeWithText("Patch newly installed apps").assertIsDisplayed().performScrollTo()
        compose.onNodeWithText("Repatch updated apps").assertIsDisplayed().performScrollTo()

        // The first switch belongs to "Patch newly installed apps".
        compose.onAllNodes(isToggleable())[0].assertIsOff().performClick()
        assertEquals(true, installToggle)
    }

    @Test
    fun `automation screen reflects enabled settings`() {
        compose.setContent {
            MorphXposedTheme {
                AutomationScreen(
                    settings = settings(autoInstall = true, autoUpdate = true),
                    onAutoPatchOnInstall = {},
                    onAutoPatchOnUpdate = {},
                    onAutoCheck = {},
                    onIntervalHours = {},
                    onRunUpdateCheck = {},
                    onPatchAllNow = {}
                )
            }
        }
        compose.onAllNodes(isToggleable())[0].assertIsOn()
        compose.onAllNodes(isToggleable())[1].assertIsOn()
    }

    @Test
    fun `logs screen shows a run and its status`() {
        val run = PatchRunEntity(
            id = 1,
            packageName = "com.google.android.youtube",
            appLabel = "YouTube",
            startedAt = System.currentTimeMillis(),
            status = "SUCCEEDED",
            sourceVersionName = "21.45.82",
            sourceVersionCode = 1,
            bundleTag = "v2.3.0",
            appliedPatches = "Hide ads,SponsorBlock",
            installed = true,
            installStrategy = "SESSION"
        )
        compose.setContent {
            MorphXposedTheme {
                LogsScreen(runs = listOf(run), onClear = {})
            }
        }
        compose.onNodeWithText("Logs").assertIsDisplayed()
        compose.onNodeWithText("YouTube").assertIsDisplayed()
        compose.onNodeWithText("SUCCEEDED").assertIsDisplayed()
    }

    @Test
    fun `logs screen shows an empty state`() {
        compose.setContent {
            MorphXposedTheme {
                LogsScreen(runs = emptyList(), onClear = {})
            }
        }
        compose.onNodeWithText("No runs yet").assertIsDisplayed()
    }

    @Test
    fun `settings screen renders installer choices and the root-mount caveat`() {
        val lsposedText = "LSPosed found; install MorphXposed Events to enable real-time events"
        val lsposedOn = false
        compose.setContent {
            MorphXposedTheme {
                SettingsScreen(
                    settings = settings(),
                    themeMode = ThemeMode.SYSTEM,
                    sources = listOf(officialSource()),
                    onTheme = {},
                    onInstallStrategy = {},
                    onNotifySuccess = {},
                    onNotifyFailure = {},
                    onKeepBackups = {},
                    onWifiOnly = {},
                    onVerboseLogging = {},
                    onAddSource = {},
                    onRemoveSource = {},
                    onGithubToken = {},
                    lsposedStatus = lsposedText,
                    lsposedActive = lsposedOn,
                    onRefreshLsposed = {}
                )
            }
        }

        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Session installer (no root)").assertIsDisplayed().performScrollTo()
        compose.onNodeWithText("Root mount (recommended with root)").assertIsDisplayed()
        // The data-loss caveat must be visible when the session installer is selected.
        compose.onNodeWithText(
            "Without root the stock app must be uninstalled before installing the patched build, " +
                "which clears its data. Use root mount to avoid that.",
            substring = true
        ).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Morphe (official)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Built in").assertIsDisplayed()
    }

    @Test
    fun `settings screen explains the root mount path when selected`() {
        val lsposedText = "Events module active - events are arriving"
        val lsposedOn = true
        compose.setContent {
            MorphXposedTheme {
                SettingsScreen(
                    settings = settings(installStrategy = InstallStrategy.ROOT_MOUNT),
                    themeMode = ThemeMode.DARK,
                    sources = listOf(officialSource()),
                    onTheme = {},
                    onInstallStrategy = {},
                    onNotifySuccess = {},
                    onNotifyFailure = {},
                    onKeepBackups = {},
                    onWifiOnly = {},
                    onVerboseLogging = {},
                    onAddSource = {},
                    onRemoveSource = {},
                    onGithubToken = {},
                    lsposedStatus = lsposedText,
                    lsposedActive = lsposedOn,
                    onRefreshLsposed = {}
                )
            }
        }
        compose.onNodeWithText("bind-mounts the patched APK", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun officialSource() = PatchSourceEntity(
        id = 1,
        repoOwner = "MorpheApp",
        repoName = "morphe-patches",
        displayName = "Morphe (official)",
        isOfficial = true
    )
}
