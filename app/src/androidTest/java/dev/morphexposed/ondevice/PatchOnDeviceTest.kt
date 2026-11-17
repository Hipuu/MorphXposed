package dev.morphexposed.ondevice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.morphexposed.MorpheXposedApp
import dev.morphexposed.automation.TriggerReason
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real patcher on a real Android device.
 *
 * This is the one check this project could not perform for most of its life. Everything else ran on
 * a host JVM, where `URLClassLoader` reads the bundle happily and none of the dalvik-specific rules
 * apply — which is why both loader defects from round 44 survived a fully green suite.
 *
 * The test drives [dev.morphexposed.automation.PatchCoordinator.runBatch] exactly as the UI does:
 * same engine, same signing, same database, same installer policy. It deliberately runs with
 * `install = false` semantics by asserting on the produced APK rather than on the device state,
 * because the emulator cannot replace YouTube and a test that reports "installed" without
 * installing anything is precisely the failure mode this project has had to fix repeatedly.
 *
 * Preconditions, created by `scripts/ondevice-prepare.sh`:
 *  * the official bundle at `files/bundles/patches-official.mpp`, registered in `patch_sources`;
 *  * an app the bundle supports, installed on the device.
 *
 * Without them the tests skip, and a skip is not a pass.
 */
@RunWith(AndroidJUnit4::class)
class PatchOnDeviceTest {

    private lateinit var app: MorpheXposedApp
    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as MorpheXposedApp
    }

    private fun bundle(): File? =
        File(context.filesDir, "bundles/patches-official.mpp").takeIf { it.isFile }

    /** A package the bundle can patch that is actually installed here, or null. */
    private fun installedSupportedPackage(): String? = runBlocking {
        app.sourceRepository.supportedPackages().keys.firstOrNull { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    @Test
    fun theBundleLoadsOnDevice() {
        assumeTrue("No bundle on the device; run scripts/ondevice-prepare.sh", bundle() != null)

        val inspected = runBlocking { app.sourceRepository.inspectEnabled() }

        assertTrue(
            "the bundle must load through the device's dex loader; an empty list means it did not",
            inspected.isNotEmpty()
        )
        val info = inspected.first().second
        assertTrue("the bundle must declare patches", info.patches.isNotEmpty())

        println(
            "Loaded ${info.patches.size} patches covering ${info.supportedPackages.size} packages " +
                "(bundle ${info.patcherVersion ?: "no patcher version"})"
        )
    }

    @Test
    fun theBundleSupportsAnInstalledApp() {
        assumeTrue("No bundle on the device", bundle() != null)

        val pkg = installedSupportedPackage()
        assumeTrue("No installed app is supported by the bundle", pkg != null)

        val report = runBlocking {
            app.coordinator.refreshInstalledApps()
            app.database.supportedAppDao().byPackage(pkg!!)
        }
        assertNotNull("the supported app must be recorded by the scan", report)
        println("$pkg ${report!!.versionName} is supported and recorded")
    }

    /**
     * The deliverable: a patch actually applied on Android.
     *
     * Kept separate from the checks above so the report says clearly whether the engine ran or was
     * only reachable.
     */
    @Test
    fun patchingProducesAPatchedApkOnDevice() = runBlocking {
        assumeTrue("No bundle on the device", bundle() != null)

        val pkg = installedSupportedPackage()
        assumeTrue("No installed app is supported by the bundle", pkg != null)

        // The user has to have opted in for runBatch to act; a manual run is always allowed.
        app.coordinator.refreshInstalledApps()

        val summary = app.coordinator.runBatch(
            reason = TriggerReason.MANUAL,
            packageFilter = pkg,
            force = true
        )

        summary.failed.forEach { (label, why) -> println("FAILED $label: $why") }
        summary.skipped.forEach { (label, why) -> println("SKIPPED $label: $why") }

        assertTrue(
            "the manual run must attempt the app; it was skipped: " +
                summary.skipped.joinToString { "${it.first}: ${it.second}" },
            summary.skipped.isEmpty()
        )
        assertEquals(
            "the patch run must not fail",
            emptyList<Pair<String, String>>(),
            summary.failed
        )
        assertEquals("exactly one app must be patched", 1, summary.patched.size)

        // The run records the produced APK; asserting it exists is what distinguishes "the engine
        // ran" from "the engine returned".
        val run = app.database.patchRunDao().latestFor(pkg!!)
        assertNotNull("the run must be recorded", run)
        assertTrue(
            "the run must have produced output, status was ${run!!.status}",
            run.status == "SUCCEEDED" || run.status == "PATCHED" || run.status == "INSTALLING"
        )

        val out = run.outputApkPath?.let { File(it) }
        assertNotNull("the run must record the patched APK path (log: ${run.error})", out)
        assertTrue("the patched APK must exist at ${out!!.absolutePath}", out.isFile)
        assertTrue("the patched APK must not be empty", out.length() > 0)

        println(
            "Patched ${run.packageName} ${run.sourceVersionName} -> ${out.name} " +
                "(${out.length()} bytes), status=${run.status}, " +
                "applied='${run.appliedPatches}', installed=${run.installed}"
        )
    }
}
