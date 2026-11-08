package app.knotwork.android.presentation.ui.chat

import app.knotwork.android.domain.usecases.ExportChatUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * The name a chat export gets — the one the share sheet's receiver sees and the
 * document picker suggests.
 *
 * The rules under test are the ones a folder of exports depends on: the chat is
 * recognisable from the name in any script, the name is something every file
 * system accepts, it never grows past what the picker shows, and the timestamp
 * is the journals' own.
 */
class ChatExportFileTest {

    /** A fixed device-local moment, so the stamp is predictable in any zone. */
    private val at: Date = Date.from(
        LocalDateTime.of(2026, 11, 5, 14, 30, 0).atZone(ZoneId.systemDefault()).toInstant(),
    )

    @Test
    fun `given a plain name when named then the file is chat, name, stamp and json`() {
        assertEquals("chat-Trip-plan-20261105-143000.json", chatExportFileName("Trip plan", at))
    }

    @Test
    fun `given a name in another script when named then its letters are kept`() {
        assertEquals("chat-Поездка-в-Вальпараисо-20261105-143000.json", chatExportFileName("Поездка в Вальпараисо", at))
        assertEquals("Ελλάδα-2026", chatExportFileTitle("Ελλάδα 2026"))
    }

    @Test
    fun `given separators a file system refuses when named then each run becomes one dash`() {
        // `/` and `:` are refused by FAT and by the Storage Access Framework's own
        // name check; a run of them must not turn into a run of dashes either.
        assertEquals("a-b-c", chatExportFileTitle("a / b :: c"))
        assertEquals("notes-v2", chatExportFileTitle("notes\\v2?*\"<>|"))
    }

    @Test
    fun `given separators at either end when named then no dash leads or trails`() {
        assertEquals("draft", chatExportFileTitle("  ...draft!!  "))
    }

    @Test
    fun `given only emoji and punctuation when named then the title is empty and the stem stands alone`() {
        assertEquals("", chatExportFileTitle("🚀🔥 !!!"))
        assertEquals("chat-20261105-143000.json", chatExportFileName("🚀🔥 !!!", at))
    }

    @Test
    fun `given the export's fallback name when named then it is left out`() {
        // `chat-Chat-…` repeats the stem and says nothing about the chat.
        assertEquals("chat-20261105-143000.json", chatExportFileName(ExportChatUseCase.FALLBACK_SESSION_NAME, at))
    }

    @Test
    fun `given a long name when named then the title stops at the limit without a trailing dash`() {
        val title = chatExportFileTitle("word ".repeat(50))

        assertTrue(title, title.codePointCount(0, title.length) <= CHAT_EXPORT_FILE_TITLE_LIMIT)
        assertTrue(title, !title.endsWith("-"))
        assertEquals("word-word-word-word-word-word-word-word", title)
    }

    @Test
    fun `given letters outside the basic plane when cut then no surrogate pair is split`() {
        // U+1D400 MATHEMATICAL BOLD CAPITAL A is a letter that takes two chars.
        val boldA = String(Character.toChars(0x1D400))
        val title = chatExportFileTitle(boldA.repeat(CHAT_EXPORT_FILE_TITLE_LIMIT + 5))

        assertEquals(CHAT_EXPORT_FILE_TITLE_LIMIT, title.codePointCount(0, title.length))
        assertEquals(boldA.repeat(CHAT_EXPORT_FILE_TITLE_LIMIT), title)
    }

    @Test
    fun `given a decomposed accent when named then the mark stays with its letter`() {
        // "é" written as e + U+0301 COMBINING ACUTE ACCENT, as some keyboards send it.
        assertEquals("Café-noir", chatExportFileTitle("Café noir"))
        // A mark with no letter before it is not a name of its own.
        assertEquals("x", chatExportFileTitle("́ x"))
    }
}
