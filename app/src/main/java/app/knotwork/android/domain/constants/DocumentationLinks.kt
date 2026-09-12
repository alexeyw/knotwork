package app.knotwork.android.domain.constants

/**
 * Every documentation entry point the app can open, generated from the build.
 *
 * DO NOT EDIT BY HAND. The list lives in `buildSrc`
 * (`DocumentationLinkRegistry`) because the build resolves each entry's
 * anchor against the real Markdown under `docs` before it lets a build pass, and
 * `buildSrc` cannot read this module. Run `./gradlew :app:generateDocumentationLinks`
 * and commit the result; `verifyDocumentationLinks` fails `check` on drift.
 *
 * The entries carry no URL: the repository ref a build links against depends
 * on the build type, so the URL is assembled at the edge by
 * `RepositoryLinks.blobUrl`.
 */
object DocumentationLinks {

    /**
     * One document, or one section of one.
     *
     * @property id Stable key the UI names the entry by.
     * @property path Repository-relative path under `docs/`.
     * @property anchor Heading anchor without the leading `#`, or `null` for
     *   the top of the document.
     */
    data class Entry(val id: String, val path: String, val anchor: String?)

    /** Id of `docs/user-guide.md`. */
    const val ID_USER_GUIDE: String = "user-guide"

    /** Id of `docs/faq.md`. */
    const val ID_FAQ: String = "faq"

    /** Id of `docs/cookbook.md`. */
    const val ID_COOKBOOK: String = "cookbook"

    /** Id of `docs/troubleshooting.md`. */
    const val ID_TROUBLESHOOTING: String = "troubleshooting"

    /** Id of `docs/external-automation.md`. */
    const val ID_EXTERNAL_AUTOMATION: String = "external-automation"

    /** Id of `docs/user-guide.md#adding-an-mcp-server`. */
    const val ID_MCP_SETUP: String = "mcp-setup"

    /** Id of `docs/user-guide.md#triggers`. */
    const val ID_TRIGGERS: String = "triggers"

    /** Every entry, in the order the About screen lists them. */
    val ENTRIES: List<Entry> = listOf(
        Entry(id = ID_USER_GUIDE, path = "docs/user-guide.md", anchor = null),
        Entry(id = ID_FAQ, path = "docs/faq.md", anchor = null),
        Entry(id = ID_COOKBOOK, path = "docs/cookbook.md", anchor = null),
        Entry(id = ID_TROUBLESHOOTING, path = "docs/troubleshooting.md", anchor = null),
        Entry(id = ID_EXTERNAL_AUTOMATION, path = "docs/external-automation.md", anchor = null),
        Entry(id = ID_MCP_SETUP, path = "docs/user-guide.md", anchor = "adding-an-mcp-server"),
        Entry(id = ID_TRIGGERS, path = "docs/user-guide.md", anchor = "triggers"),
    )

    /**
     * Looks an entry up by its stable id.
     *
     * @param id One of the `ID_` constants above.
     * @return The entry, or `null` for an unknown id.
     */
    fun byId(id: String): Entry? = ENTRIES.firstOrNull { it.id == id }
}
