package app.knotwork.android.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import app.knotwork.design.theme.KnotworkTheme

/**
 * Thin alias that delegates to [KnotworkTheme] so every screen receives the
 * brand palette, typography, shapes, and extended tokens.
 *
 * Routing through [KnotworkTheme] avoids Material You's
 * `dynamicLightColorScheme(context)`, whose wallpaper-derived `primary` is
 * whatever the device picks — usually blue — instead of the brand amber, and
 * pins the entire surface to the documented design tokens.
 *
 * Material You is intentionally disabled: there is no `dynamicColor`
 * switch to turn it back on.
 *
 * @param darkTheme `true` to use the dark Knotwork palette; defaults to
 * the system theme via [isSystemInDarkTheme].
 * @param content composable tree wrapped by the theme.
 */
@Composable
fun KnotworkAppTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    KnotworkTheme(darkTheme = darkTheme, content = content)
}
