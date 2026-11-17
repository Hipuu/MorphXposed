package dev.morphxposed.domain.source

import dev.morphxposed.BuildConfig

/**
 * Decides whether the patcher this APK ships can run a given patch bundle.
 *
 * A `.mpp` bundle declares the patcher version it was built for (`Patcher-Version` in its
 * manifest). Morphe's engine refuses a bundle newer than itself, but the failure surfaces only
 * after the download and the start of a run — a confusing message a long way from the cause. The
 * bundle metadata is read right after download, so the check can happen there instead.
 *
 * The comparison uses [VersionComparator], the same ordering the rest of the app uses for Morphe
 * versions, so `2.10.0` correctly outranks `2.9.0`.
 */
object PatcherCompatibility {

    /** What to do about a bundle. */
    sealed interface Verdict {
        /** The bundle can be run. */
        data object Compatible : Verdict

        /** The bundle declares no patcher version; it is assumed to work, as Morphe does. */
        data object Unknown : Verdict

        /** The bundle needs a newer patcher than this app ships. */
        data class RequiresNewerPatcher(
            val required: String,
            val bundled: String
        ) : Verdict

        val canRun: Boolean get() = this !is RequiresNewerPatcher

        /** A short explanation for the Patches screen. */
        fun describe(): String = when (this) {
            Compatible -> "Compatible with the bundled patcher"
            Unknown -> "Declares no patcher version"
            is RequiresNewerPatcher ->
                "Needs patcher $required or newer; this app ships $bundled"
        }
    }

    /** The patcher version compiled into this build. */
    fun bundledVersion(): String = BuildConfig.MORPHE_PATCHER_VERSION

    /**
     * Compares [required] against the bundled patcher.
     *
     * A null or blank [required] is [Verdict.Unknown] rather than a failure: Morphe treats the
     * absence of the attribute as "no constraint", and refusing to run would break bundles that
     * are perfectly usable.
     */
    fun check(
        required: String?,
        bundled: String = bundledVersion()
    ): Verdict {
        val wanted = required?.trim()?.takeIf { it.isNotEmpty() } ?: return Verdict.Unknown
        val have = bundled.trim()

        // [VersionComparator] falls back to alphabetical ordering for values it cannot parse,
        // under which "not-a-version" outranks "2.4.0" and would block the bundle. Only compare
        // when both sides are actually versions; anything else is unknown, not newer.
        if (!looksLikeVersion(wanted) || !looksLikeVersion(have)) return Verdict.Unknown

        val newer = runCatching { VersionComparator.compare(wanted, have) > 0 }.getOrDefault(false)
        return if (newer) Verdict.RequiresNewerPatcher(wanted, have) else Verdict.Compatible
    }

    /** A dotted numeric version such as `2.4.0` or `2.4`, optionally with a suffix. */
    private fun looksLikeVersion(value: String): Boolean =
        VERSION_PATTERN.matches(value)

    private val VERSION_PATTERN = Regex("""^\d+(\.\d+)*(?:[-+][0-9A-Za-z.\-]+)?$""")
}
