package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.domain.constants.DocumentationLinks
import app.knotwork.android.domain.settings.SettingsRegistry
import app.knotwork.android.domain.settings.anchorKey
import app.knotwork.design.screens.settings.SettingsRowAnchors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The completeness gate for settings help text.
 *
 * Its predecessor, `SettingsSearchCatalogTest`, reads like a gate of this kind
 * and is one on **names only**: `descRes` is nullable and no assertion looks at
 * it, so a row could be registered with no description, or have a stale one
 * deleted, and the build stayed green. That is how one setting came to be
 * explained in four different places in four different wordings — one of them
 * quoting a threshold (`8 s`) that no constant in the code holds.
 *
 * So this gate asserts what that one could not: that every registry row is
 * **decided** — explained, or recorded as deliberately unexplained with a
 * reason — and that every explanation actually resolves to text a reader could
 * use. The verdict is a pure function of the repository, so per the project's
 * static-analysis policy it blocks the build rather than filing a report.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsHelpCatalogTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `every registry row is decided and no decision is orphaned`() {
        val anchors = SettingsRegistry.allEntries().map { it.anchorKey() }.toSet()
        assertEquals(
            "HELP must decide exactly the registry anchors",
            anchors,
            SettingsHelpCatalog.HELP.keys,
        )
    }

    @Test
    fun `every explained row resolves to non-blank text`() {
        explanations().forEach { (anchor, res) ->
            assertTrue("zero string resource for $anchor", res != 0)
            assertTrue("blank help text for $anchor", context.getString(res).isNotBlank())
        }
    }

    @Test
    fun `no explanation exceeds the character ceiling`() {
        explanations().forEach { (anchor, res) ->
            val length = context.getString(res).length
            assertTrue(
                "help for $anchor is $length characters, over the $MAX_HELP_CHARS ceiling",
                length <= MAX_HELP_CHARS,
            )
        }
    }

    @Test
    fun `no two rows are explained by the same sentence`() {
        val byText = explanations().groupBy { (_, res) -> context.getString(res) }
        val shared = byText.filterValues { it.size > 1 }
        assertTrue(
            "the same sentence explains more than one row: ${shared.mapValues { entry ->
                entry.value.map { it.first }
            }}",
            shared.isEmpty(),
        )
    }

    @Test
    fun `no explanation uses the phrasings the copy standard forbids`() {
        explanations().forEach { (anchor, res) ->
            val text = context.getString(res).lowercase()
            FORBIDDEN_PHRASES.forEach { phrase ->
                assertTrue("help for $anchor uses the forbidden phrasing \"$phrase\"", !text.contains(phrase))
            }
        }
    }

    @Test
    fun `a row whose behaviour is not shipped says so rather than staying silent`() {
        val notShipped = SettingsHelpCatalog.HELP
            .filterValues { it is SettingHelp.NotShipped }
            .keys

        // Leaving these silent was the first attempt, and using the app showed
        // why it was wrong: a row with no glyph beside rows that have one reads
        // as unfinished, and "moving this changes nothing yet" is exactly what
        // the person who just dragged the slider needs to be told.
        //
        // The set is empty now: every row that carried this marker was closed by
        // wiring it up or removing it. The assertion stays, and stays pinned to
        // the empty set, so a NEW unshipped row is a deliberate edit here rather
        // than a quiet addition.
        assertEquals(NOT_SHIPPED_ROWS, notShipped)
        val controller = SettingsHelpCatalog.controller(context)
        notShipped.forEach { anchor ->
            assertTrue("$anchor must still offer an explanation", controller.hintFor(anchor) != null)
        }
    }

    @Test
    fun `the controller resolves explained rows and stays silent on the rest`() {
        val controller = SettingsHelpCatalog.controller(context)
        val explained = explanations().first().first
        val unexplained = SettingsHelpCatalog.HELP.entries.first { it.value is SettingHelp.None }.key

        assertEquals(context.getString(explanations().first().second), controller.hintFor(explained)?.text)
        assertEquals(null, controller.hintFor(unexplained))
        assertEquals(null, controller.hintFor(null))
    }

    @Test
    fun `the external automation hint offers the contract document`() {
        // The hint panel has carried a link slot since it was written and no
        // hint filled it. This row is the one whose explanation genuinely does
        // not fit: the safety model, the statuses and the refusal reasons are a
        // document, not a sentence.
        var opened: String? = null
        val controller = SettingsHelpCatalog.controller(context) { id -> opened = id }

        val link = controller.hintFor("EXTERNAL_AUTOMATION_ENABLED")?.link
        assertTrue("EXTERNAL_AUTOMATION_ENABLED must offer a documentation link", link != null)
        link?.onClick?.invoke()

        assertEquals(DocumentationLinks.ID_EXTERNAL_AUTOMATION, opened)
    }

    @Test
    fun `a documentation link names a document the registry still knows`() {
        // The link ids are generated constants, so a document that was renamed
        // or lost its heading fails the build before it reaches here. What this
        // guards is the other direction: a hand-written link surviving the
        // removal of the row it explains, which would then never render.
        //
        // Iterated over HELP_DOC_LINKS, not over HELP: walking the rows would
        // visit only links that still have a row, and an orphan is precisely
        // the entry that has none.
        HELP_DOC_LINKS.forEach { (anchor, link) ->
            assertTrue(
                "$anchor carries a documentation link but is no longer a settings row",
                SettingsHelpCatalog.HELP.containsKey(anchor),
            )
            assertTrue(
                "$anchor links to '${link.documentId}', which the registry does not declare",
                DocumentationLinks.byId(link.documentId) != null,
            )
            assertTrue("$anchor offers a link with no label", context.getString(link.labelRes).isNotBlank())
        }
    }

    @Test
    fun `a row carrying a documentation link renders it`() {
        // Closes the gap between the map and what the panel shows: a row listed
        // in HELP_DOC_LINKS whose hint resolves to null would render no link at
        // all, and the map alone cannot tell.
        val controller = SettingsHelpCatalog.controller(context)
        HELP_DOC_LINKS.keys.forEach { anchor ->
            assertTrue("$anchor must render the link it declares", controller.hintFor(anchor)?.link != null)
        }
    }

    @Test
    fun `only one hint is open at a time`() {
        val controller = SettingsHelpCatalog.controller(context)

        controller.toggle("TEMPERATURE")
        assertEquals("TEMPERATURE", controller.expandedAnchor)

        controller.toggle("TOP_K")
        assertEquals("opening a hint must close the one already open", "TOP_K", controller.expandedAnchor)

        controller.toggle("TOP_K")
        assertEquals("tapping the open hint closes it", null, controller.expandedAnchor)
    }

    @Test
    fun `every explained row has somewhere to show its explanation`() {
        // Read from the production tables, not mirrored: SLIDER_TO_ANCHOR is
        // `internal` and in this very package, and a hand-copied set would be
        // one more unenforced duplicate of exactly the kind this task removes.
        val rendered = SettingsRowAnchors.ALL + SLIDER_TO_ANCHOR.values
        val unreachable = explanations().map { it.first }.filterNot { it in rendered }

        // A hint nobody can open is the same defect this task exists to remove,
        // one level up: text that claims to be in the product and is not. The
        // registry rows with no surface at all are excluded by name here, with
        // the defect filed — so this list can only shrink, and a NEW unreachable
        // hint fails the build.
        assertEquals(
            "these explanations cannot be opened anywhere in the app",
            KNOWN_SURFACELESS,
            unreachable.toSet(),
        )
    }

    /**
     * Every row that carries an explanation, paired with its string resource —
     * `NotShipped` rows included, since they carry text and render a glyph
     * exactly like an ordinary one.
     */
    private fun explanations(): List<Pair<String, Int>> = SettingsHelpCatalog.HELP.entries
        .mapNotNull { (anchor, help) ->
            when (help) {
                is SettingHelp.Text -> anchor to help.res
                is SettingHelp.NotShipped -> anchor to help.res
                is SettingHelp.None -> null
            }
        }

    private companion object {
        /**
         * Rows whose behaviour does not happen yet. Each still carries an
         * explanation saying so; the set is pinned because it is also the
         * defect list, and it may only shrink. It has now shrunk to nothing:
         * the sampling sliders reach the engine, and the four rows that could
         * not be wired were removed instead.
         */
        val NOT_SHIPPED_ROWS: Set<String> = emptySet()

        /**
         * Registry rows that no screen renders, so their explanation has
         * nowhere to open. The five Tools ceilings that used to sit here now
         * have sliders under Tools → Advanced, so the set is empty — and stays
         * pinned at empty, because a registry row search can reach and no
         * screen can show is the exact defect this list recorded.
         */
        val KNOWN_SURFACELESS: Set<String> = emptySet()

        /**
         * The hard ceiling, measured rather than chosen by taste: at 200 % font
         * scale a sentence this long fills about half of a 760 dp screen, which
         * is the point past which the row being explained is pushed off the top
         * and the reader loses what the sentence is about.
         */
        const val MAX_HELP_CHARS = 140

        /**
         * Phrasings the copy standard rules out. Each one describes the control
         * rather than what the reader will notice, which is the register that
         * went unread in closed testing.
         */
        val FORBIDDEN_PHRASES = listOf(
            "enables ",
            "disables ",
            "allows you to",
            "this setting",
            "when enabled",
        )
    }
}
