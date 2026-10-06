package app.knotwork.android.presentation.common

import app.knotwork.android.domain.models.ApprovalRequestContext
import app.knotwork.android.domain.models.ApprovalRequestSource
import app.knotwork.design.components.chat.HitlRequestContext
import app.knotwork.design.components.chat.HitlRequestSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [toHitlRequestContext] — the one translation the approval card and the
 * approval notification both read: every source keeps its meaning, a trigger
 * keeps its name (or its lack of one), and the text and flags pass unchanged.
 */
class HitlRequestMappingTest {

    @Test
    fun `given each source when mapped then the catalog names the same source`() {
        val expected = mapOf(
            ApprovalRequestSource.Chat to HitlRequestSource.Chat,
            ApprovalRequestSource.Shared to HitlRequestSource.Shared,
            ApprovalRequestSource.Trigger("Morning briefing") to HitlRequestSource.Trigger("Morning briefing"),
            ApprovalRequestSource.Trigger(null) to HitlRequestSource.Trigger(null),
            ApprovalRequestSource.ScheduledTask to HitlRequestSource.ScheduledTask,
            ApprovalRequestSource.QuickTile to HitlRequestSource.QuickTile,
            ApprovalRequestSource.OtherApp to HitlRequestSource.OtherApp,
        )

        expected.forEach { (domain, catalog) ->
            val mapped = ApprovalRequestContext(
                domain,
                "do it",
                shortened = false,
                hadImage = false,
            ).toHitlRequestContext()
            assertEquals(domain.toString(), catalog, mapped.source)
        }
    }

    @Test
    fun `given the text and its flags when mapped then they pass unchanged`() {
        val mapped = ApprovalRequestContext(
            ApprovalRequestSource.Chat,
            request = null,
            shortened = true,
            hadImage = true,
        )
            .toHitlRequestContext()

        assertEquals(
            HitlRequestContext(HitlRequestSource.Chat, request = null, shortened = true, hadImage = true),
            mapped,
        )
    }
}
