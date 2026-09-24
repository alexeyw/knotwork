package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Unit coverage for [GitCommitId], against real git repositories.
 *
 * The property under test is the one reproducible builds need: one commit, one
 * identifier — on a full clone, on a depth-1 clone like CI's checkout, and in a
 * repository whose git configuration sets `core.abbrev`, as a developer's may.
 * With the previous command, `git rev-parse --short HEAD`, the three disagree.
 *
 * Global and system git configuration are shut out, so the host running the test
 * cannot supply the variation itself; the repositories carry their own.
 */
class GitCommitIdTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var origin: File
    private lateinit var shallowClone: File
    private lateinit var configuredClone: File

    @Before
    fun createRepositories() {
        origin = temporaryFolder.newFolder("origin")
        git(origin, "init", "-q")
        repeat(3) { git(origin, "commit", "-q", "--allow-empty", "-m", "commit $it") }
        shallowClone = temporaryFolder.root.resolve("shallow")
        // `--depth` is ignored for a plain local path; a `file://` URL makes git honour it.
        git(temporaryFolder.root, "clone", "-q", "--depth", "1", "file://${origin.absolutePath}", shallowClone.path)
        configuredClone = temporaryFolder.root.resolve("configured")
        git(temporaryFolder.root, "clone", "-q", origin.path, configuredClone.path)
        git(configuredClone, "config", "core.abbrev", "12")
    }

    @Test
    fun `given one commit in three clones when the command runs then each prints the same text`() {
        val outputs = listOf(origin, shallowClone, configuredClone).map { run(GitCommitId.COMMAND, it) }

        assertEquals(1, outputs.distinct().size)
    }

    @Test
    fun `given one commit in three clones then the identifier is the first eight characters of its hash`() {
        val hash = git(origin, "rev-parse", "HEAD").trim()

        listOf(origin, shallowClone, configuredClone).forEach { repository ->
            assertEquals(hash.take(8), GitCommitId.of(run(GitCommitId.COMMAND, repository)))
        }
    }

    @Test
    fun `given output that is not a full hash then there is no identifier`() {
        assertNull(GitCommitId.of("b2a0a52\n"))
        assertNull(GitCommitId.of(""))
        assertNull(GitCommitId.of("fatal: not a git repository"))
    }

    @Test
    fun `given a SHA-256 repository hash then the identifier is its first eight characters`() {
        assertEquals("0123abcd", GitCommitId.of("0123abcd".repeat(8) + "\n"))
    }

    /** Runs git with fixed identity and no global or system configuration. */
    private fun git(directory: File, vararg arguments: String): String {
        val identity = listOf("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "-c", "commit.gpgsign=false")
        return run(listOf("git") + identity + arguments, directory)
    }

    /** Runs [command] in [directory] and returns its standard output, failing on a non-zero exit. */
    private fun run(command: List<String>, directory: File): String {
        val process = ProcessBuilder(command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment()["GIT_CONFIG_GLOBAL"] = "/dev/null"
                environment()["GIT_CONFIG_NOSYSTEM"] = "1"
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS)) { "`${command.joinToString(" ")}` did not finish" }
        check(process.exitValue() == 0) { "`${command.joinToString(" ")}` failed: $output" }
        return output
    }
}
