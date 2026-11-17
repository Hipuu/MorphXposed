package dev.morphexposed

/**
 * Proves at compile time that the official Morphe patching engine is usable from
 * this module. If morphe-patcher's classes or transitive dependencies cannot be
 * linked here, the build fails rather than failing at runtime on a device.
 */
internal object PatcherLinkProbe {
    fun classes(): List<String> = listOf(
        app.morphe.patcher.Patcher::class.java.name,
        app.morphe.patcher.PatcherConfig::class.java.name,
        app.morphe.patcher.PatcherResult::class.java.name,
        app.morphe.patcher.patch.Patch::class.java.name,
        app.morphe.patcher.patch.PatchLoader::class.java.name,
    )
}
