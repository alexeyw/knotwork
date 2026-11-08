package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.domain.models.PromptPreset
import kotlinx.coroutines.flow.Flow

/**
 * Repository over the two-tier prompt-preset catalogue:
 *
 * - **Bundled presets** are read-only and ship inside the APK under
 *   `assets/presets/prompts`. They form the curated starter catalogue
 *   surfaced in the Prompt Library on first launch.
 * - **User presets** are mutable and live in the `prompt_presets` Room
 *   table (schema v25). They are created when the user runs
 *   `SavePromptAsPresetUseCase` against the system prompt currently being
 *   edited in `NodeConfigSheet`.
 *
 * The split is intentional: bundled presets cannot be edited or deleted by
 * the user, which keeps the starter catalogue stable across sessions and
 * lets us ship updated copies via app updates. User presets are owned by
 * the user and freely mutable through this repository.
 */
interface PromptPresetRepository {

    /**
     * Observes the catalogue of read-only presets bundled with the APK.
     *
     * Implementations may lazily decode the JSON files on first
     * subscription and cache the result for subsequent subscribers — the
     * contents do not change at runtime. Each emission contains
     * [PromptPreset]s with `isBundled = true`.
     *
     * The presets are in the language the interface is shown in — their
     * names and the other texts a person reads; what a model reads stays as
     * written — and the flow emits again when that language changes.
     *
     * Malformed asset files are skipped (logged as warnings) rather than
     * failing the whole flow, so a single bad file does not hide the
     * remaining catalogue from the picker.
     *
     * @return A [Flow] that emits the bundled catalogue.
     */
    fun getBundledPresets(): Flow<List<PromptPreset>>

    /**
     * Observes the catalogue of user-saved presets persisted in Room.
     *
     * Re-emits on every insert / delete so the picker UI stays in sync
     * with the source of truth. Each emission contains [PromptPreset]s
     * with `isBundled = false`.
     *
     * @return A [Flow] that emits the user catalogue.
     */
    fun getUserPresets(): Flow<List<PromptPreset>>

    /**
     * Observes the catalogue of presets (both bundled and user-saved)
     * targeting the given [nodeType].
     *
     * Convenience accessor used by the Prompt Library when it is opened
     * from `NodeConfigSheet` — at that point the active node's type is
     * known and only matching presets should be visible.
     *
     * @param nodeType The node type to filter by.
     * @return A [Flow] emitting bundled + user presets whose
     *   [PromptPreset.nodeType] equals [nodeType].
     */
    fun getPresetsForType(nodeType: NodeType): Flow<List<PromptPreset>>

    /**
     * Resolves a single preset by id, looking in the bundled catalogue
     * first and falling back to the user catalogue. A bundled preset comes in
     * the language the interface is shown in now.
     *
     * @param id The stable preset id.
     * @return The matching preset, or `null` if no preset with [id] exists
     *   in either catalogue.
     */
    suspend fun getPresetById(id: String): PromptPreset?

    /**
     * Persists [preset] to the user catalogue. Replaces any existing row
     * with the same id (insert-or-update semantics).
     *
     * Implementations must reject [preset]s with `isBundled = true` —
     * bundled presets are read-only by contract.
     *
     * @param preset The user preset to save; must have `isBundled = false`.
     * @throws IllegalArgumentException if `preset.isBundled` is `true`.
     */
    suspend fun saveUserPreset(preset: PromptPreset)

    /**
     * Deletes the user preset with [id]. No-op when no matching row
     * exists; bundled presets are unaffected because they are not stored
     * in Room.
     *
     * @param id The id of the user preset to delete.
     */
    suspend fun deleteUserPreset(id: String)
}
