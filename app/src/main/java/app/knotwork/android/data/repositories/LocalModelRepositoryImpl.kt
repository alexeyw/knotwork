package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.LocalModelDao
import app.knotwork.android.data.mappers.toDomain
import app.knotwork.android.data.mappers.toEntity
import app.knotwork.android.domain.models.ActiveModelMeta
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelPerformanceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [LocalModelRepository] that uses [LocalModelDao] as the data source.
 */
@Singleton
class LocalModelRepositoryImpl @Inject constructor(
    private val localModelDao: LocalModelDao,
    private val modelPerformanceRepository: ModelPerformanceRepository,
) : LocalModelRepository {

    override fun getAllModels(): Flow<List<LocalModel>> = localModelDao.getAllModels()
        .map { entities ->
            entities.map { entity ->
                val domain = entity.toDomain()
                // The download manager doesn't push the real content-length
                // through to the local store, so rows that landed via
                // `ModelsViewModel.startDownload` carry `size = 0L`.
                // Patch the size by querying the on-disk file length here
                // so the UI reads the actual footprint (Models subtitle,
                // active-card meta, More-tab counter).
                if (domain.size > 0L) {
                    domain
                } else {
                    val onDiskSize = runCatching { File(domain.path).length() }.getOrDefault(defaultValue = 0L)
                    if (onDiskSize > 0L) domain.copy(size = onDiskSize) else domain
                }
            }
        }
        .flowOn(Dispatchers.IO)

    override suspend fun getActiveModel(): LocalModel? = withContext(Dispatchers.IO) {
        localModelDao.getActiveModel()?.toDomain()
    }

    override suspend fun insertModel(model: LocalModel): Long = withContext(Dispatchers.IO) {
        localModelDao.insertModel(model.toEntity())
    }

    override suspend fun updateModel(model: LocalModel): Unit = withContext(Dispatchers.IO) {
        localModelDao.updateModel(model.toEntity())
    }

    override suspend fun deleteModelById(id: Long): Unit = withContext(Dispatchers.IO) {
        // Remove the on-disk model file before dropping the record, otherwise
        // deleting a model leaks its (often multi-GB) weights file on disk.
        // Best-effort: a missing file or an unreadable path must not block the
        // record deletion, so the file removal is wrapped in runCatching.
        localModelDao.findById(id)?.let { entity ->
            runCatching {
                val file = File(entity.path)
                if (file.exists()) file.delete()
            }
            // Drop the model's performance history too — samples are keyed by
            // path with no foreign key, so they would otherwise be orphaned.
            modelPerformanceRepository.deleteForModel(entity.path)
        }
        localModelDao.deleteModelById(id)
    }

    override suspend fun setActiveModel(id: Long): Unit = withContext(Dispatchers.IO) {
        localModelDao.deactivateAllModels()
        localModelDao.activateModelById(id)
    }

    override suspend fun setVisionSupport(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        localModelDao.setVisionSupport(id, enabled)
    }

    override suspend fun setAudioSupport(id: Long, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        localModelDao.setAudioSupport(id, enabled)
    }

    override suspend fun isInstalled(fileName: String): Boolean = withContext(Dispatchers.IO) {
        localModelDao.countByName(fileName) > 0
    }

    override suspend fun findByFileName(fileName: String): LocalModel? = withContext(Dispatchers.IO) {
        localModelDao.findByName(fileName)?.toDomain()
    }

    override suspend fun findByPath(path: String): LocalModel? = withContext(Dispatchers.IO) {
        localModelDao.findByPath(path)?.toDomain()
    }

    override fun observeActiveModelMeta(): Flow<ActiveModelMeta?> = localModelDao.observeActiveModel()
        .map { entity ->
            if (entity == null) {
                null
            } else {
                val file = runCatching { File(entity.path) }.getOrNull()
                val downloadedAt = file?.takeIf { it.exists() }?.lastModified()
                ActiveModelMeta(
                    modelId = entity.id,
                    name = entity.name,
                    sizeBytes = entity.size,
                    contextWindowTokens = DEFAULT_CONTEXT_WINDOW_TOKENS,
                    quantization = parseQuantization(entity.name),
                    downloadedAtMs = downloadedAt,
                )
            }
        }
        .flowOn(Dispatchers.IO)

    /**
     * Best-effort quantization marker extracted from a model filename.
     * Matches conventional patterns like `gemma-2b-it-q4_K_M` →
     * `Q4_K_M`, `qwen2-7b-f16` → `F16`. Returns `null` if no recognizable
     * marker is found — the UI then hides the quantization column.
     */
    internal fun parseQuantization(modelName: String): String? {
        val match = QUANTIZATION_PATTERN.find(modelName) ?: return null
        return match.value.uppercase()
    }

    private companion object {
        /** Default context-window assumption for the active-model card. */
        const val DEFAULT_CONTEXT_WINDOW_TOKENS: Int = 2_048

        /**
         * Quantization markers we recognize: `q4_K_M`, `q5_0`, `q8_0`,
         * `f16`, `bf16`, `fp16`, `int8`. Case-insensitive.
         */
        val QUANTIZATION_PATTERN: Regex =
            Regex("""(?i)\b(q[0-9](?:_[0-9a-z]+)?|f16|bf16|fp16|int8)\b""")
    }
}
