package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.services.AgentWorkspace
import app.knotwork.android.domain.services.AttachmentStore
import app.knotwork.android.domain.services.DatabaseResetService
import app.knotwork.android.domain.services.TransientCacheSweeper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ResetLockedDatabaseUseCase]: the recovery wipe erases the database
 * first, then the plain-text content that belongs to it — workspace, attachments,
 * transient copies — and touches no file when the database survives.
 */
class ResetLockedDatabaseUseCaseTest {

    private val databaseResetService = mockk<DatabaseResetService>()
    private val workspace = mockk<AgentWorkspace>()
    private val attachments = mockk<AttachmentStore>()
    private val caches = mockk<TransientCacheSweeper>()

    private val useCase = ResetLockedDatabaseUseCase(databaseResetService, workspace, attachments, caches)

    @Before
    fun setup() {
        coEvery { databaseResetService.wipeAllData() } just runs
        coEvery { workspace.eraseAll() } returns true
        coEvery { attachments.deleteAll() } returns true
        coEvery { caches.sweepAll() } returns true
    }

    @Test
    fun `given a confirmed wipe when invoked then the database goes first and every file store after it`() = runTest {
        assertTrue(useCase())

        coVerifyOrder {
            databaseResetService.wipeAllData()
            workspace.eraseAll()
            attachments.deleteAll()
            caches.sweepAll()
        }
    }

    @Test
    fun `given the database survives when invoked then the failure propagates and no file is touched`() = runTest {
        val failure = IllegalStateException("database file survived")
        coEvery { databaseResetService.wipeAllData() } throws failure

        try {
            useCase()
            fail("a surviving database must fail the wipe")
        } catch (e: IllegalStateException) {
            assertEquals(failure, e)
        }

        coVerify(exactly = 0) { workspace.eraseAll() }
        coVerify(exactly = 0) { attachments.deleteAll() }
        coVerify(exactly = 0) { caches.sweepAll() }
    }

    @Test
    fun `given one file store keeps something when invoked then the others still run and the result says so`() =
        runTest {
            coEvery { workspace.eraseAll() } returns false

            assertFalse(useCase())

            coVerify(exactly = 1) { attachments.deleteAll() }
            coVerify(exactly = 1) { caches.sweepAll() }
        }
}
