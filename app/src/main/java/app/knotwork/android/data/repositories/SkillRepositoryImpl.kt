package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.TagsCsv
import app.knotwork.android.data.local.dao.SkillDao
import app.knotwork.android.data.local.models.SkillEntity
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.repositories.SkillRepository
import app.knotwork.android.domain.services.DefaultNames
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

/**
 * Room-backed [SkillRepository]. Both bundled and user skills live in the
 * `skills` table; the bundled set is materialised on launch by
 * [seedBundledSkills], which upserts each bundled skill by its stable id (so
 * repeated launches never duplicate it and never touch user rows).
 *
 * The tool restriction round-trips through [SkillEntity.toolAllowlistCsv]
 * with the null-vs-empty distinction preserved:
 * - domain `null` (all tools) ↔ column `null`,
 * - domain `[]` (no tools) ↔ column `""`,
 * - domain `[a, b]` ↔ column `"a,b"`.
 *
 * A duplicate is named with [DefaultNames.copyOf], in the interface language.
 */
class SkillRepositoryImpl @Inject constructor(
    private val dao: SkillDao,
    private val bundledSkillSource: BundledSkillSource,
    private val defaultNames: DefaultNames,
) : SkillRepository {

    override fun getBundledSkills(): Flow<List<Skill>> =
        dao.getBundled().map { rows -> rows.map { it.toDomainModel() } }

    override fun getUserSkills(): Flow<List<Skill>> = dao.getUser().map { rows -> rows.map { it.toDomainModel() } }

    override fun getAllSkills(): Flow<List<Skill>> = dao.getAll().map { rows -> rows.map { it.toDomainModel() } }

    override suspend fun getSkillById(id: String): Skill? = dao.getById(id)?.toDomainModel()

    override suspend fun saveUserSkill(skill: Skill) {
        require(!skill.isBundled) { "Bundled skills are read-only and cannot be saved as user skills" }
        val now = System.currentTimeMillis()
        // Preserve createdAt on edit (the caller passes the loaded value); stamp
        // it on first save (createdAt == 0). updatedAt always advances.
        val createdAt = if (skill.createdAt > 0L) skill.createdAt else now
        dao.upsert(skill.copy(createdAt = createdAt, updatedAt = now, isBundled = false).toEntity())
    }

    override suspend fun deleteUserSkill(id: String) {
        // The DAO query carries an `isBundled = 0` guard, so this is a no-op for
        // a bundled id even if one slips through.
        dao.deleteUserById(id)
    }

    override suspend fun duplicateSkill(sourceId: String): Skill? {
        val source = dao.getById(sourceId)?.toDomainModel() ?: return null
        val now = System.currentTimeMillis()
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = defaultNames.copyOf(source.name),
            isBundled = false,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(copy.toEntity())
        return copy
    }

    override suspend fun seedBundledSkills() {
        bundledSkillSource.load().forEach { skill ->
            // Upsert by stable id ⇒ idempotent across launches; force the
            // bundled flag regardless of the asset's value.
            dao.upsert(skill.copy(isBundled = true).toEntity())
        }
    }

    /** Maps a domain [Skill] to its Room row, encoding the tool allowlist. */
    private fun Skill.toEntity(): SkillEntity = SkillEntity(
        id = id,
        name = name,
        description = description,
        instruction = instruction,
        toolAllowlistCsv = toolAllowlist?.let { TagsCsv.encode(it) },
        contextConfig = contextConfig,
        isBundled = isBundled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** Maps a Room row to the domain [Skill], decoding the tool allowlist. */
    private fun SkillEntity.toDomainModel(): Skill = Skill(
        id = id,
        name = name,
        description = description,
        instruction = instruction,
        toolAllowlist = toolAllowlistCsv?.let { TagsCsv.decode(it) },
        contextConfig = contextConfig,
        isBundled = isBundled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
