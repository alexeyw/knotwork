package app.knotwork.android.domain.usecases

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.repositories.MemoryHistoryRepositoryImpl
import app.knotwork.android.data.repositories.MemoryRepositoryImpl
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end integration coverage for [MemoryExtractionUseCase] wired to the
 * real [MemoryRepositoryImpl] + [Converters] over an in-memory Room database.
 *
 * The local model and embedding backend are faked, but persistence, the
 * `source` column round-trip, and the cosine-similarity dedup run for real —
 * proving the auto-extraction write path actually lands a chunk that a later
 * session could retrieve.
 */
@RunWith(AndroidJUnit4::class)
class MemoryExtractionIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var embeddingProviderResolver: EmbeddingProviderResolver
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var metricsRepository: MetricsRepository
    private lateinit var useCase: MemoryExtractionUseCase

    private val sessionId = "session-int"
    private val messages = listOf(
        ChatMessage(id = 1, sessionId = sessionId, role = Role.USER, content = "I prefer dark mode", timestamp = 1L),
        ChatMessage(id = 2, sessionId = sessionId, role = Role.AGENT, content = "Got it.", timestamp = 2L),
    )

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MemoryRepositoryImpl(database.memoryDao(), Converters(), database.memoryHistoryDao())

        llmInferenceEngine = mockk()
        loadModelUseCase = mockk()
        promptTemplateEngine = mockk()
        embeddingProviderResolver = mockk()
        embeddingProvider = mockk()
        settingsRepository = mockk()
        metricsRepository = mockk(relaxed = true)

        coEvery { loadModelUseCase.invoke(any()) } returns Result.Success(Unit)
        coEvery { promptTemplateEngine.render(any(), any()) } answers { firstArg() }
        coEvery { embeddingProviderResolver.resolve() } returns embeddingProvider
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } returns
            flowOf("""[{"type": "preference", "text": "Prefers dark mode"}]""")

        useCase = MemoryExtractionUseCase(
            llmInferenceEngine = llmInferenceEngine,
            loadModelUseCase = loadModelUseCase,
            promptTemplateEngine = promptTemplateEngine,
            promptVariableProviders = emptySet(),
            embeddingProviderResolver = embeddingProviderResolver,
            memoryRepository = repository,
            memorySearchStatsTracker = MemorySearchStatsTracker(),
            structuredOutputGate = StructuredOutputGate(),
            runSettings = settingsRepository,
            metricsRepository = metricsRepository,
            // Strict: these scenarios write a new fact or one identical to a stored
            // one, neither of which asks the judge.
            supersedeJudge = mockk(),
            memoryHistoryRepository = MemoryHistoryRepositoryImpl(
                database.memoryDao(),
                database.memoryHistoryDao(),
                Converters(),
            ),
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun extraction_persistsFactWithChatSessionSource() = runBlocking {
        coEvery { embeddingProvider.embed(any<List<String>>()) } returns listOf(floatArrayOf(1f, 0f, 0f))

        val outcome = useCase(sessionId, messages)

        assertEquals(1, outcome.saved)
        val stored = repository.getAllMemories()
        assertEquals(1, stored.size)
        assertEquals("Prefers dark mode", stored.single().text)
        // The `source` column round-trips through Room + Converters.
        assertEquals(MemorySource.ChatSession(sessionId), stored.single().source)
    }

    @Test
    fun extraction_skipsFactAlreadyStored() = runBlocking {
        val embedding = floatArrayOf(0f, 1f, 0f)
        coEvery { embeddingProvider.embed(any<List<String>>()) } returns listOf(embedding)
        // Pre-seed an identical chunk; the real cosine search must flag the
        // extracted fact as a duplicate (similarity 1.0 >= 0.92).
        repository.saveMemory("Prefers dark mode", embedding, MemorySource.Manual)

        val outcome = useCase(sessionId, messages)

        assertEquals(0, outcome.saved)
        assertEquals(1, outcome.skippedDuplicates)
        assertEquals(1, repository.getAllMemories().size)
    }
}
