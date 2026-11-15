package app.knotwork.android.presentation.ui.chat

import app.knotwork.android.domain.usecases.ChatExportDocument
import app.knotwork.android.domain.usecases.ExportChatUseCase
import app.knotwork.android.presentation.ui.common.FakePickedDocument
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Date

/**
 * The export half shared by the chat screen and the archive.
 *
 * What it promises: a render failure is reported on both actions, never
 * swallowed; a save writes **the document it rendered before the picker opened**,
 * byte for byte; the picked stream is closed on every path; and a save that
 * cannot write a byte removes the empty file the picker created and says so.
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
    fun `given a save when the document arrives then the render made before the picker is written`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        // The chat changes while the picker is open: the file must still be the
        // render the picker was opened for, not a second one.
        coEvery { exportChat("s1") } returns Result.success(document.copy(json = "{\"changed\": true}"))
        val picked = FakePickedDocument()
        delegate.saveTo(picked)
        advanceUntilIdle()

        assertEquals(
            listOf(
                ChatExportEvent.PickDestination("chat-Trip-plan-20261105-143000.json"),
                ChatExportEvent.Saved,
            ),
            events,
        )
        assertArrayEquals(document.json.toByteArray(Charsets.UTF_8), picked.bytes)
        assertTrue("the picked stream must be closed", picked.stream.closed)
        assertFalse("a written file is kept", picked.deleteAsked)
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
        val picked = FakePickedDocument(failOnWrite = true)
        delegate.saveTo(picked)
        advanceUntilIdle()

        assertEquals(ChatExportEvent.SaveFailed, events.last())
        assertTrue("the picked stream must be closed", picked.stream.closed)
    }

    @Test
    fun `given no held render when a document arrives then the empty file is removed and the save fails`() = runTest {
        // A process death while the picker was open: the new delegate holds nothing.
        val delegate = delegate()
        val events = eventsOf(delegate)

        val picked = FakePickedDocument()
        delegate.saveTo(picked)
        advanceUntilIdle()

        assertEquals(listOf(ChatExportEvent.SaveFailed), events)
        assertTrue("the empty file the picker created must be removed", picked.deleteAsked)
        assertEquals("nothing is written into it", 0, picked.openCount)
    }

    @Test
    fun `given no held render and a file that cannot be deleted when a document arrives then its name is given`() =
        runTest {
            val delegate = delegate()
            val events = eventsOf(delegate)

            delegate.saveTo(FakePickedDocument(name = "chat-Trip-plan.json", deletes = false))
            advanceUntilIdle()

            assertEquals(listOf(ChatExportEvent.FileLeft("chat-Trip-plan.json")), events)
        }

    @Test
    fun `given a document that will not open when saved then it is removed and the save fails`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        val picked = FakePickedDocument(opens = false)
        delegate.saveTo(picked)
        advanceUntilIdle()

        assertEquals(ChatExportEvent.SaveFailed, events.last())
        assertTrue("the empty file the picker created must be removed", picked.deleteAsked)
    }

    @Test
    fun `given a cancelled pick when a document later arrives then the dropped render is not written`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        delegate.cancelSave()
        val picked = FakePickedDocument()
        delegate.saveTo(picked)
        advanceUntilIdle()

        assertEquals(ChatExportEvent.SaveFailed, events.last())
        assertEquals(0, picked.bytes.size)
        assertTrue(picked.deleteAsked)
    }

    @Test
    fun `given one save when a second document arrives then the render is written only once`() = runTest {
        coEvery { exportChat("s1") } returns Result.success(document)
        val delegate = delegate()
        val events = eventsOf(delegate)

        delegate.save("s1")
        advanceUntilIdle()
        val first = FakePickedDocument()
        val second = FakePickedDocument()
        delegate.saveTo(first)
        delegate.saveTo(second)
        advanceUntilIdle()

        // Both outcomes are reported; their order follows the I/O each needed, not the arrival.
        assertEquals(2, events.drop(1).size)
        assertEquals(setOf(ChatExportEvent.Saved, ChatExportEvent.SaveFailed), events.drop(1).toSet())
        assertArrayEquals(document.json.toByteArray(Charsets.UTF_8), first.bytes)
        assertEquals(0, second.bytes.size)
        assertTrue("the second, empty file is removed", second.deleteAsked)
    }
}
