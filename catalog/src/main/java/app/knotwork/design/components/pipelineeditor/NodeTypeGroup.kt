package app.knotwork.design.components.pipelineeditor

import androidx.annotation.StringRes
import app.knotwork.design.R

/**
 * What a node type is for, as the node-type picker groups the types.
 *
 * The groups are a reading aid for someone choosing a node, not a property the
 * engine knows about: a pipeline never consults them. Their order is the
 * picker's order — start and finish first, because every pipeline needs both,
 * then the steps that run a model, the ones that ask or act, the branches, and
 * the nodes that work through a list.
 *
 * @property title The group's heading.
 */
enum class NodeTypeGroup(@StringRes val title: Int) {
    /** Where a run begins and where its answer leaves. */
    START_AND_FINISH(R.string.knotwork_node_picker_group_io),

    /** One inference step, on the phone or in the cloud, or a skill's. */
    RUN_A_MODEL(R.string.knotwork_node_picker_group_model),

    /** Asking the user, calling a tool, or running another pipeline. */
    ASK_AND_ACT(R.string.knotwork_node_picker_group_act),

    /** The nodes whose outcome decides which edge the run follows. */
    BRANCH(R.string.knotwork_node_picker_group_branch),

    /** Making a list of subtasks, walking it, and condensing what it produced. */
    WORK_THROUGH_A_LIST(R.string.knotwork_node_picker_group_list),
    ;

    /** This group's node types, in [NodeType] declaration order. */
    val types: List<NodeType>
        get() = NodeType.entries.filter { it.group == this }
}

/**
 * The picker group [this] node type is listed under.
 *
 * Exhaustive over [NodeType], so a type added without a group does not compile
 * and can never fall out of the picker.
 */
val NodeType.group: NodeTypeGroup
    get() = when (this) {
        NodeType.INPUT, NodeType.OUTPUT -> NodeTypeGroup.START_AND_FINISH
        NodeType.LITE_RT, NodeType.CLOUD, NodeType.SKILL -> NodeTypeGroup.RUN_A_MODEL
        NodeType.CLARIFICATION, NodeType.TOOL, NodeType.PIPELINE -> NodeTypeGroup.ASK_AND_ACT
        NodeType.INTENT_ROUTER, NodeType.IF_CONDITION, NodeType.EVALUATION -> NodeTypeGroup.BRANCH
        NodeType.DECOMPOSITION, NodeType.QUEUE_PROCESSOR, NodeType.SUMMARY -> NodeTypeGroup.WORK_THROUGH_A_LIST
    }
