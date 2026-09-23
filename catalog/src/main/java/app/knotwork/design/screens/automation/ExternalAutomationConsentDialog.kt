@file:Suppress("MatchingDeclarationName") // Hosts ExternalAutomationConsentContent + its helpers.

package app.knotwork.design.screens.automation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.knotwork.design.components.buttons.KnotworkTextButton
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/** Side length of the leading glyph on the consent card. */
private val DialogIcon = 40.dp

/** Inner glyph size of the leading tile. */
private val DialogIconGlyph = 22.dp

/** Leading glyph size on a consent bullet. */
private val BulletGlyphSize = 16.dp

/**
 * Ceiling on the scrolling body so a long list of bullets cannot push the
 * confirm and cancel buttons off a short screen — which at font-scale 200 % is
 * the difference between a consent dialog and a trap.
 */
private val BodyMaxHeight = 420.dp

/**
 * Copy of the external-automation consent dialog. Defaults are the final English
 * wording (used by previews / snapshots); the app overrides each with a
 * `stringResource`.
 *
 * The bullets are separate fields rather than one paragraph because each is a
 * distinct promise or warning the user is agreeing to, and a wall of prose is
 * exactly how a consent screen stops being read. Their order is deliberate: what
 * the user is opening up first, what still protects them second, what it can cost
 * third, and how to undo it last.
 *
 * @property title The question being asked.
 * @property intro One sentence naming what switching this on does.
 * @property bulletAnyApp Who can ask — there is no per-app allowlist.
 * @property bulletOnePipeline What they can ask for — one pipeline, no redirects.
 * @property bulletApprovals What still stops a run — the HITL gate is unchanged.
 * @property bulletCost What a run can spend — the user's own cloud key.
 * @property bulletReversible How to undo it — the same switch, one tap.
 * @property confirm Confirm-button label.
 * @property cancel Cancel-button label.
 */
data class ExternalAutomationConsentStrings(
    val title: String = "Let other apps run a pipeline?",
    val intro: String =
        "Automation apps like Tasker or MacroDroid — and shell scripts over adb — will be able to ask " +
            "Knotwork to run one pipeline for them.",
    val bulletAnyApp: String =
        "Any app on this device that can send a broadcast may ask. There is no per-app list: the " +
            "pipeline you pick is the whole permission.",
    val bulletOnePipeline: String =
        "Only that one pipeline can be run. A request naming anything else is refused, never " +
            "redirected to the one you picked.",
    val bulletApprovals: String =
        "Your approval settings still apply: a tool call stops and asks you first exactly as in the " +
            "app's own background runs, and a destructive one always does.",
    val bulletCost: String =
        "A run started this way can use your cloud API key, and costs whatever that run costs.",
    val bulletReversible: String = "One tap on the same switch turns this off again.",
    val confirm: String = "Turn on",
    val cancel: String = "Cancel",
)

/**
 * Stateless body of the external-automation consent dialog. The host wraps this
 * in a `Dialog` container; this composable just draws the card.
 *
 * It is raised when the user switches the contract **on**, and the switch only
 * moves once they confirm — switching it back off is immediate and asks nothing.
 * That asymmetry is the point: opening an entry point to code the user did not
 * write is a decision that deserves a sentence, closing it is not.
 *
 * A modal moment rather than an inline disclosure because this is the one place
 * the wording is guaranteed to be in front of the reader, and because a modal
 * prompt is the form Play's prominent-disclosure guidance recognises. The body
 * scrolls under a height ceiling, so the two buttons stay reachable at large
 * font scales instead of being pushed off the card.
 *
 * @param onConfirm Invoked when the user accepts; the caller performs the write.
 * @param onCancel Invoked on cancel; the switch must stay where it was.
 * @param modifier Layout modifier applied to the card.
 * @param strings Localised copy.
 */
@Composable
fun ExternalAutomationConsentContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    strings: ExternalAutomationConsentStrings = ExternalAutomationConsentStrings(),
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = KnotworkTheme.shapes.lg,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(KnotworkTheme.spacing.sp5)) {
            ConsentHeader(title = strings.title)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = BodyMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = KnotworkTheme.spacing.sp4),
                verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
            ) {
                Text(
                    text = strings.intro,
                    style = KnotworkTextStyles.BodySm,
                    color = KnotworkTheme.extended.onSurface2,
                )
                ConsentBullet(text = strings.bulletAnyApp, glyph = AppIcons.Globe, widens = true)
                ConsentBullet(text = strings.bulletOnePipeline, glyph = AppIcons.Flow, widens = false)
                ConsentBullet(text = strings.bulletApprovals, glyph = AppIcons.Shield, widens = false)
                ConsentBullet(text = strings.bulletCost, glyph = AppIcons.Cloud, widens = true)
                ConsentBullet(text = strings.bulletReversible, glyph = AppIcons.Check, widens = false)
            }
            // Marks where the scrolling body ends. At large font scales the body
            // clips mid-sentence, and without a boundary that reads as broken
            // text rather than as "there is more above the buttons".
            HorizontalDivider(
                modifier = Modifier.padding(top = KnotworkTheme.spacing.sp3),
                color = KnotworkTheme.extended.divider,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = KnotworkTheme.spacing.sp3),
                horizontalArrangement = Arrangement.spacedBy(
                    space = KnotworkTheme.spacing.sp2,
                    alignment = Alignment.End,
                ),
            ) {
                KnotworkTextButton(text = strings.cancel, onClick = onCancel)
                KnotworkTextButton(text = strings.confirm, onClick = onConfirm)
            }
        }
    }
}

@Composable
private fun ConsentHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(DialogIcon),
            shape = KnotworkTheme.shapes.md,
            color = KnotworkTheme.extended.surface2,
        ) {
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = AppIcons.External,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(DialogIconGlyph),
                )
            }
        }
        Text(
            text = title,
            style = KnotworkTextStyles.TitleMd.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * One consent bullet: a glyph naming what the sentence is about, and the sentence.
 *
 * The two bullets that widen the user's exposure are tinted differently from the
 * three that reassure — and each carries its own glyph, so the distinction
 * survives for a reader who cannot see the tint.
 */
@Composable
private fun ConsentBullet(text: String, glyph: ImageVector, widens: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = null,
            tint = if (widens) KnotworkTheme.extended.signalWarn else KnotworkTheme.extended.onSurfaceMuted,
            modifier = Modifier.size(BulletGlyphSize).padding(top = KnotworkTheme.spacing.sp1),
        )
        Text(
            text = text,
            style = KnotworkTextStyles.BodySm,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
