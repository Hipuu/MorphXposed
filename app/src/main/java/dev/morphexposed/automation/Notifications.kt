package dev.morphexposed.automation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.morphexposed.R
import dev.morphexposed.ui.MainActivity

/**
 * Notification channels and builders.
 *
 * Three channels exist because they have genuinely different urgency: a finished patch is
 * informational, a failure needs attention, and the ongoing patching notification is a
 * foreground-service requirement that must stay silent.
 */
class Notifications(private val context: Context) {

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        val channels = listOf(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Patching in progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while an app is being patched"
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_RESULT,
                "Patch results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Tells you when a patch finished" },
            NotificationChannel(
                CHANNEL_ACTION,
                "Needs attention",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Patch failures and required actions" }
        )
        manager.createNotificationChannels(channels)
    }

    fun ongoingProgress(title: String, text: String, progress: Int? = null): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (progress != null) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun patchSucceeded(appLabel: String, patchCount: Int, installed: Boolean) {
        val body = if (installed) {
            "$patchCount patches applied and installed."
        } else {
            "$patchCount patches applied. Open MorpheXposed to install."
        }
        manager.notify(
            idFor("ok", appLabel),
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$appLabel patched")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun patchFailed(appLabel: String, reason: String) {
        manager.notify(
            idFor("fail", appLabel),
            NotificationCompat.Builder(context, CHANNEL_ACTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Could not patch $appLabel")
                .setContentText(reason)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun actionRequired(appLabel: String, message: String) {
        manager.notify(
            idFor("action", appLabel),
            NotificationCompat.Builder(context, CHANNEL_ACTION)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("$appLabel needs attention")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun updatesAvailable(count: Int, tags: String) {
        val body = if (count == 1) {
            "A newer patch bundle is available ($tags)."
        } else {
            "$count patch sources have newer bundles."
        }
        manager.notify(
            NOTIFICATION_UPDATES,
            NotificationCompat.Builder(context, CHANNEL_RESULT)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Morphe patches updated")
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent())
                .build()
        )
    }

    fun cancelOngoing() = manager.cancel(NOTIFICATION_ONGOING)

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun idFor(kind: String, key: String) = (kind + key).hashCode()

    companion object {
        const val CHANNEL_ONGOING = "patching_ongoing"
        const val CHANNEL_RESULT = "patch_results"
        const val CHANNEL_ACTION = "patch_actions"

        const val NOTIFICATION_ONGOING = 1001
        const val NOTIFICATION_UPDATES = 1002
    }
}
