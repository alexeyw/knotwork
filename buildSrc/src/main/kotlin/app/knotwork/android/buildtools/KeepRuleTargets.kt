package app.knotwork.android.buildtools

import java.io.File
import java.util.zip.ZipFile

/**
 * Finds keep rules that name a class, an annotation or a package that does not
 * exist on the classpath R8 is given.
 *
 * R8 says nothing about a rule that matches nothing: no warning, no info line in
 * the build log. So a rule written against the wrong name reads as protection in
 * review for as long as it exists. Four rules in `app/proguard-rules.pro` named
 * `androidx.appfunctions.AppFunctionInventory`, `…AppFunctionInvoker` and
 * `@androidx.appfunctions.AppFunction`, while the library ships them under
 * `…internal`, `…service.internal` and `…service`; a fifth kept
 * `org.tensorflow.lite.**`, a package no dependency has. None of them protected
 * anything, and nothing noticed.
 *
 * The property checked is narrow and exact: every fully-qualified name in a
 * class specification resolves to at least one class. A name may be a class
 * (`a.b.C`, `a.b.C$D`), an annotation (`@a.b.Ann`), a member type inside the
 * braces (`a.b.Type field;`), or a pattern — `*` and `?` stay within one package
 * segment, `**` crosses them, as in ProGuard's own syntax. Names with no package
 * (`*`, `**$$serializer`) and back-references (`<1>`) match by construction and
 * are left out. `-dontwarn` and `-dontnote` are left out on purpose: their whole
 * job is to name classes that are absent.
 *
 * Whether a rule that resolves is also *needed* is a different question, and the
 * two post-R8 guards (`verify<Variant>KeepRules`, `verify<Variant>Instantiable`)
 * answer the part of it that can be answered from the artefact.
 */
object KeepRuleTargets {

    /** Directives that take a class specification, and whose names must therefore exist. */
    private val CLASS_SPECIFICATION_DIRECTIVES = setOf(
        "-keep",
        "-keepclassmembers",
        "-keepclasseswithmembers",
        "-keepnames",
        "-keepclassmembernames",
        "-keepclasseswithmembernames",
        "-if",
        "-assumenosideeffects",
        "-assumenoexternalsideeffects",
        "-assumenoescapingparameters",
        "-assumenoexternalreturnvalues",
        "-assumevalues",
        "-checkdiscard",
        "-whyareyoukeeping",
        "-identifiernamestring",
    )

    /**
     * A dotted Java name, optionally carrying ProGuard wildcards. The first
     * character excludes `.`, so a member's `(...)` argument wildcard is never
     * taken for a name.
     */
    private val QUALIFIED_NAME = Regex("""[A-Za-z_$*?][\w$*?]*(?:\.[\w$*?]+)+""")

    /**
     * One name a rule relies on.
     *
     * @property line 1-based line of the rules file the directive starts on.
     * @property directive The directive keyword, without its modifiers (`-keep`).
     * @property name The name as written, `@` and `!` removed.
     */
    data class Target(val line: Int, val directive: String, val name: String)

    /**
     * A name that resolves to no class.
     *
     * @property target The name and where the rule that carries it starts.
     */
    data class Violation(val target: Target) {

        /** Human-readable one-liner for the aggregated failure message. */
        fun format(): String =
            "line ${target.line} (`${target.directive}`): `${target.name}` matches no class on the classpath"
    }

    /**
     * Lists every name the class specifications of [rules] rely on.
     *
     * @param rules Full text of a ProGuard/R8 configuration file.
     * @return The names in file order; a name used twice is listed twice, with each line.
     */
    fun targetsOf(rules: String): List<Target> {
        val targets = mutableListOf<Target>()
        var directive: String? = null
        var directiveLine = 0
        rules.lines().forEachIndexed { index, rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val body = if (line.startsWith("-")) {
                directive = line.substringBefore(' ').substringBefore(',')
                directiveLine = index + 1
                line.substringAfter(' ', missingDelimiterValue = "")
            } else {
                line
            }
            val current = directive ?: return@forEachIndexed
            if (current !in CLASS_SPECIFICATION_DIRECTIVES) return@forEachIndexed
            QUALIFIED_NAME.findAll(body).forEach { match ->
                targets += Target(line = directiveLine, directive = current, name = match.value)
            }
        }
        return targets
    }

    /**
     * Checks every name in [rules] against [classNames].
     *
     * @param rules Full text of a ProGuard/R8 configuration file.
     * @param classNames Binary names of every class R8 sees (`a.b.C$D`).
     * @return One [Violation] per name that matches no class; empty when all resolve.
     */
    fun verify(rules: String, classNames: Set<String>): List<Violation> =
        targetsOf(rules).filterNot { resolves(it.name, classNames) }.map(::Violation)

    /**
     * Collects the binary names of the classes in [jars] and [directories].
     *
     * Entries under `META-INF/` are skipped: a multi-release jar keeps copies of
     * its classes under `META-INF/versions/<n>/`, and those are not names a rule
     * can address.
     *
     * @param jars Jar files, each read through its central directory only.
     * @param directories Class output directories, walked recursively.
     * @return Every class as `a.b.C$D`.
     */
    fun classNamesIn(jars: Collection<File>, directories: Collection<File>): Set<String> {
        val names = HashSet<String>()
        jars.filter { it.isFile }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().map { it.name }.forEach { entry -> classNameOf(entry)?.let(names::add) }
            }
        }
        directories.filter { it.isDirectory }.forEach { root ->
            root.walkTopDown().filter { it.isFile }.forEach { file ->
                classNameOf(file.relativeTo(root).invariantSeparatorsPath)?.let(names::add)
            }
        }
        return names
    }

    /** The binary class name of a jar entry or relative path, or `null` when it is not a class. */
    private fun classNameOf(path: String): String? =
        path.takeIf { it.endsWith(".class") && !it.startsWith("META-INF/") }
            ?.removeSuffix(".class")
            ?.replace('/', '.')

    /** Whether [name] — a class, or a pattern — matches at least one of [classNames]. */
    private fun resolves(name: String, classNames: Set<String>): Boolean {
        val bare = name.removePrefix("@").removePrefix("!")
        if (bare.none { it == '*' || it == '?' }) return bare in classNames
        val literalPrefix = bare.takeWhile { it != '*' && it != '?' }
        val pattern = patternOf(bare)
        return classNames.any { it.startsWith(literalPrefix) && pattern.matches(it) }
    }

    /** ProGuard's class-name wildcards as a regex: `**` crosses packages, `*` and `?` do not. */
    private fun patternOf(name: String): Regex = Regex(
        buildString {
            var i = 0
            while (i < name.length) {
                when {
                    name.startsWith("**", i) -> append(".*").also { i += 2 }
                    name[i] == '*' -> append("[^.]*").also { i++ }
                    name[i] == '?' -> append("[^.]").also { i++ }
                    else -> append(Regex.escape(name[i].toString())).also { i++ }
                }
            }
        },
    )
}
