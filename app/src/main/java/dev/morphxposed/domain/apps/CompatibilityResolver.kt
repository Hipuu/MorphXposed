package dev.morphxposed.domain.apps

import dev.morphxposed.domain.source.BundleInfo
import dev.morphxposed.domain.source.PatchInfo
import dev.morphxposed.domain.source.VersionComparator

/** How well an installed app lines up with what the bundle claims to support. */
enum class Compatibility {
    /** The installed version is one the bundle explicitly supports. */
    SUPPORTED,

    /**
     * Only patches that apply to any app ("universal" patches such as signature spoofing) can
     * run; no app-specific patch supports this version. Universal patches do not make a version
     * supported, so automation must not treat this as patchable on its own.
     */
    UNIVERSAL_ONLY,

    /** The bundle can patch the app, but not this version. */
    UNSUPPORTED_VERSION,

    /** The bundle has patches for the package but none can run on this device SDK. */
    UNSUPPORTED_DEVICE,

    /** No enabled bundle knows this package. */
    NOT_SUPPORTED
}

/**
 * The verdict for one app against one bundle.
 */
data class CompatibilityReport(
    val packageName: String,
    val installedVersion: String,
    val status: Compatibility,
    /** Versions the bundle recommends for this package, newest first. */
    val supportedVersions: List<String>,
    /** Patches that can run against the installed version. */
    val applicablePatches: List<PatchInfo>,
    /** Patches declared for this package but skipped for the installed version. */
    val skippedPatches: List<PatchInfo>,
    /** Human-readable explanation shown in the UI. */
    val reason: String
) {
    val hasApplicablePatches: Boolean get() = applicablePatches.isNotEmpty()
}

/**
 * Decides whether an installed app can be patched, using the compatibility data the
 * bundle itself declares. No heuristics: this reads the same declarations Morphe reads.
 */
object CompatibilityResolver {

    /**
     * Resolves [bundle] against an installed app.
     *
     * Morphe semantics, taken from what the official bundle actually declares:
     *
     *  * A patch that names the package and lists versions supports **only** those versions.
     *  * A patch that names the package with **no** versions supports any version of that
     *    package.
     *  * A patch that names **no** package is universal: it applies to any app (signature
     *    spoofing, installer spoofing). Universal patches alone must **not** be reported as
     *    [Compatibility.SUPPORTED], because that would let automation patch an arbitrary version
     *    of an app whose real patches were never written for it. They yield
     *    [Compatibility.UNIVERSAL_ONLY] instead.
     */
    fun resolve(
        bundle: BundleInfo,
        packageName: String,
        installedVersion: String,
        deviceSdk: Int
    ): CompatibilityReport {
        val packageSpecific = bundle.patches.filter { it.compatibility.containsKey(packageName) }
        val universal = bundle.patches.filter { it.isUniversal }

        if (packageSpecific.isEmpty() && universal.isEmpty()) {
            return CompatibilityReport(
                packageName = packageName,
                installedVersion = installedVersion,
                status = Compatibility.NOT_SUPPORTED,
                supportedVersions = emptyList(),
                applicablePatches = emptyList(),
                skippedPatches = emptyList(),
                reason = "No patches in this source target $packageName."
            )
        }

        val applicable = ArrayList<PatchInfo>()
        val skipped = ArrayList<PatchInfo>()
        val everyVersion = sortedSetOf(VersionComparator.reversed())

        for (patch in packageSpecific) {
            val versions = patch.compatibility[packageName]
            if (versions.isNullOrEmpty()) {
                // Package named, no versions: any version of this package.
                applicable.add(patch)
                continue
            }
            everyVersion.addAll(versions)
            val matches = versions.any { it.equals(installedVersion, ignoreCase = true) }
            if (matches) applicable.add(patch) else skipped.add(patch)
        }

        val supportedVersions = everyVersion.toList()

        if (applicable.isNotEmpty()) {
            // Universal patches run alongside the supported package-specific ones: signature or
            // installer spoofing is usually wanted even when the app itself is supported.
            val withUniversal = (applicable + universal).sortedBy { it.name.lowercase() }
            return CompatibilityReport(
                packageName = packageName,
                installedVersion = installedVersion,
                status = Compatibility.SUPPORTED,
                supportedVersions = supportedVersions,
                applicablePatches = withUniversal,
                skippedPatches = skipped.sortedBy { it.name.lowercase() },
                reason = if (skipped.isEmpty()) {
                    "All ${applicable.size} patches support version $installedVersion." +
                        if (universal.isNotEmpty()) " ${universal.size} universal patch(es) apply too." else ""
                } else {
                    "${applicable.size} of ${packageSpecific.size} patches support version " +
                        "$installedVersion; ${skipped.size} need a different version."
                }
            )
        }

        // No package-specific patch matched. Universal patches could still run, but that is not
        // the same as the app being supported.
        if (universal.isNotEmpty()) {
            return CompatibilityReport(
                packageName = packageName,
                installedVersion = installedVersion,
                status = Compatibility.UNIVERSAL_ONLY,
                supportedVersions = supportedVersions,
                applicablePatches = universal.sortedBy { it.name.lowercase() },
                skippedPatches = skipped.sortedBy { it.name.lowercase() },
                reason = buildString {
                    append("Version ").append(installedVersion).append(" is not supported by the ")
                    append("app-specific patches in this source; only ")
                    append(universal.size).append(" universal patch(es) apply.")
                    if (supportedVersions.isNotEmpty()) {
                        append(" Supported: ")
                        append(supportedVersions.take(6).joinToString(", "))
                        if (supportedVersions.size > 6) append(", …")
                    }
                }
            )
        }

        return CompatibilityReport(
            packageName = packageName,
            installedVersion = installedVersion,
            status = Compatibility.UNSUPPORTED_VERSION,
            supportedVersions = supportedVersions,
            applicablePatches = emptyList(),
            skippedPatches = skipped.sortedBy { it.name.lowercase() },
            reason = buildString {
                append("Installed version ").append(installedVersion)
                append(" is not supported by this source.")
                if (supportedVersions.isNotEmpty()) {
                    append(" Supported: ")
                    append(supportedVersions.take(6).joinToString(", "))
                    if (supportedVersions.size > 6) append(", …")
                }
            }
        )
    }

    /**
     * Picks the patches to apply for an app: every applicable patch that defaults to
     * on, which is what Morphe's own "simple mode" does.
     */
    fun defaultSelection(report: CompatibilityReport): List<String> =
        report.applicablePatches.filter { it.isDefault }.map { it.name }
            .ifEmpty { report.applicablePatches.map { it.name } }

    /**
     * Best package-level compatibility across several bundles, preferring a bundle that
     * actually supports the installed version over one that only offers universal patches.
     */
    fun best(
        reports: List<Pair<Long, CompatibilityReport>>
    ): Pair<Long, CompatibilityReport>? {
        if (reports.isEmpty()) return null
        return reports.firstOrNull { it.second.status == Compatibility.SUPPORTED }
            ?: reports.firstOrNull { it.second.status == Compatibility.UNIVERSAL_ONLY }
            ?: reports.maxByOrNull { it.second.supportedVersions.size }
    }
}
