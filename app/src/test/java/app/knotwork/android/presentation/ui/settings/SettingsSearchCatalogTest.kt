package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.domain.settings.SettingTier
import app.knotwork.android.domain.settings.SettingsCategoryId
import app.knotwork.android.domain.settings.SettingsRegistry
import app.knotwork.android.domain.settings.SettingsSearchEngine
import app.knotwork.android.domain.settings.SettingsSearchField
import app.knotwork.android.domain.settings.SettingsSearchResult
import app.knotwork.android.domain.settings.SettingsSearchableEntry
import app.knotwork.android.domain.settings.anchorKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.knotwork.design.screens.settings.SettingsCategoryId as CatalogCategoryId

/**
 * Drift guard for the settings-search index bridge.
 *
 * The completeness test makes the registry the single source of truth: every row
 * it exposes must have search copy in [SettingsSearchCatalog.SEARCH_STRINGS], so
 * a setting added to the registry cannot silently fall out of search. It is paired
 * with `SettingsRegistryTest`, which guards the registry's own contents.
 *
 * For a long time it guarded **names only**. `descRes` was nullable and no
 * assertion looked at it, so the description a searcher actually reads was the
 * one piece of settings copy under no gate at all — which is how **Block
 * destructive tools** came to be described as "always require a typed confirm"
 * while its own help text said the call is refused outright. Two opposite
 * behaviours, one green build. The description is now non-nullable and its text
 * is asserted here, the same way [SettingsHelpCatalogTest] asserts help text.
 *
 * What no test can check is whether the sentence is *true*. So the gate below
 * does what a machine can: every row has a description, it resolves to usable
 * text, it is not a duplicate of another row's, and it stays one line.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsSearchCatalogTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `every registry anchor has search copy and there is no orphan copy`() {
        val anchors = SettingsRegistry.allEntries().map { it.anchorKey() }.toSet()
        assertEquals(
            "SEARCH_STRINGS must cover exactly the registry anchors",
            anchors,
            SettingsSearchCatalog.SEARCH_STRINGS.keys,
        )
    }

    @Test
    fun `every registry anchor has search synonyms and there are none for a row that is gone`() {
        val anchors = SettingsRegistry.allEntries().map { it.anchorKey() }.toSet()
        assertEquals(anchors, SettingsSearchCatalog.SEARCH_SYNONYMS.keys)
    }

    @Test
    fun `given the app in English when the index is built then every row has its synonyms`() {
        val index = SettingsSearchCatalog.buildIndex(context)

        assertTrue(index.all { it.synonyms.isNotEmpty() })
        // A query that names nothing in the row's own text still finds it.
        val hit = SettingsSearchEngine.search("cost", index).first()
        assertEquals("LINK_RUN_LIMITS", hit.entry.anchorKey)
        assertEquals("cost", hit.synonymHit)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when a Russian or technical term is searched then the row is found`() {
        val index = SettingsSearchCatalog.buildIndex(context)

        val spend = SettingsSearchEngine.search("расходы", index).first()
        assertEquals("LINK_RUN_LIMITS", spend.entry.anchorKey)
        assertEquals("расходы", spend.synonymHit)
        assertEquals("LOCAL_MODEL_BACKEND", SettingsSearchEngine.search("gpu", index).first().entry.anchorKey)
    }

    @Test
    fun `every search-copy entry carries a non-zero name resource`() {
        SettingsSearchCatalog.SEARCH_STRINGS.forEach { (anchor, copy) ->
            assertTrue("blank name resource for $anchor", copy.nameRes != 0)
        }
    }

    @Test
    fun `every search-copy entry resolves to non-blank name and description text`() {
        SettingsSearchCatalog.SEARCH_STRINGS.forEach { (anchor, copy) ->
            assertTrue("zero description resource for $anchor", copy.descRes != 0)
            assertTrue("blank search name for $anchor", context.getString(copy.nameRes).isNotBlank())
            assertTrue("blank search description for $anchor", context.getString(copy.descRes).isNotBlank())
        }
    }

    @Test
    fun `no two rows share a search description`() {
        val byText = SettingsSearchCatalog.SEARCH_STRINGS.entries
            .groupBy { (_, copy) -> context.getString(copy.descRes) }
        val shared = byText.filterValues { it.size > 1 }
        assertTrue(
            "the same description is used by more than one row: ${shared.mapValues { entry ->
                entry.value.map { it.key }
            }}",
            shared.isEmpty(),
        )
    }

    @Test
    fun `no search description exceeds the one-line ceiling`() {
        SettingsSearchCatalog.SEARCH_STRINGS.forEach { (anchor, copy) ->
            val length = context.getString(copy.descRes).length
            assertTrue(
                "description for $anchor is $length characters, over the $MAX_DESC_CHARS ceiling",
                length <= MAX_DESC_CHARS,
            )
        }
    }

    @Test
    fun `toCatalog maps every domain category to a distinct catalog category`() {
        val mapped = SettingsCategoryId.entries.map { it.toCatalog() }
        assertEquals(SettingsCategoryId.entries.size, mapped.toSet().size)
        assertEquals(CatalogCategoryId.Generation, SettingsCategoryId.GENERATION.toCatalog())
        assertEquals(CatalogCategoryId.About, SettingsCategoryId.ABOUT.toCatalog())
    }

    @Test
    fun `toHubRow carries the name match range and tier`() {
        val entry = SettingsSearchableEntry(
            anchorKey = "TEMPERATURE",
            categoryId = SettingsCategoryId.GENERATION,
            tier = SettingTier.ADVANCED,
            name = "Temperature",
            description = "",
            categoryTitle = "Generation",
            synonyms = emptyList(),
        )
        val row = SettingsSearchResult(
            entry = entry,
            matchedField = SettingsSearchField.NAME,
            nameMatchRange = 0 until 4,
            synonymHit = null,
        ).toHubRow()

        assertEquals("TEMPERATURE", row.anchorKey)
        assertEquals(CatalogCategoryId.Generation, row.categoryId)
        assertEquals(0, row.nameMatchStart)
        assertEquals(4, row.nameMatchLength)
        assertFalse(row.isBasic)
        assertEquals(null, row.synonymHit)
    }

    @Test
    fun `toHubRow reports no name match and surfaces the synonym for a synonym hit`() {
        val entry = SettingsSearchableEntry(
            anchorKey = "PIPELINE_MAX_STEPS",
            categoryId = SettingsCategoryId.PIPELINES,
            tier = SettingTier.BASIC,
            name = "Cap autonomous steps",
            description = "",
            categoryTitle = "Pipelines & structured output",
            synonyms = listOf("max"),
        )
        val row = SettingsSearchResult(
            entry = entry,
            matchedField = SettingsSearchField.SYNONYM,
            nameMatchRange = null,
            synonymHit = "max",
        ).toHubRow()

        assertEquals(-1, row.nameMatchStart)
        assertEquals(0, row.nameMatchLength)
        assertTrue(row.isBasic)
        assertEquals("max", row.synonymHit)
    }

    private companion object {
        /**
         * Search descriptions render as a single line under the row name, so the
         * ceiling is what fits there rather than a taste limit: at 200 % font
         * scale a longer sentence wraps and pushes the next result off-screen.
         */
        const val MAX_DESC_CHARS = 80
    }
}
