package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Only the callers that need a binding **after** its pipeline is gone read the raw
 * surface binding; everything that starts a run from a surface, or shows it as
 * ready, reads the existence-checked one.
 *
 * A binding is an id kept in settings and can outlive its pipeline ("Erase data"
 * keeps settings). The share target and the tile used to read the raw id and hand
 * it to the run queue, which falls back to the default pipeline when it cannot
 * find the bound one — a surface that reads "Not set" ran a graph nobody bound to
 * it. A new launch path wired to the raw resolver fails here, by name, before it
 * can repeat that.
 */
class SurfaceBindingReadersTest {

    @Test
    fun `given the production sources when the raw surface resolver is read then only the allowed callers read it`() {
        val readers = ProductionSources.code
            .filter { (path, code) -> RAW.containsMatchIn(code) && !path.endsWith("/ResolveSurfacePipelineUseCase.kt") }
            .keys.map { it.substringAfterLast('/') }.toSortedSet()

        assertEquals(
            "a new reader of the raw binding — if it starts a run or shows a surface as ready, " +
                "read ResolveLaunchableSurfacePipelineUseCase instead",
            RAW_READERS,
            readers,
        )
    }

    @Test
    fun `given the surface launch paths when they resolve a binding then they read the existence-checked one`() {
        val missing = LAUNCH_PATHS.filterNot { name ->
            ProductionSources.code.entries.any { (path, code) ->
                path.endsWith("/$name") && code.contains("ResolveLaunchableSurfacePipelineUseCase")
            }
        }

        assertEquals(emptyList<String>(), missing)
    }

    private companion object {
        /** The raw resolver's type name as a whole word, not as a prefix of the launchable one. */
        val RAW = Regex("""\bResolveSurfacePipelineUseCase\b""")

        /** Callers that need the id after its pipeline is gone, and the launchable resolver built on it. */
        val RAW_READERS = sortedSetOf(
            "FindPipelineBindingsUseCase.kt",
            "OrchestratorViewModel.kt",
            "ResolveLaunchableSurfacePipelineUseCase.kt",
        )

        /** Every path that starts a run from a surface or shows a surface as ready. */
        val LAUNCH_PATHS = listOf(
            "LaunchSharePipelineUseCase.kt",
            "LaunchTilePipelineUseCase.kt",
            "DutyPipelineTileService.kt",
        )
    }
}
