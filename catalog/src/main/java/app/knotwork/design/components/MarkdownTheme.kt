package app.knotwork.design.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Knotwork bindings for `com.mikepenz:multiplatform-markdown-renderer`.
 *
 * Two factories: [knotworkMarkdownTypography] (text styles per markdown
 * element) and [knotworkMarkdownColor] (surface + outline colors).
 *
 * Usage:
 * ```
 * Markdown(
 *     content = mdText,
 *     typography = knotworkMarkdownTypography(),
 *     colors     = knotworkMarkdownColor(),
 * )
 * ```
 *
 * Why we override the library defaults:
 *
 *  - The library maps `h1` → `displayLarge` (in Knotwork that's
 *    `Display3xl`, 38 sp). Markdown is rendered **inside content
 *    surfaces** — chat bubbles, tool output cards, prompt previews,
 *    HITL detail panes — never as page chrome. Display-tier sizes
 *    fight the surrounding UI. We collapse the heading scale into
 *    `TitleXl → BodySm` so the largest markdown heading lines up
 *    with a screen title (24 sp) and the smallest reads as an
 *    inline overline (13 sp Medium).
 *  - Body must equal chat bubble body so a markdown paragraph and a
 *    plain chat paragraph render identically. That's `BodyBase`
 *    (15 sp / 22 lh) — not `bodyLarge` (17 sp).
 *  - Code / inline code: `MonoBase` (JetBrains Mono 13 sp). Mono at
 *    13 sp visually matches sans 15 sp on the cap-line, which keeps
 *    inline `code` from jumping inside a paragraph.
 *  - Quote uses `BodyBase` italic, *not* `bodyMedium` — same reason
 *    as body; blockquote should sit on the paragraph baseline.
 *  - Lists inherit `BodyBase`. The library's `ordered` / `bullet` /
 *    `list` slots all map to the same style — they take different
 *    bullet glyphs but typography is uniform.
 *  - Links: `primary` + underline + Medium (not Bold). Bold makes
 *    inline links read like emphasis; Medium is enough to
 *    distinguish weight while staying inline-quiet.
 *  - Code background uses `surface2` (a real tonal step) rather
 *    than `onBackground @ 10%` — the alpha trick produces a muddy
 *    grey on our warm-neutral surface ladder. Tonal step reads
 *    cleaner against `surface1` chat bubbles and stays legible in
 *    dark theme.
 *  - Inline code uses `surface3` for a touch more contrast against
 *    the paragraph, since it's a small target.
 *  - Divider uses `extended.divider` (the hairline token), not the
 *    M3 `outlineVariant` slot — same value in light, but in dark
 *    `divider` is intentionally one step closer to surface than
 *    `outlineVariant`, which we want for `---` rules.
 *  - Table background = `surface1` (same as cards). Subtle but
 *    enough to anchor the row structure when zebra-striping is off.
 *
 * Letter-spacing / line-height are inherited from the source
 * `KnotworkTextStyles` entries — do not override here.
 */

/**
 * Mirror of `markdownTypography(...)` from the library, themed to
 * Knotwork. All parameters keep their library names so a caller can
 * still override individual slots.
 *
 * Source of truth for the underlying styles: [KnotworkTextStyles].
 */
@Composable
@ReadOnlyComposable
fun knotworkMarkdownTypography(
    // ── Headings ─────────────────────────────────────────────
    // Knotwork collapses M3 display tier — markdown headings live
    // inside content, not as page chrome.
    h1: TextStyle = KnotworkTextStyles.TitleXl, // 24 sp · SemiBold
    h2: TextStyle = KnotworkTextStyles.TitleLg, // 20 sp · SemiBold
    h3: TextStyle = KnotworkTextStyles.TitleMd, // 17 sp · SemiBold
    h4: TextStyle = KnotworkTextStyles.BodyBase.copy(
        fontWeight = FontWeight.SemiBold,
    ), // 15 sp · SemiBold
    h5: TextStyle = KnotworkTextStyles.BodySm.copy(
        fontWeight = FontWeight.SemiBold,
    ), // 13 sp · SemiBold
    h6: TextStyle = KnotworkTextStyles.LabelSm.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.04.em,
    ), // 11 sp · SemiBold · tracked

    // ── Body ─────────────────────────────────────────────────
    // Must equal chat bubble body so MD and plain text align.
    text: TextStyle = KnotworkTextStyles.BodyBase,
    paragraph: TextStyle = KnotworkTextStyles.BodyBase,

    // ── Code ─────────────────────────────────────────────────
    // MonoBase = JetBrains Mono 13 sp / 18 lh — cap-line matches
    // sans 15 sp so inline `code` doesn't shift the paragraph.
    code: TextStyle = KnotworkTextStyles.MonoBase,
    inlineCode: TextStyle = KnotworkTextStyles.MonoBase,

    // ── Quote ────────────────────────────────────────────────
    // Italic of body — color tint lives on the renderer, not here.
    quote: TextStyle = KnotworkTextStyles.BodyBase.merge(
        SpanStyle(fontStyle = FontStyle.Italic),
    ),

    // ── Lists ────────────────────────────────────────────────
    // Library exposes three slots; we keep them aligned.
    ordered: TextStyle = KnotworkTextStyles.BodyBase,
    bullet: TextStyle = KnotworkTextStyles.BodyBase,
    list: TextStyle = KnotworkTextStyles.BodyBase,

    // ── Links ────────────────────────────────────────────────
    // Medium + underline + primary. Bold reads as emphasis and
    // collides with **bold** markdown spans inside the same line.
    textLink: TextLinkStyles = TextLinkStyles(
        style = KnotworkTextStyles.BodyBase.copy(
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.Underline,
        ).toSpanStyle(),
    ),

    // ── Tables ───────────────────────────────────────────────
    // Compact tables in chat / tool output: read body-sized, not
    // smaller — the cell padding is what creates density.
    table: TextStyle = KnotworkTextStyles.BodyBase,
): com.mikepenz.markdown.model.MarkdownTypography =
    com.mikepenz.markdown.model.DefaultMarkdownTypography(
        h1 = h1, h2 = h2, h3 = h3, h4 = h4, h5 = h5, h6 = h6,
        text = text,
        code = code,
        inlineCode = inlineCode,
        quote = quote,
        paragraph = paragraph,
        ordered = ordered,
        bullet = bullet,
        list = list,
        textLink = textLink,
        table = table,
    )

/**
 * Mirror of `markdownColor(...)` from the library, themed to
 * Knotwork. Reads from both `MaterialTheme.colorScheme` (text,
 * default surfaces) and `KnotworkTheme.extended` (tonal steps and
 * divider hairline).
 *
 * The library's [com.mikepenz.markdown.model.MarkdownColors]
 * surface in `0.41.0` is intentionally small — link / code / table
 * text inherit from the global [text] color, which is what we want
 * (Knotwork keeps body text on a single foreground token regardless
 * of markdown element). Link colour comes from the `textLink` slot
 * on [knotworkMarkdownTypography] above.
 *
 * Note this is a `@Composable` factory (the library's signature is
 * not — but it dereferences `MaterialTheme.colorScheme` in its
 * defaults, which only works inside composition. We make that
 * explicit rather than rely on the trick.)
 */
@Composable
@ReadOnlyComposable
fun knotworkMarkdownColor(
    text: Color = MaterialTheme.colorScheme.onSurface,

    // Block code: one tonal step above chat bubble surface
    // (surface1 → surface2). On dark theme this is the same one-step
    // rule and stays legible without ramping into surfaceVariant.
    codeBackground: Color = KnotworkTheme.extended.surface2,

    // Inline code: one further step (surface3) for a touch more
    // contrast since the target is small and sits inside a sentence.
    inlineCodeBackground: Color = KnotworkTheme.extended.surface3,

    // `---` rule. extended.divider == outlineVariant in light,
    // but in dark it's one step closer to surface — what we want
    // for inline rules.
    dividerColor: Color = KnotworkTheme.extended.divider,

    // Subtle tonal lift to anchor table rows. surface1 matches the
    // resting card background so tables read as content cards.
    tableBackground: Color = KnotworkTheme.extended.surface1,
): com.mikepenz.markdown.model.MarkdownColors =
    com.mikepenz.markdown.model.DefaultMarkdownColors(
        text = text,
        codeBackground = codeBackground,
        inlineCodeBackground = inlineCodeBackground,
        dividerColor = dividerColor,
        tableBackground = tableBackground,
    )
