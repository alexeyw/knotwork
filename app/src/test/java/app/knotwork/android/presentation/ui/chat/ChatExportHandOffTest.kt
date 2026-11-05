package app.knotwork.android.presentation.ui.chat

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import androidx.core.content.FileProvider
import app.knotwork.android.domain.constants.TransientCacheDirectory
import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.ChatSession
import app.knotwork.android.domain.models.Role
import app.knotwork.android.domain.repositories.ChatRepository
import app.knotwork.android.domain.repositories.PipelineRunRepository
import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.presentation.ui.common.ExportDocumentHandOff
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * **The chat file is the same bytes whichever way it leaves.** The guard of the
 * export's two hand-offs: the file staged for the share sheet and the file
 * written into the document the user picked.
 *
 * Every document here is rendered by the real [ExportChatUseCase], from chats
 * chosen to break an encoder that is not the shared one: Cyrillic, emoji outside
 * the basic plane, the line and paragraph separators, CRLF, quotes and slashes
 * `org.json` escapes, a NUL — and a 2 MB answer, past the size at which a share
 * carried as `EXTRA_TEXT` fails on the Binder (measured: about 350 000 characters
 * on API 34 and 36).
 *
 * The four failure modes it catches, each a plausible edit:
 *  - one path re-encodes (UTF-16, a BOM, a trailing newline);
 *  - one path re-serialises "to tidy it" (`org.json` rewrites `/` as `\/` and
 *    reformats numbers);
 *  - the share goes back to carrying the document as text, which a long chat
 *    cannot survive;
 *  - the chat share stages into another export's directory and deletes a file a
 *    receiving app is still reading.
 */
@RunWith(RobolectricTestRunner::class)
class ChatExportHandOffTest {

    private lateinit var context: Application

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.cacheDir, TransientCacheDirectory.CHAT_EXPORT.dirName).deleteRecursively()
        shadowOf(context).checkActivities(false)
        // `FileProvider` caches an authority's roots per process, while Robolectric
        // gives every test method its own data directory — see JournalExportShareTest.
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    /** Renders one chat holding [texts] through the real use case. */
    private suspend fun render(name: String, vararg texts: String): ChatExportDocument {
        val chatRepository = mockk<ChatRepository>()
        val pipelineRunRepository = mockk<PipelineRunRepository>()
        coEvery { chatRepository.getSessionById(SESSION_ID) } returns
            ChatSession(id = SESSION_ID, name = name, updatedAt = 0L)
        every { chatRepository.getMessagesForSession(SESSION_ID) } returns flowOf(
            texts.mapIndexed { index, text ->
                ChatMessage(
                    sessionId = SESSION_ID,
                    role = if (index % 2 == 0) Role.USER else Role.AGENT,
                    content = text,
                    timestamp = index.toLong(),
                )
            },
        )
        every { pipelineRunRepository.observeRunsForSession(SESSION_ID) } returns flowOf(emptyList())
        return ExportChatUseCase(chatRepository, pipelineRunRepository)(SESSION_ID).getOrThrow()
    }

    /** The chats the guard runs over. */
    private suspend fun corpus(): List<ChatExportDocument> = listOf(
        render("Trip plan", "Where to?", "Lisbon."),
        render("Поездка в Вальпараисо", "Привет! Как добраться?", "Автобусом — 1,5 часа; цена ~4 000 CLP."),
        render("emoji 🚀", "🚀🔥 😀 Σ ∑ 𝐀", "ok 👍🏽 family 👨‍👩‍👧"),
        render("separators", "line separator paragraph", "crlf\r\nand\ttab and NUL\u0000end"),
        render("escapes", "a/b \\ \"quoted\" </script> 1.50 1e3 -0", "{\"nested\": [1, 2.0, \"x\"]}"),
        render("long", "x".repeat(LONG_ANSWER_CHARS), "and after it"),
    )

    /** Stages [document] through the call the chat screens make, and returns the staged file. */
    private suspend fun share(document: ChatExportDocument, fileName: String): File {
        val shared = shareChatExport(context, document, fileName, chooserTitle = "Export chat")
        assertTrue("the share sheet should have opened", shared)
        return File(File(context.cacheDir, TransientCacheDirectory.CHAT_EXPORT.dirName), fileName)
    }

    /** Writes [document] as a save into a picked file does. */
    private suspend fun save(document: ChatExportDocument): ByteArray {
        val picked = ByteArrayOutputStream()
        assertTrue("the save should have succeeded", ExportDocumentHandOff.write(picked, document.json))
        return picked.toByteArray()
    }

    @Test
    fun `given any chat when shared and saved then both files are the same bytes, its UTF-8 json`() = runTest {
        corpus().forEachIndexed { index, document ->
            val fileName = chatExportFileName(document.sessionName)

            val shared = share(document, fileName).readBytes()
            val saved = save(document)

            val expected = document.json.toByteArray(Charsets.UTF_8)
            assertArrayEquals("chat #$index: the saved file differs from its UTF-8 json", expected, saved)
            assertArrayEquals("chat #$index: the shared file differs from the saved one", saved, shared)
            // The expected bytes rule out a byte-order mark and anything appended;
            // the file still reads back as the document.
            assertEquals(document.json, String(saved, Charsets.UTF_8))
            assertEquals(SESSION_ID, JSONObject(String(shared, Charsets.UTF_8)).getString("sessionId"))
        }
    }

    @Test
    fun `given a chat too long for text when shared then the intent carries a uri, not the document`() = runTest {
        val document = render("long", "x".repeat(LONG_ANSWER_CHARS))
        val fileName = chatExportFileName(document.sessionName)

        share(document, fileName)

        val chooser = shadowOf(context).nextStartedActivity
        assertNotNull("a chooser must be started", chooser)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(CHAT_EXPORT_MIME_JSON, send.type)
        assertNull("the document must not travel as text", send.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals(fileName, send.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("long", send.getStringExtra(Intent.EXTRA_SUBJECT))
        val uri = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("content", uri?.scheme)
        assertEquals("${context.packageName}.fileprovider", uri?.authority)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        // What crosses the Binder no longer grows with the chat: a 2 MB answer
        // leaves an intent of a few hundred bytes.
        val parcel = Parcel.obtain()
        try {
            chooser.writeToParcel(parcel, 0)
            assertTrue("chooser parcel is ${parcel.dataSize()} bytes", parcel.dataSize() < MAX_INTENT_BYTES)
        } finally {
            parcel.recycle()
        }
    }

    @Test
    fun `given a staged journal when a chat is shared then the journal stays and the chat stages apart`() = runTest {
        val journalDir = File(context.cacheDir, TransientCacheDirectory.JOURNAL_EXPORT.dirName).apply { mkdirs() }
        val journal = File(journalDir, "trigger-journal-20261105-143000.json").apply { writeText("{}") }
        val first = render("first", "one")
        val second = render("second", "two")

        share(first, "chat-first.json")
        val staged = share(second, "chat-second.json")

        assertTrue("a chat share must not delete a staged journal", journal.exists())
        // One chat at a time: a stale chat is never handed to the next share.
        assertEquals(listOf(staged.name), staged.parentFile?.list()?.toList())
    }

    private companion object {
        const val SESSION_ID = "session-1"

        /** A 2 MB answer — well past the ~350 000 characters a text share fails at. */
        const val LONG_ANSWER_CHARS = 2_000_000

        /** Ceiling for the chooser intent once the document travels as a uri. */
        const val MAX_INTENT_BYTES = 16 * 1024
    }
}
