package app.knotwork.android.domain.usecases

import app.knotwork.android.data.local.dao.SkillDao
import app.knotwork.android.data.local.models.SkillEntity
import app.knotwork.android.data.repositories.BundledSkillSource
import app.knotwork.android.data.repositories.SkillRepositoryImpl
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.services.EnglishDefaultNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that bundled-skill seeding is idempotent: running it repeatedly
 * inserts each bundled skill exactly once (upsert by stable id), so an
 * upgrading user who launches many times never accumulates duplicates.
 *
 * Uses a tiny in-memory [SkillDao] so the row count is observable, exercising
 * the real [SkillRepositoryImpl] seed path through [SeedBundledSkillsUseCase].
 */
class SeedBundledSkillsUseCaseTest {

    /** In-memory DAO keyed by id — upsert replaces, so duplicate seeds can't grow the map. */
    private class FakeSkillDao : SkillDao {
        val rows = linkedMapOf<String, SkillEntity>()
        override fun getAll(): Flow<List<SkillEntity>> = flowOf(rows.values.toList())
        override fun getBundled(): Flow<List<SkillEntity>> = flowOf(rows.values.filter { it.isBundled })
        override fun getUser(): Flow<List<SkillEntity>> = flowOf(rows.values.filter { !it.isBundled })
        override suspend fun getById(skillId: String): SkillEntity? = rows[skillId]
        override suspend fun upsert(entity: SkillEntity) {
            rows[entity.id] = entity
        }
        override suspend fun deleteUserById(skillId: String) {
            rows.remove(skillId)
        }
    }

    private fun bundled(id: String) = Skill(
        id = id,
        name = id,
        description = "",
        instruction = "do it",
        toolAllowlist = emptyList(),
        contextConfig = NodeContextConfig.ALL_ENABLED,
        isBundled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `given bundled skills when seeded twice then each exists exactly once`() = runTest {
        val dao = FakeSkillDao()
        val source = object : BundledSkillSource {
            override suspend fun load(): List<Skill> = listOf(bundled("summarizer"), bundled("translator"))
        }
        val useCase = SeedBundledSkillsUseCase(SkillRepositoryImpl(dao, source, EnglishDefaultNames))

        useCase()
        useCase()

        val all = dao.getAll().first()
        assertEquals(2, all.size)
        assertEquals(setOf("summarizer", "translator"), all.map { it.id }.toSet())
    }
}
