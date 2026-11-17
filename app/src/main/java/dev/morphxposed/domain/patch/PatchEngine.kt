package dev.morphxposed.domain.patch

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherProgress
import app.morphe.patcher.PatcherTask
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Progress events emitted while a patch run proceeds. */
sealed interface PatchEvent {
    data class Stage(val stage: StageName, val detail: String = "") : PatchEvent
    data class TaskProgress(val task: String, val done: Long, val total: Long) : PatchEvent
    data class PatchExecuting(val name: String) : PatchEvent
    data class PatchSucceeded(val name: String) : PatchEvent
    data class PatchFailed(val name: String, val message: String) : PatchEvent
    data class Log(val line: String) : PatchEvent

    /**
     * A run finished and produced a patched APK.
     *
     * @param installed true only when the APK actually reached the device.
     * @param pendingInstall true when the platform is waiting on the user to confirm.
     */
    data class Done(
        val outputApk: File,
        val applied: List<String>,
        val installed: Boolean = false,
        val pendingInstall: Boolean = false
    ) : PatchEvent

    data class Error(val message: String, val cause: Throwable? = null) : PatchEvent
}

enum class StageName {
    PREPARING, LOADING_BUNDLE, PATCHING, WRITING_APK, FINISHED
}

class PatchRunException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs the **official** Morphe patcher over an APK.
 *
 * This class contains no patch logic of its own: it wires `app.morphe:morphe-patcher` to
 * the selected patches and streams its results. All bytecode and resource manipulation,
 * fingerprint matching, and APK writing are done by Morphe itself, so behaviour matches
 * Morphe Manager and Morphe Desktop.
 *
 * Progress is delivered through a [channelFlow] because the patcher reports from several
 * of its own worker threads; `trySend` keeps that thread-safe and non-blocking.
 */
class PatchEngine {

    private val running = AtomicBoolean(false)

    /** True while a run is in progress; only one run is allowed at a time. */
    val isRunning: Boolean get() = running.get()

    /**
     * Applies [patchNames] from [bundleFile] to [inputApk], writing the patched APK to
     * [outputApk].
     *
     * @param workDir scratch space the patcher may delete and recreate freely.
     * @param targetPatcherVersion patcher behaviour version, or null to infer it per bundle.
     * @param options per-patch option values, keyed by patch name then option key.
     */
    fun run(
        inputApk: File,
        outputApk: File,
        bundleFile: File,
        patchNames: List<String>,
        workDir: File,
        targetPatcherVersion: String? = null,
        options: Map<String, Map<String, Any?>> = emptyMap()
    ): Flow<PatchEvent> = channelFlow {
        if (!running.compareAndSet(false, true)) {
            send(PatchEvent.Error("A patch run is already in progress."))
            close()
            return@channelFlow
        }

        fun log(line: String) {
            trySend(PatchEvent.Log(line))
        }

        var patcher: Patcher? = null
        try {
            send(PatchEvent.Stage(StageName.PREPARING))
            require(inputApk.isFile) { "Input APK is missing: ${inputApk.absolutePath}" }
            require(bundleFile.isFile) { "Patch bundle is missing: ${bundleFile.absolutePath}" }
            outputApk.parentFile?.mkdirs()
            if (outputApk.exists()) outputApk.delete()

            val tempDir = File(workDir, "patcher-temp").apply { mkdirs() }
            val workspaceDir = File(workDir, "workspace").apply { mkdirs() }

            send(PatchEvent.Stage(StageName.LOADING_BUNDLE, bundleFile.name))
            log("Loading patch bundle ${bundleFile.name}")

            val allPatches: Set<Patch> = withContext(Dispatchers.IO) {
                runCatching { loadPatchesFromJar(setOf(bundleFile)) }
                    .getOrElse { throw PatchRunException("Could not load patch bundle: ${it.message}", it) }
            }
            log("Bundle declares ${allPatches.size} patches")

            val byName = allPatches.mapNotNull { p -> p.name?.let { it to p } }.toMap()
            val missing = patchNames.filterNot { it in byName }
            if (missing.isNotEmpty()) {
                throw PatchRunException("Bundle has no patch named: ${missing.joinToString(", ")}")
            }
            val selected: Set<Patch> = patchNames.mapNotNull { byName[it] }.toSet()
            if (selected.isEmpty()) throw PatchRunException("No patches were selected.")

            val progress = PatcherProgress { task: PatcherTask, done: Long, total: Long ->
                // Called from the patcher's worker threads.
                trySend(PatchEvent.TaskProgress(task.name, done, total))
            }

            send(PatchEvent.Stage(StageName.PATCHING, "${selected.size} patches"))

            // Keep only the ABIs this device can run. The patcher treats an empty set as
            // "keep everything", so an unmappable device falls back to the safe default.
            val keepArchitectures = ArchitectureSelection.keepFor()
            log("Keeping native ABIs: ${ArchitectureSelection.describe(keepArchitectures)}")

            val config = PatcherConfig(
                apkFile = inputApk,
                temporaryFilesPath = tempDir,
                keepArchitectures = keepArchitectures,
                fileWorkspacePath = workspaceDir,
                targetPatcherVersion = targetPatcherVersion,
                validate = false,
                progress = progress
            )

            patcher = Patcher(config)
            val active = patcher!!
            active += selected

            // Apply option values before execution, as Morphe does.
            options.forEach { (patchName, values) ->
                val patch = byName[patchName] ?: return@forEach
                values.forEach { (key, value) ->
                    runCatching { patch.options[key] = value }
                        .onFailure { log("Could not set option '$key' on '$patchName': ${it.message}") }
                }
            }

            val applied = ArrayList<String>()
            active { patch -> trySend(PatchEvent.PatchExecuting(patch.name.orEmpty())) }
                .collect { result ->
                    val name = result.patch.name.orEmpty()
                    val failure = result.exception
                    if (failure != null) {
                        log("\"$name\" failed: ${failure.message}")
                        send(PatchEvent.PatchFailed(name, failure.message ?: "failed"))
                        throw PatchRunException("\"$name\" failed: ${failure.message}", failure)
                    }
                    applied += name
                    log("\"$name\" succeeded")
                    send(PatchEvent.PatchSucceeded(name))
                }

            if (applied.isEmpty()) {
                throw PatchRunException("No patch was executed.")
            }
            log("${applied.size} patches applied")

            send(PatchEvent.Stage(StageName.WRITING_APK))
            log("Writing patched APK to ${outputApk.name}")
            withContext(Dispatchers.IO) { active.writeApk(outputApk) }

            if (!outputApk.isFile || outputApk.length() == 0L) {
                throw PatchRunException("Patcher did not produce an APK.")
            }
            log("Wrote ${outputApk.length()} bytes")

            send(PatchEvent.Stage(StageName.FINISHED))
            send(PatchEvent.Done(outputApk = outputApk, applied = applied.toList()))
        } catch (t: Throwable) {
            log("Run failed: ${t.message}")
            send(PatchEvent.Error(t.message ?: t.javaClass.simpleName, t))
        } finally {
            runCatching { patcher?.close() }
            running.set(false)
        }
        close()
    }.flowOn(Dispatchers.IO)
}
