package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit coverage for [PinnedNdk], over fake SDK layouts shaped like the real one
 * (`ndk/<revision>/source.properties`, `toolchains/llvm/prebuilt/<host>/bin/llvm-strip`).
 *
 * The first case is the host the audit built on: NDKs installed, none of them the
 * one the build asks for — the state in which AGP ships unstripped libraries
 * without failing.
 */
class PinnedNdkTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val pinned = "28.2.13676358"

    @Test
    fun `given other NDKs installed but not the pinned one then the pinned one is reported missing`() {
        val sdk = temporaryFolder.newFolder("sdk")
        installNdk(sdk, "29.0.14206865")
        installNdk(sdk, "30.0.14904198", revision = "30.0.14904198-beta1")

        assertEquals(
            "NDK $pinned is not installed in ${sdk.resolve("ndk/$pinned").path}.",
            PinnedNdk.problem(sdk, pinned),
        )
    }

    @Test
    fun `given the pinned NDK with a strip tool then there is no problem`() {
        val sdk = temporaryFolder.newFolder("sdk")
        installNdk(sdk, pinned)

        assertNull(PinnedNdk.problem(sdk, pinned))
    }

    @Test
    fun `given a pre-release suffix on the installed revision then its numeric part is compared`() {
        val sdk = temporaryFolder.newFolder("sdk")
        installNdk(sdk, "30.0.14904198", revision = "30.0.14904198-beta1")

        assertNull(PinnedNdk.problem(sdk, "30.0.14904198"))
    }

    @Test
    fun `given a directory named for the pin holding another revision then the mismatch is reported`() {
        val sdk = temporaryFolder.newFolder("sdk")
        installNdk(sdk, pinned, revision = "27.3.13750724")

        assertEquals(
            "${sdk.resolve("ndk/$pinned").path} holds NDK 27.3.13750724, not the pinned $pinned.",
            PinnedNdk.problem(sdk, pinned),
        )
    }

    @Test
    fun `given the pinned NDK without llvm-strip then the missing tool is reported`() {
        val sdk = temporaryFolder.newFolder("sdk")
        installNdk(sdk, pinned, withStrip = false)

        assertEquals(
            "NDK $pinned in ${sdk.resolve("ndk/$pinned").path} has no llvm-strip under toolchains/llvm/prebuilt/*/bin.",
            PinnedNdk.problem(sdk, pinned),
        )
    }

    /** Lays out an NDK the way `sdkmanager` installs one. */
    private fun installNdk(sdk: File, directory: String, revision: String = directory, withStrip: Boolean = true) {
        val ndk = sdk.resolve("ndk/$directory").apply { mkdirs() }
        ndk.resolve("source.properties").writeText("Pkg.Desc = Android NDK\nPkg.Revision = $revision\n")
        val bin = ndk.resolve("toolchains/llvm/prebuilt/darwin-x86_64/bin").apply { mkdirs() }
        if (withStrip) bin.resolve("llvm-strip").writeText("")
    }
}
