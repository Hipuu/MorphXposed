package dev.morphxposed.domain.patch

import android.os.Build
import app.morphe.patcher.resource.CpuArchitecture

/**
 * Works out which native ABIs a patched APK should keep.
 *
 * Morphe's patcher drops native libraries for architectures the device cannot execute, which
 * shrinks the output considerably on a multi-ABI APK. The set has to come from the device, not
 * from the app being patched: keeping an architecture the device cannot run bloats the APK, and
 * dropping one it can run breaks the app.
 *
 * The patcher's default is an **empty set**, which it treats as "keep everything". Passing the
 * device's real ABIs is therefore an optimisation, and getting it wrong is worse than not doing
 * it — so anything unrecognised is dropped from the set rather than guessed at, and an empty
 * result falls back to the safe default.
 */
object ArchitectureSelection {

    /**
     * Maps [Build.SUPPORTED_ABIS] to the patcher's enum.
     *
     * @return the architectures to keep, or an empty set to keep everything when nothing maps.
     */
    fun keepFor(abis: List<String> = Build.SUPPORTED_ABIS.toList()): Set<CpuArchitecture> {
        val mapped = abis.mapNotNull(::fromAbiName).toSet()
        // An empty set means "keep everything" to the patcher, which is the safe answer when the
        // device reported nothing usable.
        return mapped
    }

    /** Maps one ABI string, as `Build.SUPPORTED_ABIS` reports it. */
    fun fromAbiName(abi: String): CpuArchitecture? = when (abi.lowercase().trim()) {
        "arm64-v8a" -> CpuArchitecture.ARM64_V8A
        "armeabi-v7a" -> CpuArchitecture.ARMEABI_V7A
        // "armeabi" is the legacy pre-v7 name and maps to its own enum constant.
        "armeabi" -> CpuArchitecture.ARMEABI
        "x86" -> CpuArchitecture.X86
        "x86_64" -> CpuArchitecture.X86_64
        "mips" -> CpuArchitecture.MIPS
        "mips64" -> CpuArchitecture.MIPS64
        else -> null
    }

    /** Human-readable summary for the UI and logs. */
    fun describe(keep: Set<CpuArchitecture>): String =
        if (keep.isEmpty()) {
            "all architectures (device reported none usable)"
        } else {
            keep.map { it.arch }.sorted().joinToString(", ")
        }
}
