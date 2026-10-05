package app.knotwork.android.data.mappers

import app.knotwork.android.data.local.models.LocalModelEntity
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileHash

/**
 * Maps a [LocalModelEntity] data transfer object to a [LocalModel] domain model.
 *
 * @return The mapped [LocalModel].
 */
fun LocalModelEntity.toDomain(): LocalModel = LocalModel(
    id = this.id,
    name = this.name,
    path = this.path,
    size = this.size,
    isActive = this.isActive,
    supportsVision = this.supportsVision,
    supportsAudio = this.supportsAudio,
    fileHash = fileHashOrNull(),
)

/**
 * The stored file hash as one value, or `null` unless all three columns are
 * set — a partly written stamp is no stamp.
 */
private fun LocalModelEntity.fileHashOrNull(): ModelFileHash? {
    val sha = sha256 ?: return null
    val size = sha256FileSize ?: return null
    val modifiedAt = sha256FileModifiedAt ?: return null
    return ModelFileHash(sha256 = sha, fileSizeBytes = size, fileModifiedAtMs = modifiedAt)
}

/**
 * Maps a [LocalModel] domain model to a [LocalModelEntity] data transfer object.
 *
 * @return The mapped [LocalModelEntity].
 */
fun LocalModel.toEntity(): LocalModelEntity = LocalModelEntity(
    id = this.id,
    name = this.name,
    path = this.path,
    size = this.size,
    isActive = this.isActive,
    supportsVision = this.supportsVision,
    supportsAudio = this.supportsAudio,
    sha256 = this.fileHash?.sha256,
    sha256FileSize = this.fileHash?.fileSizeBytes,
    sha256FileModifiedAt = this.fileHash?.fileModifiedAtMs,
)
