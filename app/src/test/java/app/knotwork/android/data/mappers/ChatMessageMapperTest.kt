package app.knotwork.android.data.mappers

import app.knotwork.android.data.local.models.ChatMessageEntity
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageMapperTest {

    @Test
    fun `toDomain maps entity correctly`() {
        val entity = ChatMessageEntity(
            id = 1L,
            sessionId = "session123",
            role = "USER",
            content = "Hello there",
            timestamp = 1600000000L,
        )

        val domain = entity.toDomain()

        assertEquals(1L, domain.id)
        assertEquals("session123", domain.sessionId)
        assertEquals(Role.USER, domain.role)
        assertEquals("Hello there", domain.content)
        assertEquals(1600000000L, domain.timestamp)
    }

    @Test
    fun `toDomain maps invalid role to SYSTEM`() {
        val entity = ChatMessageEntity(
            id = 2L,
            sessionId = "session123",
            role = "UNKNOWN_ROLE",
            content = "Error message",
            timestamp = 1600000000L,
        )

        val domain = entity.toDomain()

        assertEquals(Role.SYSTEM, domain.role)
    }

    @Test
    fun `toEntity maps domain correctly`() {
        val domainModel = ChatMessage(
            id = 3L,
            sessionId = "session456",
            role = Role.AGENT,
            content = "How can I help?",
            timestamp = 1600000000L,
        )

        val entity = domainModel.toEntity()

        assertEquals(3L, entity.id)
        assertEquals("session456", entity.sessionId)
        assertEquals("AGENT", entity.role)
        assertEquals("How can I help?", entity.content)
        assertEquals(1600000000L, entity.timestamp)
    }

    @Test
    fun `toEntity maps domain with null id to entity with id 0`() {
        val domain = ChatMessage(
            id = null,
            sessionId = "session789",
            role = Role.USER,
            content = "New message",
            timestamp = 1600000000L,
        )

        val entity = domain.toEntity()

        assertEquals(0L, entity.id)
        assertEquals("session789", entity.sessionId)
        assertEquals("USER", entity.role)
        assertEquals("New message", entity.content)
        assertEquals(1600000000L, entity.timestamp)
    }

    @Test
    fun `toDomain preserves isFinal and isStarred flags`() {
        val entity = ChatMessageEntity(
            id = 10L,
            sessionId = "session-flags",
            role = "AGENT",
            content = "intermediate",
            timestamp = 1700000000L,
            isFinal = false,
            isStarred = true,
        )

        val domain = entity.toDomain()

        assertEquals(false, domain.isFinal)
        assertEquals(true, domain.isStarred)
    }

    @Test
    fun `toDomain defaults isFinal to true and isStarred to false for legacy entity`() {
        val entity = ChatMessageEntity(
            id = 11L,
            sessionId = "legacy",
            role = "USER",
            content = "legacy",
            timestamp = 1700000001L,
        )

        val domain = entity.toDomain()

        assertEquals(true, domain.isFinal)
        assertEquals(false, domain.isStarred)
    }

    @Test
    fun `toEntity round-trips isFinal and isStarred`() {
        val domain = ChatMessage(
            id = 12L,
            sessionId = "round-trip",
            role = Role.SYSTEM,
            content = "tool observation",
            timestamp = 1700000002L,
            isFinal = false,
            isStarred = true,
        )

        val entity = domain.toEntity()

        assertEquals(false, entity.isFinal)
        assertEquals(true, entity.isStarred)
    }

    @Test
    fun `toEntity spreads attachment fields`() {
        val domain = ChatMessage(
            id = 20L,
            sessionId = "att",
            role = Role.USER,
            content = "look",
            timestamp = 1700000010L,
            attachment = MessageAttachment(path = "img.jpg", mimeType = "image/jpeg", width = 712, height = 1536),
        )

        val entity = domain.toEntity()

        assertEquals("img.jpg", entity.attachmentPath)
        assertEquals("image/jpeg", entity.attachmentMimeType)
        assertEquals(712, entity.attachmentWidth)
        assertEquals(1536, entity.attachmentHeight)
    }

    @Test
    fun `toDomain reconstructs attachment from entity columns`() {
        val entity = ChatMessageEntity(
            id = 21L,
            sessionId = "att",
            role = "USER",
            content = "look",
            timestamp = 1700000011L,
            attachmentPath = "img.jpg",
            attachmentMimeType = "image/jpeg",
            attachmentWidth = 712,
            attachmentHeight = 1536,
        )

        val attachment = entity.toDomain().attachment

        assertEquals("img.jpg", attachment?.path)
        assertEquals("image/jpeg", attachment?.mimeType)
        assertEquals(712, attachment?.width)
        assertEquals(1536, attachment?.height)
    }

    @Test
    fun `modelName round-trips through both mappers and defaults to null`() {
        val entity = ChatMessageEntity(
            id = 30L,
            sessionId = "model",
            role = "AGENT",
            content = "answer",
            timestamp = 1700000020L,
            modelName = "Gemma 4",
        )
        assertEquals("Gemma 4", entity.toDomain().modelName)

        val domain = ChatMessage(
            id = 31L,
            sessionId = "model",
            role = Role.AGENT,
            content = "answer",
            timestamp = 1700000021L,
            modelName = "Gemma 4",
        )
        assertEquals("Gemma 4", domain.toEntity().modelName)

        // Legacy rows / non-agent messages carry no recorded model.
        assertNull(
            ChatMessageEntity(
                id = 32L,
                sessionId = "legacy",
                role = "USER",
                content = "hi",
                timestamp = 1700000022L,
            ).toDomain().modelName,
        )
    }

    @Test
    fun `relayed round-trips through both mappers and defaults to false`() {
        val entity = ChatMessageEntity(
            id = 40L,
            sessionId = "relay",
            role = "AGENT",
            content = "a tool's result",
            timestamp = 1700000030L,
            relayed = true,
        )
        assertTrue(entity.toDomain().relayed)

        val domain = ChatMessage(
            id = 41L,
            sessionId = "relay",
            role = Role.AGENT,
            content = "a tool's result",
            timestamp = 1700000031L,
            relayed = true,
        )
        assertTrue(domain.toEntity().relayed)

        assertFalse(
            ChatMessageEntity(id = 42L, sessionId = "legacy", role = "AGENT", content = "a", timestamp = 1L)
                .toDomain().relayed,
        )
    }

    @Test
    fun `toDomain yields null attachment when path absent`() {
        val entity = ChatMessageEntity(
            id = 22L,
            sessionId = "no-att",
            role = "USER",
            content = "text only",
            timestamp = 1700000012L,
        )

        assertNull(entity.toDomain().attachment)
    }

    @Test
    fun `toDomain defaults attachment metadata when only path present`() {
        val entity = ChatMessageEntity(
            id = 23L,
            sessionId = "att",
            role = "USER",
            content = "",
            timestamp = 1700000013L,
            attachmentPath = "img.jpg",
        )

        val attachment = entity.toDomain().attachment

        assertEquals("image/jpeg", attachment?.mimeType)
        assertEquals(0, attachment?.width)
        assertEquals(0, attachment?.height)
    }
}
