package app.knotwork.android.domain.presetl10n

import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PipelineSamplePrompt
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.models.PromptPreset
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [PresetTranslations.translate]: which texts of a bundled preset a translation
 * replaces, and that it replaces each one only where the preset still carries
 * the English the translation was written for.
 */
class PresetTranslationsTest {

    private val llm = NodeModel(
        id = "llm",
        type = NodeType.LITE_RT,
        x = 0f,
        y = 0f,
        label = "Local LLM",
        systemPrompt = "Answer briefly.",
        configJson = """{"v":1,"type":"LITE_RT","title":"Local LLM","customPrompt":"Answer briefly."}""",
    )

    private val pipeline = PipelinePreset(
        id = "local_only_qa",
        name = "Local-only Q&A",
        description = "Answers on the device.",
        category = PresetCategory.LOCAL,
        graph = PipelineGraph(
            id = "local_only_qa",
            name = "Local-only Q&A",
            nodes = listOf(llm),
            samplePrompts = listOf(
                PipelineSamplePrompt(title = "Explain an eclipse"),
                PipelineSamplePrompt(title = "Look up Chile", toolsHint = "search_tool"),
            ),
        ),
        isBundled = true,
    )

    private val template = PromptPreset(
        id = "concise",
        name = "Concise assistant",
        description = "Short answers.",
        nodeType = NodeType.LITE_RT,
        systemPrompt = "You are concise.",
        tags = listOf("concise", "offline"),
        isBundled = true,
    )

    private val russian = PresetTranslations(
        pipelines = mapOf(
            "local_only_qa" to PipelinePresetTranslation(
                name = TranslatedText("Local-only Q&A", "Ответы на устройстве"),
                description = TranslatedText("Answers on the device.", "Отвечает на устройстве."),
                nodeLabels = mapOf("llm" to TranslatedText("Local LLM", "Локальная модель")),
                samplePrompts = listOf(
                    TranslatedText("Explain an eclipse", "Объясни затмение"),
                    TranslatedText("Look up Chile", "Найди Чили"),
                ),
            ),
        ),
        prompts = mapOf(
            "concise" to PromptPresetTranslation(
                name = TranslatedText("Concise assistant", "Краткий помощник"),
                description = TranslatedText("Short answers.", "Короткие ответы."),
                tags = listOf(TranslatedText("concise", "кратко")),
            ),
        ),
    )

    @Test
    fun `given a translated bundled pipeline when translated then every text a person reads is in the language`() {
        val translated = russian.translate(pipeline)

        assertEquals("Ответы на устройстве", translated.name)
        assertEquals("Ответы на устройстве", translated.graph.name)
        assertEquals("Отвечает на устройстве.", translated.description)
        assertEquals(
            listOf(PipelineSamplePrompt("Объясни затмение"), PipelineSamplePrompt("Найди Чили", "search_tool")),
            translated.graph.samplePrompts,
        )
        val node = translated.graph.nodes.single()
        assertEquals("Локальная модель", node.label)
        assertEquals("Локальная модель", JSONObject(node.configJson!!).getString("title"))
    }

    @Test
    fun `given a translated bundled pipeline when translated then what a model reads stays as written`() {
        val node = russian.translate(pipeline).graph.nodes.single()

        assertEquals("Answer briefly.", node.systemPrompt)
        assertEquals("Answer briefly.", JSONObject(node.configJson!!).getString("customPrompt"))
    }

    @Test
    fun `given a translation written for older English when translated then that text keeps its new English`() {
        val reworded = pipeline.copy(
            description = "Answers on the device, offline.",
            graph = pipeline.graph.copy(
                nodes = listOf(llm.copy(label = "On-device LLM")),
                samplePrompts = listOf(PipelineSamplePrompt(title = "Explain a solar eclipse")),
            ),
        )

        val translated = russian.translate(reworded)

        assertEquals("Ответы на устройстве", translated.name)
        assertEquals("Answers on the device, offline.", translated.description)
        assertEquals("On-device LLM", translated.graph.nodes.single().label)
        assertEquals("Explain a solar eclipse", translated.graph.samplePrompts.single().title)
    }

    @Test
    fun `given a user preset under a bundled preset's id when translated then it is left as the user wrote it`() {
        val userPreset = pipeline.copy(isBundled = false)

        assertSame(userPreset, russian.translate(userPreset))
    }

    @Test
    fun `given a preset the translation does not cover when translated then it is returned as it is`() {
        val other = pipeline.copy(id = "cloud_assist")

        assertSame(other, russian.translate(other))
        assertSame(pipeline, PresetTranslations.NONE.translate(pipeline))
    }

    @Test
    fun `given a node whose editor title differs from its label when translated then only the label changes`() {
        val config = """{"v":1,"type":"LITE_RT","title":"Something else"}"""
        val preset = pipeline.copy(graph = pipeline.graph.copy(nodes = listOf(llm.copy(configJson = config))))

        val node = russian.translate(preset).graph.nodes.single()

        assertEquals("Локальная модель", node.label)
        assertEquals(config, node.configJson)
    }

    @Test
    fun `given a node without an editor config when translated then its label changes and no config appears`() {
        val preset = pipeline.copy(graph = pipeline.graph.copy(nodes = listOf(llm.copy(configJson = null))))

        val node = russian.translate(preset).graph.nodes.single()

        assertEquals("Локальная модель", node.label)
        assertNull(node.configJson)
    }

    @Test
    fun `given a node whose editor config is not JSON when translated then the config is kept as it was`() {
        val preset = pipeline.copy(graph = pipeline.graph.copy(nodes = listOf(llm.copy(configJson = "{broken"))))

        val node = russian.translate(preset).graph.nodes.single()

        assertEquals("Локальная модель", node.label)
        assertEquals("{broken", node.configJson)
    }

    @Test
    fun `given a translated bundled template when translated then its texts are and its prompt is not`() {
        val translated = russian.translate(template)

        assertEquals("Краткий помощник", translated.name)
        assertEquals("Короткие ответы.", translated.description)
        assertEquals(listOf("кратко", "offline"), translated.tags)
        assertEquals("You are concise.", translated.systemPrompt)
    }

    @Test
    fun `given a user template under a bundled template's id when translated then it is left as written`() {
        val userTemplate = template.copy(isBundled = false)

        assertSame(userTemplate, russian.translate(userTemplate))
        assertSame(template, PresetTranslations.NONE.translate(template))
    }
}
