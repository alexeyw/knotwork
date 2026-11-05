package app.knotwork.android.presentation.ui.taskmonitor

import app.knotwork.android.domain.models.NodeType
import app.knotwork.android.presentation.ui.common.UiText
import app.knotwork.design.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A running step is named by its node type in the interface language: the run
 * reports the type's code, and the task monitor used to show the code itself.
 */
class NodeTypeNameTest {

    @Test
    fun `given a node type's code when named then the type's name resource is used`() {
        assertEquals(UiText(R.string.knotwork_node_type_lite_rt_name), nodeTypeName("LITE_RT"))
    }

    @Test
    fun `given every node type the engine runs when named then each has a name of its own`() {
        val names = NodeType.entries.map { nodeTypeName(it.name) }

        assertTrue(names.none { it is UiText.Dynamic })
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `given a code no type answers to when named then it is shown as it came`() {
        assertEquals(UiText.Dynamic("node-7"), nodeTypeName("node-7"))
    }
}
