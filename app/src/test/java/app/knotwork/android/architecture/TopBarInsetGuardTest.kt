package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural guard: **a bar at the top of a screen applies the status-bar inset,
 * or a named parent applies it for it.**
 *
 * The app draws edge to edge, and both the shell scaffold and each screen's own
 * `Scaffold` zero their window insets, so every screen owns its status-bar inset.
 * M3's `TopAppBar` adds it by itself; a bar laid out by hand does not. Twice a
 * hand-built bar drew its title under the clock — the Help screen's two bars, then
 * the Files selection bar — and neither was visible to any test: Robolectric renders
 * with no system insets, so a snapshot of the broken bar is identical to a snapshot
 * of the fixed one.
 *
 * Three rules, each read from source:
 *
 *  1. **Scaffold slots.** Every composable of this project called inside a
 *     `topBar = { … }` slot either shows inset evidence — an M3 top app bar, or a
 *     `windowInsetsPadding` / `statusBarsPadding` / `safeDrawingPadding` /
 *     `systemBarsPadding` call — or is a container that only lays out a `content`
 *     slot. This is the rule the Files selection bar broke: its slot switched
 *     between a `TopAppBar` and a hand-built `Row`, and only the first carried the
 *     inset.
 *  2. **Bars outside a slot.** A top bar placed by hand at the top of a column is
 *     invisible to rule 1, so each one is listed in [OFF_SLOT_BARS] with the owner
 *     of its inset — itself, or a named parent file that must show the evidence.
 *     A renamed or deleted entry fails the test rather than silently checking
 *     nothing.
 *  3. **Census.** Any composable named like a top bar (`…TopBar`, `…AppBar`,
 *     `…Toolbar`, `…SelectionBar`) must be reached by rule 1 or listed under rule 2.
 *
 * **What it cannot catch**, recorded so the question is not reopened: a hand-built
 * top bar outside a `Scaffold` slot whose name does not look like a bar (the Help
 * bars are `…ListBar` / `…ReaderBar`, which is why they are listed by name). And it
 * reads evidence, not geometry: an inset applied to the wrong element passes.
 *
 * Sources are read from both modules. The `:catalog` sources reach this test's
 * inputs through its compiled classes, so a change to a bar's code re-runs it.
 */
class TopBarInsetGuardTest {

    @Test
    fun `given every Scaffold topBar slot when scanned then each bar it calls owns the status-bar inset`() {
        val sources = productionSources()
        assertTrue("found no topBar slots — the scan has stopped covering anything", topBarSlots(sources).isNotEmpty())

        assertEquals(
            "Bars in a topBar slot without a status-bar inset (use an M3 TopAppBar, or add " +
                ".windowInsetsPadding(WindowInsets.statusBars) to the bar):",
            emptyList<String>(),
            slotViolations(sources),
        )
    }

    @Test
    fun `given the bars laid out outside a slot when checked then each named inset owner shows the inset`() {
        val sources = productionSources()
        val violations = OFF_SLOT_BARS.mapNotNull { (bar, owner) ->
            val definition = definitionsOf(sources, bar).firstOrNull()
                ?: return@mapNotNull "$bar: no longer defined — update OFF_SLOT_BARS"
            val ownerText = if (owner == SELF) {
                definition.body
            } else {
                sources.entries
                    .firstOrNull { it.key.endsWith(owner) }?.value
                    ?: return@mapNotNull "$bar: inset owner $owner not found"
            }
            if (INSET_EVIDENCE.containsMatchIn(ownerText)) null else "$bar: $owner shows no status-bar inset"
        }

        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `given every composable named like a top bar when counted then each is covered by a rule`() {
        val sources = productionSources()
        val inSlots = topBarSlots(sources).flatMap { slot -> calledProjectComposables(sources, slot.text) }.toSet()
        val unguarded = sources.flatMap { (path, text) ->
            FUN_NAME.findAll(text)
                .filter { isComposableAt(text, it.range.first) }
                .map { it.groupValues[1] }
                .filter { BAR_NAME.matches(it) && it !in inSlots && it !in OFF_SLOT_BARS }
                .map { "$it (${path.substringAfterLast('/')})" }
                .toList()
        }

        assertEquals(
            "Top-bar-shaped composables no rule checks — call them from a topBar slot or add them to OFF_SLOT_BARS:",
            emptyList<String>(),
            unguarded,
        )
    }

    @Test
    fun `given a slot that switches to a hand-built bar without an inset when scanned then it is reported`() {
        // The Files defect, reduced: the rule must be able to fail.
        val sources = mapOf(
            "FilesContent.kt" to """
                @Composable
                fun FilesContent() {
                    Scaffold(
                        topBar = { Shell { if (selecting) SelectionBar() else NormalBar() } },
                    ) { }
                }

                @Composable
                fun Shell(content: @Composable () -> Unit) {
                    Column { content() }
                }

                @Composable
                private fun NormalBar() {
                    TopAppBar(title = { })
                }

                @Composable
                private fun SelectionBar() {
                    Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) { }
                }
            """.trimIndent(),
        )

        assertEquals(listOf("SelectionBar (FilesContent.kt)"), slotViolations(sources))
    }

    @Test
    fun `given the hand-built bar applies the inset when scanned then nothing is reported`() {
        val sources = mapOf(
            "FilesContent.kt" to """
                @Composable
                fun FilesContent() {
                    Scaffold(topBar = { SelectionBar() }) { }
                }

                @Composable
                private fun SelectionBar() {
                    Row(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) { }
                }
            """.trimIndent(),
        )

        assertTrue(slotViolations(sources).isEmpty())
    }

    private data class Slot(val path: String, val text: String)

    private data class Definition(val path: String, val signature: String, val body: String)

    /** Production Kotlin sources of `:app` and `:catalog`, keyed by path. */
    private fun productionSources(): Map<String, String> =
        listOf(File("src/main/java"), File("../catalog/src/main/java"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .associate { it.invariantSeparatorsPath to it.readText() }

    private fun topBarSlots(sources: Map<String, String>): List<Slot> = sources.flatMap { (path, text) ->
        TOP_BAR_SLOT.findAll(text).map { match ->
            val open = match.range.last
            Slot(path, text.substring(open, closingBrace(text, open)))
        }.toList()
    }

    private fun slotViolations(sources: Map<String, String>): List<String> = topBarSlots(sources).flatMap { slot ->
        calledProjectComposables(sources, slot.text).mapNotNull { name ->
            val definition = definitionsOf(sources, name, preferPath = slot.path).first()
            val isContainer = CONTENT_SLOT_PARAMETER.containsMatchIn(definition.signature)
            if (isContainer || INSET_EVIDENCE.containsMatchIn(definition.body)) {
                null
            } else {
                "$name (${definition.path.substringAfterLast('/')})"
            }
        }
    }.distinct()

    private fun calledProjectComposables(sources: Map<String, String>, text: String): List<String> =
        CAPITALISED_CALL.findAll(text).map { it.groupValues[1] }.distinct()
            .filter { definitionsOf(sources, it).isNotEmpty() }
            .toList()

    private fun definitionsOf(
        sources: Map<String, String>,
        name: String,
        preferPath: String? = null,
    ): List<Definition> {
        val all = sources.flatMap { (path, text) ->
            Regex("""\bfun\s+$name\s*\(""").findAll(text)
                .filter { isComposableAt(text, it.range.first) }
                .map { match ->
                    val bodyOpen = text.indexOf('{', match.range.last)
                    // From `fun`, not from the annotation: a preceding declaration's
                    // `content: @Composable` parameter must not make this one a container.
                    val signature = text.substring(match.range.first, maxOf(bodyOpen, match.range.last))
                    val body = if (bodyOpen < 0) "" else text.substring(bodyOpen, closingBrace(text, bodyOpen))
                    Definition(path, signature, body)
                }.toList()
        }
        return all.filter { it.path == preferPath }.ifEmpty { all }
    }

    /**
     * Whether the declaration starting at [funIndex] is annotated `@Composable`: the
     * annotation must sit between the end of the previous declaration and `fun`.
     */
    private fun isComposableAt(text: String, funIndex: Int): Boolean {
        val preamble = text.substring(0, funIndex)
        val previousEnd = maxOf(preamble.lastIndexOf("}\n"), preamble.lastIndexOf(")\n"))
        return preamble.substring(maxOf(previousEnd, 0)).contains("@Composable")
    }

    /** Index just past the brace that closes the one at [open]. */
    private fun closingBrace(text: String, open: Int): Int {
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index + 1
            }
        }
        return text.length
    }

    private companion object {
        /** Marks the owner of an inset as the bar itself. */
        const val SELF = "self"

        /**
         * Top bars placed by hand outside a `Scaffold` slot, mapped to the owner of their
         * status-bar inset: [SELF], or the path suffix of the parent file that applies it.
         */
        val OFF_SLOT_BARS: Map<String, String> = mapOf(
            // Help: a column with a two-line title / back + title + action; each bar insets itself.
            "HelpListBar" to SELF,
            "HelpReaderBar" to SELF,
            // Full-screen image viewer: an overlay with its own bar, which insets itself.
            "ViewerTopBar" to SELF,
            // Onboarding: the host screen pads the whole content with safeDrawingPadding().
            "OnboardingTopBar" to "presentation/ui/onboarding/OnboardingScreen.kt",
            // Pipeline editor: both toolbars sit in a Box that applies the system-bar insets.
            "EditorToolbar" to "presentation/ui/pipeline/editor/PipelineEditorScreen.kt",
            "MultiSelectToolbar" to "presentation/ui/pipeline/editor/PipelineEditorScreen.kt",
        )

        val INSET_EVIDENCE = Regex(
            """\b(?:CenterAligned|Medium|Large)?TopAppBar\s*\(|""" +
                """\b(?:windowInsetsPadding|statusBarsPadding|safeDrawingPadding|systemBarsPadding)\s*\(""",
        )
        val TOP_BAR_SLOT = Regex("""\btopBar\s*=\s*\{""")
        val CAPITALISED_CALL = Regex("""\b([A-Z]\w*)\s*\(""")
        val CONTENT_SLOT_PARAMETER = Regex("""\bcontent\s*:\s*@Composable""")
        val FUN_NAME = Regex("""\bfun\s+([A-Z]\w*)\s*\(""")
        val BAR_NAME = Regex("""\w+(?:TopBar|AppBar|Toolbar|SelectionBar)""")
    }
}
