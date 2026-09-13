package app.knotwork.android.data.mappers

import app.knotwork.android.data.local.models.ChatSessionEntity
import app.knotwork.android.domain.models.ChatSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [ChatSessionEntity] ↔ [ChatSession] mapper.
 *
 * Round-trips both directions to guarantee the `pipelineId`
 * column is preserved end-to-end (DB ⇄ domain), including the `null`
 * sentinel that means "use the default pipeline".
 */
class ChatSessionMapperTest {

    @Test
    fun `entity to domain copies all fields including null pipelineId`() {
        val entity = ChatSessionEntity(
            id = "session-1",
            name = "My Chat",
            updatedAt = 1_000L,
            pipelineId = null,
        )

        val domain = entity.toDomain()

        assertEquals("session-1", domain.id)
        assertEquals("My Chat", domain.name)
        assertEquals(1_000L, domain.updatedAt)
        assertNull(domain.pipelineId)
    }

    @Test
    fun `entity to domain preserves a non-null pipelineId`() {
        val entity = ChatSessionEntity(
            id = "session-2",
            name = "Bound Chat",
            updatedAt = 2_000L,
            pipelineId = "pipeline-x",
        )

        val domain = entity.toDomain()

        assertEquals("pipeline-x", domain.pipelineId)
    }

    @Test
    fun `domain to entity preserves a non-null pipelineId`() {
        val domain = ChatSession(
            id = "session-3",
            name = "Bound Chat",
            updatedAt = 3_000L,
            pipelineId = "pipeline-y",
        )

        val entity = domain.toEntity()

        assertEquals("session-3", entity.id)
        assertEquals("Bound Chat", entity.name)
        assertEquals(3_000L, entity.updatedAt)
        assertEquals("pipeline-y", entity.pipelineId)
    }

    @Test
    fun `domain to entity copies null pipelineId`() {
        val domain = ChatSession(
            id = "session-4",
            name = "Default Chat",
            updatedAt = 4_000L,
            pipelineId = null,
        )

        val entity = domain.toEntity()

        assertNull(entity.pipelineId)
    }

    @Test
    fun `round-trip preserves all fields`() {
        val original = ChatSessionEntity(
            id = "session-rt",
            name = "Round-trip",
            updatedAt = 5_000L,
            pipelineId = "pipeline-z",
            isStarred = true,
            isArchived = true,
        )

        val roundTripped = original.toDomain().toEntity()

        assertEquals(original, roundTripped)
    }

    @Test
    fun `entity to domain carries the archive flag`() {
        val archived = ChatSessionEntity(
            id = "session-5",
            name = "Archived Chat",
            updatedAt = 6_000L,
            isArchived = true,
        ).toDomain()
        val active = ChatSessionEntity(
            id = "session-6",
            name = "Active Chat",
            updatedAt = 7_000L,
        ).toDomain()

        assertTrue(archived.isArchived)
        assertFalse("A session must default to not-archived", active.isArchived)
    }

    @Test
    fun `domain to entity carries the archive flag`() {
        val archived = ChatSession(
            id = "session-7",
            name = "Archived Chat",
            updatedAt = 8_000L,
            isArchived = true,
        ).toEntity()
        val active = ChatSession(
            id = "session-8",
            name = "Active Chat",
            updatedAt = 9_000L,
        ).toEntity()

        assertTrue(archived.isArchived)
        assertFalse("A session must default to not-archived", active.isArchived)
    }
}
