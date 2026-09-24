package app.knotwork.android.data.network.huggingface

import app.knotwork.android.data.repositories.NetworkActivityTrackerImpl
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [HuggingFaceModelApi]'s part in the More tab's privacy indicator.
 *
 * Mapping and search behaviour are covered through
 * `ModelDiscoveryRepositoryImplTest`; this class pins only that browsing Discover counts
 * as the network use it is. The indicator reads "no network calls", and before this the
 * Hub requests were the one user-visible path it was never told about.
 */
class HuggingFaceModelApiTest {

    private lateinit var server: MockWebServer
    private val networkActivity = NetworkActivityTrackerImpl()
    private lateinit var api: HuggingFaceModelApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = HuggingFaceModelApi(
            baseUrl = server.url("/").toString().trimEnd('/'),
            client = OkHttpClient(),
            networkActivityTracker = networkActivity,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `given a catalogue request when listModels then the privacy indicator records it`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("[]").build())

        api.listModels(query = null, limit = 5)

        assertNotNull("the Hub request left without being recorded", networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given a detail request when getModel then the privacy indicator records it`() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("""{"id":"org/model"}""").build())

        api.getModel("org/model")

        assertNotNull("the Hub request left without being recorded", networkActivity.lastOutboundAt.value)
    }

    @Test
    fun `given no request was made when the tracker is read then nothing is recorded`() {
        // Building the client is not a network call; only a request is.
        assertNull(networkActivity.lastOutboundAt.value)
    }
}
