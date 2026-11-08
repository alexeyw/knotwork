package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.PipelinePreset
import kotlinx.coroutines.flow.Flow

/**
 * Repository over the two-tier pipeline-preset catalogue:
 *
 * - **Bundled presets** are read-only and ship inside the APK under
 *   `assets/presets/pipelines`. They form the curated starter catalogue
 *   surfaced on first launch.
 * - **User presets** are mutable and live in the `pipeline_presets` Room
 *   table (schema v24). They are created when the user runs
 *   `SavePipelineAsPresetUseCase` against the currently-edited graph.
 *
 * The split is intentional: bundled presets cannot be edited or deleted by
 * the user, which keeps the starter catalogue stable across sessions and
 * lets us ship updated copies via app updates. User presets are owned by
 * the user and freely mutable through this repository.
 */
interface PipelinePresetRepository {

    /**
     * Observes the catalogue of read-only presets bundled with the APK.
     *
     * Implementations may lazily decode the JSON files on first
     * subscription and cache the result for subsequent subscribers — the
     * contents do not change at runtime. Each emission contains
     * [PipelinePreset]s with `isBundled = true`.
     *
     * The presets are in the language the interface is shown in — their
     * names and the other texts a person reads; what a model reads stays as
     * written — and the flow emits again when that language changes.
     *
     * Malformed asset files are skipped (logged as warnings) rather than
     * failing the whole flow, so a single bad file does not hide the
     * remaining catalogue from the picker.
     *
     * Presets flagged [PipelinePreset.isInternal] are **excluded**: they are
     * building blocks composed by another preset, not catalogue entries the
     * user picks. They remain reachable through [getPresetById], which is what
     * `LoadPipelineFromPresetUseCase` uses to seed a composed preset's
     * `PIPELINE` targets.
     *
     * @return A [Flow] that emits the user-facing bundled catalogue.
     */
    fun getBundledPresets(): Flow<List<PipelinePreset>>

    /**
     * Observes the catalogue of user-saved presets persisted in Room.
     *
     * Re-emits on every insert / delete so the picker UI stays in sync
     * with the source of truth. Each emission contains [PipelinePreset]s
     * with `isBundled = false`.
     *
     * @return A [Flow] that emits the user catalogue.
     */
    fun getUserPresets(): Flow<List<PipelinePreset>>

    /**
     * Resolves a single preset by id, looking in the bundled catalogue
     * first and falling back to the user catalogue. A bundled preset comes in
     * the language the interface is shown in now.
     *
     * Used by `LoadPipelineFromPresetUseCase` to materialise the chosen
     * preset into a concrete [PipelinePreset] before id regeneration.
     * Resolves [PipelinePreset.isInternal] presets too — unlike
     * [getBundledPresets] — because a composed preset's `PIPELINE` targets are
     * seeded through exactly this lookup.
     *
     * @param id The stable preset id.
     * @return The matching preset, or `null` if no preset with [id] exists
     *   in either catalogue.
     */
    suspend fun getPresetById(id: String): PipelinePreset?

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
    suspend fun saveUserPreset(preset: PipelinePreset)

    /**
     * Deletes the user preset with [id]. No-op when no matching row
     * exists; bundled presets are unaffected because they are not stored
     * in Room.
     *
     * @param id The id of the user preset to delete.
     */
    suspend fun deleteUserPreset(id: String)
}
