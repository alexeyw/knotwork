package app.knotwork.android.data.local

import java.io.File

/**
 * Age-based cleanup of one transient cache directory — the rule shared by the
 * daily sweep ([TransientCacheSweeperImpl]) and the share staging's own prune
 * ([WorkspaceShareCopies]), so the two cannot disagree about what "expired"
 * means.
 */
internal object TransientCacheFiles {

    /**
     * Deletes every top-level entry of [dir] whose newest modification time is at
     * or before [cutoffMillis]. A directory entry (a share slot) counts as fresh
     * while anything inside it is: the age is its subtree's newest timestamp, not
     * the directory's own, which a slow copy into it would not refresh.
     *
     * @param dir The transient directory; a missing one prunes nothing.
     * @param cutoffMillis Entries last touched at or before this instant are removed.
     * @return How many top-level entries were removed.
     */
    fun pruneOlderThan(dir: File, cutoffMillis: Long): Int = dir.listFiles().orEmpty().count { entry ->
        newestModification(entry) <= cutoffMillis && entry.deleteRecursively()
    }

    /** Newest modification time within [entry]'s subtree (the entry itself for a file). */
    private fun newestModification(entry: File): Long =
        entry.walkTopDown().maxOfOrNull { it.lastModified() } ?: entry.lastModified()
}
