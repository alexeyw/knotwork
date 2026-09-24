package app.knotwork.android.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope
import java.io.File

/**
 * Shared Konsist scope for the architecture guard suite.
 *
 * The scope is pinned to the `app` module's **main** source set
 * ([PRODUCTION_SOURCE_PATH]) rather than [Konsist.scopeFromProject] /
 * [Konsist.scopeFromProduction] on purpose:
 *
 * - It excludes test sources, which legitimately cross layer boundaries
 *   (a `domain` use-case test may construct `data` fakes) and must not be
 *   constrained by these rules.
 * - It excludes anything outside the module's real source tree. The project
 *   root can contain unrelated copies of the codebase — notably stale git
 *   worktrees under `.claude/worktrees/` from parallel sessions — which a
 *   whole-project scope would parse and then flag with spurious, environment
 *   dependent violations. Pinning to a single directory subtree keeps the
 *   guard deterministic across machines and CI.
 */
internal object ArchitectureScope {
    /**
     * Path, relative to the Gradle root project directory, of the `app`
     * module's production source set.
     */
    private const val PRODUCTION_SOURCE_PATH = "app/src/main"

    /**
     * Konsist scope over the `app` module's production code, reused by every
     * test in the suite to avoid re-parsing the source tree per test.
     */
    val production: KoScope = Konsist.scopeFromDirectory(PRODUCTION_SOURCE_PATH)

    /**
     * Konsist scope over **every** production source set that ships: the `app` module's
     * `main`, flavour (`full`, `foss`) and build-type (`debug`) sets, and the `:catalog`
     * module's production sets.
     *
     * For the guards whose question is "what can the shipped app do", where [production]
     * is too narrow: the Crashlytics upload lives in `app/src/full`, and the image loader
     * the chat screen renders with lives in `:catalog`. Source sets are listed from disk
     * rather than named, so a new flavour or build type is in scope the day it appears;
     * test source sets are left out by name.
     */
    val allProductionSourceSets: KoScope by lazy {
        SHIPPING_MODULES
            .flatMap { module -> productionSourceSetsOf(module) }
            .map(Konsist::scopeFromDirectory)
            .reduce(KoScope::plus)
    }

    /** Modules whose code ships in the app. */
    private val SHIPPING_MODULES = listOf("app", "catalog")

    /**
     * Root-relative paths of [module]'s production source sets, e.g. `app/src/full`.
     *
     * Resolved against the repository root, the parent of the `app` module directory
     * [ProductionSources] finds: Konsist resolves these paths from the root.
     */
    private fun productionSourceSetsOf(module: String): List<String> {
        val root = ProductionSources.moduleDirectory().absoluteFile.parentFile
        val sources = File(root, "$module/src")
        val sets = sources.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .map { "$module/src/${it.name}" }
            .sorted()
        check(sets.isNotEmpty()) { "no production source sets under ${sources.path}" }
        return sets
    }
}
