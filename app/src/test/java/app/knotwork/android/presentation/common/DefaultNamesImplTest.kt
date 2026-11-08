package app.knotwork.android.presentation.common

import android.content.Context
import app.knotwork.android.domain.repositories.AppLanguageRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The names the app gives what it creates: written in the interface language,
 * and a new chat's name recognised whatever language it was stored in — the
 * auto-rename must not stop when the language changed between creating a chat
 * and sending its first message.
 */
@RunWith(RobolectricTestRunner::class)
class DefaultNamesImplTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // Robolectric does not read the generated locale config (decisions §113.4),
    // so the shipped languages are given here.
    private val languages = mockk<AppLanguageRepository> { every { supportedLanguages() } returns listOf("en", "ru") }

    private val names get() = DefaultNamesImpl(context, languages)

    @Test
    fun `given the app in English when names are asked then they read as before the resources`() {
        assertEquals("New Chat", names.newChat())
        assertEquals("New Pipeline", names.newPipeline())
        assertEquals("Input", names.inputNode())
        assertEquals("Output", names.outputNode())
        assertEquals("Trip (copy)", names.copyOf("Trip"))
        assertEquals("Chat 5b7a2c", names.chatWithId("5b7a2c"))
        assertEquals("Imported Chat", names.importedChat())
        assertEquals("Trigger", names.triggerChat())
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when names are asked then they are Russian`() {
        assertEquals("Новый чат", names.newChat())
        assertEquals("Новый пайплайн", names.newPipeline())
        assertEquals("Trip (копия)", names.copyOf("Trip"))
    }

    @Test
    fun `given the app in English when a chat created in Russian is checked then it is still new`() {
        assertTrue(names.isNewChat("Новый чат"))
        assertTrue(names.isNewChat("New Chat"))
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when a chat from an earlier release is checked then it is still new`() {
        assertTrue(names.isNewChat("New Chat"))
        assertTrue(names.isNewChat("Новый чат"))
    }

    @Test
    fun `given a name a person gave when checked then the chat is not new`() {
        assertFalse(names.isNewChat("Trip to Rome"))
        assertFalse(names.isNewChat("new chat"))
    }
}
