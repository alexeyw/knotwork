package app.knotwork.android.data.prompt

import app.knotwork.android.domain.prompt.PromptVariableProvider
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the value for the `$LANG` placeholder.
 *
 * Resolves to the language the user reads the app in, as a BCP-47 tag
 * (e.g. `en-US`, `de-DE`, `ru`): the language picked for the app alone
 * (Settings → About → Language, or the system's "App languages" page) when
 * there is one, else the device's. The process default follows a per-app
 * language — measured on API 34 — so [Locale.getDefault] is that language.
 * Useful when the system prompt needs to bias the model toward the user's
 * preferred language without the user having to retype the locale manually
 * in every template.
 *
 * Reads [Locale.getDefault] on every [resolve] call so a runtime language
 * change takes effect on the very next render.
 *
 * @property localeProvider Returns the [Locale] used at render time. Tests
 *   override with a fixed locale for deterministic assertions.
 */
@Singleton
class LangVariableProvider internal constructor(private val localeProvider: () -> Locale) : PromptVariableProvider {

    @Inject
    constructor() : this(localeProvider = { Locale.getDefault() })

    override fun key(): String = KEY

    override suspend fun resolve(): String = localeProvider().toLanguageTag()

    private companion object {
        const val KEY = "LANG"
    }
}
