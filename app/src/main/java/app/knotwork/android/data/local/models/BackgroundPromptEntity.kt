package app.knotwork.android.data.local.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The prompt of one queued background run, kept in the encrypted database so
 * the background runtime's own store — unencrypted — never holds it.
 *
 * @property id The id the background request carries in its input.
 * @property prompt The prompt the run will execute.
 * @property createdAt When the prompt was stored, epoch millis.
 */
@Entity(tableName = "background_prompts")
data class BackgroundPromptEntity(@PrimaryKey val id: String, val prompt: String, val createdAt: Long)
