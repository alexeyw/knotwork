package app.knotwork.design.screens.automation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The "Largest" system text preset, where the consent body has to stay usable. */
private const val LARGE_FONT_SCALE = 2.0f

/**
 * Roborazzi baselines for the external-automation surfaces — the request journal
 * across its documented states, and the consent dialog raised by the master
 * switch — in both themes.
 *
 * The journal's states are not decorative variants: an empty journal under a
 * switched-off contract and an empty one under a live contract mean opposite
 * things, and a caller looping against a disabled entry point is the shape a
 * misconfigured profile actually takes. Each gets a baseline so a change to the
 * posture banner or the repeat collapse cannot land unseen.
 *
 * Reduced-motion is pinned via [FixedKnotworkA11y] so the pending-run dot's
 * pulse cannot randomise a snapshot.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class ExternalAutomationSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Request journal ──────────────────────────────────────────────────────

    @Test
    fun journal_populated_light() = snapshot("journal_populated", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.populated())
    }

    @Test
    fun journal_populated_dark() = snapshot("journal_populated", dark = true) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.populated())
    }

    @Test
    fun journal_empty_light() = snapshot("journal_empty", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.empty())
    }

    @Test
    fun journal_empty_dark() = snapshot("journal_empty", dark = true) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.empty())
    }

    @Test
    fun journal_contract_off_light() = snapshot("journal_contract_off", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.contractOff())
    }

    @Test
    fun journal_contract_off_dark() = snapshot("journal_contract_off", dark = true) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.contractOff())
    }

    @Test
    fun journal_unbound_light() = snapshot("journal_unbound", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.unbound())
    }

    @Test
    fun journal_unbound_dark() = snapshot("journal_unbound", dark = true) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.unbound())
    }

    @Test
    fun journal_refusal_heavy_light() = snapshot("journal_refusal_heavy", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.refusalHeavy())
    }

    @Test
    fun journal_refusal_heavy_dark() = snapshot("journal_refusal_heavy", dark = true) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.refusalHeavy())
    }

    @Test
    fun journal_loading_light() = snapshot("journal_loading", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.loading())
    }

    @Test
    fun journal_call_block_open_light() = snapshot("journal_call_block", dark = false) {
        ExternalAutomationJournalContent(state = ExternalAutomationPreview.callBlockOpen())
    }

    @Test
    fun journal_populated_font_scale_2x_light() =
        snapshot("journal_populated_font_scale_2x", dark = false, fontScale = LARGE_FONT_SCALE) {
            ExternalAutomationJournalContent(state = ExternalAutomationPreview.populated())
        }

    // ── Consent dialog ───────────────────────────────────────────────────────

    @Test
    fun consent_light() = snapshot("consent", dark = false) {
        DialogHost { ExternalAutomationConsentContent(onConfirm = {}, onCancel = {}) }
    }

    @Test
    fun consent_dark() = snapshot("consent", dark = true) {
        DialogHost { ExternalAutomationConsentContent(onConfirm = {}, onCancel = {}) }
    }

    /**
     * The consent card at the "Largest" text preset — the capture that proves the
     * confirm and cancel buttons survive a body long enough to scroll.
     */
    @Test
    fun consent_font_scale_2x_light() = snapshot("consent_font_scale_2x", dark = false, fontScale = LARGE_FONT_SCALE) {
        DialogHost { ExternalAutomationConsentContent(onConfirm = {}, onCancel = {}) }
    }

    /** Approximates the scrim inset a real `Dialog` container gives the card. */
    @Composable
    private fun DialogHost(content: @Composable () -> Unit) {
        Box(modifier = Modifier.padding(20.dp)) { content() }
    }

    /**
     * Wraps the content under the standard test rule, pins reduced-motion so
     * looping animations don't randomise the snapshot, and writes the PNG to
     * `src/test/snapshots/external_automation_<name>_<theme>.png`.
     */
    private fun snapshot(name: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                ) { content() }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/external_automation_${name}_$themeTag.png",
        )
    }
}
