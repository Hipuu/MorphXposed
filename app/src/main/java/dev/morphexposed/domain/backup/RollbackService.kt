package dev.morphexposed.domain.backup

import android.content.Context
import android.util.Log
import dev.morphexposed.data.db.ApkBackupEntity
import dev.morphexposed.data.db.MorphDatabase
import dev.morphexposed.domain.install.InstallResult
import dev.morphexposed.domain.install.RootMountInstaller
import dev.morphexposed.domain.install.SessionInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Restores an app from a retained original APK.
 *
 * Backups were being written but could never be used, which made "rollback" a claim rather than
 * a feature. This is the missing half: it puts the saved APK back on the device.
 *
 * Two cases, and they are genuinely different:
 *
 *  * **Root mount** — the patched APK was never installed, only mounted over the stock one. A
 *    rollback means removing the mount module and rebooting, so the stock app is simply used
 *    again. Nothing is installed, so nothing is lost.
 *  * **Session install** — the stock app was replaced, and the backup is the only copy left. It
 *    has to be re-installed, which changes the signature back and therefore clears app data.
 *    That is unavoidable and the caller is told so before proceeding.
 */
class RollbackService(
    private val context: Context,
    private val db: MorphDatabase,
    private val sessionInstaller: SessionInstaller,
    private val rootInstaller: RootMountInstaller
) {

    /** Whether a rollback is possible, and what it would cost. */
    data class Availability(
        val canRollback: Boolean,
        val backup: ApkBackupEntity?,
        /** True when rolling back re-installs the stock app and therefore clears its data. */
        val losesAppData: Boolean,
        val reason: String
    )

    /** Describes what a rollback for [packageName] would do. */
    suspend fun availability(packageName: String, wasMounted: Boolean): Availability =
        withContext(Dispatchers.IO) {
            val backup = db.apkBackupDao().latestFor(packageName)
                ?: return@withContext Availability(
                    canRollback = false,
                    backup = null,
                    losesAppData = false,
                    reason = "No original APK was saved for this app, so it cannot be restored."
                )

            val file = File(backup.filePath)
            if (!file.isFile || file.length() == 0L) {
                return@withContext Availability(
                    canRollback = false,
                    backup = backup,
                    losesAppData = false,
                    reason = "The saved original APK is missing from storage."
                )
            }

            Availability(
                canRollback = true,
                backup = backup,
                // A mounted app was never replaced, so removing the mount is lossless.
                losesAppData = !wasMounted,
                reason = if (wasMounted) {
                    "Removes the mount so the stock app is used again. App data is kept."
                } else {
                    "Re-installs the original APK ${backup.versionName}. The signature changes " +
                        "back, so Android clears the app's data."
                }
            )
        }

    /** Outcome of a rollback attempt. */
    sealed interface Result {
        data class Restored(val versionName: String) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Restores [packageName] to its saved original.
     *
     * @param wasMounted whether the patched app was applied by mount rather than installed.
     */
    suspend fun rollback(packageName: String, wasMounted: Boolean): Result =
        withContext(Dispatchers.IO) {
            val state = availability(packageName, wasMounted)
            val backup = state.backup
                ?: return@withContext Result.Failed(state.reason)
            if (!state.canRollback) {
                return@withContext Result.Failed(state.reason)
            }

            try {
                if (wasMounted) {
                    removeMountModule(packageName)
                    db.supportedAppDao().markPatched(
                        packageName = packageName,
                        at = System.currentTimeMillis(),
                        version = backup.versionName,
                        bundleTag = null,
                        bundleSha = null,
                        mounted = false
                    )
                    Log.i(TAG, "Removed the mount for $packageName; stock app is in use again")
                    return@withContext Result.Restored(backup.versionName)
                }

                val apk = File(backup.filePath)
                if (!sessionInstaller.isAvailable()) {
                    return@withContext Result.Failed(
                        "Cannot restore: the installer permission is not granted."
                    )
                }

                when (val result = sessionInstaller.install(apk, packageName, backup.versionName)) {
                    is InstallResult.Success -> {
                        Log.i(TAG, "Restored $packageName to ${backup.versionName}")
                        Result.Restored(backup.versionName)
                    }

                    is InstallResult.PendingUserAction -> {
                        // The platform will confirm; treat it as accepted rather than failed.
                        Log.i(TAG, "Restore of $packageName awaiting user confirmation")
                        Result.Restored(backup.versionName)
                    }

                    is InstallResult.Failure ->
                        Result.Failed("Restore failed: ${result.message}")

                    is InstallResult.NotPermitted ->
                        Result.Failed("Cannot restore: install permission is not granted.")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Rollback failed for $packageName", t)
                Result.Failed(t.message ?: t.javaClass.simpleName)
            }
        }

    /**
     * Deletes the mount module so the stock APK is used again.
     *
     * The bind mount itself disappears on the next boot; this only removes the module that
     * installs it. Deliberately does not attempt to `umount` a live path, which can leave the
     * app in a half-mounted state.
     */
    private fun removeMountModule(packageName: String) {
        val dir = dev.morphexposed.domain.install.RootModuleScripts.moduleDir(packageName)
        dev.morphexposed.domain.install.RootShell.run(
            "rm -rf ${dev.morphexposed.domain.install.RootModuleScripts.quote(dir)}"
        ) { it.isSuccess }
    }

    private companion object {
        const val TAG = "MorphXRollback"
    }
}
