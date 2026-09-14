package app.knotwork.design.screens.onboarding

/**
 * Four-step scenario onboarding state for the catalog `OnboardingContent`
 * composable. The flow leads with **value** ("choose what the agent will do for
 * you") rather than the machine: the user picks a scenario, the app materialises
 * the matching pipeline and downloads exactly the model that scenario needs.
 */
enum class OnboardingStep(val pageIndex: Int, val indicatorLabel: String) {
    /** Welcome / brand pitch. Step 1. */
    Welcome(pageIndex = 0, indicatorLabel = "Welcome"),

    /** Value gallery — pick the scenario the agent should do for you. Step 2. */
    ChooseScenario(pageIndex = 1, indicatorLabel = "Choose a scenario"),

    /** Motivated download of the model the chosen scenario needs. Step 3. */
    Download(pageIndex = 2, indicatorLabel = "Download"),

    /** "Ready with your scenario" recap. Step 4. */
    Ready(pageIndex = 3, indicatorLabel = "Ready"),
}

/**
 * A curated onboarding scenario rendered as a value card on the gallery step.
 * The [id] is the stable key the `:app` layer resolves through
 * `OnboardingScenarioCatalog` into a preset / entry-surface / model recipe; the
 * copy (title, value line) is drawn from string resources in `OnboardingContent`
 * so the design-system layer stays localisation-friendly.
 *
 * Rendering order is the enum order: Styled Translation leads (on-identity),
 * Share Handler second, and the **featured** Virtual Companion is third — the
 * featured treatment must never make it the first impression (VISION §2).
 *
 * @property id stable scenario id (matches the bundled preset filename stem and
 *   the `:app` `OnboardingScenarioCatalog` id).
 * @property requiredModel the LiteRT model the scenario needs; pre-selected on
 *   the motivated-download step and framed as "needs this". Every curated
 *   scenario targets Gemma 4 E4B — the smaller E2B stays reachable under
 *   "Or choose another" as the genuinely lighter alternative.
 * @property featured `true` for the highlighted card (accent border + "Most
 *   popular" badge). Only Virtual Companion is featured.
 * @property bindsShareSurface `true` when set-up binds the system Share sheet to
 *   the materialised pipeline (Share Handler). Drives the card meta row and the
 *   "Ready" entry-surface / safety rows.
 */
enum class OnboardingScenario(
    val id: String,
    val requiredModel: OnboardingLiteRtModel,
    val featured: Boolean,
    val bindsShareSurface: Boolean,
) {
    /** On-device register/dialect-preserving translator. First / default card. */
    StyledTranslation(
        id = "styled_translation",
        requiredModel = OnboardingLiteRtModel.Gemma4E4B,
        featured = false,
        bindsShareSurface = false,
    ),

    /** Share-target capture → structured note in the inbox (HITL file write). */
    ShareHandler(
        id = "share_handler",
        requiredModel = OnboardingLiteRtModel.Gemma4E4B,
        featured = false,
        bindsShareSurface = true,
    ),

    /** Mood-routing companion. Featured, but the third card (identity guardrail). */
    VirtualCompanion(
        id = "virtual_companion_mood_router",
        requiredModel = OnboardingLiteRtModel.Gemma4E4B,
        featured = true,
        bindsShareSurface = false,
    ),
}

/**
 * LiteRT model offered on the download step. The id is the stable key the
 * `:app/OnboardingViewModel` persists to settings; the human-facing label and
 * size come from the catalog so the row presentation stays self-contained.
 */
enum class OnboardingLiteRtModel(val id: String, val displayName: String, val sizeLabel: String) {
    /** Gemma 4 E2B — smallest footprint; the lighter/faster alternative. */
    Gemma4E2B(id = "gemma_4_e2b", displayName = "Gemma 4 E2B", sizeLabel = "2.59 GB"),

    /** Gemma 4 E4B — the model every curated scenario targets. */
    Gemma4E4B(id = "gemma_4_e4b", displayName = "Gemma 4 E4B", sizeLabel = "3.66 GB"),

    /** User-supplied URL pointing at a `.litertlm` model bundle. */
    CustomUrl(id = "custom_url", displayName = "Custom URL…", sizeLabel = "paste"),
}

/**
 * Compact projection of the materialised scenario pipeline rendered on the
 * "Ready" step. The names render as monospace chips; [accentNodeName] gets the
 * `NodeIfCondition`-green outline to distinguish a routing node from the rest of
 * the flow.
 */
data class OnboardingDefaultPipelinePreview(
    val nodes: List<String>,
    val nodeCount: Int,
    val edgeCount: Int,
    val accentNodeName: String? = null,
)

/**
 * Top-level immutable input to `OnboardingContent`.
 *
 * @property step current page index (0..3).
 * @property selectedScenario the scenario the user has picked on the gallery
 * step, or `null` before a pick (the gallery CTA stays disabled until set).
 * @property isSettingUpScenario `true` while the host materialises the picked
 * scenario. Gallery interaction is suppressed and the CTA reads "Setting up…"
 * so the wait is visible — an unresponsive-looking CTA is what drives users to
 * tap again and again.
 * @property liteRtModel currently-selected model on the download step. Defaults
 * to the picked scenario's [OnboardingScenario.requiredModel]; the user can
 * still switch to an alternative under "Or choose another".
 * @property scenarioPreview compact recap of the materialised scenario pipeline
 * rendered on the "Ready" step. `null` is permitted for snapshot tests driving
 * earlier steps.
 * @property downloadProgress current download progress on the download step.
 * `null` = idle, `0f..1f` = active download.
 * @property downloadError last download / load failure message, or `null`.
 * @property installedModelId stable catalog id of the model already installed
 * (re-detected on entry or set after a successful download). When non-`null` the
 * download CTA becomes enabled.
 * @property customDownloadUrl user-supplied URL bound to the
 * [OnboardingLiteRtModel.CustomUrl] row.
 * @property isModelWarmed `true` once the host has loaded the model into the
 * inference engine. Gates the "Ready" CTA so the user never lands in a cold chat.
 * @property isCheckingAcceleration `true` while the host verifies that this
 * device's hardware acceleration actually runs the model, before falling back to
 * the safe path if it does not. Purely a copy change on the still-disabled
 * "Ready" CTA: the check costs about a second, and naming it beats a generic
 * "Preparing…" that looks like the app is stuck.
 */
data class OnboardingViewState(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val selectedScenario: OnboardingScenario? = null,
    val isSettingUpScenario: Boolean = false,
    val liteRtModel: OnboardingLiteRtModel = OnboardingLiteRtModel.Gemma4E4B,
    val scenarioPreview: OnboardingDefaultPipelinePreview? = null,
    val downloadProgress: Float? = null,
    val downloadError: String? = null,
    val installedModelId: String? = null,
    val customDownloadUrl: String = "",
    val isModelWarmed: Boolean = false,
    val isCheckingAcceleration: Boolean = false,
) {
    /**
     * Primary CTA enablement matrix. The download-step CTA serves three roles
     * depending on download state (the catalog renders distinct labels for
     * each), so enablement has to follow the matching state:
     *  - **Welcome** — always enabled (`Continue`);
     *  - **ChooseScenario** — enabled once a scenario is picked (`Set up …`),
     *    and disabled again while that set-up is in flight;
     *  - **Download — `Continue`** (model already on disk) — enabled;
     *  - **Download — `Downloading…`** (download in flight) — disabled;
     *  - **Download — `Download {name}`** (no install, no download) — enabled
     *    for the bundled presets; for the `CustomUrl` row it depends on
     *    [customDownloadUrl];
     *  - **Ready** — enabled once the host has warmed the inference handle
     *    (so chat works on the first send), *or* when warm-up has failed
     *    ([downloadError] set) so the CTA can offer a **Retry** instead of a
     *    permanently-disabled "Preparing…".
     */
    val isPrimaryCtaEnabled: Boolean
        get() = when (step) {
            OnboardingStep.Welcome -> true
            OnboardingStep.ChooseScenario -> selectedScenario != null && !isSettingUpScenario
            OnboardingStep.Download -> when {
                installedModelId != null -> true
                downloadProgress != null -> false
                liteRtModel == OnboardingLiteRtModel.CustomUrl -> customDownloadUrl.isNotBlank()
                else -> true
            }
            OnboardingStep.Ready -> isModelWarmed || downloadError != null
        }

    /**
     * `true` on the "Ready" step when warm-up has failed — the CTA becomes an
     * enabled **Retry** and the recap surfaces the error banner, so a failed
     * warm never dead-ends the flow behind a disabled "Preparing…".
     */
    val isReadyWarmUpRetryable: Boolean get() = step == OnboardingStep.Ready && !isModelWarmed && downloadError != null

    /** Convenience: are we on the final step? */
    val isFinalStep: Boolean get() = step == OnboardingStep.Ready
}

/**
 * Stable callback bundle accepted by `OnboardingContent`.
 */
@Suppress("LongParameterList") // Mirrors user-visible affordances; collapsing further hides intent.
class OnboardingCallbacks(
    val onNext: () -> Unit = {},
    val onSkip: () -> Unit = {},
    val onFinish: () -> Unit = {},
    /** Invoked when the user selects a scenario card on the gallery step. */
    val onScenarioPick: (OnboardingScenario) -> Unit = {},
    /**
     * Invoked by the gallery CTA ("Set up {Scenario}"). The host materialises
     * the picked scenario's pipeline, binds its surface, and advances to the
     * motivated-download step.
     */
    val onSetUpScenario: () -> Unit = {},
    /**
     * Invoked by the trailing "Start from scratch" escape card. The host
     * completes onboarding without materialising a scenario and lands the user
     * in the app to build their own pipeline.
     */
    val onStartFromScratch: () -> Unit = {},
    val onLiteRtModelPick: (OnboardingLiteRtModel) -> Unit = {},
    /**
     * Invoked by the "Ready" CTA when warm-up has failed
     * ([OnboardingViewState.isReadyWarmUpRetryable]) — the host clears the
     * error and re-runs the model warm-up.
     */
    val onRetryWarmUp: () -> Unit = {},
    /**
     * Invoked when the user taps the download-step CTA to begin a model
     * download. The host resolves the picked model to its URL / filename and
     * starts the download stream.
     */
    val onStartDownload: () -> Unit = {},
    /**
     * Invoked on every character change in the `Custom URL` input. The host
     * stores the latest value in `customDownloadUrl` and uses it when
     * [onStartDownload] fires for the `CustomUrl` row.
     */
    val onCustomDownloadUrlChanged: (String) -> Unit = {},
    /**
     * Invoked from the Ready step's documentation line. The host opens the FAQ
     * in a browser — the one moment onboarding can name the documentation to a
     * user who installed from a store listing that has no room for a URL.
     */
    val onOpenDocumentation: () -> Unit = {},
)

/** Convenience factory returning a callbacks bundle that ignores every event. */
fun noopOnboardingCallbacks(): OnboardingCallbacks = OnboardingCallbacks()
