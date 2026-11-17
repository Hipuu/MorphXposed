package dev.morphxposed.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds the share intent for a patched APK.
 *
 * When no installer is available the orchestrator still produces a signed APK and the run records
 * "APK exported instead". That file lands in the app's private storage, and without a share action
 * the claim was hollow: an unrooted user had no way to reach it. The `FileProvider` and its
 * `file_paths.xml` were both declared from the start and never used.
 *
 * Kept separate from the screen so the intent shape — the one part that is easy to get wrong and
 * hard to test through Compose — can be checked directly.
 */
object ApkSharing {

    /** The mime type Android uses for an APK; a wrong one makes the chooser offer nothing useful. */
    const val APK_MIME = "application/vnd.android.package-archive"

    /**
     * Resolves [apk] to a content URI the platform will grant a reader to.
     *
     * Returns null when the file is absent: sharing a path that does not exist produces a chooser
     * entry that fails after the user picks it, which is worse than not offering it.
     */
    fun contentUri(context: Context, apk: File?): Uri? {
        if (!isShareable(apk?.absolutePath)) return null
        return runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk!!)
        }.getOrNull()
    }

    /**
     * Whether [apk] sits under a root that `file_paths.xml` exposes.
     *
     * `FileProvider` throws `IllegalArgumentException("Failed to find configured root")` for any
     * file outside those roots, which surfaces as a share that silently does nothing. The check is
     * broken out because it is the precondition that actually matters, and because it can be
     * verified without asking `FileProvider` to canonicalise a path.
     */
    fun isUnderSharedRoot(context: Context, apk: File): Boolean {
        // Must match res/xml/file_paths.xml: files-path "patched/" and cache-path ".".
        val roots = listOf(
            File(context.filesDir, "patched"),
            context.cacheDir
        )
        return roots.any { root ->
            runCatching { apk.canonicalPath.startsWith(root.canonicalPath + File.separator) }
                .getOrDefault(false)
        }
    }

    /** The chooser intent for a resolved [uri]. */
    fun chooser(uri: Uri, label: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = APK_MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, label)
            // Both are needed: the grant flag alone is ignored by some receivers.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share $label").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** The filename to present, without the internal path. */
    fun displayName(apk: File): String = apk.name

    /**
     * Whether a saved run's APK can still be shared.
     *
     * The file may have been cleared by the system, so the record alone is not enough.
     */
    fun isShareable(path: String?): Boolean =
        path != null && path.isNotBlank() && File(path).let { it.isFile && it.length() > 0 }
}
