package app.knotwork.android.data.local

import java.io.File
import java.nio.file.Files

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
        .onEnter { it == root || !Files.isSymbolicLink(it.toPath()) }
        .filter { it != root && !Files.isSymbolicLink(it.toPath()) }

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
