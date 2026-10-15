package app.knotwork.android.presentation.ui.chat.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.knotwork.design.components.chat.ImageViewer
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented UI tests for the full-screen [ImageViewer] opened when an
 * attachment thumbnail is tapped: header metadata, the close / share actions,
 * and the missing-file fallback.
 */
class ImageViewerInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun viewer_rendersFileNameAndDimensions_andCloseShareDispatchCallbacks() {
        var dismissCount = 0
        var shareCount = 0
        composeTestRule.setContent {
            KnotworkTheme {
                ImageViewer(
                    model = "/abs/photo.jpg",
                    fileName = FILE_NAME,
                    dimensionsLabel = DIMENSIONS,
                    isMissing = false,
                    onDismiss = { dismissCount++ },
                    onShare = { shareCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(FILE_NAME).assertIsDisplayed()
        composeTestRule.onNodeWithText(DIMENSIONS).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(SHARE_LABEL).performClick()
        composeTestRule.onNodeWithContentDescription(CLOSE_LABEL).performClick()

        assertEquals("Share callback should fire once", 1, shareCount)
        assertEquals("Dismiss callback should fire once", 1, dismissCount)
    }

    @Test
    fun viewer_whenMissing_rendersFallbackAndHidesShare() {
        composeTestRule.setContent {
            KnotworkTheme {
                ImageViewer(
                    model = null,
                    fileName = FILE_NAME,
                    dimensionsLabel = DIMENSIONS,
                    isMissing = true,
                    onDismiss = {},
                    onShare = {},
                )
            }
        }

        composeTestRule.onNodeWithText(MISSING_TITLE).assertIsDisplayed()
        // Share is suppressed for a missing file even though onShare is non-null.
        composeTestRule.onAllNodesWithContentDescription(SHARE_LABEL).assertCountEquals(0)
    }

    private companion object {
        // Mirrors the catalog string resources (knotwork_image_viewer_*).
        const val FILE_NAME: String = "photo.jpg"
        const val DIMENSIONS: String = "712×1536 · 84 KB"
        const val CLOSE_LABEL: String = "Close"
        const val SHARE_LABEL: String = "Share image"
        const val MISSING_TITLE: String = "Image no longer available"
    }
}
