package app.knotwork.android.domain.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [CustomModelLink.parse] — the one check a pasted model link passes
 * before anything downloads, and the one place its file name comes from.
 */
class CustomModelLinkTest {

    @Test
    fun `given a direct litertlm link when parsed then it is accepted under its last segment`() {
        val link = CustomModelLink.parse("https://example.com/models/foo-1.litertlm")

        assertEquals(CustomModelLink.Accepted("https://example.com/models/foo-1.litertlm", "foo-1.litertlm"), link)
    }

    @Test
    fun `given a Hugging Face link with a query string when parsed then the file name drops the query`() {
        val url = "https://huggingface.co/org/repo/resolve/main/model.litertlm?download=true"

        assertEquals(CustomModelLink.Accepted(url, "model.litertlm"), CustomModelLink.parse(url))
    }

    @Test
    fun `given a fragment when parsed then the file name drops it`() {
        val url = "https://example.com/model.litertlm#top"

        assertEquals(CustomModelLink.Accepted(url, "model.litertlm"), CustomModelLink.parse(url))
    }

    @Test
    fun `given surrounding whitespace when parsed then the link is trimmed`() {
        val link = CustomModelLink.parse("  https://example.com/model.litertlm \n")

        assertEquals(CustomModelLink.Accepted("https://example.com/model.litertlm", "model.litertlm"), link)
    }

    @Test
    fun `given an upper-case extension or a trailing slash when parsed then it is accepted`() {
        assertEquals(
            CustomModelLink.Accepted("https://example.com/MODEL.LITERTLM", "MODEL.LITERTLM"),
            CustomModelLink.parse("https://example.com/MODEL.LITERTLM"),
        )
        assertEquals(
            CustomModelLink.Accepted("https://example.com/model.litertlm/", "model.litertlm"),
            CustomModelLink.parse("https://example.com/model.litertlm/"),
        )
    }

    @Test
    fun `given a slashless file name when parsed then it is its own last segment`() {
        assertEquals(
            CustomModelLink.Accepted("my-model.litertlm", "my-model.litertlm"),
            CustomModelLink.parse("my-model.litertlm"),
        )
    }

    @Test
    fun `given another model format when parsed then it is refused`() {
        listOf(
            "https://example.com/models/gemma.task",
            "https://example.com/models/llama.gguf",
            "https://example.com/models/foo-1",
            "https://example.com/model.litertlm.zip",
        ).forEach { url ->
            assertEquals(url, CustomModelLink.NotLitertlm, CustomModelLink.parse(url))
        }
    }

    @Test
    fun `given litertlm only in the query string when parsed then it is refused`() {
        val link = CustomModelLink.parse("https://example.com/download?file=model.litertlm")

        assertEquals(CustomModelLink.NotLitertlm, link)
    }

    @Test
    fun `given a bare extension or no path when parsed then it is refused`() {
        assertEquals(CustomModelLink.NotLitertlm, CustomModelLink.parse("https://example.com/.litertlm"))
        assertEquals(CustomModelLink.NotLitertlm, CustomModelLink.parse("https://example.com"))
        assertEquals(CustomModelLink.NotLitertlm, CustomModelLink.parse("https://example.com/"))
    }

    @Test
    fun `given an empty or blank field when parsed then it is blank`() {
        assertEquals(CustomModelLink.Blank, CustomModelLink.parse(""))
        assertEquals(CustomModelLink.Blank, CustomModelLink.parse("   "))
    }
}
