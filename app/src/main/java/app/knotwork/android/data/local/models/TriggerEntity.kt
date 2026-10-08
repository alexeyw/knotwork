package app.knotwork.android.data.local.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room row for a user-defined automation trigger (`triggers` table, v46).
 *
 * The activation condition is stored as a compact JSON string in
 * [conditionJson] (produced by
 * [app.knotwork.android.domain.triggerio.TriggerConditionCodec]) rather than
 * spread across type-specific columns, so the schema stays narrow and a new
 * condition shape needs no migration. [enabled] is indexed because the
 * active-trigger query (the scheduler sync's hot path) filters on it.
 *
 * @property id Stable unique id (UUID), primary key.
 * @property name Human-readable label.
 * @property pipelineId Bound pipeline id, or `null` when the trigger is inert
 *   (unbound). No foreign key: a trigger may outlive its pipeline, and the fire
 *   path auto-disables a trigger whose pipeline has been deleted.
 * @property prompt Input message fed to the bound pipeline on each fire.
 * @property conditionJson The serialised [app.knotwork.android.domain.models.TriggerCondition].
 * @property enabled Whether the trigger is active (indexed).
 * @property armed Edge-detection latch for event conditions (charging/network):
 *   `true` means ready to fire on the next transition into the satisfied state.
 * @property createdAt Epoch-millis the trigger was created.
 * @property lastFiredAt Epoch-millis of the last fire, or `null` if never fired.
 * @property sessionId Id of the chat session the trigger's background runs land
 *   in (added v46), or `null` until the first fire. No foreign key: a deleted
 *   session is detected at fire time and a fresh one is bound, so the column may
 *   reference a row that no longer exists.
 * @property activatedAt Epoch-millis the trigger last became active (enabled and
 *   bound), added v70; `null` for a row last activated before v70.
 * @property staleNoticeFor The sign-of-life moment the last "has not checked in"
 *   notice was about (added v70), or `null` when none was sent.
 */
@Entity(tableName = "triggers")
data class TriggerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val pipelineId: String?,
    val prompt: String,
    val conditionJson: String,
    @ColumnInfo(index = true) val enabled: Boolean,
    val armed: Boolean,
    val createdAt: Long,
    val lastFiredAt: Long?,
    val sessionId: String? = null,
    val activatedAt: Long? = null,
    val staleNoticeFor: Long? = null,
)
