package app.knotwork.android.domain.models

/**
 * Type-safe identifier for the on-device LiteRT execution backend.
 *
 * Each entry carries the wire key that is persisted in `DataStore` under
 * `local_model_backend` and surfaced to the user in the Settings screen. Owning the
 * mapping here lets every consumer (settings UI, repository default, LiteRT engine
 * factory) round-trip through the same typed enum and dispatch with exhaustive `when`.
 *
 * The wire form is preserved as the existing `"CPU" / "GPU" / "NPU"` strings so the
 * refactor does not invalidate values already persisted on user devices.
 */
enum class LocalBackend(
    /** Wire key persisted in `DataStore` under `local_model_backend`. */
    val key: String,
    /**
     * Whether the user can choose this backend and the engine runs on it. A
     * withdrawn backend stays an entry so the records of runs made on it still
     * read back, and a stored choice of it runs on [CPU] ([runnableFromKey]).
     */
    val offered: Boolean,
) {
    /** CPU execution via XNNPACK; the safe default supported on every device. */
    CPU("CPU", offered = true),

    /** GPU delegate; faster on supported hardware. */
    GPU("GPU", offered = true),

    /**
     * Neural Processing Unit delegate — **withdrawn**. LiteRT-LM needs a vendor
     * dispatch library and a model compiled for the chip; the app ships neither,
     * so an "NPU" engine ran on CPU three times slower, and its initialisation ran
     * the E4B model out of memory. Kept for the run records that name it.
     */
    NPU("NPU", offered = false),
    ;

    /** Owns the wire-key ↔ enum parsing rule used by the settings layer. */
    companion object {
        /**
         * Parses a wire/UI backend key into a typed [LocalBackend].
         *
         * Unknown keys and `null` return `null` — callers decide whether the absence
         * means "fall back to default" ([CPU]) or "raise a validation error".
         */
        fun fromKey(key: String?): LocalBackend? = entries.firstOrNull { it.key == key }

        /** The backends the user can choose, in the order the settings list them. */
        val selectable: List<LocalBackend> = entries.filter { it.offered }

        /**
         * The backend a stored choice runs on: the choice itself when it is offered,
         * otherwise [CPU] — for a withdrawn backend (an NPU choice made before it was
         * withdrawn), an unknown key, or none.
         *
         * @param key The stored wire key, or `null`.
         * @return The backend to initialise.
         */
        fun runnableFromKey(key: String?): LocalBackend = fromKey(key)?.takeIf { it.offered } ?: CPU
    }
}
