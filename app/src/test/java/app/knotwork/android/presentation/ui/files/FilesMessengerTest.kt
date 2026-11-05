package app.knotwork.android.presentation.ui.files

import android.content.Context
import android.content.res.Resources
import app.knotwork.android.R
import app.knotwork.android.presentation.state.TransientMessageRelay
import app.knotwork.android.presentation.ui.common.UiText
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Unit coverage for [FilesMessenger] — the single owner of what the Files
 * screen says when an operation does not go through.
 */
class FilesMessengerTest {

    private lateinit var relay: TransientMessageRelay
    private lateinit var context: Context
    private lateinit var resources: Resources
    private lateinit var messenger: FilesMessenger

    /** Captures the string resource a call resolves, so the sentence chosen is asserted rather than its text. */
    private val resolved = slot<Int>()

    @Before
    fun setup() {
        relay = mockk(relaxed = true)
        context = mockk()
        resources = mockk()
        every { context.resources } returns resources
        every { context.getString(capture(resolved)) } returns TEXT
        messenger = FilesMessenger(relay, context)
    }

    @Test
    fun `given a failure resource when reporting then it is resolved and posted`() {
        messenger.failure(R.string.files_message_import_failed)

        assertEquals(R.string.files_message_import_failed, resolved.captured)
        verify { relay.post(UiText.Dynamic(TEXT)) }
    }

    @Test
    fun `given every file staged when reporting a share then nothing is said`() {
        messenger.shareStaged(staged = 3, requested = 3)

        verify(exactly = 0) { relay.post(any()) }
    }

    @Test
    fun `given an empty selection when reporting a share then nothing is said`() {
        // Guards the boundary where "none staged" and "all staged" coincide:
        // an empty selection must not read as a total failure.
        messenger.shareStaged(staged = 0, requested = 0)

        verify(exactly = 0) { relay.post(any()) }
    }

    @Test
    fun `given no file staged when reporting a share then the total failure is surfaced`() {
        // The share sheet never opens in this case, so without a message the
        // tap does visibly nothing at all.
        messenger.shareStaged(staged = 0, requested = 2)

        assertEquals(R.string.files_message_share_failed, resolved.captured)
        verify { relay.post(UiText.Dynamic(TEXT)) }
    }

    @Test
    fun `given some files staged when reporting a share then the counts are surfaced`() {
        // The sheet does open here — carrying fewer files than were selected,
        // which is the case a person is least likely to notice.
        //
        // Stubbed on the exact arguments rather than matchers: `resources` is
        // not relaxed, so wrong counts would reach an unstubbed call and fail
        // the test. That is the assertion — the sentence has to name 2 of 3,
        // and pluralise on the 3 rather than on the 2.
        every {
            resources.getQuantityString(R.plurals.files_message_share_partial, 3, 2, 3)
        } returns TEXT

        messenger.shareStaged(staged = 2, requested = 3)

        verify { relay.post(UiText.Dynamic(TEXT)) }
    }

    private companion object {
        /** Stand-in for every resolved sentence; the test asserts which resource, not its wording. */
        const val TEXT = "resolved-snackbar-text"
    }
}
