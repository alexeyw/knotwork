package app.knotwork.android.domain.repositories

import kotlinx.coroutines.flow.StateFlow

/**
 * The language the app's interface is shown in.
 *
 * The choice belongs to the system, not to the app's own settings store: the
 * platform keeps one per-app language, which the system's "App languages" page
 * and the in-app picker both read and write, and which survives a backup like
 * any other system setting. So this surface never persists anything itself — it
 * reads and writes the system's choice, and reports every change, wherever it
 * was made.
 *
 * Only the interface follows this choice. What a model reads stays as written —
 * system prompts, tool descriptions and tool results are not translated.
 */
interface AppLanguageRepository {

    /**
     * The languages the interface is translated into, as BCP-47 language tags,
     * the app's default language first.
     *
     * @return Every language the picker may offer, besides following the device.
     */
    fun supportedLanguages(): List<String>

    /**
     * The language picked for the app as a BCP-47 tag, or `null` while the app
     * follows the device's language. Emits again when the choice changes — from
     * the in-app picker or from the system's settings.
     */
    val selectedLanguage: StateFlow<String?>

    /**
     * Picks the app's language. The system then re-creates the visible screens in
     * that language.
     *
     * @param languageTag A tag from [supportedLanguages], or `null` to follow the
     *   device's language again.
     */
    fun select(languageTag: String?)
}
