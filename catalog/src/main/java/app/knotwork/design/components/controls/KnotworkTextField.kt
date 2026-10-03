package app.knotwork.design.components.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Single-line Knotwork text input. Built on top of [BasicTextField] so we
 * can pin every visual detail (8 dp `sm` shape, two-weight border, exact
 * padding map, mono vs sans typography, leading/trailing icon insets) and
 * stay independent of Material3's `OutlinedTextField` decoration box —
 * which doesn't expose enough control over hover / focus / read-only state
 * transitions to fit the Knotwork spec.
 *
 * State table:
 * | state               | container  | border               | text         |
 * |---------------------|-----------|----------------------|--------------|
 * | default             | surface1  | outline 1 dp         | onSurface    |
 * | hovered             | surface1  | outlineStrong 1 dp   | onSurface    |
 * | focused             | surface1  | primary 2 dp         | onSurface    |
 * | filled              | surface1  | outline 1 dp         | onSurface    |
 * | disabled            | surface3  | outline 1 dp         | onSurfaceDim |
 * | readOnly            | surface2  | outline 1 dp         | onSurface    |
 * | error               | surface1  | riskDestructive 2 dp | onSurface    |
 * | search variant      | surface2  | none (full shape)    | onSurface    |
 *
 * @param value Current text. Caller owns the state.
 * @param onValueChange Edit callback. Ignored when [enabled] is `false` or
 *  [readOnly] is `true`.
 * @param modifier Layout modifier applied to the outer container. Defaults
 *  to `fillMaxWidth()` since every `KnotworkField` row spans the form.
 * @param size Height + padding token. See [KnotworkFieldSize].
 * @param placeholder Hint text shown when [value] is empty.
 * @param leadingIcon Optional 18 dp icon rendered before the text.
 * @param trailingIcon Optional 20 dp icon rendered after the text. The hit
 *  area expands to 40 dp through [IconButton].
 * @param onTrailingClick Click handler for [trailingIcon]. When `null` the
 *  trailing icon is decorative (no ripple).
 * @param enabled `false` greys out the entire field and disables input.
 * @param readOnly `true` keeps the field active but blocks edits; the
 *  container switches to `surface2` so the user reads it as "value present,
 *  but not editable".
 * @param isError `true` swaps the border to the destructive palette.
 * @param keyboardOptions IME options forwarded to [BasicTextField].
 * @param visualTransformation Forwarded to [BasicTextField] — see
 *  [KnotworkPasswordField] for the password use case.
 * @param monospace `true` switches the text style to [KnotworkTextStyles.MonoBase]
 *  (the spec's `Mono13`) for tokens / URLs / expressions / JSON.
 * @param search When `true`, paints the field as the search-bar variant:
 *  pill shape, `surface2` container, no border (the *Search variant*).
 *  Pair with `leadingIcon = AppIcons.Search` at the call site.
 * @param contentDescription Optional a11y label, passed through to the
 *  outer box semantics. Required when [KnotworkField]'s caps-label is empty
 *  (e.g. inline rename, search bar).
 */
@Composable
fun KnotworkTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    size: KnotworkFieldSize = KnotworkFieldSize.Sm,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    onTrailingClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    monospace: Boolean = false,
    search: Boolean = false,
    contentDescription: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()

    val visuals = resolveFieldVisuals(
        size = size,
        search = search,
        enabled = enabled,
        readOnly = readOnly,
        isError = isError,
        isFocused = isFocused,
        isHovered = isHovered,
    )
    val textStyle = baseTextStyle(monospace = monospace, size = size, color = visuals.textColor)

    Box(
        // Centre the content within the field's min-height: without this the row is
        // pinned top-start, so single-line text floats in the top half of a taller field.
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = visuals.height)
            .clip(visuals.shape)
            .then(
                if (visuals.borderWidth > 0.dp && visuals.borderColor != Color.Transparent) {
                    Modifier.border(visuals.borderWidth, visuals.borderColor, visuals.shape)
                } else {
                    Modifier
                },
            )
            .background(visuals.containerColor, visuals.shape)
            .padding(visuals.containerPadding)
            .semantics {
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KnotworkFieldDefaults.IconGap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = visuals.leadingIconColor,
                    modifier = Modifier.size(KnotworkFieldDefaults.LeadingIconSize),
                )
            }
            Box(modifier = Modifier.weight(1f, fill = true)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    readOnly = readOnly,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    visualTransformation = visualTransformation,
                    interactionSource = interactionSource,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = textStyle,
                        color = visuals.placeholderColor,
                    )
                }
            }
            if (trailingIcon != null) {
                if (onTrailingClick != null && enabled) {
                    IconButton(
                        onClick = onTrailingClick,
                        modifier = Modifier.size(TrailingHitArea),
                    ) {
                        Icon(
                            imageVector = trailingIcon,
                            contentDescription = null,
                            tint = visuals.trailingIconColor,
                            modifier = Modifier.size(KnotworkFieldDefaults.TrailingIconSize),
                        )
                    }
                } else {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = visuals.trailingIconColor,
                        modifier = Modifier.size(KnotworkFieldDefaults.TrailingIconSize),
                    )
                }
            }
        }
    }
}

/** Trailing-icon clickable hit area — slightly compressed from the spec's 48 dp to keep dense rows from bloating. */
private val TrailingHitArea = 40.dp

/**
 * Resolved visual contract for the current state cross-product. Pulled into
 * a value object so the [KnotworkTextField] composable reads as
 * "compose decoration → state lookup → render", not as a 200-line `when`.
 */
private data class FieldVisuals(
    val height: Dp,
    val containerPadding: androidx.compose.foundation.layout.PaddingValues,
    val containerColor: Color,
    val borderColor: Color,
    val borderWidth: Dp,
    val shape: CornerBasedShape,
    val textColor: Color,
    val placeholderColor: Color,
    val leadingIconColor: Color,
    val trailingIconColor: Color,
)

@Composable
private fun resolveFieldVisuals(
    size: KnotworkFieldSize,
    search: Boolean,
    enabled: Boolean,
    readOnly: Boolean,
    isError: Boolean,
    isFocused: Boolean,
    isHovered: Boolean,
): FieldVisuals {
    val ext = KnotworkTheme.extended
    val shape = if (search || size == KnotworkFieldSize.Composer) {
        KnotworkTheme.shapes.full
    } else {
        KnotworkTheme.shapes.sm
    }
    val height = when (size) {
        KnotworkFieldSize.Sm -> KnotworkFieldDefaults.HeightSm
        KnotworkFieldSize.Md -> KnotworkFieldDefaults.HeightMd
        KnotworkFieldSize.Lg -> KnotworkFieldDefaults.HeightLg
        KnotworkFieldSize.Composer -> KnotworkFieldDefaults.HeightComposer
    }
    val padding = when (size) {
        KnotworkFieldSize.Sm -> KnotworkFieldDefaults.PaddingSm
        KnotworkFieldSize.Md -> KnotworkFieldDefaults.PaddingMd
        KnotworkFieldSize.Lg -> KnotworkFieldDefaults.PaddingLg
        KnotworkFieldSize.Composer -> KnotworkFieldDefaults.PaddingSm
    }

    val containerColor: Color
    val borderColor: Color
    val borderWidth: Dp
    when {
        !enabled -> {
            containerColor = ext.surface3
            borderColor = MaterialTheme.colorScheme.outline
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
        readOnly -> {
            containerColor = ext.surface2
            borderColor = MaterialTheme.colorScheme.outline
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
        isError -> {
            containerColor = ext.surface1
            borderColor = ext.riskDestructive
            borderWidth = KnotworkFieldDefaults.BorderError
        }
        search -> {
            containerColor = ext.surface2
            borderColor = Color.Transparent
            borderWidth = 0.dp
        }
        isFocused -> {
            containerColor = ext.surface1
            borderColor = MaterialTheme.colorScheme.primary
            borderWidth = KnotworkFieldDefaults.BorderFocused
        }
        isHovered -> {
            containerColor = ext.surface1
            borderColor = ext.outlineStrong
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
        else -> {
            containerColor = ext.surface1
            borderColor = MaterialTheme.colorScheme.outline
            borderWidth = KnotworkFieldDefaults.BorderDefault
        }
    }

    val textColor = if (!enabled) ext.onSurfaceDim else MaterialTheme.colorScheme.onSurface
    val placeholder = ext.onSurfaceDim
    val leadingIcon = when {
        !enabled -> ext.onSurfaceDim
        isError -> ext.riskDestructive
        isFocused -> MaterialTheme.colorScheme.onSurface
        else -> ext.onSurfaceMuted
    }
    val trailingIcon = leadingIcon

    return FieldVisuals(
        height = height,
        containerPadding = padding,
        containerColor = containerColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        shape = shape,
        textColor = textColor,
        placeholderColor = placeholder,
        leadingIconColor = leadingIcon,
        trailingIconColor = trailingIcon,
    )
}

@Composable
private fun baseTextStyle(monospace: Boolean, size: KnotworkFieldSize, color: Color): TextStyle = if (monospace) {
    KnotworkTextStyles.MonoBase.copy(color = color)
} else if (size == KnotworkFieldSize.Lg) {
    KnotworkTextStyles.BodyLg.copy(color = color)
} else {
    KnotworkTextStyles.BodyBase.copy(color = color)
}
