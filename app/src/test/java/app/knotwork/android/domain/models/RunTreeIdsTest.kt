package app.knotwork.android.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [RunTreeIds]: a child run id is derived from its parent, node and visit, and read back. */
class RunTreeIdsTest {

    @Test
    fun `given a parent, node and visit when a child id is made then it reads back to the same node and visit`() {
        val id = RunTreeIds.child("root", "pipe", 2)

        assertEquals("root::pipe::2", id)
        assertEquals("pipe" to 2, RunTreeIds.parentVisit(id, parentRunId = "root"))
    }

    @Test
    fun `given an id that is not a child of the parent when read then there is no visit`() {
        assertNull(RunTreeIds.parentVisit("other::pipe::2", parentRunId = "root"))
        assertNull(RunTreeIds.parentVisit("root::pipe::x", parentRunId = "root"))
        assertNull(RunTreeIds.parentVisit("root", parentRunId = "root"))
    }
}
