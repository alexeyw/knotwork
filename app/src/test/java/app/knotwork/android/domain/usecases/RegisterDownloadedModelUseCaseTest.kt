package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.repositories.LocalModelRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the upsert semantics of registering a downloaded file: the write runs
 * on every completed download, including re-downloads of a file the user
 * already has, so "insert once, refresh afterwards" is the whole contract.
 */
class RegisterDownloadedModelUseCaseTest {

    private val localModelRepository = mockk<LocalModelRepository>()
    private val useCase = RegisterDownloadedModelUseCase(localModelRepository)

    @Test
    fun `given no existing row when registering then a fresh inactive model is inserted`() = runTest {
        coEvery { localModelRepository.findByFileName("gemma.litertlm") } returns null
        val inserted = slot<LocalModel>()
        coEvery { localModelRepository.insertModel(capture(inserted)) } returns 7L

        val id = useCase("gemma.litertlm", "/data/gemma.litertlm", sizeBytes = 2_048L)

        assertEquals(7L, id)
        assertEquals("gemma.litertlm", inserted.captured.name)
        assertEquals("/data/gemma.litertlm", inserted.captured.path)
        assertEquals(2_048L, inserted.captured.size)
        // Installing a model is not choosing it.
        assertEquals(false, inserted.captured.isActive)
    }

    @Test
    fun `given an existing row when registering then it is refreshed instead of duplicated`() = runTest {
        val existing = LocalModel(id = 9L, name = "gemma.litertlm", path = "/old/path", size = 0L, isActive = true)
        coEvery { localModelRepository.findByFileName("gemma.litertlm") } returns existing
        val updated = slot<LocalModel>()
        coEvery { localModelRepository.updateModel(capture(updated)) } returns Unit

        val id = useCase("gemma.litertlm", "/data/gemma.litertlm", sizeBytes = 2_048L)

        // `local_models` has no unique index on the name, so a blind insert
        // would leave the user with two rows for one file.
        coVerify(exactly = 0) { localModelRepository.insertModel(any()) }
        assertEquals(9L, id)
        assertEquals("/data/gemma.litertlm", updated.captured.path)
        assertEquals(2_048L, updated.captured.size)
        // An active model that gets re-downloaded stays active.
        assertEquals(true, updated.captured.isActive)
    }

    @Test
    fun `given two registrations of one file at once when both run then one row is inserted`() = runTest {
        // A download finishing while the start-up rediscovery registers the same
        // file: each looked up, found nothing, and inserted.
        val rows = mutableListOf<LocalModel>()
        coEvery { localModelRepository.findByFileName(any()) } coAnswers {
            // Read, then suspend as a database call does before its result is used.
            val found = rows.firstOrNull { it.name == firstArg<String>() }
            yield()
            found
        }
        coEvery { localModelRepository.insertModel(any()) } coAnswers {
            rows += firstArg<LocalModel>()
            rows.size.toLong()
        }
        coEvery { localModelRepository.updateModel(any()) } returns Unit

        listOf(
            async { useCase("gemma.litertlm", "/data/gemma.litertlm", sizeBytes = 2_048L) },
            async { useCase("gemma.litertlm", "/data/gemma.litertlm", sizeBytes = 2_048L) },
        ).awaitAll()

        assertEquals(1, rows.size)
    }
}
