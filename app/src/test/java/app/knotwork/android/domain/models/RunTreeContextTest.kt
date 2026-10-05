package app.knotwork.android.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit coverage for [RunTreeContext].
 *
 * The two promises worth a test are the ones a careless edit would break silently:
 * a sub-pipeline must get the parent's holders, not copies, and a tree made for a
 * node run outside the engine must not be mistaken for an unlimited one.
 */
class RunTreeContextTest {

    @Test
    fun `given a tree when nested then depth grows by one and every holder is the same instance`() {
        val parent = RunTreeContext.standalone().copy(
            depth = 2,
            imageDelivery = RunImageDelivery(EngineImageInput("/a.jpg", width = 1, height = 1, sizeBytes = 1L)),
            imagePresent = true,
            origin = RunOrigin.TRIGGER,
        )

        val child = parent.nested("p", visitIndex = 1)

        assertEquals(3, child.depth)
        // The same header, so the whole tree derives its seeds from one run seed.
        assertSame(parent.header, child.header)
        assertSame(parent.budget, child.budget)
        assertSame(parent.stuckDetector, child.stuckDetector)
        assertSame(parent.contextNotes, child.contextNotes)
        assertSame(parent.imageDelivery, child.imageDelivery)
        assertSame(parent.generatingModel, child.generatingModel)
        assertEquals(true, child.imagePresent)
        assertEquals(RunOrigin.TRIGGER, child.origin)
        assertEquals(2, parent.depth)
    }

    @Test
    fun `given two nestings when their seed paths are compared then node and visit both tell them apart`() {
        val root = RunTreeContext.standalone()

        val first = root.nested("p", visitIndex = 0)
        val secondVisit = root.nested("p", visitIndex = 1)
        val otherNode = root.nested("q", visitIndex = 0)
        val grandchild = first.nested("p", visitIndex = 0)

        assertEquals("", root.seedPath)
        assertEquals("/p#0", first.seedPath)
        assertEquals("/p#0/p#0", grandchild.seedPath)
        assertEquals(4, setOf(root.seedPath, first.seedPath, secondVisit.seedPath, otherNode.seedPath).size)
    }

    @Test
    fun `given a standalone tree then its ledger is already at its ceiling`() {
        // Zero, not unlimited: an engine handed this tree must stop before its
        // first step rather than run without a ceiling.
        val tree = RunTreeContext.standalone()

        assertEquals(RunTerminationReason.StepCeiling(limit = 0, spent = 0), tree.budget.hardBreach())
    }

    @Test
    fun `given a standalone tree then it is a fresh root with no image and the interactive origin`() {
        val first = RunTreeContext.standalone()
        val second = RunTreeContext.standalone()

        assertEquals(0, first.depth)
        assertNull(first.imageDelivery)
        assertFalse(first.imagePresent)
        assertNull(first.generatingModel.localModelPath)
        assertNull(first.generatingModel.cloudLabel)
        assertEquals(RunOrigin.CHAT, first.origin)
        // Unshared: two nodes run outside the engine do not charge one ledger.
        assertNotSame(first.budget, second.budget)
        assertNotSame(first.generatingModel, second.generatingModel)
    }
}
