package app.knotwork.design.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.knotwork.design.components.buttons.KnotworkButtonsCatalogContent
import app.knotwork.design.components.chat.ChatCatalogContent
import app.knotwork.design.components.chips.KnotworkChipsCatalogContent
import app.knotwork.design.components.console.ConsoleCatalogContent
import app.knotwork.design.components.lists.KnotworkListsCatalogContent
import app.knotwork.design.components.misc.KnotworkMiscCatalogContent
import app.knotwork.design.components.pipelineeditor.PipelineEditorCatalogContent
import app.knotwork.design.components.text.Text
import app.knotwork.design.theme.KnotworkTheme
import app.knotwork.design.tokens.KnotworkTextStyles

/**
 * Catalog page surfacing every base component shipped in
 * `app.knotwork.design.components.*` in a single scrollable column.
 *
 * Sections (top → bottom): Buttons, Chips & pills, List rows, Misc. Each
 * section reuses its category-level `*CatalogContent` composable so the
 * preview / snapshot story is consistent — categories own the canonical
 * variant set; this page composes them.
 *
 * Mirrors `FoundationsCatalogPage` and `IconCatalogPage` so the design
 * system has a uniform browsing experience across foundations / icons /
 * components. Renders inside the parent [KnotworkTheme]; callers (preview
 * / test) pin `darkTheme` deterministically.
 */
@Composable
fun ComponentsCatalogPage() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KnotworkTheme.spacing.sp4),
            verticalArrangement = Arrangement.spacedBy(KnotworkTheme.spacing.sp6),
        ) {
            item { SectionHeader(text = "Buttons") }
            item { KnotworkButtonsCatalogContent() }
            item { SectionHeader(text = "Chips & pills") }
            item { KnotworkChipsCatalogContent() }
            item { SectionHeader(text = "List rows") }
            item { KnotworkListsCatalogContent() }
            item { SectionHeader(text = "Misc") }
            item { KnotworkMiscCatalogContent() }
            item { SectionHeader(text = "Chat") }
            item { ChatCatalogContent() }
            item { SectionHeader(text = "Console") }
            item { ConsoleCatalogContent() }
            item { SectionHeader(text = "Pipeline editor") }
            item { PipelineEditorCatalogContent() }
        }
    }
}

/** Section title rendered above each component category. */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = KnotworkTextStyles.TitleLg,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = KnotworkTheme.spacing.sp2),
    )
}
