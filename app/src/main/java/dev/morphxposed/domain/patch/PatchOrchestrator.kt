package dev.morphxposed.domain.patch

import android.content.Context
import android.util.Log
import dev.morphxposed.data.db.MorphDatabase
import dev.morphxposed.data.db.PatchRunEntity
import dev.morphxposed.data.db.SupportedAppEntity
import dev.morphxposed.data.prefs.InstallStrategy
import dev.morphxposed.data.prefs.SettingsStore
import dev.morphxposed.domain.apps.AppScanner
import dev.morphxposed.domain.apps.Compatibility
import dev.morphxposed.domain.apps.CompatibilityResolver
import dev.morphxposed.domain.install.ApkInstaller
import dev.morphxposed.domain.install.InstallResult
import dev.morphxposed.domain.install.InstallStrategyName
import dev.morphxposed.domain.install.RootMountInstaller
import dev.morphxposed.domain.install.SessionInstaller
import dev.morphxposed.domain.sign.PatchSigner
import dev.morphxposed.domain.source.BundleInfo
import dev.morphxposed.domain.source.BundleInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/** Why a run did not proceed. Surfaced verbatim to the user. */
sealed interface RunOutcome {
    data class Patched(
        val runId: Long,
        val outputApk: File,
        val installed: Boolean,
        val strategy: InstallStrategyName?,
        val appliedPatches: List<String>
    ) : RunOutcome

    data class Skipped(val reason: String) : RunOutcome
    data class Failed(val message: String, val runId: Long?) : RunOutcome
}

/**
 * Orchestrates a complete patch run for one package:
 *
 *  scan the installed app → load the bundle → verify compatibility → run Morphe's patcher →
 *  sign → install or export → record the run.
 *
 * Every step that can fail does so with a message the user can act on, and the run row is
 * always completed so the Logs screen never shows a stuck run.
 */
class PatchOrchestrator(
    private val context: Context,
    private val db: MorphDatabase,
    private val settings: SettingsStore,
    private val scanner: AppScanner,
    private val engine: PatchEngine,
    private val inspector: BundleInspector,
    private val sessionInstaller: SessionInstaller,
    private val rootInstaller: RootMountInstaller
) {

    private val signer by lazy {
        PatchSigner(
            File(context.filesDir, "signing/morphxposed.p12"),
            PatchSigner.DEFAULT_ALIAS
        )
    }

    /** Directory holding produced APKs. */
    private val outputDir: File
        get() = File(context.filesDir, "patched").apply { mkdirs() }

    /** Working directories for the patcher, cleared per run. */
    private fun workDir(packageName: String): File =
        File(context.cacheDir, "patchwork/${packageName.replace('.', '_')}")

    /**
     * Runs the whole pipeline.
     *
     * @param explicitPatchNames patches to apply; when null, the bundle's default selection is used.
     * @param forceInstall install even if the app was already patched with this exact bundle and version.
     */
    fun run(
        packageName: String,
        explicitPatchNames: List<String>? = null,
        forceInstall: Boolean = false,
        install: Boolean = true
    ): Flow<PatchEvent> = flow {
        var runId: Long? = null
        val log = StringBuilder()
        fun appendLog(line: String) {
            log.appendLine(line)
        }

        try {
            // ---- 1. Resolve the installed app -------------------------------------------------
            emit(PatchEvent.Stage(StageName.PREPARING, "Reading $packageName"))
            val app = scanner.packageInfo(packageName)
                ?: throw PatchRunException("$packageName is not installed.")
            appendLog("Installed ${app.label} ${app.versionName} (${app.versionCode})")

            // ---- 2. Resolve the bundle --------------------------------------------------------
            val snap = settings.snapshot()
            val resolved = resolveBundle(packageName, app.versionName)
                ?: throw PatchRunException(
                    "No enabled patch source can patch ${app.label} ${app.versionName}."
                )
            val bundleFile = resolved.file
            val bundleInfo = resolved.info
            appendLog("Using bundle ${resolved.tag} (${bundleInfo.patches.size} patches)")

            // ---- 3. Verify compatibility ------------------------------------------------------
            val report = CompatibilityResolver.resolve(
                bundle = bundleInfo,
                packageName = packageName,
                installedVersion = app.versionName
            )
            if (report.status != Compatibility.SUPPORTED) {
                emit(PatchEvent.Error(report.reason))
                return@flow
            }

            val selected = explicitPatchNames?.takeIf { it.isNotEmpty() }
                ?: CompatibilityResolver.defaultSelection(report)
            if (selected.isEmpty()) throw PatchRunException("No patches apply to ${app.versionName}.")

            // ---- 4. Skip if nothing changed ---------------------------------------------------
            // "Already done" must compare against the bundle actually used for this app, not
            // whichever source happens to be listed first.
            val alreadyPatched = app.lastPatchedVersion == app.versionName &&
                app.lastPatchedBundleSha256 == resolved.sha256
            if (alreadyPatched && !forceInstall) {
                appendLog("Already patched with this bundle and version; nothing to do.")
                emit(PatchEvent.Log("Nothing to do: already patched."))
                emit(PatchEvent.Stage(StageName.FINISHED))
                return@flow
            }

            // ---- 5. Record the run ------------------------------------------------------------
            // Keep the run history bounded: without this the Logs table grows forever, and each
            // row carries the whole patch log. Eight runs per app is plenty to diagnose with.
            runCatching { db.patchRunDao().pruneTo(MAX_RUNS_RETAINED) }
            runId = db.patchRunDao().insert(
                PatchRunEntity(
                    packageName = packageName,
                    appLabel = app.label,
                    startedAt = System.currentTimeMillis(),
                    status = "PATCHING",
                    sourceVersionName = app.versionName,
                    sourceVersionCode = app.versionCode,
                    bundleTag = resolved.tag,
                    bundleSha256 = resolved.sha256,
                    installStrategy = snap.installStrategy.name
                )
            )

            // ---- 6. Snapshot the original APK for rollback ------------------------------------
            if (snap.keepOriginalBackup) {
                backupOriginal(app)
            }

            // ---- 7. Patch ---------------------------------------------------------------------
            prepareInputApk(app)?.let { input ->
                appendLog("Input APK: ${input.name} (${input.length()} bytes)")
            }

            val inputApk = stagedInputApk(app)
            val rawOutput = File(workDir(packageName), "patched-unsigned.apk")
            val signedOutput = File(outputDir, "${packageName}-${app.versionName}-patched.apk")

            val applied = ArrayList<String>()
            var patchFailure: String? = null

            engine.run(
                inputApk = inputApk,
                outputApk = rawOutput,
                bundleFile = bundleFile,
                patchNames = selected,
                workDir = workDir(packageName),
                targetPatcherVersion = null
            ).collect { event ->
                when (event) {
                    is PatchEvent.Log -> appendLog(event.line)
                    is PatchEvent.PatchSucceeded -> applied += event.name
                    is PatchEvent.PatchFailed -> patchFailure = event.message
                    is PatchEvent.Error -> patchFailure = event.message
                    else -> Unit
                }
                emit(event)
            }

            if (patchFailure != null) {
                throw PatchRunException(patchFailure!!)
            }
            if (!rawOutput.isFile) throw PatchRunException("Patcher produced no APK.")

            // ---- 8. Sign ----------------------------------------------------------------------
            emit(PatchEvent.Stage(StageName.WRITING_APK, "Signing"))
            val signed = withContext(Dispatchers.IO) { signer.sign(rawOutput, signedOutput) }
            if (!signed) throw PatchRunException("Could not sign the patched APK.")
            appendLog("Signed ${signedOutput.name} (${signedOutput.length()} bytes)")

            // ---- 9. Install -------------------------------------------------------------------
            var installResult: InstallResult? = null
            if (install) {
                val installer = installerFor(snap.installStrategy)
                if (!installer.isAvailable()) {
                    appendLog("Installer ${installer.strategy} is not available.")
                    installResult = InstallResult.NotPermitted(installer.strategy)
                } else {
                    emit(PatchEvent.Stage(StageName.FINISHED, "Installing"))
                    installResult = installer.install(signedOutput, packageName, app.versionName)
                    appendLog("Install result: $installResult")
                }
            }

            val installed = installResult is InstallResult.Success
            val pending = installResult is InstallResult.PendingUserAction
            // A mount-based install preserves the stock app, so record it separately.
            val mounted = (installResult as? InstallResult.Success)?.strategy ==
                InstallStrategyName.ROOT_MOUNT

            db.supportedAppDao().markPatched(
                packageName = packageName,
                at = System.currentTimeMillis(),
                version = app.versionName,
                bundleTag = resolved.tag,
                bundleSha = resolved.sha256,
                mounted = mounted
            )
            db.supportedAppDao().setSelectedPatches(packageName, applied.joinToString(","))

            // "SUCCEEDED" must mean the patch was applied. Whether it reached the device is
            // reported separately by `installed`, so an exported-but-not-installed run is not
            // presented as a completed install.
            val status = when {
                installed -> "SUCCEEDED"
                pending -> "INSTALLING"
                else -> "PATCHED"
            }
            db.patchRunDao().complete(
                id = runId,
                status = status,
                finishedAt = System.currentTimeMillis(),
                applied = applied.joinToString(","),
                failed = "",
                installed = installed,
                strategy = installResult?.strategy?.name ?: snap.installStrategy.name,
                log = log.toString(),
                outputPath = signedOutput.absolutePath,
                error = when (installResult) {
                    is InstallResult.Failure -> installResult.message
                    is InstallResult.NotPermitted -> "Installer unavailable; APK exported instead."
                    else -> null
                }
            )

            emit(
                PatchEvent.Done(
                    outputApk = signedOutput,
                    applied = applied,
                    installed = installed,
                    pendingInstall = pending
                )
            )
        } catch (t: Throwable) {
            appendLog("FAILED: ${t.message}")
            Log.e(TAG, "Patch run failed for $packageName", t)
            runId?.let { id ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        db.patchRunDao().complete(
                            id = id,
                            status = "FAILED",
                            finishedAt = System.currentTimeMillis(),
                            applied = "",
                            failed = t.message.orEmpty(),
                            installed = false,
                            strategy = null,
                            log = log.toString(),
                            outputPath = null,
                            error = t.message ?: t.javaClass.simpleName
                        )
                    }
                }
            }
            emit(PatchEvent.Error(t.message ?: t.javaClass.simpleName, t))
        }
    }.flowOn(Dispatchers.IO)

    private fun installerFor(strategy: InstallStrategy): ApkInstaller = when (strategy) {
        InstallStrategy.ROOT_MOUNT -> rootInstaller
        // Shizuku support is layered on the session installer; the session path is the
        // guaranteed fallback when Shizuku is absent.
        InstallStrategy.SHIZUKU, InstallStrategy.SESSION -> sessionInstaller
    }

    /** A bundle resolved for a package, with the source it came from. */
    private data class ResolvedBundle(
        val sourceId: Long,
        val file: File,
        val info: BundleInfo,
        val sha256: String?,
        val tag: String
    )

    /**
     * Finds the bundle that can patch [packageName] and its metadata.
     *
     * The first enabled source that reports SUPPORTED for the installed version wins, so an
     * app patched by a community source is never overridden by the official one.
     */
    private suspend fun resolveBundle(packageName: String, installedVersion: String): ResolvedBundle? {
        val sources = db.patchSourceDao().enabled()
        for (source in sources) {
            val fileName = source.cachedFileName ?: continue
            val file = File(context.filesDir, "bundles/$fileName")
            if (!file.isFile) continue

            val info = runCatching { inspector.inspect(file, source.installedTag ?: "") }
                .getOrElse {
                    Log.w(TAG, "Could not inspect bundle for ${source.displayName}: ${it.message}")
                    continue
                }

            val report = CompatibilityResolver.resolve(
                info, packageName, installedVersion
            )
            if (report.status == Compatibility.SUPPORTED) {
                return ResolvedBundle(
                    sourceId = source.id,
                    file = file,
                    info = info,
                    sha256 = source.cachedSha256,
                    tag = source.installedTag ?: info.tag
                )
            }
        }
        return null
    }

    /**
     * Copies the installed base APK (plus splits, packed as an APKS archive) into backup storage.
     */
    private suspend fun backupOriginal(app: SupportedAppEntity) = withContext(Dispatchers.IO) {
        runCatching {
            val existing = db.apkBackupDao().forVersion(app.packageName, app.versionCode)
            if (existing != null && File(existing.filePath).isFile) return@runCatching

            val dir = File(context.filesDir, "backups/${app.packageName}").apply { mkdirs() }
            val base = File(app.apkPath)
            if (!base.isFile) return@runCatching

            val splits = app.splitApkPaths.split("\n").filter { it.isNotBlank() }
            val target = if (splits.isEmpty()) {
                File(dir, "${app.versionName}.apk").also { base.copyTo(it, overwrite = true) }
            } else {
                val archive = File(dir, "${app.versionName}.apks")
                dev.morphxposed.domain.split.SplitArchives.pack(
                    baseApk = base,
                    splitApks = splits.map(::File).filter { it.isFile },
                    output = archive
                )
                archive
            }

            db.apkBackupDao().upsert(
                dev.morphxposed.data.db.ApkBackupEntity(
                    packageName = app.packageName,
                    versionName = app.versionName,
                    versionCode = app.versionCode,
                    filePath = target.absolutePath,
                    sizeBytes = target.length(),
                    createdAt = System.currentTimeMillis()
                )
            )
        }.onFailure { Log.w(TAG, "Could not back up ${app.packageName}: ${it.message}") }
    }

    /**
     * Ensures a patchable input APK exists. Split installs are merged into one APK first,
     * because the patcher expects a single archive.
     */
    private suspend fun prepareInputApk(app: SupportedAppEntity): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val base = File(app.apkPath)
                val splits = app.splitApkPaths.split("\n").filter { it.isNotBlank() }.map(::File)
                if (splits.isEmpty()) {
                    if (!base.isFile) throw PatchRunException("Installed APK is not readable.")
                    base.copyTo(File(workDir(app.packageName), "input.apk"), overwrite = true)
                } else {
                    val merged = File(workDir(app.packageName), "input.apk")
                    dev.morphxposed.domain.split.SplitArchives.mergeToApk(
                        baseApk = base,
                        splitApks = splits.filter { it.isFile },
                        output = merged
                    )
                    merged
                }
            }.getOrNull()
        }

    private suspend fun stagedInputApk(app: SupportedAppEntity): File {
        val f = File(workDir(app.packageName), "input.apk")
        if (!f.isFile) prepareInputApk(app)
        return f
    }

    companion object {
        /**
         * How many finished runs to keep. Each row stores the run's full log, so the table grows
         * quickly on a device that patches several apps on a schedule.
         */
        const val MAX_RUNS_RETAINED = 200

        private const val TAG = "MorphXOrchestrator"
    }
}
