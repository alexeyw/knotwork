package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps [app.knotwork.android.domain.constants.NotificationIds] the only place a
 * notification id is decided.
 *
 * **Why.** Each notifier once computed its own ids — `201 + hash % 1000` for approvals,
 * `301 + …` for clarifications, `401 + …` for ceilings, plus fixed 101 / 102 in two
 * services. Every family was sound on its own, and together they overlapped: a `notify`
 * of one family replaced another family's notification, a `cancel` removed it, the
 * ongoing notification of a parked run included. No single file was wrong, so no
 * review of one file could see it; the disjointness is asserted in `NotificationIdsTest`,
 * and this guard keeps every id inside what that test checks.
 *
 * **What it reads.** The production sources, comments removed. A file that posts a
 * notification (`.notify(`, `startForeground(`, `ForegroundInfo(`) must take the id from
 * `NotificationIds`, and no file but the registry may declare an id-shaped constant
 * (`…NOTIFICATION_ID…`, `BASE_ID`, `ID_MASK`). It is a census of spellings: an id
 * computed inline from a literal in a file that also names the registry is not seen —
 * the review checklist has to.
 */
class NotificationIdSourceGuardTest {

    @Test
    fun `every file that posts a notification takes its id from the registry`() {
        val offenders = postingFiles().filterValues { code -> !code.contains("NotificationIds.") }.keys

        assertEquals(
            "these files post a notification without taking its id from NotificationIds — add a family or " +
                "a fixed id there, so the disjointness test covers it",
            emptySet<String>(),
            offenders,
        )
    }

    @Test
    fun `no file but the registry declares a notification id constant`() {
        val offenders = ProductionSources.code
            .filterKeys { !it.endsWith("/domain/constants/NotificationIds.kt") }
            .mapValues { (_, code) -> ID_CONSTANT.findAll(code).map { it.value.trim() }.toList() }
            .filterValues { it.isNotEmpty() }

        assertEquals(
            "declare notification ids in NotificationIds, not beside the code that posts them",
            emptyMap<String, List<String>>(),
            offenders,
        )
    }

    @Test
    fun `the census sees the posting sites it claims to`() {
        // Without this the rules above pass vacuously if the patterns stop matching.
        val names = postingFiles().keys.map { it.substringAfterLast('/') }.toSet()
        for (expected in KNOWN_POSTERS) {
            assertTrue("$expected no longer recognised as posting a notification ($names)", expected in names)
        }
        assertTrue(ID_CONSTANT.containsMatchIn("        private const val NOTIFICATION_ID = 101"))
        assertTrue(ID_CONSTANT.containsMatchIn("const val BASE_ID = 23_900"))
    }

    private fun postingFiles(): Map<String, String> =
        ProductionSources.code.filterValues { code -> POSTING_CALL.containsMatchIn(code) }

    private companion object {
        /** A call that posts a notification under an id. */
        val POSTING_CALL = Regex("""\.notify\(|\bstartForeground\(|\bForegroundInfo\(""")

        /** A constant whose name says it is (part of) a notification id. */
        val ID_CONSTANT = Regex("""\bconst\s+val\s+\w*(NOTIFICATION_ID|BASE_ID|ID_MASK)\w*""")

        /** Files that post notifications today — one per posting shape the patterns cover. */
        val KNOWN_POSTERS = listOf(
            "ApprovalNotificationManager.kt",
            "ClarificationNotificationManager.kt",
            "CeilingNotificationManager.kt",
            "ScheduledTaskNotifierImpl.kt",
            "AgentForegroundService.kt",
            "AgentWorker.kt",
            "ModelDownloadWorker.kt",
        )
    }
}
