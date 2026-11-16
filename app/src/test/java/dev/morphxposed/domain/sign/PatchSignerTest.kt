package dev.morphxposed.domain.sign

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.morphe.patcher.apk.ApkUtils
import com.android.apksig.ApkVerifier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * Signs a real APK with the app's own signer and verifies the result with `apksig`.
 *
 * Signing sits on the critical path of every install and had never been executed. The fixture
 * matters: `ApkUtils.signApk` reads `minSdkVersion` from the binary manifest, so an APK with a
 * placeholder manifest fails with `MinSdkVersionException`. This uses the real compiled APK at
 * `testdata/target.apk`, which is what a patch run actually feeds it.
 *
 * Requires `testdata/target.apk`; skipped otherwise so a bare checkout still builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatchSignerTest {

    private lateinit var context: Context
    private lateinit var work: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        work = File(context.cacheDir, "signer-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    /** The real, compilable APK produced by the project's testdata build. */
    private fun realApk(): File? = listOf(
        File("testdata/target.apk"),
        File("../testdata/target.apk")
    ).firstOrNull { it.isFile }

    /** A copy of the real APK with its META-INF signature entries stripped, as the patcher emits. */
    private fun unsignedCopy(name: String = "unsigned.apk"): File? {
        val src = realApk() ?: return null
        val dst = File(work, name)
        ZipFile(src).use { zip ->
            java.util.zip.ZipOutputStream(dst.outputStream()).use { out ->
                zip.entries().asSequence()
                    .filterNot { it.name.startsWith("META-INF/") }
                    .forEach { entry ->
                        out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
            }
        }
        return dst
    }

    private fun newSigner(name: String = "signing.p12") =
        PatchSigner(File(work, name), PatchSigner.DEFAULT_ALIAS)

    @Test
    fun `generates a keystore that Morphe can read back`() {
        val input = unsignedCopy()
        assumeTrue("testdata/target.apk not present", input != null)
        val keystore = File(work, "signing.p12")

        val signer = newSigner()
        assertTrue("signing should succeed", signer.sign(input!!, File(work, "signed.apk")))

        assertTrue("the keystore must exist", keystore.isFile)
        assertTrue("the keystore must not be empty", keystore.length() > 0)

        // The exact failure found earlier: a keystore that its own writer produced but the
        // reader could not parse. This asserts the round trip, not merely that bytes exist.
        val readBack = ApkUtils.readKeyStore(
            ApkUtils.KeyStoreDetails(keystore, "morphxposed", PatchSigner.DEFAULT_ALIAS, "morphxposed")
        )
        assertNotNull(readBack)
        assertNotNull(
            "the alias must be present in the generated keystore",
            readBack.getCertificate(PatchSigner.DEFAULT_ALIAS)
        )
    }

    @Test
    fun `produces an apk whose signature verifies`() {
        val input = unsignedCopy()
        assumeTrue("testdata/target.apk not present", input != null)
        val signed = File(work, "signed.apk")

        assertTrue(newSigner().sign(input!!, signed))

        val result = ApkVerifier.Builder(signed).build().verify()
        assertTrue(
            "the signed APK must verify; errors: ${result.errors.map { it.toString() }}",
            result.isVerified
        )
    }

    @Test
    fun `signs with the scheme Android requires`() {
        val input = unsignedCopy()
        assumeTrue("testdata/target.apk not present", input != null)
        val signed = File(work, "signed.apk")

        assertTrue(newSigner().sign(input!!, signed))

        val result = ApkVerifier.Builder(signed).build().verify()
        assertTrue(
            "v2 signing must be present, the minimum for API 24+",
            result.isVerifiedUsingV2Scheme
        )
    }

    @Test
    fun `the signature adds data to the archive`() {
        val input = unsignedCopy()
        assumeTrue("testdata/target.apk not present", input != null)
        val before = input!!.length()
        val signed = File(work, "signed.apk")

        assertTrue(newSigner().sign(input, signed))

        assertTrue("the signed APK must be larger", signed.length() > before)
    }

    @Test
    fun `the signing identity is stable across runs`() {
        // This is the property that makes an update installable: a later patch run must sign
        // with the same key, or Android refuses to replace the installed app.
        val keystore = File(work, "signing.p12")

        val first = unsignedCopy("a.apk")
        assumeTrue("testdata/target.apk not present", first != null)
        val firstOut = File(work, "first.apk")
        assertTrue(PatchSigner(keystore, PatchSigner.DEFAULT_ALIAS).sign(first!!, firstOut))

        val second = unsignedCopy("b.apk")!!
        val secondOut = File(work, "second.apk")
        assertTrue(PatchSigner(keystore, PatchSigner.DEFAULT_ALIAS).sign(second, secondOut))

        val firstCerts = ApkVerifier.Builder(firstOut).build().verify()
            .signerCertificates.map { it.encoded.toList() }
        val secondCerts = ApkVerifier.Builder(secondOut).build().verify()
            .signerCertificates.map { it.encoded.toList() }

        assertEquals("both runs must use the same certificate", firstCerts, secondCerts)
        assertEquals("there must be exactly one signer", 1, firstCerts.size)
    }

    @Test
    fun `a different keystore produces a different identity`() {
        // Guards against accidentally sharing a key: a freshly generated keystore must not
        // yield the same certificate, or every install would be signed by the same identity.
        val a = unsignedCopy("x.apk")
        assumeTrue("testdata/target.apk not present", a != null)

        val outA = File(work, "a-signed.apk")
        assertTrue(PatchSigner(File(work, "a.p12"), PatchSigner.DEFAULT_ALIAS).sign(a!!, outA))

        val b = unsignedCopy("y.apk")!!
        val outB = File(work, "b-signed.apk")
        assertTrue(PatchSigner(File(work, "b.p12"), PatchSigner.DEFAULT_ALIAS).sign(b, outB))

        val certA = ApkVerifier.Builder(outA).build().verify().signerCertificates.map { it.encoded.toList() }
        val certB = ApkVerifier.Builder(outB).build().verify().signerCertificates.map { it.encoded.toList() }

        assertNotEquals("two keystores must not share an identity", certA, certB)
    }

    @Test
    fun `fails cleanly when the input is not an apk`() {
        val bogus = File(work, "not-an-apk.apk").apply { writeText("this is not a zip") }
        val ok = newSigner().sign(bogus, File(work, "out.apk"))
        assertTrue("signing garbage must return false rather than throw", !ok)
    }

    @Test
    fun `fails cleanly when the input is missing`() {
        val ok = newSigner().sign(File(work, "nope.apk"), File(work, "out.apk"))
        assertTrue("signing a missing file must return false rather than throw", !ok)
    }

    @Test
    fun `hasKey reflects keystore state`() {
        val keystore = File(work, "signing.p12")
        val signer = PatchSigner(keystore, PatchSigner.DEFAULT_ALIAS)
        assertTrue("no key before signing", !signer.hasKey())

        val input = unsignedCopy()
        assumeTrue("testdata/target.apk not present", input != null)
        signer.sign(input!!, File(work, "signed.apk"))

        assertTrue("a key exists after signing", signer.hasKey())
    }
}
