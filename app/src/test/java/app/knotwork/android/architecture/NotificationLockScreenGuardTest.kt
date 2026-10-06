package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps every notification that carries what the user or the model wrote down to
 * its title where the system hides sensitive content (`lockScreenVersion`).
 *
 * **Why.** No notification set a visibility or a public version, so each showed
 * whatever the platform default allows — on a lock screen set to show all
 * content, the tool call awaiting approval, the model's question, the run's
 * answer. The rule is easy to forget for the next notification: it compiles and
 * renders the same on an unlocked phone, so nothing but a census notices.
 *
 * **What it reads.** The production sources, comments removed. Each file must
 * call `lockScreenVersion(` once per notification builder it constructs
 * (`NotificationCompat.Builder(` / `Notification.Builder(`), so a second builder
 * added to a file that already redacts one is caught too. A file whose
 * notifications show no such text is listed in [WITHOUT_USER_CONTENT] with the
 * reason, and the list may not keep a file that no longer builds one.
 */
class NotificationLockScreenGuardTest {

    @Test
    fun `every notification builder sets a lock-screen version or is listed with its reason`() {
        val offenders = builderFiles()
            .filterKeys { path -> path.substringAfterLast('/') !in WITHOUT_USER_CONTENT }
            .mapValues { (_, code) -> BUILDER.findAll(code).count() to LOCK_SCREEN_VERSION.findAll(code).count() }
            .filterValues { (builders, versions) -> builders != versions }

        assertEquals(
            "these files build a notification without lockScreenVersion(...) — give it a title-only version " +
                "for the lock screen, or list the file in WITHOUT_USER_CONTENT with the reason its text is safe " +
                "there (builders to versions)",
            emptyMap<String, Pair<Int, Int>>(),
            offenders,
        )
    }

    @Test
    fun `every listed file still builds a notification`() {
        val builders = builderFiles().keys.map { it.substringAfterLast('/') }.toSet()

        assertEquals(
            "listed in WITHOUT_USER_CONTENT but no longer builds a notification — remove the entry",
            emptySet<String>(),
            WITHOUT_USER_CONTENT.keys - builders,
        )
    }

    @Test
    fun `the census sees the builders it claims to`() {
        // Without this the rules above pass vacuously if the patterns stop matching.
        val names = builderFiles().keys.map { it.substringAfterLast('/') }.toSet()
        for (expected in KNOWN_REDACTING) {
            assertTrue("$expected no longer recognised as building a notification ($names)", expected in names)
        }
        assertTrue(BUILDER.containsMatchIn("val b = NotificationCompat.Builder(context, channel)"))
        assertTrue(BUILDER.containsMatchIn("Notification.Builder(context, channel)"))
        assertTrue(LOCK_SCREEN_VERSION.containsMatchIn(".lockScreenVersion(context, channelId, icon, title)"))
    }

    private fun builderFiles(): Map<String, String> = ProductionSources.code
        .filterKeys { !it.endsWith("/presentation/notifications/LockScreenVersion.kt") }
        .filterValues { code -> BUILDER.containsMatchIn(code) }

    private companion object {
        /** A notification builder being constructed. */
        val BUILDER = Regex("""\bNotification(Compat)?\.Builder\(""")

        /** The call that gives a notification its title-only lock-screen version. */
        val LOCK_SCREEN_VERSION = Regex("""\.lockScreenVersion\(""")

        /** Files whose notifications show nothing the user or the model wrote, and why. */
        val WITHOUT_USER_CONTENT = mapOf(
            "AgentForegroundNotification.kt" to
                "the agent's status line: fixed app strings, at most the name of the tool in use",
            "ModelDownloadWorker.kt" to "the model file being downloaded and its progress",
            "CeilingNotificationManager.kt" to "how many steps or tokens a run spent against its limit",
        )

        /** Files that redact today — one per kind of content they carry. */
        val KNOWN_REDACTING = listOf(
            "ApprovalNotificationManager.kt",
            "ClarificationNotificationManager.kt",
            "ScheduledTaskNotifierImpl.kt",
        )
    }
}
