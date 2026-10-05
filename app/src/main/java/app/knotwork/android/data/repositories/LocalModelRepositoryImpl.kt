package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.LocalModelDao
import app.knotwork.android.data.mappers.toDomain
import app.knotwork.android.data.mappers.toEntity
import app.knotwork.android.domain.models.ActiveModelMeta
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileHash
import app.knotwork.android.domain.models.ModelFileStatus
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.repositories.ModelPerformanceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
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

    override suspend fun currentFileHash(path: String): String? = try {
        withContext(Dispatchers.IO) {
            val hash = localModelDao.findByPath(path)?.toDomain()?.fileHash
            hash?.sha256?.takeIf { hash.describesFile(File(path)) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Read on the run path for every on-device call it records: a registry that
        // cannot be read costs the call its checksum, never the run.
        Timber.w(e, "Model file hash unavailable; recording the call without it")
        null
    }

    override suspend fun modelsNeedingFileHash(): List<LocalModel> = withContext(Dispatchers.IO) {
        localModelDao.getAllModels().first()
            .map { it.toDomain() }
            .filter { model ->
                val file = File(model.path)
                // A missing file has nothing to hash; a present one needs a pass
                // unless its stored hash still describes it.
                file.isFile && model.fileHash?.describesFile(file) != true
            }
    }

    override suspend fun fileStatus(path: String): ModelFileStatus = withContext(Dispatchers.IO) {
        val model = localModelDao.findByPath(path)?.toDomain()
        val file = File(path)
        when {
            model == null || !file.isFile -> ModelFileStatus.Missing
            model.fileHash?.describesFile(file) == true -> ModelFileStatus.Hashed(model.fileHash.sha256)
            else -> ModelFileStatus.HashPending
        }
    }

    override suspend fun recordFileHash(id: Long, hash: ModelFileHash): Unit = withContext(Dispatchers.IO) {
        localModelDao.setFileHash(
            id = id,
            sha256 = hash.sha256,
            fileSize = hash.fileSizeBytes,
            fileModifiedAt = hash.fileModifiedAtMs,
        )
    }

    /**
     * Whether this hash still describes [file] as it is on disk now: the file
     * exists and has the size and modification time the hash was computed at.
     */
    private fun ModelFileHash.describesFile(file: File): Boolean =
        file.isFile && describes(sizeBytes = file.length(), modifiedAtMs = file.lastModified())

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
