package app.knotwork.android.presentation.ui.orchestrator

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.knotwork.android.R
import app.knotwork.android.domain.models.PipelineCollision
import app.knotwork.android.domain.text.toDisplaySafe
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.android.presentation.ui.common.asString
import app.knotwork.design.components.dialogs.MAX_NAMED_LIST_ITEMS
import app.knotwork.design.components.dialogs.OutcomeAction
import app.knotwork.design.components.dialogs.OutcomeActionEmphasis
import app.knotwork.design.components.dialogs.OutcomeDialog
import app.knotwork.design.components.dialogs.OutcomeNamedList
import app.knotwork.design.components.dialogs.OutcomeTone

/**
 * Asks what to do with an imported pipeline whose id is already taken — by a
 * pipeline in the library, or by what a deleted pipeline left bound to it.
 *
 * Names the pipeline **already there** — the file's own `name` is its author's
 * to choose, and the dialog once named it, claiming the library held a
 * pipeline it did not — and lists everything bound to that id, because Replace
 * keeps the id and every binding runs the imported steps from then on.
 * Importing as a copy loses nothing, so it is the action drawn to be reached
 * for, as on the prompt importer's collision.
 *
 * @param collision The existing pipeline, its bindings and the incoming graph.
 * @param onReplace Replace the existing pipeline in place.
 * @param onImportAsCopy Save the file as a new pipeline beside it.
 * @param onDismiss Cancel the import.
 */
@Composable
internal fun PipelineCollisionDialog(
    collision: PipelineCollision,
    onReplace: () -> Unit,
    onImportAsCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bound = PipelineBindingsText.lines(collision.bindings).map { it.asString() }
    // No library pipeline holds the id when a deleted one left its bindings
    // behind: there is nothing to replace, only bindings the id would take over.
    val existingName = collision.existingName
    OutcomeDialog(
        tone = OutcomeTone.QUESTION,
        headline = if (existingName != null) {
            stringResource(R.string.orchestrator_library_import_collision_title_format, existingName.toDisplaySafe())
        } else {
            stringResource(R.string.orchestrator_library_import_orphan_title)
        },
        // The file's own name, as the file's claim — so a user who picked the
        // wrong file can tell, without the name standing in for the library's.
        body = stringResource(
            if (existingName != null) {
                R.string.orchestrator_library_import_collision_single_body
            } else {
                R.string.orchestrator_library_import_orphan_body
            },
            collision.incoming.name.toDisplaySafe(),
        ),
        namedList = bound.takeIf { it.isNotEmpty() }?.let { items ->
            OutcomeNamedList(
                heading = stringResource(
                    if (existingName != null) {
                        R.string.orchestrator_library_import_collision_bindings_heading
                    } else {
                        R.string.orchestrator_library_import_orphan_bindings_heading
                    },
                ),
                items = items,
                moreLabel = moreLabel(items.size),
            )
        },
        confirm = OutcomeAction(
            label = stringResource(R.string.orchestrator_library_import_collision_copy),
            onClick = onImportAsCopy,
            emphasis = OutcomeActionEmphasis.EMPHASISED,
        ),
        neutral = OutcomeAction(
            label = stringResource(
                if (existingName != null) {
                    R.string.orchestrator_library_import_collision_replace
                } else {
                    R.string.orchestrator_library_import_orphan_keep
                },
            ),
            onClick = onReplace,
        ),
        dismiss = OutcomeAction(label = stringResource(R.string.common_cancel), onClick = onDismiss),
        onDismissRequest = onDismiss,
    )
}

/**
 * Asks what to do with a bundle some of whose pipelines are already in the
 * library: one line per such pipeline, by its library name and with what runs
 * it, then Import as copies / Replace / Cancel. A schema-version note, when the
 * bundle needs one, joins the body.
 *
 * @param pending The prepared bundle; must have at least one collision.
 * @param onReplace Replace the colliding pipelines in place.
 * @param onImportAsCopies Save the whole bundle as new pipelines.
 * @param onDismiss Cancel the import.
 */
@Composable
internal fun BundleCollisionDialog(
    pending: PendingBundleImport,
    onReplace: () -> Unit,
    onImportAsCopies: () -> Unit,
    onDismiss: () -> Unit,
) {
    val count = pending.collisions.size
    val items = pending.collisions.map { PipelineBindingsText.summaryLine(it).asString() }
    // "Already in your library" is true only while every taken id is a library
    // pipeline; an id a deleted pipeline left bound is taken without being there.
    val allInLibrary = pending.collisions.all { it.existingName != null }
    val body = listOfNotNull(
        UiText(R.string.orchestrator_library_import_bundle_collision_body),
        UiText(R.string.orchestrator_library_import_bundle_schema_body).takeIf {
            pending.schemaMismatches.isNotEmpty()
        },
    )
    OutcomeDialog(
        tone = OutcomeTone.QUESTION,
        headline = pluralStringResource(
            if (allInLibrary) {
                R.plurals.orchestrator_library_import_bundle_collision_title
            } else {
                R.plurals.orchestrator_library_import_bundle_taken_title
            },
            count,
            count,
            pending.pipelines.size,
        ),
        body = UiText.Joined(body, " ").asString(),
        namedList = OutcomeNamedList(
            heading = stringResource(
                if (allInLibrary) {
                    R.string.orchestrator_library_import_bundle_collision_heading
                } else {
                    R.string.orchestrator_library_import_bundle_taken_heading
                },
            ),
            items = items,
            moreLabel = moreLabel(items.size),
        ),
        confirm = OutcomeAction(
            label = stringResource(R.string.orchestrator_library_import_bundle_copies),
            onClick = onImportAsCopies,
            emphasis = OutcomeActionEmphasis.EMPHASISED,
        ),
        neutral = OutcomeAction(
            label = stringResource(R.string.orchestrator_library_import_bundle_replace),
            onClick = onReplace,
        ),
        dismiss = OutcomeAction(label = stringResource(R.string.common_cancel), onClick = onDismiss),
        onDismissRequest = onDismiss,
    )
}

/** The "…and N more" line when [size] exceeds what the dialog spells out. */
@Composable
private fun moreLabel(size: Int): String? = (size - MAX_NAMED_LIST_ITEMS).takeIf { it > 0 }?.let {
    pluralStringResource(R.plurals.orchestrator_library_import_dropped_more, it, it)
}
