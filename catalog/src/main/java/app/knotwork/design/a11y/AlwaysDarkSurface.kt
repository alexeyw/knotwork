package app.knotwork.design.a11y

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/**
 * Semantics marker for a subtree drawn on the console surface
 * (`KnotworkExtendedColors.consoleBg`), which is near-black in **both** themes.
 *
 * Text colour is judged against the surface behind it. Everywhere else in the
 * light theme the risk and signal accents are too faint for words and the
 * `…Text` tokens are used instead; on this surface the accents themselves read
 * (the error accent is 4.6:1 on the light theme's console background), and a
 * darkened text tone would not. The marker lets a check that walks rendered
 * text — the catalog's accent-text check (`assertNoAccentText`) — tell the two
 * situations apart instead of carrying a list of exempt screens.
 *
 * It carries no information for accessibility services and changes nothing
 * they announce.
 */
val AlwaysDarkSurfaceKey: SemanticsPropertyKey<Unit> = SemanticsPropertyKey(name = "AlwaysDarkSurface")

// Setter behind `alwaysDarkSurface()`; see AlwaysDarkSurfaceKey.
private var SemanticsPropertyReceiver.alwaysDarkSurface: Unit by AlwaysDarkSurfaceKey

/**
 * Marks this node's subtree as drawn on the always-dark console surface.
 * See [AlwaysDarkSurfaceKey].
 *
 * @return this modifier with the marker attached.
 */
fun Modifier.alwaysDarkSurface(): Modifier = semantics { alwaysDarkSurface = Unit }
