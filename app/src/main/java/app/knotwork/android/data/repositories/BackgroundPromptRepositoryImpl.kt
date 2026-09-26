package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.BackgroundPromptDao
import app.knotwork.android.data.local.models.BackgroundPromptEntity
import app.knotwork.android.domain.repositories.BackgroundPromptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * [BackgroundPromptRepository] over the `background_prompts` table of the
 * encrypted database.
 *
 * @property dao The table's data access.
 * @property clock Source of the stored-at time; injectable for tests.
 */
class BackgroundPromptRepositoryImpl internal constructor(
    private val dao: BackgroundPromptDao,
    private val clock: () -> Long,
) : BackgroundPromptRepository {

    /**
     * Creates the repository over the system clock.
     *
     * @param dao The table's data access.
     */
    @Inject
    constructor(dao: BackgroundPromptDao) : this(dao, System::currentTimeMillis)

    override suspend fun store(id: String, prompt: String) {
        dao.upsert(BackgroundPromptEntity(id = id, prompt = prompt, createdAt = clock()))
    }

    override suspend fun get(id: String): String? = dao.promptOf(id)

    override suspend fun delete(id: String) {
        dao.delete(id)
    }

    override suspend fun retainOnly(ids: Set<String>, storedBefore: Long): Int {
        val orphans = dao.idsStoredBefore(storedBefore).filterNot { it in ids }
        // Chunked below SQLite's bound-variable limit.
        orphans.chunked(DELETE_CHUNK).forEach { dao.deleteAll(it) }
        return orphans.size
    }

    override fun observeAll(): Flow<Map<String, String>> =
        dao.observeAll().map { rows -> rows.associate { it.id to it.prompt } }

    private companion object {
        const val DELETE_CHUNK = 500
    }
}
