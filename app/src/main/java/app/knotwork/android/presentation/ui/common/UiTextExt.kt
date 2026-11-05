package app.knotwork.android.presentation.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Resolves the [UiText] to a concrete `String` using the current
 * Composition's `LocalContext`.
 *
 * This is the canonical resolution path for any `UiText` that lives in
 * `UiState` and is rendered inside a `@Composable`.
 *
 * @return The translated, formatted string, or `""` for [UiText.Empty].
 */
@Composable
fun UiText.asString(): String {
    val context = LocalContext.current
    return context.resolve(this)
}

/**
 * Non-Composable counterpart of [asString].
 *
 * Use this from notifications, services, broadcast receivers, or any other
 * surface that holds a `Context` but is not part of a Composition.
 *
 * An argument that is itself a [UiText] is resolved first, so a sentence can carry
 * another — "Bundle pipeline #2 is invalid: <why>" — each worded in the same language.
 *
 * @param text The [UiText] to resolve.
 * @return The translated, formatted string, or `""` for [UiText.Empty].
 */
fun Context.resolve(text: UiText): String = when (text) {
    is UiText.Resource -> if (text.args.isEmpty()) {
        getString(text.id)
    } else {
        getString(text.id, *resolvedArgs(text.args))
    }
    is UiText.Dynamic -> text.text
    is UiText.Joined -> text.parts.joinToString(text.separator) { resolve(it) }
    is UiText.Plural -> resources.getQuantityString(text.id, text.quantity, *resolvedArgs(text.args))
    UiText.Empty -> ""
}

/** The format arguments of a text, with every [UiText] among them resolved. */
private fun Context.resolvedArgs(args: List<Any>): Array<Any> =
    args.map { arg -> if (arg is UiText) resolve(arg) else arg }.toTypedArray()
