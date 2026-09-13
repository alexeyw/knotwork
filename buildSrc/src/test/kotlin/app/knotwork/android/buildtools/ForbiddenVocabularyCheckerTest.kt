package app.knotwork.android.buildtools

import app.knotwork.android.buildtools.ForbiddenVocabularyChecker.Family
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ForbiddenVocabularyChecker].
 *
 * The forbidden literals appear verbatim here on purpose: the Gradle task scans
 * `buildSrc/src/main` but not these tests, so the fixtures cannot make the guard
 * flag itself. Run with `./gradlew :buildSrc:test`.
 */
class ForbiddenVocabularyCheckerTest {

    @Test
    fun `given clean text when scanned then no violations`() {
        val files = mapOf(
            "README.md" to "# Knotwork — on-device AI agent for Android\n",
            "app/src/main/res/values/strings.xml" to "<string name=\"about_tagline\">An on-device AI agent for Android</string>\n",
            "catalog/Theme.kt" to "fun KnotworkTheme() = Unit\n",
        )

        assertTrue(ForbiddenVocabularyChecker.scan(files).isEmpty())
    }

    @Test
    fun `given each retired product name form when scanned then flagged as retired name`() {
        val files = mapOf(
            "a.xml" to "<string name=\"title\">Android AI Agent</string>\n",
            "b.kt" to "private const val TAG = \"AndroidAIAgent:InferenceLock\"\n",
            "c.yml" to "description: Report a defect in the On-Device AI Agent.\n",
            "d.md" to "The package moved from `ai.agent.android`.\n",
            "e.md" to "lower case android ai agent too\n",
        )

        val violations = ForbiddenVocabularyChecker.scan(files)

        assertTrue(violations.all { it.family == Family.RETIRED_PRODUCT_NAME })
        assertEquals(
            listOf(
                "a.xml:1 Android AI Agent",
                "b.kt:1 AndroidAIAgent",
                "c.yml:1 On-Device AI Agent",
                "d.md:1 ai.agent.android",
                "e.md:1 android ai agent",
            ),
            violations.map { "${it.file}:${it.line} ${it.token}" },
        )
    }

    @Test
    fun `given the name inside a longer identifier when scanned then still flagged`() {
        val files = mapOf("settings.gradle.kts" to "// was PersonalAndroidAIAgent\nfun AndroidAIAgentTheme() = Unit\n")

        assertEquals(
            listOf(1, 2),
            ForbiddenVocabularyChecker.scan(files).map { it.line },
        )
    }

    @Test
    fun `given the name wrapped across a KDoc line when scanned then flagged on its first line`() {
        val files = mapOf("x.kt" to "/**\n * Greets the user as Android\n * AI Agent on start.\n */\n")

        val violations = ForbiddenVocabularyChecker.scan(files)

        assertEquals(listOf("x.kt:2 Android AI Agent"), violations.map { "${it.file}:${it.line} ${it.token}" })
    }

    @Test
    fun `given the lower-case tagline or the hyphenated codename when scanned then not flagged`() {
        val files = mapOf(
            "CONTRIBUTING.md" to "Knotwork, an on-device AI agent for\nAndroid.\n",
            "settings.gradle.kts" to "rootProject.name = \"android-ai-agent\"\n",
            "google-services.json" to "\"project_id\": \"android-ai-agent\"\n",
        )

        assertTrue(ForbiddenVocabularyChecker.scan(files).isEmpty())
    }

    @Test
    fun `given each spelling of a planning number when scanned then flagged as planning number`() {
        val files = mapOf(
            "a.kt" to "// the Phase 28 cancellation contract\n",
            "b.kt" to "// Observed during the phase-40 directed test\n",
            "c.kt" to "fun `given pre-Phase21 node when decode`() = Unit\n",
            "d.html" to "* exported in PHASE 16 — 4/5.\n",
            "e.kt" to "// the duplication task 2/11 removed\n",
            "f.yml" to "branches: [phase/43]\n",
        )

        val violations = ForbiddenVocabularyChecker.scan(files)

        assertTrue(violations.all { it.family == Family.INTERNAL_PLANNING_NUMBER })
        assertEquals(
            listOf(
                "a.kt:1 Phase 28",
                "b.kt:1 phase-40",
                "c.kt:1 Phase21",
                "d.html:1 PHASE 16",
                "e.kt:1 task 2/11",
                "f.yml:1 phase/43",
            ),
            violations.map { "${it.file}:${it.line} ${it.token}" },
        )
    }

    @Test
    fun `given single-digit scenario step labels when scanned then not flagged`() {
        val files = mapOf(
            "IntegrationTest.kt" to "// ── Phase 1: schedule ──\n// Phase 2 — a fresh executor\n// two-phase commit\n",
        )

        assertTrue(ForbiddenVocabularyChecker.scan(files).isEmpty())
    }

    @Test
    fun `given hits in several files when scanned then ordered by file then line`() {
        val files = mapOf(
            "z.md" to "clean\nPhase 40\n",
            "a.md" to "Android AI Agent\n\n\nphase/12\n",
        )

        assertEquals(
            listOf("a.md:1", "a.md:4", "z.md:2"),
            ForbiddenVocabularyChecker.scan(files).map { "${it.file}:${it.line}" },
        )
    }

    @Test
    fun `given a violation when formatted then renders path line family and token`() {
        val violation = ForbiddenVocabularyChecker.scan(mapOf("docs/x.md" to "\nPhase 40\n")).single()

        assertEquals("docs/x.md:2: internal planning number `Phase 40`", violation.format())
    }
}
