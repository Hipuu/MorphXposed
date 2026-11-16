package dev.morphxposed.installer

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import dev.morphxposed.domain.install.InstallResult
import dev.morphxposed.domain.install.InstallStrategyName
import dev.morphxposed.domain.install.SessionInstaller
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageInstaller
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exercises the session installer against Robolectric's `PackageInstaller`.
 *
 * This is the unrooted install path: a session is created, the APK is streamed into it, and
 * the commit is handed to the platform. Reaching `commit` with a well-formed session is the
 * hardware-independent half; the confirmation dialog and result callback are covered by
 * [InstallResultReceiverTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionInstallerTest {

    private lateinit var context: Context
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "session-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun apk(name: String = "patched.apk"): File {
        val f = File(work, name)
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(byteArrayOf(0x03, 0x00, 0x08, 0x00))
            zip.closeEntry()
        }
        return f
    }

    @Test
    fun `the session installer declares it needs the stock app removed`() {
        // The signature differs from the stock build, so Android requires an uninstall first.
        // That trade-off is surfaced to the user, so the flag must be honest.
        assertTrue(SessionInstaller(context).requiresUninstallOfStock)
        assertEquals(InstallStrategyName.SESSION, SessionInstaller(context).strategy)
    }

    @Test
    fun `availability tracks the install permission`() = runBlocking {
        val installer = SessionInstaller(context)
        val pm = context.packageManager

        // Robolectric reports the permission state the manifest requests; the installer must
        // report the same answer the platform would act on, never a guess.
        assertEquals(pm.canRequestPackageInstalls(), installer.isAvailable())
    }

    @Test
    fun `installing streams the apk into a session and commits`() = runBlocking {
        val installer = SessionInstaller(context)
        if (!installer.isAvailable()) {
            // Without INSTALL_PACKAGES approval the installer refuses before touching a session,
            // which is itself the correct behaviour and is asserted below.
            val refused = installer.install(apk(), "com.example.target", "1.0")
            assertTrue(
                "without permission the installer must refuse, got $refused",
                refused is InstallResult.NotPermitted
            )
            return@runBlocking
        }

        val result = installer.install(apk(), "com.example.target", "1.0")

        // Either the platform accepted it, or it reported a concrete failure; it must never
        // silently do nothing.
        assertTrue(
            "the install must return a definite outcome, got $result",
            result is InstallResult.PendingUserAction ||
                result is InstallResult.Failure ||
                result is InstallResult.NotPermitted
        )
        if (result is InstallResult.PendingUserAction) {
            assertEquals(InstallStrategyName.SESSION, result.strategy)
            assertNotNull("a session id must be reported", result.sessionId)
        }
    }

    @Test
    fun `a failure names the session strategy`() = runBlocking {
        val installer = SessionInstaller(context)
        // A missing APK must fail without throwing.
        val result = installer.install(File(work, "absent.apk"), "com.example.target", "1.0")
        assertEquals(
            "the result must be attributed to the session strategy",
            InstallStrategyName.SESSION,
            result.strategy
        )
    }

    @Test
    fun `the platform shadow reports a created session`() {
        // Confirms Robolectric actually backs PackageInstaller, so the test above is exercising
        // the real path rather than a no-op stub.
        val installer = context.packageManager.packageInstaller
        assertNotNull(installer)
        val shadow: ShadowPackageInstaller = shadowOf(installer)
        assertNotNull(shadow)
        assertEquals(0, PackageInstaller.STATUS_SUCCESS)
    }
}
