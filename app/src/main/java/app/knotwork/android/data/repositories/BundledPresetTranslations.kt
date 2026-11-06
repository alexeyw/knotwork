package app.knotwork.android.data.repositories

import android.content.Context
import androidx.annotation.VisibleForTesting
import app.knotwork.android.data.repositories.AppLanguageRepositoryImpl.Companion.DEFAULT_LANGUAGE
import app.knotwork.android.domain.presetl10n.PresetTranslations
import app.knotwork.android.domain.presetl10n.PresetTranslationsJson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the bundled presets' translations, `assets/presets/l10n/<language>.json`.
 *
 * Each language's file is read once and kept for the life of the process: the
 * assets are packaged into the APK and cannot change while it runs.
 *
 * A language with no file — the default one, which the presets are written in,
 * or one the presets were never translated into — has no translation, and its
 * presets show as written. So does a file that cannot be read: the catalogue is
 * worth more in English than not at all. `PresetTranslationCoverageTest` keeps
 * every shipped file readable, so that is a damaged installation, and it is
 * logged.
 *
 * @property context Application context the assets are opened from.
 */
@Singleton
class BundledPresetTranslations @Inject constructor(@ApplicationContext private val context: Context) {

    /** Dispatcher every asset read runs on. Swapped in unit tests. */
    @VisibleForTesting
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    private val byLanguage = ConcurrentHashMap<String, PresetTranslations>()

    /**
     * The presets' translations into [language].
     *
     * @param language A BCP-47 tag, as `AppLanguageRepository.interfaceLanguage` reports it.
     * @return The translations, or [PresetTranslations.NONE] when the presets
     *   are shown as written in that language.
     */
    suspend fun forLanguage(language: String): PresetTranslations {
        if (language == DEFAULT_LANGUAGE) return PresetTranslations.NONE
        byLanguage[language]?.let { return it }
        val read = withContext(dispatcher) { read(language) }
        return byLanguage.putIfAbsent(language, read) ?: read
    }

    private fun read(language: String): PresetTranslations {
        val path = "$DIRECTORY/$language.json"
        return try {
            val text = context.assets.open(path).bufferedReader().use { it.readText() }
            PresetTranslationsJson.parse(text, language)
        } catch (_: FileNotFoundException) {
            PresetTranslations.NONE
        } catch (e: IOException) {
            Timber.w(e, "Preset translation %s could not be read", path)
            PresetTranslations.NONE
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Preset translation %s is malformed", path)
            PresetTranslations.NONE
        }
    }

    private companion object {
        /** Directory of the translation files under `assets`. */
        const val DIRECTORY = "presets/l10n"
    }
}
