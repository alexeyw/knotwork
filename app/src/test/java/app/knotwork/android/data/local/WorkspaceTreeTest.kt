package app.knotwork.android.data.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Unit tests for [WorkspaceTree.isRealEntry] — the link check behind every walk of
 * the workspace (listing, quota, pruning).
 *
 * The check used to ask `java.nio` alone, and `File.toPath()` throws
 * `InvalidPathException` for a name the platform's path encoder cannot represent —
 * a lone UTF-16 surrogate, which earlier versions accepted. One such name made every
 * walk throw: listing, the quota and, after a restart, every write and delete,
 * including the delete that would remove it. A JVM cannot create such a file (it
 * writes `?`), so the name's failure is simulated with a [File] whose `toPath` throws.
 */
class WorkspaceTreeTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** A real file whose name `java.nio` refuses, as a lone surrogate is refused. */
    private class UnmappableNameFile(parent: File, name: String) : File(parent, name) {
        override fun toPath(): Path =
            throw InvalidPathException(path, "Malformed input or input contains unmappable characters")
    }

    @Test
    fun `given a real file whose name java nio refuses when checked then it is a real entry and nothing throws`() {
        val root = temporaryFolder.root.canonicalFile
        File(root, "odd.txt").writeText("x")

        assertTrue(WorkspaceTree.isRealEntry(UnmappableNameFile(root, "odd.txt")))
    }

    @Test
    fun `given a link whose name java nio refuses when checked then it is not a real entry`() {
        val root = temporaryFolder.newFolder("ws").canonicalFile
        val outside = temporaryFolder.newFile("outside.txt")
        java.nio.file.Files.createSymbolicLink(File(root, "link.txt").toPath(), outside.toPath())

        assertFalse(WorkspaceTree.isRealEntry(UnmappableNameFile(root, "link.txt")))
    }

    @Test
    fun `given an ordinary file and an ordinary link when checked then only the file is real`() {
        val root = temporaryFolder.newFolder("plain").canonicalFile
        val file = File(root, "a.txt").apply { writeText("a") }
        val link = File(root, "b.txt")
        java.nio.file.Files.createSymbolicLink(link.toPath(), file.toPath())

        assertTrue(WorkspaceTree.isRealEntry(file))
        assertFalse(WorkspaceTree.isRealEntry(link))
    }
}
