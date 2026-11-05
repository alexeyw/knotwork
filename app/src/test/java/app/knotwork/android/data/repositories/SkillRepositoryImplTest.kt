package app.knotwork.android.data.repositories

import app.knotwork.android.data.local.dao.SkillDao
import app.knotwork.android.data.local.models.SkillEntity
import app.knotwork.android.domain.models.NodeContextConfig
import app.knotwork.android.domain.models.Skill
import app.knotwork.android.domain.services.EnglishDefaultNames
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SkillRepositoryImpl]. The tool allowlist round-trips through
 * the nullable CSV column with the null-vs-empty distinction intact, and the
 * read-only bundled contract is enforced on the user mutation paths.
 */
class SkillRepositoryImplTest {

    private val dao: SkillDao = mockk(relaxed = true)
    private val bundledSource: BundledSkillSource = mockk(relaxed = true)
    private val repository = SkillRepositoryImpl(dao, bundledSource, EnglishDefaultNames)

    private fun entity(id: String, toolAllowlistCsv: String?, isBundled: Boolean = false) = SkillEntity(
        id = id,
        name = "Name",
        description = "Desc",
        instruction = "Instruction",
        toolAllowlistCsv = toolAllowlistCsv,
        contextConfig = NodeContextConfig.ALL_ENABLED,
        isBundled = isBundled,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun skill(id: String, toolAllowlist: List<String>?, isBundled: Boolean = false) = Skill(
        id = id,
        name = "Name",
        description = "Desc",
        instruction = "Instruction",
        toolAllowlist = toolAllowlist,
        contextConfig = NodeContextConfig.ALL_ENABLED,
        isBundled = isBundled,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `given a null allowlist column when decoded then it maps to all tools (null)`() = runTest {
        coEvery { dao.getById("a") } returns entity("a", toolAllowlistCsv = null)
        assertNull(repository.getSkillById("a")?.toolAllowlist)
    }

    @Test
    fun `given an empty allowlist column when decoded then it maps to no tools (empty)`() = runTest {
        coEvery { dao.getById("a") } returns entity("a", toolAllowlistCsv = "")
        assertEquals(emptyList<String>(), repository.getSkillById("a")?.toolAllowlist)
    }

    @Test
    fun `given a subset column when decoded then it maps to the listed tools`() = runTest {
        coEvery { dao.getById("a") } returns entity("a", toolAllowlistCsv = "read_file,write_file")
        assertEquals(listOf("read_file", "write_file"), repository.getSkillById("a")?.toolAllowlist)
    }

    @Test
    fun `given a bundled skill when saveUserSkill then it throws`() = runTest {
        try {
            repository.saveUserSkill(skill("a", toolAllowlist = null, isBundled = true))
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("read-only"))
        }
    }

    @Test
    fun `given all-tools skill when saved then the column is null`() = runTest {
        val captured = slot<SkillEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        repository.saveUserSkill(skill("a", toolAllowlist = null))
        assertNull(captured.captured.toolAllowlistCsv)
        assertFalse(captured.captured.isBundled)
    }

    @Test
    fun `given no-tools skill when saved then the column is empty string not null`() = runTest {
        val captured = slot<SkillEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        repository.saveUserSkill(skill("a", toolAllowlist = emptyList()))
        assertEquals("", captured.captured.toolAllowlistCsv)
    }

    @Test
    fun `given an existing createdAt when saved then it is preserved and updatedAt advances`() = runTest {
        val captured = slot<SkillEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit
        repository.saveUserSkill(skill("a", toolAllowlist = null).copy(createdAt = 4_242L))
        assertEquals(4_242L, captured.captured.createdAt)
        assertTrue(captured.captured.updatedAt >= captured.captured.createdAt)
    }

    @Test
    fun `given a delete request when deleteUserSkill then it delegates to the guarded dao query`() = runTest {
        repository.deleteUserSkill("a")
        coVerify { dao.deleteUserById("a") }
    }

    @Test
    fun `given a source skill when duplicated then a new editable copy is persisted`() = runTest {
        coEvery { dao.getById("src") } returns entity("src", toolAllowlistCsv = "read_file", isBundled = true)
        val captured = slot<SkillEntity>()
        coEvery { dao.upsert(capture(captured)) } returns Unit

        val copy = repository.duplicateSkill("src")

        assertTrue(copy!!.name.endsWith("(copy)"))
        assertFalse(copy.isBundled)
        assertTrue(copy.id != "src")
        assertEquals(listOf("read_file"), copy.toolAllowlist)
        assertEquals(copy.id, captured.captured.id)
    }

    @Test
    fun `given a missing source when duplicated then it returns null`() = runTest {
        coEvery { dao.getById("missing") } returns null
        assertNull(repository.duplicateSkill("missing"))
    }

    @Test
    fun `given bundled assets when seeded then each is upserted with the bundled flag`() = runTest {
        coEvery { bundledSource.load() } returns listOf(
            skill("summarizer", toolAllowlist = emptyList(), isBundled = true),
            skill("translator", toolAllowlist = emptyList(), isBundled = true),
        )
        repository.seedBundledSkills()
        coVerify(exactly = 1) { dao.upsert(match { it.id == "summarizer" && it.isBundled }) }
        coVerify(exactly = 1) { dao.upsert(match { it.id == "translator" && it.isBundled }) }
    }

    @Test
    fun `given user rows when observed then they are mapped to domain skills`() = runTest {
        coEvery { dao.getUser() } returns flowOf(listOf(entity("a", toolAllowlistCsv = null)))
        val skills = repository.getUserSkills().first()
        assertEquals(1, skills.size)
        assertEquals("a", skills.first().id)
    }
}
