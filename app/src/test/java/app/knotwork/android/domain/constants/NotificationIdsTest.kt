package app.knotwork.android.domain.constants

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the property [NotificationIds] exists for: no two notification families, and no
 * family and fixed id, can ever produce the same notification id.
 */
class NotificationIdsTest {

    @Test
    fun `given every family range and fixed id when compared pairwise then none overlap`() {
        val spans: List<Pair<String, IntRange>> =
            NotificationIds.Family.entries.map { it.name to it.range } +
                NotificationIds.FIXED.map { "fixed $it" to it..it }

        val overlaps = spans.indices.flatMap { i ->
            (i + 1 until spans.size)
                .filter { j -> spans[i].second.overlaps(spans[j].second) }
                .map { j -> "${spans[i].first} ${spans[i].second} ∩ ${spans[j].first} ${spans[j].second}" }
        }

        assertEquals(emptyList<String>(), overlaps)
    }

    @Test
    fun `given keys whose hashes are negative or extreme when idFor then the slot stays inside the range`() {
        // "polygenelubricants".hashCode() == Int.MIN_VALUE; the old `% 1000` fold sent
        // such keys below the base, into the neighbouring family.
        val keys = listOf("", "polygenelubricants", "a", "request-1", "session-z", "\u0000")
        assertEquals(Int.MIN_VALUE, "polygenelubricants".hashCode())

        for (family in NotificationIds.Family.entries) {
            for (key in keys) {
                val id = family.idFor(key)
                assertTrue("${family.name} put '$key' at $id, outside ${family.range}", id in family.range)
            }
        }
    }

    @Test
    fun `given the task result family when idFor then it keeps the slots it had before the registry`() {
        // Its notifications are auto-cancel results, but a slot change would still
        // strand any showing across the update; the registry adopted its formula.
        for (key in listOf("s1", "polygenelubricants", "7f3e2c1a-0000-4000-8000-000000000000")) {
            assertEquals(23_900 + (key.hashCode() and 0x0FFF), NotificationIds.Family.TASK_RESULT.idFor(key))
        }
    }

    @Test
    fun `given the pre-update formula when slot then it is the old signed fold`() {
        // The shade of an updated device still holds ids computed this way; a
        // "cleanup" of the formula would make them unreachable.
        assertEquals(
            201 + "s1".hashCode() % 1_000,
            NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.APPROVAL_BASE, "s1"),
        )
        assertEquals(
            301 - 648,
            NotificationIds.PreUpdate.slot(NotificationIds.PreUpdate.CLARIFICATION_BASE, "polygenelubricants"),
        )
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}
