package app.knotwork.design.tokens

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Knotwork typography — bundled-TTF Inter (sans) + JetBrains Mono (mono).
 *
 * Font files live in `:app/src/main/res/font/` (cut to Latin-1, Cyrillic and
 * the symbols the interface shows, to keep the APK delta predictable). The
 * `:app` module is responsible for instantiating the [FontFamily] from
 * `R.font.*` IDs and pushing them into [KnotworkFonts]
 * via [KnotworkFonts.install] from `Application.onCreate()`. Until that wiring
 * lands the family pointers fall back to system sans/mono so previews and
 * tests render without crashing.
 *
 * Scale (sp / line-height in sp):
 *  - xs   11sp / 16   — captions, edge labels in pipeline editor.
 *  - sm   13sp / 18   — secondary text, chip labels.
 *  - base 15sp / 22   — body, chat messages.
 *  - md   17sp / 24   — list rows, dialog body.
 *  - lg   20sp / 28   — section headers.
 *  - xl   24sp / 32   — screen titles.
 *  - 2xl  30sp / 38   — onboarding headlines.
 *  - 3xl  38sp / 46   — hero (rare).
 *  - mono 13sp / 18   — JetBrains Mono, console + code chips.
 *
 * Letter-spacing follows M3 defaults except for display sizes where the
 * Knotwork tokens tighten to `-0.01em`.
 */
object KnotworkFonts {
    private var interFamilyState: FontFamily = FontFamily.SansSerif
    private var monoFamilyState: FontFamily = FontFamily.Monospace

    /**
     * Currently-installed sans-serif family used by the Inter-based text styles
     * in [KnotworkTextStyles]. Defaults to [FontFamily.SansSerif] until [install]
     * has been called.
     */
    val interFamily: FontFamily get() = interFamilyState

    /**
     * Currently-installed monospaced family used by the JetBrains-Mono-based
     * text styles in [KnotworkTextStyles]. Defaults to [FontFamily.Monospace]
     * until [install] has been called.
     */
    val monoFamily: FontFamily get() = monoFamilyState

    /**
     * Installs the bundled font families. Intended to be called once from
     * `Application.onCreate()` in the consuming app module after wiring the
     * `R.font.*` resources into [FontFamily] instances.
     *
     * Safe to call multiple times; the last call wins. The state lives in
     * a process-wide singleton because the typography functions
     * ([knotworkTypography]) read the families lazily.
     *
     * @param inter Inter family (Regular / Medium / SemiBold / Bold).
     * @param mono JetBrains Mono family (Regular / Medium / SemiBold / Bold).
     */
    fun install(inter: FontFamily, mono: FontFamily) {
        interFamilyState = inter
        monoFamilyState = mono
    }
}

/**
 * Knotwork text-style sheet. Map onto Material3 [Typography] in
 * [knotworkTypography]; reach for [KnotworkTextStyles] directly when the M3
 * mapping does not cover the use case (edge labels, risk pills, mono chips).
 *
 * All styles read [KnotworkFonts.interFamily] / [KnotworkFonts.monoFamily]
 * lazily at composition time, so calling [KnotworkFonts.install] before the
 * first composition is sufficient to switch the entire scale onto bundled
 * fonts.
 */
object KnotworkTextStyles {
    private val inter get() = KnotworkFonts.interFamily
    private val mono get() = KnotworkFonts.monoFamily

    /** 38 sp / 46 sp Bold — onboarding hero (rare). */
    val Display3xl get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.01).em,
    )

    /** 30 sp / 38 sp Bold — onboarding headlines. */
    val Display2xl get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.01).em,
    )

    /** 24 sp / 32 sp SemiBold — screen titles. */
    val TitleXl get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.005).em,
    )

    /** 20 sp / 28 sp SemiBold — section headers. */
    val TitleLg get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    )

    /** 17 sp / 24 sp SemiBold — list rows, dialog body. */
    val TitleMd get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )

    /** 17 sp / 24 sp Regular — large body. */
    val BodyLg get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    )

    /** 15 sp / 22 sp Regular — body, chat messages. */
    val BodyBase get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    )

    /** 13 sp / 18 sp Regular — secondary body. */
    val BodySm get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** 14 sp / 20 sp SemiBold, +0.1 tracking — button labels. */
    val ButtonLabel get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    )

    /** 15 sp / 20 sp Medium — prominent labels, list titleSmall slot. */
    val LabelLg get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.005.em,
    )

    /** 13 sp / 16 sp Medium — chips, secondary buttons. */
    val LabelMd get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.005.em,
    )

    /** 11 sp / 14 sp Medium — edge labels, micro-pills. */
    val LabelSm get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.01.em,
    )

    /** 11 sp / 16 sp Regular — captions, timestamps. */
    val Caption get() = TextStyle(
        fontFamily = inter,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )

    /** 13 sp / 18 sp Regular Mono — console body, JSON, IDs. */
    val MonoBase get() = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** 11 sp / 16 sp Regular Mono — relevance scores, micro-IDs. */
    val MonoSm get() = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    )
}

/**
 * Builds the Material3 [Typography] sheet wired to the Knotwork scale.
 *
 * Material3 slot mapping:
 *
 *   displayLarge   ← Display3xl
 *   displayMedium  ← Display2xl
 *   displaySmall   ← TitleXl
 *   headlineLarge  ← TitleXl
 *   headlineMedium ← TitleLg
 *   headlineSmall  ← TitleMd
 *   titleLarge     ← TitleLg
 *   titleMedium    ← TitleMd
 *   titleSmall     ← LabelLg
 *   bodyLarge      ← BodyLg
 *   bodyMedium     ← BodyBase
 *   bodySmall      ← BodySm
 *   labelLarge     ← LabelLg
 *   labelMedium    ← LabelMd
 *   labelSmall     ← LabelSm
 *
 * @return a fresh [Typography] instance suitable for the Material3
 * `MaterialTheme(typography = …)` parameter. The function reads
 * [KnotworkFonts] state at call time, so it must be invoked from inside
 * `KnotworkTheme` after the `:app` module has installed the bundled
 * families — earlier callers receive system-fallback fonts.
 */
fun knotworkTypography(): Typography = Typography(
    displayLarge = KnotworkTextStyles.Display3xl,
    displayMedium = KnotworkTextStyles.Display2xl,
    displaySmall = KnotworkTextStyles.TitleXl,
    headlineLarge = KnotworkTextStyles.TitleXl,
    headlineMedium = KnotworkTextStyles.TitleLg,
    headlineSmall = KnotworkTextStyles.TitleMd,
    titleLarge = KnotworkTextStyles.TitleLg,
    titleMedium = KnotworkTextStyles.TitleMd,
    titleSmall = KnotworkTextStyles.LabelLg,
    bodyLarge = KnotworkTextStyles.BodyLg,
    bodyMedium = KnotworkTextStyles.BodyBase,
    bodySmall = KnotworkTextStyles.BodySm,
    labelLarge = KnotworkTextStyles.LabelLg,
    labelMedium = KnotworkTextStyles.LabelMd,
    labelSmall = KnotworkTextStyles.LabelSm,
)
