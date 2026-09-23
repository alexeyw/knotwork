@file:Suppress("MagicNumber") // Token file: every literal IS the data; named constants would just rename the same hex.

package app.knotwork.design.tokens

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Knotwork color tokens — port of the canonical OKLCH tokens into Compose-ready
 * sRGB hex values.
 *
 * Conversion pipeline: OKLCH → linear sRGB → sRGB hex, chroma clipped to gamut.
 * Individual hex values must not be hand-edited; updates flow from the upstream
 * OKLCH token spec, then are re-exported into this file.
 *
 * The accent ramp uses hue 70 (warm amber). Signal hues (155 success / 80 warn
 * / 25 error) are fixed and **must never** be re-themed by accent — they
 * convey state semantics and must read consistently across themes.
 */
object KnotworkPalette {
    // Accent ramp (hue 70, warm amber)
    val Accent50 = Color(0xFFFFEFDD)
    val Accent100 = Color(0xFFFBE0C1)
    val Accent200 = Color(0xFFF0C595)
    val Accent300 = Color(0xFFE1AC6E)
    val Accent400 = Color(0xFFD49648)
    val Accent500 = Color(0xFFC48225) // MANUAL source rail (light); not the primary role
    val Accent600 = Color(0xFFA76C12)
    val Accent700 = Color(0xFF81520A)
    val Accent800 = Color(0xFF54360B)
    val Accent900 = Color(0xFF331F05)

    // Signal (hue-locked, never re-themed by accent)
    val SignalSuccess = Color(0xFF43A96E)
    val SignalWarn = Color(0xFFD5A13C)
    val SignalError = Color(0xFFD55753)

    // Per-NodeType hues (13) — identical in both themes so pipelines read
    // identically in light and dark.
    val NodeInput = Color(0xFFC48225)
    val NodeIntentRouter = Color(0xFF9283DC)
    val NodeIfCondition = Color(0xFF43A96E)
    val NodeClarification = Color(0xFFD49648)

    val NodeLiteRt = Color(0xFFA76C12)
    val NodeCloud = Color(0xFF5F9DB0)
    val NodeTool = Color(0xFFD5A13C)
    val NodeDecomposition = Color(0xFF00A8A2)
    val NodeQueueProcessor = Color(0xFFC670AB)
    val NodeEvaluation = Color(0xFFD5725A)
    val NodeSummary = Color(0xFF359BD9)
    val NodeOutput = Color(0xFF81520A)

    // Deep indigo for `NodeType.PIPELINE` — occupies the empty slot between
    // IntentRouter violet (~290°) and Summary/Cloud blue (~220°). Darker and
    // more saturated than its neighbours (L 0.47, oklch(0.47 0.16 274)) so it
    // reads as a separate family at a glance; the lower lightness also keeps
    // `headerOnColor` firmly in the white-foreground band (L < 0.5, 7.16:1).
    val NodePipeline = Color(0xFF424DB2)
}

/**
 * Light-theme surface and role tokens consumed by both the Material3
 * [ColorScheme] and the Knotwork-specific [KnotworkExtendedColors].
 */
object KnotworkLight {
    val Surface0 = Color(0xFFFEFCFA)
    val Surface1 = Color(0xFFFAF6F3)
    val Surface2 = Color(0xFFF6F2ED)
    val Surface3 = Color(0xFFF1ECE6)
    val Surface4 = Color(0xFFEDE6DF)
    val SurfaceInv = Color(0xFF1A1815)

    val OnSurface = Color(0xFF14110E)
    val OnSurface2 = Color(0xFF3B3732)
    val OnSurfaceMuted = Color(0xFF6D6863)
    val OnSurfaceDim = Color(0xFF95918D)

    val Outline = Color(0xFFD4D0CB)
    val OutlineStrong = Color(0xFFAFAAA4)
    val Divider = Color(0xFFE7E4E0)

    val OnPrimary = Color(0xFFFEFBF8)
    val PrimaryContainer = KnotworkPalette.Accent100
    val OnPrimaryContainer = KnotworkPalette.Accent800

    // Tertiary container — lighter cool-blue tint of `NodeCloud` (#5F9DB0)
    // paired with a near-black on-tone for AA contrast on the container.
    val TertiaryContainer = Color(0xFFD6E5EB)
    val OnTertiaryContainer = Color(0xFF173039)

    val RiskReadonly = Color(0xFF5F9DB0)
    val RiskSensitive = KnotworkPalette.SignalWarn
    val RiskDestructive = KnotworkPalette.SignalError

    // Chat bubble pairs: user / agent / tool, each bg + fg.
    val ChatUserBg = KnotworkPalette.Accent100
    val ChatUserFg = KnotworkPalette.Accent800
    val ChatAgentBg = Surface1
    val ChatAgentFg = OnSurface
    val ChatToolBg = Surface2
    val ChatToolFg = OnSurface2

    val ConsoleBg = Color(0xFF191511)
    val ConsoleFg = Color(0xFFE7E4E0)
    val ConsoleTag = KnotworkPalette.Accent300

    // Memory source-tag provenance: AUTO hue 220 (blue),
    // MANUAL hue 70 (brand amber), COMPACT hue 285 (violet). bg / fg / 3px rail.
    val MemAutoBg = Color(0xFFD6F0F9)
    val MemAutoFg = Color(0xFF005E78)
    val MemAutoRail = Color(0xFF1F9BBA)
    val MemManualBg = KnotworkPalette.Accent100
    val MemManualFg = KnotworkPalette.Accent800
    val MemManualRail = KnotworkPalette.Accent500
    val MemCompactBg = Color(0xFFE8E9FF)
    val MemCompactFg = Color(0xFF534F8D)
    val MemCompactRail = Color(0xFF807CC6)
}

/**
 * Dark-theme surface and role tokens consumed by both the Material3
 * [ColorScheme] and the Knotwork-specific [KnotworkExtendedColors].
 */
object KnotworkDark {
    val Surface0 = Color(0xFF0F0D0B)
    val Surface1 = Color(0xFF181512)
    val Surface2 = Color(0xFF221E1A)
    val Surface3 = Color(0xFF2C2823)
    val Surface4 = Color(0xFF39342F)
    val SurfaceInv = Color(0xFFF4F1ED)

    val OnSurface = Color(0xFFF4F1EE)
    val OnSurface2 = Color(0xFFC7C3BF)
    val OnSurfaceMuted = Color(0xFF96918C)
    val OnSurfaceDim = Color(0xFF67625D)

    val Outline = Color(0xFF413C36)
    val OutlineStrong = Color(0xFF635C55)
    val Divider = Color(0xFF2C2824)

    val OnPrimary = Color(0xFF171008)
    val PrimaryContainer = Color(0xFF462D0B)
    val OnPrimaryContainer = KnotworkPalette.Accent200

    // Tertiary container — dark cool-blue tone paired with a light cyan
    // foreground; symmetric to the light-theme TertiaryContainer.
    val TertiaryContainer = Color(0xFF1A3B47)
    val OnTertiaryContainer = Color(0xFFC4DDE5)

    val RiskReadonly = Color(0xFF77B6CA)
    val RiskSensitive = Color(0xFFE9B452)
    val RiskDestructive = Color(0xFFF97770)

    // Chat bubble pairs: user / agent / tool, each bg + fg.
    val ChatUserBg = Color(0xFF412805)
    val ChatUserFg = KnotworkPalette.Accent100
    val ChatAgentBg = Surface2
    val ChatAgentFg = OnSurface
    val ChatToolBg = Surface3
    val ChatToolFg = OnSurface2

    val ConsoleBg = Color(0xFF070504)
    val ConsoleFg = Color(0xFFDAD7D3)
    val ConsoleTag = KnotworkPalette.Accent300

    // Memory source-tag provenance — dark variants.
    val MemAutoBg = Color(0xFF07333F)
    val MemAutoFg = Color(0xFF7ED2ED)
    val MemAutoRail = Color(0xFF4AADC9)
    val MemManualBg = Color(0xFF462D0B)
    val MemManualFg = KnotworkPalette.Accent100
    val MemManualRail = KnotworkPalette.Accent300
    val MemCompactBg = Color(0xFF2B2A46)
    val MemCompactFg = Color(0xFFBDBCFD)
    val MemCompactRail = Color(0xFF928FD4)
}

/**
 * Builds the Material3 [ColorScheme] for the light Knotwork palette.
 *
 * Knotwork roles are mapped onto Material3 slots as follows:
 *  - `primary` → [KnotworkPalette.Accent600] (light) / `Accent300` (dark) —
 *    accent-600 (L 0.58) keeps white `on-primary` above the 3:1 UI-contrast
 *    floor on filled buttons; accent-500 (L 0.66) fell short.
 *  - `secondary` → `Accent700` (light) / `Accent300` (dark)
 *  - `tertiary` → readonly-risk hue (the cool blue-cyan node colour)
 *  - `error` → [KnotworkPalette.SignalError] / dark equivalent
 *  - surfaces map to the Knotwork surface ramp
 *
 * For Knotwork-specific roles that have no Material3 slot (chat bubbles,
 * console surfaces, the three risk levels and 12 node hues), reach for
 * [KnotworkExtendedColors] via `KnotworkTheme.extended`.
 *
 * @return a freshly built [ColorScheme]; cheap enough to allocate on every
 * theme switch, so callers do not need to memoise.
 */
fun knotworkLightColorScheme(): ColorScheme = lightColorScheme(
    primary = KnotworkPalette.Accent600,
    onPrimary = KnotworkLight.OnPrimary,
    primaryContainer = KnotworkLight.PrimaryContainer,
    onPrimaryContainer = KnotworkLight.OnPrimaryContainer,
    secondary = KnotworkPalette.Accent700,
    onSecondary = KnotworkLight.OnPrimary,
    secondaryContainer = KnotworkLight.Surface3,
    onSecondaryContainer = KnotworkLight.OnSurface,
    tertiary = KnotworkPalette.NodeCloud,
    onTertiary = KnotworkLight.OnPrimary,
    tertiaryContainer = KnotworkLight.TertiaryContainer,
    onTertiaryContainer = KnotworkLight.OnTertiaryContainer,
    error = KnotworkPalette.SignalError,
    onError = KnotworkLight.OnPrimary,
    errorContainer = Color(0xFFFADCDA),
    onErrorContainer = Color(0xFF5A1410),
    background = KnotworkLight.Surface0,
    onBackground = KnotworkLight.OnSurface,
    surface = KnotworkLight.Surface0,
    onSurface = KnotworkLight.OnSurface,
    surfaceVariant = KnotworkLight.Surface2,
    onSurfaceVariant = KnotworkLight.OnSurface2,
    surfaceTint = KnotworkPalette.Accent500,
    inverseSurface = KnotworkLight.SurfaceInv,
    inverseOnSurface = KnotworkLight.OnPrimary,
    inversePrimary = KnotworkPalette.Accent300,
    outline = KnotworkLight.Outline,
    outlineVariant = KnotworkLight.Divider,
    scrim = Color(0x66000000),
    // Material3 expanded surface ramp (M3 1.1+): bind every container step
    // to the Knotwork Surface0..4 ramp so tonal containers in libraries
    // that read these slots (NavigationBar, BottomSheet, FilledCard, …)
    // stay on-brand instead of inheriting Material baseline tints.
    surfaceBright = KnotworkLight.Surface0,
    surfaceDim = KnotworkLight.Surface3,
    surfaceContainerLowest = KnotworkLight.Surface0,
    surfaceContainerLow = KnotworkLight.Surface1,
    surfaceContainer = KnotworkLight.Surface2,
    surfaceContainerHigh = KnotworkLight.Surface3,
    surfaceContainerHighest = KnotworkLight.Surface4,
)

/**
 * Builds the Material3 [ColorScheme] for the dark Knotwork palette.
 *
 * The mapping mirrors [knotworkLightColorScheme] but pulls darker
 * accent steps (`Accent400`/`Accent300`) so contrast on dark surfaces
 * stays inside WCAG AA.
 *
 * @return a freshly built [ColorScheme]; cheap to recompute on theme flips.
 */
fun knotworkDarkColorScheme(): ColorScheme = darkColorScheme(
    primary = KnotworkPalette.Accent300,
    onPrimary = KnotworkDark.OnPrimary,
    primaryContainer = KnotworkDark.PrimaryContainer,
    onPrimaryContainer = KnotworkDark.OnPrimaryContainer,
    secondary = KnotworkPalette.Accent300,
    onSecondary = KnotworkDark.OnPrimary,
    secondaryContainer = KnotworkDark.Surface3,
    onSecondaryContainer = KnotworkDark.OnSurface,
    tertiary = KnotworkDark.RiskReadonly,
    onTertiary = KnotworkDark.OnPrimary,
    tertiaryContainer = KnotworkDark.TertiaryContainer,
    onTertiaryContainer = KnotworkDark.OnTertiaryContainer,
    error = KnotworkDark.RiskDestructive,
    onError = KnotworkDark.OnPrimary,
    errorContainer = Color(0xFF5A1B17),
    onErrorContainer = Color(0xFFFADCDA),
    background = KnotworkDark.Surface0,
    onBackground = KnotworkDark.OnSurface,
    surface = KnotworkDark.Surface0,
    onSurface = KnotworkDark.OnSurface,
    surfaceVariant = KnotworkDark.Surface2,
    onSurfaceVariant = KnotworkDark.OnSurface2,
    surfaceTint = KnotworkPalette.Accent400,
    inverseSurface = KnotworkDark.SurfaceInv,
    inverseOnSurface = KnotworkDark.OnPrimary,
    inversePrimary = KnotworkPalette.Accent700,
    outline = KnotworkDark.Outline,
    outlineVariant = KnotworkDark.Divider,
    scrim = Color(0x99000000),
    // Material3 expanded surface ramp (M3 1.1+): mirror the Knotwork dark
    // Surface0..4 ramp so tonal containers stay on-brand. `surfaceBright`
    // takes the lightest dark step (`Surface4`); `surfaceDim` the deepest.
    surfaceBright = KnotworkDark.Surface4,
    surfaceDim = KnotworkDark.Surface0,
    surfaceContainerLowest = KnotworkDark.Surface0,
    surfaceContainerLow = KnotworkDark.Surface1,
    surfaceContainer = KnotworkDark.Surface2,
    surfaceContainerHigh = KnotworkDark.Surface3,
    surfaceContainerHighest = KnotworkDark.Surface4,
)
