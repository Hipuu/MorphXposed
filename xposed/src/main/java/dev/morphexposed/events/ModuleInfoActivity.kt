package dev.morphexposed.events

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shown when the user opens the module from the launcher.
 *
 * LSPosed exposes no API a module can call to read or change its own activation state, so this
 * screen explains how to activate the module rather than pretending to detect it.
 */
class ModuleInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = """
                MorpheXposed Events
                
                This is an optional LSPosed module. It lets MorpheXposed react to app installs and
                updates immediately instead of at the next scheduled check.
                
                It does NOT patch anything. Morphe patches are applied by rewriting the APK, which
                only the main MorpheXposed app does. This module only reports package events.
                
                MorpheXposed works without this module.
                
                To activate:
                  1. Install MorpheXposed (the main app).
                  2. Open LSPosed Manager.
                  3. Enable "MorpheXposed Events".
                  4. Set its scope to "System Framework" (android).
                  5. Reboot, or restart the system framework.
                
                Scope must be System Framework only. Other apps are not touched.
            """.trimIndent()
            textSize = 14f
            setPadding(48, 96, 48, 48)
        }

        setContentView(ScrollView(this).apply { addView(text) })
    }
}
