package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Census of every component the app's source manifests export, and the permission
 * each one demands of its caller.
 *
 * **Why an exact set.** An exported component is an entry point any installed app
 * can reach, and one without a permission is reachable by *every* installed app (and
 * by `adb`). The threat model once said the external-automation contract was "the
 * only entry surface reachable by code the user did not write" while the share
 * target sat beside it, exported without a permission and without the contract's
 * controls. Nothing could check that sentence, because nothing listed the exports.
 * This test is that list: a new export fails it until someone adds it here — and,
 * when it carries no permission and ships, names it in `SECURITY.md`.
 *
 * **Source manifests only.** Library components merged in at build time (WorkManager,
 * AppFunctions, Firebase) are not in these files; the merged release manifest has
 * its own gate in the build. Every source set that has a manifest is read, the
 * build-type and flavour overlays included, so an export added in an overlay is
 * not invisible here.
 */
class ExportedComponentInventoryTest {

    /** One exported component as a source manifest declares it. */
    private data class Export(val name: String, val permission: String?)

    /** Every source set with a manifest, and the exports its manifest declares. */
    private fun exportsBySourceSet(): Map<String, Set<Export>> {
        val src = File(ProductionSources.moduleDirectory(), "src")
        return src.listFiles().orEmpty()
            .filter { File(it, MANIFEST).isFile }
            .associate { set -> set.name to exportsOf(File(set, MANIFEST)) }
    }

    /**
     * The components [manifest] exports: every component element with
     * `android:exported="true"`, except one the overlay removes from the merge.
     */
    private fun exportsOf(manifest: File): Set<Export> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        return COMPONENT_TAGS.flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
            .filter { it.getAttribute("android:exported") == "true" && it.getAttribute("tools:node") != "remove" }
            .map {
                Export(name = it.getAttribute("android:name"), permission = it.attributeOrNull("android:permission"))
            }
            .toSet()
    }

    private fun Element.attributeOrNull(name: String): String? = getAttribute(name).takeIf { it.isNotEmpty() }

    @Test
    fun `given every source manifest then its exports and their permissions are exactly the inventoried ones`() {
        assertEquals(EXPECTED, exportsBySourceSet())
    }

    @Test
    fun `given the external-automation receiver then it still opts in to strict intent matching`() {
        val manifest = File(ProductionSources.moduleDirectory(), "src/main/$MANIFEST")
        val receivers = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
            .getElementsByTagName("receiver")
        val receiver = (0 until receivers.length).map { receivers.item(it) as Element }
            .single { it.getAttribute("android:name") == EXTERNAL_AUTOMATION_RECEIVER }

        assertEquals("enforceIntentFilter", receiver.getAttribute("android:intentMatchingFlags"))
    }

    @Test
    fun `given every shipping export without a permission then the threat model names it`() {
        // The debug overlay is excluded: it reaches no release build.
        val security = File(repositoryRoot(), "SECURITY.md").readText()
        val unguarded = exportsBySourceSet()
            .filterKeys { it != DEBUG_SOURCE_SET }
            .values.flatten()
            .filter { it.permission == null }
            .map { it.name.substringAfterLast('.') }

        assertEquals(setOf("MainActivity", "ShareReceiverActivity", "ExternalAutomationReceiver"), unguarded.toSet())
        unguarded.forEach { simpleName ->
            assertTrue("SECURITY.md does not name `$simpleName`", security.contains("`$simpleName`"))
        }
    }

    @Test
    fun `given the debug-only export then its class exists only in the debug source set`() {
        // The debug overlay's receiver is acceptable only because it never ships:
        // its class must not be reachable from a release build either.
        val module = ProductionSources.moduleDirectory()
        assertTrue(File(module, "src/debug/java/app/knotwork/android/debug/TriggerJournalDumpReceiver.kt").isFile)
        assertFalse(
            ProductionSources.code.keys.any {
                !it.startsWith("debug/") && it.endsWith("/TriggerJournalDumpReceiver.kt")
            },
        )
    }

    private fun repositoryRoot(): File {
        var dir: File? = ProductionSources.moduleDirectory().absoluteFile
        while (dir != null) {
            if (File(dir, "SECURITY.md").isFile) return dir
            dir = dir.parentFile
        }
        error("SECURITY.md not found above the module directory")
    }

    private companion object {
        const val MANIFEST = "AndroidManifest.xml"
        const val DEBUG_SOURCE_SET = "debug"
        const val EXTERNAL_AUTOMATION_RECEIVER = ".presentation.receivers.ExternalAutomationReceiver"

        /** The manifest elements that declare a component. */
        val COMPONENT_TAGS = listOf("activity", "activity-alias", "receiver", "service", "provider")

        /**
         * The inventory. A permission of `null` means **any installed app** can reach
         * the component; each such one that ships is named in `SECURITY.md`.
         */
        val EXPECTED: Map<String, Set<Export>> = mapOf(
            "main" to setOf(
                // Launcher: navigation only — a caller can open a chat by id, nothing runs.
                Export(".presentation.ui.MainActivity", permission = null),
                // Share target: must be startable on another app's behalf by the share sheet.
                Export(".presentation.share.ShareReceiverActivity", permission = null),
                Export(
                    ".presentation.tile.DutyPipelineTileService",
                    permission = "android.permission.BIND_QUICK_SETTINGS_TILE",
                ),
                // External contract: its callers cannot hold a permission of ours.
                Export(EXTERNAL_AUTOMATION_RECEIVER, permission = null),
            ),
            DEBUG_SOURCE_SET to setOf(Export(".debug.TriggerJournalDumpReceiver", permission = null)),
            "full" to emptySet(),
            "foss" to emptySet(),
        )
    }
}
