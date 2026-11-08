package app.knotwork.android.presentation.common

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.services.ChatSystemRows
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ChatSystemRows] read from string resources in the interface language — the
 * application context's configuration, which follows the per-app language
 * without a restart (decisions §113.4), as [DefaultNamesImpl] does.
 *
 * @property context Application context the sentences are read from.
 */
@Singleton
class ChatSystemRowsImpl @Inject constructor(@param:ApplicationContext private val context: Context) : ChatSystemRows {

    override fun toolDenied(toolName: String): String = context.getString(R.string.chat_system_tool_denied, toolName)

    override fun clarificationReplyNotDelivered(): String =
        context.getString(R.string.chat_system_clarification_reply_not_delivered)
}
