package dev.morphexposed.domain.install

import org.junit.Test
import java.io.File

/**
 * Writes the generated root-manager scripts to disk so they can be executed for real.
 *
 * Reading a shell script and believing it is correct is exactly the mistake this project has
 * been burned by before. These files are consumed by `scripts/verify-root-scripts.sh`, which
 * runs them under `bash -n`, `sh -n`, and with the Android commands stubbed, to check that a
 * matching version mounts and a mismatched one refuses.
 *
 * The output is a build artifact, not a deliverable.
 */
class RootScriptsEmitForShellCheck {

    @Test
    fun `emit the generated scripts for shell verification`() {
        val out = File("build/root-scripts").apply {
            deleteRecursively()
            mkdirs()
        }

        val pkg = "com.example.target"
        File(out, "module.prop").writeText(RootModuleScripts.moduleProp(pkg, "1.0.0"))
        File(out, "post-fs-data.sh").writeText(RootModuleScripts.postFsData())
        File(out, "service.sh").writeText(RootModuleScripts.service(pkg, "1.0.0"))

        println("emitted to ${out.absolutePath}")
        listOf("module.prop", "post-fs-data.sh", "service.sh").forEach {
            val f = File(out, it)
            println("  $it: ${f.length()} bytes")
        }
    }
}
