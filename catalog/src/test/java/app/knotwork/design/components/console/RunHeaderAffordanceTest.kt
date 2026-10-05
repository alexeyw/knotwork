package app.knotwork.design.components.console

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.screens.chat.ChatHomeRunPreview
import app.knotwork.design.theme.KnotworkTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the run strip, the hash chips and the check's sheet do when used: the line
 * toggles, each copy button hands over the whole value, a disabled action does
 * nothing yet stays readable, and a mismatch's button names where it is fixed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class RunHeaderAffordanceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val taps = mutableListOf<String>()

    private fun strip(header: RunHeaderUi, expanded: Boolean = true) = composeTestRule.setContent {
        KnotworkTheme {
            RunHeaderStrip(
                header = header,
                expanded = expanded,
                onToggle = { taps += "toggle" },
                onCopySeed = { taps += "seed" },
                onCopyModelSha = { taps += "sha:$it" },
                onCopyDigest = { taps += "digest" },
                onVerify = { taps += "verify" },
                onRunAgain = { taps += "again" },
                onExport = { taps += "export" },
                onOpenSettings = { taps += "settings:$it" },
            )
        }
    }

    @Test
    fun `given the collapsed line when tapped then the strip toggles`() {
        strip(ChatHomeRunPreview.header(ChatHomeRunPreview.Header.LOCAL_CPU), expanded = false)

        composeTestRule.onNodeWithContentDescription("Run header", substring = true).performClick()

        assertEquals(listOf("toggle"), taps)
    }

    @Test
    fun `given the open header when each copy and action is used then each hands over its value`() {
        val header = ChatHomeRunPreview.header(ChatHomeRunPreview.Header.LOCAL_CPU)
        strip(header)
        val sha = (header.detail as RunDetailUi.Recorded).models.single().sha256

        composeTestRule.onNodeWithContentDescription("Copy seed").performClick()
        composeTestRule.onNodeWithContentDescription("Copy model SHA-256").performClick()
        composeTestRule.onNodeWithContentDescription("Copy run digest").performClick()
        composeTestRule.onNodeWithContentDescription("Verify", substring = true).performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription(
            "Run again with this seed",
            substring = true,
        ).performScrollTo().performClick()
        composeTestRule.onNodeWithContentDescription("Export trace", substring = true).performScrollTo().performClick()

        assertEquals(listOf("seed", "sha:$sha", "digest", "verify", "again", "export"), taps)
    }

    @Test
    fun `given a mismatch when the header is open then Verify is dimmed and its button opens Settings`() {
        strip(ChatHomeRunPreview.header(ChatHomeRunPreview.Header.VERIFY_MISMATCH))

        composeTestRule.onNodeWithContentDescription("Verify, dimmed", substring = true)
            .assertIsNotEnabled()
            .performClick()
        composeTestRule.onNodeWithText("Settings").performClick()

        assertEquals(listOf("settings:${RunSettingsTarget.SETTINGS}"), taps)
    }

    @Test
    fun `given a run still going when the header is open then no action fires`() {
        strip(ChatHomeRunPreview.header(ChatHomeRunPreview.Header.ACTIVE))

        composeTestRule.onNodeWithText("Available when the run finishes.").assertExists()
        composeTestRule.onNodeWithContentDescription("Export trace", substring = true).performScrollTo().performClick()

        assertEquals(emptyList<String>(), taps)
    }

    @Test
    fun `given a node's hash chips when tapped then the full hash of each is copied`() {
        val copied = mutableListOf<ConsoleHashCopy>()
        val span = ChatHomeRunPreview.hashedTraces().first()
        composeTestRule.setContent {
            KnotworkTheme {
                ConsolePane(
                    tab = ConsoleTab.Traces,
                    onTabChange = {},
                    logs = emptyList(),
                    vars = emptyList(),
                    traces = listOf(span),
                    filter = ConsoleFilter.allOn,
                    onFilterChange = {},
                    onSearch = {},
                    onCopyAll = {},
                    onClear = {},
                    onCopyHash = { copied += it },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Input hash", substring = true).performClick()
        composeTestRule.onNodeWithContentDescription("Output hash", substring = true).performClick()

        assertEquals(
            listOf(
                ConsoleHashCopy(span.name, HashKind.INPUT, span.inputSha256!!),
                ConsoleHashCopy(span.name, HashKind.OUTPUT, span.outputSha256!!),
            ),
            copied,
        )
    }

    @Test
    fun `given the check's sheet when used then cancel, verify again and the diverged hashes reach the host`() {
        val diverged = ChatHomeRunPreview.verification(ChatHomeRunPreview.Check.DIVERGED)
        val verdict = diverged.rows.firstNotNullOf { it.verdict as? VerdictUi.Diverged }
        composeTestRule.setContent {
            KnotworkTheme {
                VerificationSheetContent(
                    ui = diverged,
                    onCancel = { taps += "cancel" },
                    onVerifyAgain = { taps += "again" },
                    onCopyHash = { taps += "hash:$it" },
                    onClose = { taps += "close" },
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("recorded 7afd06b4", substring = true).performClick()
        composeTestRule.onNodeWithContentDescription("Close").performClick()

        assertEquals(listOf("hash:${verdict.recordedSha256}", "close"), taps)
    }

    @Test
    fun `given a running check when Cancel is tapped then the host hears it`() {
        sheet(ChatHomeRunPreview.Check.PROGRESS)

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertEquals(listOf("cancel"), taps)
    }

    @Test
    fun `given a cancelled check when Verify again is tapped then the host hears it`() {
        sheet(ChatHomeRunPreview.Check.CANCELLED)

        composeTestRule.onNodeWithText("Verify again").performClick()

        assertEquals(listOf("again"), taps)
    }

    private fun sheet(stage: ChatHomeRunPreview.Check) = composeTestRule.setContent {
        KnotworkTheme {
            VerificationSheetContent(
                ui = ChatHomeRunPreview.verification(stage),
                onCancel = { taps += "cancel" },
                onVerifyAgain = { taps += "again" },
                onCopyHash = {},
                onClose = {},
            )
        }
    }
}
