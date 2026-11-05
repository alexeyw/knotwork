package app.knotwork.android.presentation.common

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import app.knotwork.android.R
import app.knotwork.android.domain.repositories.AppLanguageRepository
import app.knotwork.android.domain.services.DefaultNames
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DefaultNames] read from string resources in the interface language — the
 * application context's configuration, which follows the per-app language
 * without a restart (decisions §113.4).
 *
 * It lives in presentation, as `RunOutcomeAnnouncerImpl` does, because the words
 * are interface text and resources are read here.
 *
 * @property context Application context the names are read from.
 * @property languages The languages the app ships, for recognising a new chat's
 *   name whatever language it was created in.
 */
@Singleton
class DefaultNamesImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val languages: AppLanguageRepository,
) : DefaultNames {

    override fun newChat(): String = context.getString(R.string.default_name_chat)

    override fun isNewChat(name: String): Boolean = name == LEGACY_NEW_CHAT ||
        languages.supportedLanguages().any { tag -> name == stringIn(tag, R.string.default_name_chat) }

    override fun importedChat(): String = context.getString(R.string.default_name_imported_chat)

    override fun chatWithId(shortId: String): String = context.getString(R.string.default_name_chat_with_id, shortId)

    override fun triggerChat(): String = context.getString(R.string.default_name_trigger_chat)

    override fun newPipeline(): String = context.getString(R.string.default_name_pipeline)

    override fun inputNode(): String = context.getString(R.string.default_name_input_node)

    override fun outputNode(): String = context.getString(R.string.default_name_output_node)

    override fun copyOf(name: String): String = context.getString(R.string.default_name_copy, name)

    /** The value of [id] in the language [tag], whatever the interface language is now. */
    private fun stringIn(tag: String, @StringRes id: Int): String {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(configuration).getString(id)
    }

    private companion object {
        /**
         * The name every release before the name became a resource stored for a new
         * chat. Such a chat still renames itself on its first message.
         */
        const val LEGACY_NEW_CHAT = "New Chat"
    }
}
