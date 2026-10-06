package app.knotwork.design.components.pipelineeditor

/**
 * The node-type picker's search: which types a query keeps, grouped as the
 * picker lists them.
 *
 * A type matches when **every word** of the query occurs, ignoring case, in its
 * name, its one-line description or its enum id (`QUEUE_PROCESSOR`) — the id
 * is searchable but never shown, for someone arriving from an exported
 * pipeline or the cookbook. Words match as substrings, so *rout* finds
 * *Intent Router*. There are no aliases and no ranking: the order never changes
 * while typing, a group whose types all drop out is dropped with them, and the
 * groups keep [NodeTypeGroup] order.
 *
 * Pure — the caller resolves the texts — so the rules are unit-tested without
 * Compose.
 */
object NodeTypeSearch {

    /**
     * The texts one node type is searched by and shown with.
     *
     * @property type The node type.
     * @property name Its resolved name.
     * @property description Its resolved one-line description.
     */
    data class Entry(val type: NodeType, val name: String, val description: String)

    /**
     * One group of the picker with the entries the query kept.
     *
     * @property group The group.
     * @property entries Its matching entries, in [NodeTypeGroup.types] order; never empty.
     */
    data class Section(val group: NodeTypeGroup, val entries: List<Entry>)

    /**
     * Filters every node type by [query].
     *
     * @param query The search field's text; blank keeps every type.
     * @param entryOf Resolves a type's texts.
     * @return The groups with at least one match, in [NodeTypeGroup] order.
     */
    fun filter(query: String, entryOf: (NodeType) -> Entry): List<Section> {
        val words = wordsOf(query)
        return NodeTypeGroup.entries.mapNotNull { group ->
            val kept = group.types.map(entryOf).filter { matches(it, words) }
            kept.takeIf { it.isNotEmpty() }?.let { Section(group, it) }
        }
    }

    /**
     * Whether [entry] matches [query] by the rule in this object's KDoc.
     *
     * @param entry The type and its texts.
     * @param query The search field's text; blank matches everything.
     * @return `true` when every word of [query] occurs in the entry's texts or id.
     */
    fun matches(entry: Entry, query: String): Boolean = matches(entry, wordsOf(query))

    private fun matches(entry: Entry, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val haystack = "${entry.name} ${entry.description} ${entry.type.name}".lowercase()
        return words.all { it in haystack }
    }

    private fun wordsOf(query: String): List<String> =
        query.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")
}
