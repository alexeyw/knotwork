package app.knotwork.android.presentation.ui.prompts

import app.knotwork.android.R
import app.knotwork.android.domain.models.InputProblem
import app.knotwork.android.domain.models.InvalidInputException
import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPackCandidate
import app.knotwork.android.domain.models.PromptPackParseError
import app.knotwork.android.domain.models.PromptPreset
import app.knotwork.android.domain.prompt.PromptSegment
import app.knotwork.android.domain.prompt.PromptTemplateEngine
import app.knotwork.android.domain.prompt.PromptVariableProvider
import app.knotwork.android.domain.repositories.PromptPresetRepository
import app.knotwork.android.domain.services.EnglishDefaultNames
import app.knotwork.android.domain.usecases.SavePromptAsPresetUseCase
import app.knotwork.android.domain.usecases.promptpack.ExportPromptPackUseCase
import app.knotwork.android.domain.usecases.promptpack.ImportPromptPackUseCase
import app.knotwork.android.domain.usecases.promptpack.PromptPackCollisionChoice
import app.knotwork.android.domain.usecases.promptpack.ResolvePromptPackCollisionUseCase
import app.knotwork.android.presentation.ui.common.UiText
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PromptLibraryViewModelTest {

    private lateinit var promptPresetRepository: PromptPresetRepository
    private lateinit var savePromptAsPresetUseCase: SavePromptAsPresetUseCase
    private lateinit var importPromptPackUseCase: ImportPromptPackUseCase
    private lateinit var resolvePromptPackCollisionUseCase: ResolvePromptPackCollisionUseCase
    private lateinit var exportPromptPackUseCase: ExportPromptPackUseCase
    private lateinit var promptTemplateEngine: PromptTemplateEngine
    private lateinit var providerDate: PromptVariableProvider
    private lateinit var providerTime: PromptVariableProvider
    private lateinit var viewModel: PromptLibraryViewModel

    private val testDispatcher = StandardTestDispatcher()

    private val bundledA = PromptPreset(
        id = "bundled-a",
        name = "Concise assistant",
        description = "Short answers.",
        nodeType = NodeType.LITE_RT,
        systemPrompt = "You are concise.",
        tags = listOf("concise"),
        isBundled = true,
    )
    private val userA = PromptPreset(
        id = "user-a",
        name = "My LiteRt preset",
        description = "",
        nodeType = NodeType.LITE_RT,
        systemPrompt = "You are friendly.",
        isBundled = false,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        promptPresetRepository = mockk(relaxed = true) {
            every { getBundledPresets() } returns flowOf(listOf(bundledA))
            every { getUserPresets() } returns flowOf(listOf(userA))
        }
        savePromptAsPresetUseCase = mockk()
        promptTemplateEngine = mockk()
        providerDate = mockk {
            every { key() } returns "DATE"
            coEvery { resolve() } returns "01 May 2026"
        }
        providerTime = mockk {
            every { key() } returns "TIME"
            coEvery { resolve() } returns "15:30"
        }

        importPromptPackUseCase = ImportPromptPackUseCase(promptPresetRepository)
        resolvePromptPackCollisionUseCase = ResolvePromptPackCollisionUseCase(promptPresetRepository)
        exportPromptPackUseCase = ExportPromptPackUseCase(promptPresetRepository)

        viewModel = PromptLibraryViewModel(
            promptPresetRepository,
            savePromptAsPresetUseCase,
            promptTemplateEngine,
            setOf(providerDate, providerTime),
            importPromptPackUseCase,
            resolvePromptPackCollisionUseCase,
            exportPromptPackUseCase,
            EnglishDefaultNames,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads bundled and user presets`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(false, state.isLoading)
        assertEquals(listOf(bundledA), state.bundledPresets)
        assertEquals(listOf(userA), state.userPresets)
    }

    @Test
    fun `deletePrompt forwards user-preset deletions to repository`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deletePrompt(userA.id)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { promptPresetRepository.deleteUserPreset(userA.id) }
    }

    @Test
    fun `deletePrompt refuses to delete a bundled preset`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.deletePrompt(bundledA.id)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { promptPresetRepository.deleteUserPreset(any()) }
    }

    @Test
    fun `availableVariables are derived from injected providers and sorted`() {
        val state = viewModel.uiState.value
        assertEquals(listOf("\$DATE", "\$TIME"), state.availableVariables)
    }

    @Test
    fun `requestPromptPreview transitions to Ready with engine segments`() = runTest {
        val template = "Hi \$DATE"
        val segments = listOf(
            PromptSegment.Literal("Hi "),
            PromptSegment.Resolved("DATE", "01 May 2026"),
        )
        coEvery { promptTemplateEngine.renderSegments(template, any()) } returns segments

        viewModel.requestPromptPreview(template)
        testDispatcher.scheduler.advanceUntilIdle()

        val readyState = viewModel.uiState.value.previewState
        assertTrue(readyState is PromptPreviewState.Ready)
        assertEquals(segments, (readyState as PromptPreviewState.Ready).segments)
        coVerify { promptTemplateEngine.renderSegments(template, any()) }
    }

    @Test
    fun `dismissPromptPreview resets state to Hidden`() = runTest {
        coEvery { promptTemplateEngine.renderSegments(any(), any()) } returns emptyList()
        viewModel.requestPromptPreview("anything")
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.dismissPromptPreview()

        assertEquals(PromptPreviewState.Hidden, viewModel.uiState.value.previewState)
    }

    @Test
    fun `saveEditor passes existingId for an in-place update`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery {
            savePromptAsPresetUseCase(
                systemPrompt = any(),
                name = any(),
                description = any(),
                nodeType = any(),
                tags = any(),
                existingId = any(),
            )
        } returns Result.success(userA.id)

        viewModel.openEditor(promptId = userA.id)
        viewModel.onEditorBodyChange("Updated body.")
        viewModel.saveEditor()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            savePromptAsPresetUseCase(
                systemPrompt = "Updated body.",
                name = userA.name,
                description = userA.description,
                nodeType = NodeType.LITE_RT,
                tags = userA.tags,
                existingId = userA.id,
            )
        }
    }

    @Test
    fun `given a name the save refuses when saved then the editor shows the refusal's words`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        coEvery {
            savePromptAsPresetUseCase(
                systemPrompt = any(),
                name = any(),
                description = any(),
                nodeType = any(),
                tags = any(),
                existingId = any(),
            )
        } returns Result.failure(InvalidInputException(InputProblem.BlankName(InputProblem.Subject.PRESET)))

        viewModel.openEditor(promptId = userA.id)
        viewModel.saveEditor()
        testDispatcher.scheduler.advanceUntilIdle()

        // The words of the resource, not the problem's description.
        assertEquals(UiText(R.string.input_problem_blank_preset_name), viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `saveEditor refuses invalid category`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.openEditor(promptId = null)
        viewModel.onEditorCategoryChange("TOOL") // not LLM-driven
        viewModel.onEditorBodyChange("body")
        viewModel.saveEditor()
        testDispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.errorMessage)
        coVerify(exactly = 0) {
            savePromptAsPresetUseCase(
                systemPrompt = any(),
                name = any(),
                description = any(),
                nodeType = any(),
                tags = any(),
                existingId = any(),
            )
        }
    }

    // --- Prompt import / export ------------------------------------------

    private val cleanDocument = """
        ---
        name: Standup digest
        nodeType: OUTPUT
        ---

        Three bullets.
    """.trimIndent()

    @Test
    fun `given a clean file when imported then a snackbar names the prompt and its category`() = runTest {
        coEvery { promptPresetRepository.getPresetById(any()) } returns null

        viewModel.importPromptFile(document = cleanDocument, fileName = "standup-digest.md")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.importDialog)
        assertEquals("OUTPUT", state.snackbar?.showCategory)
    }

    @Test
    fun `given a file asking for tools when imported then the refusal is reported instead of a snackbar`() = runTest {
        coEvery { promptPresetRepository.getPresetById(any()) } returns null
        val greedy = """
                ---
                name: Web summarizer
                nodeType: CLOUD
                allowed-tools: web_search
                ---

                Summarize the page.
        """.trimIndent()

        viewModel.importPromptFile(document = greedy, fileName = "web.md")
        testDispatcher.scheduler.advanceUntilIdle()

        val dialog = viewModel.uiState.value.importDialog as PromptImportDialog.Reported
        assertTrue(dialog.notes.hasRefusal)
        // The dialog is the disclosure *and* the confirmation — its first
        // sentence says the prompt is in the library — so no snackbar is
        // raised behind it, where its action could not be reached anyway.
        assertNull(viewModel.uiState.value.snackbar)
        // And the prompt really did land: a refusal is not a failed import.
        coVerify { promptPresetRepository.saveUserPreset(any()) }
    }

    @Test
    fun `given an unreadable file when imported then a failure dialog names the cause`() = runTest {
        viewModel.importPromptFile(document = "not a prompt pack", fileName = "x.md")
        testDispatcher.scheduler.advanceUntilIdle()

        val dialog = viewModel.uiState.value.importDialog as PromptImportDialog.Failed
        assertTrue(dialog.cause is PromptPackParseError.MalformedFrontmatter)
        assertNull(viewModel.uiState.value.snackbar)
    }

    @Test
    fun `given a colliding file when imported then the decision is asked before anything is written`() = runTest {
        coEvery { promptPresetRepository.getPresetById("standup-digest") } returns PromptPreset(
            id = "standup-digest",
            name = "Standup digest",
            description = "",
            nodeType = NodeType.OUTPUT,
            systemPrompt = "An edit made in the app.",
            isBundled = false,
        )

        viewModel.importPromptFile(document = cleanDocument, fileName = "standup-digest.md")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.importDialog is PromptImportDialog.Collision)
        coVerify(exactly = 0) { promptPresetRepository.saveUserPreset(any()) }
    }

    @Test
    fun `given a collision when keep both is chosen then the dialog closes and the prompt is written`() = runTest {
        val candidate = PromptPackCandidate(
            id = "standup-digest",
            name = "Standup digest",
            description = "",
            nodeType = NodeType.OUTPUT,
            systemPrompt = "Three bullets.",
        )

        viewModel.resolveImportCollision(
            candidate = candidate,
            choice = PromptPackCollisionChoice.KEEP_BOTH,
            notes = null,
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.importDialog)
        coVerify { promptPresetRepository.saveUserPreset(any()) }
    }

    @Test
    fun `given an export request when the prompt resolves then it is parked for the picker and then consumed`() =
        runTest {
            coEvery { promptPresetRepository.getPresetById(userA.id) } returns userA

            viewModel.requestExport(userA.id)
            testDispatcher.scheduler.advanceUntilIdle()

            assertEquals(
                "${userA.name.lowercase().replace(" ", "-")}.md",
                viewModel.uiState.value.pendingExport?.fileName,
            )

            // Consumed on launch so a recomposition cannot fire the picker
            // twice for one tap.
            viewModel.consumePendingExport()
            assertNull(viewModel.uiState.value.pendingExport)
        }
}
