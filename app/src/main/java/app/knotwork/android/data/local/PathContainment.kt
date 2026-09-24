package app.knotwork.android.data.local

import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The one containment test for "does this path stay inside that directory".
 *
 * Both sides are **canonicalised first** — `..` collapsed, symlinks followed —
 * and only then compared, with a trailing separator so a sibling such as
 * `audio_evil/` cannot pass for `audio/`. The shape it replaces,
 * `file.absolutePath.startsWith(root)`, compares the string as written:
 * `…/audio/../files/attachments/x.jpg` starts with `…/audio/` and resolves
 * outside it. `PathContainmentGuardTest` keeps a path `startsWith` out of every
 * other production file, so a new store reuses this instead of re-deriving it.
 */
internal object PathContainment {

    /**
     * Returns the canonical form of [candidate] when it lies strictly inside
     * [root], or `null` when it escapes, is [root] itself, or cannot be
     * canonicalised (a NUL byte, a name the filesystem rejects).
     *
     * @param candidate The path to test, possibly caller-supplied.
     * @param root The directory it must stay inside.
     * @return The canonical [File] to operate on, or `null` to refuse.
     */
    fun childOrNull(candidate: File, root: File): File? {
        val canonicalRoot = canonicalOrNull(root) ?: return null
        val canonical = canonicalOrNull(candidate) ?: return null
        return canonical.takeIf { isInside(it, canonicalRoot) }
    }

    /**
     * Whether an **already canonical** [canonical] path is [canonicalRoot] or lies
     * under it. For a caller that canonicalised once and needs the result anyway
     * (the workspace's resolver); everyone else uses [childOrNull].
     *
     * @param canonical A canonicalised candidate.
     * @param canonicalRoot The canonicalised directory.
     * @return `true` when the candidate is the root or a descendant of it.
     */
    fun isSelfOrInside(canonical: File, canonicalRoot: File): Boolean =
        canonical.path == canonicalRoot.path || isInside(canonical, canonicalRoot)

    private fun isInside(canonical: File, canonicalRoot: File): Boolean =
        canonical.path.startsWith(canonicalRoot.path + File.separator)

    /**
     * Canonicalises [file], or returns `null` when the filesystem rejects it. Only
     * the exception's type is logged: the path may be caller-supplied, and a
     * warning reaches crash reports once the user opts in.
     */
    private fun canonicalOrNull(file: File): File? = try {
        file.canonicalFile
    } catch (e: IOException) {
        Timber.w("Path could not be canonicalised (%s)", e.javaClass.simpleName)
        null
    }
}
