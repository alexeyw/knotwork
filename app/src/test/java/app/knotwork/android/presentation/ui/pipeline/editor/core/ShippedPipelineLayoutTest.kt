package app.knotwork.android.presentation.ui.pipeline.editor.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the layout of every pipeline the project ships as a file: the bundled presets
 * under `src/main/assets/presets/pipelines/` and the cookbook recipes under
 * `docs/recipes/`. No two node cards in one graph may overlap, with a one-grid-step
 * margin between them.
 *
 * **Why this exists.** Node positions are canvas units, which are dp, and a card is
 * [NodeCardFootprint.WIDTH] × up to [NodeCardFootprint.MAX_HEIGHT] dp. The editor used to
 * treat positions as screen pixels, which stacked every preset's cards on a dense phone and
 * hid the fact that one of them — the comprehensive showcase — overlapped even on a
 * density-1 screen. Now that the editor draws positions in the unit they were authored in,
 * a hand-placed file is the only remaining way to put two cards on top of each other, and
 * nothing else would notice: every graph still parses and validates.
 *
 * Reads the JSON directly and walks every `nodes` array it finds, so the same check covers
 * the preset format, the export format and a multi-pipeline bundle without depending on
 * any of their serializers. Both directories are unit-test inputs (assets through
 * `isIncludeAndroidResources`, recipes through an explicit `inputs.dir`), so an edit to
 * either re-runs this test instead of answering from a cached pass.
 */
class ShippedPipelineLayoutTest {

    private val presetDir = File("src/main/assets/presets/pipelines")
    private val recipeDir = File("../docs/recipes")

    private data class Card(val id: String, val x: Float, val y: Float)

    @Test
    fun `given the shipped pipeline files when read then both directories contribute graphs`() {
        // A path that stops resolving would make the overlap check pass over nothing.
        assertTrue("no bundled presets found under ${presetDir.absolutePath}", graphsIn(presetDir).isNotEmpty())
        assertTrue("no recipes found under ${recipeDir.absolutePath}", graphsIn(recipeDir).isNotEmpty())
    }

    @Test
    fun `given every shipped graph when laid out in dp then no two cards overlap`() {
        val overlaps = (graphsIn(presetDir) + graphsIn(recipeDir)).flatMap { (source, cards) ->
            overlappingPairs(cards).map { (a, b) -> "$source: ${a.id} ↔ ${b.id}" }
        }
        assertEquals(
            "Overlapping node cards (move one of each pair by at least one grid step):",
            emptyList<String>(),
            overlaps,
        )
    }

    @Test
    fun `given two cards closer than a card plus the margin when checked then the pair is reported`() {
        // The check must be able to fail: the showcase pair that overlapped at density 1.
        val cards = listOf(Card("node-1", 58f, 82f), Card("node-23", 190f, 82f), Card("far", 2000f, 2000f))
        assertEquals(listOf("node-1" to "node-23"), overlappingPairs(cards).map { (a, b) -> a.id to b.id })
    }

    @Test
    fun `given cards exactly a card plus the margin apart when checked then no pair is reported`() {
        val step = NodeCardFootprint.WIDTH + CanvasTransform.GRID_STEP
        val cards = listOf(Card("a", 0f, 0f), Card("b", step, 0f))
        assertTrue(overlappingPairs(cards).isEmpty())
    }

    /** Every graph found in the `*.json` files of [dir], labelled `file#graphId`. */
    private fun graphsIn(dir: File): List<Pair<String, List<Card>>> = dir.listFiles { file -> file.extension == "json" }
        .orEmpty()
        .sortedBy { it.name }
        .flatMap { file -> collectGraphs(JSONObject(file.readText()), file.name) }

    /** Walks [json] and returns one entry per object that carries a `nodes` array. */
    private fun collectGraphs(json: Any?, fileName: String): List<Pair<String, List<Card>>> = when (json) {
        is JSONObject -> {
            val own = json.optJSONArray("nodes")?.let { nodes ->
                listOf("$fileName#${json.optString("id")}" to cardsOf(nodes))
            }.orEmpty()
            own + json.keys().asSequence()
                .filter { it != "nodes" }
                .flatMap { collectGraphs(json.get(it), fileName) }
                .toList()
        }
        is JSONArray -> (0 until json.length()).flatMap { collectGraphs(json.get(it), fileName) }
        else -> emptyList()
    }

    private fun cardsOf(nodes: JSONArray): List<Card> = (0 until nodes.length()).map { index ->
        val node = nodes.getJSONObject(index)
        val position = node.getJSONObject("position")
        Card(
            id = node.getString("id"),
            x = position.getDouble("x").toFloat(),
            y = position.getDouble("y").toFloat(),
        )
    }

    /**
     * Pairs whose card rectangles, each grown by one grid step on the right and bottom,
     * intersect — i.e. cards with less than [CanvasTransform.GRID_STEP] of clear space
     * between them on both axes.
     */
    private fun overlappingPairs(cards: List<Card>): List<Pair<Card, Card>> {
        val reachX = NodeCardFootprint.WIDTH + CanvasTransform.GRID_STEP
        val reachY = NodeCardFootprint.MAX_HEIGHT + CanvasTransform.GRID_STEP
        return cards.indices.flatMap { i ->
            (i + 1 until cards.size).mapNotNull { j ->
                val a = cards[i]
                val b = cards[j]
                val apartX = kotlin.math.abs(a.x - b.x) >= reachX
                val apartY = kotlin.math.abs(a.y - b.y) >= reachY
                if (apartX || apartY) null else a to b
            }
        }
    }
}
