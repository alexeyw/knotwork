package app.knotwork.design.components.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a long message's bubble preview ends, by the design's cut rule. */
class ChatMessagePreviewTest {

    /** [count] paragraphs of exactly [length] characters, each ending in a full stop. */
    private fun paragraphs(count: Int, length: Int = 300): List<String> = (1..count).map { n ->
        val head = "Paragraph $n. "
        head + "word ".repeat(length / 5).take(length - head.length - 1) + "."
    }

    private fun preview(source: String, markdown: Boolean = true): ChatMessagePreview =
        checkNotNull(chatMessagePreview(source, markdown)) { "no preview for ${source.length} characters" }

    @Test
    fun `given a message of 6 000 characters or less when previewed then it is shown whole`() {
        assertNull(chatMessagePreview("a ".repeat(3_000), markdown = true))
        assertNull(chatMessagePreview("a".repeat(CHAT_PREVIEW_THRESHOLD), markdown = false))
    }

    @Test
    fun `given paragraphs when previewed then the preview ends on the last whole paragraph within 4 000`() {
        val blocks = paragraphs(40)
        val source = blocks.joinToString("\n\n")

        val shown = preview(source)

        val whole = blocks.runningFold("") { acc, p -> if (acc.isEmpty()) p else "$acc\n\n$p" }.drop(1)
        assertEquals(whole.last { it.length <= PREVIEW_MAX_CHARS }, shown.text)
        assertEquals(shown.text.length * 100 / source.length, shown.shownPercent)
    }

    @Test
    fun `given a heading as the last block that fits when previewed then the preview stops before it`() {
        // 13 × 300 + 12 × 2 = 3 924; the heading ends at 3 938 and the next paragraph at 4 240.
        val first = paragraphs(13).joinToString("\n\n")
        val source = "$first\n\n## Next part\n\n" + paragraphs(30).joinToString("\n\n")
        assertEquals(3_924, first.length)

        val shown = preview(source)

        assertEquals(first, shown.text)
    }

    @Test
    fun `given short blocks then one long paragraph when previewed then it is cut inside at its last space`() {
        val long = "word ".repeat(2_000).trimEnd()
        val source = "Intro line.\n\n$long"

        val shown = preview(source)

        assertTrue(shown.text.length in PREVIEW_MIN_CHARS..PREVIEW_MAX_CHARS)
        assertTrue(shown.text.endsWith("word"))
        assertEquals(' ', source[shown.text.length])
    }

    @Test
    fun `given one block without whitespace when previewed then it is cut at 4 000 and shows under 1 percent`() {
        val json = "{\"id\":1,\"name\":\"item\"},".repeat(45_000)

        val shown = preview(json)

        assertEquals(PREVIEW_MAX_CHARS, shown.text.length)
        assertEquals(0, shown.shownPercent)
    }

    @Test
    fun `given a long block whose only space is near its start when cut then it is cut at 4 000, not at that space`() {
        val source = "Intro.\n\nKey: " + "A".repeat(20_000)

        val shown = preview(source)

        assertEquals(PREVIEW_MAX_CHARS, shown.text.length)
    }

    @Test
    fun `given an emoji across the 4 000th character when cut there then the emoji is not split`() {
        val source = "x".repeat(PREVIEW_MAX_CHARS - 1) + "😀" + "y".repeat(5_000)

        val shown = preview(source, markdown = false)

        assertEquals(PREVIEW_MAX_CHARS - 1, shown.text.length)
    }

    @Test
    fun `given a code block of 300 lines when previewed then the preview ends after a whole line, fence open`() {
        val lines = (1..300).map { "val step$it = pipeline.node(\"step-$it\").run()" }
        val source = "Here:\n\n```kotlin\n" + lines.joinToString("\n") + "\n```"

        val shown = preview(source)

        assertTrue(shown.text.length in PREVIEW_MIN_CHARS..PREVIEW_MAX_CHARS)
        assertTrue(shown.text.endsWith(".run()"))
        assertTrue("the preview leaves the fence open", !shown.text.trimEnd().endsWith("```"))
    }

    @Test
    fun `given a long table when previewed then the preview ends after a whole row`() {
        val rows = (1..400).map { "| Station $it | ${it * 3} | ${it * 7} |" }
        val source = "| Station | Bikes | Docks |\n|---|---|---|\n" + rows.joinToString("\n")

        val shown = preview(source)

        assertTrue(shown.text.length in PREVIEW_MIN_CHARS..PREVIEW_MAX_CHARS)
        assertTrue(shown.text.endsWith("|"))
        assertTrue(rows.any { shown.text.endsWith(it) })
    }

    @Test
    fun `given a long list when previewed then the preview ends after a whole item`() {
        val items = (1..200).map { "- Item $it: " + "detail ".repeat(5).trimEnd() }
        val source = items.joinToString("\n")

        val shown = preview(source)

        assertTrue(items.any { shown.text.endsWith(it) })
        assertTrue(shown.text.length in PREVIEW_MIN_CHARS..PREVIEW_MAX_CHARS)
    }

    @Test
    fun `given a heading before a block without whitespace when previewed then the cut goes into that block`() {
        val source = "Short intro.\n\n## Payload\n\n" + "A".repeat(20_000)

        val shown = preview(source)

        assertEquals(PREVIEW_MAX_CHARS, shown.text.length)
        assertTrue(shown.text.contains("## Payload"))
    }

    @Test
    fun `given plain text when previewed then it ends on a whole line and Markdown means nothing`() {
        val lines = (1..300).map { "# not a heading, line $it of the pasted log" }
        val source = lines.joinToString("\n")

        val shown = preview(source, markdown = false)

        assertTrue(lines.any { shown.text.endsWith(it) })
        assertTrue(shown.text.length in PREVIEW_MIN_CHARS..PREVIEW_MAX_CHARS)
    }

    @Test
    fun `given the design's restic guide size when previewed then the share is rounded down`() {
        // 3 962 of 11 240 characters is 35.2 %: shown as 35.
        val source = "a".repeat(3_962) + "\n" + "b ".repeat((11_240 - 3_963) / 2) + "b"
        assertEquals(11_240, source.length)

        assertEquals(35, preview(source, markdown = false).shownPercent)
    }
}
