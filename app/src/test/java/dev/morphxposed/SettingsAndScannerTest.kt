package dev.morphxposed

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.morphxposed.data.prefs.InstallStrategy
import dev.morphxposed.data.prefs.SettingsStore
import dev.morphxposed.data.prefs.ThemeMode
import dev.morphxposed.domain.apps.AppScanner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the settings store and package scanner against the real Android framework.
 *
 * DataStore and `PackageManager` are both real here, so this verifies the defaults the app
 * ships with, that writes survive, and that the scanner correctly reports nothing when no
 * supported package is installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsAndScannerTest {

    private lateinit var context: Context
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        settings = SettingsStore(context)
        // DataStore is a per-file singleton, so it is shared across tests in this JVM. Reset it
        // to defaults so each test observes the state the app actually ships with rather than
        // whatever a previous test wrote.
        settings.resetToDefaults()
    }

    @Test
    fun `defaults are safe`() = runBlocking {
        val snap = settings.snapshot()

        // Automation must be opt-in: nothing is patched without the user asking.
        assertFalse("automatic patching on install must default to off", snap.autoPatchOnInstall)
        assertFalse("automatic patching on update must default to off", snap.autoPatchOnUpdate)

        // Session installer is the only strategy that works without privilege.
        assertEquals(InstallStrategy.SESSION, snap.installStrategy)

        // Checks are on by default, but they only fetch metadata.
        assertTrue(snap.autoCheckForPatchUpdates)
        assertEquals(SettingsStore.DEFAULT_INTERVAL_HOURS, snap.updateCheckIntervalHours)

        assertTrue("original APK backups should be kept by default", snap.keepOriginalBackup)
    }

    @Test
    fun `writes persist and are readable back`() = runBlocking {
        settings.setAutoPatchOnInstall(true)
        settings.setInstallStrategy(InstallStrategy.ROOT_MOUNT)
        settings.setUpdateCheckIntervalHours(6)
        settings.setThemeMode(ThemeMode.DARK)

        val snap = settings.snapshot()
        assertTrue(snap.autoPatchOnInstall)
        assertEquals(InstallStrategy.ROOT_MOUNT, snap.installStrategy)
        assertEquals(6, snap.updateCheckIntervalHours)
        assertEquals(ThemeMode.DARK, settings.themeMode.first())
    }

    @Test
    fun `check interval is clamped to a sane range`() = runBlocking {
        settings.setUpdateCheckIntervalHours(0)
        assertEquals("0 hours must be clamped up", 1, settings.updateCheckIntervalHours.first())

        settings.setUpdateCheckIntervalHours(10_000)
        assertEquals("an absurd interval must be clamped down", 168, settings.updateCheckIntervalHours.first())
    }

    @Test
    fun `github token is stored trimmed`() = runBlocking {
        settings.setGithubToken("  ghp_example  ")
        assertEquals("ghp_example", settings.snapshot().githubToken)
    }

    @Test
    fun `scanner reports no supported apps when the map is empty`() = runBlocking {
        val scanner = AppScanner(context)
        val result = scanner.scan(emptyMap())

        // Nothing is supported, so nothing is returned, whatever is actually installed.
        assertTrue("no package in the support map means no results", result.apps.isEmpty())
    }

    @Test
    fun `scanner does not return a package that is not installed`() = runBlocking {
        val scanner = AppScanner(context)
        val result = scanner.scan(mapOf("com.example.not.installed" to 1L))

        assertTrue(
            "a supported package that is not installed must not appear",
            result.apps.none { it.packageName == "com.example.not.installed" }
        )
    }

    @Test
    fun `packageInfo returns null for an uninstalled package`() = runBlocking {
        val scanner = AppScanner(context)
        assertEquals(null, scanner.packageInfo("com.example.definitely.not.installed"))
        assertFalse(scanner.isInstalled("com.example.definitely.not.installed"))
    }
}
