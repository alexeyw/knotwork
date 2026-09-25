package app.knotwork.android.domain.constants

/**
 * Canonical mapping between the catalog-side `OnboardingLiteRtModel` ids
 * (which are stable UI keys and stay decoupled from any download URL)
 * and the actual on-disk filename + HuggingFace download URL used by the
 * data-layer `ModelDownloadManager`.
 *
 * Used exclusively by the onboarding flow
 * ([app.knotwork.android.presentation.ui.onboarding.OnboardingViewModel]):
 *  - to look up the expected `fileName` so it can call
 *    [app.knotwork.android.domain.repositories.LocalModelRepository.isInstalled]
 *    when the user re-enters onboarding after a previous install;
 *  - to start the download via
 *    [app.knotwork.android.domain.repositories.ModelDownloadManager.downloadModel]
 *    when the user taps the step-2 CTA.
 *
 * The Models screen (`ModelsViewModel.availablePresets`) keeps its own
 * list because the two surfaces evolve independently: onboarding ships a
 * curated two-model picker plus a custom-URL row, while the Models screen
 * can grow more presets without forcing onboarding to widen.
 */
object OnboardingModelCatalog {

    /**
     * Stable catalog id of the recommended bundled model — the entry that
     * gets the "Recommended" pill on step 2.
     */
    const val ID_GEMMA_4_E2B: String = "gemma_4_e2b"

    /** Stable catalog id of the larger Gemma 4 variant. */
    const val ID_GEMMA_4_E4B: String = "gemma_4_e4b"

    /**
     * Stable catalog id for the "Custom URL" row. The accompanying URL is
     * not bundled — the user supplies it through the state-bound
     * `customDownloadUrl` field.
     */
    const val ID_CUSTOM_URL: String = "custom_url"

    /**
     * Resolved download metadata for a single bundled preset.
     *
     * @property id stable catalog id (matches `OnboardingLiteRtModel.id`).
     * @property fileName the local filename written to disk by the
     * download manager and persisted as `LocalModel.name`.
     * @property downloadUrl absolute HuggingFace URL of the `.litertlm`
     * bundle.
     */
    data class Entry(val id: String, val fileName: String, val downloadUrl: String)

    /**
     * Two presets the step-2 picker exposes. The order is unimportant —
     * the catalog enum on the design-system side is the rendering order.
     */
    val PRESETS: List<Entry> = listOf(
        Entry(
            id = ID_GEMMA_4_E2B,
            fileName = "gemma-4-E2B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/" +
                "resolve/main/gemma-4-E2B-it.litertlm",
        ),
        Entry(
            id = ID_GEMMA_4_E4B,
            fileName = "gemma-4-E4B-it.litertlm",
            downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/" +
                "resolve/main/gemma-4-E4B-it.litertlm",
        ),
    )

    /**
     * Returns the catalog entry for [id], or `null` for `custom_url` /
     * any unknown id. The custom path is intentionally not represented
     * here because both its URL and filename come from user input, checked by
     * [app.knotwork.android.domain.models.CustomModelLink.parse].
     */
    fun entryById(id: String): Entry? = PRESETS.firstOrNull { it.id == id }
}
