package app.knotwork.android.presentation.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import app.knotwork.android.R
import app.knotwork.design.tokens.KnotworkFonts

/**
 * Builds the bundled Inter and JetBrains Mono [FontFamily] instances from the
 * `R.font.*` resources packaged inside `:app/src/main/res/font/` and pushes
 * them into [KnotworkFonts] so the design-system typography
 * ([app.knotwork.design.tokens.KnotworkTextStyles]) renders against the brand
 * fonts instead of the platform sans/mono fallbacks.
 *
 * Called once from [app.knotwork.android.App.onCreate]. Idempotent — subsequent
 * invocations are harmless because [KnotworkFonts.install] is last-write-wins.
 *
 * Font asset inventory (`res/font/`, about 258 KB in all). Each file is cut by
 * `config/fonts/subset_fonts.py` to the characters of `config/fonts/characters.txt`:
 * Latin-1 with typographic punctuation, Cyrillic, and the symbols the interface
 * shows (`BundledFontCoverageTest` fails a shown character the fonts lack):
 *
 *  | Resource id                      | Weight                |
 *  |----------------------------------|-----------------------|
 *  | `R.font.inter_regular`           | [FontWeight.Normal]   |
 *  | `R.font.inter_medium`            | [FontWeight.Medium]   |
 *  | `R.font.inter_semibold`          | [FontWeight.SemiBold] |
 *  | `R.font.inter_bold`              | [FontWeight.Bold]     |
 *  | `R.font.jetbrains_mono_regular`  | [FontWeight.Normal]   |
 *  | `R.font.jetbrains_mono_medium`   | [FontWeight.Medium]   |
 *  | `R.font.jetbrains_mono_semibold` | [FontWeight.SemiBold] |
 *  | `R.font.jetbrains_mono_bold`     | [FontWeight.Bold]     |
 *
 * JetBrains Mono SemiBold (600) and Bold (700) are load-bearing: the spec's
 * micro-caps tags (source / status tags, node-kind labels, badges, stat
 * numbers) render in Mono 700, and synthetic bold smears at 9.5–11 px. Both
 * weights ship as real files cut from the same release as the 400 / 500 ones
 * (SIL OFL 1.1) — covered by `assets/license_jetbrainsmono_ofl.txt`.
 */
internal object KnotworkFontsBootstrap {

    /**
     * Constructs the bundled font families and installs them into
     * [KnotworkFonts]. Must run before the first Compose composition so the
     * typography sheet picks up the brand fonts on the very first frame.
     */
    fun install() {
        val inter = FontFamily(
            Font(R.font.inter_regular, FontWeight.Normal),
            Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold),
            Font(R.font.inter_bold, FontWeight.Bold),
        )
        val mono = FontFamily(
            Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
            Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
            Font(R.font.jetbrains_mono_semibold, FontWeight.SemiBold),
            Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
        )
        KnotworkFonts.install(inter = inter, mono = mono)
    }
}
