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
    fun `the guard sees the settings implementation and the sections`() {
        // Keeps the guard from passing vacuously on a renamed composite or an empty census.
        assertTrue("found sections: ${sectionNames()}", sectionNames().size >= MIN_SECTIONS)
        assertTrue(
            "found implementations: ${settingsImplementations().map { it.name }}",
            settingsImplementations().any { it.name == "SettingsManager" },
        )
    }

    private fun sectionNames(): Set<String> = ArchitectureScope.production.interfaces()
        .single { it.name == COMPOSITE }
        .parents()
        .mapTo(mutableSetOf()) { it.name }

    private fun settingsImplementations() = ArchitectureScope.production.classes(includeNested = true)
        .filter { it.hasParentWithName(sectionNames() + COMPOSITE, indirectParents = true) }

    private companion object {
        const val COMPOSITE = "SettingsRepository"

        /** The sections the composite was split into; it may gain sections, not lose them silently. */
        const val MIN_SECTIONS = 9
    }
}
