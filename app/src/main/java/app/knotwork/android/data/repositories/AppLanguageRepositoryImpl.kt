package app.knotwork.android.data.repositories

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import app.knotwork.android.R
import app.knotwork.android.domain.repositories.AppLanguageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AppLanguageRepository] over the platform's per-app language (`LocaleManager`).
 *
 * The languages offered are the ones the build lists in the generated
 * `LocaleConfig` (`androidResources.generateLocaleConfig` with
 * `localeFilters` in `app/build.gradle.kts`), so a language added to the build
 * reaches the picker without a code change here.
 *
 * A change made outside the app — on the system's "App languages" page — reaches
 * the process as a configuration change, never as a call into this class, so the
 * repository listens for configuration changes on the application context and
 * re-reads the system's choice on each one.
 *
 * The language the interface is shown in is read from the string resources
 * themselves (`interface_language_tag`, which each translation writes as its own
 * tag), so it is the language the screens resolve to — never a second guess at
 * Android's resource matching.
 *
 * @property context Application context: the `LocaleManager` and the
 *   configuration-change callback belong to the application.
 * @property readLocaleConfig Reads the languages of the app's `LocaleConfig`, or
 *   `null` when it cannot be read. A seam: Robolectric cannot read the generated
 *   config, so the JVM tests hand the list in and the instrumented test reads the
 *   real one.
 */
@Singleton
class AppLanguageRepositoryImpl internal constructor(
    private val context: Context,
    private val readLocaleConfig: () -> LocaleList?,
) : AppLanguageRepository {

    /**
     * Production constructor: the languages come from the app's generated
     * `LocaleConfig`.
     *
     * @param context Application context.
     */
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        readLocaleConfig = {
            LocaleConfig(context).takeIf { it.status == LocaleConfig.STATUS_SUCCESS }?.supportedLocales
        },
    )

    private val localeManager: LocaleManager = context.getSystemService(LocaleManager::class.java)

    private val selection = MutableStateFlow(readSelection())

    override val selectedLanguage: StateFlow<String?> = selection.asStateFlow()

    private val shown = MutableStateFlow(readInterfaceLanguage(context))

    override val interfaceLanguage: StateFlow<String> = shown.asStateFlow()

    private val supported: List<String> by lazy { readSupportedLanguages() }

    init {
        context.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    selection.value = readSelection()
                    shown.value = readInterfaceLanguage(context.createConfigurationContext(newConfig))
                }

                @Deprecated("Deprecated in API 34; nothing to release here")
                override fun onLowMemory() = Unit
            },
        )
    }

    override fun supportedLanguages(): List<String> = supported

    override fun select(languageTag: String?) {
        localeManager.applicationLocales =
            languageTag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        selection.value = readSelection()
    }

    /** The first per-app locale as a tag, or `null` while the app follows the device. */
    private fun readSelection(): String? {
        val locales = localeManager.applicationLocales
        return if (locales.isEmpty) null else locales[0].toLanguageTag()
    }

    /**
     * The language [resources] resolve the interface's strings to.
     *
     * @param resources A context carrying the configuration to read under.
     * @return The tag the resolved translation writes for itself.
     */
    private fun readInterfaceLanguage(resources: Context): String = resources.getString(R.string.interface_language_tag)

    /**
     * The generated `LocaleConfig`'s languages, the default first; just the
     * default when the config cannot be read, so the picker always has the
     * language every string exists in.
     */
    private fun readSupportedLanguages(): List<String> {
        val locales = readLocaleConfig() ?: return listOf(DEFAULT_LANGUAGE)
        val tags = List(locales.size()) { locales[it].toLanguageTag() }.distinct()
        return listOf(DEFAULT_LANGUAGE) + (tags - DEFAULT_LANGUAGE)
    }

    /** Constants shared with the tests that hold the build's languages to the code. */
    companion object {
        /**
         * The language of the unqualified resources — `unqualifiedResLocale` in
         * `app/src/main/res/resources.properties`, which `TranslationCoverageTest`
         * holds equal to this value.
         */
        const val DEFAULT_LANGUAGE: String = "en"
    }
}
