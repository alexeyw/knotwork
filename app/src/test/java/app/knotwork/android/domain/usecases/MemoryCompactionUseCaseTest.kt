package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.constants.TimeAndIdConstants
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AppError
import app.knotwork.android.domain.models.MemoryChunk
import app.knotwork.android.domain.models.Result
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.repositories.MemoryRepository
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.CompactionCoverageVerifier
import app.knotwork.android.domain.services.EmbeddingProvider
import app.knotwork.android.domain.services.EmbeddingProviderResolver
import app.knotwork.android.domain.services.KMeansClusterer
import app.knotwork.android.domain.services.MemorySupersedeFixture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unit tests for [MemoryCompactionUseCase].
 *
 * Clustering is mocked ([KMeansClusterer]) so the use case's candidate-gating,
 * per-cluster consolidation, persistence, and resilience logic is exercised in
 * isolation from the k-means algorithm (covered by [KMeansClustererTest]).
 */
class MemoryCompactionUseCaseTest {

    private lateinit var llmInferenceEngine: LlmInferenceEngine
    private lateinit var loadModelUseCase: LoadModelUseCase
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var embeddingProviderResolver: EmbeddingProviderResolver
    private lateinit var embeddingProvider: EmbeddingProvider
    private lateinit var memoryRepository: MemoryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var kMeansClusterer: KMeansClusterer
    private lateinit var useCase: MemoryCompactionUseCase

    private val now = 1_000_000_000_000L

    /**
     * A unit vector at [degrees] in two dimensions, so a fixture's similarity to
     * the summary (and to its cluster's centroid) is exact — the coverage gate
     * is judged on real geometry rather than on a placeholder vector. One
     * dimension would make every chunk cosine `1.0` and hide the gate entirely.
     */
    private fun unit(degrees: Double): FloatArray {
        val radians = degrees * PI / 180.0
        return floatArrayOf(cos(radians).toFloat(), sin(radians).toFloat())
    }

    /** Chunks 1, 2, 3, 4 sit 15 degrees apart by default: close, yet distinguishable (cos 15 = 0.966 < 0.98). */
    private fun chunk(id: Long, text: String, degrees: Double = (id - 1) * DEFAULT_SPACING) =
        MemoryChunk(id = id, text = text, embedding = unit(degrees), timestamp = 1L)

    @Before
    fun setup() {
        llmInferenceEngine = mockk()
        loadModelUseCase = mockk()
        promptTemplateEngine = mockk()
        embeddingProviderResolver = mockk()
        embeddingProvider = mockk()
        memoryRepository = mockk()
        settingsRepository = mockk()
        kMeansClusterer = mockk()

        every { settingsRepository.memoryCompactionAgeDays } returns flowOf(30)
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(false)
        coEvery { loadModelUseCase.invoke(any()) } returns Result.Success(Unit)
        coEvery { promptTemplateEngine.render(any(), any()) } answers { firstArg() }
        coEvery { embeddingProviderResolver.resolve() } returns embeddingProvider
        // The default summary sits at the centre of the default cluster (0, 15
        // and 30 degrees), so the coverage gate passes unless a test moves it.
        coEvery { embeddingProvider.embed(any<String>()) } returns unit(DEFAULT_SPACING)
        coEvery { memoryRepository.saveMemory(any(), any(), any(), any()) } returns 99L
        coEvery { memoryRepository.replaceWithConsolidated(any(), any(), any()) } returns 99L
        coEvery { memoryRepository.deleteMemory(any()) } returns Unit
        coEvery { settingsRepository.setMemoryLastCompactedAt(any()) } returns Unit
        every { llmInferenceEngine.generateResponseStream(any()) } returns flowOf("Merged fact")

        useCase = MemoryCompactionUseCase(
            llmInferenceEngine = llmInferenceEngine,
            loadModelUseCase = loadModelUseCase,
            promptTemplateEngine = promptTemplateEngine,
            promptVariableProviders = emptySet(),
            embeddingProviderResolver = embeddingProviderResolver,
            memoryRepository = memoryRepository,
            memorySettings = settingsRepository,
            kMeansClusterer = kMeansClusterer,
            // The verifier is pure arithmetic: exercising the real one keeps
            // these tests honest about what actually gets deleted.
            coverageVerifier = CompactionCoverageVerifier(),
        )
    }

    @Test
    fun `given fewer than three candidates when invoke then does nothing`() = runTest {
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns listOf(chunk(1, "a"), chunk(2, "b"))

        val outcome = useCase(now)

        assertEquals(MemoryCompactionUseCase.MemoryCompactionOutcome.EMPTY, outcome)
        coVerify(exactly = 0) { kMeansClusterer.cluster(any()) }
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
    }

    @Test
    fun `given a dense cluster when invoke then consolidates and deletes originals`() = runTest {
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))

        val outcome = useCase(now)

        assertEquals(1, outcome.clustersProcessed)
        assertEquals(3, outcome.chunksConsolidated)
        assertEquals(1, outcome.chunksCreated)
        assertEquals(0, outcome.clustersRejected)
        assertEquals(0, outcome.chunksKeptUnverified)
        // The summary and the deletions land in one atomic call — never a save
        // followed by separable deletes.
        coVerify(exactly = 1) {
            memoryRepository.replaceWithConsolidated("Merged fact", any(), listOf(1L, 2L, 3L))
        }
        coVerify(exactly = 0) { memoryRepository.deleteMemory(any()) }
        coVerify(exactly = 0) { memoryRepository.saveMemory(any(), any(), any(), any()) }
        // A real consolidation stamps the last-compacted time.
        coVerify(exactly = 1) { settingsRepository.setMemoryLastCompactedAt(now) }
    }

    @Test
    fun `given a summary covering only part of a cluster when invoke then the rest survives verbatim`() = runTest {
        // Chunk 3 sits 90 degrees away: the summary written at 15 degrees cannot
        // be shown to represent it, so it must not be deleted.
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c", degrees = 90.0))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))

        val outcome = useCase(now)

        assertEquals(1, outcome.clustersProcessed)
        assertEquals(2, outcome.chunksConsolidated)
        assertEquals(1, outcome.chunksKeptUnverified)
        coVerify(exactly = 1) {
            memoryRepository.replaceWithConsolidated("Merged fact", any(), listOf(1L, 2L))
        }
    }

    @Test
    fun `given a cluster of unrelated Russian facts when invoke then nothing is deleted`() = runTest {
        // Recorded vectors of the bundled encoder: it cannot tell these facts apart,
        // so it cannot vouch that a summary carries each of them.
        val texts = listOf("Живёт в Берлине", "Аллергия на арахис", "Работает программистом")
        val candidates = texts.mapIndexed { index, text ->
            MemoryChunk(
                id = index + 1L,
                text = text,
                embedding = MemorySupersedeFixture.vector(text),
                timestamp = 1L,
            )
        }
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))
        coEvery { embeddingProvider.embed(any<String>()) } returns MemorySupersedeFixture.vector("Кошку зовут Мурка")

        val outcome = useCase(now)

        assertEquals(1, outcome.clustersRejected)
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
    }

    @Test
    fun `given a summary that covers nothing when invoke then the cluster is rejected entirely`() = runTest {
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))
        // The model answered about something else entirely.
        coEvery { embeddingProvider.embed(any<String>()) } returns unit(90.0)

        val outcome = useCase(now)

        assertEquals(0, outcome.clustersProcessed)
        assertEquals(1, outcome.clustersRejected)
        assertEquals(0, outcome.chunksConsolidated)
        // Nothing is written either: an unverified summary is discarded, not stored.
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setMemoryLastCompactedAt(any()) }
    }

    @Test
    fun `given a summary covering a single fact when invoke then the cluster is rejected`() = runTest {
        // Replacing one fact with a paraphrase of it removes no redundancy and
        // only adds the risk the paraphrase is wrong.
        val candidates = listOf(
            chunk(1, "a"),
            chunk(2, "b", degrees = 80.0),
            chunk(3, "c", degrees = 100.0),
        )
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))

        val outcome = useCase(now)

        assertEquals(0, outcome.clustersProcessed)
        assertEquals(1, outcome.clustersRejected)
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
    }

    @Test
    fun `given verbose memory logging when a dense cluster is consolidated then the pass still succeeds`() = runTest {
        // Verbose logging adds a membership log line; it must not change behaviour.
        every { settingsRepository.verboseMemoryLoggingEnabled } returns flowOf(true)
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))

        val outcome = useCase(now)

        assertEquals(1, outcome.clustersProcessed)
        assertEquals(3, outcome.chunksConsolidated)
        coVerify(exactly = 1) {
            memoryRepository.replaceWithConsolidated("Merged fact", any(), listOf(1L, 2L, 3L))
        }
    }

    @Test
    fun `given only small clusters when invoke then leaves them untouched`() = runTest {
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"), chunk(4, "d"))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1), listOf(2, 3))

        val outcome = useCase(now)

        assertEquals(0, outcome.clustersProcessed)
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setMemoryLastCompactedAt(any()) }
    }

    @Test
    fun `given a blank model reply when invoke then keeps originals`() = runTest {
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))
        every { llmInferenceEngine.generateResponseStream(any()) } returns flowOf("   ")

        val outcome = useCase(now)

        assertEquals(0, outcome.clustersProcessed)
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setMemoryLastCompactedAt(any()) }
    }

    @Test
    fun `given embedding failure when invoke then keeps originals`() = runTest {
        val candidates = listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"))
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns candidates
        every { kMeansClusterer.cluster(any()) } returns listOf(listOf(0, 1, 2))
        coEvery { embeddingProvider.embed(any<String>()) } throws RuntimeException("embed boom")

        val outcome = useCase(now)

        assertEquals(0, outcome.clustersProcessed)
        coVerify(exactly = 0) { memoryRepository.replaceWithConsolidated(any(), any(), any()) }
        coVerify(exactly = 0) { settingsRepository.setMemoryLastCompactedAt(any()) }
    }

    @Test
    fun `given model unavailable when invoke then does nothing`() = runTest {
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns
            listOf(chunk(1, "a"), chunk(2, "b"), chunk(3, "c"))
        coEvery { loadModelUseCase.invoke(any()) } returns
            Result.Error<Unit, AppError>(error = object : AppError.System {})

        val outcome = useCase(now)

        assertEquals(MemoryCompactionUseCase.MemoryCompactionOutcome.EMPTY, outcome)
        coVerify(exactly = 0) { kMeansClusterer.cluster(any()) }
    }

    @Test
    fun `given age window when invoke then queries candidates older than the cutoff`() = runTest {
        coEvery { memoryRepository.getCompactionCandidates(any()) } returns emptyList()

        useCase(now)

        val expectedCutoff = now - 30L * TimeAndIdConstants.MS_PER_DAY
        coVerify(exactly = 1) { memoryRepository.getCompactionCandidates(expectedCutoff) }
    }

    private companion object {
        /** Degrees between neighbouring default chunks. */
        const val DEFAULT_SPACING = 15.0
    }
}
