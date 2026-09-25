package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.services.DownloadedModelFile
import app.knotwork.android.domain.services.DownloadedModelFiles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers which model files on disk the start-up pass registers again: exactly the
 * ones no registry row names by path or by name.
 */
class RediscoverDownloadedModelsUseCaseTest {

    private val files = mockk<DownloadedModelFiles>()
    private val repository = mockk<LocalModelRepository>()
    private val register = mockk<RegisterDownloadedModelUseCase>(relaxed = true)
    private val useCase = RediscoverDownloadedModelsUseCase(files, repository, register)

    @Test
    fun `given an erased registry and models on disk when run then every model is registered again`() = runTest {
        // The state "Erase data" leaves: the table is empty, the files are not.
        coEvery { repository.getAllModels() } returns flowOf(emptyList())
        coEvery { files.list() } returns listOf(
            DownloadedModelFile("gemma-4-E2B-it.litertlm", "/ext/gemma-4-E2B-it.litertlm", 2_000L),
            DownloadedModelFile("custom-model.task", "/ext/custom-model.task", 900L),
        )

        val registered = useCase()

        assertEquals(2, registered)
        coVerify(exactly = 1) { register("gemma-4-E2B-it.litertlm", "/ext/gemma-4-E2B-it.litertlm", 2_000L) }
        coVerify(exactly = 1) { register("custom-model.task", "/ext/custom-model.task", 900L) }
    }

    @Test
    fun `given a file whose row stores its path under another name when run then it is not registered twice`() =
        runTest {
            // A Hugging Face file from a sub-folder: registered as `q4/model.litertlm`,
            // stored flattened as `q4_model.litertlm`.
            coEvery { repository.getAllModels() } returns flowOf(
                listOf(model(name = "q4/model.litertlm", path = "/ext/q4_model.litertlm")),
            )
            coEvery { files.list() } returns listOf(
                DownloadedModelFile("q4_model.litertlm", "/ext/q4_model.litertlm", 1L),
            )

            assertEquals(0, useCase())
            coVerify(exactly = 0) { register(any(), any(), any()) }
        }

    @Test
    fun `given a row with the file's name at an older path when run then the row is left alone`() = runTest {
        coEvery { repository.getAllModels() } returns flowOf(
            listOf(model(name = "gemma.litertlm", path = "/old/gemma.litertlm")),
        )
        coEvery { files.list() } returns listOf(DownloadedModelFile("gemma.litertlm", "/ext/gemma.litertlm", 1L))

        assertEquals(0, useCase())
        coVerify(exactly = 0) { register(any(), any(), any()) }
    }

    @Test
    fun `given no model files on disk when run then nothing is registered`() = runTest {
        coEvery { repository.getAllModels() } returns
            flowOf(listOf(model(name = "a.litertlm", path = "/ext/a.litertlm")))
        coEvery { files.list() } returns emptyList()

        assertEquals(0, useCase())
        coVerify(exactly = 0) { register(any(), any(), any()) }
    }

    private fun model(name: String, path: String) =
        LocalModel(id = 1L, name = name, path = path, size = 1L, isActive = true)
}
