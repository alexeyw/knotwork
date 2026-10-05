package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileHash
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.services.ModelFileHasher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ComputeModelFileHashesUseCase]: one pass hashes every file the registry lists
 * as needing it, stores each result, and leaves an unreadable file for the next pass.
 */
class ComputeModelFileHashesUseCaseTest {

    private val repository = mockk<LocalModelRepository>(relaxed = true)
    private val hasher = mockk<ModelFileHasher>()
    private val useCase = ComputeModelFileHashesUseCase(repository, hasher)

    @Test
    fun `given models without a current hash when run then each readable file is hashed and stored`() = runTest {
        val a = model(id = 1L, path = "/m/a")
        val b = model(id = 2L, path = "/m/b")
        val hashA = ModelFileHash("aa", fileSizeBytes = 1L, fileModifiedAtMs = 10L)
        val hashB = ModelFileHash("bb", fileSizeBytes = 2L, fileModifiedAtMs = 20L)
        coEvery { repository.modelsNeedingFileHash() } returns listOf(a, b)
        coEvery { hasher.hash("/m/a") } returns hashA
        coEvery { hasher.hash("/m/b") } returns hashB

        val hashed = useCase()

        assertEquals(2, hashed)
        coVerify(exactly = 1) { repository.recordFileHash(1L, hashA) }
        coVerify(exactly = 1) { repository.recordFileHash(2L, hashB) }
    }

    @Test
    fun `given a file the hasher cannot read when run then it is skipped and the rest are hashed`() = runTest {
        // Missing, unreadable or changing mid-read: left unhashed for the next pass.
        val broken = model(id = 1L, path = "/m/broken")
        val good = model(id = 2L, path = "/m/good")
        val hash = ModelFileHash("cc", fileSizeBytes = 3L, fileModifiedAtMs = 30L)
        coEvery { repository.modelsNeedingFileHash() } returns listOf(broken, good)
        coEvery { hasher.hash("/m/broken") } returns null
        coEvery { hasher.hash("/m/good") } returns hash

        val hashed = useCase()

        assertEquals(1, hashed)
        coVerify(exactly = 0) { repository.recordFileHash(1L, any()) }
        coVerify(exactly = 1) { repository.recordFileHash(2L, hash) }
    }

    @Test
    fun `given every file already hashed when run then nothing is read`() = runTest {
        coEvery { repository.modelsNeedingFileHash() } returns emptyList()

        assertEquals(0, useCase())
        coVerify(exactly = 0) { hasher.hash(any()) }
    }

    private fun model(id: Long, path: String) =
        LocalModel(id = id, name = path.substringAfterLast('/'), path = path, size = 1L, isActive = false)
}
