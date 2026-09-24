package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationException

/**
 * Fails a release build when a keep rule names a class, annotation or package
 * that is not on the classpath R8 is given — a rule R8 silently matches with
 * nothing. The decision lives in [KeepRuleTargets], which is pure and
 * unit-tested; this task supplies the classes.
 *
 * It runs before R8, against the same classes R8 reads: the variant's
 * `ScopedArtifact.CLASSES` over every scope (the app, its modules and every
 * library) plus the SDK boot classpath, so a rule about a framework type
 * resolves too.
 *
 * @property rulesFile The app's own `proguard-rules.pro`. Library consumer rules
 *   and AGP's defaults are not checked: this repository cannot fix them.
 * @property classJars Jars of the variant's classes, from `toGet(CLASSES)`.
 * @property classDirectories Class directories of the variant, from `toGet(CLASSES)`.
 * @property bootClasspath The SDK's `android.jar` and optional libraries.
 * @property checkedVariant Variant name, for the failure message.
 * @property stampFile Written on success, so the task can be skipped while no input changed.
 */
@CacheableTask
abstract class VerifyKeepRuleTargetsTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rulesFile: RegularFileProperty

    @get:Classpath
    abstract val classJars: ListProperty<RegularFile>

    @get:Classpath
    abstract val classDirectories: ListProperty<Directory>

    @get:Classpath
    abstract val bootClasspath: ConfigurableFileCollection

    @get:Input
    abstract val checkedVariant: Property<String>

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Resolves every name in the rules file against the variant's classes. */
    @TaskAction
    fun verify() {
        val rules = rulesFile.get().asFile.readText()
        val targets = KeepRuleTargets.targetsOf(rules)
        val classNames = KeepRuleTargets.classNamesIn(
            jars = classJars.get().map { it.asFile } + bootClasspath.files,
            directories = classDirectories.get().map { it.asFile },
        )
        // Either empty set would make every rule pass or every rule fail for a
        // reason that is not the rules: refuse instead of answering.
        if (targets.isEmpty() || classNames.isEmpty()) {
            throw VerificationException(
                "Keep-rule target check for `${checkedVariant.get()}` has nothing to compare " +
                    "(${targets.size} rule name(s), ${classNames.size} class(es)). Refusing to pass vacuously.",
            )
        }

        val violations = KeepRuleTargets.verify(rules, classNames)
        if (violations.isNotEmpty()) {
            throw VerificationException(
                buildString {
                    appendLine(
                        "${violations.size} keep-rule name(s) in app/proguard-rules.pro match nothing on the " +
                            "`${checkedVariant.get()}` classpath:",
                    )
                    violations.forEach { appendLine("  ${it.format()}") }
                    appendLine()
                    append(
                        "R8 accepts such a rule without a word and it protects nothing. Correct the name to the " +
                            "one the dependency ships, or delete the rule and say what keeps the code instead.",
                    )
                },
            )
        }

        val stamp = stampFile.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText(targets.joinToString(separator = "\n", postfix = "\n") { "${it.line} ${it.name}" })
    }
}
