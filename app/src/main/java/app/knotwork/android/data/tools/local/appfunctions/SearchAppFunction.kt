package app.knotwork.android.data.tools.local.appfunctions

import app.knotwork.android.data.tools.local.SearchTool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The body of the `search` AppFunction Knotwork publishes to other apps: the argument
 * checks and the call into the built-in `search_tool`.
 *
 * The function itself is declared on [AgentAppFunctionService], the one class the
 * AppFunctions compiler reads; that method only delegates here. The logic lives in a plain
 * injectable class so it is testable without a service, and so the callee-side instance
 * shares [SearchTool]'s caches and rate limits with the in-agent caller path.
 *
 * **Risk classification:** read-only (no user data is touched, no side effects on device
 * state). External callers therefore do not need a Human-in-the-Loop confirmation —
 * matching the in-agent risk for `search_tool` set in `ToolRepositoryImpl`.
 *
 * **What does still apply to an external caller.** This path reaches [SearchTool]
 * directly and never passes through `ToolRepositoryImpl`, so the checks that repository
 * performs — the Tools screen's per-tool switch among them — do not run on it. What does
 * run is the check [SearchTool] makes itself: while *Block network from local model* is
 * on, the search is refused for every caller. Anything a future caller must not be able to
 * bypass belongs in [SearchTool], not in the repository.
 */
@Singleton
class SearchAppFunction @Inject constructor(private val searchTool: SearchTool) {

    /**
     * Runs a Wikipedia search on behalf of an external caller.
     *
     * @param query Non-blank search term. Blank input raises [IllegalArgumentException],
     *   which the AppFunctions framework reports to the caller as an invalid-argument
     *   error.
     * @param lang Wikipedia language code matching the language of [query] (`en`, `de`,
     *   `simple`, …). The AppFunctions compiler does not honour Kotlin defaults, so the
     *   parameter is required at the wire level; an empty or blank value falls back to
     *   `"en"` so a caller can still omit a meaningful language hint. Any other value that
     *   cannot name a Wikipedia edition raises [IllegalArgumentException], like a blank
     *   [query] — [SearchTool] would refuse it anyway, and a typed error tells the caller
     *   so instead of a result string.
     * @return The article extract (truncated or LLM-summarized) returned by
     *   [SearchTool.executeSearch].
     */
    suspend fun search(query: String, lang: String): String {
        require(query.isNotBlank()) { "search_tool requires a non-blank 'query' argument" }
        val effectiveLang = lang.ifBlank { DEFAULT_LANG }
        require(SearchTool.wikipediaEdition(effectiveLang) != null) {
            "search_tool requires 'lang' to be a Wikipedia language code such as \"en\""
        }
        return searchTool.executeSearch(query, effectiveLang)
    }

    private companion object {
        const val DEFAULT_LANG = "en"
    }
}
