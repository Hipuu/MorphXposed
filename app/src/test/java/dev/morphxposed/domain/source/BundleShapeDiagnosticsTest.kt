package dev.morphxposed.domain.source

import app.morphe.patcher.patch.loadPatchesFromJar
import dev.morphxposed.domain.apps.Compatibility
import dev.morphxposed.domain.apps.CompatibilityResolver
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Diagnostics for how the official bundle actually declares compatibility.
 *
 * Kept as a test rather than a throwaway script so the assumptions the resolver relies on stay
 * verifiable as the bundle evolves. This test never fails on data shape; it fails only if the
 * resolver contradicts the data.
 */
class BundleShapeDiagnosticsTest {

    private fun bundle(): File? = listOf(
        File("testdata/patches.mpp"),
        File("../testdata/patches.mpp")
    ).firstOrNull { it.isFile }
        ?: System.getenv("MORPHX_TEST_BUNDLE")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile }

    @Test
    fun `report how youtube compatibility is declared`() {
        val file = bundle()
        assumeTrue("No bundle", file != null)

        val patches = loadPatchesFromJar(setOf(file!!))
        val yt = patches.flatMap { it.compatibility.orEmpty() }
            .filter { it.packageName == "com.google.android.youtube" }

        println("=== YouTube compatibility declarations ===")
        println("total entries     : ${yt.size}")
        println("empty targets     : ${yt.count { it.targets.isEmpty() }}")
        println("with targets      : ${yt.count { it.targets.isNotEmpty() }}")

        val anyVersion = yt.filter { it.targets.isEmpty() }
        println("any-version patches (${anyVersion.size}):")
        anyVersion.take(10).forEach { println("   ${it.name}") }

        val constrained = yt.filter { it.targets.isNotEmpty() }
        println("constrained patches (${constrained.size}), sample versions:")
        constrained.take(3).forEach { c ->
            println("   ${c.name} -> ${c.targets.mapNotNull { it.version }}")
        }

        // The important assertion: a bundle whose app-specific patches are all version
        // constrained must NOT report an arbitrary version as SUPPORTED. This is what stops
        // automation from patching a version Morphe never supported.
        val bogus = CompatibilityResolver.resolve(
            buildBundle(file, patches),
            "com.google.android.youtube",
            "0.0.1-not-a-real-version"
        )
        println("resolver says for bogus version: ${bogus.status}")
        println("reason: ${bogus.reason}")
        assert(bogus.status != Compatibility.SUPPORTED) {
            "A version no app-specific patch supports must not resolve as SUPPORTED, " +
                "got ${bogus.status}"
        }
    }

    private fun buildBundle(file: File, patches: Set<app.morphe.patcher.patch.Patch>): BundleInfo {
        val supported = mutableMapOf<String, MutableSet<String>>()
        val infos = patches.map { p ->
            val byPkg = linkedMapOf<String, List<String>>()
            var universal = p.compatibility.isNullOrEmpty()
            p.compatibility.orEmpty().forEach { c ->
                val pkg = c.packageName
                if (pkg.isNullOrBlank()) universal = true else {
                    val versions = c.targets.mapNotNull { it.version }
                    byPkg[pkg] = versions
                    supported.getOrPut(pkg) { mutableSetOf() }.addAll(versions)
                }
            }
            PatchInfo(
                name = p.name ?: "(unnamed)",
                description = p.description,
                isDefault = p.default,
                compatibility = byPkg,
                isUniversal = universal,
                options = emptyList()
            )
        }
        return BundleInfo(
            tag = file.name,
            patcherVersion = null,
            bundleName = null,
            bundleVersion = null,
            patches = infos,
            supportedPackages = supported.mapValues { it.value.toList() },
            universalPatchCount = infos.count { it.isUniversal }
        )
    }
}
