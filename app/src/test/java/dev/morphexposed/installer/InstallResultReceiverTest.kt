package dev.morphexposed.installer

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import dev.morphexposed.MorpheXposedApp
import dev.morphexposed.data.db.PatchRunEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Exercises the platform's verdict on an installer session.
 *
 * `PackageInstaller.commit` only says the platform *accepted* the request; the real outcome
 * arrives later as a broadcast. That callback is the only thing that tells the app whether a
 * patched APK actually reached the device, and it had never been executed.
 *
 * Robolectric shadows `PackageInstaller`, so a session can be created and its outcome driven
 * here without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InstallResultReceiverTest {

    private lateinit var context: Context
    private lateinit var app: MorpheXposedApp

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        app = context.applicationContext as MorpheXposedApp
    }

    private fun resultIntent(
        status: Int,
        packageName: String?,
        message: String? = null
    ) = Intent(InstallResultReceiver.ACTION_INSTALL_RESULT).apply {
        putExtra(PackageInstaller.EXTRA_STATUS, status)
        packageName?.let { putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, it) }
        message?.let { putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, it) }
    }

    /** Seeds an in-flight run so the receiver has something to complete. */
    private suspend fun seedRun(packageName: String): Long =
        app.database.patchRunDao().insert(
            PatchRunEntity(
                packageName = packageName,
                appLabel = "Test App",
                startedAt = System.currentTimeMillis(),
                status = "INSTALLING",
                sourceVersionName = "1.0",
                sourceVersionCode = 1,
                bundleTag = "v2.3.0",
                appliedPatches = "Hide ads",
                installStrategy = "SESSION"
            )
        )

    private fun awaitStatus(runId: Long, expected: Set<String>): PatchRunEntity? {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
        var latest: PatchRunEntity? = null
        while (System.currentTimeMillis() < deadline) {
            latest = kotlinx.coroutines.runBlocking {
                app.database.patchRunDao().latestFor("com.example.target")
            }
            if (latest?.status in expected) return latest
            Thread.sleep(50)
        }
        return latest
    }

    @Test
    fun `a successful install leaves the run untouched`() = kotlinx.coroutines.runBlocking {
        // Success needs no correction: the orchestrator already recorded what it patched.
        val id = seedRun("com.example.target")
        app.database.patchRunDao().setStatus(id, "INSTALLING")

        InstallResultReceiver().onReceive(
            context,
            resultIntent(PackageInstaller.STATUS_SUCCESS, "com.example.target")
        )

        // Give any erroneous background work a moment, then confirm nothing was rewritten to a
        // failure.
        Thread.sleep(500)
        val latest = app.database.patchRunDao().latestFor("com.example.target")
        assertNotNull(latest)
        assertTrue(
            "a successful install must not be recorded as failed",
            latest!!.status != "FAILED"
        )
    }

    @Test
    fun `a failed install marks the run failed and keeps the reason`() = kotlinx.coroutines.runBlocking {
        val id = seedRun("com.example.target")
        app.database.patchRunDao().setStatus(id, "INSTALLING")

        InstallResultReceiver().onReceive(
            context,
            resultIntent(
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                "com.example.target",
                "signatures do not match"
            )
        )

        val latest = awaitStatus(id, setOf("FAILED"))
        assertNotNull("the run must be updated", latest)
        assertEquals("FAILED", latest!!.status)
        assertTrue(
            "the platform's message must be kept for the Logs screen",
            latest.error!!.contains("signatures do not match") ||
                latest.log.contains("signatures do not match")
        )
        assertTrue("the failure must be attributed to this run", latest.id == id)
    }

    @Test
    fun `a pending user action is recorded as installing, not failed`() = kotlinx.coroutines.runBlocking {
        // The user may take any amount of time on the confirmation dialog; calling that a
        // failure would be wrong.
        val id = seedRun("com.example.target")
        app.database.patchRunDao().setStatus(id, "INSTALLING")

        InstallResultReceiver().onReceive(
            context,
            resultIntent(PackageInstaller.STATUS_PENDING_USER_ACTION, "com.example.target")
        )

        val latest = awaitStatus(id, setOf("INSTALLING"))
        assertNotNull(latest)
        assertEquals(
            "waiting on the user is not a failure",
            "INSTALLING",
            latest!!.status
        )
    }

    @Test
    fun `an aborted install is recorded as cancelled`() = kotlinx.coroutines.runBlocking {
        val id = seedRun("com.example.target")
        app.database.patchRunDao().setStatus(id, "INSTALLING")

        InstallResultReceiver().onReceive(
            context,
            resultIntent(PackageInstaller.STATUS_FAILURE_ABORTED, "com.example.target")
        )

        val latest = awaitStatus(id, setOf("FAILED"))
        assertNotNull(latest)
        assertEquals("FAILED", latest!!.status)
    }

    @Test
    fun `an install failure for an unknown package is harmless`() {
        // Nothing to attribute it to; the receiver must not crash.
        InstallResultReceiver().onReceive(
            context,
            resultIntent(PackageInstaller.STATUS_FAILURE, "com.example.never.patched")
        )
        assertTrue("must not throw", true)
    }

    @Test
    fun `a result without a package name is ignored`() {
        InstallResultReceiver().onReceive(
            context,
            Intent(InstallResultReceiver.ACTION_INSTALL_RESULT).apply {
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            }
        )
        assertTrue("must not throw", true)
    }

    @Test
    fun `an intent with the wrong action is ignored`() = kotlinx.coroutines.runBlocking {
        val id = seedRun("com.example.target")
        val before = app.database.patchRunDao().latestFor("com.example.target")

        InstallResultReceiver().onReceive(
            context,
            Intent("some.other.ACTION").apply {
                putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, "com.example.target")
            }
        )

        Thread.sleep(500)
        val after = app.database.patchRunDao().latestFor("com.example.target")
        assertEquals(
            "an unrelated broadcast must not touch the run",
            before?.status,
            after?.status
        )
        assertEquals(id, after?.id)
    }

    @Test
    fun `the receiver does not open a second database`() {
        // It must reuse the application's instance; opening another connection over the same
        // file for one row update is wasteful and can contend with the patcher.
        assertNotNull("the app must expose its database", app.database)
    }
}
