package app.knotwork.design.components.misc

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkButtonSize
import app.knotwork.design.components.buttons.KnotworkSecondaryButton
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * What a test row is doing. Each state has its own glyph and its own opening words, so the state
 * never depends on colour alone.
 */
enum class TestProbeTone {
    /** The test cannot run; the status says why. */
    Disabled,

    /** The test can run; the status says what it does and what it costs. */
    Idle,

    /** The test is running; the button cancels it. */
    Running,

    /** The test reached its target. */
    Reachable,

    /** Nothing was sent: a rule refused it. Drawn as a warning, not an error. */
    Refused,

    /** The test was sent and failed. */
    Failed,
}

/**
 * One test row, already resolved.
 *
 * @property label The row's title, e.g. "Test connection".
 * @property tone What the row is doing.
 * @property status The line under the title: the reason, the cost, the progress or the result.
 * @property head Bold words that open the status — "Not sent." on a refusal.
 * @property actionLabel The button: "Test", "Cancel" or "Test again".
 * @property accessibleStatus What TalkBack reads for the status when it differs from the visible
 *   text — while running, "Testing connection" once rather than every elapsed second.
 */
data class TestProbeUi(
    val label: String,
    val tone: TestProbeTone,
    val status: String,
    val head: String? = null,
    val actionLabel: String,
    val accessibleStatus: String? = null,
)

/**
 * A test row: glyph, label, status and one button, on a bordered surface.
 *
 * Grown from the Models screen's *Test backend* row into a state machine shared by every form that
 * can test what it configures — a model provider, an MCP server, the local backend.
 *
 * - **Running** draws a 2 dp indeterminate bar on the row's bottom edge — except under reduced
 *   motion, where a frozen segment would read as partial progress; the elapsed seconds in the
 *   status and the *Cancel* button say the test is still going.
 * - **Refused** is not drawn as an error: nothing was sent and nothing failed. The glyph takes the
 *   warn tint and the text stays in full ink.
 * - **TalkBack** meets three stops — label, status, button — not merged, so the status can be
 *   re-read alone. The status is a polite live region: the start is announced once, the result in
 *   full.
 * - **At font scale 150 % and above** the status takes the full width under the label and the
 *   button moves below it.
 *
 * @param state The resolved row.
 * @param onRun The button was pressed while not running.
 * @param onCancel The button was pressed while running.
 * @param modifier Layout modifier from the caller.
 */
@Composable
fun KnotworkTestProbeRow(state: TestProbeUi, onRun: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val stacked = LocalDensity.current.fontScale >= STACKED_FONT_SCALE
    Surface(
        modifier = modifier.fillMaxWidth().testTag(TEST_PROBE_ROW_TEST_TAG),
        shape = KnotworkTheme.shapes.md,
        color = KnotworkTheme.extended.surface1,
        border = BorderStroke(1.dp, KnotworkTheme.extended.divider),
    ) {
        Box {
            Column(modifier = Modifier.padding(KnotworkTheme.spacing.sp3)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
                    verticalAlignment = if (stacked) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Icon(
                        imageVector = glyphOf(state.tone),
                        contentDescription = null,
                        tint = glyphTintOf(state.tone),
                        modifier = Modifier.size(GLYPH_SIZE),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.label,
                            style = KnotworkTextStyles.BodyBase.copy(fontWeight = FontWeight.SemiBold),
                            color = if (state.tone == TestProbeTone.Disabled) {
                                KnotworkTheme.extended.onSurface2
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                        if (!stacked) ProbeStatus(state)
                    }
                    if (!stacked) ProbeAction(state, onRun, onCancel)
                }
                if (stacked) {
                    ProbeStatus(state)
                    Box(modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2)) {
                        ProbeAction(state, onRun, onCancel)
                    }
                }
            }
            if (state.tone == TestProbeTone.Running && !KnotworkTheme.a11y.reducedMotion()) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(PROGRESS_HEIGHT).align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = PROGRESS_TRACK_ALPHA),
                )
            }
        }
    }
}

/** The status line: optional bold head, then the text, read as a polite live region. */
@Composable
private fun ProbeStatus(state: TestProbeUi) {
    val spoken = state.accessibleStatus ?: listOfNotNull(state.head, state.status).joinToString(" ")
    Text(
        text = buildAnnotatedString {
            state.head?.let { head ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(head) }
                append(" ")
            }
            append(state.status)
        },
        style = KnotworkTextStyles.BodySm,
        color = statusColorOf(state.tone),
        modifier = Modifier
            .padding(top = KnotworkTheme.spacing.sp1)
            .testTag(TEST_PROBE_STATUS_TEST_TAG)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = spoken
            },
    )
}

/** *Cancel* while running, otherwise *Test* / *Test again* — disabled while the row is. */
@Composable
private fun ProbeAction(state: TestProbeUi, onRun: () -> Unit, onCancel: () -> Unit) {
    if (state.tone == TestProbeTone.Running) {
        KnotworkTextButton(text = state.actionLabel, onClick = onCancel, size = KnotworkButtonSize.Sm)
    } else {
        KnotworkSecondaryButton(
            text = state.actionLabel,
            onClick = onRun,
            size = KnotworkButtonSize.Sm,
            enabled = state.tone != TestProbeTone.Disabled,
        )
    }
}

private fun glyphOf(tone: TestProbeTone): ImageVector = when (tone) {
    TestProbeTone.Disabled, TestProbeTone.Idle, TestProbeTone.Running -> AppIcons.Bolt
    TestProbeTone.Reachable -> AppIcons.Check
    TestProbeTone.Refused -> AppIcons.Block
    TestProbeTone.Failed -> AppIcons.AlertCircle
}

@Composable
private fun glyphTintOf(tone: TestProbeTone): Color = when (tone) {
    TestProbeTone.Disabled -> KnotworkTheme.extended.onSurfaceMuted
    TestProbeTone.Idle -> KnotworkTheme.extended.onSurface2
    TestProbeTone.Running -> MaterialTheme.colorScheme.primary
    TestProbeTone.Reachable -> KnotworkTheme.extended.signalSuccess
    TestProbeTone.Refused -> KnotworkTheme.extended.signalWarn
    TestProbeTone.Failed -> KnotworkTheme.extended.signalError
}

@Composable
private fun statusColorOf(tone: TestProbeTone): Color = when (tone) {
    TestProbeTone.Reachable -> KnotworkTheme.extended.signalSuccess
    TestProbeTone.Failed -> KnotworkTheme.extended.signalError
    TestProbeTone.Refused -> MaterialTheme.colorScheme.onSurface
    TestProbeTone.Disabled, TestProbeTone.Idle, TestProbeTone.Running -> KnotworkTheme.extended.onSurface2
}

/** Font scale from which the status and the button move under the label. */
private const val STACKED_FONT_SCALE = 1.5f

/** The share of the primary colour the running bar's track takes. */
private const val PROGRESS_TRACK_ALPHA = 0.18f

private val GLYPH_SIZE = 20.dp
private val PROGRESS_HEIGHT = 2.dp

/** Test tag of a test row. */
const val TEST_PROBE_ROW_TEST_TAG: String = "test_probe_row"

/** Test tag of a test row's status line. */
const val TEST_PROBE_STATUS_TEST_TAG: String = "test_probe_status"
