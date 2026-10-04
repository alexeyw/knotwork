package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.Flow

/**
 * State the app keeps between starts that is not a preference: the first-launch and onboarding
 * flags, the chat that is open, and the console tab last chosen.
 *
 * One of the sections [SettingsRepository] is made of.
 */
interface AppStateSettings {

    /**
     * A [Flow] representing the current state of the first launch flag.
     * Emits `true` if it's the user's first time launching the app, `false` otherwise.
     *
     * Semantics (intentionally narrow): this flag gates one-shot seeding
     * inside `InitializeAppUseCase` (default prompts, the seeded
     * `Default System Pipeline`). It is cleared as part of that
     * initialization, so callers cannot rely on it to decide whether the
     * user has seen onboarding — use [hasCompletedOnboarding] for that.
     */
    val isFirstLaunch: Flow<Boolean>

    /**
     * Updates the first launch flag.
     *
     * @param isFirstLaunch The new value to set.
     */
    suspend fun setFirstLaunch(isFirstLaunch: Boolean)

    /**
     * A [Flow] indicating whether the user has finished (or skipped) the
     * onboarding flow at least once. Emits `false` until [setHasCompletedOnboarding]
     * is called.
     *
     * This is intentionally a separate flag from [isFirstLaunch]: cold-start
     * initialization (`InitializeAppUseCase`) clears `isFirstLaunch` before
     * the splash hands control to the nav-graph, so the onboarding gate
     * cannot key off it. The two flags evolve independently — seeding the
     * default pipeline runs exactly once per fresh install, while
     * onboarding can be re-shown later via Settings → Reset onboarding
     * without re-seeding.
     */
    val hasCompletedOnboarding: Flow<Boolean>

    /**
     * Updates the onboarding-completion flag.
     *
     * @param completed `true` after the user finishes or skips onboarding;
     *        `false` resets onboarding so it is shown again on the next
     *        launch (consumed by the Settings → Reset onboarding action).
     */
    suspend fun setHasCompletedOnboarding(completed: Boolean)

    /**
     * A [Flow] representing the current active chat session ID.
     */
    val currentChatSessionId: Flow<String?>

    /**
     * Updates the current active chat session ID.
     */
    suspend fun setCurrentChatSessionId(sessionId: String?)

    /**
     * A [Flow] emitting the user's last-selected console tab on the chat
     * home pane. Stored as a raw string (the enum name from
     * `app.knotwork.design.components.console.ConsoleTab`) so the domain
     * layer stays free of `:catalog` imports. Defaults to `"Logs"` for a
     * fresh install.
     */
    val consolePreferredConsoleTabName: Flow<String>

    /**
     * Persists the user's chosen console tab so it survives process death.
     *
     * @param name Enum name of the chosen tab (`Logs` / `Vars` / `Traces`).
     */
    suspend fun setConsolePreferredConsoleTabName(name: String)
}
