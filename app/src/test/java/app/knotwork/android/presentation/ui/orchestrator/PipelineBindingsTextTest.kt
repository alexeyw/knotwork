package app.knotwork.android.presentation.ui.orchestrator

import app.knotwork.android.R
import app.knotwork.android.domain.models.EntrySurface
import app.knotwork.android.domain.models.PipelineBindings
import app.knotwork.android.domain.models.PipelineCollision
import app.knotwork.android.domain.models.PipelineGraph
import app.knotwork.android.presentation.ui.common.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Replace confirmation's lines for what is bound to a pipeline.
 */
class PipelineBindingsTextTest {

    @Test
    fun `given nothing bound when listed then there are no lines`() {
        assertEquals(emptyList<UiText>(), PipelineBindingsText.lines(PipelineBindings()))
    }

    @Test
    fun `given every kind of binding when listed then each has a line, surfaces in declaration order`() {
        val lines = PipelineBindingsText.lines(
            PipelineBindings(
                isDefault = true,
                surfaces = setOf(EntrySurface.EXTERNAL_AUTOMATION, EntrySurface.SHARE, EntrySurface.QUICK_TILE),
                triggerCount = 2,
                chatCount = 1,
                callerNames = listOf("Full agent"),
            ),
        )

        assertEquals(
            listOf(
                UiText(R.string.orchestrator_library_binding_default),
                UiText(R.string.orchestrator_library_binding_share),
                UiText(R.string.orchestrator_library_binding_tile),
                UiText(R.string.orchestrator_library_binding_external),
                UiText.Plural(R.plurals.orchestrator_library_binding_triggers, 2, listOf(2)),
                UiText.Plural(R.plurals.orchestrator_library_binding_chats, 1, listOf(1)),
                UiText.of(R.string.orchestrator_library_binding_caller, "Full agent"),
            ),
            lines,
        )
    }

    @Test
    fun `given a caller name carrying line breaks when listed then it is quoted as one line`() {
        val line = PipelineBindingsText.lines(PipelineBindings(callerNames = listOf("Full\nagent"))).single()

        assertEquals(UiText.of(R.string.orchestrator_library_binding_caller, "Full agent"), line)
    }

    @Test
    fun `given a colliding pipeline with nothing bound when summarised then only its name is shown`() {
        val collision =
            PipelineCollision(PipelineGraph(id = "p", name = "file name"), "Library name", PipelineBindings())

        assertEquals(
            UiText.of(R.string.orchestrator_library_import_bundle_collision_item, "Library name"),
            PipelineBindingsText.summaryLine(collision),
        )
    }

    @Test
    fun `given a colliding pipeline with bindings when summarised then its name leads and they follow`() {
        val collision = PipelineCollision(
            incoming = PipelineGraph(id = "p", name = "file name"),
            existingName = "Library name",
            bindings = PipelineBindings(isDefault = true, chatCount = 3),
        )

        assertEquals(
            UiText.Joined(
                listOf(
                    UiText.of(R.string.orchestrator_library_import_bundle_collision_item, "Library name"),
                    UiText.Joined(
                        listOf(
                            UiText(R.string.orchestrator_library_binding_default),
                            UiText.Plural(R.plurals.orchestrator_library_binding_chats, 3, listOf(3)),
                        ),
                        "; ",
                    ),
                ),
                " — ",
            ),
            PipelineBindingsText.summaryLine(collision),
        )
    }
}
