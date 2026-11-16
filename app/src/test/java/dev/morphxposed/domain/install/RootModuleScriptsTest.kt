package dev.morphxposed.domain.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Inspects the shell that gets written into `/data/adb/modules/...`.
 *
 * This output is the riskiest thing the app produces and it cannot be exercised without root,
 * so it is verified by inspection: the scripts must be syntactically well-formed, must quote
 * interpolated values, and must keep the version guard that stops a mismatched APK being
 * mounted over a stock app.
 */
class RootModuleScriptsTest {

    private val yt = "com.google.android.youtube"

    @Test
    fun `module id is a legal directory name for any package`() {
        assertEquals("morphxposed_com_google_android_youtube", RootModuleScripts.moduleId(yt))
        // Hyphens and digits are common in package names.
        assertEquals(
            "morphxposed_com_example_app_2_beta",
            RootModuleScripts.moduleId("com.example.app-2-beta")
        )
        assertTrue(
            "the id must not contain a path separator",
            RootModuleScripts.moduleId("com.example.a/b").none { it == '/' }
        )
    }

    @Test
    fun `module prop carries the required keys and a single-line description`() {
        val prop = RootModuleScripts.moduleProp(yt, "21.45.82")
        val lines = prop.trim().lines()

        assertTrue("module.prop must declare id", lines.any { it.startsWith("id=morphxposed_com_google_android_youtube") })
        assertTrue("module.prop must declare name", lines.any { it.startsWith("name=") })
        assertTrue("module.prop must declare version", lines.any { it == "version=21.45.82" })
        assertTrue("module.prop must declare versionCode", lines.any { it == "versionCode=1" })
        assertTrue("module.prop must declare author", lines.any { it.startsWith("author=") })
        assertTrue("module.prop must declare description", lines.any { it.startsWith("description=") })

        // A newline inside a value would corrupt the file for the root manager.
        val version = lines.first { it.startsWith("version=") }.removePrefix("version=")
        assertFalse("version must not contain a newline", version.contains("\n"))
    }

    @Test
    fun `module prop collapses a multi-line version into one line`() {
        val prop = RootModuleScripts.moduleProp(yt, "1.0\nmalicious=key")
        val lines = prop.trim().lines()
        assertTrue(
            "a newline in the version must not introduce a new key",
            lines.none { it.startsWith("malicious") }
        )
    }

    @Test
    fun `service script keeps the version guard`() {
        val script = RootModuleScripts.service(yt, "21.45.82")

        // The guard is what stops a mismatched mount crashing the app.
        assertTrue(
            "the service script must compare the installed version before mounting",
            script.contains("EXPECTED_VERSION") && script.contains("ACTUAL")
        )
        assertTrue(
            "the script must exit instead of mounting on a mismatch",
            script.contains("exit 0")
        )
        assertTrue("the script must wait for boot", script.contains("sys.boot_completed"))
        assertTrue("the script must bind mount", script.contains("mount -o bind"))
    }

    @Test
    fun `service script emits only well-formed lines`() {
        val script = RootModuleScripts.service(yt, "21.45.82")

        assertTrue("must start with a shebang", script.startsWith("#!/system/bin/sh"))
        assertTrue("must end with a newline", script.endsWith("\n"))
        // No leftover Kotlin interpolation survivors.
        assertFalse("no unresolved template markers", script.contains("\${'$'}"))
        assertFalse("no unresolved placeholder", script.contains("__"))
    }

    @Test
    fun `every interpolated value is quoted`() {
        // A package name cannot contain shell metacharacters, but the generator must not rely
        // on that: quoting is what makes the invariant explicit.
        val service = RootModuleScripts.service(yt, "1.0")
        assertTrue(
            "the package name must be single-quoted in the assignment",
            service.contains("PKG='$yt'")
        )

        val apply = RootModuleScripts.applyNowCommand(yt)
        assertTrue("the apply command must quote the module path", apply.contains("'/data/adb/modules/"))
    }

    @Test
    fun `quote escapes an embedded single quote`() {
        assertEquals("'abc'", RootModuleScripts.quote("abc"))
        assertEquals("'it'\\''s'", RootModuleScripts.quote("it's"))
    }

    @Test
    fun `apply command fails cleanly when the stock app is missing`() {
        val cmd = RootModuleScripts.applyNowCommand("com.example.not.installed")
        // Missing stock path or missing APK must exit non-zero rather than mounting nothing.
        assertTrue("must bail when the stock path is absent", cmd.contains("|| exit 1"))
        assertTrue("the command must resolve the stock path first", cmd.contains("pm path"))
    }

    @Test
    fun `post-fs-data mounts every apk in the module directory`() {
        val script = RootModuleScripts.postFsData()
        assertTrue(script.contains("for APK in"))
        assertTrue(script.contains("mount -o bind"))
        // Must skip gracefully when there is nothing to mount.
        assertTrue("must skip missing files", script.contains("continue"))
    }
}
