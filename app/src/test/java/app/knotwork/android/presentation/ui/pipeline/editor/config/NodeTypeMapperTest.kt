package app.knotwork.android.presentation.ui.pipeline.editor.config

import org.junit.Assert.assertEquals
import org.junit.Test
import app.knotwork.android.domain.models.CloudProvider as DomainCloudProvider
import app.knotwork.android.domain.models.NodeType as DomainNodeType
import app.knotwork.design.components.pipelineeditor.CloudProvider as CatalogCloudProvider
import app.knotwork.design.components.pipelineeditor.NodeType as CatalogNodeType

class NodeTypeMapperTest {

    @Test
    fun `given every domain node type when toCatalog round-trip then identity`() {
        DomainNodeType.entries.forEach { type ->
            val catalog = NodeTypeMapper.toCatalog(type)
            val back = NodeTypeMapper.toDomain(catalog)
            assertEquals(type, back)
        }
    }

    @Test
    fun `given PIPELINE domain node type when toCatalog then maps to catalog PIPELINE`() {
        assertEquals(CatalogNodeType.PIPELINE, NodeTypeMapper.toCatalog(DomainNodeType.PIPELINE))
        assertEquals(DomainNodeType.PIPELINE, NodeTypeMapper.toDomain(CatalogNodeType.PIPELINE))
    }

    @Test
    fun `given every catalog node type when toDomain round-trip then identity`() {
        CatalogNodeType.entries.forEach { type ->
            val domain = NodeTypeMapper.toDomain(type)
            val back = NodeTypeMapper.toCatalog(domain)
            assertEquals(type, back)
        }
    }

    @Test
    fun `given every domain provider when mapped to the catalog and back then it is the same provider`() {
        // One entry per provider: a node's provider survives the config sheet whichever it is. The
        // catalog once had a Compatible tile for several, and saving could change which one ran.
        DomainCloudProvider.entries.forEach { provider ->
            assertEquals(provider, CloudProviderMapper.toDomain(CloudProviderMapper.toCatalog(provider)))
        }
        assertEquals(
            "two domain providers share a catalog entry",
            DomainCloudProvider.entries.size,
            DomainCloudProvider.entries.map(CloudProviderMapper::toCatalog).toSet().size,
        )
    }

    @Test
    fun `given null provider when toCatalog then defaults to OPEN_AI`() {
        assertEquals(CatalogCloudProvider.OPEN_AI, CloudProviderMapper.toCatalog(null))
    }

    @Test
    fun `given primary cloud providers when round-trip then identity`() {
        listOf(
            CatalogCloudProvider.OPEN_AI,
            CatalogCloudProvider.ANTHROPIC,
            CatalogCloudProvider.GOOGLE,
        ).forEach { c ->
            val domain = CloudProviderMapper.toDomain(c)
            assertEquals(c, CloudProviderMapper.toCatalog(domain))
        }
    }

    @Test
    fun `given catalog AUTO when toDomain then null`() {
        assertEquals(null, CloudProviderMapper.toDomain(CatalogCloudProvider.AUTO))
    }

    @Test
    fun `given catalog AUTO when toWireId then auto sentinel`() {
        assertEquals(DomainCloudProvider.AUTO_KEY, CloudProviderMapper.toWireId(CatalogCloudProvider.AUTO))
        assertEquals("auto", CloudProviderMapper.toWireId(CatalogCloudProvider.AUTO))
    }

    @Test
    fun `given concrete catalog providers when toWireId then concrete wire id`() {
        assertEquals("openai", CloudProviderMapper.toWireId(CatalogCloudProvider.OPEN_AI))
        assertEquals("deepseek", CloudProviderMapper.toWireId(CatalogCloudProvider.DEEPSEEK))
        assertEquals("openai_compatible", CloudProviderMapper.toWireId(CatalogCloudProvider.OPENAI_COMPATIBLE))
    }

    @Test
    fun `given auto wire id when fromWireId then AUTO preserved`() {
        assertEquals(CatalogCloudProvider.AUTO, CloudProviderMapper.fromWireId("auto"))
        assertEquals(CatalogCloudProvider.AUTO, CloudProviderMapper.fromWireId("AUTO"))
    }

    @Test
    fun `given null or concrete wire id when fromWireId then mapped tile`() {
        // null = legacy "no provider" → OpenAI default (distinct from auto).
        assertEquals(CatalogCloudProvider.OPEN_AI, CloudProviderMapper.fromWireId(null))
        assertEquals(CatalogCloudProvider.ANTHROPIC, CloudProviderMapper.fromWireId("anthropic"))
        assertEquals(CatalogCloudProvider.OLLAMA, CloudProviderMapper.fromWireId("ollama"))
    }

    @Test
    fun `given every catalog provider when wire-id round-trip then identity`() {
        CatalogCloudProvider.entries.forEach { provider ->
            assertEquals(provider, CloudProviderMapper.fromWireId(CloudProviderMapper.toWireId(provider)))
        }
    }

    @Test
    fun `given a node saved with an id spelled its own way when saved unchanged then the spelling is kept`() {
        // A browser-edited file may say "Ollama"; an unchanged save does not rewrite it.
        assertEquals("Ollama", CloudProviderMapper.toWireIdPreserving(CatalogCloudProvider.OLLAMA, "Ollama"))
        assertEquals("groq", CloudProviderMapper.toWireIdPreserving(CatalogCloudProvider.GROQ, "openrouter"))
    }

    @Test
    fun `given every domain provider when mapped to a tile then each has one`() {
        DomainCloudProvider.entries.forEach { provider ->
            val tile = CloudProviderMapper.toCatalog(provider)
            assertEquals(provider.id, CloudProviderMapper.toWireIdPreserving(tile, provider.id))
        }
    }
}
