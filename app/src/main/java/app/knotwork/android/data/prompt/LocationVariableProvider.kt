package app.knotwork.android.data.prompt

import android.app.LocaleManager
import android.content.Context
import app.knotwork.android.domain.prompt.PromptVariableProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the value for the `$LOCATION` placeholder.
 *
 * Privacy-conservative implementation — resolves to the device's coarse
 * region (the country portion of the device's [Locale], e.g. `US`, `DE`)
 * rather than a fine-grained GPS coordinate. This keeps the variable
 * informative ("the user is in Germany so prefer metric units") while
 * never requiring an Android location permission.
 *
 * The region is read from the **device's** languages, not from the process
 * default: once the user picks a language for the app alone, the process
 * default becomes that language with no country (`ru`, not `ru-RU`), and so
 * does `Resources.getSystem()` — measured on API 34. A language the user reads
 * the app in says nothing about where they are; the device's region does.
 *
 * Reads the locale on every [resolve] call so a runtime change reflects
 * immediately.
 *
 * @property localeProvider Returns the [Locale] whose country is the region.
 */
@Singleton
class LocationVariableProvider internal constructor(private val localeProvider: () -> Locale) :
    PromptVariableProvider {

    /**
     * Production constructor: the region comes from the device's languages.
     *
     * @param context Application context, for the `LocaleManager`.
     */
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        localeProvider = { deviceRegionLocale(context.getSystemService(LocaleManager::class.java)) },
    )

    override fun key(): String = KEY

    override suspend fun resolve(): String = localeProvider().country.ifBlank { UNKNOWN }

    private companion object {
        const val KEY = "LOCATION"
        const val UNKNOWN = "unknown"
    }
}

/**
 * The first of the device's languages that names a country, else the process
 * default.
 *
 * @param localeManager The platform service; `null` only off-device.
 * @return The locale whose country is the device's region.
 */
internal fun deviceRegionLocale(localeManager: LocaleManager?): Locale {
    val systemLocales = localeManager?.systemLocales ?: return Locale.getDefault()
    return List(systemLocales.size()) { systemLocales[it] }.firstOrNull { it.country.isNotBlank() }
        ?: Locale.getDefault()
}
