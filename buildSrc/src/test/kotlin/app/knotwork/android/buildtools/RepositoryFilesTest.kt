package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [repositoryFilesOf], the file index the inline-code path pass
 * of `verifyDocLinks` resolves against.
 *
 * The property under test is that a local run and a CI run see the same tree:
 * build output and the directories that exist only in a developer's checkout are
 * left out, so a documentation path cannot resolve locally against a file CI
 * will never have.
 */
class RepositoryFilesTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** Creates an empty file at a repository-relative path. */
    private fun touch(path: String) {
        File(temporaryFolder.root, path).apply { parentFile.mkdirs() }.writeText("")
    }

    @Test
    fun `given sources, build output and checkout-only directories when indexed then only the sources remain`() {
        listOf(
            "README.md",
            "docs/a.md",
            "app/src/main/java/X.kt",
            "app/build/generated/Y.kt",
            "catalog/build/Z.kt",
            "buildSrc/.gradle/cache.bin",
            ".git/config",
            ".gradle/state.bin",
            ".idea/misc.xml",
            ".kotlin/errors.log",
            ".claude/rules/code-style.md",
            "project_docs/TODO.md",
            "local.properties",
        ).forEach(::touch)

        assertEquals(
            setOf("README.md", "docs/a.md", "app/src/main/java/X.kt"),
            repositoryFilesOf(temporaryFolder.root),
        )
    }

    @Test
    fun `given a directory only named like an excluded one when indexed then it is kept`() {
        touch("app/src/main/java/buildtools/Tool.kt")
        touch("docs/project_docs.md")

        assertEquals(
            setOf("app/src/main/java/buildtools/Tool.kt", "docs/project_docs.md"),
            repositoryFilesOf(temporaryFolder.root),
        )
    }
}
