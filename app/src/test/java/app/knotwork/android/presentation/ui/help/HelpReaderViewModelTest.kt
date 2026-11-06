package app.knotwork.android.presentation.ui.help

import androidx.lifecycle.SavedStateHandle
import app.knotwork.android.domain.models.BundledDocument
import app.knotwork.android.domain.models.NetworkState
import app.knotwork.android.domain.repositories.BundledDocumentationRepository
import app.knotwork.android.domain.repositories.NetworkStateRepository
import app.knotwork.android.domain.usecases.ResolveDocumentationLinkUseCase
import app.knotwork.android.presentation.ui.navigation.NavRoutes
import app.knotwork.android.presentation.ui.settings.FakeAppLanguageRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [HelpReaderViewModel] and the interface language: the document is read in the
 * language the interface is shown in, and again when that language changes —
 * the ViewModel outlives the screen's re-creation in the new language.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelpReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val language = FakeAppLanguageRepository()
    private val texts = mapOf("en" to "# FAQ\n", "ru" to "# Вопросы\n")

    private val repository = mockk<BundledDocumentationRepository> {
        coEvery { documents() } answers { Result.success(listOf(faq())) }
        coEvery { content("faq") } answers { Result.success(texts.getValue(language.interfaceLanguage.value)) }
    }

    private val network = mockk<NetworkStateRepository> {
        every { networkState } returns MutableStateFlow(NetworkState())
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = HelpReaderViewModel(
        repository = repository,
        resolveLink = ResolveDocumentationLinkUseCase(),
        networkState = network,
        appLanguage = language,
        savedStateHandle = SavedStateHandle(mapOf(NavRoutes.HELP_DOCUMENT_ID_ARG to "faq")),
    )

    @Test
    fun `given a document when the reader opens then it shows the text in the interface language`() =
        runTest(dispatcher) {
            language.showIn("ru")

            val viewModel = viewModel()
            advanceUntilIdle()

            assertEquals("# Вопросы\n", viewModel.uiState.value.markdown)
        }

    @Test
    fun `when the interface language changes then the open document is read again in it`() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        assertEquals("# FAQ\n", viewModel.uiState.value.markdown)

        language.showIn("ru")
        advanceUntilIdle()

        assertEquals("# Вопросы\n", viewModel.uiState.value.markdown)
    }

    private fun faq() = BundledDocument(
        id = "faq",
        path = "docs/faq.md",
        assetPath = "docs/faq.md",
        anchors = emptyMap(),
        links = emptyMap(),
    )
}
