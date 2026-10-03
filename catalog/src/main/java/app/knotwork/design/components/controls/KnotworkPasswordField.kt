package app.knotwork.design.components.controls

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import app.knotwork.design.icons.AppIcons

/**
 * Password / API-key / token Knotwork input. Wraps [KnotworkTextField] with
 * a [PasswordVisualTransformation] (`•` glyph) by default and a built-in
 * eye-toggle trailing icon that flips between [AppIcons.Eye]
 * and [AppIcons.EyeOff].
 *
 * Spec mapping:
 *  - `enabled = false` callers can compose with [KnotworkField] helper to
 *    show the masked-with-suffix variant (`••••••••YOUR`) themselves — the
 *    last-4 reveal is a presentation-layer concern, not a control-layer
 *    one, so this atom keeps a tight surface and lets callers decide what
 *    to show when the field is locked.
 *
 * @param value Current token / password text.
 * @param onValueChange Edit callback.
 * @param modifier Forwarded to the inner [KnotworkTextField].
 * @param placeholder Hint text shown when [value] is empty.
 * @param enabled `false` greys out the field and disables the eye-toggle.
 * @param isError `true` paints the destructive border around the field.
 * @param initiallyRevealed When `true`, the eye-toggle starts in the
 *  revealed (eye-open) state. Defaults to hidden so the password masks on
 *  first paint.
 * @param size Forwarded to [KnotworkTextField] — defaults to
 *  [KnotworkFieldSize.Sm]; bump to [KnotworkFieldSize.Lg] for onboarding hero
 *  fields where the token is the page's primary affordance.
 */
@Composable
fun KnotworkPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    isError: Boolean = false,
    initiallyRevealed: Boolean = false,
    size: KnotworkFieldSize = KnotworkFieldSize.Sm,
) {
    var revealed by remember { mutableStateOf(initiallyRevealed) }
    val trailing: ImageVector = if (revealed) AppIcons.EyeOff else AppIcons.Eye
    val transformation: VisualTransformation = if (revealed) {
        VisualTransformation.None
    } else {
        PasswordVisualTransformation()
    }
    KnotworkTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        size = size,
        placeholder = placeholder,
        trailingIcon = trailing,
        onTrailingClick = { revealed = !revealed },
        enabled = enabled,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = transformation,
        // Mono only when revealed — API keys are wide and read better as Mono13,
        // but the bullet glyph is rendered by the visual transformation and
        // looks identical in any family, so we keep the masked state in sans
        // for vertical-rhythm parity with sibling sans fields.
        monospace = revealed,
    )
}
