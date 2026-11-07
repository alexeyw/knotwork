package app.knotwork.android.presentation.ui.common

import app.knotwork.design.components.pipelineeditor.text
import app.knotwork.design.components.pipelineeditor.NodeType as CatalogNodeType

/**
 * The name of a node type in the interface language — a run reports the type's
 * code (`LITE_RT`), and the task monitor and the metrics screen show its name. A
 * code no type answers to is shown as it came.
 *
 * @param code The node type's code.
 * @return The type's name.
 */
internal fun nodeTypeName(code: String): UiText =
    CatalogNodeType.entries.firstOrNull { it.name == code }?.let { UiText(it.text.name) } ?: UiText.Dynamic(code)
