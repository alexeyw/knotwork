package app.knotwork.android.data.local.models

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for one model call a pipeline node made — the record that makes a
 * run checkable. Added in schema v67 (`MIGRATION_66_67`).
 *
 * Two kinds of rows, discriminated by [engine]:
 *
 * - **`LOCAL`** — an on-device call: the full [prompt] and [output] with their
 *   hashes, the sampler and seed, the model file and its hash, the backend and
 *   the context window. Repeating [prompt] with these values on the same device
 *   reproduces [output].
 * - **`CLOUD`** — a call to a hosted model: only [cloudProvider] and
 *   [cloudModel]; every on-device column is `null`.
 *
 * The rows share the run's `seq` numbering with `trace_steps`, so the two tables
 * merge back into one ordered trace; they are written in the same transaction as
 * the trace rows buffered with them.
 *
 * @property id Auto-generated row id.
 * @property runId The run the call belongs to. Cascade-deletes with its
 *   `pipeline_runs` row, so run retention removes the calls with the rest of the trace.
 * @property sessionId The chat session the run belongs to. Cascade-deletes with
 *   the session.
 * @property seq Position of the call in the run's trace sequence.
 * @property timestamp Wall-clock time the call was recorded.
 * @property depth Pipeline-nesting level of the run.
 * @property nodeId The node that made the call.
 * @property nodeType The node's type name.
 * @property visit Zero-based visit index of the node within its run invocation.
 * @property callIndex Zero-based index of the call within the visit.
 * @property engine [ENGINE_LOCAL] or [ENGINE_CLOUD].
 * @property seed Sampler seed of a local call.
 * @property temperature Sampler temperature of a local call.
 * @property topK Sampler top-k of a local call.
 * @property topP Sampler top-p of a local call.
 * @property modelPath Model file path of a local call.
 * @property modelSha256 Registry hash of that file, `null` when it was not known.
 * @property backend `LocalBackend` key a local call actually ran on.
 * @property contextWindow Engine context window (tokens) of a local call.
 * @property hadImage Whether a local call sent an image with its prompt.
 * @property prompt The full prompt of a local call.
 * @property output The full output of a local call.
 * @property promptSha256 SHA-256 of [prompt].
 * @property outputSha256 SHA-256 of [output].
 * @property cloudProvider Provider id of a cloud call.
 * @property cloudModel Model id of a cloud call, when known.
 * @property durationMs How long a local call's stream took; added in v68
 *   (`MIGRATION_67_68`), `null` for a call recorded before it.
 */
@Entity(
    tableName = "model_calls",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("sessionId"),
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PipelineRunEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("runId"),
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["runId"])],
)
data class ModelCallEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val runId: String,
    val sessionId: String,
    val seq: Long,
    val timestamp: Long,
    val depth: Int,
    val nodeId: String,
    val nodeType: String,
    val visit: Int,
    val callIndex: Int,
    val engine: String,
    val seed: Int? = null,
    val temperature: Double? = null,
    val topK: Int? = null,
    val topP: Double? = null,
    val modelPath: String? = null,
    val modelSha256: String? = null,
    val backend: String? = null,
    val contextWindow: Int? = null,
    val hadImage: Boolean = false,
    val prompt: String? = null,
    val output: String? = null,
    val promptSha256: String? = null,
    val outputSha256: String? = null,
    val cloudProvider: String? = null,
    val cloudModel: String? = null,
    val durationMs: Long? = null,
) {
    /** The [engine] discriminator values. */
    companion object {
        /** [engine] of an on-device call. */
        const val ENGINE_LOCAL: String = "LOCAL"

        /** [engine] of a call to a hosted model. */
        const val ENGINE_CLOUD: String = "CLOUD"
    }
}
