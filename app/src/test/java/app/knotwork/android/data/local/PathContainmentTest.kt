package app.knotwork.android.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Verifies [PathContainment] on a real filesystem: `..`, a sibling sharing the
 * root's name as a prefix, and a symlink out are all refused — the three ways a
 * prefix test on the string as written lets a path escape.
 */
class PathContainmentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun root(): File = tempFolder.newFolder("audio")

    @Test
    fun `given a file inside the root then its canonical form is returned`() {
        val root = root()
        val inside = File(root, "clip.wav")

        assertEquals(inside.canonicalFile, PathContainment.childOrNull(inside, root))
    }

    @Test
    fun `given a dot-dot path that starts with the root then it is refused`() {
        val root = root()
        tempFolder.newFolder("files")

        assertNull(PathContainment.childOrNull(File("${root.absolutePath}/../files/x.jpg"), root))
    }

    @Test
    fun `given a sibling whose name extends the root's then it is refused`() {
        val root = root()
        val sibling = tempFolder.newFolder("audio_evil")

        assertNull(PathContainment.childOrNull(File(sibling, "x.wav"), root))
    }

    @Test
    fun `given a symlink inside the root pointing out then it is refused`() {
        val root = root()
        val outside = tempFolder.newFolder("secret")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())

        assertNull(PathContainment.childOrNull(File(root, "link/keys.xml"), root))
    }

    @Test
    fun `given the root itself then childOrNull refuses it and isSelfOrInside accepts it`() {
        val root = root().canonicalFile

        assertNull(PathContainment.childOrNull(root, root))
        assertTrue(PathContainment.isSelfOrInside(root, root))
        assertFalse(PathContainment.isSelfOrInside(File(root.parentFile, "audio_evil").canonicalFile, root))
    }

    @Test
    fun `given a path the filesystem rejects then it is refused rather than thrown`() {
        val root = root()

        assertNull(PathContainment.childOrNull(File(root, "bad${Char(0)}name"), root))
    }
}
