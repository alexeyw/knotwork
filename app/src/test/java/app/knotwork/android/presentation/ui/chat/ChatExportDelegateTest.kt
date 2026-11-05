package app.knotwork.android.presentation.ui.chat

import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * The export half shared by the chat screen and the archive.
 *
 * What it promises: a render failure is reported on both actions, never
 * swallowed; a save writes **the document it rendered before the picker opened**,
 * byte for byte; the picked stream is closed on every path; and a save whose
 * render is gone says so instead of leaving an empty file in silence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatExportDelegateTest {

    private val exportChat = mockk<ExportChatUseCase>()

    private val document = ChatExportDocument(
        sessionName = "Trip plan",
        json = "{\n  \"sessionName\": \"Trip plan\",\n  \"text\": \"Привет — 🚀 a\\/b\"\n}",
    )

    private val at: Date = Date.from(
        LocalDateTime.of(2026, 11, 5, 14, 30, 0).atZone(ZoneId.systemDefault()).toInstant(),
    )

    /** A stream that records its bytes and whether it was closed, and can be made to fail. */
    private class RecordingStream(private val failOnWrite: Boolean = false, private val failOnClose: Boolean = false) :
        OutputStream() {
        val bytes = ByteArrayOutputStream()
        var closed = false
            private set

        override fun write(b: Int) {
            if (failOnWrite) throw IOException("no space left on device")
            bytes.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failOnWrite) throw IOException("no space left on device")
            bytes.write(b, off, len)
        }

        override fun close() {
            closed = true
            if (failOnClose) throw IOException("provider went away")
        }
    }

    /** A delegate on the test scope, its write driven by the test scheduler and its clock pinned. */
    private fun TestScope.delegate() = ChatExportDelegate(
        scope = this,
        exportChat = exportChat,
        ioDispatcher = StandardTestDispatcher(testScheduler),
        clock = { at },
    )

    /**
     * Collects the delegate's events into a list for the life of the test — on an
     * unconfined dispatcher, because `advanceUntilIdle` never runs a collector
     * queued on the test dispatcher inside `backgroundScope`.
     */
    private fun TestScope.eventsOf(delegate: ChatExportDelegate): List<ChatExportEvent> {
        val events = mutableListOf<ChatExportEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { delegate.events.collect { events += it } }
        return events
    }

    @Test
    fun `given a chat when shared then the rendered document goes out under the chat's file name`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.share("s1")
        advanceUntilIdle()

        assertEquals(
            listOf(ChatExportEvent.Share(document, "chat-Trip-plan-20261105-143000.json")),
            events,
        )
    }

    @Test
    fun `given a chat that cannot be read when shared then the failure is reported`() = runTest {
        coEvery { exportChat("s1") } returns Result.failure(IllegalStateException("database closed"))
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.share("s1")
        advanceUntilIdle()

        assertEquals(listOf(ChatExportEvent.RenderFailed), events)
    }

    @Test
    fun `given a blank id when shared or saved then nothing is rendered or raised`() = runTest {
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.share(" ")
        delegate.save("")
        advanceUntilIdle()

        assertTrue(events.isEmpty())
        coVerify(exactly = 0) { exportChat(any()) }
    }

    @Test
    fun `given a save when the stream arrives then the document rendered before the picker is written`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        // The chat changes while the picker is open: the file must still be the
        // render the picker was opened for, not a second one.
        coEvery { exportChat("s1") } returns Result.success(document.copy(json = "{\"changed\": true}"))
        val stream = RecordingStream()
        delegate.saveTo(stream)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ChatExportEvent.PickDestination("chat-Trip-plan-20261105-143000.json"),
                ChatExportEvent.Saved,
            ),
            events,
        )
        assertArrayEquals(document.json.toByteArray(Charsets.UTF_8), stream.bytes.toByteArray())
        assertTrue("the picked stream must be closed", stream.closed)
        coVerify(exactly = 1) { exportChat("s1") }
    }

    @Test
    fun `given a chat that cannot be read when saved then the picker is not opened`() = runTest {
        coEvery { exportChat("s1") } returns Result.failure(IllegalStateException("database closed"))
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()

        assertEquals(listOf(ChatExportEvent.RenderFailed), events)
    }

    @Test
    fun `given the write fails when saved then the failure is reported and the stream closed`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        val stream = RecordingStream(failOnWrite = true)
        delegate.saveTo(stream)
        advanceUntilIdle()

        assertEquals(ChatExportEvent.SaveFailed, events.last())
        assertTrue("the picked stream must be closed", stream.closed)
    }

    @Test
    fun `given no held render when a stream arrives then the save fails and the stream is closed`() = runTest {
        // A process death while the picker was open: the new delegate holds nothing.
        val delegate = delegate()
        val events = eventsOf(delegate)

        val stream = RecordingStream(failOnClose = true)
        delegate.saveTo(stream)
        advanceUntilIdle()

        assertEquals(listOf(ChatExportEvent.SaveFailed), events)
        assertTrue("the picked stream must be closed", stream.closed)
        assertEquals(0, stream.bytes.size())
    }

    @Test
    fun `given a cancelled pick when a stream later arrives then the dropped render is not written`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        delegate.cancelSave()
        val stream = RecordingStream()
        delegate.saveTo(stream)
        advanceUntilIdle()

        assertEquals(ChatExportEvent.SaveFailed, events.last())
        assertEquals(0, stream.bytes.size())
    }

    @Test
    fun `given one save when a second stream arrives then the render is written only once`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        val first = RecordingStream()
        val second = RecordingStream()
        delegate.saveTo(first)
        delegate.saveTo(second)
        advanceUntilIdle()

        assertEquals(listOf(ChatExportEvent.Saved, ChatExportEvent.SaveFailed), events.drop(1))
        assertArrayEquals(document.json.toByteArray(Charsets.UTF_8), first.bytes.toByteArray())
        assertEquals(0, second.bytes.size())
    }
}
