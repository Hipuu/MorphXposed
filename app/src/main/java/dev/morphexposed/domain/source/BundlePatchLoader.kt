package dev.morphexposed.domain.source

import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromDex
import app.morphe.patcher.patch.loadPatchesFromJar
import java.io.File

/**
 * Loads patches with whichever of Morphe's two loaders this runtime can actually execute.
 *
 * Morphe ships both because the runtimes cannot read each other's bytecode:
 *
 *  * the **JAR** loader uses `URLClassLoader`, which reads `.jar` on a host JVM;
 *  * the **DEX** loader uses `DexClassLoader`, which reads `.dex` on Android.
 *
 * On a device the JAR loader fails from `URLClassLoader.findClass` with
 * `ClassCastException: UnsupportedOperationException cannot be cast to ClassNotFoundException` —
 * dalvik bytecode is not JVM bytecode. In a JVM unit test the DEX loader fails instead, for the
 * mirror reason. Neither loader is correct in the abstract; the right one depends on where the code
 * runs.
 *
 * Trying DEX first and falling back to JAR resolves that without a build flag or a runtime sniff:
 * on a device the first attempt succeeds, and under JVM tests the first attempt fails cheaply and
 * the second produces the result the tests were written against.
 *
 * Everything here was found by running on an emulator. No JVM-hosted test could produce either
 * failure, which is why both survived every earlier round.
 */
object BundlePatchLoader {

    /** How the patches were loaded, for logging and for tests. */
    enum class Used { DEX, JAR }

    data class Result(val patches: List<Patch>, val used: Used)

    /**
     * Loads patches from [files], preferring the Android loader.
     *
     * @throws Throwable the JAR loader's failure when both paths fail, since that is the one a
     *   caller can act on ("the bundle may need a newer patcher").
     */
    fun load(files: Set<File>): Result {
        // A writable dex is refused by Android: DexFile.openDexFileNative throws
        // "SecurityException: Writable dex file ... is not allowed". A freshly downloaded bundle is
        // writable by default, so this must happen before the DEX attempt.
        files.forEach(::makeReadOnly)

        runCatching { loadPatchesFromDex(files).toList() }
            .getOrNull()
            ?.let { return Result(it, Used.DEX) }

        // On a JVM the DEX path always fails; the JAR path is the one that works there.
        return Result(loadPatchesFromJar(files).toList(), Used.JAR)
    }

    /**
     * Makes [file] readable but not writable, including its directory.
     *
     * Android rejects a writable dex, and also rejects one inside a group- or world-writable
     * directory — hence the execute bit alongside.
     */
    private fun makeReadOnly(file: File) {
        runCatching {
            file.parentFile?.let { dir ->
                dir.setReadable(true, true)
                dir.setWritable(true, true)
                dir.setExecutable(true, true)
            }
            file.setReadable(true, true)
            file.setWritable(false, false)
        }
    }
}
