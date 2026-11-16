package dev.morphxposed.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import dev.morphxposed.MorphXposedApp
import dev.morphxposed.data.db.MorphDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives the platform's verdict on an installer session. Android delivers the result here
 * rather than returning it from `commit`, because the user may take any amount of time to
 * confirm the install prompt.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)

        val success = status == PackageInstaller.STATUS_SUCCESS
        val note = when {
            success -> "Installed $packageName"
            status == PackageInstaller.STATUS_PENDING_USER_ACTION -> "Waiting for user confirmation"
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> "Install cancelled"
            else -> "Install failed ($status): ${message.orEmpty()}"
        }

        if (!success && packageName != null) {
            // Record the failure against the most recent run for this package so the Logs screen
            // shows why nothing changed on the device.
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    // Reuse the application's database when it is available. Building a second
                    // instance over the same file would open another connection for a single
                    // row update, and the app's instance already holds the run this refers to.
                    val db = (context.applicationContext as? MorphXposedApp)?.database
                        ?: MorphDatabase.build(context)
                    val latest = db.patchRunDao().latestFor(packageName)
                    if (latest != null) {
                        db.patchRunDao().complete(
                            id = latest.id,
                            status = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) "INSTALLING" else "FAILED",
                            finishedAt = System.currentTimeMillis(),
                            applied = latest.appliedPatches,
                            failed = latest.failedPatches,
                            installed = false,
                            strategy = latest.installStrategy,
                            log = latest.log + "\n" + note,
                            outputPath = latest.outputApkPath,
                            error = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) null else note
                        )
                    }
                }
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "dev.morphxposed.INSTALL_RESULT"
    }
}
