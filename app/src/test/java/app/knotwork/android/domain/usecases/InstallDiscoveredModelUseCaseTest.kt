package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.models.DiscoverableModelFile
import app.knotwork.android.domain.models.DownloadState
import app.knotwork.android.domain.repositories.ModelDownloadManager
import app.knotwork.android.domain.repositories.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [InstallDiscoveredModelUseCase]. */
class InstallDiscoveredModelUseCaseTest {

    private val downloadManager = mockk<ModelDownloadManager>()
    private val registerDownloadedModel = mockk<RegisterDownloadedModelUseCase>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val useCase = InstallDiscoveredModelUseCase(
        downloadManager,
        registerDownloadedModel,
        settingsRepository,
    )

    private val file = DiscoverableModelFile(
        fileName = "gemma.litertlm",
        sizeBytes = 2_048L,
        resolveUrl = "https://huggingface.co/litert-community/gemma/resolve/main/gemma.litertlm",
        isInstalled = false,
    )

    @Test
    fun `given success when invoked then the row is refreshed with the hub-reported size`() = runTest {
        every { settingsRepository.huggingFaceAuthToken } returns flowOf("hf_token")
        every { downloadManager.downloadModel(any(), any(), any()) } returns flowOf(
            DownloadState.Pending,
            DownloadState.Downloading(50),
            DownloadState.Success("/data/gemma.litertlm"),
        )
        coEvery { registerDownloadedModel(any(), any(), any()) } returns 7L

        val states = useCase(file).toList()

        assertTrue(states.last() is DownloadState.Success)
        // The worker already registered the file by its on-disk length; the Hub
        // figure is the authoritative one, so it wins where it is known.
        coVerify {
            registerDownloadedModel(
                fileName = file.fileName,
                path = "/data/gemma.litertlm",
                sizeBytes = 2_048L,
            )
        }
    }

    @Test
    fun `given a stored token when invoked then the download is asked to authenticate`() = runTest {
        every { settingsRepository.huggingFaceAuthToken } returns flowOf("hf_token")
        every { downloadManager.downloadModel(any(), any(), any()) } returns flowOf(DownloadState.Pending)

        useCase(file).first()

        // The token value itself stays in the encrypted store — only the
        // decision to use it travels.
        coVerify { downloadManager.downloadModel(any(), any(), useStoredAuth = true) }
    }

    @Test
    fun `given no stored token when invoked then the download stays anonymous`() = runTest {
        every { settingsRepository.huggingFaceAuthToken } returns flowOf(null)
        every { downloadManager.downloadModel(any(), any(), any()) } returns flowOf(DownloadState.Pending)

        useCase(file).first()

        coVerify { downloadManager.downloadModel(any(), any(), useStoredAuth = false) }
    }

    @Test
    fun `given error when invoked then nothing is registered`() = runTest {
        every { settingsRepository.huggingFaceAuthToken } returns flowOf(null)
        every { downloadManager.downloadModel(any(), any(), any()) } returns flowOf(
            DownloadState.Pending,
            DownloadState.Error(mockk(relaxed = true)),
        )

        val states = useCase(file).toList()

        assertTrue(states.last() is DownloadState.Error)
        coVerify(exactly = 0) { registerDownloadedModel(any(), any(), any()) }
    }

    @Test
    fun `given a running download when attached then it is followed and sized, and none is started`() = runTest {
        every { downloadManager.observeDownload(file.fileName) } returns flowOf(
            DownloadState.Downloading(70),
            DownloadState.Success("/data/models/${file.fileName}"),
        )
        coEvery { registerDownloadedModel(any(), any(), any()) } returns 1L

        val states = useCase.attach(file).toList()

        assertEquals(DownloadState.Downloading(70), states.first())
        assertTrue(states.last() is DownloadState.Success)
        coVerify { registerDownloadedModel(file.fileName, "/data/models/${file.fileName}", file.sizeBytes) }
        coVerify(exactly = 0) { downloadManager.downloadModel(any(), any(), any()) }
    }

    @Test
    fun `given nothing running when attached then the stream is empty`() = runTest {
        every { downloadManager.observeDownload(file.fileName) } returns emptyFlow()

        assertEquals(emptyList<DownloadState>(), useCase.attach(file).toList())
    }
}
