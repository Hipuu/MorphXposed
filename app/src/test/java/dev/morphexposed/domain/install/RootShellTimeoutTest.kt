package dev.morphexposed.domain.install

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the rule that no `su` invocation may block forever.
 *
 * Found the hard way: probing root on a real OnePlus 13 wedged the entire adb shell queue, because
 * the superuser manager was showing a prompt and the process simply never exited. The same path
 * runs from the Settings screen — `PermissionStatus` calls `hasRoot()` — so opening Settings would
 * have frozen the app with no way out.
 *
 * The property is structural and checked by reading the source: a behavioural test would need a
 * real `su` that hangs, which is exactly what a unit test cannot provide.
 */
class RootShellTimeoutTest {

    /** The RootShell source, located from whichever directory the test runs in. */
    private fun source(): File =
        File("src/main/java/dev/morphexposed/domain/install/Installers.kt")
            .takeIf { it.isFile }
            ?: File("app/src/main/java/dev/morphexposed/domain/install/Installers.kt")

    @Test
    fun `the root shell source is locatable`() {
        assertTrue(
            "could not find Installers.kt from ${File(".").absolutePath}",
            source().isFile
        )
    }

    @Test
    fun `no su invocation waits without a deadline`() {
        val text = source().readText()
        val rootShell = text.substringAfter("object RootShell")

        // A bare waitFor() is unbounded; every call must pass a timeout. Comment lines are
        // stripped first: the doc comment above explains the bug using the exact text, and
        // matching that would make this check reject its own explanation.
        val code = rootShell.lines()
            .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
            .joinToString("\n")
        val bareWaits = Regex("""waitFor\(\s*\)""").findAll(code).count()
        assertTrue(
            "RootShell has $bareWaits unbounded waitFor() call(s); a superuser prompt would " +
                "block them forever",
            bareWaits == 0
        )
    }

    @Test
    fun `the su helper is bounded and destroys a hung process`() {
        val rootShell = source().readText().substringAfter("object RootShell")

        assertTrue(
            "runSu must pass a timeout to waitFor",
            rootShell.contains("waitFor(timeoutMs")
        )
        assertTrue(
            "a timed-out su must be killed, not merely abandoned",
            rootShell.contains("destroyForcibly")
        )
        assertTrue(
            "the probe must use the bounded helper",
            rootShell.contains("runSu(listOf(\"su\", \"-c\", \"id\"))")
        )
    }

    @Test
    fun `a timeout is reported as no root rather than throwing`() {
        val rootShell = source().readText().substringAfter("object RootShell")
        // hasRoot() treats a null (timed-out) result as false, so the Permissions card can render
        // instead of the app hanging.
        assertTrue(
            "hasRoot must tolerate a null result",
            rootShell.contains("runSu(argv(\"id\"))?.second?.contains(\"uid=0\") ?: false")
        )
    }

    @Test
    fun `the file operations are bounded too`() {
        val rootShell = source().readText().substringAfter("object RootShell")
        assertTrue(
            "pushFile must bound its wait; a root prompt would hang a patch run",
            Regex("""waitFor\(\s*120""").containsMatchIn(rootShell)
        )
        assertTrue(
            "writeText must bound its wait",
            Regex("""waitFor\(\s*30""").containsMatchIn(rootShell)
        )
    }
}
