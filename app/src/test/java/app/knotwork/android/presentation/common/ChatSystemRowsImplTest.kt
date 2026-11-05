package app.knotwork.android.presentation.common

import app.knotwork.android.domain.services.EnglishChatSystemRows
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The rows the chat records for a denied tool call and an undelivered reply. In
 * English they are byte for byte what earlier releases wrote, so a history read
 * by a model reads as before; in Russian the model reads them in Russian.
 */
@RunWith(RobolectricTestRunner::class)
class ChatSystemRowsImplTest {

    private val rows get() = ChatSystemRowsImpl(RuntimeEnvironment.getApplication())

    @Test
    fun `given the app in English when a row is written then it is the sentence earlier releases wrote`() {
        assertEquals("Tool 'fs.delete_file' denied by user.", rows.toolDenied("fs.delete_file"))
        assertEquals(EnglishChatSystemRows.toolDenied("fs.delete_file"), rows.toolDenied("fs.delete_file"))
        assertEquals(EnglishChatSystemRows.REPLY_NOT_DELIVERED, rows.clarificationReplyNotDelivered())
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when a row is written then it is Russian`() {
        assertEquals("Вызов инструмента «fs.delete_file» отклонён пользователем.", rows.toolDenied("fs.delete_file"))
        assertEquals(
            "Ответ не доставлен: уточнение уже было закрыто ответом по умолчанию.",
            rows.clarificationReplyNotDelivered(),
        )
    }
}
