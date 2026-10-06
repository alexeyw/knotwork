package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.MemoryVersion
import app.knotwork.android.domain.repositories.MemoryHistoryRepository
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayOutputStream

class ExportMemoryBaseUseCaseTest {

    private fun useCase(
        repo: MemoryRepository,
        providerId: String = EmbeddingProvider.ID_USE,
        history: Map<Long, List<MemoryVersion>> = emptyMap(),
    ): ExportMemoryBaseUseCase {
        val settings = mockk<SettingsRepository>()
        every { settings.activeEmbeddingProviderId } returns flowOf(providerId)
        val historyRepository = mockk<MemoryHistoryRepository>()
        coEvery { historyRepository.getAllHistory() } returns history
        return ExportMemoryBaseUseCase(repo, settings, historyRepository)
    }

    @Test
    fun `given chunks with history when a subset is exported then only their history is written`() = runTest {
        // Given
        val repo = mockk<MemoryRepository>()
        coEvery { repo.getAllMemories() } returns listOf(
            MemoryChunk(id = 1, text = "Lives in Munich", embedding = floatArrayOf(0.1f), timestamp = 5L),
            MemoryChunk(id = 2, text = "Prefers tea", embedding = floatArrayOf(0.3f), timestamp = 6L),
        )
        val history = mapOf(
            1L to listOf(version(chunkId = 1, text = "Lives in Berlin")),
            2L to listOf(version(chunkId = 2, text = "Prefers coffee")),
        )

        // When
        val out = ByteArrayOutputStream()
        useCase(repo, history = history)(out, ids = setOf(1L))

        // Then
        val chunk = JSONObject(out.toString(Charsets.UTF_8.name())).getJSONArray("chunks").getJSONObject(0)
        val versions = chunk.getJSONArray("history")
        assertEquals(1, versions.length())
        assertEquals("Lives in Berlin", versions.getJSONObject(0).getString("text"))
        assertEquals(1L, versions.getJSONObject(0).getLong("capturedAt"))
        assertEquals(2L, versions.getJSONObject(0).getLong("replacedAt"))
        assertFalse(out.toString(Charsets.UTF_8.name()).contains("Prefers coffee"))
    }

    @Test
    fun `given a chunk without history when exported then it carries no history key`() = runTest {
        val repo = mockk<MemoryRepository>()
        coEvery { repo.getAllMemories() } returns
            listOf(MemoryChunk(id = 1, text = "alpha", embedding = floatArrayOf(0.1f), timestamp = 1L))

        val out = ByteArrayOutputStream()
        useCase(repo)(out)

        val chunk = JSONObject(out.toString(Charsets.UTF_8.name())).getJSONArray("chunks").getJSONObject(0)
        assertFalse(chunk.has("history"))
    }

    private fun version(chunkId: Long, text: String) = MemoryVersion(
        id = 0L,
        chunkId = chunkId,
        text = text,
        source = MemorySource.Manual,
        tags = emptyList(),
        capturedAt = 1L,
        replacedAt = 2L,
    )

    @Test
    fun `invoke serialises every chunk into the target stream`() = runTest {
        val repo = mockk<MemoryRepository>()
        coEvery { repo.getAllMemories() } returns listOf(
            MemoryChunk(id = 1, text = "alpha", embedding = floatArrayOf(0.1f, 0.2f), timestamp = 1L),
            MemoryChunk(
                id = 2,
                text = "beta",
                embedding = floatArrayOf(0.3f),
                timestamp = 2L,
                isPinned = true,
                source = MemorySource.Manual,
                tags = listOf("preference"),
            ),
        )
        val out = ByteArrayOutputStream()
        val written = useCase(repo, providerId = "openai_3_small")(out, nowMillis = 42L)
        val payload = JSONObject(out.toString(Charsets.UTF_8.name()))
        assertEquals(2, written)
        assertEquals(1, payload.getInt("schemaVersion"))
        assertEquals("openai_3_small", payload.getString("embeddingProviderId"))
        assertEquals(42L, payload.getLong("exportedAt"))
        assertEquals(2, payload.getJSONArray("chunks").length())
        val first = payload.getJSONArray("chunks").getJSONObject(0)
        assertEquals("alpha", first.getString("text"))
        val second = payload.getJSONArray("chunks").getJSONObject(1)
        assertEquals(true, second.getBoolean("isPinned"))
        assertEquals("manual", second.getJSONObject("source").getString("type"))
        assertEquals("preference", second.getJSONArray("tags").getString(0))
    }

    @Test
    fun `invoke writes only the requested ids when a subset is supplied`() = runTest {
        val repo = mockk<MemoryRepository>()
        coEvery { repo.getAllMemories() } returns listOf(
            MemoryChunk(id = 1, text = "alpha", embedding = floatArrayOf(0.1f), timestamp = 1L),
            MemoryChunk(id = 2, text = "beta", embedding = floatArrayOf(0.3f), timestamp = 2L),
        )
        val out = ByteArrayOutputStream()
        val written = useCase(repo)(out, ids = setOf(2L))
        val payload = JSONObject(out.toString(Charsets.UTF_8.name()))
        assertEquals(1, written)
        assertEquals(1, payload.getJSONArray("chunks").length())
        assertEquals("beta", payload.getJSONArray("chunks").getJSONObject(0).getString("text"))
    }

    @Test
    fun `invoke returns zero for empty memory`() = runTest {
        val repo = mockk<MemoryRepository>()
        coEvery { repo.getAllMemories() } returns emptyList()
        val written = useCase(repo)(ByteArrayOutputStream())
        assertEquals(0, written)
    }
}
