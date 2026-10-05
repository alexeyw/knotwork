package app.knotwork.android.data.local.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity persisting one pipeline run in the `pipeline_runs` table.
 *
 * Mirrors the domain model `PipelineRun`; enum-typed fields (`origin`,
 * `status`) are stored as their `name` strings for forward-compatible,
 * human-debuggable rows. There is deliberately **no** foreign key on
 * [sessionId]: a run record is created at enqueue time, which for
 * scheduler-originated tasks precedes the creation of the chat-session row
 * itself (the session is auto-created when the first message is saved).
 * This follows the `chat_messages` precedent; cleanup on session deletion is
 * explicit, inside the `ChatDao.deleteSessionCompletely` transaction.
 *
 * @property id Unique run id (UUID), equal to the id of the originating agent task.
 * @property sessionId Id of the owning chat session (indexed, no FK — see above).
 * @property pipelineId Id of the resolved pipeline; `null` while the run is queued.
 * @property origin `RunOrigin` name: what triggered the run (`CHAT` / `SCHEDULER`).
 * @property status `PipelineRunStatus` name (indexed — the orphan sweep and the
 *   reattach protocol both query by status).
 * @property currentNodeId Id of the last graph node that started executing.
 * @property startedAt Epoch millis when the run was enqueued.
 * @property finishedAt Epoch millis of the terminal transition; `null` while active.
 * @property errorMessage Failure / interruption reason for FAILED and INTERRUPTED runs.
 * @property graphContentHash Content hash of the executing graph, captured at the
 *   RUNNING transition; `null` while queued.
 * @property userPrompt The user message that started the run, captured at enqueue
 *   time for checkpoint resume; `null` only for rows written before the column
 *   existed (such runs cannot be resumed).
 * @property parentRunId Id of the parent run when this row is a sub-pipeline run
 *   spawned by a `PIPELINE` node; `null` for a top-level run. A self-referential
 *   foreign key with `ON DELETE CASCADE` so deleting a parent (retention)
 *   removes the whole sub-tree; indexed for the child-run lookups that build the
 *   run tree. Session-level queries filter to `parentRunId IS NULL` so children
 *   never surface as standalone runs in the reattach / status-card / activity
 *   paths — children are internal and only ever resumed through their root.
 * @property hadImage `true` when the originating message carried an image attachment.
 *   Persisted so a resumed run (which never re-delivers the image) can still report
 *   image presence to IF/router nodes executing live past the resume point. Defaults
 *   to `false`.
 * @property stepsSpent Node executions charged to this run **tree**, accumulated
 *   across every attempt of the logical run. Written only on a tree root; a child
 *   run charges its root, so a child row keeps `0`. `@ColumnInfo(defaultValue)`
 *   is mandatory here and must match the `DEFAULT` in the migration exactly —
 *   Room's `TableInfo` comparison sees both sides, and the one existing column
 *   that got this wrong (`hadImage`, v48→49) passes only because its expected
 *   side happens to be the null one.
 * @property tokensSpent Tokens charged to this run tree, on the same root-keyed
 *   basis as [stepsSpent].
 * @property stepCeilingExtensions How many extra portions of the step ceiling
 *   the user has granted this run tree by answering a ceiling pause. Root-keyed
 *   like [stepsSpent], and `NOT NULL DEFAULT 0` for the same reason: a run that
 *   never asked has been granted nothing, and that is a number, not an absence.
 * @property tokenCeilingExtensions Extra portions of the token ceiling granted
 *   to this run tree, on the same basis as [stepCeilingExtensions].
 * @property terminationReason `RunTerminationKind` name explaining why the run
 *   stopped, when the app itself decided to stop it; `null` otherwise and for
 *   rows written before the column existed. Decoded permissively: an unknown
 *   string reads back as `null` rather than throwing, unlike [origin] and
 *   [status], because an unclassified termination is a legitimate state while
 *   an unknown origin or status is data corruption.
 * @property headerSeed The run seed of a root run's header (`RunHeader.seed`).
 *   The seven `header*` columns hold one `RunHeader`, written together once when
 *   the run starts (`MIGRATION_66_67`); all `null` for a sub-pipeline's row, a
 *   run not started yet and every row written before headers existed.
 * @property headerTemperature The sampler temperature of the header.
 * @property headerTopK The sampler top-k of the header.
 * @property headerTopP The sampler top-p of the header.
 * @property headerAppVersion The app version the run started on.
 * @property headerRuntimeVersion The inference runtime and its version.
 * @property headerDevice The device descriptor.
 */
@Entity(
    tableName = "pipeline_runs",
    foreignKeys = [
        ForeignKey(
            entity = PipelineRunEntity::class,
            parentColumns = arrayOf("id"),
            childColumns = arrayOf("parentRunId"),
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["status"]),
        Index(value = ["parentRunId"]),
    ],
)
data class PipelineRunEntity(
    @PrimaryKey
    val id: String,
    val sessionId: String,
    val pipelineId: String?,
    val origin: String,
    val status: String,
    val currentNodeId: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val errorMessage: String?,
    val graphContentHash: String?,
    val userPrompt: String? = null,
    val parentRunId: String? = null,
    val hadImage: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val stepsSpent: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val tokensSpent: Int = 0,
    val terminationReason: String? = null,
    @ColumnInfo(defaultValue = "0")
    val stepCeilingExtensions: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val tokenCeilingExtensions: Int = 0,
    val headerSeed: Int? = null,
    val headerTemperature: Double? = null,
    val headerTopK: Int? = null,
    val headerTopP: Double? = null,
    val headerAppVersion: String? = null,
    val headerRuntimeVersion: String? = null,
    val headerDevice: String? = null,
)
