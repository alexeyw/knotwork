package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.nio.ByteBuffer
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every character the interface shows is in the bundled fonts — or named, with
 * its reason, on `config/fonts/uncovered-characters.txt`.
 *
 * **Why.** The app draws its text in Inter and JetBrains Mono, cut down to the
 * characters of `config/fonts/characters.txt` by `config/fonts/subset_fonts.py`.
 * A character outside the cut is drawn by the system font in the middle of a
 * line in the brand fonts, with another width, weight and x-height. Before
 * the fonts were cut with Cyrillic that was every Cyrillic letter — the Russian
 * interface read in the system font with brand-font digits between its words —
 * and `→` in every language (decisions §124).
 *
 * **What it reads.** The text the interface shows: the string resources of
 * `app` and `catalog` in every language, the bundled presets and their
 * translations, the bundled help documents, and the string literals of the
 * production code — all of `:app`, since the engine in `domain` writes the
 * console's lines (`▶`, `↻`), and `:catalog` without its showcase
 * ([InterfaceTextScope.catalogSources]). A character that is not drawn — a
 * control or format character, a line or paragraph separator — is not counted.
 * Text that arrives at run time — a model's answer, a message, a file name — is
 * not the interface's own; it falls back where the fonts end, which is why
 * `characters.txt` takes whole alphabets, not just the letters the strings use.
 *
 * **What else it holds.**
 * - A line of the list applies: the character is shown and the families it
 *   names lack it. A line that no longer does fails, so the list stays true.
 * - The four weights of a family cover the same characters: text set bold must
 *   not lose a glyph its regular weight has.
 * - Every font stays inside `characters.txt`, so the list keeps saying what the
 *   files hold.
 *
 * Reads `app/src/main`, `catalog/src/main` and `config/fonts/`; the last two are
 * declared inputs of the test task.
 */
class BundledFontCoverageTest {

    @Test
    fun `every character the interface shows is in the bundled fonts, or on the list`() {
        val listed = uncoveredList()
        val missing = shown.mapNotNull { (code, sources) ->
            val lacking = fonts.filter { code !in it.characters && listed[code]?.excuses(it.family) != true }
            if (lacking.isEmpty()) {
                null
            } else {
                val more = if (sources.size > SOURCES_SHOWN) " and ${sources.size - SOURCES_SHOWN} more" else ""
                "${describe(code)} — not in ${lacking.joinToString { it.file }}; shown in " +
                    sources.take(SOURCES_SHOWN).joinToString() + more
            }
        }
        assertTrue(
            "the interface shows characters the bundled fonts do not have — the system font draws them in the " +
                "middle of a line. Add each to config/fonts/characters.txt and re-run " +
                "config/fonts/subset_fonts.py, or, when the typeface has no such glyph, name it in " +
                "${UNCOVERED_LIST.name} with the reason:\n" + missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    @Test
    fun `every line of the uncovered list still applies`() {
        val stale = uncoveredList().values.flatMap { entry ->
            if (entry.code !in shown) {
                listOf("${entry.line} — no longer shown")
            } else {
                entry.families().filter { family ->
                    fonts.filter { it.family == family }.all { entry.code in it.characters }
                }.map { family -> "${entry.line} — now in every $family font" }
            }
        }
        assertEquals("${UNCOVERED_LIST.name} has lines that no longer apply", emptyList<String>(), stale)
    }

    @Test
    fun `the four weights of a family cover the same characters`() {
        FAMILIES.forEach { family ->
            val weights = fonts.filter { it.family == family }
            assertEquals("$family: ${weights.map { it.file }}", WEIGHTS, weights.size)
            val first = weights.first()
            weights.drop(1).forEach { font ->
                val extra = (font.characters - first.characters).sorted().map(::describe)
                val lacking = (first.characters - font.characters).sorted().map(::describe)
                assertTrue(
                    "${font.file} and ${first.file} cover different characters — only in ${font.file}: $extra; " +
                        "only in ${first.file}: $lacking",
                    extra.isEmpty() && lacking.isEmpty(),
                )
            }
        }
    }

    @Test
    fun `every font stays inside the subset list`() {
        val list = subsetList()
        fonts.forEach { font ->
            val outside = (font.characters - list).sorted().map(::describe)
            assertEquals("${font.file}: characters ${SUBSET_LIST.name} does not list", emptyList<String>(), outside)
        }
    }

    @Test
    fun `the reader finds what the fonts are known to hold`() {
        val inter = fonts.first { it.file == "inter_regular.ttf" }
        val mono = fonts.first { it.file == "jetbrains_mono_regular.ttf" }
        listOf('A', 'z', 'é', 'Ж', 'я', '→').forEach { assertTrue("$it", it.code in inter.characters) }
        assertTrue('Ж'.code in mono.characters)
        assertFalse("Inter has no carriage return", '\r'.code in inter.characters)
        assertTrue("JetBrains Mono has a carriage return", '\r'.code in mono.characters)
        assertFalse(WASTEBASKET in inter.characters)
    }

    @Test
    fun `the census reads every kind of source`() {
        val sources = shown.values.flatten().toSet()
        listOf(
            "app/src/main/res/values-ru/",
            "app/src/main/res/values-es/",
            "catalog/src/main/res/values-ru/",
            "app/src/main/assets/presets/",
            "app/src/main/assets/docs/ru/",
            "app/src/main/java/app/knotwork/android/presentation/",
            "app/src/main/java/app/knotwork/android/domain/",
            "catalog/src/main/java/",
        ).forEach { prefix -> assertTrue(prefix, sources.any { it.startsWith(prefix) }) }
    }

    @Test
    fun `escapes in resources, json and literals are read as the characters they stand for`() {
        assertEquals("→ ' \" é", unescape("""→ \' \" é"""))
        assertEquals(listOf(WASTEBASKET), unescape("""🗑""").codePoints().toArray().toList())
    }

    /**
     * One bundled font file.
     *
     * @property file Its name in `res/font/`.
     * @property family `inter` or `jetbrains_mono` — the name without its weight.
     * @property characters The code points its character map gives a glyph.
     */
    private data class BundledFont(val file: String, val family: String, val characters: Set<Int>)

    /**
     * A line of `uncovered-characters.txt`: a character the interface shows that
     * the named families do not have.
     *
     * @property code The character.
     * @property names The families, or `all`.
     * @property line The line as written, for messages.
     */
    private data class UncoveredEntry(val code: Int, val names: Set<String>, val line: String) {
        fun families(): List<String> = if (ALL in names) FAMILIES else names.toList()
        fun excuses(family: String): Boolean = ALL in names || family in names
    }

    /**
     * The code points a TrueType font's character map gives a glyph — its format 4
     * subtables, which hold the Basic Multilingual Plane; every bundled font has
     * exactly those. A format 12 subtable (a character beyond U+FFFF) fails loudly
     * rather than being read in part.
     */
    private object CharacterMap {

        fun read(file: File): Set<Int> {
            val font = ByteBuffer.wrap(file.readBytes())
            val cmap = (0 until font.getShort(TABLE_COUNT).unsigned())
                .map { TABLE_RECORDS + it * TABLE_RECORD_SIZE }
                .firstOrNull { record -> font.getInt(record) == CMAP_TAG }
                ?.let { record -> font.getInt(record + TABLE_OFFSET) }
                ?: error("${file.name}: no cmap table")
            val found = mutableSetOf<Int>()
            repeat(font.getShort(cmap + 2).unsigned()) { index ->
                val subtable = cmap + font.getInt(cmap + ENCODING_RECORDS + index * ENCODING_RECORD_SIZE + 4)
                when (val format = font.getShort(subtable).unsigned()) {
                    FORMAT_SEGMENTED -> found += segmented(font, subtable)
                    FORMAT_GROUPS -> error("${file.name}: a format 12 character map — teach CharacterMap to read it")
                    else -> assertTrue("${file.name}: character map format $format", format in LEGACY)
                }
            }
            return found
        }

        /** Format 4: segments of consecutive code points, each mapped by a delta or a glyph array. */
        private fun segmented(font: ByteBuffer, table: Int): Set<Int> {
            val segments = font.getShort(table + SEGMENT_COUNT_X2).unsigned() / 2
            val ends = table + SEGMENT_ARRAYS
            val starts = ends + segments * 2 + 2
            val deltas = starts + segments * 2
            val rangeOffsets = deltas + segments * 2
            val found = mutableSetOf<Int>()
            repeat(segments) { segment ->
                val start = font.getShort(starts + segment * 2).unsigned()
                val end = font.getShort(ends + segment * 2).unsigned()
                val delta = font.getShort(deltas + segment * 2).toInt()
                val rangeOffsetAt = rangeOffsets + segment * 2
                val rangeOffset = font.getShort(rangeOffsetAt).unsigned()
                for (code in start..minOf(end, LAST_BMP)) {
                    val glyph = if (rangeOffset == 0) {
                        (code + delta) and MASK_16
                    } else {
                        font.getShort(rangeOffsetAt + rangeOffset + (code - start) * 2).unsigned()
                            .let { if (it == 0) 0 else (it + delta) and MASK_16 }
                    }
                    if (glyph != 0) found += code
                }
            }
            return found
        }

        private fun Short.unsigned() = toInt() and MASK_16

        private const val TABLE_COUNT = 4
        private const val TABLE_RECORDS = 12
        private const val TABLE_RECORD_SIZE = 16
        private const val TABLE_OFFSET = 8
        private const val CMAP_TAG = 0x636D6170 // "cmap"
        private const val ENCODING_RECORDS = 4
        private const val ENCODING_RECORD_SIZE = 8
        private const val FORMAT_SEGMENTED = 4
        private const val FORMAT_GROUPS = 12
        private const val SEGMENT_COUNT_X2 = 6
        private const val SEGMENT_ARRAYS = 14
        private const val LAST_BMP = 0xFFFE
        private const val MASK_16 = 0xFFFF

        /** Formats that map nothing Android reads: Mac byte encodings, variation sequences. */
        private val LEGACY = setOf(0, 6, 14)
    }

    private companion object {
        const val WEIGHTS = 4
        const val SOURCES_SHOWN = 3
        const val HEX = 16
        const val WASTEBASKET = 0x1F5D1
        const val ALL = "all"
        val FAMILIES = listOf("inter", "jetbrains_mono")
        val TEXT_ASSETS = setOf("json", "md")
        val ESCAPE = Regex("""\\(?:u([0-9a-fA-F]{4})|(.))""")
        val WHITESPACE = Regex("""\s+""")
        val NOT_DRAWN = setOf(
            Character.CONTROL,
            Character.FORMAT,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
        )
        val root: File = ProductionSources.moduleDirectory().parentFile
        val UNCOVERED_LIST = File(root, "config/fonts/uncovered-characters.txt")
        val SUBSET_LIST = File(root, "config/fonts/characters.txt")

        /** The bundled fonts, read once per test JVM. */
        val fonts: List<BundledFont> by lazy {
            File(root, "app/src/main/res/font").listFiles { file -> file.extension == "ttf" }.orEmpty()
                .sortedBy { it.name }
                .map { BundledFont(it.name, it.nameWithoutExtension.substringBeforeLast('_'), CharacterMap.read(it)) }
                .also { found -> assertEquals(FAMILIES.size * WEIGHTS, found.size) }
        }

        /** Every drawn character of the interface's text, with the files that show it; read once per test JVM. */
        val shown: Map<Int, Set<String>> by lazy {
            val found = sortedMapOf<Int, MutableSet<String>>()
            fun add(source: String, text: String) = text.codePoints()
                .filter(::isDrawn)
                .forEach { found.getOrPut(it) { sortedSetOf() } += source }

            listOf("app", "catalog").forEach { module ->
                File(root, "$module/src/main/res")
                    .listFiles { dir -> dir.isDirectory && dir.name.startsWith("values") }.orEmpty()
                    .flatMap { dir -> dir.listFiles { file -> file.extension == "xml" }.orEmpty().toList() }
                    .forEach { file -> add(path(file), resourceText(file)) }
            }
            listOf("app/src/main/assets/presets", "app/src/main/assets/docs").forEach { dir ->
                File(root, dir).walkTopDown()
                    .filter { it.isFile && it.extension in TEXT_ASSETS }
                    .forEach { file -> add(path(file), unescape(file.readText())) }
            }
            val code = ProductionSources.code.mapKeys { (path, _) -> "app/src/$path" } +
                InterfaceTextScope.catalogSources()
            code.forEach { (source, text) ->
                KotlinStringLiterals.literals(text).forEach { add(source, unescape(it.content)) }
            }
            found
        }

        /** Whether [code] is drawn as a glyph — not a control or format character, not a line or paragraph break. */
        fun isDrawn(code: Int): Boolean = Character.getType(code).toByte() !in NOT_DRAWN

        /** The text of a resource file's strings, plurals and string arrays, escapes resolved. */
        fun resourceText(file: File): String {
            val elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .documentElement.childNodes
            return (0 until elements.length).mapNotNull { elements.item(it) as? Element }.flatMap { element ->
                when (element.tagName) {
                    "string" -> listOf(element.textContent)
                    "plurals", "string-array" -> element.getElementsByTagName("item").let { items ->
                        (0 until items.length).map { items.item(it).textContent }
                    }
                    else -> emptyList()
                }
            }.joinToString("\n") { unescape(it) }
        }

        /**
         * [text] with its backslash escapes resolved — `\uXXXX` to the character, any
         * other `\x` to `x` — as Android resources, JSON and Kotlin literals spell them.
         */
        fun unescape(text: String): String = ESCAPE.replace(text) { match ->
            match.groups[1]?.value?.toInt(HEX)?.toChar()?.toString() ?: match.groupValues[2]
        }

        fun path(file: File) = file.relativeTo(root).invariantSeparatorsPath

        fun uncoveredList(): Map<Int, UncoveredEntry> = UNCOVERED_LIST.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val entry = line.substringBefore("#").trim()
                val reason = line.substringAfter("#", "").trim()
                val parts = entry.split(WHITESPACE)
                assertTrue("${UNCOVERED_LIST.name}: `$line` — give the reason after `#`", reason.isNotEmpty())
                assertEquals("${UNCOVERED_LIST.name}: `$line` — `U+XXXX <families>`", 2, parts.size)
                val names = parts[1].split(",").toSet()
                assertTrue(
                    "${UNCOVERED_LIST.name}: `$line` — families are $FAMILIES or $ALL",
                    names.all { it == ALL || it in FAMILIES },
                )
                val code = codePoint(parts[0])
                code to UncoveredEntry(code, names, line.trim())
            }

        /** The code points `characters.txt` lists. */
        fun subsetList(): Set<Int> = SUBSET_LIST.readLines()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line ->
                val bounds = line.split("..").map(::codePoint)
                (bounds.first()..bounds.last()).toList()
            }
            .toSet()

        fun codePoint(text: String): Int {
            assertTrue("`$text` is not U+XXXX", text.startsWith("U+"))
            return text.removePrefix("U+").toInt(HEX)
        }

        fun describe(code: Int): String =
            "U+%04X %s".format(code, if (isDrawn(code)) String(Character.toChars(code)) else "")
    }
}
