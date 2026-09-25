package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Unit coverage for [Slf4jProviders]: what counts as a registration, in a jar and
 * in a resource directory, in the `ServiceLoader` file format.
 */
class Slf4jProvidersTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `given the jar slf4j-simple ships then its provider is a registration`() {
        // The service file as slf4j-simple 2.0.17 carries it.
        val simple = jar("slf4j-simple-2.0.17.jar", Slf4jProviders.SERVICE_ENTRY to "org.slf4j.simple.SimpleServiceProvider\n")
        val api = jar("slf4j-api-2.0.17.jar", "org/slf4j/LoggerFactory.class" to "")

        val found = Slf4jProviders.registrationsIn(listOf(api, simple), directories = emptyList())

        assertEquals(
            listOf(Slf4jProviders.Registration("slf4j-simple-2.0.17.jar", listOf("org.slf4j.simple.SimpleServiceProvider"))),
            found,
        )
    }

    @Test
    fun `given a resource directory with a registration then it is found`() {
        val directory = temporaryFolder.newFolder("java_res")
        File(directory, Slf4jProviders.SERVICE_ENTRY).apply { parentFile.mkdirs() }
            .writeText("ch.qos.logback.classic.spi.LogbackServiceProvider")

        val found = Slf4jProviders.registrationsIn(jars = emptyList(), directories = listOf(directory))

        assertEquals(listOf("ch.qos.logback.classic.spi.LogbackServiceProvider"), found.single().providers)
    }

    @Test
    fun `given a service file with only comments and blank lines then nothing is registered`() {
        val empty = jar("empty.jar", Slf4jProviders.SERVICE_ENTRY to "# no provider here\n\n   \n")

        assertEquals(emptyList<Slf4jProviders.Registration>(), Slf4jProviders.registrationsIn(listOf(empty), emptyList()))
    }

    @Test
    fun `given the ServiceLoader file format then comments and whitespace are stripped`() {
        assertEquals(
            listOf("a.First", "b.Second"),
            Slf4jProviders.providerNames("  a.First  # the default\n\n# b.Disabled\nb.Second\n"),
        )
    }

    private fun jar(name: String, vararg entries: Pair<String, String>): File {
        val file = File(temporaryFolder.root, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((path, text) in entries) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }
}
