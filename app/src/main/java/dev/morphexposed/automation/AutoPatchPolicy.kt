package dev.morphexposed.automation

import dev.morphexposed.data.db.SupportedAppEntity
import dev.morphexposed.data.prefs.SettingsStore

/**
 * Decides whether a given trigger is allowed to patch a given app.
 *
 * This is the gate in front of all automatic patching, so getting it wrong has direct
 * consequences in both directions: too permissive and the app patches something the user never
 * opted into; too strict and automation silently never runs. It is a pure function so every
 * combination can be checked without a database, a coroutine, or a device.
 */
object AutoPatchPolicy {

    /** Why an app was not patched, or [Allowed] when it may be. */
    sealed interface Decision {
        data object Allowed : Decision

        /** The user hid the app. */
        data object Hidden : Decision

        /** The user did not enable automatic patching for this app. */
        data object AppNotOptedIn : Decision

        /** The global trigger for this reason is switched off. */
        data class TriggerDisabled(val reason: TriggerReason) : Decision

        val isAllowed: Boolean get() = this is Allowed

        /** A short explanation for the run summary. */
        fun describe(): String = when (this) {
            Allowed -> "Allowed"
            Hidden -> "Hidden"
            AppNotOptedIn -> "Automatic patching is off for this app"
            is TriggerDisabled -> when (reason) {
                TriggerReason.APP_INSTALLED -> "Not patching newly installed apps"
                TriggerReason.APP_UPDATED -> "Not repatching updated apps"
                TriggerReason.PATCHES_UPDATED -> "Not patching on patch updates"
                TriggerReason.PERIODIC -> "Not patching on the schedule"
                TriggerReason.MANUAL -> "Automatic patching is off"
            }
        }
    }

    /**
     * Decides whether [reason] may patch [app] under [snapshot].
     *
     * A manual run is always allowed for a visible app: the user pressing "Patch" is consent.
     * Every automatic trigger additionally requires the per-app opt-in, because the global
     * switches are a policy, not consent for any particular app.
     */
    fun decide(
        app: SupportedAppEntity,
        reason: TriggerReason,
        snapshot: SettingsStore.Snapshot
    ): Decision {
        if (app.hidden) return Decision.Hidden

        return when (reason) {
            // Consent is explicit: the user selected this app right now.
            TriggerReason.MANUAL -> Decision.Allowed

            TriggerReason.APP_INSTALLED -> when {
                !snapshot.autoPatchOnInstall -> Decision.TriggerDisabled(reason)
                !app.autoPatchEnabled -> Decision.AppNotOptedIn
                else -> Decision.Allowed
            }

            TriggerReason.APP_UPDATED -> when {
                !snapshot.autoPatchOnUpdate -> Decision.TriggerDisabled(reason)
                !app.autoPatchEnabled -> Decision.AppNotOptedIn
                else -> Decision.Allowed
            }

            // These run for the set of apps the user opted in, so the per-app flag is the only
            // condition beyond the app being visible.
            TriggerReason.PATCHES_UPDATED,
            TriggerReason.PERIODIC -> if (app.autoPatchEnabled) {
                Decision.Allowed
            } else {
                Decision.AppNotOptedIn
            }
        }
    }

    /** Every app this reason would act on. */
    fun eligible(
        apps: List<SupportedAppEntity>,
        reason: TriggerReason,
        snapshot: SettingsStore.Snapshot
    ): List<SupportedAppEntity> = apps.filter { decide(it, reason, snapshot).isAllowed }
}
