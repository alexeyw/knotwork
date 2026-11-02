package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.models.ChatMessage
import app.knotwork.android.domain.models.MessageAttachment
import app.knotwork.android.domain.models.Role
import app.knotwork.design.components.chat.chatMessagePreview
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Which messages of a thread get a bubble preview, and that each is cut once, off the caller's thread. */
class ChatMessagePreviewsTest {

    /** Runs each cut at once on the caller's thread and counts how many times work was handed to it. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var hops = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            hops++
            block.run()
        }
    }

    private val dispatcher = CountingDispatcher()
    private val previews = ChatMessagePreviews { dispatcher }

    private val long = (1..40).joinToString("\n\n") { "Paragraph $it. " + "Words to read slowly. ".repeat(13) }

    private fun message(id: Long?, role: Role, content: String, attachment: MessageAttachment? = null) =
        ChatMessage(id = id, sessionId = "s", role = role, content = content, timestamp = 0, attachment = attachment)

    @Test
    fun `given only short messages when previewed then nothing is cut and nothing leaves the caller`() = runTest {
        val result = previews.of(listOf(message(1, Role.USER, "hi"), message(2, Role.AGENT, "hello")))

        assertTrue(result.isEmpty())
        assertEquals(0, dispatcher.hops)
    }

    @Test
    fun `given a long answer when previewed twice then it is cut once, off the caller, and kept`() = runTest {
        val messages = listOf(message(1, Role.USER, "hi"), message(2, Role.AGENT, long))

        val first = previews.of(messages)
        val second = previews.of(messages.map { it.copy(content = String(it.content.toCharArray())) })

        assertEquals(chatMessagePreview(long, markdown = true), first[2])
        assertSame(first[2], second[2])
        assertEquals(1, dispatcher.hops)
    }

    @Test
    fun `given a message whose text changed when previewed again then it is cut again`() = runTest {
        previews.of(listOf(message(2, Role.AGENT, long)))

        val changed = previews.of(listOf(message(2, Role.AGENT, long + "\n\nOne more line.")))

        assertNotNull(changed[2])
        assertEquals(2, dispatcher.hops)
    }

    @Test
    fun `given a message gone from the thread when it comes back then it is cut again`() = runTest {
        previews.of(listOf(message(2, Role.AGENT, long)))
        previews.of(emptyList())

        previews.of(listOf(message(2, Role.AGENT, long)))

        assertEquals(2, dispatcher.hops)
    }

    @Test
    fun `given the user's long text when previewed then it is cut as plain text`() = runTest {
        val result = previews.of(listOf(message(1, Role.USER, long)))

        assertEquals(chatMessagePreview(long, markdown = false), result[1])
    }

    @Test
    fun `given a long system notice, an image's caption, or a message not stored yet then none is cut`() = runTest {
        val image = MessageAttachment(path = "a.jpg", mimeType = "image/jpeg", width = 4, height = 3)

        val result = previews.of(
            listOf(
                message(1, Role.SYSTEM, long),
                message(2, Role.USER, long, attachment = image),
                message(null, Role.AGENT, long),
            ),
        )

        assertTrue(result.isEmpty())
        assertEquals(0, dispatcher.hops)
    }

    @Test
    fun `given a message just sent when previewed on the spot then it is the plain-text cut`() {
        assertEquals(
            chatMessagePreview(long, markdown = false),
            ChatMessagePreviews.previewOf(message(null, Role.USER, long)),
        )
        assertNull(ChatMessagePreviews.previewOf(message(null, Role.USER, "hi")))
    }
}
