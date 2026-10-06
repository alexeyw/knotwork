package app.knotwork.android.domain.usecases

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.data.local.Converters
import app.knotwork.android.data.repositories.MemoryHistoryRepositoryImpl
import app.knotwork.android.data.repositories.MemoryRepositoryImpl
import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.engine.NodeContextBuilder
import app.knotwork.android.domain.engine.PipelineExecutionContext
import app.knotwork.android.domain.engine.structured.StructuredOutputGate
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MemorySource
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.MetricsRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.CompactionCoverageVerifier
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.KMeansClusterer
import app.knotwork.android.domain.services.MemoryReranker
import app.knotwork.android.domain.services.MemorySearchStatsTracker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end integration coverage for the **whole long-term memory lifecycle**,
 * wiring the real domain components over an in-memory Room database:
 * auto-extraction → embedding/storage → cross-session retrieval → injection into
 * a node's `--- Long-Term Memory ---` context block → survival across a
 * background compaction pass.
 *
 * Only the two non-deterministic, device-bound dependencies are faked: the local
 * LLM ([LlmInferenceEngine], which has no model on the test device) and the
 * embedding backend ([EmbeddingProvider], replaced with a deterministic
 * text → vector map so similarity is reproducible). Everything else runs for
 * real — Room persistence and the `source`-column round-trip
 * ([MemoryRepositoryImpl] + [Converters]), the cosine search, the
 * [MemoryReranker], the [NodeContextBuilder] block layout, the
 * [KMeansClusterer], and both use cases — so the test proves the pieces
 * actually compose, not just that each behaves in isolation.
 *
 * The faked vectors are chosen so the retrieval query lands on the same vector
 * as the extracted preference fact (cosine `1.0`), while the filler chunks live
 * in an orthogonal direction (cosine `0.0`): close enough to drive a real
 * retrieval hit, far enough that compaction clusters only the filler set.
 */
@RunWith(AndroidJUnit4::class)
class MemoryLifecycleIntegrationTest {

    private lateinit var database: AppDatabase
    private lateinit var repository: MemoryRepository

    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var metricsRepository: MetricsRepository
    private lateinit var embeddingProviderResolver: EmbeddingProviderResolver

    private lateinit var extractionUseCase: MemoryExtractionUseCase
    private lateinit var retrieveUseCase: RetrieveRelevantMemoryUseCase
    private lateinit var compactionUseCase: MemoryCompactionUseCase
    private val nodeContextBuilder = NodeContextBuilder()

    /** First session: the user states a durable UI preference. */
    private val sessionA = "session-a"
    private val sessionAMessages = listOf(
        ChatMessage(id = 1, sessionId = sessionA, role = Role.USER, content = "I prefer dark mode", timestamp = 1L),
        ChatMessage(id = 2, sessionId = sessionA, role = Role.AGENT, content = "Got it.", timestamp = 2L),
    )

    /** Second session: a different question that is semantically about that preference. */
    private val nextSessionQuery = "What's my UI preference?"

    /**
     * Deterministic embedding fake. Texts that should match the retrieval query
     * map to [DARK_VEC]; the compaction filler set maps to the orthogonal
     * [FILLER_VEC]; anything else gets [OTHER_VEC] so an unexpected string can
     * never accidentally score a hit.
     */
    private val vectorByText = mapOf(
        "Prefers dark mode" to DARK_VEC,
        nextSessionQuery to DARK_VEC,
        "Stale fact one" to FILLER_VEC,
        "Stale fact two" to FILLER_VEC,
        "Stale fact three" to FILLER_VEC,
        "Consolidated stale facts" to FILLER_VEC,
    )

    private val fakeEmbeddingProvider = object : EmbeddingProvider {
        override val id: String = EmbeddingProvider.ID_USE
        override val displayName: String = "Fake on-device"
        override val dimension: Int = DARK_VEC.size
        override suspend fun isAvailable(): Boolean = true
        override suspend fun embed(text: String): FloatArray = vectorByText[text] ?: OTHER_VEC
        override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }
    }

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
        settingsRepository = mockk()
        metricsRepository = mockk(relaxed = true)

        // The local model is "loaded" for both extraction and compaction.
        coEvery { loadModelUseCase.invoke(any()) } returns Result.Success(Unit)
        // Templating is exercised elsewhere; here the rendered prompt == template.
        coEvery { promptTemplateEngine.render(any(), any()) } answers { firstArg() }

        // Single LLM fake that serves both passes: extraction returns the fact
        // JSON, compaction returns a single consolidated fact. Two-arg matcher
        // covers both the one-arg compaction call (temperature defaulted) and the
        // gate's temperature-overridden extraction call.
        every { llmInferenceEngine.generateResponseStream(any(), any(), any()) } answers {
            val prompt = firstArg<String>()
            if (prompt.contains("CONSOLIDATED FACT")) {
                flowOf("Consolidated stale facts")
            } else {
                flowOf("""[{"type": "preference", "text": "Prefers dark mode"}]""")
            }
        }

        every { settingsRepository.memorySearchTopK } returns flowOf(TOP_K)
        every { settingsRepository.memorySearchThreshold } returns flowOf(THRESHOLD)
        every { settingsRepository.memoryRecencyHalfLifeDays } returns flowOf(HALF_LIFE_DAYS)
        every { settingsRepository.memoryCompactionAgeDays } returns flowOf(COMPACTION_AGE_DAYS)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        every { settingsRepository.structuredOutputMaxRepairs } returns flowOf(2)
        every { settingsRepository.activeEmbeddingProviderId } returns flowOf(EmbeddingProvider.ID_USE)
        coEvery { settingsRepository.setMemoryLastCompactedAt(any()) } just Runs

        embeddingProviderResolver = EmbeddingProviderResolver(
            providers = mapOf(EmbeddingProvider.ID_USE to fakeEmbeddingProvider),
            memorySettings = settingsRepository,
        )

        val searchStatsTracker = MemorySearchStatsTracker()
        extractionUseCase = MemoryExtractionUseCase(
            llmInferenceEngine = llmInferenceEngine,
            loadModelUseCase = loadModelUseCase,
            promptTemplateEngine = promptTemplateEngine,
            promptVariableProviders = emptySet(),
            embeddingProviderResolver = embeddingProviderResolver,
            memoryRepository = repository,
            memorySearchStatsTracker = searchStatsTracker,
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
        retrieveUseCase = RetrieveRelevantMemoryUseCase(
            embeddingProviderResolver = embeddingProviderResolver,
            memoryRepository = repository,
            memoryReranker = MemoryReranker(),
            memorySettings = settingsRepository,
            memorySearchStatsTracker = searchStatsTracker,
        )
        compactionUseCase = MemoryCompactionUseCase(
            llmInferenceEngine = llmInferenceEngine,
            loadModelUseCase = loadModelUseCase,
            promptTemplateEngine = promptTemplateEngine,
            promptVariableProviders = emptySet(),
            embeddingProviderResolver = embeddingProviderResolver,
            memoryRepository = repository,
            memorySettings = settingsRepository,
            kMeansClusterer = KMeansClusterer(),
            coverageVerifier = CompactionCoverageVerifier(),
        )
    }

    @After
    fun teardown() {
        database.close()
    }

    /**
     * The headline lifecycle: a preference stated in session A is auto-extracted,
     * and in a *later* session a related question retrieves it and the
     * [NodeContextBuilder] folds it into the `--- Long-Term Memory ---` block a
     * downstream node would receive.
     */
    @Test
    fun autoExtractedFact_isRetrievableInNextSessionContextBlock() = runBlocking {
        // Session A completes → auto-extraction distils and stores the fact.
        val extraction = extractionUseCase(sessionA, sessionAMessages)
        assertEquals(1, extraction.saved)
        val stored = repository.getAllMemories().single()
        assertEquals("Prefers dark mode", stored.text)
        assertEquals(MemorySource.ChatSession(sessionA), stored.source)

        // Next session: a memory-enabled node retrieves on the new question.
        val retrieved = retrieveUseCase(nextSessionQuery)
        assertEquals(listOf("Prefers dark mode"), retrieved.map { it.text })

        // The engine would inject those chunks; the builder renders the block.
        val context = nodeContextBuilder.build(
            config = NodeContextConfig(
                chatHistory = false,
                originalTask = false,
                nodeInput = false,
                longTermMemory = true,
                toolResults = false,
            ),
            ctx = PipelineExecutionContext(
                originalUserMessage = nextSessionQuery,
                chatHistory = emptyList(),
                previousNodeOutput = "",
                toolResults = emptyList(),
                memoryEntries = retrieved,
            ),
        )
        assertEquals("--- Long-Term Memory ---\n1. Prefers dark mode", context)
    }

    /**
     * A pinned memory must outlive a background compaction pass and stay
     * retrievable. Three stale, non-pinned filler chunks make a real cluster the
     * pass can consolidate, while the pinned preference (compaction-exempt) is
     * left intact. Compaction is "advanced in time" by passing a `nowMillis`
     * far in the future so the recently-written filler chunks fall past the age
     * cutoff.
     */
    @Test
    fun pinnedMemory_survivesCompactionAndStaysRetrievable() = runBlocking {
        // Store + pin the preference so compaction can never touch it.
        extractionUseCase(sessionA, sessionAMessages)
        val pinnedId = repository.getAllMemories().single().id
        repository.setMemoryPinned(pinnedId, pinned = true)

        // Seed three stale, near-identical filler chunks (one cluster).
        repository.saveMemory("Stale fact one", FILLER_VEC, MemorySource.Manual)
        repository.saveMemory("Stale fact two", FILLER_VEC, MemorySource.Manual)
        repository.saveMemory("Stale fact three", FILLER_VEC, MemorySource.Manual)
        assertEquals(4, repository.getAllMemories().size)

        // Run compaction "100 days later" so the cutoff lands past the fillers.
        val futureNow = System.currentTimeMillis() + 100L * TimeAndIdConstants.MS_PER_DAY
        val outcome = compactionUseCase(futureNow)

        // The three fillers were merged into one summary; the pinned chunk and
        // the new summary remain (3 fillers gone, 1 summary added → 2 total).
        assertEquals(3, outcome.chunksConsolidated)
        assertEquals(1, outcome.chunksCreated)
        val remaining = repository.getAllMemories()
        assertEquals(2, remaining.size)
        assertTrue(remaining.any { it.id == pinnedId && it.isPinned && it.text == "Prefers dark mode" })

        // The pinned preference is still retrievable after compaction.
        val retrieved = retrieveUseCase(nextSessionQuery)
        assertTrue(retrieved.any { it.text == "Prefers dark mode" })
    }

    private companion object {
        /** Direction shared by the stored preference and the retrieval query. */
        val DARK_VEC = floatArrayOf(1f, 0f, 0f)

        /** Orthogonal direction for the compaction filler set. */
        val FILLER_VEC = floatArrayOf(0f, 1f, 0f)

        /** Catch-all for any unmapped text — orthogonal to both of the above. */
        val OTHER_VEC = floatArrayOf(0f, 0f, 1f)

        const val TOP_K = 5
        const val THRESHOLD = 0.55f
        const val HALF_LIFE_DAYS = 30
        const val COMPACTION_AGE_DAYS = 30
    }
}
