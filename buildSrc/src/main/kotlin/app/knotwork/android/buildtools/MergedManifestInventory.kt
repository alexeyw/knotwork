package app.knotwork.android.buildtools

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reduces a merged Android manifest to the entries a dependency can add without
 * a line of this repository changing, so they can be compared against a
 * committed expectation.
 *
 * The source manifests are not what ships. The merger folds in every library's
 * own manifest, and a transitive dependency can bring a permission, an exported
 * component or a `queries` entry that nobody here wrote. The privacy policy
 * enumerates this app's permissions and the threat model names every export any
 * installed app can reach, so an addition from a library would make both wrong —
 * and the first anyone would learn of it is the store listing.
 *
 * Four kinds of entry, one line each:
 *
 * - `uses-permission NAME [maxSdkVersion=N]` (and `uses-permission-sdk-23`) — what
 *   the app asks the user or the system for;
 * - `permission NAME protectionLevel=LEVEL` — permissions the app *declares*,
 *   which other apps may then request;
 * - `exported TAG NAME permission=PERMISSION|none` — every component another app
 *   can start or bind, with what it must hold to do so (for a provider, also its
 *   `readPermission` / `writePermission`). The permission is part of the line on
 *   purpose: an export that loses it is a new surface under an old name;
 * - `queries package|intent|provider …` — package visibility the app claims.
 *
 * Components that are not exported are left out: they are no entry surface, and
 * an expectation that churned on every internal service a library adds would be
 * approved without being read.
 *
 * Pure `String -> List<String>` transforms, unit-tested in isolation.
 */
object MergedManifestInventory {

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /** The manifest elements that declare a component. */
    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    /** The top-level elements that request a permission. */
    private val PERMISSION_REQUEST_TAGS = listOf("uses-permission", "uses-permission-sdk-23")

    /**
     * The difference between an expectation and a manifest.
     *
     * @property added Entries the manifest has and the expectation does not.
     * @property removed Entries the expectation lists and the manifest no longer has.
     * @property duplicated Entries the expectation lists more than once; a duplicate
     *   would hide the removal of one of its copies.
     */
    data class Drift(val added: List<String>, val removed: List<String>, val duplicated: List<String>) {
        /** `true` when the expectation describes the manifest exactly. */
        val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty() && duplicated.isEmpty()
    }

    /**
     * Lists the entry surfaces [manifestXml] declares.
     *
     * @param manifestXml Content of a merged `AndroidManifest.xml`.
     * @return One line per entry, sorted and without duplicates.
     */
    fun of(manifestXml: String): List<String> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // A merged manifest never declares a document type; refusing one keeps
            // the parser from resolving entities for whatever file is the input.
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(manifestXml))).documentElement
        val lines = mutableSetOf<String>()

        root.children().forEach { element ->
            when (element.tagName) {
                in PERMISSION_REQUEST_TAGS -> {
                    val maxSdk = element.android("maxSdkVersion")?.let { " maxSdkVersion=$it" }.orEmpty()
                    lines += "${element.tagName} ${element.android("name")}$maxSdk"
                }
                "permission" ->
                    lines += "permission ${element.android("name")} " +
                        "protectionLevel=${element.android("protectionLevel") ?: "normal"}"
                "queries" -> element.children().forEach { lines += queryLine(it) }
                "application" -> element.children()
                    .filter { it.tagName in COMPONENT_TAGS && it.isExported() }
                    .forEach { lines += exportLine(it) }
            }
        }
        return lines.sorted()
    }

    /**
     * Reads a committed expectation: one entry per line, `#` starting a comment
     * (on its own line or after an entry), blank lines ignored.
     *
     * @param text Content of the expectation file.
     * @return The entries in file order, duplicates kept so [compare] can report them.
     */
    fun parseExpectation(text: String): List<String> =
        text.lines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }

    /**
     * Compares an expectation with the entries a manifest actually has.
     *
     * @param expected Entries from the committed expectation, as [parseExpectation] returns them.
     * @param actual Entries of the merged manifest, as [of] returns them.
     * @return What was added, what disappeared, and what the expectation lists twice.
     */
    fun compare(expected: List<String>, actual: List<String>): Drift {
        val expectedSet = expected.toSet()
        val actualSet = actual.toSet()
        return Drift(
            added = (actualSet - expectedSet).sorted(),
            removed = (expectedSet - actualSet).sorted(),
            duplicated = expected.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted(),
        )
    }

    /**
     * Whether another app can reach [this] component. Anything but a literal
     * `"false"` counts, so an `android:exported="@bool/…"` resolved at runtime is
     * listed rather than assumed closed. An absent attribute means not exported:
     * the merger refuses a component with an intent filter and no explicit value
     * at this target SDK.
     */
    private fun Element.isExported(): Boolean = android("exported")?.let { it != "false" } ?: false

    /**
     * One exported component as a line, with every permission that guards it: a
     * provider can be guarded by `readPermission` / `writePermission` alone, and
     * losing one of them opens it without `android:permission` changing.
     */
    private fun exportLine(component: Element): String = buildString {
        append("exported ${component.tagName} ${component.android("name")} ")
        append("permission=${component.android("permission") ?: "none"}")
        if (component.tagName == "provider") {
            component.android("readPermission")?.let { append(" readPermission=$it") }
            component.android("writePermission")?.let { append(" writePermission=$it") }
        }
    }

    /** One `queries` child as a line: a package, a provider authority, or an intent's filter. */
    private fun queryLine(element: Element): String = when (element.tagName) {
        "package" -> "queries package ${element.android("name")}"
        "provider" -> "queries provider ${element.android("authorities")}"
        "intent" -> "queries intent " + element.children().joinToString(" ") { part ->
            if (part.tagName == "data") {
                "data=" + part.androidAttributes().joinToString(",") { (key, value) -> "$key:$value" }
            } else {
                "${part.tagName}=${part.android("name")}"
            }
        }
        else -> "queries ${element.tagName}"
    }

    /** The value of the `android:` attribute [name], or `null` when absent. */
    private fun Element.android(name: String): String? =
        getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    /** Every `android:` attribute of this element, sorted by name. */
    private fun Element.androidAttributes(): List<Pair<String, String>> =
        (0 until attributes.length)
            .map { attributes.item(it) }
            .filter { it.namespaceURI == ANDROID_NS }
            .map { it.localName to it.nodeValue }
            .sortedBy { it.first }

    /** The child elements of this element, in document order. */
    private fun Element.children(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>()
}
