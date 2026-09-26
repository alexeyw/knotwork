package app.knotwork.android.data.mappers

import app.knotwork.android.data.local.models.ChatMessageEntity
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.Role

/**
 * Converts a [ChatMessageEntity] database model to a [ChatMessage] domain model.
 *
 * The attachment is reconstructed only when [ChatMessageEntity.attachmentPath]
 * is present; the remaining attachment columns fall back to safe defaults
 * (`image/jpeg`, zero dimensions) so a partially-written legacy row never
 * crashes mapping.
 *
 * @return The corresponding [ChatMessage].
 */
fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = try {
        Role.valueOf(role)
    } catch (e: IllegalArgumentException) {
        Role.SYSTEM
    },
    content = content,
    timestamp = timestamp,
    isFinal = isFinal,
    isStarred = isStarred,
    attachment = attachmentPath?.let { path ->
        MessageAttachment(
            path = path,
            mimeType = attachmentMimeType ?: "image/jpeg",
            width = attachmentWidth ?: 0,
            height = attachmentHeight ?: 0,
        )
    },
    modelName = modelName,
    imported = imported,
    relayed = relayed,
)

/**
 * Converts a [ChatMessage] domain model to a [ChatMessageEntity] database model.
 *
 * @return The corresponding [ChatMessageEntity].
 */
fun ChatMessage.toEntity(): ChatMessageEntity = ChatMessageEntity(
    id = id ?: 0,
    sessionId = sessionId,
    role = role.name,
    content = content,
    timestamp = timestamp,
    isFinal = isFinal,
    isStarred = isStarred,
    attachmentPath = attachment?.path,
    attachmentMimeType = attachment?.mimeType,
    attachmentWidth = attachment?.width,
    attachmentHeight = attachment?.height,
    modelName = modelName,
    imported = imported,
    relayed = relayed,
)
