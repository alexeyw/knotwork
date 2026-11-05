package app.knotwork.android.presentation.ui.triggers

import app.knotwork.android.domain.usecases.DEFAULT_FAILURE_MESSAGE
import app.knotwork.android.domain.usecases.NO_PROGRESS_JOURNAL_MESSAGE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A trigger journal row stores the reason a run failed as text. The two sentences the app
 * stores itself are shown in the interface language, rows written before this included; any
 * other reason is the run's diagnostic and is shown as written.
 */
class JournalRunErrorTest {

    private val labels = JournalLabels(
        justNow = "just now",
        minutesAgoFormat = "%d min ago",
        today = "today",
        yesterday = "yesterday",
        runFailed = "RUN FAILED (interface language)",
        noProgress = "NO PROGRESS (interface language)",
    )

    @Test
    fun `given the app's own stored sentences when shown then the interface-language words replace them`() {
        assertEquals(labels.runFailed, journalRunError(DEFAULT_FAILURE_MESSAGE, labels))
        assertEquals(labels.noProgress, journalRunError(NO_PROGRESS_JOURNAL_MESSAGE, labels))
    }

    @Test
    fun `given a run's own diagnostic when shown then it is kept as written`() {
        assertEquals("step-ceiling: 15/15 steps", journalRunError("step-ceiling: 15/15 steps", labels))
    }

    @Test
    fun `given rows stored by earlier releases when matched then the stored English is what is recognised`() {
        // The match is on the exact stored text; changing either constant would leave old rows English.
        assertEquals("Run failed", DEFAULT_FAILURE_MESSAGE)
        assertEquals(
            "The same work kept repeating without moving the task forward, so the run was ended.",
            NO_PROGRESS_JOURNAL_MESSAGE,
        )
    }
}
