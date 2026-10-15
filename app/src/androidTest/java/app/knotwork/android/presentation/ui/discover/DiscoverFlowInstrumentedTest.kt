package app.knotwork.android.presentation.ui.discover

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.knotwork.design.screens.discover.DiscoverCallbacks
import app.knotwork.design.screens.discover.DiscoverContent
import app.knotwork.design.screens.discover.DiscoverDetailCallbacks
import app.knotwork.design.screens.discover.DiscoverDetailContent
import app.knotwork.design.screens.discover.DiscoverDetailViewState
import app.knotwork.design.screens.discover.DiscoverDetailVisualState
import app.knotwork.design.screens.discover.DiscoverFileRow
import app.knotwork.design.screens.discover.DiscoverFileStatus
import app.knotwork.design.screens.discover.DiscoverViewState
import app.knotwork.design.screens.discover.DiscoverVisualState
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the model-discovery flow: the list surface (network
 * error / empty degradations) and the detail surface up to and including the
 * licence-confirmation dialog — the gate the task requires ("Discover flow up
 * to licence confirmation"). Both are the stateless catalog content composables
 * driven by synthetic view states.
 */
class DiscoverFlowInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun list_errorState_rendersErrorAndRetryDispatches() {
        var retryCount = 0
        composeTestRule.setContent {
            KnotworkTheme {
                DiscoverContent(
                    state = DiscoverViewState(
                        visualState = DiscoverVisualState.Error,
                        errorMessage = "Network unavailable",
                    ),
                    callbacks = DiscoverCallbacks(onRetry = { retryCount++ }),
                )
            }
        }

        composeTestRule.onNodeWithText(ERROR_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(RETRY).performClick()

        assertEquals("Retry callback should fire once", 1, retryCount)
    }

    @Test
    fun list_emptyState_rendersNoModelsFound() {
        composeTestRule.setContent {
            KnotworkTheme {
                DiscoverContent(
                    state = DiscoverViewState(visualState = DiscoverVisualState.Empty),
                )
            }
        }

        composeTestRule.onNodeWithText(EMPTY_TITLE).assertIsDisplayed()
    }

    @Test
    fun detail_loadedFile_installClickDispatches() {
        var installedFile: String? = null
        composeTestRule.setContent {
            KnotworkTheme {
                DiscoverDetailContent(
                    state = loadedDetail(),
                    callbacks = DiscoverDetailCallbacks(onInstallClick = { installedFile = it }),
                )
            }
        }

        composeTestRule.onNodeWithText(INSTALL).performClick()

        assertEquals("Install should fire for the listed file", FILE_NAME, installedFile)
    }

    @Test
    fun detail_pendingLicense_showsConfirmDialog_andAcceptDispatches() {
        var confirmedFile: String? = null
        composeTestRule.setContent {
            KnotworkTheme {
                DiscoverDetailContent(
                    state = loadedDetail().copy(pendingLicenseFileName = FILE_NAME),
                    callbacks = DiscoverDetailCallbacks(onLicenseConfirm = { confirmedFile = it }),
                )
            }
        }

        composeTestRule.onNodeWithText(LICENSE_TITLE).assertIsDisplayed()
        composeTestRule.onNodeWithText(LICENSE_ACCEPT).performClick()

        assertEquals("Licence confirm should fire for the pending file", FILE_NAME, confirmedFile)
    }

    private fun loadedDetail(): DiscoverDetailViewState = DiscoverDetailViewState(
        visualState = DiscoverDetailVisualState.Loaded,
        title = "Gemma 3n",
        repoId = "litert-community/Gemma-3n",
        metaLine = "↓ 12.3k · ♥ 45",
        license = "apache-2.0",
        files = listOf(
            DiscoverFileRow(fileName = FILE_NAME, sizeLabel = "2.4 GB", status = DiscoverFileStatus.Idle),
        ),
    )

    private companion object {
        // Mirrors the catalog string resources (knotwork_discover_*).
        const val ERROR_TITLE: String = "Couldn't load models"
        const val RETRY: String = "Retry"
        const val EMPTY_TITLE: String = "No models found"
        const val INSTALL: String = "Install"
        const val LICENSE_TITLE: String = "Review the licence"
        const val LICENSE_ACCEPT: String = "Accept & install"
        const val FILE_NAME: String = "gemma-3n-it.litertlm"
    }
}
