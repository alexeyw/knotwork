package app.knotwork.android.data.network.huggingface

import app.knotwork.android.domain.constants.ModelDiscoveryConstants
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

/**
 * Read-only client for the Hugging Face Hub model endpoints, built on the
 * shared [OkHttpClient] (the project has no Retrofit; the model download
 * manager uses raw OkHttp too) and [kotlinx.serialization].
 *
 * No request is issued except in direct response to a user action, and no
 * authentication token is sent: the curated `litert-community` listing and the
 * model metadata are public even for access-gated repos (only the file
 * *download* — handled by the download manager — needs a token). Blocking I/O
 * runs on [Dispatchers.IO]; non-2xx responses surface as a typed
 * [HuggingFaceApiException], transport errors as the underlying `IOException`.
 *
 * @property baseUrl Hub host (no trailing slash); injected so tests can point
 *   the client at a local mock server.
 * @property client shared OkHttp client.
 * @property networkActivityTracker Told about each Hub request, so the More tab's privacy
 *   indicator counts Discover browsing as the network use it is.
 */
class HuggingFaceModelApi @Inject constructor(
    @HuggingFaceBaseUrl private val baseUrl: String,
    private val client: OkHttpClient,
    private val networkActivityTracker: NetworkActivityTracker,
) {

    /** Lenient decoder; the endpoints carry many fields the DTO ignores. */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Lists (or searches) repositories in the curated organisation, newest
     * downloads first.
     *
     * @param query optional Hub free-text filter; omitted when `null`/blank.
     * @param limit maximum repositories to request.
     * @return the decoded list (engine compatibility is filtered downstream).
     */
    suspend fun listModels(query: String?, limit: Int): List<HfModelDto> = withContext(Dispatchers.IO) {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/models")
            .addQueryParameter("author", ModelDiscoveryConstants.HF_LITERT_AUTHOR)
            .addQueryParameter("full", "true")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1")
        if (!query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("search", query)
        }
        val body = executeForBody(urlBuilder.build())
        json.decodeFromString(ListSerializer(HfModelDto.serializer()), body)
    }

    /**
     * Fetches a single repository with file blob metadata (`blobs=true`) so
     * the response carries per-file sizes and the model card.
     *
     * @param repoId fully-qualified repository id (`"author/name"`).
     * @return the decoded repository detail.
     */
    suspend fun getModel(repoId: String): HfModelDto = withContext(Dispatchers.IO) {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/models/$repoId")
            .addQueryParameter("blobs", "true")
            .build()
        val body = executeForBody(url)
        json.decodeFromString(HfModelDto.serializer(), body)
    }

    /**
     * Executes a GET against [url] and returns the response body as text,
     * mapping a non-2xx status to a [HuggingFaceApiException]. The response is
     * always closed.
     */
    private fun executeForBody(url: HttpUrl): String {
        val request = Request.Builder().url(url).get().build()
        networkActivityTracker.recordOutbound()
        client.newCall(request).execute().use { response ->
            val bodyText = response.body.string()
            if (!response.isSuccessful) {
                throw HuggingFaceApiException(response.code, "Hugging Face API returned ${response.code}")
            }
            return bodyText
        }
    }
}
