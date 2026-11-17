package dev.morphexposed.automation

import android.content.Context
import android.os.Build

/**
 * Reports the permissions and device capabilities the app actually depends on.
 *
 * The brief asks Settings to surface "permissions". That matters because the app has several
 * prerequisites whose absence produces a confusing failure far from the cause — an install that
 * silently becomes an exported APK, a periodic check that never fires, root mount that reports
 * "not available" with no explanation. Each one is a plain question to the platform, so each can
 * be answered precisely instead of guessed at.
 */
class PermissionStatus(private val context: Context) {

    /** One prerequisite, with what it is for and how to fix it. */
    data class Item(
        val name: String,
        val granted: Boolean,
        val description: String,
        /** What the user has to do, or null when nothing is needed. */
        val remedy: String?,
        /** True when the app is meaningfully degraded without it. */
        val required: Boolean
    )

    /** Every prerequisite, in the order a user would hit them. */
    fun read(): List<Item> = listOf(
        installPermission(),
        notificationPermission(),
        rootAvailability(),
        lsposedAvailability()
    )

    private fun installPermission(): Item {
        val granted = runCatching {
            context.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false)
        return Item(
            name = "Install apps",
            granted = granted,
            description = "Needed to install a patched APK. Without it the patched APK is saved " +
                "to storage but never reaches the device.",
            remedy = if (granted) null else
                "Grant \"Install unknown apps\" for MorpheXposed in system settings.",
            required = true
        )
    }

    private fun notificationPermission(): Item {
        // POST_NOTIFICATIONS only exists from API 33; before that it is implicitly granted.
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
        } else {
            true
        }
        return Item(
            name = "Notifications",
            granted = granted,
            description = "Used to report patch results and to tell you when something needs " +
                "your attention.",
            remedy = if (granted) null else "Allow notifications for MorpheXposed.",
            // Automation still works without them; the user simply is not told.
            required = false
        )
    }

    private fun rootAvailability(): Item {
        val hasRoot = runCatching {
            dev.morphexposed.domain.install.RootShell.hasRoot()
        }.getOrDefault(false)
        return Item(
            name = "Root access",
            granted = hasRoot,
            description = "Optional. Enables root mount, which keeps the stock signature and " +
                "the app's data. Without root the patched app must replace the stock one, " +
                "which clears its data.",
            remedy = if (hasRoot) null else
                "Grant root to MorpheXposed, or use the session installer and accept that the " +
                    "app's data is cleared.",
            required = false
        )
    }

    private fun lsposedAvailability(): Item {
        val state = runCatching { LsposedStatus(context).read() }.getOrNull()
        val available = state?.managerInstalled == true
        return Item(
            // The card appends " (optional)" to every item with required = false, so the name must
            // not carry it too — doing both rendered "LSPosed (optional) (optional)".
            name = "LSPosed",
            granted = available,
            description = "Enables real-time app install and update detection. Automation works " +
                "without it, on the scheduled check.",
            remedy = if (available) null else
                "Install LSPosed and the MorpheXposed Events module for immediate detection.",
            required = false
        )
    }
}
