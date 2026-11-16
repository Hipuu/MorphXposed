package dev.morphxposed.domain.source

import android.util.Log
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.jar.JarFile
import java.util.zip.ZipFile

/**
 * A single patch as declared by a bundle.
 */
data class PatchInfo(
    val name: String,
    val description: String?,
    val isDefault: Boolean,
    /** *(packageName -> supported version strings)* this patch declares. */
    val compatibility: Map<String, List<String>>,
    /** True when the patch declares no package, meaning it applies to any app. */
    val isUniversal: Boolean,
    val options: List<String>
)

/**
 * What a bundle declares about itself, read from its manifest and its patches.
 */
data class BundleInfo(
    val tag: String,
    val patcherVersion: String?,
    val bundleName: String?,
    val bundleVersion: String?,
    val patches: List<PatchInfo>,
    /** package name -> every version any patch claims to support, newest first. */
    val supportedPackages: Map<String, List<String>>,
    val universalPatchCount: Int
) {
    fun patchesFor(packageName: String): List<PatchInfo> =
        patches.filter { it.compatibility.containsKey(packageName) }
}

/**
 * Reads real patch metadata out of a `.mpp` bundle without ever executing anything
 * from it beyond the official patcher's own class loading of patch definitions.
 *
 * Patch definitions are Kotlin objects whose metadata is produced by the patcher's
 * own `PatchLoader`, so this reflects exactly what Morphe itself would see.
 */
class BundleInspector {

    /**
     * Inspects [bundleFile].
     *
     * @throws BundleInspectionException when the manifest is unreadable or the bundle's
     *   patches cannot be loaded (e.g. built for a newer patcher).
     */
    suspend fun inspect(bundleFile: File, tag: String): BundleInfo = withContext(Dispatchers.IO) {
        if (!bundleFile.exists()) {
            throw BundleInspectionException("Bundle file is missing: ${bundleFile.name}")
        }

        val manifest = readManifest(bundleFile)
        val requiredPatcher = manifest?.mainAttributes?.getValue("Patcher-Version")
        val bundleName = manifest?.mainAttributes?.getValue("Bundle-Name")
        val bundleVersion = manifest?.mainAttributes?.getValue("Bundle-Version")

        val patches = try {
            loadPatchesFromJar(setOf(bundleFile)).map { it.toInfo() }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load patches from ${bundleFile.name}", t)
            throw BundleInspectionException(
                "Could not load patches (bundle may need a newer Morphe Patcher): ${t.message}"
            )
        }

        val supported = mutableMapOf<String, MutableSet<String>>()
        for (patch in patches) {
            for ((pkg, versions) in patch.compatibility) {
                supported.getOrPut(pkg) { mutableSetOf() }.addAll(versions)
            }
        }

        BundleInfo(
            tag = tag,
            patcherVersion = requiredPatcher,
            bundleName = bundleName,
            bundleVersion = bundleVersion,
            patches = patches.sortedBy { it.name.lowercase() },
            supportedPackages = supported.mapValues { (_, versions) ->
                versions.sortedWith(VersionComparator.reversed())
            },
            universalPatchCount = patches.count { it.isUniversal }
        )
    }

    private fun Patch.toInfo(): PatchInfo {
        val compat = this.compatibility.orEmpty()
        // A patch declares compatibility per package; entries without a package name
        // (or with none at all) are universal.
        val byPackage = linkedMapOf<String, List<String>>()
        var universal = compat.isEmpty()
        for (c in compat) {
            val pkg = c.packageName
            if (pkg.isNullOrBlank()) {
                universal = true
                continue
            }
            val versions = c.targets.mapNotNull { it.version }
            byPackage[pkg] = versions
        }
        return PatchInfo(
            name = this.name ?: "(unnamed)",
            description = this.description,
            // `default` is the patcher's own "apply this patch unless deselected" flag.
            isDefault = this.default,
            compatibility = byPackage,
            isUniversal = universal,
            // Options is a Map<String, Option<*>>; its keys are the option names.
            options = this.options.keys.toList()
        )
    }

    private fun readManifest(file: File): java.util.jar.Manifest? = runCatching {
        JarFile(file).use { it.manifest }
    }.getOrElse { e ->
        // Some bundle writers produce a plain ZIP without a JAR manifest; not fatal.
        Log.w(TAG, "No readable manifest in ${file.name}: ${e.message}")
        null
    }

    /** Reads the bundle's entry names, used only for diagnostics. */
    fun entryNames(file: File): List<String> = runCatching {
        ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
    }.getOrDefault(emptyList())

    companion object {
        private const val TAG = "MorphXBundleInspector"
    }
}

class BundleInspectionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Compares Morphe app version strings. Morphe treats a null version as "any", and
 * otherwise sorts semantically where possible and alphabetically when not.
 */
object VersionComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        if (pa != null && pb != null) {
            val n = minOf(pa.size, pb.size)
            for (i in 0 until n) {
                val c = pa[i].compareTo(pb[i])
                if (c != 0) return c
            }
            return pa.size.compareTo(pb.size)
        }
        return a.compareTo(b, ignoreCase = true)
    }

    private fun parse(v: String): List<Int>? {
        val parts = v.split('.', '-', '_', '+')
        if (parts.isEmpty()) return null
        val numbers = ArrayList<Int>(parts.size)
        for (part in parts) {
            val digits = part.takeWhile { it.isDigit() }
            if (digits.isEmpty()) return null
            numbers.add(digits.toIntOrNull() ?: return null)
        }
        return numbers
    }
}
