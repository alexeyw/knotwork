package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the app's release-only observer turns the crash collector on or off.
 *
 * `App.observeCrashReportingOptIn` mirrors the persisted consent into the
 * collector, and is installed only in release builds of the `full` flavour — which
 * is what the privacy policy's "debug builds never enable crash reporting" rests
 * on. The Settings switch and the settings reset used to flip the collector
 * directly as well, from any build: switching consent on in a debug build started
 * Crashlytics collection (a capture on a debug build saw its traffic). Every other
 * caller of `setEnabled` on the repository is refused here, by file.
 */
class CrashReportingConsentOwnerTest {

    @Test
    fun `given the production sources when the crash collector is switched then only the app observer does it`() {
        val callers = ProductionSources.code
            .filter { (path, code) ->
                path.substringAfterLast('/') !in IMPLEMENTATIONS &&
                    REPOSITORY.containsMatchIn(code) &&
                    SET_ENABLED.containsMatchIn(code)
            }
            .keys.map { it.substringAfterLast('/') }.toSortedSet()

        assertEquals(sortedSetOf("App.kt"), callers)
    }

    private companion object {
        /** A file that names the crash-reporting port at all. */
        val REPOSITORY = Regex("""\bCrashReportingRepository\b""")

        /** A call switching a collector — the repository's one consent method. */
        val SET_ENABLED = Regex("""\.setEnabled\(""")

        /** The port itself and its flavour implementations, which define `setEnabled`. */
        val IMPLEMENTATIONS = setOf(
            "CrashReportingRepository.kt",
            "FirebaseCrashReportingRepositoryImpl.kt",
            "NoOpCrashReportingRepository.kt",
        )
    }
}
