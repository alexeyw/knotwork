package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.domain.repositories.PipelineRepository
import app.knotwork.android.domain.services.EnglishDefaultNames
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatePipelineUseCaseTest {

    private val pipelineRepository: PipelineRepository = mockk(relaxed = true)
    private val useCase = CreatePipelineUseCase(pipelineRepository, EnglishDefaultNames)

    @Test
    fun `given blank name when invoke then returns failure`() = runTest {
        val result = useCase("   ")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given name longer than 60 chars when invoke then returns failure`() = runTest {
        val longName = "a".repeat(61)

        val result = useCase(longName)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        coVerify(exactly = 0) { pipelineRepository.savePipeline(any()) }
    }

    @Test
    fun `given valid name when invoke then saves seeded INPUT-OUTPUT pipeline`() = runTest {
        val saved = slot<PipelineGraph>()
        coEvery { pipelineRepository.savePipeline(capture(saved)) } returns Unit

        val result = useCase("  My Pipeline  ")

        assertTrue(result.isSuccess)
        val created = result.getOrNull()
        assertNotNull(created)
        created!!

        // Persisted graph matches returned graph.
        assertEquals(created, saved.captured)

        // Name is trimmed.
        assertEquals("My Pipeline", created.name)

        // Seed: exactly one INPUT and one OUTPUT, connected.
        assertEquals(2, created.nodes.size)
        val input = created.nodes.single { it.type == NodeType.INPUT }
        val output = created.nodes.single { it.type == NodeType.OUTPUT }
        assertEquals(1, created.connections.size)
        val connection = created.connections.single()
        assertEquals(input.id, connection.sourceNodeId)
        assertEquals(output.id, connection.targetNodeId)

        // Validation must pass — the seed is meant to be saveable as-is.
        assertTrue(created.validate().isEmpty())
    }

    @Test
    fun `given save failure when invoke then propagates failure`() = runTest {
        coEvery { pipelineRepository.savePipeline(any()) } throws RuntimeException("io error")

        val result = useCase("My Pipeline")

        assertTrue(result.isFailure)
        assertEquals("io error", result.exceptionOrNull()?.message)
    }
}
