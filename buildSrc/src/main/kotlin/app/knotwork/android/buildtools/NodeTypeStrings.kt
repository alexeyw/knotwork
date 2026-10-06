package app.knotwork.android.buildtools

/**
 * Reads the name and the one-line description of every pipeline node type
 * from `catalog/src/main/res/values/strings_node_types.xml`.
 *
 * **Why a string resource is the source.** The app shows both texts where a
 * node type is chosen, so they have to be resources the app can resolve. Two
 * documents repeat them — `docs/cookbook.md` opens each node's entry with the
 * description, and the browser editor's palette shows the name and the
 * description — and both used to carry a hand-written copy. The browser
 * editor's copy drifted: it described twelve of fourteen types, and its cloud
 * provider list predated the providers the app had shipped. So the documents
 * now quote the resource file, and their generators read it through this one
 * parser.
 *
 * **Strict on purpose.** The text reaches Markdown and a JavaScript string as
 * well as the app, so anything whose meaning differs between Android's string
 * rendering and a verbatim copy is rejected rather than approximated: markup,
 * format arguments, escapes other than `\'` and `\"`, an unescaped double
 * quote (Android treats it as quoting and drops it) and an unescaped
 * apostrophe (the resource compiler rejects it). Whitespace is collapsed
 * the way Android collapses it in an unquoted string, so the copy reads exactly
 * as the app renders it.
 *
 * **A description is one short sentence.** The picker shows it under the name
 * with nothing cut, so its length is a layout budget, not a style preference:
 * at [DESCRIPTION_BUDGET] characters every description takes two lines or fewer
 * on a 360 dp phone (measured by the designer for the picker row). It must also
 * end a sentence, because the cookbook continues it with further sentences.
 */
object NodeTypeStrings {

    /** Thrown when the file holds something this reader refuses to copy. */
    class ParseException(message: String) : RuntimeException(message)

    /**
     * The two texts of one node type, as the app renders them.
     *
     * @property name The node type's name, e.g. `Intent Router`.
     * @property description One sentence saying what the node is for.
     */
    data class Text(val name: String, val description: String)

    /**
     * The most characters a description may have: two lines or fewer in the
     * node-type picker's row on a 360 dp phone at 100 % font scale.
     */
    const val DESCRIPTION_BUDGET: Int = 85

    private const val PREFIX = "knotwork_node_type_"
    private const val NAME_SUFFIX = "_name"
    private const val DESCRIPTION_SUFFIX = "_description"

    /**
     * The resource name a node type's [part] is stored under.
     *
     * @param id A `NodeType` enum constant name, e.g. `LITE_RT`.
     * @param part `name` or `description`.
     * @return e.g. `knotwork_node_type_lite_rt_name`.
     */
    fun resourceName(id: String, part: String): String = PREFIX + id.lowercase() + "_" + part

    /**
     * Parses the file into one [Text] per node type.
     *
     * @param xml The content of `strings_node_types.xml`.
     * @return The texts keyed by `NodeType` enum constant name, in file order.
     * @throws ParseException when an entry is not a node-type text, a type is
     *   missing one of its two texts or has one twice, or a text holds anything
     *   a verbatim copy would render differently.
     */
    fun parse(xml: String): Map<String, Text> {
        val withoutComments = COMMENT.replace(xml, "")
        val entries = ELEMENT.findAll(withoutComments).toList()
        if (entries.isEmpty()) throw ParseException("strings_node_types.xml holds no <string> entries.")
        rejectStrayElements(withoutComments, entries.size)

        val names = linkedMapOf<String, String>()
        val descriptions = linkedMapOf<String, String>()
        entries.forEach { match ->
            val resource = match.groupValues[1]
            val text = decode(resource, match.groupValues[2])
            val (id, target) = when {
                !resource.startsWith(PREFIX) -> throw ParseException(
                    "`$resource` is not a node-type text; strings_node_types.xml holds only " +
                        "`$PREFIX<id>_name` and `$PREFIX<id>_description`.",
                )
                resource.endsWith(DESCRIPTION_SUFFIX) ->
                    idOf(resource, DESCRIPTION_SUFFIX) to descriptions
                resource.endsWith(NAME_SUFFIX) -> idOf(resource, NAME_SUFFIX) to names
                else -> throw ParseException("`$resource` ends in neither `_name` nor `_description`.")
            }
            if (target.put(id, text) != null) throw ParseException("`$resource` is declared twice.")
        }

        (names.keys - descriptions.keys).forEach { throw ParseException("$it has a name but no description.") }
        (descriptions.keys - names.keys).forEach { throw ParseException("$it has a description but no name.") }
        descriptions.forEach { (id, description) -> requireOneShortSentence(id, description) }
        return names.mapValues { (id, name) -> Text(name = name, description = descriptions.getValue(id)) }
    }

    /**
     * Holds a description to the picker's budget and to being one sentence the
     * cookbook can continue.
     */
    private fun requireOneShortSentence(id: String, description: String) {
        val resource = resourceName(id, "description")
        val length = description.codePointCount(0, description.length)
        if (length > DESCRIPTION_BUDGET) {
            throw ParseException(
                "`$resource` is $length characters; a description has at most $DESCRIPTION_BUDGET, the two " +
                    "lines the node-type picker gives it on a 360 dp phone. Move the rest into the cookbook " +
                    "entry's continuation (CookbookDocsGenerator.NODE_DOC_META).",
            )
        }
        if (!description.endsWith('.') || SENTENCE_BREAK.containsMatchIn(description)) {
            throw ParseException(
                "`$resource` must be exactly one sentence ending in a full stop; the cookbook entry continues it.",
            )
        }
    }

    /** Maps `knotwork_node_type_lite_rt_name` to `LITE_RT`. */
    private fun idOf(resource: String, suffix: String): String {
        val middle = resource.removePrefix(PREFIX).removeSuffix(suffix)
        if (!ID_BODY.matches(middle)) throw ParseException("`$resource` does not name a node type.")
        return middle.uppercase()
    }

    /**
     * Any `<string`, `<plurals` or `<string-array` the entry pattern did not
     * consume — a malformed or unexpected element would otherwise be skipped
     * silently and its type reported as merely missing.
     */
    private fun rejectStrayElements(xml: String, parsed: Int) {
        val opened = OPENING.findAll(xml).count()
        if (opened != parsed) {
            throw ParseException(
                "strings_node_types.xml declares $opened resource element(s) but only $parsed are plain " +
                    "`<string name=\"…\">text</string>` entries.",
            )
        }
    }

    /** Turns the raw element body into the text Android renders. */
    private fun decode(resource: String, raw: String): String {
        if ('<' in raw) throw ParseException("`$resource` contains markup; node-type texts are plain text.")
        if ('%' in raw) throw ParseException("`$resource` contains `%`; node-type texts take no arguments.")
        val out = StringBuilder()
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            when {
                char == '\\' -> {
                    val next = raw.getOrNull(index + 1)
                    if (next != '\'' && next != '"') {
                        throw ParseException(
                            "`$resource` uses the escape `\\${next ?: ""}`; only \\' and \\\" are allowed.",
                        )
                    }
                    out.append(next)
                    index += 2
                    continue
                }
                char == '"' -> throw ParseException(
                    "`$resource` has an unescaped double quote, which Android drops; write \\\" instead.",
                )
                char == '\'' -> throw ParseException(
                    "`$resource` has an unescaped apostrophe, which the resource compiler rejects; write \\' instead.",
                )
                else -> out.append(char)
            }
            index++
        }
        val text = WHITESPACE.replace(decodeEntities(out.toString()), " ").trim()
        if (text.isEmpty()) throw ParseException("`$resource` is empty.")
        // An entity can smuggle an angle bracket past the markup check; the browser
        // editor writes the name into `innerHTML`, where it would become markup.
        if ('<' in text || '>' in text) {
            throw ParseException("`$resource` contains `<` or `>`; node-type texts are plain text.")
        }
        return text
    }

    /** Resolves the XML entities a plain-text string may carry. */
    private fun decodeEntities(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private val COMMENT = Regex("""<!--[\s\S]*?-->""")
    private val ELEMENT = Regex("""<string\s+name="([a-z0-9_]+)"\s*>([\s\S]*?)</string>""")
    private val OPENING = Regex("""<(?:string|plurals|string-array)\b""")
    private val ID_BODY = Regex("""[a-z][a-z0-9]*(?:_[a-z0-9]+)*""")
    private val WHITESPACE = Regex("""\s+""")
    private val SENTENCE_BREAK = Regex("""[.!?]\s""")
}
