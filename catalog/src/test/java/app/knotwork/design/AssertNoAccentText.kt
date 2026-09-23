package app.knotwork.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.text.TextLayoutResult
import app.knotwork.design.a11y.AlwaysDarkSurfaceKey
import app.knotwork.design.tokens.KnotworkLight
import app.knotwork.design.tokens.KnotworkPalette

/**
 * Fails when any text on screen is drawn in a risk or signal **accent** of the
 * light theme — the colours that reach only 2.1–3.9:1 on the light surfaces,
 * short of the 4.5:1 WCAG AA asks of body text. Words in those hues use the
 * `…Text` tokens of `KnotworkExtendedColors`; a warning says its words in
 * `onSurface2` and keeps the amber on its glyph.
 *
 * It reads the colour each text node was actually laid out with
 * (`GetTextLayoutResult`, the style plus every span), so a colour that reaches
 * the text through a helper, a `when` or an inherited content colour is caught
 * as well as a literal one — a source scan sees only the literal.
 *
 * Text under a node marked with [AlwaysDarkSurfaceKey] is skipped: that surface
 * is near-black in both themes and the accents read on it.
 *
 * Coverage is what the test renders. Every screenshot capture in this module
 * calls it first (`SnapshotComparisonOptionsGuardTest` enforces that), so a new
 * state gets the check with its baseline; a popup no screenshot opens is not
 * checked.
 *
 * @return this provider, so the check chains into the capture call.
 */
fun <T : SemanticsNodeInteractionsProvider> T.assertNoAccentText(): T {
    val offenders = onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    )
        .fetchSemanticsNodes(atLeastOneRootRequired = false)
        .filterNot { it.isOnAlwaysDarkSurface() }
        .mapNotNull { node -> node.accentUse() }
    check(offenders.isEmpty()) {
        "Text drawn in a light-theme accent (below 4.5:1 on the light surfaces) — use the matching " +
            "…Text token, or onSurface2 words beside the glyph for a warning:\n" + offenders.joinToString("\n")
    }
    return this
}

/** The accent this node's text is drawn in, described for the failure message, or `null`. */
private fun SemanticsNode.accentUse(): String? {
    val layouts = mutableListOf<TextLayoutResult>()
    config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val input = layouts.firstOrNull()?.layoutInput ?: return null
    val colours = listOf(input.style.color) + input.text.spanStyles.map { it.item.color }
    val accent = colours.firstOrNull { it.isLightAccent() } ?: return null
    val hex = "#%06X".format(accent.toArgb() and RGB_MASK)
    return "  \"${input.text.text.take(PREVIEW_CHARS)}\" in $hex"
}

/** Whether this node sits under a subtree marked as the always-dark console surface. */
private fun SemanticsNode.isOnAlwaysDarkSurface(): Boolean {
    var current: SemanticsNode? = this
    while (current != null) {
        if (AlwaysDarkSurfaceKey in current.config) return true
        current = current.parent
    }
    return false
}

/** Whether this colour, whatever its alpha, is one of the light theme's risk / signal accents. */
private fun Color.isLightAccent(): Boolean = this != Color.Unspecified && (toArgb() and RGB_MASK) in LIGHT_ACCENTS

/**
 * The light-theme accents, as RGB. `riskSensitive` and `riskDestructive` are the
 * warn and error signals themselves in the light theme, so they are covered.
 */
private val LIGHT_ACCENTS: Set<Int> = listOf(
    KnotworkPalette.SignalWarn,
    KnotworkPalette.SignalError,
    KnotworkPalette.SignalSuccess,
    KnotworkLight.RiskReadonly,
).mapTo(mutableSetOf()) { it.toArgb() and RGB_MASK }

/** Strips the alpha channel from an ARGB int. */
private const val RGB_MASK = 0x00FFFFFF

/** How much of an offending text the failure message quotes. */
private const val PREVIEW_CHARS = 60
