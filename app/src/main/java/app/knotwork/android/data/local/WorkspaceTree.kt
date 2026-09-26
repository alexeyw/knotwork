package app.knotwork.android.data.local

import java.io.File
import java.nio.file.Files
import java.nio.file.InvalidPathException

/**
 * The agent workspace's directory as a tree of real entries: the one walk behind
 * both the listing and the quota, how the quota counts what that walk finds, and
 * the housekeeping that keeps empty directories from piling up.
 *
 * Stateless; every function takes the canonical workspace root. The functions that
 * delete a directory must run under [AgentWorkspaceImpl]'s lock, which is what keeps
 * a writer from losing a directory it has just created.
 */
internal object WorkspaceTree {

    /**
     * What the workspace holds, as the quota counts it.
     *
     * @property bytes Total size of the regular files, scratch files excluded.
     * @property entries Number of files and directories, the root excluded.
     */
    data class Tally(val bytes: Long, val entries: Int)

    /**
     * Every real file and directory under [root], [root] itself excluded, contents
     * before the directory holding them. A symbolic link is neither entered nor
     * returned, so the walk cannot leave the root or go round a cycle.
     *
     * @param root The canonical workspace root.
     * @return The entries, bottom-up.
     */
    fun realEntries(root: File): Sequence<File> = root.walkBottomUp()
        .onEnter { it == root || isRealEntry(it) }
        .filter { it != root && isRealEntry(it) }

    /**
     * Reports whether [entry] is itself a real file or directory rather than a
     * symbolic link.
     *
     * Asks `java.nio` first. A name `java.nio` cannot turn into a `Path` — a lone
     * UTF-16 surrogate, which the platform's path encoder refuses — makes
     * `toPath` throw, and one such entry used to fail every walk. For those the
     * answer comes from `java.io` instead: an entry is real when resolving it
     * changes nothing, that is when its canonical path is its own absolute path
     * under the (canonical) directory holding it. A link resolves elsewhere.
     *
     * @param entry An entry found by the walk.
     * @return `true` for a real file or directory, `false` for a link.
     */
    fun isRealEntry(entry: File): Boolean = try {
        !Files.isSymbolicLink(entry.toPath())
    } catch (e: InvalidPathException) {
        val parent = entry.parentFile?.canonicalFile ?: return false
        entry.canonicalPath == File(parent, entry.name).absolutePath
    }

    /**
     * Counts what [root] holds and removes every empty directory on the way —
     * contents come before their directory, so a chain of them goes in one pass.
     * That clears what earlier versions, or a write that failed after creating its
     * directories, left behind.
     *
     * @param root The canonical workspace root.
     * @param isScratch Whether a file is a transient write stage, excluded from
     *   both counts.
     * @return The bytes and entries remaining after the empty directories are gone.
     */
    fun measureAndPrune(root: File, isScratch: (File) -> Boolean): Tally {
        var bytes = 0L
        var entries = 0
        realEntries(root).forEach { entry ->
            when {
                entry.isFile -> if (!isScratch(entry)) {
                    bytes += entry.length()
                    entries++
                }
                entry.isDirectory -> if (entry.list()?.isEmpty() != true || !entry.delete()) entries++
            }
        }
        return Tally(bytes, entries)
    }

    /**
     * Reports whether a directory level between [target] and [root] exists as a
     * regular file, so no entry can be created at [target].
     *
     * @param target The canonical path about to be written.
     * @param root The canonical workspace root.
     * @return `true` when some ancestor of [target] below [root] is a file.
     */
    fun runsThroughFile(target: File, root: File): Boolean {
        var dir = target.parentFile
        while (dir != null && dir != root) {
            if (dir.exists() && !dir.isDirectory) return true
            dir = dir.parentFile
        }
        return false
    }

    /**
     * Counts the directories between [target] and [root] that do not exist yet —
     * the ones writing [target] will create, each an entry of its own.
     *
     * @param target The canonical file about to be written.
     * @param root The canonical workspace root.
     * @return How many directories the write will create.
     */
    fun missingDirectoryCount(target: File, root: File): Int {
        var missing = 0
        var dir = target.parentFile
        while (dir != null && dir != root && !dir.exists()) {
            missing++
            dir = dir.parentFile
        }
        return missing
    }

    /**
     * Removes the directories above [deleted], nearest first, while each is empty,
     * stopping below [root]. [deleted] is canonical, so its ancestors are real
     * directories inside the root — never a link out of it.
     *
     * @param deleted The canonical file that was just deleted.
     * @param root The canonical workspace root.
     * @return How many directories were removed.
     */
    fun removeEmptiedAncestors(deleted: File, root: File): Int {
        var removed = 0
        var dir = deleted.parentFile
        while (dir != null && dir != root && dir.list()?.isEmpty() == true && dir.delete()) {
            removed++
            dir = dir.parentFile
        }
        return removed
    }

    /**
     * The `/`-separated path of [file] relative to [root].
     *
     * @param file A file under [root].
     * @param root The canonical workspace root.
     * @return The workspace-relative path.
     */
    fun relativePath(file: File, root: File): String = file.toRelativeString(root).replace(File.separatorChar, '/')
}
