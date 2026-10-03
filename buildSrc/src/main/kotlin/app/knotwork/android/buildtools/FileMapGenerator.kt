package app.knotwork.android.buildtools

/**
 * Rebuilds the Kotlin-source tree of a `FILE_MAP.md` between `AUTO-GEN`
 * markers, so that "the map matches the repository" becomes a pure function of
 * the repository rather than a promise kept by review.
 *
 * **Why generated.** The maps were maintained by a post-write hook, a PR
 * checklist item and a step in the agent workflow — and all three point at one
 * map out of the eight in the tree. Measured before this generator existed:
 * `app/src/main` was accurate to four entries, while the `:catalog` map was
 * missing 128 of its own sources, the unit-test map named 44 files of 375, and
 * the instrumented map 16 of 60.
 * A map that covers an eighth of its directory is not a stale map, it is a
 * misleading one: it reads as complete.
 *
 * **What is generated and what is not.** The structure — which paths exist,
 * their nesting, their order — is derived. The *descriptions* are not: the
 * existing lines carry design rationale no KDoc holds (why the MCP pool is a
 * singleton, why a journal row keeps two package columns). So every
 * description is carried across by path key, and only a path with none is
 * seeded from [KdocSentenceExtractor]. A path with neither gets an explicit
 * marker and is counted; the count is ratcheted so gaps cannot accumulate.
 *
 * **The failure this guards against.** A generator that silently drops what it
 * cannot parse produces a shorter map and a green build — the exact shape of
 * defect this project has hit before. So [render] reconciles counts rather than
 * trusting them: every parsed annotation must either land in the output or be
 * reported as dropped, and a description that vanishes while its path still
 * exists is a [GenerationException], never a quiet deletion.
 *
 * The entry points are [render] (pure transform used by both Gradle tasks) and
 * [drift] (comparison used by the verify task).
 */
object FileMapGenerator {

    /** Thrown when the Markdown cannot be parsed, or the rendered rows do not reconcile. */
    class GenerationException(message: String) : RuntimeException(message)

    /** Identifier of the generated block holding a module's Kotlin sources. */
    const val BLOCK_SOURCES: String = "FILE_MAP"

    /** Identifier of the generated block holding the non-`main` source sets. */
    const val BLOCK_SOURCE_SETS: String = "FILE_MAP_SOURCE_SETS"

    /** Prefix every "no description" marker starts with, so markers are countable. */
    const val NO_DESCRIPTION_MARKER: String = "**No description**"

    /**
     * One Kotlin file offered to the generator.
     *
     * @property path Path relative to the map's root, with `/` separators.
     * @property kdocSentence The sentence [KdocSentenceExtractor] derived from
     *   the file, or `null` when the file offers no unambiguous one.
     */
    data class SourceFile(val path: String, val kdocSentence: String?)

    /**
     * Outcome of a [render] call.
     *
     * @property markdown The rewritten Markdown.
     * @property fileCount Number of Kotlin files in the rendered block.
     * @property undescribed Paths rendered with the "no description" marker,
     *   in rendered order.
     * @property filesWithoutSeed Paths of files whose KDoc offered no sentence
     *   to seed a description from, whether or not a carried-over description
     *   already covers them.
     * @property dropped Descriptions whose path is gone from the repository, as
     *   `path to description`. Never silently discarded: the caller reports them.
     */
    data class RenderResult(
        val markdown: String,
        val fileCount: Int,
        val undescribed: List<String>,
        val filesWithoutSeed: List<String>,
        val dropped: List<Pair<String, String>>,
    )

    /**
     * Rebuilds one block of a file map.
     *
     * Pure and idempotent: `render(render(x)) == render(x)` for the same file
     * list, because the descriptions the second pass reads are the ones the
     * first pass wrote.
     *
     * @param markdown Current contents of the `FILE_MAP.md`.
     * @param block Identifier of the block to rewrite ([BLOCK_SOURCES] or
     *   [BLOCK_SOURCE_SETS]).
     * @param files Every Kotlin file under the map's root.
     * @return The rewritten Markdown together with the counts the caller reports.
     * @throws GenerationException when the block markers are missing, when the
     *   existing block cannot be parsed, or when a carried-over description
     *   would vanish although its path still exists.
     */
    fun render(markdown: String, block: String, files: List<SourceFile>): RenderResult {
        val carried = parseDescriptions(blockBody(markdown, block))
        val tree = buildTree(files.map { it.path })
        val undescribed = mutableListOf<String>()
        val used = mutableSetOf<String>()
        val seeds = files.associate { it.path to it.kdocSentence }

        val body = StringBuilder("\n")
        renderNodes(tree.children, "", 0, carried, seeds, used, undescribed, body)

        val rendered = replaceBlock(markdown, block, body.toString())
        val livePaths = tree.allPaths()
        val dropped = carried
            .filterKeys { it !in livePaths }
            .map { (path, description) -> path to description }
            .sortedBy { it.first }

        // Reconciliation: an annotation whose path survived must have been used.
        val lostButLive = carried.keys.filter { it in livePaths && it !in used }
        if (lostButLive.isNotEmpty()) {
            throw GenerationException(
                "Descriptions were dropped for paths that still exist: " +
                    lostButLive.sorted().joinToString(", ") + ". This is a generator bug, not a repository state.",
            )
        }

        return RenderResult(
            markdown = rendered,
            fileCount = files.size,
            undescribed = undescribed,
            filesWithoutSeed = files.filter { it.kdocSentence == null }.map { it.path },
            dropped = dropped,
        )
    }

    /**
     * Whether the committed block differs from what [render] would produce.
     *
     * @param markdown Current contents of the `FILE_MAP.md`.
     * @param block Identifier of the block to check.
     * @param files Every Kotlin file under the map's root.
     * @return `true` when the committed Markdown has drifted.
     * @throws GenerationException on the same conditions as [render].
     */
    fun drift(markdown: String, block: String, files: List<SourceFile>): Boolean =
        render(markdown, block, files).markdown != markdown

    /**
     * Renders one level of the tree and recurses into directories.
     *
     * Siblings are ordered by lower-cased name, directories and files
     * interleaved, so that the order is a function of the names alone — the
     * previous hand-chosen order could not be reproduced from the repository
     * and so could not be verified.
     */
    private fun renderNodes(
        nodes: Map<String, Node>,
        prefix: String,
        depth: Int,
        carried: Map<String, String>,
        seeds: Map<String, String?>,
        used: MutableSet<String>,
        undescribed: MutableList<String>,
        out: StringBuilder,
    ) {
        val ordered = nodes.values.sortedWith(compareBy({ it.name.lowercase() }, { it.name }))
        for (node in ordered) {
            val path = if (prefix.isEmpty()) node.name else "$prefix/${node.name}"
            val label = if (node.isDirectory) "${node.name}/" else node.name
            val indent = "  ".repeat(depth)
            val existing = carried[path]
            if (existing != null) used += path
            val description = existing
                ?: seeds[path]
                ?: if (node.isDirectory) DIRECTORY_MARKER else FILE_MARKER
            if (description.startsWith(NO_DESCRIPTION_MARKER)) undescribed += path
            out.append(indent).append("- `").append(label).append("` - ").append(description).append('\n')
            if (node.isDirectory) {
                renderNodes(node.children, path, depth + 1, carried, seeds, used, undescribed, out)
            }
        }
    }

    /**
     * Reads `path -> description` out of a rendered block.
     *
     * Paths are reconstructed from indentation, so the parser mirrors the
     * renderer. Both the hyphen and the em-dash separator are accepted, and a
     * description wrapped over several lines is joined — the maps predate this
     * generator and used both conventions.
     *
     * @param body The block's current body.
     * @return Description by path, excluding entries that carry none.
     * @throws GenerationException when an entry's indentation has no parent.
     */
    fun parseDescriptions(body: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val stack = mutableListOf<String>()
        var currentPath: String? = null
        val pending = StringBuilder()

        fun flush() {
            val path = currentPath ?: return
            val text = pending.toString().trim()
            if (text.isNotEmpty() && !text.startsWith(NO_DESCRIPTION_MARKER)) result[path] = text
            pending.setLength(0)
            currentPath = null
        }

        for (rawLine in body.lines()) {
            val match = ENTRY.matchEntire(rawLine)
            if (match == null) {
                // A continuation line of the previous entry's description.
                if (currentPath != null && rawLine.isNotBlank()) {
                    pending.append(' ').append(rawLine.trim())
                }
                continue
            }
            flush()
            val depth = match.groupValues[1].length / 2
            val label = match.groupValues[2]
            val description = match.groupValues[3]
            if (depth > stack.size) {
                throw GenerationException("Entry `$label` is indented past its parent in the file map.")
            }
            while (stack.size > depth) stack.removeAt(stack.lastIndex)
            val name = label.removeSuffix("/")
            val path = (stack + name).joinToString("/")
            if (label.endsWith("/")) stack += name
            currentPath = path
            pending.append(description)
        }
        flush()
        return result
    }

    /** Builds a directory tree out of relative paths. */
    private fun buildTree(paths: List<String>): Node {
        val root = Node(name = "", isDirectory = true)
        for (path in paths) {
            val segments = path.split('/')
            var node = root
            for ((index, segment) in segments.withIndex()) {
                val isDirectory = index < segments.lastIndex
                node = node.children.getOrPut(segment) { Node(segment, isDirectory) }
                if (node.isDirectory != isDirectory) {
                    throw GenerationException("`$path` is both a file and a directory in the source tree.")
                }
            }
        }
        return root
    }

    /**
     * One node of the rendered tree.
     *
     * @property name The segment name, without a trailing slash.
     * @property isDirectory Whether the node has children.
     * @property children Child nodes by name.
     */
    private class Node(
        val name: String,
        val isDirectory: Boolean,
        val children: MutableMap<String, Node> = linkedMapOf(),
    ) {
        /** Every path in this subtree, directories included. */
        fun allPaths(prefix: String = ""): Set<String> {
            val result = mutableSetOf<String>()
            for (child in children.values) {
                val path = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                result += path
                if (child.isDirectory) result += child.allPaths(path)
            }
            return result
        }
    }

    /**
     * Replaces one block's body, leaving its markers and the rest of the file
     * intact.
     *
     * @throws GenerationException when the markers are missing or inverted.
     */
    private fun replaceBlock(markdown: String, block: String, body: String): String {
        val bounds = markerBounds(markdown, block)
        return markdown.substring(0, bounds.first + openMarker(block).length) + body + markdown.substring(bounds.second)
    }

    /**
     * Returns one block's current body.
     *
     * @throws GenerationException when the markers are missing or inverted.
     */
    private fun blockBody(markdown: String, block: String): String {
        val bounds = markerBounds(markdown, block)
        return markdown.substring(bounds.first + openMarker(block).length, bounds.second)
    }

    /**
     * Locates one block's markers.
     *
     * @return The index of the opening marker and the index of the closing one.
     * @throws GenerationException when either marker is missing or they are inverted.
     */
    private fun markerBounds(markdown: String, block: String): Pair<Int, Int> {
        val start = markdown.indexOf(openMarker(block))
        val end = markdown.indexOf(closeMarker(block))
        if (start < 0 || end < 0 || end < start) {
            throw GenerationException("Markers for block `$block` are missing or inverted in the file map.")
        }
        return start to end
    }

    /** The opening marker of [block]. */
    fun openMarker(block: String): String = "<!-- AUTO-GEN:$block -->"

    /** The closing marker of [block]. */
    fun closeMarker(block: String): String = "<!-- /AUTO-GEN:$block -->"

    /** Rendered for a file whose KDoc offers no unambiguous description. */
    private const val FILE_MARKER =
        "$NO_DESCRIPTION_MARKER — give the declaration this file is named for a KDoc, " +
            "or replace this line by hand."

    /** Rendered for a directory, which has no KDoc to fall back on. */
    private const val DIRECTORY_MARKER = "$NO_DESCRIPTION_MARKER — replace this line by hand."

    /** One rendered entry: indentation, the back-quoted label, and the description. */
    private val ENTRY = Regex("""^( *)- `([^`]+)`(?: +[-—] +(.*))?$""")
}
