package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keeps Kotlin's `assert` out of the instrumented tests.
 *
 * **Why.** Kotlin's `assert(value) { message }` throws only when the runtime has assertions enabled.
 * Every app process on Android is forked from the zygote, which runs with them disabled, so on a
 * device or an emulator the call evaluates its condition and returns: a test whose last line is
 * one passes whatever the condition says. Thirteen such lines in seven instrumented tests had
 * never checked anything when they were replaced by JUnit's `assertEquals`. On the JVM, Gradle
 * runs unit tests with assertions enabled, so `src/test` is left alone.
 *
 * **Why a source scan.** The `androidTest` source set is not on the JVM unit-test classpath, so the
 * guard reads its files. `src/androidTest` is a declared input of this module's test tasks, so an
 * edit there re-runs it. Comments are removed first ([ProductionSources.stripComments]), so a note
 * that names the call does not count.
 */
class InstrumentedAssertGuardTest {

    @Test
    fun `given an instrumented test then it checks with JUnit assertions, not Kotlin's assert`() {
        val files = File(ProductionSources.moduleDirectory(), ANDROID_TEST).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        assertTrue(
            "found no JUnit assertion in $ANDROID_TEST — the scan no longer matches the code",
            files.any { JUNIT_ASSERT.containsMatchIn(it.readText()) },
        )

        val calls = files.flatMap { file ->
            kotlinAssertLines(ProductionSources.stripComments(file.readText())).map { "${file.name}:$it" }
        }

        assertEquals(
            "Kotlin's assert() is a no-op on Android; use org.junit.Assert (assertEquals, assertTrue) instead",
            emptyList<String>(),
            calls,
        )
    }

    @Test
    fun `given sample lines then the scan flags each form of the call and nothing else`() {
        val code = listOf(
            "assert(count == 1)",
            "    assert (count == 1) { \"message\" }",
            "kotlin.assert(ok)",
            "assertEquals(1, count)",
            "Assert.assertTrue(ok)",
            "composeTestRule.onNodeWithText(label).assertIsDisplayed()",
            "assertThat(value).isEqualTo(1)",
            "// assert(count == 1)",
            "/** Kotlin's assert(value) is a no-op here. */",
        ).joinToString("\n")

        assertEquals(listOf(1, 2, 3), kotlinAssertLines(ProductionSources.stripComments(code)))
    }

    /** One-based numbers of the lines in [code] that call Kotlin's `assert`. */
    private fun kotlinAssertLines(code: String): List<Int> = code.lines()
        .withIndex()
        .filter { (_, line) -> KOTLIN_ASSERT.containsMatchIn(line) }
        .map { (index, _) -> index + 1 }

    private companion object {
        const val ANDROID_TEST = "src/androidTest"

        /**
         * A call to Kotlin's `assert`, plain or qualified. The look-behind leaves out a member or a
         * longer name that starts the same way (`assertEquals`, `.assertIsDisplayed()`).
         */
        val KOTLIN_ASSERT = Regex("""(?<![\w.])(kotlin\.)?assert\s*\(""")

        /** JUnit's assertions, for the check that the scan reads what it claims to. */
        val JUNIT_ASSERT = Regex("""\borg\.junit\.Assert\b""")
    }
}
