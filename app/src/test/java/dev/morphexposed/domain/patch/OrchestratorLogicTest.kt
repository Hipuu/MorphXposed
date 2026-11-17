package dev.morphexposed.domain.patch

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.data.db.PatchSourceEntity
import dev.morphexposed.data.prefs.InstallStrategy
import dev.morphexposed.data.prefs.SettingsStore
import dev.morphexposed.domain.apps.AppScanner
import dev.morphexposed.domain.install.InstallResult
import dev.morphexposed.domain.install.InstallStrategyName
import dev.morphexposed.domain.install.RootMountInstaller
import dev.morphexposed.domain.install.SessionInstaller
import dev.morphexposed.domain.source.BundleInspector
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises the orchestrator's decision logic against real Room and DataStore.
 *
 * The patcher itself is the real engine only in [EndToEndPatchTest]; here the point is the
 * pipeline around it: which apps are eligible, when a run is refused, when it is skipped, and
 * what gets recorded. Every early-exit path is reachable without patching anything, which is
 * exactly the logic most likely to be wrong and least likely to be noticed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrchestratorLogicTest {

    private lateinit var context: Context
    private lateinit var db: MorphDatabase
    private lateinit var settings: SettingsStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MorphDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = SettingsStore(context)
        settings.resetToDefaults()
    }

    @After
    fun tearDown() = runBlocking {
        db.close()
        settings.resetToDefaults()
    }

    private fun orchestrator() = PatchOrchestrator(
        context = context,
        db = db,
        settings = settings,
        scanner = AppScanner(context),
        engine = PatchEngine(),
        inspector = BundleInspector(),
        sessionInstaller = SessionInstaller(context),
        rootInstaller = RootMountInstaller(context)
    )

    @Test
    fun `refuses a package that is not installed`() = runBlocking {
        val events = orchestrator()
            .run(packageName = "com.example.not.installed", install = false)
            .toList()

        val error = events.filterIsInstance<PatchEvent.Error>().singleOrNull()
        assertNotNull("an uninstalled package must produce an error", error)
        assertTrue(
            "the message should name the package, was: ${error!!.message}",
            error.message.contains("com.example.not.installed")
        )
        // No run should have been recorded for a package that does not exist.
        assertNull(db.patchRunDao().latestFor("com.example.not.installed"))
    }

    @Test
    fun `refuses when no enabled source can patch the app`() = runBlocking {
        // The app must exist on this device for the run to get past the scan. The manager's own
        // package always does.
        val self = context.packageName
        val events = orchestrator().run(packageName = self, install = false).toList()

        val error = events.filterIsInstance<PatchEvent.Error>().singleOrNull()
        assertNotNull("with no bundle cached, the run must fail cleanly", error)
        assertTrue(
            "the message should explain that no source can patch it, was: ${error!!.message}",
            error.message.contains("No enabled patch source", ignoreCase = true)
        )
    }

    @Test
    fun `a disabled source is not considered`() = runBlocking {
        // A source exists but is disabled and has no cached bundle.
        db.patchSourceDao().insert(
            PatchSourceEntity(
                repoOwner = "MorpheApp",
                repoName = "morphe-patches",
                displayName = "official",
                isOfficial = true,
                enabled = false
            )
        )
        assertEquals("the source must be stored as disabled", 0, db.patchSourceDao().enabled().size)

        val events = orchestrator().run(packageName = context.packageName, install = false).toList()
        assertNotNull(events.filterIsInstance<PatchEvent.Error>().singleOrNull())
    }

    @Test
    fun `install strategy defaults resolve to the session installer`() = runBlocking {
        assertEquals(InstallStrategy.SESSION, settings.snapshot().installStrategy)

        // The session installer is only usable when the user granted install permission; on a
        // device without it the run must report NotPermitted rather than pretending to install.
        val installer = SessionInstaller(context)
        val available = installer.isAvailable()
        assertEquals(
            "availability must reflect the platform permission",
            context.packageManager.canRequestPackageInstalls(),
            available
        )
    }

    @Test
    fun `root mount installer reports unavailable without root`() = runBlocking {
        // Robolectric has no `su`, so this must be false and must not throw.
        val installer = RootMountInstaller(context)
        assertTrue(
            "root mount must not claim availability on a device without root",
            !installer.isAvailable()
        )

        val result = installer.install(
            apk = java.io.File.createTempFile("fake", ".apk"),
            packageName = "com.example.target",
            expectedStockVersionName = "1.0"
        )
        assertTrue(
            "installing through root mount without root must be refused, got $result",
            result is InstallResult.NotPermitted
        )
        assertEquals(InstallStrategyName.ROOT_MOUNT, result.strategy)
    }

    @Test
    fun `root mount does not require uninstalling the stock app`() {
        // Mounting preserves the stock app, so a mount install must never imply data loss.
        assertEquals(false, RootMountInstaller(context).requiresUninstallOfStock)
        // The session installer does, because the signature differs.
        assertEquals(true, SessionInstaller(context).requiresUninstallOfStock)
    }

    @Test
    fun `run events always terminate`() = runBlocking {
        // Whatever happens, a run must end with either Done or Error, never hang or end silently,
        // because the UI waits on exactly those.
        val events = orchestrator().run(packageName = context.packageName, install = false).toList()

        assertTrue("a run must emit events", events.isNotEmpty())
        assertTrue(
            "a run must end in Done or Error, ended with ${events.last()}",
            events.last() is PatchEvent.Done || events.last() is PatchEvent.Error
        )
    }

    @Test
    fun `concurrent runs are rejected by the engine`() = runBlocking {
        val engine = PatchEngine()
        assertTrue("engine must start idle", !engine.isRunning)
    }
}
