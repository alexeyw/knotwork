package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every AppFunctions entry point is registered in its module's manifest exactly as the
 * compiler generated it, and nothing else is registered as an AppFunctions service.
 *
 * **Why a test.** An entry point (`@AppFunctionServiceEntryPoint`) makes the AppFunctions
 * compiler generate a service class named by `serviceName` and an inventory in `assets/`
 * named by `appFunctionXmlFileName`. The manifest has to name both by hand. Any mismatch —
 * a service name one letter off, `v2` pointing at the inventory's old name, the permission
 * or the intent filter missing — builds, installs and runs, and shows only on a device, as
 * a function no caller can find or bind.
 *
 * **What it reads.** The Kotlin sources and the manifest of `:app` and `:tools-probe`,
 * comments removed from the Kotlin. For each entry point it expects, in the same module's
 * manifest, a `<service>` whose resolved name is `<entry point's package>.<serviceName>`,
 * exported, guarded by `BIND_APP_FUNCTION_SERVICE`, with the `AppFunctionService` intent
 * action and the `android.app.appfunctions.v2` property set to `<appFunctionXmlFileName>.xml`.
 * The file name must not carry `.xml` itself: the compiler appends it.
 */
class AppFunctionServiceManifestGuardTest {

    @Test
    fun `every entry point is registered as the compiler generated it`() {
        val offenders = modules.flatMap { module ->
            module.entryPoints.flatMap { entry ->
                val service = module.services[entry.generatedService]
                    ?: return@flatMap listOf("${module.name}: no <service> named ${entry.generatedService}")
                listOfNotNull(
                    "${entry.generatedService} is not exported".takeUnless { service.exported },
                    "${entry.generatedService} is not guarded by $BIND_PERMISSION"
                        .takeUnless { service.permission == BIND_PERMISSION },
                    "${entry.generatedService} has no $SERVICE_ACTION intent action"
                        .takeUnless { SERVICE_ACTION in service.actions },
                    (
                        "${entry.generatedService}: v2 is ${service.properties[V2_PROPERTY]}, " +
                            "the compiler writes ${entry.xmlFileName}.xml"
                        ).takeUnless { service.properties[V2_PROPERTY] == "${entry.xmlFileName}.xml" },
                    "${entry.source}: appFunctionXmlFileName ends in .xml, the compiler appends it"
                        .takeUnless { !entry.xmlFileName.endsWith(".xml") },
                ).map { "${module.name}: $it" }
            }
        }

        assertEquals("AppFunctions entry points and manifests disagree", emptyList<String>(), offenders)
    }

    @Test
    fun `no AppFunctions service is registered without an entry point`() {
        val offenders = modules.flatMap { module ->
            val generated = module.entryPoints.map { it.generatedService }.toSet()
            module.services.values
                .filter { SERVICE_ACTION in it.actions || it.permission == BIND_PERMISSION }
                .filterNot { it.name in generated }
                .map { "${module.name}: ${it.name}" }
        }

        assertEquals(
            "a manifest registers an AppFunctions service no entry point generates",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the census sees the entry points it claims to`() {
        // Keeps the rules above from passing vacuously if the patterns stop matching.
        val app = modules.single { it.name == "app" }
        val probe = modules.single { it.name == "tools-probe" }
        assertEquals(listOf("KnotworkAppFunctionService"), app.entryPoints.map { it.generatedService.simpleName() })
        assertEquals(listOf("EchoAppFunctionService"), probe.entryPoints.map { it.generatedService.simpleName() })
        assertTrue(app.services.size > 1)
    }

    /** An `@AppFunctionServiceEntryPoint` class and what it makes the compiler generate. */
    private data class EntryPoint(val source: String, val generatedService: String, val xmlFileName: String)

    /** A `<service>` declared in a manifest, with its name resolved against the namespace. */
    private data class Service(
        val name: String,
        val exported: Boolean,
        val permission: String?,
        val actions: Set<String>,
        val properties: Map<String, String>,
    )

    /** One module's entry points and manifest services. */
    private data class Module(val name: String, val entryPoints: List<EntryPoint>, val services: Map<String, Service>)

    private companion object {
        const val BIND_PERMISSION = "android.permission.BIND_APP_FUNCTION_SERVICE"
        const val SERVICE_ACTION = "android.app.appfunctions.AppFunctionService"
        const val V2_PROPERTY = "android.app.appfunctions.v2"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        val ENTRY_POINT = Regex("""@AppFunctionServiceEntryPoint\s*\(([^)]*)\)""")
        val SERVICE_NAME = Regex("""serviceName\s*=\s*"([^"]+)"""")
        val XML_FILE_NAME = Regex("""appFunctionXmlFileName\s*=\s*"([^"]+)"""")
        val PACKAGE = Regex("""^\s*package\s+([\w.`]+)""", RegexOption.MULTILINE)
        val NAMESPACE = Regex("""namespace\s*=\s*"([^"]+)"""")

        val modules: List<Module> by lazy {
            val root = ProductionSources.moduleDirectory().parentFile
            listOf("app", "tools-probe").map { name -> readModule(name, File(root, name)) }
        }

        fun readModule(name: String, dir: File): Module {
            val sources = File(dir, "src/main").walkTopDown().filter { it.isFile && it.extension == "kt" }
            val entryPoints = sources.flatMap { file ->
                val code = ProductionSources.stripComments(file.readText())
                val pkg = PACKAGE.find(code)?.groupValues?.get(1)?.replace("`", "").orEmpty()
                ENTRY_POINT.findAll(code).map { match ->
                    val args = match.groupValues[1]
                    EntryPoint(
                        source = file.name,
                        generatedService = "$pkg.${SERVICE_NAME.find(args)?.groupValues?.get(1)}",
                        xmlFileName = XML_FILE_NAME.find(args)?.groupValues?.get(1).orEmpty(),
                    )
                }
            }.toList()
            val namespace = NAMESPACE.find(File(dir, "build.gradle.kts").readText())!!.groupValues[1]
            return Module(name, entryPoints, readServices(File(dir, "src/main/AndroidManifest.xml"), namespace))
        }

        fun readServices(manifest: File, namespace: String): Map<String, Service> {
            val document = DocumentBuilderFactory.newInstance()
                .apply { isNamespaceAware = true }
                .newDocumentBuilder()
                .parse(manifest)
            val services = document.getElementsByTagName("service")
            return (0 until services.length).map { services.item(it) as Element }.associate { element ->
                val raw = element.getAttributeNS(ANDROID_NS, "name")
                val name = if (raw.startsWith(".")) namespace + raw else raw
                name to Service(
                    name = name,
                    exported = element.getAttributeNS(ANDROID_NS, "exported") == "true",
                    permission = element.getAttributeNS(ANDROID_NS, "permission").ifEmpty { null },
                    actions = element.descendants("action").map { it.getAttributeNS(ANDROID_NS, "name") }.toSet(),
                    properties = element.descendants("property").associate {
                        it.getAttributeNS(ANDROID_NS, "name") to it.getAttributeNS(ANDROID_NS, "value")
                    },
                )
            }
        }

        fun String.simpleName(): String = substringAfterLast('.')

        fun Element.descendants(tag: String): List<Element> {
            val nodes = getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as Element }
        }
    }
}
