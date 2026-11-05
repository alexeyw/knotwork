package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.domain.models.TestProbeFailure
import app.knotwork.android.domain.models.TestProbeResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings row's reason for a failed backend probe: the engine's own text as it came, the
 * app's sentence in the interface language otherwise — including for a record stored by an
 * earlier release, which carries neither.
 */
@RunWith(RobolectricTestRunner::class)
class ProbeFailureTextTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun failed(errorMessage: String? = null, failure: TestProbeFailure? = null) = TestProbeResult(
        tokensGenerated = 0,
        durationMs = 0L,
        timestampMs = 0L,
        success = false,
        errorMessage = errorMessage,
        failure = failure,
    )

    @Test
    fun `given the engine's text when worded then it is shown as it came`() {
        assertEquals("no dispatch library", probeFailureText(context, failed(errorMessage = "no dispatch library")))
    }

    @Test
    fun `given a failure the app words when worded then it reads in English`() {
        val text = probeFailureText(context, failed(failure = TestProbeFailure.NO_ACTIVE_MODEL))

        assertEquals("No active model selected.", text)
    }

    @Test
    @Config(qualifiers = "ru")
    fun `given the app in Russian when a typed failure is worded then it reads in Russian`() {
        val text = probeFailureText(context, failed(failure = TestProbeFailure.NO_ACTIVE_MODEL))

        assertEquals("Активная модель не выбрана.", text)
        assertEquals("Неизвестная ошибка", probeFailureText(context, failed()))
    }
}
