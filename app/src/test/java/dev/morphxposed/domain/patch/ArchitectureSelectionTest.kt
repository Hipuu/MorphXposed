package dev.morphxposed.domain.patch

import app.morphe.patcher.resource.CpuArchitecture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the ABI set handed to Morphe's patcher.
 *
 * The patcher drops native libraries for architectures outside this set, so the decision has a
 * real effect on the output: keeping an ABI the device cannot run wastes space, and dropping one
 * it can run breaks the app. An empty set means "keep everything" to the patcher, which is why
 * unmappable input must fall back to empty rather than to a guess.
 */
class ArchitectureSelectionTest {

    @Test
    fun `maps the ABI names Android reports`() {
        assertEquals(CpuArchitecture.ARM64_V8A, ArchitectureSelection.fromAbiName("arm64-v8a"))
        assertEquals(CpuArchitecture.ARMEABI_V7A, ArchitectureSelection.fromAbiName("armeabi-v7a"))
        assertEquals(CpuArchitecture.ARMEABI, ArchitectureSelection.fromAbiName("armeabi"))
        assertEquals(CpuArchitecture.X86, ArchitectureSelection.fromAbiName("x86"))
        assertEquals(CpuArchitecture.X86_64, ArchitectureSelection.fromAbiName("x86_64"))
        assertEquals(CpuArchitecture.MIPS, ArchitectureSelection.fromAbiName("mips"))
        assertEquals(CpuArchitecture.MIPS64, ArchitectureSelection.fromAbiName("mips64"))
    }

    @Test
    fun `is case and whitespace tolerant`() {
        // Build.SUPPORTED_ABIS is lower-case in practice, but the mapping must not silently fail
        // on a differently-cased value.
        assertEquals(CpuArchitecture.ARM64_V8A, ArchitectureSelection.fromAbiName("ARM64-V8A"))
        assertEquals(CpuArchitecture.ARM64_V8A, ArchitectureSelection.fromAbiName(" arm64-v8a "))
    }

    @Test
    fun `an unknown ABI maps to nothing rather than a guess`() {
        assertEquals(null, ArchitectureSelection.fromAbiName("riscv64"))
        assertEquals(null, ArchitectureSelection.fromAbiName(""))
        assertEquals(null, ArchitectureSelection.fromAbiName("not-an-abi"))
    }

    @Test
    fun `a typical 64-bit device keeps both its ABIs`() {
        val keep = ArchitectureSelection.keepFor(listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals(
            setOf(CpuArchitecture.ARM64_V8A, CpuArchitecture.ARMEABI_V7A, CpuArchitecture.ARMEABI),
            keep
        )
    }

    @Test
    fun `an unmappable device falls back to keeping everything`() {
        // Empty means "keep everything" to the patcher. Returning a partial or guessed set here
        // would strip libraries the device needs.
        val keep = ArchitectureSelection.keepFor(listOf("something-new", "another"))
        assertTrue("an unmappable device must fall back to the safe default", keep.isEmpty())
    }

    @Test
    fun `unknown entries are dropped but known ones are kept`() {
        val keep = ArchitectureSelection.keepFor(listOf("arm64-v8a", "riscv64"))
        assertEquals(setOf(CpuArchitecture.ARM64_V8A), keep)
    }

    @Test
    fun `an empty device list keeps everything`() {
        assertTrue(ArchitectureSelection.keepFor(emptyList()).isEmpty())
    }

    @Test
    fun `the description names the architectures`() {
        val text = ArchitectureSelection.describe(
            setOf(CpuArchitecture.ARM64_V8A, CpuArchitecture.ARMEABI_V7A)
        )
        assertTrue("the description must list them, was: $text", text.contains("arm64"))
        assertTrue("it must list every one, was: $text", text.contains("v7a") || text.contains("v8a"))

        assertTrue(
            "an empty set must describe the fallback",
            ArchitectureSelection.describe(emptySet()).contains("all architectures")
        )
    }
}
