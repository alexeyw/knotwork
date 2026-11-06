package app.knotwork.android.data.repositories

import android.content.Context
import android.content.res.AssetManager
import app.knotwork.android.data.local.dao.PipelinePresetDao
import app.knotwork.android.data.local.models.PipelinePresetEntity
import app.knotwork.android.domain.constants.BundledPresetCatalog
import app.knotwork.android.domain.models.ConnectionModel
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.NodeModel
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.models.PipelinePreset
import app.knotwork.android.domain.models.PresetCategory
import app.knotwork.android.domain.pipelineio.PipelinePresetJsonSerializer
import app.knotwork.android.presentation.ui.settings.FakeAppLanguageRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class LocalPipelinePresetRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var dao: PipelinePresetDao
    private lateinit var language: FakeAppLanguageRepository
    private lateinit var repository: LocalPipelinePresetRepositoryImpl

    private val sampleGraph = PipelineGraph(
        id = "graph-id",
        name = "graph",
        nodes = listOf(
            NodeModel(
                id = "i",
                type = NodeType.INPUT,
                x = 0f,
                y = 0f,
                contextConfig = NodeContextConfig.defaultForType(NodeType.INPUT),
            ),
            NodeModel(
                id = "o",
                type = NodeType.OUTPUT,
                x = 100f,
                y = 0f,
                contextConfig = NodeContextConfig.defaultForType(NodeType.OUTPUT),
            ),
        ),
        connections = listOf(ConnectionModel(id = "c", sourceNodeId = "i", targetNodeId = "o")),
    )

    private fun preset(
        id: String = "p1",
        name: String = "p",
        category: PresetCategory = PresetCategory.LOCAL,
        tags: List<String> = listOf("a", "b"),
        isBundled: Boolean = false,
        isInternal: Boolean = false,
    ) = PipelinePreset(
        id = id,
        name = name,
        description = "desc",
        category = category,
        graph = sampleGraph,
        tags = tags,
        isBundled = isBundled,
        isInternal = isInternal,
    )

    @Before
    fun setup() {
        context = mockk()
        assets = mockk()
        dao = mockk(relaxed = true)
        every { context.assets } returns assets
        language = FakeAppLanguageRepository()
        val translations = BundledPresetTranslations(context).apply { dispatcher = UnconfinedTestDispatcher() }
        repository = LocalPipelinePresetRepositoryImpl(context, dao, language, translations)
    }

    /** One bundled preset, "p", and a Russian translation of its name. */
    private fun givenOneBundledPresetTranslatedIntoRussian() {
        val bundled = preset(id = "bundled_one", isBundled = true)
        every { assets.list("presets/pipelines") } returns arrayOf("bundled_one.json")
        every { assets.open("presets/pipelines/bundled_one.json") } answers {
            PipelinePresetJsonSerializer.serialize(bundled).byteInputStream()
        }
        every { assets.open("presets/l10n/ru.json") } answers {
            """{"formatVersion": 1, "language": "ru",
               "pipelines": {"bundled_one": {"name": {"en": "p", "ru": "пресет"}}}}""".byteInputStream()
        }
    }

    @Test
    fun `given the interface in Russian when getBundledPresets then a bundled preset comes translated`() = runTest {
        givenOneBundledPresetTranslatedIntoRussian()
        language.showIn("ru")

        val emitted = repository.getBundledPresets().first()

        assertEquals("пресет", emitted.single().name)
    }

    @Test
    fun `when the interface language changes then the catalogue is emitted again in it`() = runTest {
        givenOneBundledPresetTranslatedIntoRussian()

        // The first read of the assets runs on Dispatchers.IO; after it, the catalogue
        // comes from the cache and every emission below is immediate.
        repository.getBundledPresets().first()
        val names = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.getBundledPresets().collect { names += it.single().name }
        }

        language.showIn("ru")
        language.showIn("en")

        assertEquals(listOf("p", "пресет", "p"), names)
    }

    @Test
    fun `given the interface in Russian when getPresetById then a bundled preset comes translated`() = runTest {
        givenOneBundledPresetTranslatedIntoRussian()
        language.showIn("ru")

        assertEquals("пресет", repository.getPresetById("bundled_one")?.name)
    }

    @Test
    fun `given a language with no translation file when getBundledPresets then presets show as written`() = runTest {
        givenOneBundledPresetTranslatedIntoRussian()
        every { assets.open("presets/l10n/de.json") } throws FileNotFoundException("presets/l10n/de.json")
        language.showIn("de")

        assertEquals("p", repository.getBundledPresets().first().single().name)
    }

    @Test
    fun `given valid bundled JSON files when getBundledPresets then emits parsed presets`() = runTest {
        val bundledOne = preset(id = "bundled_one", isBundled = true)
        val bundledTwo = preset(id = "bundled_two", category = PresetCategory.CLOUD, isBundled = true)
        every { assets.list("presets/pipelines") } returns arrayOf("bundled_one.json", "bundled_two.json")
        every { assets.open("presets/pipelines/bundled_one.json") } answers {
            PipelinePresetJsonSerializer.serialize(bundledOne).byteInputStream()
        }
        every { assets.open("presets/pipelines/bundled_two.json") } answers {
            PipelinePresetJsonSerializer.serialize(bundledTwo).byteInputStream()
        }

        val emitted = repository.getBundledPresets().first()

        assertEquals(2, emitted.size)
        assertTrue(emitted.all { it.isBundled })
        assertEquals(setOf("bundled_one", "bundled_two"), emitted.map { it.id }.toSet())
    }

    @Test
    fun `given malformed bundled JSON when getBundledPresets then skips broken files but keeps the rest`() = runTest {
        val good = preset(id = "good", isBundled = true)
        every { assets.list("presets/pipelines") } returns arrayOf("good.json", "broken.json")
        every { assets.open("presets/pipelines/good.json") } answers {
            PipelinePresetJsonSerializer.serialize(good).byteInputStream()
        }
        every { assets.open("presets/pipelines/broken.json") } answers { "not json".byteInputStream() }

        val emitted = repository.getBundledPresets().first()

        assertEquals(1, emitted.size)
        assertEquals("good", emitted.single().id)
    }

    @Test
    fun `given missing bundled directory when getBundledPresets then emits empty list`() = runTest {
        every { assets.list("presets/pipelines") } throws IOException("not found")

        val emitted = repository.getBundledPresets().first()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `given non-json entries in the bundled directory when getBundledPresets then they are ignored`() = runTest {
        val good = preset(id = "good", isBundled = true)
        every { assets.list("presets/pipelines") } returns arrayOf("good.json", ".gitkeep", "README.txt")
        every { assets.open("presets/pipelines/good.json") } answers {
            PipelinePresetJsonSerializer.serialize(good).byteInputStream()
        }

        val emitted = repository.getBundledPresets().first()

        assertEquals(1, emitted.size)
    }

    @Test
    fun `given bundled cache loaded when getBundledPresets called again then assets not re-read`() = runTest {
        val good = preset(id = "good", isBundled = true)
        every { assets.list("presets/pipelines") } returns arrayOf("good.json")
        every { assets.open("presets/pipelines/good.json") } answers {
            PipelinePresetJsonSerializer.serialize(good).byteInputStream()
        }

        repository.getBundledPresets().first()
        repository.getBundledPresets().first()

        // The asset enumeration and read both happen exactly once across two subscriptions
        // — subsequent reads come from the cached field.
        io.mockk.verify(exactly = 1) { assets.list("presets/pipelines") }
        io.mockk.verify(exactly = 1) { assets.open("presets/pipelines/good.json") }
    }

    @Test
    fun `given user rows when getUserPresets then maps entities to domain model`() = runTest {
        val row = PipelinePresetEntity(
            id = "user-1",
            name = "User preset",
            description = "desc",
            categoryKey = "hybrid",
            graphJson = PipelinePresetJsonSerializer.serialize(
                preset(id = "user-1", category = PresetCategory.HYBRID, isBundled = false),
            ),
            tagsCsv = "alpha,beta",
            createdAt = 123L,
        )
        every { dao.getAll() } returns flowOf(listOf(row))

        val emitted = repository.getUserPresets().first()

        assertEquals(1, emitted.size)
        val parsed = emitted.single()
        assertEquals("user-1", parsed.id)
        assertEquals("User preset", parsed.name)
        assertEquals(PresetCategory.HYBRID, parsed.category)
        assertEquals(listOf("alpha", "beta"), parsed.tags)
        assertFalse(parsed.isBundled)
        assertNotNull(parsed.graph)
    }

    @Test
    fun `given empty tagsCsv when getUserPresets then tags decode to empty list`() = runTest {
        val row = PipelinePresetEntity(
            id = "user-1",
            name = "User preset",
            description = "",
            categoryKey = "other",
            graphJson = PipelinePresetJsonSerializer.serialize(preset(id = "user-1", isBundled = false)),
            tagsCsv = "",
            createdAt = 0L,
        )
        every { dao.getAll() } returns flowOf(listOf(row))

        val emitted = repository.getUserPresets().first()

        assertEquals(emptyList<String>(), emitted.single().tags)
    }

    @Test
    fun `given user preset when saveUserPreset then upserts entity with serialized graph and joined tags`() = runTest {
        val captured = slot<PipelinePresetEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        repository.saveUserPreset(preset(tags = listOf("a", "b", "c"), isBundled = false))

        coVerify(exactly = 1) { dao.upsert(any()) }
        assertEquals("p1", captured.captured.id)
        assertEquals("local", captured.captured.categoryKey)
        assertEquals("a,b,c", captured.captured.tagsCsv)
        assertTrue(captured.captured.graphJson.contains("\"schemaVersion\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `given bundled preset when saveUserPreset then rejects via IllegalArgumentException`() = runTest {
        repository.saveUserPreset(preset(isBundled = true))
    }

    @Test
    fun `given preset id when deleteUserPreset then delegates to DAO`() = runTest {
        repository.deleteUserPreset("victim")

        coVerify(exactly = 1) { dao.deleteById("victim") }
    }

    @Test
    fun `given internal bundled preset when getBundledPresets then it is hidden from the catalogue`() = runTest {
        val visible = preset(id = "visible", isBundled = true)
        val internal = preset(id = "internal_block", isBundled = true, isInternal = true)
        every { assets.list("presets/pipelines") } returns arrayOf("visible.json", "internal_block.json")
        every { assets.open("presets/pipelines/visible.json") } answers {
            PipelinePresetJsonSerializer.serialize(visible).byteInputStream()
        }
        every { assets.open("presets/pipelines/internal_block.json") } answers {
            PipelinePresetJsonSerializer.serialize(internal).byteInputStream()
        }

        val emitted = repository.getBundledPresets().first()

        assertEquals(listOf("visible"), emitted.map { it.id })
    }

    @Test
    fun `given bundled presets when getBundledPresets then they follow the declared display order`() = runTest {
        // AssetManager lists alphabetically; the catalogue must come back in
        // the product order instead, with unranked ids last.
        val ranked = BundledPresetCatalog.DISPLAY_ORDER.take(2)
        val fileNames = (listOf("zz_unranked") + ranked.reversed()).map { "$it.json" }
        every { assets.list("presets/pipelines") } returns fileNames.toTypedArray()
        fileNames.forEach { fileName ->
            val id = fileName.removeSuffix(".json")
            every { assets.open("presets/pipelines/$fileName") } answers {
                PipelinePresetJsonSerializer.serialize(preset(id = id, isBundled = true)).byteInputStream()
            }
        }

        val emitted = repository.getBundledPresets().first()

        assertEquals(ranked + "zz_unranked", emitted.map { it.id })
    }

    @Test
    fun `given internal bundled preset when getPresetById then it still resolves`() = runTest {
        // Composition seeding (LoadPipelineFromPresetUseCase) reaches internal
        // sub-pipelines through getPresetById, so hiding them from the
        // catalogue must not make them unresolvable.
        val internal = preset(id = "internal_block", isBundled = true, isInternal = true)
        every { assets.list("presets/pipelines") } returns arrayOf("internal_block.json")
        every { assets.open("presets/pipelines/internal_block.json") } answers {
            PipelinePresetJsonSerializer.serialize(internal).byteInputStream()
        }

        val result = repository.getPresetById("internal_block")

        assertNotNull(result)
        assertTrue(result!!.isInternal)
        coVerify(exactly = 0) { dao.getById(any()) }
    }

    @Test
    fun `given id present in bundled when getPresetById then returns bundled match without hitting DAO`() = runTest {
        val bundled = preset(id = "bundled_one", isBundled = true)
        every { assets.list("presets/pipelines") } returns arrayOf("bundled_one.json")
        every { assets.open("presets/pipelines/bundled_one.json") } answers {
            PipelinePresetJsonSerializer.serialize(bundled).byteInputStream()
        }

        val result = repository.getPresetById("bundled_one")

        assertNotNull(result)
        assertEquals("bundled_one", result!!.id)
        assertTrue(result.isBundled)
        coVerify(exactly = 0) { dao.getById(any()) }
    }

    @Test
    fun `given id absent from bundled when getPresetById then falls back to DAO`() = runTest {
        every { assets.list("presets/pipelines") } returns emptyArray()
        val row = PipelinePresetEntity(
            id = "user-1",
            name = "User preset",
            description = "",
            categoryKey = "local",
            graphJson = PipelinePresetJsonSerializer.serialize(preset(id = "user-1", isBundled = false)),
            tagsCsv = "",
            createdAt = 0L,
        )
        coEvery { dao.getById("user-1") } returns row

        val result = repository.getPresetById("user-1")

        assertNotNull(result)
        assertFalse(result!!.isBundled)
    }

    @Test
    fun `given id absent in both stores when getPresetById then returns null`() = runTest {
        every { assets.list("presets/pipelines") } returns emptyArray()
        coEvery { dao.getById("missing") } returns null

        assertNull(repository.getPresetById("missing"))
    }
}
