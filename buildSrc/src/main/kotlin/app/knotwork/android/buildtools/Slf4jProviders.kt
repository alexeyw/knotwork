package app.knotwork.android.buildtools

import java.io.File
import java.util.zip.ZipFile

/**
 * Finds the SLF4J logging providers a set of Java resources would register.
 *
 * ### Why the app must ship none
 *
 * Koog logs through SLF4J, and its Android client module brought `slf4j-simple`
 * in at runtime. That provider writes every `INFO`+ line to `System.err` — logcat
 * on Android — past the redaction the app applies to its own logs, and some of
 * those lines carry model output (a reasoning trace at `INFO`, a whole response
 * at `WARN`). With no provider, SLF4J 2 falls back to its no-op logger.
 *
 * ### Why it reads the resources, not the APK
 *
 * SLF4J 2 finds a provider only through a `ServiceLoader` registration — the
 * [SERVICE_ENTRY] file a provider library carries. The packaged APK cannot show
 * it: R8 rewrites a `ServiceLoader` lookup it can resolve into a direct
 * constructor call and drops the service file, so the APK holds the provider
 * class and no registration (measured on a local release build, 25 September
 * 2026). The dependencies' resources, before R8, still hold every registration
 * that would be compiled in.
 */
object Slf4jProviders {

    /** The `ServiceLoader` file through which an SLF4J 2 provider registers itself. */
    const val SERVICE_ENTRY = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"

    /**
     * One registration found.
     *
     * @property source The jar or directory holding it.
     * @property providers Provider class names it lists.
     */
    data class Registration(val source: String, val providers: List<String>) {
        /** Human-readable line for a failure message. */
        fun format(): String = "  - $source registers ${providers.joinToString()}"
    }

    /**
     * Every SLF4J provider registration in [jars] and [directories].
     *
     * @param jars Resource jars to scan.
     * @param directories Resource directories to scan.
     * @return The registrations that list at least one provider; empty when none would be found.
     */
    fun registrationsIn(jars: List<File>, directories: List<File>): List<Registration> {
        val fromJars = jars.filter { it.isFile }.mapNotNull { jar ->
            ZipFile(jar).use { zip ->
                zip.getEntry(SERVICE_ENTRY)?.let { entry ->
                    zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }?.let { Registration(jar.name, providerNames(it)) }
        }
        val fromDirectories = directories.mapNotNull { directory ->
            File(directory, SERVICE_ENTRY).takeIf { it.isFile }
                ?.let { Registration(directory.path, providerNames(it.readText())) }
        }
        return (fromJars + fromDirectories).filter { it.providers.isNotEmpty() }
    }

    /**
     * Class names listed in a `ServiceLoader` file: one per line, `#` starting a
     * comment, blank lines ignored — the format `java.util.ServiceLoader` reads.
     *
     * @param serviceFile The file's text.
     * @return The listed class names, in order.
     */
    fun providerNames(serviceFile: String): List<String> =
        serviceFile.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
}
