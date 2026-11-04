package app.knotwork.design.components.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import app.knotwork.design.components.text.Text
import app.knotwork.design.icons.AppIcons
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Composable harness exercising every [KnotworkChip] / [RiskPill] /
 * [StatusPill] variant in a single column. Used by the Android Studio
 * preview pane and by the Roborazzi snapshot baseline so chip / pill
 * regressions surface in the same diff.
 *
 * Renders inside the parent [KnotworkTheme]; callers (preview / test) pin
 * `darkTheme` deterministically.
 */
@Composable
fun KnotworkChipsCatalogContent() {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        ) {
            SectionLabel(text = "KnotworkChip — styles")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                KnotworkChip(label = "Default", onClick = {})
                KnotworkChip(label = "Tonal", onClick = {}, style = ChipStyle.Tonal)
                KnotworkChip(label = "Outline", onClick = {}, style = ChipStyle.Outline)
                KnotworkChip(label = "Disabled", onClick = {}, enabled = false)
            }

            SectionLabel(text = "KnotworkChip — selected")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                KnotworkChip(label = "Default", onClick = {}, selected = true)
                KnotworkChip(label = "Tonal", onClick = {}, selected = true, style = ChipStyle.Tonal)
                KnotworkChip(label = "Outline", onClick = {}, selected = true, style = ChipStyle.Outline)
            }

            SectionLabel(text = "KnotworkChip — with icons")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                KnotworkChip(
                    label = "Filter",
                    onClick = {},
                    leadingIcon = AppIcons.Filter,
                )
                KnotworkChip(
                    label = "Models",
                    onClick = {},
                    trailingIcon = AppIcons.X,
                    selected = true,
                )
                KnotworkChip(label = "Decorative", leadingIcon = AppIcons.Filter)
            }

            SectionLabel(text = "RiskPill")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                RiskPill(risk = Risk.Readonly)
                RiskPill(risk = Risk.Sensitive)
                RiskPill(risk = Risk.Destructive)
            }

            SectionLabel(text = "StatusPill")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp2)) {
                StatusPill(status = Status.Idle)
                StatusPill(status = Status.Running)
                StatusPill(status = Status.Success)
                StatusPill(status = Status.Warning)
                StatusPill(status = Status.Error)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.LabelMd,
        color = KnotworkTheme.extended.onSurfaceMuted,
    )
}

/** Light-theme preview of chips and pills. */
@Preview(name = "Chips & pills — Light", showBackground = true, heightDp = 720)
@Composable
private fun KnotworkChipsLightPreview() {
    KnotworkTheme(darkTheme = false) { KnotworkChipsCatalogContent() }
}

/** Dark-theme preview of chips and pills. */
@Preview(name = "Chips & pills — Dark", showBackground = true, heightDp = 720)
@Composable
private fun KnotworkChipsDarkPreview() {
    KnotworkTheme(darkTheme = true) { KnotworkChipsCatalogContent() }
}
