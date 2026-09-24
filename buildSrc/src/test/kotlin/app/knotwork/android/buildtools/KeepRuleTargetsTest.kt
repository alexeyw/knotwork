package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit coverage for [KeepRuleTargets].
 *
 * The first case is the defect the checker exists for, copied from
 * `app/proguard-rules.pro` as it stood: five rules whose names exist in no
 * dependency, next to the real names the AppFunctions library ships. R8 accepted
 * all five without a word.
 */
class KeepRuleTargetsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** What `androidx.appfunctions` 1.0.0-alpha09 and LiteRT-LM actually ship, plus a bystander. */
    private val classpath = setOf(
        "androidx.appfunctions.internal.AppFunctionInventory",
        "androidx.appfunctions.service.internal.AppFunctionInvoker",
        "androidx.appfunctions.service.AppFunction",
        "androidx.appfunctions.service.PlatformAppFunctionService",
        "com.google.ai.edge.litertlm.Engine",
        "kotlin.Metadata",
    )

    @Test
    fun `given the rules that named classes the library does not ship then each name is a violation`() {
        val rules = """
            # ─── AppFunctions ───
            -keep class * implements androidx.appfunctions.AppFunctionInventory { *; }
            -keep class * implements androidx.appfunctions.AppFunctionInvoker { *; }
            -keep @androidx.appfunctions.AppFunction class *
            -keepclassmembers class * {
                @androidx.appfunctions.AppFunction <methods>;
            }
            -keep class androidx.appfunctions.** { *; }
            -keep class com.google.ai.edge.** { *; }
            -keep class org.tensorflow.lite.** { *; }
        """.trimIndent()

        val violations = KeepRuleTargets.verify(rules, classpath)

        assertEquals(
            listOf(
                "line 2 (`-keep`): `androidx.appfunctions.AppFunctionInventory` matches no class on the classpath",
                "line 3 (`-keep`): `androidx.appfunctions.AppFunctionInvoker` matches no class on the classpath",
                "line 4 (`-keep`): `androidx.appfunctions.AppFunction` matches no class on the classpath",
                "line 5 (`-keepclassmembers`): `androidx.appfunctions.AppFunction` matches no class on the classpath",
                "line 10 (`-keep`): `org.tensorflow.lite.**` matches no class on the classpath",
            ),
            violations.map { it.format() },
        )
    }

    @Test
    fun `given the names the library ships then nothing is reported`() {
        val rules = """
            -keep class * implements androidx.appfunctions.internal.AppFunctionInventory { *; }
            -keep class * implements androidx.appfunctions.service.internal.AppFunctionInvoker { *; }
            -keep @androidx.appfunctions.service.AppFunction class *
            -keep class kotlin.Metadata { *; }
        """.trimIndent()

        assertEquals(emptyList<KeepRuleTargets.Violation>(), KeepRuleTargets.verify(rules, classpath))
    }

    @Test
    fun `given dontwarn and attribute directives then their names are not targets`() {
        val rules = """
            -dontwarn org.tensorflow.lite.**
            -dontnote com.missing.Thing
            -keepattributes *Annotation*,InnerClasses,Signature
            -renamesourcefileattribute SourceFile
        """.trimIndent()

        assertEquals(emptyList<KeepRuleTargets.Target>(), KeepRuleTargets.targetsOf(rules))
    }

    @Test
    fun `given a directive with modifiers and member types then every dotted name is a target`() {
        val rules = """
            -keep,includedescriptorclasses class **${'$'}${'$'}serializer { *; }
            -keepclasseswithmembers class * {
                kotlinx.serialization.KSerializer serializer(...);
            }
            -keepclassmembers class * {
                *** Companion;
            }
        """.trimIndent()

        assertEquals(
            listOf(
                KeepRuleTargets.Target(
                    line = 2,
                    directive = "-keepclasseswithmembers",
                    name = "kotlinx.serialization.KSerializer",
                ),
            ),
            KeepRuleTargets.targetsOf(rules),
        )
    }

    @Test
    fun `given a trailing comment then the name before it is still a target and the comment is not`() {
        val targets = KeepRuleTargets.targetsOf("-keep class a.b.C { *; } # was a.b.Old")

        assertEquals(listOf("a.b.C"), targets.map { it.name })
    }

    @Test
    fun `given a single-star package pattern then only a class directly in that package satisfies it`() {
        val rule = "-keep class * extends a.b.* { *; }"

        assertEquals(1, KeepRuleTargets.verify(rule, setOf("a.b.c.Deeper")).size)
        assertTrue(KeepRuleTargets.verify(rule, setOf("a.b.Direct")).isEmpty())
    }

    @Test
    fun `given a double-star package pattern then a class at any depth below satisfies it`() {
        val rule = "-keep class a.b.** { *; }"

        assertTrue(KeepRuleTargets.verify(rule, setOf("a.b.c.d.Deep")).isEmpty())
        assertEquals(1, KeepRuleTargets.verify(rule, setOf("a.bc.Sibling")).size)
    }

    @Test
    fun `given a nested class name then it resolves by its binary name`() {
        val rule = "-keep class a.b.Outer${'$'}Inner { *; }"

        assertTrue(KeepRuleTargets.verify(rule, setOf("a.b.Outer${'$'}Inner")).isEmpty())
        assertEquals(1, KeepRuleTargets.verify(rule, setOf("a.b.Outer")).size)
    }

    @Test
    fun `given a question mark then it stands for exactly one character of one segment`() {
        val rule = "-keep class a.b.C? { *; }"

        assertTrue(KeepRuleTargets.verify(rule, setOf("a.b.C1")).isEmpty())
        assertEquals(1, KeepRuleTargets.verify(rule, setOf("a.b.C12", "a.b.C")).size)
    }

    @Test
    fun `given jars and class directories then every class is indexed and metadata entries are not`() {
        val jar = temporaryFolder.newFile("lib.jar")
        ZipOutputStream(jar.outputStream()).use { zip ->
            listOf("a/b/C.class", "a/b/C\$D.class", "META-INF/versions/9/a/b/C.class", "a/b/readme.txt").forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.closeEntry()
            }
        }
        val classes = temporaryFolder.newFolder("classes")
        classes.resolve("x/y").mkdirs()
        classes.resolve("x/y/Z.class").writeText("")

        val names = KeepRuleTargets.classNamesIn(jars = listOf(jar), directories = listOf(classes))

        assertEquals(setOf("a.b.C", "a.b.C\$D", "x.y.Z"), names)
    }
}
