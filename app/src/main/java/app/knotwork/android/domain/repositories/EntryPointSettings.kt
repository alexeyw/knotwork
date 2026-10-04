package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * Which pipeline each entry point runs: the default pipeline, the share target, the Quick Settings
 * tile and external automation — with the share target's one-chat preference and the external
 * automation contract's master switch.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface EntryPointSettings {

    /**
     * A [Flow] representing the id of the pipeline the user has marked as
     * default. `null` means no explicit choice — chats without their own
     * binding then have no pipeline to execute against and the task queue
     * fails such runs with an explicit error (it never silently picks an
     * arbitrary pipeline from the library).
     *
     * Set on first launch by `InitializeAppUseCase` to the seeded
     * `Default System Pipeline` so the default is unambiguous from the
     * start. Cleared automatically when the marked pipeline is deleted.
     */
    val defaultPipelineId: Flow<String?>

    /**
     * Updates the user-marked default pipeline id. Pass `null` to clear
     * the marker (unbound chats then refuse to run until a new default
     * is marked or the chat is bound explicitly).
     *
     * @param pipelineId Pipeline id to mark as default, or `null` to clear.
     */
    suspend fun setDefaultPipelineId(pipelineId: String?)

    /**
     * A [Flow] of the pipeline id bound to the **share target** entry surface.
     * `null` means the surface is unbound: sharing text/an image into the app
     * does nothing until the user picks a pipeline (privacy-first default).
     *
     * Like [defaultPipelineId] this is a user binding, not a tunable preference,
     * so it is **never** touched by `resetToRecommendedDefaults`. It is cleared
     * automatically when the bound pipeline is deleted.
     */
    val shareTargetPipelineId: Flow<String?>

    /**
     * Updates the pipeline bound to the share target. Pass `null` to unbind
     * (sharing then becomes inert again).
     *
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding.
     */
    suspend fun setShareTargetPipelineId(pipelineId: String?)

    /**
     * `true` when content shared into the app should accumulate in a single
     * reusable **Shared** chat instead of opening a fresh chat per share.
     *
     * Unlike [shareTargetPipelineId] this is a tunable behaviour preference (not
     * a user binding), so `resetToRecommendedDefaults` restores it to
     * [SettingsDefaults.SHARE_REUSE_SESSION_DEFAULT] (`true`).
     */
    val shareReuseSession: Flow<Boolean>

    /**
     * Updates the "keep shares in one chat" preference.
     *
     * @param reuse `true` to append every share to the single Shared chat,
     *   `false` to start a new chat per share.
     */
    suspend fun setShareReuseSession(reuse: Boolean)

    /**
     * A [Flow] of the pipeline id bound to the **Quick Settings tile** ("duty"
     * pipeline). `null` means the tile is unbound: tapping it opens the app's
     * Background settings instead of running anything (privacy-first default).
     *
     * Like [defaultPipelineId] this is a user binding, not a tunable preference,
     * so it is **never** touched by `resetToRecommendedDefaults`. It is cleared
     * automatically when the bound pipeline is deleted.
     */
    val quickSettingsTilePipelineId: Flow<String?>

    /**
     * Updates the pipeline bound to the Quick Settings tile. Pass `null` to
     * unbind (the tile then routes to settings instead of running).
     *
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding.
     */
    suspend fun setQuickSettingsTilePipelineId(pipelineId: String?)

    /**
     * A [Flow] of the pipeline id bound to the **external-automation** entry
     * surface — the one a third-party automation app may ask the app to run.
     * `null` means the surface is unbound and every external request is refused.
     *
     * The binding is an **allowlist**, not a default: an external request must
     * name this exact pipeline, and one naming any other pipeline is refused
     * rather than redirected here. That is what makes the user's choice in
     * settings the complete statement of what another app is permitted to run.
     *
     * Like [defaultPipelineId] this is a user binding, not a tunable preference,
     * so it is **never** touched by `resetToRecommendedDefaults`. It is cleared
     * automatically when the bound pipeline is deleted.
     */
    val externalAutomationPipelineId: Flow<String?>

    /**
     * Updates the pipeline bound to the external-automation surface. Pass `null`
     * to unbind (external requests are then refused outright).
     *
     * @param pipelineId Pipeline id to bind, or `null` to clear the binding.
     */
    suspend fun setExternalAutomationPipelineId(pipelineId: String?)

    /**
     * A [Flow] of the master switch for the external-automation contract —
     * whether another app on the device may ask this one to run a pipeline at
     * all. Defaults to `false`.
     *
     * Off by default is not caution for its own sake: switching it on widens the
     * app's attack surface to every installed app, because a broadcast carries no
     * attested sender identity. The switch is therefore the user's consent, and
     * the only thing standing between an inbound broadcast and the parser.
     *
     * Being off does not merely ignore requests — it refuses them with
     * [app.knotwork.android.domain.models.ExternalAutomationRejectionReason.CONTRACT_DISABLED]
     * and journals the refusal, so a caller can tell "switched off" from "never
     * arrived".
     *
     * This is a security-relevant preference rather than a tunable, so
     * `resetToRecommendedDefaults` returns it to `false` — the safe direction.
     */
    val externalAutomationEnabled: Flow<Boolean>

    /**
     * Switches the external-automation contract on or off.
     *
     * @param enabled `true` to let permitted external requests through, `false`
     *   to refuse every one of them.
     */
    suspend fun setExternalAutomationEnabled(enabled: Boolean)
}
