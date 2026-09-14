package app.knotwork.android.presentation.ui.about

import app.knotwork.android.domain.constants.RepositoryLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Drift guard for the outbound links of the About screen ([AboutLinks]).
 *
 * Both links are hand-written strings that no compiler check covers. The privacy
 * link is the fragile one: it targets a **file** in the repository, and it is the
 * same URL handed to the app stores as the mandatory privacy-policy link, so a
 * rename or a move breaks the About screen and a store listing at once. An
 * earlier revision pointed at a README heading anchor, which is worse than a
 * broken link — a stale anchor still opens the page, just at the wrong place,
 * and it silently passes for as long as nobody scrolls.
 */
class AboutLinksTest {

    @Test
    fun `given the license link when read then it is the canonical Apache 2 0 url`() {
        assertEquals(
            "License link must point at the canonical Apache 2.0 text",
            "https://www.apache.org/licenses/LICENSE-2.0",
            AboutLinks.LICENSE_URL,
        )
    }

    @Test
    fun `given the privacy link when read then it points at the current repository`() {
        // Guards against the repository rename leaving a stale URL behind: GitHub
        // keeps a redirect from the old name, but that redirect dies the moment
        // anyone creates a repository under the old name.
        assertTrue(
            "Privacy link must target the current repository, was ${AboutLinks.PRIVACY_URL}",
            AboutLinks.PRIVACY_URL.startsWith("$REPOSITORY_URL/"),
        )
    }

    @Test
    fun `given the privacy link when resolved then the repository holds that file`() {
        val path = AboutLinks.PRIVACY_URL.removePrefix("$REPOSITORY_URL/blob/main/")
        assertTrue(
            "Privacy link must address a file on the default branch, was ${AboutLinks.PRIVACY_URL}",
            path != AboutLinks.PRIVACY_URL && !path.contains('#'),
        )
        assertTrue(
            "Privacy link targets '$path', which does not exist in the repository",
            File(repositoryRoot(), path).isFile,
        )
    }

    @Test
    fun `given the privacy link when read then it stays pinned to the default branch`() {
        // The documentation links of `DocumentationLinks` are pinned to the tag
        // of the installed version; this one deliberately is not. What binds a
        // user is the current privacy policy, not the edition current when they
        // installed — and this URL is filed with the app stores, where a link
        // that moved every release would be a link that breaks. Asserted so the
        // exemption cannot be "tidied up" into the version-pinned rule.
        assertTrue(
            "Privacy link must resolve on the default branch, was ${AboutLinks.PRIVACY_URL}",
            AboutLinks.PRIVACY_URL.startsWith("$REPOSITORY_URL/blob/main/"),
        )
    }

    @Test
    fun `given the repository constant when read then the About links are built from it`() {
        // Closes the defect this constant was extracted for: the host used to be
        // written out three times, and three copies survive a repository rename
        // by staying wrong in three places.
        assertEquals(REPOSITORY_URL, RepositoryLinks.REPOSITORY_URL)
    }

    @Test
    fun `given the privacy policy when read then it names the paths that leave the device`() {
        // The policy is what a store reviewer and a user read instead of the code.
        // If a data path ever stops being named there, the document has drifted
        // from the app it describes — these are the paths SECURITY.md and the
        // README both document as capable of sending data off the device.
        val policy = File(repositoryRoot(), PRIVACY_POLICY_FILE).readText()
        listOf("Crashlytics", "MCP", "Hugging Face", "http_request")
            .forEach { path ->
                assertTrue("$PRIVACY_POLICY_FILE never mentions '$path'", policy.contains(path))
            }
    }

    /**
     * Walks up from the test's working directory (the Gradle module directory) to
     * the repository root — the first ancestor that holds the privacy policy.
     *
     * @return The repository root directory.
     */
    private fun repositoryRoot(): File {
        val workingDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        var dir: File? = File(workingDir)
        while (dir != null) {
            if (File(dir, PRIVACY_POLICY_FILE).isFile) return dir
            dir = dir.parentFile
        }
        error("$PRIVACY_POLICY_FILE not found walking up from $workingDir")
    }

    private companion object {
        /** Canonical public repository URL, without a trailing slash or fragment. */
        const val REPOSITORY_URL = "https://github.com/alexeyw/knotwork"

        /** Repository-root file the About screen's privacy link resolves to. */
        const val PRIVACY_POLICY_FILE = "PRIVACY.md"
    }
}
