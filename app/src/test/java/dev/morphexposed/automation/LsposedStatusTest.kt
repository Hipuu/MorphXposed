package dev.morphexposed.automation

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Covers what the app claims about LSPosed.
 *
 * The important property is **honesty**: a module cannot read its own activation state, so the
 * class must not imply it can. These tests pin both the detection it does perform and the
 * wording it falls back to, because a settings row that shows a confident green tick it cannot
 * justify is worse than one that says "unknown".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LsposedStatusTest {

    private lateinit var context: Context
    private lateinit var status: LsposedStatus

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        status = LsposedStatus(context)
        BridgeObservations.reset()
    }

    @After
    fun tearDown() {
        BridgeObservations.reset()
    }

    private fun install(packageName: String, versionName: String) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        }
        shadowOf(context.packageManager).installPackage(info)
    }

    @Test
    fun `with nothing installed it reports no LSPosed`() {
        val state = status.read(bridgeObserved = false)

        assertFalse(state.managerInstalled)
        assertFalse(state.moduleInstalled)
        assertFalse(state.bridgeObserved)
        assertNull(state.managerVersion)
        assertNull(state.moduleVersion)
    }

    @Test
    fun `an installed manager is detected with its version`() {
        install("org.lsposed.manager", "1.9.2")

        val state = status.read(bridgeObserved = false)

        assertTrue("the manager must be detected", state.managerInstalled)
        assertTrue("its version must be reported", state.managerVersion == "1.9.2")
        assertFalse("the module is not installed", state.moduleInstalled)
    }

    @Test
    fun `an installed module is detected`() {
        install("dev.morphexposed.events", "1.0.0")

        val state = status.read(bridgeObserved = false)
        assertTrue(state.moduleInstalled)
        assertTrue(state.moduleVersion == "1.0.0")
    }

    @Test
    fun `the debug variant of the module is also detected`() {
        // The debug build appends a suffix; failing to look for it would show "not installed"
        // to a developer who has it installed.
        install("dev.morphexposed.events.debug", "1.0.0-debug")

        val state = status.read(bridgeObserved = false)
        assertTrue(state.moduleInstalled)
    }

    @Test
    fun `a delivered event is the only thing reported as active`() {
        // Installed but silent: the module may be disabled, out of scope, or waiting for a
        // reboot. The app must not claim it works.
        install("org.lsposed.manager", "1.9.2")
        install("dev.morphexposed.events", "1.0.0")

        val silent = status.read(bridgeObserved = false)
        assertFalse("installed is not the same as active", silent.bridgeObserved)
        assertTrue(
            "the wording must not claim activation, was: ${silent.describe()}",
            silent.describe().contains("cannot be read") ||
                silent.describe().contains("Activation")
        )

        val active = status.read(bridgeObserved = true)
        assertTrue(active.bridgeObserved)
        assertTrue(
            "an arriving event must be reported as active, was: ${active.describe()}",
            active.describe().contains("active")
        )
    }

    @Test
    fun `every state describes itself without overclaiming`() {
        val states = listOf(
            LsposedStatus.State(false, null, false, null, false),
            LsposedStatus.State(true, "1.9.2", false, null, false),
            LsposedStatus.State(true, "1.9.2", true, "1.0.0", false),
            LsposedStatus.State(true, "1.9.2", true, "1.0.0", true)
        )
        states.forEach { state ->
            val text = state.describe()
            assertTrue("a state must describe itself", text.isNotBlank())
            // No state except the observed one may imply the module is running.
            if (!state.bridgeObserved) {
                assertFalse(
                    "without an event the wording must not say 'active - events are arriving', " +
                        "was: $text",
                    text.contains("events are arriving")
                )
            }
        }
    }

    @Test
    fun `bridge observations record what arrived`() {
        assertFalse(BridgeObservations.observed)
        assertNull(BridgeObservations.lastEvent)

        BridgeObservations.record("INSTALLED")

        assertTrue(BridgeObservations.observed)
        assertTrue(BridgeObservations.lastEvent == "INSTALLED")

        BridgeObservations.record("UPDATED")
        assertTrue("the latest event must win", BridgeObservations.lastEvent == "UPDATED")

        BridgeObservations.reset()
        assertFalse("reset must clear both", BridgeObservations.observed)
        assertNull(BridgeObservations.lastEvent)
    }

    @Test
    fun `reading status never throws`() {
        // Package lookups are refused on some devices; the row must still render.
        val state = runCatching { status.read() }.getOrNull()
        assertNotNull("reading status must not throw", state)
    }

    @Test
    fun `the platform gate matches the module's minimum API`() {
        assertTrue(
            "API 26 satisfies the module's xposed API 82 requirement",
            LsposedStatus.isPlatformSupported()
        )
        assertTrue("the minimum LSPosed API must be the one declared in the manifest",
            LsposedStatus.MIN_XPOSED_API == 82)
    }
}
