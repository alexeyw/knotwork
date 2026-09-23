package app.knotwork.android.data.local

import app.knotwork.android.domain.constants.TransientCacheDirectory
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * The copies of workspace files staged for the system share sheet, under
 * `cacheDir/shared/` ([TransientCacheDirectory.WORKSPACE_SHARE]) — the only directory of
 * the three the `FileProvider` serves that holds workspace content.
 *
 * A share hands the receiving app a URI to a **copy**, never to the workspace, so
 * the copy has to live somewhere and has to go away. Two rules, each closing one
 * way the old staging failed:
 *
 * - **A copy never outlives its file.** Each copy sits in its own slot named
 *   `<key>-<uuid>/<file name>`, where the key is derived from the file's
 *   canonical workspace path, so [discard] finds every copy of a file the moment
 *   the file is deleted. Before, "delete" on the Files screen left a
 *   byte-identical copy behind.
 * - **A share does not pull another share's copy away.** Staging removes only
 *   copies older than [TransientCacheDirectory.RETENTION_MILLIS]; before, every
 *   share wiped the whole directory, taking the previous share's file from an
 *   app that had not read it yet.
 *
 * Whatever neither rule removes, the daily transient-cache sweep does.
 * Not thread-safe on its own: [AgentWorkspaceImpl] calls it under its mutex.
 *
 * @property root Supplies the staging directory (created on demand).
 * @property clock Supplies "now" for the retention cut-off; replaced in tests.
 */
internal class WorkspaceShareCopies(
    private val root: () -> File,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Copies [source] into a fresh slot and returns the copy. Expired copies of
     * any file are removed first.
     *
     * @param source The canonical workspace file to copy.
     * @param relativePath Its canonical path relative to the workspace root —
     *   the identity [discard] matches on.
     * @return The staged copy, named like [source].
     */
    fun stage(source: File, relativePath: String): File {
        val dir = root().apply { mkdirs() }
        TransientCacheFiles.pruneOlderThan(dir, clock() - TransientCacheDirectory.RETENTION_MILLIS)
        val slot = File(dir, "${keyOf(relativePath)}$KEY_SEPARATOR${UUID.randomUUID()}").apply { mkdirs() }
        return source.copyTo(File(slot, source.name))
    }

    /**
     * Removes every staged copy of the file at [relativePath].
     *
     * @param relativePath The canonical workspace-relative path of the file.
     * @return How many copies were removed.
     */
    fun discard(relativePath: String): Int {
        val prefix = "${keyOf(relativePath)}$KEY_SEPARATOR"
        return root().listFiles().orEmpty().count { it.name.startsWith(prefix) && it.deleteRecursively() }
    }

    /**
     * The slot key of a workspace path: the first [KEY_HEX_CHARS] hex characters
     * of its SHA-256. A hash rather than the path itself, because a path carries
     * separators and can outgrow a directory name; 64 bits are plenty to keep two
     * files of one workspace apart.
     */
    private fun keyOf(relativePath: String): String = MessageDigest.getInstance(DIGEST)
        .digest(relativePath.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(HEX_DIGITS_PER_BYTE, '0')
        }
        .take(KEY_HEX_CHARS)

    private companion object {
        const val DIGEST = "SHA-256"
        const val KEY_HEX_CHARS = 16
        const val KEY_SEPARATOR = "-"
        const val BYTE_MASK = 0xFF
        const val HEX_RADIX = 16
        const val HEX_DIGITS_PER_BYTE = 2
    }
}
