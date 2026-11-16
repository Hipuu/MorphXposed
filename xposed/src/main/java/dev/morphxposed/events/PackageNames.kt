package dev.morphxposed.events

/**
 * Parsing helpers for the events module, kept separate from the Xposed hook class so they can be
 * tested on a plain JVM.
 *
 * [EventsModule] implements an Xposed interface, so referencing it drags in the compile-only
 * Xposed API and cannot be instantiated in a unit test. The logic that actually needs checking —
 * deciding which argument of a hooked platform method is a package name — has no Xposed
 * dependency at all, so it lives here.
 */
internal object PackageNames {

    /**
     * Picks the package name out of a hooked method's arguments.
     *
     * The internal `PackageManagerService` methods take different parameters across Android
     * releases, so the package is found by shape rather than position. A wrong guess is not
     * harmless: the host acts on whatever package is reported.
     */
    fun fromArguments(args: Array<Any?>?): String? =
        args?.asSequence()
            ?.filterIsInstance<String>()
            ?.firstOrNull(::looksLikePackage)

    /** A plausible Android package name: dotted, lower-case, not a path or a descriptor. */
    fun looksLikePackage(value: String): Boolean {
        if (value.isEmpty() || value.length > 255) return false
        // A path or a DEX type descriptor is not a package.
        if (value.startsWith("/")) return false
        if (value.length > 2 && value.startsWith("L") && value.endsWith(";")) return false
        if (!value.contains('.')) return false
        if (value.any { it.isWhitespace() || it == '/' || it == ':' || it == '=' }) return false
        // Every segment must be non-empty and start with a letter.
        return value.split('.').all { segment ->
            segment.isNotEmpty() && segment.first().isLetter() &&
                segment.all { it.isLetterOrDigit() || it == '_' }
        }
    }
}
