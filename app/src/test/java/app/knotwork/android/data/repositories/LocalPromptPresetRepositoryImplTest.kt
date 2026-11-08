package app.knotwork.android.data.repositories

import android.content.Context
import android.content.res.AssetManager
import app.knotwork.android.data.local.dao.PromptPresetDao
import app.knotwork.android.data.local.models.PromptPresetEntity
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.promptio.PromptPresetJsonSerializer
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
import java.io.IOException

class LocalPromptPresetRepositoryImplTest {

    private lateinit var context: Context
    private lateinit var assets: AssetManager
    private lateinit var dao: PromptPresetDao
    private lateinit var language: FakeAppLanguageRepository
    private lateinit var repository: LocalPromptPresetRepositoryImpl

    private fun preset(
        id: String = "p1",
        name: String = "p",
        nodeType: NodeType = NodeType.LITE_RT,
        tags: List<String> = listOf("a", "b"),
        isBundled: Boolean = false,
    ) = PromptPreset(
        id = id,
        name = name,
        description = "desc",
        nodeType = nodeType,
        systemPrompt = "You are concise.",
        tags = tags,
        isBundled = isBundled,
    )

    @Before
    fun setup() {
        context = mockk()
        assets = mockk()
        dao = mockk(relaxed = true)
        every { context.assets } returns assets
        language = FakeAppLanguageRepository()
        val translations = BundledPresetTranslations(context).apply { dispatcher = UnconfinedTestDispatcher() }
        repository = LocalPromptPresetRepositoryImpl(context, dao, language, translations)
    }

    /** One bundled template, "p" tagged "a", "b", and a Russian translation of its name and first tag. */
    private fun givenOneBundledTemplateTranslatedIntoRussian() {
        val bundled = preset(id = "bundled_one", isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("bundled_one.json")
        every { assets.open("presets/prompts/bundled_one.json") } answers {
            PromptPresetJsonSerializer.serialize(bundled).byteInputStream()
        }
        every { assets.open("presets/l10n/ru.json") } answers {
            """{"formatVersion": 1, "language": "ru", "prompts": {"bundled_one": {
               "name": {"en": "p", "ru": "шаблон"}, "tags": [{"en": "a", "ru": "а"}]}}}""".byteInputStream()
        }
    }

    @Test
    fun `given the interface in Russian when getBundledPresets then a template comes translated, its prompt not`() =
        runTest {
            givenOneBundledTemplateTranslatedIntoRussian()
            language.showIn("ru")

            val template = repository.getBundledPresets().first().single()

            assertEquals("шаблон", template.name)
            assertEquals(listOf("а", "b"), template.tags)
            assertEquals("You are concise.", template.systemPrompt)
        }

    @Test
    fun `when the interface language changes then the templates are emitted again in it`() = runTest {
        givenOneBundledTemplateTranslatedIntoRussian()

        // The first read of the assets runs on Dispatchers.IO; after it, the catalogue
        // comes from the cache and every emission below is immediate.
        repository.getBundledPresets().first()
        val names = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.getBundledPresets().collect { names += it.single().name }
        }

        language.showIn("ru")

        assertEquals(listOf("p", "шаблон"), names)
    }

    @Test
    fun `given the interface in Russian when getPresetById then a bundled template comes translated`() = runTest {
        givenOneBundledTemplateTranslatedIntoRussian()
        language.showIn("ru")

        assertEquals("шаблон", repository.getPresetById("bundled_one")?.name)
    }

    @Test
    fun `given valid bundled JSON files when getBundledPresets then emits parsed presets`() = runTest {
        val bundledOne = preset(id = "bundled_one", isBundled = true)
        val bundledTwo = preset(id = "bundled_two", nodeType = NodeType.CLOUD, isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("bundled_one.json", "bundled_two.json")
        every { assets.open("presets/prompts/bundled_one.json") } answers {
            PromptPresetJsonSerializer.serialize(bundledOne).byteInputStream()
        }
        every { assets.open("presets/prompts/bundled_two.json") } answers {
            PromptPresetJsonSerializer.serialize(bundledTwo).byteInputStream()
        }

        val emitted = repository.getBundledPresets().first()

        assertEquals(2, emitted.size)
        assertTrue(emitted.all { it.isBundled })
        assertEquals(setOf("bundled_one", "bundled_two"), emitted.map { it.id }.toSet())
    }

    @Test
    fun `given malformed bundled JSON when getBundledPresets then skips broken files but keeps the rest`() = runTest {
        val good = preset(id = "good", isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("good.json", "broken.json")
        every { assets.open("presets/prompts/good.json") } answers {
            PromptPresetJsonSerializer.serialize(good).byteInputStream()
        }
        every { assets.open("presets/prompts/broken.json") } answers { "not json".byteInputStream() }

        val emitted = repository.getBundledPresets().first()

        assertEquals(1, emitted.size)
        assertEquals("good", emitted.single().id)
    }

    @Test
    fun `given missing bundled directory when getBundledPresets then emits empty list`() = runTest {
        every { assets.list("presets/prompts") } throws IOException("not found")

        val emitted = repository.getBundledPresets().first()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `given non-json entries in the bundled directory when getBundledPresets then they are ignored`() = runTest {
        val good = preset(id = "good", isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("good.json", ".gitkeep", "README.txt")
        every { assets.open("presets/prompts/good.json") } answers {
            PromptPresetJsonSerializer.serialize(good).byteInputStream()
        }

        val emitted = repository.getBundledPresets().first()

        assertEquals(1, emitted.size)
    }

    @Test
    fun `given bundled cache loaded when getBundledPresets called again then assets not re-read`() = runTest {
        val good = preset(id = "good", isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("good.json")
        every { assets.open("presets/prompts/good.json") } answers {
            PromptPresetJsonSerializer.serialize(good).byteInputStream()
        }

        repository.getBundledPresets().first()
        repository.getBundledPresets().first()

        io.mockk.verify(exactly = 1) { assets.list("presets/prompts") }
        io.mockk.verify(exactly = 1) { assets.open("presets/prompts/good.json") }
    }

    @Test
    fun `given user rows when getUserPresets then maps entities to domain model`() = runTest {
        val row = PromptPresetEntity(
            id = "user-1",
            name = "User preset",
            description = "desc",
            nodeTypeKey = "OUTPUT",
            systemPrompt = "Be brief.",
            tagsCsv = "alpha,beta",
            createdAt = 123L,
        )
        every { dao.getAll() } returns flowOf(listOf(row))

        val emitted = repository.getUserPresets().first()

        assertEquals(1, emitted.size)
        val parsed = emitted.single()
        assertEquals("user-1", parsed.id)
        assertEquals("User preset", parsed.name)
        assertEquals(NodeType.OUTPUT, parsed.nodeType)
        assertEquals("Be brief.", parsed.systemPrompt)
        assertEquals(listOf("alpha", "beta"), parsed.tags)
        assertFalse(parsed.isBundled)
    }

    @Test
    fun `given empty tagsCsv when getUserPresets then tags decode to empty list`() = runTest {
        val row = PromptPresetEntity(
            id = "user-1",
            name = "User preset",
            description = "",
            nodeTypeKey = "LITE_RT",
            systemPrompt = "x",
            tagsCsv = "",
            createdAt = 0L,
        )
        every { dao.getAll() } returns flowOf(listOf(row))

        val emitted = repository.getUserPresets().first()

        assertEquals(emptyList<String>(), emitted.single().tags)
    }

    @Test
    fun `given user row with unknown NodeType when getUserPresets then row is dropped`() = runTest {
        val row = PromptPresetEntity(
            id = "user-1",
            name = "User preset",
            description = "",
            nodeTypeKey = "MAGICAL_TYPE",
            systemPrompt = "x",
            tagsCsv = "",
            createdAt = 0L,
        )
        every { dao.getAll() } returns flowOf(listOf(row))

        val emitted = repository.getUserPresets().first()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `given user preset when saveUserPreset then upserts entity with joined tags`() = runTest {
        val captured = slot<PromptPresetEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        repository.saveUserPreset(preset(tags = listOf("a", "b", "c"), isBundled = false))

        coVerify(exactly = 1) { dao.upsert(any()) }
        assertEquals("p1", captured.captured.id)
        assertEquals("LITE_RT", captured.captured.nodeTypeKey)
        assertEquals("a,b,c", captured.captured.tagsCsv)
        assertEquals("You are concise.", captured.captured.systemPrompt)
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
    fun `given id present in bundled when getPresetById then returns bundled match without hitting DAO`() = runTest {
        val bundled = preset(id = "bundled_one", isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("bundled_one.json")
        every { assets.open("presets/prompts/bundled_one.json") } answers {
            PromptPresetJsonSerializer.serialize(bundled).byteInputStream()
        }

        val result = repository.getPresetById("bundled_one")

        assertNotNull(result)
        assertEquals("bundled_one", result!!.id)
        assertTrue(result.isBundled)
        coVerify(exactly = 0) { dao.getById(any()) }
    }

    @Test
    fun `given id absent from bundled when getPresetById then falls back to DAO`() = runTest {
        every { assets.list("presets/prompts") } returns emptyArray()
        val row = PromptPresetEntity(
            id = "user-1",
            name = "User preset",
            description = "",
            nodeTypeKey = "LITE_RT",
            systemPrompt = "x",
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
        every { assets.list("presets/prompts") } returns emptyArray()
        coEvery { dao.getById("missing") } returns null

        assertNull(repository.getPresetById("missing"))
    }

    @Test
    fun `given getPresetsForType when invoked then concatenates bundled matches and DAO rows`() = runTest {
        val bundledLite = preset(id = "bundled_lite", nodeType = NodeType.LITE_RT, isBundled = true)
        val bundledCloud = preset(id = "bundled_cloud", nodeType = NodeType.CLOUD, isBundled = true)
        every { assets.list("presets/prompts") } returns arrayOf("bundled_lite.json", "bundled_cloud.json")
        every { assets.open("presets/prompts/bundled_lite.json") } answers {
            PromptPresetJsonSerializer.serialize(bundledLite).byteInputStream()
        }
        every { assets.open("presets/prompts/bundled_cloud.json") } answers {
            PromptPresetJsonSerializer.serialize(bundledCloud).byteInputStream()
        }
        val userRow = PromptPresetEntity(
            id = "user-lite",
            name = "User",
            description = "",
            nodeTypeKey = "LITE_RT",
            systemPrompt = "x",
            tagsCsv = "",
            createdAt = 0L,
        )
        every { dao.getAllForType("LITE_RT") } returns flowOf(listOf(userRow))

        val emitted = repository.getPresetsForType(NodeType.LITE_RT).first()

        assertEquals(2, emitted.size)
        assertEquals(setOf("bundled_lite", "user-lite"), emitted.map { it.id }.toSet())
        // Bundled CLOUD preset must NOT leak into the LITE_RT-filtered flow.
        assertFalse(emitted.any { it.id == "bundled_cloud" })
    }
}
