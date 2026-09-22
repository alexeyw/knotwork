package app.knotwork.android.data.tools.local

import app.knotwork.android.data.engine.ModelNetworkGate
import app.knotwork.android.domain.engine.LlmInferenceEngine
import app.knotwork.android.domain.models.AgentTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A local tool that performs a simple web search using the Wikipedia API.
 * This is used for queries that require fetching external data.
 *
 * **Why the network check lives here and not in the caller.** Two independent callers
 * reach [executeSearch]: [app.knotwork.android.data.tools.local.executors.SearchToolExecutor]
 * for the agent's own runs, and
 * [app.knotwork.android.data.tools.local.appfunctions.SearchAppFunction] for a caller on
 * the device going through the AppFunctions runtime. The second one bypasses
 * `ToolRepositoryImpl` entirely, so a check placed in the repository would hold for one
 * path and not the other. Placing it on the line that opens the connection is the only
 * arrangement where every caller is covered by construction. The `lang` check
 * ([searchUrl]) lives here for the same reason.
 *
 * @property llmEngine On-device engine used to summarise an over-long article extract.
 * @property networkGate Decides whether this tool may reach the network right now — the
 *   "Block network from local model" restriction covers it (see [ModelNetworkGate]).
 */
@Singleton
class SearchTool @Inject constructor(
    private val llmEngine: LlmInferenceEngine,
    private val networkGate: ModelNetworkGate,
) {

    companion object {
        const val TOOL_NAME = "search_tool"
        const val TOOL_DESCRIPTION =
            "Searches Wikipedia for up-to-date information about a topic. " +
                "Use this when you need facts or data to answer a user's question."
        const val TOOL_PARAMETERS = """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "The topic to search for" },
                "lang": { "type": "string", "description": "Required. The 2-letter language code. This code MUST match the language of the keywords you provided in the query field. Use standard codes, e.g., \"en\" (English), \"es\" (Spanish), \"zh\" (Chinese), \"fr\" (French), \"de\" (German), \"ja\" (Japanese), \"ko\" (Korean), \"it\" (Italian), \"pt\" (Portuguese), \"ru\" (Russian), \"ar\" (Arabic), \"hi\" (Hindi)." }
              },
              "required": ["query", "lang"]
            }
        """

        /**
         * Maximum number of characters of the Wikipedia extract kept verbatim. Longer
         * extracts are either summarised via the local LLM or cleanly truncated.
         */
        private const val EXTRACT_CHAR_LIMIT: Int = 2_000

        /** TCP connect timeout, in milliseconds, for the Wikipedia API request. */
        private const val HTTP_CONNECT_TIMEOUT_MS: Int = 5_000

        /** TCP read timeout, in milliseconds, for the Wikipedia API request. */
        private const val HTTP_READ_TIMEOUT_MS: Int = 5_000

        /**
         * What `lang` must be: one DNS label — lower-case letters, digits and hyphens,
         * at most 63 of them. That is the whole property the URL needs, since the
         * value then cannot end the host (`/`, `?`, `#`), add a userinfo (`@`) or a
         * port (`:`), or add a label (`.`): the request can only go to a subdomain of
         * `wikipedia.org`. A stricter "language code" shape would be wrong — Simple
         * English is `simple`, and `zh-min-nan` / `be-tarask` are real editions.
         */
        private val WIKIPEDIA_SUBDOMAIN = Regex("^[a-z0-9-]{1,63}$")

        /** The tool result for a `lang` that is not a Wikipedia subdomain. */
        const val INVALID_LANG_ERROR =
            "Error: 'lang' must be a Wikipedia language code such as \"en\", \"de\" or \"simple\"."
    }

    /**
     * Returns the [AgentTool] representation of this tool.
     */
    fun asAgentTool(): AgentTool = AgentTool(
        name = TOOL_NAME,
        description = TOOL_DESCRIPTION,
        parameters = TOOL_PARAMETERS.trimIndent(),
    )

    private fun truncateCleanly(extract: String): String {
        val substring = extract.substring(0, EXTRACT_CHAR_LIMIT)
        val lastPunctuation = maxOf(
            substring.lastIndexOf('.'),
            maxOf(substring.lastIndexOf('!'), substring.lastIndexOf('?')),
        )

        if (lastPunctuation > 0) {
            return substring.substring(0, lastPunctuation + 1)
        }

        val lastSpace = substring.lastIndexOf(' ')
        if (lastSpace > 0) {
            return substring.substring(0, lastSpace) + "..."
        }

        return "$substring..."
    }

    /**
     * Builds the Wikipedia API request for [query] in the [lang] edition.
     *
     * `lang` sits in the authority of the URL and arrives from a caller the app does
     * not control — the model's own tool call, or another app through AppFunctions —
     * so it is checked against [WIKIPEDIA_SUBDOMAIN] rather than interpolated as
     * given. Case and surrounding whitespace are forgiven (`" EN "` is `en`).
     *
     * @param query The search term; percent-encoded into the query string.
     * @param lang The Wikipedia edition, e.g. `en`, `de`, `simple`, `zh-min-nan`.
     * @return The request URL, whose host is always `<lang>.wikipedia.org`, or `null`
     *   when [lang] is not a single DNS label.
     */
    internal fun searchUrl(query: String, lang: String): URL? {
        val edition = lang.trim().lowercase(Locale.ROOT)
        if (!WIKIPEDIA_SUBDOMAIN.matches(edition)) return null
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        // Using generator=search is much more flexible than titles= because it does a real search.
        return URL(
            "https://$edition.wikipedia.org/w/api.php?action=query&format=json" +
                "&prop=extracts&exintro=true&explaintext=true&generator=search" +
                "&gsrsearch=$encodedQuery&gsrlimit=1",
        )
    }

    /**
     * Executes the search query against Wikipedia.
     *
     * @param query The search query.
     * @param lang The Wikipedia edition to search, e.g. `en` (see [searchUrl]).
     * @return A summary of the search results; the refusal text when the
     *   "Block network from local model" restriction is on; or
     *   [INVALID_LANG_ERROR], before any connection, when [lang] is not a
     *   Wikipedia subdomain.
     */
    suspend fun executeSearch(query: String, lang: String): String = withContext(Dispatchers.IO) {
        networkGate.networkToolRefusal(TOOL_NAME)?.let { return@withContext it }
        // Refused rather than replaced with `en`: a silent fallback would search the
        // wrong edition and hand the model a confident answer to a different question.
        val url = searchUrl(query, lang) ?: return@withContext INVALID_LANG_ERROR
        try {
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = HTTP_CONNECT_TIMEOUT_MS
            connection.readTimeout = HTTP_READ_TIMEOUT_MS

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                val queryObj = jsonResponse.optJSONObject("query")
                if (queryObj == null) {
                    return@withContext "No results found for '$query'."
                }

                val pagesObj = queryObj.optJSONObject("pages")
                if (pagesObj == null || pagesObj.keys().hasNext().not()) {
                    return@withContext "No results found for '$query'."
                }

                val firstKey = pagesObj.keys().next()
                if (firstKey == "-1") {
                    return@withContext "No results found for '$query'."
                }

                val page = pagesObj.getJSONObject(firstKey)
                val extract = page.optString("extract", "No summary available.")

                // If the extract is too long, try to summarize it with LLM
                if (extract.length > EXTRACT_CHAR_LIMIT) {
                    if (llmEngine.isInitialized) {
                        try {
                            val prompt = "Summarize the following text within $EXTRACT_CHAR_LIMIT " +
                                "characters retaining the main factual information:\n\n" +
                                "$extract\n\nSUMMARY: "
                            val responseStream = llmEngine.generateResponseStream(prompt)
                            val summary = java.lang.StringBuilder()
                            responseStream.collect { token ->
                                summary.append(token)
                            }
                            return@withContext summary.toString()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            return@withContext truncateCleanly(extract)
                        }
                    } else {
                        return@withContext truncateCleanly(extract)
                    }
                }
                return@withContext extract
            } else {
                return@withContext "Failed to fetch data: HTTP ${connection.responseCode}"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withContext "Error executing search: ${e.message}"
        }
    }
}
