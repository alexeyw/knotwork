package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import java.util.Locale

/**
 * How the interface-language picker names a language.
 *
 * Each language is named in itself — "English", "Русский" — never in the
 * language the app is currently shown in: a reader who cannot read the current
 * language still finds their own in the list. That is also why these names come
 * from the platform's locale data rather than from string resources, which
 * would translate them.
 */
object AppLanguageLabels {

    /**
     * The name of [languageTag]'s language in that language, with the first
     * letter capitalised as a list item is ("Русский", not "русский").
     *
     * @param languageTag A BCP-47 language tag.
     * @return The language's own name; the tag itself when the platform has no
     *   name for it.
     */
    fun autonym(languageTag: String): String {
        val locale = Locale.forLanguageTag(languageTag)
        val name = locale.getDisplayName(locale).ifBlank { languageTag }
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /**
     * The language row's value: the picked language's own name, or "Device
     * language" while the app follows the device.
     *
     * @param languageTag The picked language, or `null` for the device's.
     * @param context Resolves the device-language label in the current language.
     * @return The text the row shows under its title.
     */
    fun rowValue(languageTag: String?, context: Context): String =
        languageTag?.let(::autonym) ?: context.getString(R.string.settings_language_device)
}
