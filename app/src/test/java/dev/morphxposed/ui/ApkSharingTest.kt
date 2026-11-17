package dev.morphxposed.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Covers the share action for a patched APK.
 *
 * When no installer is available the orchestrator still produces a signed APK and records "APK
 * exported instead". That file lives in the app's private storage, and until now nothing could
 * reach it: the `FileProvider` and its `file_paths.xml` were declared from the first version and
 * never used, so the message promised an export the user could not retrieve.
 *
 * **What is not exercised here.** `FileProvider.getUriForFile` cannot be run on this machine:
 * Robolectric hands out a Windows path containing an 8.3 short name (`ADMINI~1`) while
 * `getCanonicalPath()` expands it to `Administrator`, so the prefix comparison inside
 * `SimplePathStrategy` fails regardless of configuration. That is an artifact of testing on
 * Windows, not a defect — Android has no short names. The checks that matter are therefore split
 * out: the file-existence rule, the root-mapping rule that `FileProvider` enforces, and the
 * intent shape, all of which are verified below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApkSharingTest {

    private lateinit var context: Context
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "share-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun apk(name: String = "app-patched.apk", bytes: ByteArray = ByteArray(64)): File =
        File(work, name).apply { writeBytes(bytes) }

    // ---- Root mapping: the precondition FileProvider enforces ---------------------------

    @Test
    fun `a file under the patched output directory is shareable`() {
        // The orchestrator writes here; it must be one of the exposed roots.
        val dir = File(context.filesDir, "patched").apply { mkdirs() }
        val file = File(dir, "com.example-1.0.0-patched.apk").apply { writeBytes(ByteArray(16)) }

        assertTrue(
            "the patched output directory is declared in file_paths.xml and must match",
            ApkSharing.isUnderSharedRoot(context, file)
        )
    }

    @Test
    fun `a file under the cache directory is shareable`() {
        assertTrue(ApkSharing.isUnderSharedRoot(context, apk()))
    }

    @Test
    fun `a file outside every exposed root is not shareable`() {
        // Sharing this would throw inside FileProvider and do nothing visible.
        val outside = File(context.filesDir, "secret.txt").apply {
            parentFile?.mkdirs()
            writeText("not shared")
        }
        assertFalse(
            "filesDir itself is not exposed, only filesDir/patched",
            ApkSharing.isUnderSharedRoot(context, outside)
        )
    }

    @Test
    fun `the root check is not prefix-string based`() {
        // "patched-other" must not be mistaken for "patched".
        val sibling = File(context.filesDir, "patched-other").apply { mkdirs() }
        val file = File(sibling, "x.apk").apply { writeBytes(ByteArray(8)) }
        assertFalse(
            "a similarly named directory is not the exposed root",
            ApkSharing.isUnderSharedRoot(context, file)
        )
    }

    // ---- Existence rule -----------------------------------------------------------------

    @Test
    fun `a missing file is not shareable`() {
        assertFalse(ApkSharing.isShareable(File(work, "absent.apk").absolutePath))
    }

    @Test
    fun `an empty file is not shareable`() {
        // A zero-byte APK cannot be installed, so offering it wastes the user's time.
        assertFalse(ApkSharing.isShareable(apk(bytes = ByteArray(0)).absolutePath))
    }

    @Test
    fun `a blank or absent path is not shareable`() {
        assertFalse(ApkSharing.isShareable(null))
        assertFalse(ApkSharing.isShareable(""))
        assertFalse(ApkSharing.isShareable("   "))
    }

    @Test
    fun `a real file is shareable`() {
        assertTrue(ApkSharing.isShareable(apk().absolutePath))
    }

    @Test
    fun `content uri is null for anything unshareable`() {
        // Guards the share button: it must report nothing rather than crash or offer a dead entry.
        assertNull(ApkSharing.contentUri(context, File(work, "absent.apk")))
        assertNull(ApkSharing.contentUri(context, apk(bytes = ByteArray(0))))
        assertNull(ApkSharing.contentUri(context, null))
    }

    // ---- Intent shape -------------------------------------------------------------------

    @Test
    fun `the chooser sends the apk with the right mime type`() {
        val uri = Uri.parse("content://dev.morphxposed.fileprovider/patched_apks/x.apk")
        val chooser = ApkSharing.chooser(uri, "x.apk")

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("the chooser must carry a send intent", inner)
        assertEquals(Intent.ACTION_SEND, inner!!.action)
        assertEquals(
            "a wrong mime type makes the chooser offer nothing useful",
            ApkSharing.APK_MIME, inner.type
        )
        assertEquals(uri, inner.getParcelableExtra(Intent.EXTRA_STREAM))
    }

    @Test
    fun `the chooser grants read permission to the receiver`() {
        // Without the grant the receiving app opens the URI and fails.
        val uri = Uri.parse("content://dev.morphxposed.fileprovider/patched_apks/x.apk")
        val chooser = ApkSharing.chooser(uri, "x.apk")
        val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!

        assertTrue(
            "the send intent must grant read access",
            inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        assertTrue(
            "the chooser must grant it too, since some receivers read only the outer intent",
            chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
    }

    @Test
    fun `the display name hides the internal path`() {
        val file = apk("com.example.app-1.0.0-patched.apk")
        val name = ApkSharing.displayName(file)
        assertEquals("com.example.app-1.0.0-patched.apk", name)
        assertFalse("the path must not leak into the label", name.contains("/"))
    }

    @Test
    fun `the apk mime type is the one Android expects`() {
        assertEquals("application/vnd.android.package-archive", ApkSharing.APK_MIME)
    }
}
