package app.knotwork.android.architecture

/**
 * Which production sources draw interface text, for the guards that census it
 * (`DisplayedLiteralGuardTest`, `CatalogStringsWiringTest`).
 *
 * The design system ships its showcase — catalog pages, previews and their
 * sample data — in its production source set, where the app never calls it.
 * Their English is sample content, not interface text, so those files are left
 * out by name. A file named like a preview that the interface does use is named
 * in [REAL_COMPONENTS] instead; `DisplayedLiteralGuardTest` checks that no
 * other left-out file declares anything the app refers to.
 */
internal object InterfaceTextScope {

    /** Catalog files whose name reads like a preview but which draw real interface. */
    val REAL_COMPONENTS: Map<String, String> = mapOf(
        "ChatMessagePreview.kt" to "The beginning of a long message that its bubble shows instead of the whole.",
        "LongMessagePreview.kt" to "The chat bubble's cut-off view of a long message, with its Read-whole action.",
    )

    /**
     * Whether a catalog file is showcase only, by its name.
     *
     * @param fileName The file's name, `Name.kt`.
     * @return `true` for catalog pages, catalog contents, previews and preview data.
     */
    fun isShowcase(fileName: String): Boolean = fileName !in REAL_COMPONENTS && SHOWCASE_NAME.matches(fileName)

    private val SHOWCASE_NAME = Regex(""".*(CatalogPage|CatalogContent|Preview|PreviewData)\.kt""")
}
