package app.knotwork.android.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RouteLabels]: the branch labels a run follows, and the one rule that matches them. */
class RouteLabelsTest {

    @Test
    fun `given a fixed-branch node then its branches are listed in port order`() {
        assertEquals(listOf("True", "False"), RouteLabels.fixedBranches(NodeType.IF_CONDITION))
        assertEquals(listOf("Item", "Done"), RouteLabels.fixedBranches(NodeType.QUEUE_PROCESSOR))
        assertEquals(listOf("Pass", "Retry", "Fail"), RouteLabels.fixedBranches(NodeType.EVALUATION))
        assertNull(RouteLabels.fixedBranches(NodeType.INTENT_ROUTER))
        assertNull(RouteLabels.fixedBranches(NodeType.LITE_RT))
    }

    @Test
    fun `given any letter case then a label matches its branch and nothing else`() {
        assertTrue(RouteLabels.matches("false", RouteLabels.FALSE))
        assertTrue(RouteLabels.matches("DONE", RouteLabels.DONE))
        assertFalse(RouteLabels.matches("falsey", RouteLabels.FALSE))
        assertFalse(RouteLabels.matches(null, RouteLabels.TRUE))
    }

    @Test
    fun `given a label when made canonical then it is spelled as the port or refused`() {
        assertEquals("Retry", RouteLabels.canonical(NodeType.EVALUATION, "retry"))
        assertNull(RouteLabels.canonical(NodeType.IF_CONDITION, "maybe"))
        assertNull(RouteLabels.canonical(NodeType.LITE_RT, "True"))
    }
}
