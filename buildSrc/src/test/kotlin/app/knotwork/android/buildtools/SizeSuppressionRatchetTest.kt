package app.knotwork.android.buildtools

import app.knotwork.android.buildtools.SizeSuppressionRatchet.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit coverage for [SizeSuppressionRatchet].
 *
 * Most cases are about reading: a ratchet that misses one spelling of a rule id, or
 * mistakes the text of a test fixture for an annotation, is either bypassable or
 * wrong about the tree. The enumeration that preceded this check counted a
 * suppression written inside a test's string literal as a real one — the fixture
 * case below is that file's shape.
 */
class SizeSuppressionRatchetTest {

    private fun entries(source: String, path: String = "Sample.kt"): List<String> =
        SizeSuppressionRatchet.scan(mapOf(path to source.trimIndent())).entries.map { "${it.target} | ${it.rule}" }

    @Test
    fun `given a suppression on a function then the entry names the function and the rule`() {
        val scan = SizeSuppressionRatchet.scan(
            mapOf(
                "a/Sample.kt" to """
                    class Sample {
                        @Suppress("LongMethod")
                        fun walk() = Unit
                    }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf(Entry("a/Sample.kt", "fun walk", "LongMethod")), scan.entries)
        assertEquals("a/Sample.kt :: fun walk :: LongMethod", scan.entries.single().toString())
    }

    @Test
    fun `given further annotations comments and modifiers before the declaration then they are skipped`() {
        val found = entries(
            """
            // Reason: a reason.
            @Suppress("LargeClass") // trailing reason
            @Singleton
            @OptIn(ExperimentalCoroutinesApi::class)
            /* a block comment */
            internal open class Engine
            """,
        )
        assertEquals(listOf("class Engine | LargeClass"), found)
    }

    @Test
    fun `given a suppression on a primary constructor then the target is the constructor`() {
        val found = entries(
            """
            class Engine
            @Inject
            // The list is assembled by the container.
            @Suppress("LongParameterList")
            constructor(
                private val a: A,
            )
            """,
        )
        assertEquals(listOf("constructor | LongParameterList"), found)
    }

    @Test
    fun `given a file-level suppression then the target is the file`() {
        val found = entries(
            """
            @file:Suppress("MatchingDeclarationName", "TooManyFunctions") // reason

            package sample
            """,
        )
        assertEquals(listOf("${SizeSuppressionRatchet.FILE_TARGET} | TooManyFunctions"), found)
    }

    @Test
    fun `given a multi-line annotation with comments inside then every listed rule is read`() {
        val found = entries(
            """
            @Suppress(
                // Reason: one sentence
                // over two lines.
                "TooManyFunctions",
                "LargeClass",
            )
            class Big
            """,
        )
        assertEquals(listOf("class Big | TooManyFunctions", "class Big | LargeClass"), found)
    }

    @Test
    fun `given rules outside the ratchet then only the ratcheted ones become entries`() {
        assertEquals(
            listOf("fun dispatch | LongMethod"),
            entries("@Suppress(\"LongMethod\", \"NestedBlockDepth\", \"UnusedParameter\") fun dispatch() = Unit"),
        )
        assertEquals(emptyList<String>(), entries("@Suppress(\"ReturnCount\") fun parse() = Unit"))
    }

    @Test
    fun `given every rule-id spelling detekt accepts then each is read as the rule`() {
        for (id in listOf(
            "LongMethod",
            "complexity:LongMethod",
            "complexity.LongMethod",
            "detekt:LongMethod",
            "detekt.LongMethod",
            "detekt:complexity:LongMethod",
            "detekt.complexity.LongMethod",
        )) {
            assertEquals(id, listOf("fun f | LongMethod"), entries("@Suppress(\"$id\") fun f() = Unit"))
        }
    }

    @Test
    fun `given a suppression of the whole rule set or of everything then all five rules are entries`() {
        for (id in listOf("all", "detekt:all", "detekt.all", "complexity", "detekt:complexity", "detekt.complexity")) {
            val rules = entries("@Suppress(\"$id\") fun f() = Unit").map { it.substringAfter(" | ") }
            assertEquals(id, SizeSuppressionRatchet.RULES, rules.toSet())
        }
        assertEquals(emptyList<String>(), entries("@Suppress(\"style\", \"detekt:style:MagicNumber\") fun f() = Unit"))
    }

    @Test
    fun `given SuppressWarnings qualified names use-site targets and names arrays then all are read`() {
        assertEquals(listOf("fun a | LongMethod"), entries("@SuppressWarnings(\"LongMethod\") fun a() = Unit"))
        assertEquals(listOf("fun b | LongMethod"), entries("@kotlin.Suppress(\"LongMethod\") fun b() = Unit"))
        assertEquals(listOf("val c | LongMethod"), entries("@get:Suppress(\"LongMethod\") val c: Int get() = 1"))
        assertEquals(
            listOf("fun d | LongMethod", "fun d | LargeClass"),
            entries("@Suppress(names = [\"LongMethod\", \"LargeClass\"]) fun d() = Unit"),
        )
    }

    @Test
    fun `given suppression text inside comments then nothing is found`() {
        val found = entries(
            """
            /**
             * Example: `@Suppress("LongMethod")` belongs on the function.
             */
            // @Suppress("LargeClass")
            /* outer /* nested @Suppress("TooManyFunctions") */ still a comment @Suppress("LongMethod") */
            fun f() = Unit
            """,
        )
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `given suppression text inside string literals then nothing is found`() {
        // The shape of a test fixture that once counted as a real suppression.
        val found = entries(
            "val source = \"\"\"\n" +
                "    @Suppress(\"LongParameterList\") // Brand-stable public API.\n" +
                "    @Composable\n" +
                "    fun Button() = Unit\n" +
                "\"\"\"\n" +
                "val plain = \"@Suppress(\\\"LongMethod\\\") \${listOf(\"@Suppress(\\\"LargeClass\\\")\")}\"\n" +
                "val ch = '\"'\n" +
                "@Suppress(\"LongMethod\") fun real() = Unit\n",
        )
        assertEquals(listOf("fun real | LongMethod"), found)
    }

    @Test
    fun `given an argument that is not a plain literal then the suppression is unreadable`() {
        val scan = SizeSuppressionRatchet.scan(
            mapOf(
                "A.kt" to "@Suppress(RULE) fun a() = Unit",
                "B.kt" to "\n@Suppress(\"Long\$suffix\") fun b() = Unit",
            ),
        )
        assertEquals(emptyList<Entry>(), scan.entries)
        assertEquals(listOf("A.kt" to 1, "B.kt" to 2), scan.unreadable.map { it.path to it.line })
        assertTrue(scan.unreadable.first().toString().startsWith("A.kt:1: "))
    }

    @Test
    fun `given the declaration shapes in the repository then each target reads as written`() {
        assertEquals(listOf("fun ChatHomeState.toViewState | LongMethod"), entries(
            "@Suppress(\"LongMethod\")\nfun ChatHomeState.toViewState(x: Int): ViewState = TODO()",
        ))
        assertEquals(listOf("fun List<String>.joined | LongMethod"), entries(
            "@Suppress(\"LongMethod\")\nfun <T : Any> List<String>.joined(): String = \"\"",
        ))
        assertEquals(listOf("fun invoke | LongMethod"), entries(
            "@Suppress(\"LongMethod\")\noperator fun invoke(\n    a: Int,\n): Int = a",
        ))
        assertEquals(listOf("interface Codec | TooManyFunctions"), entries(
            "@Suppress(\"TooManyFunctions\")\nfun interface Codec { fun f() }",
        ))
        assertEquals(listOf("object | TooManyFunctions"), entries(
            "class A {\n    @Suppress(\"TooManyFunctions\")\n    companion object {}\n}",
        ))
        assertEquals(listOf("var Foo.bar | LongMethod"), entries(
            "@Suppress(\"LongMethod\") var Foo.bar: Int\n    get() = 1\n    set(v) {}",
        ))
        assertEquals(listOf("? | LongMethod"), entries("@Suppress(\"LongMethod\")"))
    }

    @Test
    fun `given labels and annotations without arguments then they are not mistaken for suppressions`() {
        val found = entries(
            """
            fun f() = flow {
                return@flow
            }
            @Composable fun g() = this@Outer.h()
            """,
        )
        assertEquals(emptyList<String>(), found)
    }

    @Test
    fun `given a list file then comments and blank lines are ignored and fields are read`() {
        val listed = SizeSuppressionRatchet.parseList(
            """
            # header

            a/B.kt :: class B :: LargeClass
              c/D.kt :: fun e :: LongMethod
            """.trimIndent(),
        )
        assertEquals(listOf(Entry("a/B.kt", "class B", "LargeClass"), Entry("c/D.kt", "fun e", "LongMethod")), listed)
    }

    @Test
    fun `given a malformed list line or an unknown rule then parsing fails`() {
        for (line in listOf("a/B.kt :: class B", "a/B.kt :: class B :: MagicNumber", "a/B.kt ::  :: LongMethod")) {
            try {
                SizeSuppressionRatchet.parseList(line)
                fail("parsed: $line")
            } catch (expected: SizeSuppressionRatchet.ParseException) {
                assertTrue(expected.message!!.contains(line.trim()))
            }
        }
    }

    @Test
    fun `given sources and a list then unlisted and stale entries are reported as multisets`() {
        val a = Entry("A.kt", "constructor", "LongParameterList")
        val b = Entry("B.kt", "fun f", "LongMethod")
        val c = Entry("C.kt", "class C", "LargeClass")
        val verdict = SizeSuppressionRatchet.compare(found = listOf(a, a, b), listed = listOf(a, c))
        assertEquals(listOf(a, b), verdict.unlisted)
        assertEquals(listOf(c), verdict.stale)
        assertEquals(false, verdict.clean)
        assertTrue(SizeSuppressionRatchet.compare(listOf(b, a), listOf(a, b)).clean)
    }
}
