package app.knotwork.android.domain.presetl10n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PresetTranslationsJson.parse]: the file format, and the strictness that keeps
 * a text a model reads out of a translation.
 */
class PresetTranslationsJsonTest {

    private fun parse(json: String, language: String = "ru") = PresetTranslationsJson.parse(json, language)

    private fun failure(json: String, language: String = "ru"): String =
        assertThrows(IllegalArgumentException::class.java) { parse(json, language) }.message.orEmpty()

    @Test
    fun `given a full file when parsed then every text is read with the English it translates`() {
        val translations = parse(
            """
            {
              "formatVersion": 1,
              "language": "ru",
              "pipelines": {
                "qa": {
                  "name": {"en": "Q&A", "ru": "Вопросы"},
                  "description": {"en": "Answers.", "ru": "Ответы."},
                  "nodes": {"input": {"en": "Input", "ru": "Вход"}},
                  "samplePrompts": [{"en": "Hi", "ru": "Привет"}]
                }
              },
              "prompts": {
                "concise": {
                  "name": {"en": "Concise", "ru": "Кратко"},
                  "description": {"en": "Short.", "ru": "Коротко."},
                  "tags": [{"en": "offline", "ru": "офлайн"}]
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            PresetTranslations(
                pipelines = mapOf(
                    "qa" to PipelinePresetTranslation(
                        name = TranslatedText("Q&A", "Вопросы"),
                        description = TranslatedText("Answers.", "Ответы."),
                        nodeLabels = mapOf("input" to TranslatedText("Input", "Вход")),
                        samplePrompts = listOf(TranslatedText("Hi", "Привет")),
                    ),
                ),
                prompts = mapOf(
                    "concise" to PromptPresetTranslation(
                        name = TranslatedText("Concise", "Кратко"),
                        description = TranslatedText("Short.", "Коротко."),
                        tags = listOf(TranslatedText("offline", "офлайн")),
                    ),
                ),
            ),
            translations,
        )
    }

    @Test
    fun `given members left out when parsed then they translate nothing`() {
        val translations = parse("""{"formatVersion": 1, "language": "ru", "pipelines": {"qa": {}}, "prompts": {}}""")

        assertEquals(PresetTranslations(pipelines = mapOf("qa" to PipelinePresetTranslation())), translations)
        assertEquals(PresetTranslations.NONE, parse("""{"formatVersion": 1, "language": "ru"}"""))
    }

    @Test
    fun `given a system prompt in a pipeline translation when parsed then the file is refused`() {
        val message = failure(
            """{"formatVersion": 1, "language": "ru",
               "pipelines": {"qa": {"systemPrompt": {"en": "Be brief.", "ru": "Будь краток."}}}}""",
        )

        assertTrue(message, message.contains("pipelines.qa") && message.contains("systemPrompt"))
    }

    @Test
    fun `given a system prompt in a template translation when parsed then the file is refused`() {
        val message = failure(
            """{"formatVersion": 1, "language": "ru",
               "prompts": {"concise": {"systemPrompt": {"en": "Be brief.", "ru": "Будь краток."}}}}""",
        )

        assertTrue(message, message.contains("prompts.concise") && message.contains("systemPrompt"))
    }

    @Test
    fun `given an unknown member at the root when parsed then the file is refused`() {
        assertTrue(failure("""{"formatVersion": 1, "language": "ru", "skills": {}}""").contains("skills"))
    }

    @Test
    fun `given a pair with a third language when parsed then the file is refused`() {
        val message = failure(
            """{"formatVersion": 1, "language": "ru",
               "pipelines": {"qa": {"name": {"en": "Q", "ru": "В", "de": "F"}}}}""",
        )

        assertTrue(message, message.contains("pipelines.qa.name") && message.contains("de"))
    }

    @Test
    fun `given a pair without its translation when parsed then the file is refused`() {
        val message = failure("""{"formatVersion": 1, "language": "ru", "pipelines": {"qa": {"name": {"en": "Q"}}}}""")

        assertTrue(message, message.contains("pipelines.qa.name"))
    }

    @Test
    fun `given an empty translation when parsed then the file is refused`() {
        val message = failure(
            """{"formatVersion": 1, "language": "ru",
               "pipelines": {"qa": {"nodes": {"input": {"en": "Input", "ru": "  "}}}}}""",
        )

        assertTrue(message, message.contains("pipelines.qa.nodes.input"))
    }

    @Test
    fun `given values of the wrong shape when parsed then the file is refused`() {
        failure("""{"formatVersion": 1, "language": "ru", "pipelines": []}""")
        failure("""{"formatVersion": 1, "language": "ru", "pipelines": {"qa": "Q&A"}}""")
        failure("""{"formatVersion": 1, "language": "ru", "pipelines": {"qa": {"name": "Q&A"}}}""")
        failure("""{"formatVersion": 1, "language": "ru", "pipelines": {"qa": {"samplePrompts": {}}}}""")
        failure("""{"formatVersion": 1, "language": "ru", "prompts": {"c": {"tags": [{"en": 1, "ru": "а"}]}}}""")
    }

    @Test
    fun `given another format version when parsed then the file is refused`() {
        assertTrue(failure("""{"formatVersion": 2, "language": "ru"}""").contains("format 2"))
        assertTrue(failure("""{"language": "ru"}""").contains("format -1"))
    }

    @Test
    fun `given a file declaring another language when parsed then the file is refused`() {
        assertTrue(failure("""{"formatVersion": 1, "language": "uk"}""").contains("'uk'"))
    }

    @Test
    fun `given text that is not JSON when parsed then the file is refused`() {
        assertTrue(failure("not json").contains("not JSON"))
    }
}
