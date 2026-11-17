package dev.morphxposed.ui.viewmodel

import dev.morphxposed.domain.backup.RollbackService

/**
 * Builds the restore confirmation and the words it uses.
 *
 * Kept pure so it can be tested without a database or a coroutine. The reason it exists at all is
 * that restoring a session-installed app re-installs the original APK, which changes the signature
 * back and makes Android **clear the app's data**. `RollbackService.availability()` knew this from
 * the start and nothing displayed it, so a restore was one tap away with no warning.
 *
 * The dialog copy lives here rather than inline in the screen so the wording that warns about data
 * loss is covered by a test.
 */
object RestorePresentation {

    /** What to show before restoring, or null when the restore cannot happen at all. */
    fun prompt(
        packageName: String,
        appLabel: String?,
        availability: RollbackService.Availability?
    ): MainViewModel.RestorePrompt? {
        if (availability == null || !availability.canRollback) return null
        return MainViewModel.RestorePrompt(
            packageName = packageName,
            appLabel = appLabel?.takeIf { it.isNotBlank() } ?: packageName,
            losesAppData = availability.losesAppData,
            reason = availability.reason
        )
    }

    fun title(prompt: MainViewModel.RestorePrompt): String =
        "Restore ${prompt.appLabel}?"

    /**
     * The dialog body.
     *
     * When data will be lost the message says so in its own paragraph, because the consequence is
     * irreversible and burying it in a sentence would be the wrong trade.
     */
    fun body(prompt: MainViewModel.RestorePrompt): String =
        if (prompt.losesAppData) {
            "${prompt.reason}\n\n" +
                "This will remove the app's stored data, including any account signed in to it. " +
                "It cannot be undone."
        } else {
            prompt.reason
        }

    /** The confirm button's label: it must not invite a destructive action by looking routine. */
    fun confirmLabel(prompt: MainViewModel.RestorePrompt): String =
        if (prompt.losesAppData) "Restore and clear data" else "Restore"

    fun dismissLabel(): String = "Cancel"
}
