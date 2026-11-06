package app.knotwork.android.presentation.ui.settings

import app.knotwork.android.domain.repositories.AppLanguageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-memory [AppLanguageRepository] for settings tests: records every pick and
 * lets a test change the language as the system's settings page would.
 *
 * @property languages What [supportedLanguages] answers.
 */
class FakeAppLanguageRepository(private val languages: List<String> = listOf("en", "ru")) : AppLanguageRepository {

    /** Every tag passed to [select], in order (`null` = follow the device). */
    val picks: MutableList<String?> = mutableListOf()

    private val selection = MutableStateFlow<String?>(null)

    override val selectedLanguage: StateFlow<String?> = selection

    /** What [interfaceLanguage] reports; a test moves it with [showIn]. */
    private val shown = MutableStateFlow(languages.first())

    override val interfaceLanguage: StateFlow<String> = shown

    override fun supportedLanguages(): List<String> = languages

    override fun select(languageTag: String?) {
        picks += languageTag
        selection.value = languageTag
    }

    /**
     * Changes the language without a call to [select] — as a pick on the system's
     * "App languages" page reaches the app.
     *
     * @param languageTag The new language, or `null` for the device's.
     */
    fun changeFromSystem(languageTag: String?) {
        selection.value = languageTag
    }

    /**
     * Changes the language the interface is shown in, as a configuration change
     * would.
     *
     * @param languageTag The language the strings now resolve to.
     */
    fun showIn(languageTag: String) {
        shown.value = languageTag
    }
}
