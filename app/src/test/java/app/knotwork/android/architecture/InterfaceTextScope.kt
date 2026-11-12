package app.knotwork.android.architecture

import java.io.File

/**
 * Which production sources draw interface text, for the guards that census it
 * (`DisplayedLiteralGuardTest`, `CatalogStringsWiringTest`, `BundledFontCoverageTest`).
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

    /**
     * The code that draws interface, comments removed: `:app`'s `presentation`
     * and `data/services` (notifications) in every production source set, and
     * `:catalog`'s production sources without its showcase ([isShowcase]).
     *
     * @return Code keyed by path from the repository root
     *   (`app/src/main/java/…`, `catalog/src/main/java/…`).
     */
    fun sources(): Map<String, String> {
        val app = ProductionSources.code
            .filterKeys { path -> APP_SCOPES.any { it in path } }
            .mapKeys { (path, _) -> "app/src/$path" }
        return app + catalogSources()
    }

    /**
     * `:catalog`'s production sources without its showcase ([isShowcase]),
     * comments removed.
     *
     * @return Code keyed by path from the repository root (`catalog/src/main/java/…`).
     */
    fun catalogSources(): Map<String, String> {
        val root = File(ProductionSources.moduleDirectory().parentFile, "catalog/src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !isShowcase(it.name) }
            .associate {
                "catalog/src/main/java/${it.relativeTo(root).invariantSeparatorsPath}" to
                    ProductionSources.stripComments(it.readText())
            }
    }

    private val APP_SCOPES = listOf("/presentation/", "/data/services/")

    private val SHOWCASE_NAME = Regex(""".*(CatalogPage|CatalogContent|Preview|PreviewData)\.kt""")
}
