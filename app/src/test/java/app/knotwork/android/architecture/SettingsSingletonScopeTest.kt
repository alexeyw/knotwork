package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every production class that implements a settings section is scoped `@Singleton` on the class
 * itself, not only through its Hilt bindings.
 *
 * **Why.** Each section is bound separately, and a scope on a `@Binds` method caches per binding:
 * with the class unscoped, Dagger builds the class through an uncached provider and wraps it once
 * per scoped binding — observed in the generated component, where `SettingsRepository` and
 * `GenerationSettings` each held their own `DoubleCheck` over a provider that called the
 * constructor anew. The settings implementation keeps state in memory (the MCP credential cache, the
 * mutex serialising server edits, the Hugging Face token flow), so two instances disagree: an edit
 * made through one section is not seen through another. The class-level scope makes every binding
 * resolve to one instance.
 *
 * **Which classes.** Classes of the `app` module's production code whose parents, direct or
 * indirect, include `SettingsRepository` or one of the sections it is made of — the sections are
 * read from `SettingsRepository`'s own supertype list, so a new section is covered without editing
 * this test.
 */
class SettingsSingletonScopeTest {

    @Test
    fun `every settings implementation is a class-level singleton`() {
        val unscoped = settingsImplementations()
            .filterNot { it.hasAnnotationWithName("Singleton") }
            .map { it.name }

        assertEquals(
            "annotate the class @Singleton: section bindings scoped per binding would each build their own " +
                "instance, with its own in-memory caches",
            emptyList<String>(),
            unscoped,
        )
    }

    @Test
    fun `every settings section is part of the composite`() {
        // A section left out of the composite escapes the scope rule above: a class implementing
        // only that section has no parent this guard looks for.
        val sections = ArchitectureScope.production.interfaces()
            .filter { it.name.endsWith("Settings") || it.name == "SettingsReset" }
            .mapTo(sortedSetOf()) { it.name }

        assertTrue("no settings section found — the census is broken", sections.isNotEmpty())
        assertEquals(
            "add these sections to SettingsRepository's supertype list",
            sortedSetOf<String>(),
            sections - sectionNames(),
        )
    }

    @Test
    fun `the guard sees the settings implementations`() {
        // Keeps the scope rule from passing vacuously on a renamed composite, or on a section store
        // whose parent the census fails to resolve.
        val found = settingsImplementations().mapTo(sortedSetOf()) { it.name }
        val stores = ArchitectureScope.production.classes()
            .filter { it.name.endsWith("SettingsStore") }
            .mapTo(sortedSetOf()) { it.name }

        assertTrue("found implementations: $found", "SettingsManager" in found)
        assertEquals("section stores the census does not see", sortedSetOf<String>(), stores - found)
    }

    private fun sectionNames(): Set<String> = ArchitectureScope.production.interfaces()
        .single { it.name == COMPOSITE }
        .parents()
        .mapTo(mutableSetOf()) { it.name }

    private fun settingsImplementations() = ArchitectureScope.production.classes(includeNested = true)
        .filter { it.hasParentWithName(sectionNames() + COMPOSITE, indirectParents = true) }

    private companion object {
        const val COMPOSITE = "SettingsRepository"
    }
}
