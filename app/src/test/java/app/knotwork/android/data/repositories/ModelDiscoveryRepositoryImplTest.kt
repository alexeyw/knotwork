package app.knotwork.android.data.repositories

import app.knotwork.android.data.network.huggingface.HuggingFaceModelApi
import app.knotwork.android.domain.repositories.LocalModelRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ModelDiscoveryRepositoryImpl] backed by a [MockWebServer]
 * instance standing in for the Hugging Face Hub. Covers list, search,
 * detail/resolve, the compatibility filter, the empty case and the network
 * error mapping to [Result.failure].
 */
class ModelDiscoveryRepositoryImplTest {

    private lateinit var server: MockWebServer
    private val localModelRepository = mockk<LocalModelRepository>()
    private lateinit var repository: ModelDiscoveryRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = HuggingFaceModelApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            networkActivityTracker = NetworkActivityTrackerImpl(),
        )
        repository = ModelDiscoveryRepositoryImpl(api = api, localModelRepository = localModelRepository)
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `given a list response when searchModels then maps compatible repositories`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(LIST_JSON).build())

        val result = repository.searchModels(query = null, limit = 30)

        assertTrue(result.isSuccess)
        val models = result.getOrThrow()
        // The repo with no .litertlm files is filtered out.
        assertEquals(1, models.size)
        val model = models.single()
        assertEquals("litert-community/gemma-4-E2B-it-litert-lm", model.repoId)
        assertEquals(2, model.litertFileCount)
        assertEquals("apache-2.0", model.license)
    }

    @Test
    fun `given a query when searchModels then forwards author and search params`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        repository.searchModels(query = "gemma", limit = 10)

        val request = server.takeRequest()
        val path = request.url.toString()
        assertTrue(path.contains("author=litert-community"))
        assertTrue(path.contains("search=gemma"))
        assertTrue(path.contains("limit=10"))
    }

    @Test
    fun `given empty list when searchModels then returns empty success`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        val result = repository.searchModels(query = null, limit = 30)

        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }

    @Test
    fun `given server error when searchModels then returns failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())

        val result = repository.searchModels(query = null, limit = 30)

        assertTrue(result.isFailure)
    }

    @Test
    fun `given a detail response when getModelDetail then maps files with installed flags`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(DETAIL_JSON).build())
        coEvery { localModelRepository.isInstalled("gemma-4-E2B-it.litertlm") } returns true
        coEvery { localModelRepository.isInstalled("gemma-4-E2B-it-gpu.litertlm") } returns false

        val result = repository.getModelDetail("litert-community/gemma-4-E2B-it-litert-lm")

        assertTrue(result.isSuccess)
        val detail = result.getOrThrow()
        assertEquals(2, detail.files.size)
        val cpu = detail.files.first { it.fileName == "gemma-4-E2B-it.litertlm" }
        assertTrue(cpu.isInstalled)
        assertEquals(2_588_147_712L, cpu.sizeBytes)
        assertTrue(cpu.resolveUrl.endsWith("/resolve/main/gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun `given detail request when getModelDetail then asks for blobs`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(DETAIL_JSON).build())
        coEvery { localModelRepository.isInstalled(any()) } returns false

        repository.getModelDetail("litert-community/gemma-4-E2B-it-litert-lm")

        val request = server.takeRequest()
        assertTrue(request.url.toString().contains("blobs=true"))
        assertTrue(request.url.toString().contains("api/models/litert-community/gemma-4-E2B-it-litert-lm"))
    }

    @Test
    fun `given server error when getModelDetail then returns failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("nope").build())

        val result = repository.getModelDetail("litert-community/missing")

        assertTrue(result.isFailure)
    }

    private companion object {
        val LIST_JSON = """
            [
              {
                "id": "litert-community/gemma-4-E2B-it-litert-lm",
                "author": "litert-community",
                "downloads": 1200,
                "likes": 34,
                "gated": false,
                "tags": ["litert-lm", "license:apache-2.0"],
                "lastModified": "2026-05-01T12:00:00.000Z",
                "siblings": [
                  {"rfilename": ".gitattributes"},
                  {"rfilename": "gemma-4-E2B-it.litertlm"},
                  {"rfilename": "gemma-4-E2B-it-gpu.litertlm"}
                ]
              },
              {
                "id": "litert-community/tokenizer-only",
                "author": "litert-community",
                "downloads": 5,
                "likes": 0,
                "gated": false,
                "tags": [],
                "siblings": [{"rfilename": "tokenizer.json"}]
              }
            ]
        """.trimIndent()

        val DETAIL_JSON = """
            {
              "id": "litert-community/gemma-4-E2B-it-litert-lm",
              "author": "litert-community",
              "downloads": 1200,
              "likes": 34,
              "gated": "auto",
              "tags": ["litert-lm", "license:apache-2.0"],
              "lastModified": "2026-05-01T12:00:00.000Z",
              "cardData": {"license": "apache-2.0"},
              "siblings": [
                {"rfilename": ".gitattributes", "size": 1500},
                {"rfilename": "gemma-4-E2B-it.litertlm", "size": 2588147712},
                {"rfilename": "gemma-4-E2B-it-gpu.litertlm", "size": 3000000000}
              ]
            }
        """.trimIndent()
    }
}
