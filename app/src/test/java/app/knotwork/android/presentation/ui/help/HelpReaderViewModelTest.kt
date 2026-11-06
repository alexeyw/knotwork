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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * [HelpReaderViewModel] and the interface language: the document is read in the
 * language the interface is shown in, and again when that language changes —
 * the ViewModel outlives the screen's re-creation in the new language — without
 * jumping back to the anchor it was opened at, and without an older read
 * landing after a newer one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HelpReaderViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val language = FakeAppLanguageRepository()
    private val texts = mapOf("en" to "# FAQ\n", "ru" to "# Вопросы\n")

    /** How long the English text takes to read; a test makes it slow to race a newer read. */
    private var englishReadMs = 0L

    private val repository = mockk<BundledDocumentationRepository> {
        coEvery { documents() } answers { Result.success(listOf(faq())) }
        coEvery { content("faq") } coAnswers {
            val tag = language.interfaceLanguage.value
            if (tag == "en") delay(englishReadMs)
            Result.success(texts.getValue(tag))
        }
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

    private fun viewModel(anchor: String? = null) = HelpReaderViewModel(
        repository = repository,
        resolveLink = ResolveDocumentationLinkUseCase(),
        networkState = network,
        appLanguage = language,
        savedStateHandle = SavedStateHandle(
            mapOf(NavRoutes.HELP_DOCUMENT_ID_ARG to "faq", NavRoutes.HELP_DOCUMENT_ANCHOR_ARG to anchor),
        ),
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

    @Test
    fun `given a reader opened at an anchor when the language changes then it does not jump back to it`() =
        runTest(dispatcher) {
            val viewModel = viewModel(anchor = "faq")
            advanceUntilIdle()
            assertEquals(0, viewModel.uiState.value.anchorOffset)
            viewModel.onAnchorConsumed()
            viewModel.onScrolled()

            language.showIn("ru")
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals("# Вопросы\n", state.markdown)
            assertNull(state.anchorOffset)
            assertFalse(state.anchorMarked)
            assertFalse(state.loading)
        }

    @Test
    fun `given a read still running when the language changes then the newer language's text is the one shown`() =
        runTest(dispatcher) {
            englishReadMs = 1_000
            val viewModel = viewModel()
            runCurrent()

            language.showIn("ru")
            advanceUntilIdle()

            assertEquals("# Вопросы\n", viewModel.uiState.value.markdown)
        }

    private fun faq() = BundledDocument(
        id = "faq",
        path = "docs/faq.md",
        assetPath = "docs/faq.md",
        anchors = mapOf("faq" to 0),
        links = emptyMap(),
    )
}
