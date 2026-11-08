package app.knotwork.android.domain.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsSearchEngine] and the [anchorKey] helper.
 *
 * The engine is pure: it filters and ranks a list of already-resolved
 * [SettingsSearchableEntry] values, so these tests build a small synthetic index
 * and also exercise an index derived from the real [SettingsRegistry] to prove
 * the registry can be indexed end-to-end (every row yields a unique anchor).
 */
class SettingsSearchEngineTest {

    private fun entry(
        anchorKey: String,
        name: String,
        category: SettingsCategoryId = SettingsCategoryId.GENERATION,
        categoryTitle: String = "Generation",
        tier: SettingTier = SettingTier.ADVANCED,
        description: String = "",
        synonyms: List<String> = emptyList(),
    ) = SettingsSearchableEntry(anchorKey, category, tier, name, description, categoryTitle, synonyms)

    private val index = listOf(
        entry("TEMPERATURE", "Temperature", synonyms = listOf("sampling", "creativity")),
        entry("MAX_CONTEXT_LENGTH", "Max context length", synonyms = listOf("max", "window", "tokens")),
        entry(
            "PIPELINE_MAX_STEPS",
            "Cap autonomous steps",
            category = SettingsCategoryId.PIPELINES,
            categoryTitle = "Pipelines & structured output",
            tier = SettingTier.BASIC,
            synonyms = listOf("max", "safety"),
        ),
        entry(
            "CRASH_REPORTING_ENABLED",
            "Crash reporting",
            category = SettingsCategoryId.PRIVACY,
            categoryTitle = "Privacy",
            description = "Opt-in, stack-trace only, no message content.",
            synonyms = listOf("telemetry"),
        ),
    )

    @Test
    fun `given blank query when search then returns empty`() {
        assertTrue(SettingsSearchEngine.search("   ", index).isEmpty())
        assertTrue(SettingsSearchEngine.search("", index).isEmpty())
    }

    @Test
    fun `given no match when search then returns empty`() {
        assertTrue(SettingsSearchEngine.search("lidar", index).isEmpty())
    }

    @Test
    fun `given name substring when search then matches case-insensitively with range`() {
        val results = SettingsSearchEngine.search("TEMP", index)
        assertEquals(1, results.size)
        val hit = results.single()
        assertEquals("TEMPERATURE", hit.entry.anchorKey)
        assertEquals(SettingsSearchField.NAME, hit.matchedField)
        assertEquals(0 until 4, hit.nameMatchRange)
        assertNull(hit.synonymHit)
    }

    @Test
    fun `given query when search then name matches rank above synonym matches`() {
        val results = SettingsSearchEngine.search("max", index)
        // "Max context length" — name match (prefix) ranks first.
        assertEquals("MAX_CONTEXT_LENGTH", results.first().entry.anchorKey)
        assertEquals(SettingsSearchField.NAME, results.first().matchedField)
        // "Cap autonomous steps" surfaces only via the synonym "max".
        val capHit = results.first { it.entry.anchorKey == "PIPELINE_MAX_STEPS" }
        assertEquals(SettingsSearchField.SYNONYM, capHit.matchedField)
        assertEquals("max", capHit.synonymHit)
        assertNull(capHit.nameMatchRange)
    }

    @Test
    fun `given category query when search then matches by category title`() {
        val results = SettingsSearchEngine.search("privacy", index)
        assertEquals(listOf("CRASH_REPORTING_ENABLED"), results.map { it.entry.anchorKey })
        assertEquals(SettingsSearchField.CATEGORY, results.single().matchedField)
    }

    @Test
    fun `given description-only query when search then matches by description`() {
        val results = SettingsSearchEngine.search("stack-trace", index)
        assertEquals(listOf("CRASH_REPORTING_ENABLED"), results.map { it.entry.anchorKey })
        assertEquals(SettingsSearchField.DESCRIPTION, results.single().matchedField)
    }

    @Test
    fun `given name prefix when search then outranks mid-string name match`() {
        val twoNames = listOf(
            entry("A", "Recency half-life"), // "life" is mid-string
            entry("B", "Lifetime budget"), // "life" is a prefix
        )
        val results = SettingsSearchEngine.search("life", twoNames)
        assertEquals(listOf("B", "A"), results.map { it.entry.anchorKey })
    }

    @Test
    fun `given query without accents when search then finds the accented name and highlights it`() {
        val spanish = listOf(entry("TILE", "Pipeline de Configuración rápida"))

        val hit = SettingsSearchEngine.search("configuracion rapida", spanish).single()

        assertEquals(SettingsSearchField.NAME, hit.matchedField)
        assertEquals("Configuración rápida", hit.entry.name.substring(hit.nameMatchRange!!))
    }

    @Test
    fun `given accented query when search then finds the text without accents`() {
        val hit = SettingsSearchEngine.search("MÁX", index).first()

        assertEquals("MAX_CONTEXT_LENGTH", hit.entry.anchorKey)
        assertEquals(0 until 3, hit.nameMatchRange)
    }

    @Test
    fun `given n or ñ when search then each finds the other`() {
        val names = listOf(entry("SIZE", "Tamaño máximo"), entry("SIGNAL", "Senal"))

        assertEquals(listOf("SIZE"), SettingsSearchEngine.search("tamano", names).map { it.entry.anchorKey })
        assertEquals(listOf("SIGNAL"), SettingsSearchEngine.search("señal", names).map { it.entry.anchorKey })
    }

    @Test
    fun `given query without accents when search then folds synonyms, category titles and descriptions`() {
        val folded = listOf(
            entry("CRASH", "Informes de fallos", synonyms = listOf("telemetría")),
            entry("TOP_K", "Top-K", categoryTitle = "Generación"),
            entry("THRESHOLD", "Umbral", description = "Similitud mínima de la búsqueda."),
        )

        val synonym = SettingsSearchEngine.search("telemetria", folded).single()
        assertEquals(SettingsSearchField.SYNONYM, synonym.matchedField)
        assertEquals("telemetría", synonym.synonymHit)
        assertEquals(
            SettingsSearchField.CATEGORY,
            SettingsSearchEngine.search("generacion", folded).single().matchedField,
        )
        assertEquals(
            SettingsSearchField.DESCRIPTION,
            SettingsSearchEngine.search("busqueda", folded).single().matchedField,
        )
    }

    @Test
    fun `given Cyrillic when search then ё and й stay letters of their own`() {
        val russian = listOf(entry("HEDGEHOG", "Мой ёж"))

        assertTrue(SettingsSearchEngine.search("мои", russian).isEmpty())
        assertTrue(SettingsSearchEngine.search("еж", russian).isEmpty())
        assertEquals(0 until 6, SettingsSearchEngine.search("МОЙ ЁЖ", russian).single().nameMatchRange)
    }

    @Test
    fun `foldForSearch keeps every character in its place`() {
        val text = "Ñandú Ærø Ǿ İstanbul × ёй Ạ ß"

        val folded = SettingsSearchEngine.foldForSearch(text)

        assertEquals(text.length, folded.length)
        assertEquals("nandu ærø ø istanbul × ёй a ß", folded)
    }

    @Test
    fun `anchorKey returns the key for a persisted setting`() {
        val keyed = SettingEntry(
            key = "TEMPERATURE",
            categoryId = SettingsCategoryId.GENERATION,
            tier = SettingTier.ADVANCED,
            controlType = SettingControlType.SLIDER,
        )
        assertEquals("TEMPERATURE", keyed.anchorKey())
    }

    @Test
    fun `anchorKey derives a stable id for keyless link and action rows`() {
        val link = SettingEntry(
            key = null,
            categoryId = SettingsCategoryId.TOOLS,
            tier = SettingTier.ADVANCED,
            controlType = SettingControlType.LINK,
            linkDestination = "Files / domains",
        )
        assertEquals("LINK_FILES_DOMAINS", link.anchorKey())

        val reset = SettingEntry(
            key = null,
            categoryId = SettingsCategoryId.ABOUT,
            tier = SettingTier.ADVANCED,
            controlType = SettingControlType.RESET,
        )
        assertEquals("RESET", reset.anchorKey())
    }

    @Test
    fun `every registry row yields a unique anchor key`() {
        val anchors = SettingsRegistry.allEntries().map { it.anchorKey() }
        assertEquals("anchor keys must be unique", anchors.size, anchors.toSet().size)
        assertTrue(anchors.all { it.isNotBlank() })
    }
}
