package app.knotwork.android.buildtools

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.VerificationException

/**
 * Fails when a release variant's Java resources register an SLF4J provider, so
 * no dependency can send library logging to logcat again. The decision is
 * [Slf4jProviders]'s; this task supplies the variant's resources: the Java
 * resources of its runtime classpath (the `android-java-res` view — for a library
 * its jar, for an AAR the jar inside it), which is what packaging merges.
 *
 * @property resources Java-resource jars and directories of the variant's dependencies.
 * @property checkedVariant Variant name, for the failure message.
 * @property stampFile Written on success, so the task can be skipped while no input changed.
 */
@CacheableTask
abstract class VerifyNoSlf4jProviderTask : DefaultTask() {

    @get:Classpath
    abstract val resources: ConfigurableFileCollection

    @get:Input
    abstract val checkedVariant: Property<String>

    @get:OutputFile
    abstract val stampFile: RegularFileProperty

    /** Scans the variant's resources for SLF4J provider registrations. */
    @TaskAction
    fun verify() {
        val jars = resources.files.filter { it.isFile }
        val directories = resources.files.filter { it.isDirectory }
        // No resources at all means the wiring broke, not that the app is clean.
        if (jars.isEmpty() && directories.isEmpty()) {
            throw VerificationException(
                "SLF4J provider check for `${checkedVariant.get()}` received no resources. Refusing to pass vacuously.",
            )
        }
        val registrations = Slf4jProviders.registrationsIn(jars, directories)
        if (registrations.isNotEmpty()) {
            throw VerificationException(
                "`${checkedVariant.get()}` would ship an SLF4J provider, which writes library logs — model output " +
                    "among them — to logcat past the app's redaction:\n" +
                    registrations.joinToString(separator = "\n") { it.format() } +
                    "\n\nExclude the module in `configurations.configureEach` in `app/build.gradle.kts`.",
            )
        }
        stampFile.get().asFile.writeText(
            "No SLF4J provider in ${jars.size} resource jar(s) and ${directories.size} directory(ies).\n",
        )
    }
}
