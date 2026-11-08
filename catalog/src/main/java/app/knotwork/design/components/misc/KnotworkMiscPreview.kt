package app.knotwork.design.components.misc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Composable harness exercising every [KnotworkLoader], [StripedPlaceholder],
 * [EmptyState], and [KnotworkSnackbar] variant. Used by the Android Studio
 * preview pane and by the Roborazzi snapshot baseline so misc-component
 * regressions surface in the same diff.
 *
 * Renders inside the parent [KnotworkTheme]; callers (preview / test) pin
 * `darkTheme` deterministically.
 */
@Composable
fun KnotworkMiscCatalogContent() {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
        ) {
            SectionLabel(text = "KnotworkLoader — animated + reduced-motion fallback")
            Row(
                horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp4),
            ) {
                // Animated variant — uses the production a11y impl (returns
                // `reducedMotion = false` in previews).
                KnotworkLoader()
                // Static fallback — pinned via a FixedKnotworkA11y override.
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    KnotworkLoader()
                }
            }

            SectionLabel(text = "StripedPlaceholder")
            Row(horizontalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp3)) {
                StripedPlaceholder(modifier = Modifier.size(96.dp))
                StripedPlaceholder(
                    modifier = Modifier.size(width = 160.dp, height = 96.dp),
                    caption = "product shot",
                )
            }

            SectionLabel(text = "EmptyState")
            EmptyState(
                title = "No pipelines yet",
                subtitle = "Create your first pipeline to start automating tasks.",
                ctaLabel = "Create pipeline",
                onCtaClick = {},
                illustrationSize = 120.dp,
            )

            SectionLabel(text = "KnotworkSnackbar — variants")
            KnotworkSnackbar(data = previewSnackbarData(message = "Pipeline saved"))
            KnotworkSnackbar(
                data = previewSnackbarData(message = "Failed to delete pipeline", actionLabel = "Retry"),
                variant = SnackbarVariant.Error,
            )
            KnotworkSnackbar(
                data = previewSnackbarData(message = "Memory cleared", actionLabel = "Undo"),
                variant = SnackbarVariant.Success,
            )
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

/** Builds a no-op [SnackbarData] for the preview / catalog. */
private fun previewSnackbarData(message: String, actionLabel: String? = null): SnackbarData {
    val visuals = object : SnackbarVisuals {
        override val actionLabel: String? = actionLabel
        override val duration: androidx.compose.material3.SnackbarDuration =
            androidx.compose.material3.SnackbarDuration.Indefinite
        override val message: String = message
        override val withDismissAction: Boolean = false
    }
    return object : SnackbarData {
        override val visuals: SnackbarVisuals = visuals
        override fun dismiss() = Unit
        override fun performAction() = Unit
    }
}

/** Light-theme preview of misc components. */
@Preview(name = "Misc — Light", showBackground = true, heightDp = 1100)
@Composable
private fun KnotworkMiscLightPreview() {
    KnotworkTheme(darkTheme = false) { KnotworkMiscCatalogContent() }
}

/** Dark-theme preview of misc components. */
@Preview(name = "Misc — Dark", showBackground = true, heightDp = 1100)
@Composable
private fun KnotworkMiscDarkPreview() {
    KnotworkTheme(darkTheme = true) { KnotworkMiscCatalogContent() }
}
