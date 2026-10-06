package app.knotwork.design.components.chat

import app.knotwork.design.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [label] — the one place that names whose words a request is. The card and the
 * approval notification both read it, so a wrong label here is wrong on both.
 */
class HitlRequestContextTest {

    @Test
    fun `given each source when labelled then it names that source`() {
        val expected = mapOf(
            HitlRequestSource.Chat to HitlRequestLabel(R.string.knotwork_hitl_request_source_chat),
            HitlRequestSource.Shared to HitlRequestLabel(R.string.knotwork_hitl_request_source_shared),
            HitlRequestSource.Trigger("Morning briefing") to
                HitlRequestLabel(R.string.knotwork_hitl_request_source_trigger, "Morning briefing"),
            HitlRequestSource.Trigger(null) to HitlRequestLabel(R.string.knotwork_hitl_request_source_trigger_deleted),
            HitlRequestSource.ScheduledTask to HitlRequestLabel(R.string.knotwork_hitl_request_source_scheduled),
            HitlRequestSource.QuickTile to HitlRequestLabel(R.string.knotwork_hitl_request_source_tile),
            HitlRequestSource.OtherApp to HitlRequestLabel(R.string.knotwork_hitl_request_source_other_app),
        )

        expected.forEach { (source, label) -> assertEquals(source.toString(), label, asked(source).label) }
    }

    @Test
    fun `given an instruction the agent scheduled when labelled then it never says the user asked`() {
        val label = asked(HitlRequestSource.ScheduledTask).label.text

        assertEquals(R.string.knotwork_hitl_request_source_scheduled, label)
    }

    @Test
    fun `given an image sent with text in the chat when labelled then the label says so`() {
        val context = asked(HitlRequestSource.Chat).copy(hadImage = true)

        assertEquals(HitlRequestLabel(R.string.knotwork_hitl_request_source_chat_image), context.label)
    }

    @Test
    fun `given an image sent without text from any source when labelled then it says an image was sent`() {
        for (source in listOf(HitlRequestSource.Chat, HitlRequestSource.Shared)) {
            val context = HitlRequestContext(source, request = null, shortened = false, hadImage = true)

            assertEquals(HitlRequestLabel(R.string.knotwork_hitl_request_source_image_only), context.label)
        }
    }

    private fun asked(source: HitlRequestSource) =
        HitlRequestContext(source = source, request = "do it", shortened = false, hadImage = false)
}
