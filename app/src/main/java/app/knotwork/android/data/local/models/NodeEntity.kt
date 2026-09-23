package app.knotwork.android.data.local.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.knotwork.android.domain.models.NodeContextConfig

/**
 * Room entity representing a single node in a pipeline.
 *
 * @property id The unique identifier of the node.
 * @property pipelineId The ID of the pipeline this node belongs to.
 * @property type The type of the node as a string.
 * @property x The X coordinate of the node on the canvas.
 * @property y The Y coordinate of the node on the canvas.
 * @property label The display label of the node.
 * @property toolName The optional tool name associated with this node.
 * @property targetPipelineId The id of the pipeline this node runs as a sub-pipeline when its
 * type is `PIPELINE`; `null` for every other node type.
 * @property skillId The id of the skill this node runs when its type is `SKILL`; `null` for
 * every other node type.
 * @property modelPath An optional path to a specific model file (.tflite) for this node.
 * @property conditionComplexity Threshold for task complexity.
 * @property conditionKeywords Comma-separated keywords for condition.
 * @property conditionPrompt Free-form prompt for condition classification.
 * @property fallbackClass INTENT_ROUTER: class an unmatched answer routes to.
 * @property quickReplies CLARIFICATION: comma-separated answer chips.
 * @property alwaysConfirm TOOL / SKILL: ask for approval on every call through this node.
 * @property maxSubtasks DECOMPOSITION: cap on the generated sub-task list.
 * @property stopOnError QUEUE_PROCESSOR: fail the run on the first failing item.
 * @property conditionHasImage When `true` on an IF_CONDITION node, branch True whenever the run
 *   input carries an image attachment (deterministic, no-LLM check). Nullable `INTEGER` column;
 *   `null` and `false` are treated identically.
 * @property systemPrompt An optional system prompt to configure the behavior of the node.
 * @property cloudProvider An optional provider for a CLOUD node.
 * @property clarificationTimeoutMs Timeout (in ms) for a CLARIFICATION node before it falls back
 * to a default answer. `null` means the engine's default is used.
 * @property contextConfig Per-node selection of pipeline context blocks
 * (chat history, original task, previous node output, long-term memory,
 * tool results) injected on every execution. Stored as JSON via the
 * `NodeContextConfig` Room TypeConverter; defaults to all flags `true`
 * for backward compatibility with older rows that predate this column.
 * @property configJson Optional per-type `NodeConfig` payload as
 * a JSON blob. `null` for legacy rows; the
 * editor lazily derives a default from the flat fields above on first
 * edit and writes the encoded payload back here on save.
 */
@Entity(
    tableName = "pipeline_nodes",
    foreignKeys = [
        ForeignKey(
            entity = PipelineEntity::class,
            parentColumns = ["id"],
            childColumns = ["pipelineId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pipelineId")],
)
data class NodeEntity(
    @PrimaryKey
    val id: String,
    val pipelineId: String,
    val type: String,
    val x: Float,
    val y: Float,
    val label: String,
    val toolName: String? = null,
    val targetPipelineId: String? = null,
    val skillId: String? = null,
    val modelPath: String? = null,
    val conditionComplexity: Int? = null,
    val conditionKeywords: String? = null,
    val conditionPrompt: String? = null,
    val conditionHasImage: Boolean? = null,
    val systemPrompt: String? = null,
    val cloudProvider: String? = null,
    val clarificationTimeoutMs: Long? = null,
    val fallbackClass: String? = null,
    val quickReplies: String? = null,
    val alwaysConfirm: Boolean? = null,
    val maxSubtasks: Int? = null,
    val stopOnError: Boolean? = null,
    @ColumnInfo(name = "context_config")
    val contextConfig: NodeContextConfig = NodeContextConfig.ALL_ENABLED,
    @ColumnInfo(name = "config_json")
    val configJson: String? = null,
)
