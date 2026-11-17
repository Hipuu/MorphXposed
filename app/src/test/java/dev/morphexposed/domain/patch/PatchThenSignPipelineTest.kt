package dev.morphexposed.domain.patch

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.patch.loadPatchesFromJar
import com.android.apksig.ApkVerifier
import dev.morphexposed.domain.sign.PatchSigner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * Runs the install pipeline in the order a real patch run does:
 * **patch -> sign -> verify**.
 *
 * The steps are covered individually elsewhere; this covers the composition, which is what an
 * install depends on. A patched APK that cannot be signed, or a signed APK that does not
 * verify, is indistinguishable from a working one until install time.
 *
 * Deliberately a **plain JVM** test, not Robolectric: `loadPatchesFromJar` loads the bundle
 * through a `DexClassLoader`, which Robolectric's sandboxed classloader does not support — the
 * bundle loads as zero patches there rather than failing loudly. `PatchSigner` needs no
 * `Context`, so `android.util.Log` is the only Android dependency, covered by
 * `isReturnDefaultValues`.
 *
 * Requires the official bundle and `testdata/target.apk`; skipped otherwise.
 */
class PatchThenSignPipelineTest {

    private lateinit var work: File

    @Before
    fun setUp() {
        work = File("build/pipeline-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun bundle(): File? = listOf(
        File("testdata/patches.mpp"),
        File("../testdata/patches.mpp")
    ).firstOrNull { it.isFile }
        ?: System.getenv("MORPHX_TEST_BUNDLE")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    private fun targetApk(): File? = listOf(
        File("testdata/target.apk"),
        File("../testdata/target.apk")
    ).firstOrNull { it.isFile }

    /**
     * Applies one universal patch, mirroring [PatchEngine], and returns the patched APK.
     *
     * Tries patches until one applies: a patch written for YouTube legitimately fails on an
     * unrelated APK, which is Morphe's fingerprinting correctly rejecting it.
     */
    private fun patch(source: File, bundleFile: File): File? {
        val patches = loadPatchesFromJar(setOf(bundleFile))
        val universal = patches.filter { it.compatibility.isNullOrEmpty() }

        for (patch in universal.take(10)) {
            val dir = File(work, "apply-${patch.name?.hashCode()}").apply { mkdirs() }
            val out = File(dir, "patched.apk")
            val config = PatcherConfig(
                apkFile = source,
                temporaryFilesPath = File(dir, "temp").apply { mkdirs() },
                fileWorkspacePath = File(dir, "workspace").apply { mkdirs() },
                validate = false
            )
            val ok = runCatching {
                Patcher(config).use { patcher ->
                    patcher += setOf(patch)
                    kotlinx.coroutines.runBlocking {
                        patcher { }.collect { it.exception?.let { e -> throw e } }
                    }
                    patcher.writeApk(out)
                }
            }.isSuccess

            if (ok && out.isFile && out.length() > 0) {
                println("pipeline: applied \"${patch.name}\"")
                return out
            }
        }
        return null
    }

    @Test
    fun `a patched apk can be signed and the signature verifies`() {
        val bundleFile = bundle()
        val source = targetApk()
        assumeTrue("bundle or target APK missing", bundleFile != null && source != null)

        // 1. Patch, exactly as the engine does.
        val patched = patch(source!!, bundleFile!!)
        assumeTrue("no universal patch could be applied to the fixture", patched != null)
        assertTrue("the patcher must produce output", patched!!.isFile && patched.length() > 0)

        // 2. Sign, exactly as the orchestrator does.
        val signed = File(work, "signed.apk")
        val signer = PatchSigner(File(work, "signing.p12"), PatchSigner.DEFAULT_ALIAS)
        assertTrue("the patched APK must be signable", signer.sign(patched, signed))
        assertTrue("signing must produce output", signed.isFile && signed.length() > 0)

        // 3. Verify the end product, which is what an installer accepts.
        val result = ApkVerifier.Builder(signed).build().verify()
        assertTrue(
            "the patched and signed APK must verify; errors: ${result.errors.map { it.toString() }}",
            result.isVerified
        )
        assertTrue("v2 signing must be present", result.isVerifiedUsingV2Scheme)

        // 4. It must still be a structurally valid APK.
        ZipFile(signed).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue("must keep the manifest", names.contains("AndroidManifest.xml"))
            assertTrue("must keep dex files", names.any { it.matches(Regex("classes\\d*\\.dex")) })
        }
    }

    @Test
    fun `the pipeline yields a stable signing identity`() {
        val bundleFile = bundle()
        val source = targetApk()
        assumeTrue("bundle or target APK missing", bundleFile != null && source != null)

        val patched = patch(source!!, bundleFile!!)
        assumeTrue("no universal patch could be applied", patched != null)

        val signer = PatchSigner(File(work, "signing.p12"), PatchSigner.DEFAULT_ALIAS)
        val signedA = File(work, "a.apk")
        val signedB = File(work, "b.apk")
        assertTrue(signer.sign(patched!!, signedA))
        assertTrue(signer.sign(patched, signedB))

        val certA = ApkVerifier.Builder(signedA).build().verify()
            .signerCertificates.map { it.encoded.toList() }
        val certB = ApkVerifier.Builder(signedB).build().verify()
            .signerCertificates.map { it.encoded.toList() }

        assertEquals("the same keystore must yield the same identity", certA, certB)
        assertEquals("exactly one signer", 1, certA.size)
    }

    @Test
    fun `patching changes the apk and signing makes it installable`() {
        val bundleFile = bundle()
        val source = targetApk()
        assumeTrue("bundle or target APK missing", bundleFile != null && source != null)

        val originalSize = source!!.length()
        val patched = patch(source, bundleFile!!)
        assumeTrue("no universal patch could be applied", patched != null)

        val signed = File(work, "installable.apk")
        assertTrue(PatchSigner(File(work, "k.p12"), PatchSigner.DEFAULT_ALIAS).sign(patched!!, signed))

        // The fixture is a real signed APK, but the patcher strips the original signature
        // (that is what re-signing is for). The product must carry a signature this app issued.
        assertTrue(
            "the patched+re-signed product must verify",
            ApkVerifier.Builder(signed).build().verify().isVerified
        )

        // The identity must be ours, not the fixture's: the fixture was signed with the release
        // keystore, while this run signs with a fresh throwaway one.
        val productCerts = ApkVerifier.Builder(signed).build().verify()
            .signerCertificates.map { it.encoded.toList() }
        val sourceCerts = runCatching {
            ApkVerifier.Builder(source).build().verify().signerCertificates.map { it.encoded.toList() }
        }.getOrDefault(emptyList())

        assertTrue("the product must be signed with exactly one key", productCerts.size == 1)
        assertTrue(
            "re-signing must replace the original identity, not reuse it",
            productCerts != sourceCerts
        )
        assertTrue("the product differs in size from the source", signed.length() != originalSize)
    }
}
