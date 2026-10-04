package app.knotwork.android.domain.repositories

import app.knotwork.android.domain.models.TestProbeResult
import kotlinx.coroutines.flow.Flow

/**
 * How the model generates, and which local model does it: the sampling parameters, the
 * system-prompt prefix, the context window and the voice-input length; the local backend, the
 * breadcrumbs that recover from a backend that crashed during initialisation, the last backend
 * probe; and the Hugging Face token that model downloads use.
 *
 * One of the sections [SettingsRepository] is made of. A consumer depends on the section it reads,
 * never on the whole composite.
 */
interface GenerationSettings {

    /**
     * A [Flow] representing the saved HuggingFace authorization token.
     */
    val huggingFaceAuthToken: Flow<String?>

    /**
     * Updates the HuggingFace authorization token.
     *
     * @param token The new token to save, or null to clear it.
     */
    suspend fun setHuggingFaceAuthToken(token: String?)

    /**
     * A [Flow] representing the maximum allowed context length (e.g., in characters or tokens).
     */
    val maxContextLength: Flow<Int>

    /**
     * Updates the maximum allowed context length.
     *
     * @param length The new maximum length to set.
     */
    suspend fun setMaxContextLength(length: Int)

    /**
     * A [Flow] representing the sampling temperature for generation.
     */
    val temperature: Flow<Float>

    /**
     * Updates the sampling temperature.
     */
    suspend fun setTemperature(temperature: Float)

    /**
     * A [Flow] representing the top-k sampling parameter for generation.
     */
    val topK: Flow<Int>

    /**
     * Updates the top-k sampling parameter.
     */
    suspend fun setTopK(topK: Int)

    /**
     * A [Flow] representing the top-p sampling parameter for generation.
     */
    val topP: Flow<Float>

    /**
     * Updates the top-p sampling parameter.
     */
    suspend fun setTopP(topP: Float)

    /**
     * A [Flow] representing the system prompt prefix.
     */
    val systemPromptPrefix: Flow<String>

    /**
     * Updates the system prompt prefix.
     *
     * @param prompt The new prompt to set.
     */
    suspend fun setSystemPromptPrefix(prompt: String)

    /**
     * Maximum voice-recording length, in seconds, before the recorder
     * auto-stops and the clip is handed to transcription. Defaults to
     * [app.knotwork.android.domain.constants.SettingsDefaults.AUDIO_MAX_DURATION_SEC_DEFAULT].
     */
    val audioMaxDurationSec: Flow<Int>

    /**
     * Persists the maximum voice-recording length.
     *
     * @param seconds The new limit in seconds; callers should keep it within the
     *   `AUDIO_MAX_DURATION_SEC_MIN..AUDIO_MAX_DURATION_SEC_MAX` range (validation
     *   of user-entered values lives in the Settings ViewModel).
     */
    suspend fun setAudioMaxDurationSec(seconds: Int)

    /**
     * A [Flow] emitting the wire key of the selected local-model backend
     * ([app.knotwork.android.domain.models.LocalBackend.key]). Stored as a raw string for
     * backward compatibility with DataStore values written before the typed enum existed.
     */
    val localModelBackend: Flow<String>

    /**
     * The **raw** stored backend preference: the persisted wire key, or `null`
     * when the user (and the app) has never written one.
     *
     * [localModelBackend] folds the absent case into
     * [app.knotwork.android.domain.models.LocalBackend.CPU], which is exactly
     * what every inference call site wants — but it makes "never chosen"
     * indistinguishable from "deliberately CPU". The one-shot onboarding
     * acceleration decision needs that distinction: it may pick a default only
     * while nothing has been chosen, and must never override an explicit
     * selection.
     */
    val localModelBackendPreference: Flow<String?>

    /**
     * Updates the selected backend for the local model.
     *
     * @param backend The new backend wire key; use
     *        [app.knotwork.android.domain.models.LocalBackend.key] to obtain it.
     */
    suspend fun setLocalModelBackend(backend: String)

    /**
     * Sentinel emitted by [setLastInitBackendAttempt] right before a
     * non-CPU LiteRT backend init is attempted. Cleared on successful init.
     *
     * Used as a crash-recovery breadcrumb: if a cold-start observes this
     * key still set to a non-CPU backend, the previous LiteRT init
     * crashed mid-flight (typically a missing GPU/NPU dispatch library
     * killing the process via SIGABRT before Kotlin try/catch can fire).
     * `LiteRTLlmEngine.initialize` then forces CPU and clears the
     * persisted backend so subsequent restarts are stable.
     */
    val lastInitBackendAttempt: Flow<String?>

    /**
     * Updates the crash-recovery breadcrumb. Pass `null` to clear it on
     * successful init.
     */
    suspend fun setLastInitBackendAttempt(backendKey: String?)

    /**
     * Number of **consecutive** cold starts that found [lastInitBackendAttempt]
     * still set, i.e. the previous process died before init finished. Reset to
     * zero by the first successful init.
     *
     * The breadcrumb alone cannot tell a backend crash apart from any other way
     * the process can die inside that window — swiped from recents, reclaimed by
     * the low-memory killer, frozen by the OEM. Treating the first such death as
     * proof that the GPU is broken silently and permanently downgraded the user
     * to CPU; observed during a directed on-device test, where an unrelated
     * `lmkd` kill cost the device its GPU backend. Corroboration across two
     * consecutive starts is what separates "this backend really cannot
     * initialise" from "something else killed us".
     */
    val localBackendFailureStreak: Flow<Int>

    /**
     * Records the current consecutive-failure count for the local backend.
     *
     * @param streak new streak value; `0` clears it after a successful init.
     */
    suspend fun setLocalBackendFailureStreak(streak: Int)

    /**
     * Last persisted result of a `Test backend` run inside Settings.
     * Emits `null` until the user has run the probe at least once.
     */
    val lastTestProbeResult: Flow<TestProbeResult?>

    /**
     * Persists the latest test-backend probe result so the row's
     * subtitle survives navigation.
     *
     * @param result Probe outcome to persist; pass `null` to clear.
     */
    suspend fun setLastTestProbeResult(result: TestProbeResult?)
}
