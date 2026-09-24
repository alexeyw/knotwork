package app.knotwork.android.data.network

import android.content.Context
import app.knotwork.android.domain.repositories.NetworkActivityTracker
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Covers the streaming downloader, with the weight on the paths that only
 * matter once a transfer can be interrupted: resuming a partial file, refusing
 * to resume when that would corrupt the result, and never letting an unfinished
 * transfer sit at the final file name where it would pass for an installed
 * model.
 */
class ResumableFileDownloaderTest {

    private val url = "http://example.com/model.bin"

    private lateinit var context: Context
    private lateinit var client: OkHttpClient
    private lateinit var tempDir: File
    private lateinit var downloader: ResumableFileDownloader
    private lateinit var networkActivity: NetworkActivityTracker

    /** Captures the request handed to OkHttp so header assertions can read it. */
    private val sentRequest = slot<Request>()

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        client = mockk(relaxed = true)
        tempDir = File(System.getProperty("java.io.tmpdir"), "resumable_download_test_${System.nanoTime()}")
        tempDir.mkdirs()
        every { context.getExternalFilesDir(null) } returns tempDir
        networkActivity = mockk(relaxed = true)
        downloader = ResumableFileDownloader(context, client, networkActivity)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `given a fresh download when it completes then the file lands at its final name`() = runTest {
        respondWith(response(code = 200, body = "mock_model_data"))
        val progress = mutableListOf<Int>()

        val outcome = downloader.download(url, "model.bin", authToken = null) { progress += it }

        val success = outcome as ResumableFileDownloader.Outcome.Success
        val file = File(tempDir, "model.bin")
        assertEquals(file.absolutePath, success.path)
        assertEquals("mock_model_data", file.readText())
        assertEquals(listOf(100), progress)
        // Nothing partial is left behind once the rename happened.
        assertTrue(tempDir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test
    fun `given a transfer spanning several reads when downloading then the privacy indicator hears of each`() =
        runTest {
            // A model is gigabytes and takes minutes. Recorded only when the request went
            // out, the More tab would read "no network calls in last 3 m" in the middle of
            // the download — so the tracker is told again as bytes arrive.
            respondWith(response(code = 200, body = "x".repeat(3 * 65_536)))

            downloader.download(url, "model.bin", authToken = null) {}

            verify(atLeast = 4) { networkActivity.recordOutbound() }
        }

    @Test
    fun `given an interrupted transfer when it resumes then the range is requested and bytes are appended`() = runTest {
        partFileFor("model.bin").writeText("abc")
        respondWith(response(code = 206, body = "def"))

        val outcome = downloader.download(url, "model.bin", authToken = null) {}

        assertEquals("bytes=3-", sentRequest.captured.header("Range"))
        val success = outcome as ResumableFileDownloader.Outcome.Success
        // The prefix already on disk plus the newly fetched suffix — the
        // whole point of resuming.
        assertEquals("abcdef", File(success.path).readText())
    }

    @Test
    fun `given a server that ignores the range when resuming then the stale prefix is discarded`() = runTest {
        partFileFor("model.bin").writeText("abc")
        // 200 (not 206) means the body is the whole file again; appending it
        // would produce a file with a duplicated prefix that still looks valid.
        respondWith(response(code = 200, body = "whole-file"))

        val outcome = downloader.download(url, "model.bin", authToken = null) {}

        assertEquals("whole-file", File((outcome as ResumableFileDownloader.Outcome.Success).path).readText())
    }

    @Test
    fun `given a rejected range when downloading then it restarts from zero`() = runTest {
        partFileFor("model.bin").writeText("stale-and-too-long")
        val rangeRejected = mockk<Call>()
        every { rangeRejected.execute() } returns response(code = 416, body = "")
        val fresh = mockk<Call>()
        every { fresh.execute() } returns response(code = 200, body = "fresh")
        every { client.newCall(any()) } returnsMany listOf(rangeRejected, fresh)

        val outcome = downloader.download(url, "model.bin", authToken = null) {}

        assertEquals("fresh", File((outcome as ResumableFileDownloader.Outcome.Success).path).readText())
    }

    @Test
    fun `given a partial file from another URL when downloading then it is discarded`() = runTest {
        val stale = File(tempDir, "model.bin.deadbeef.part")
        stale.writeText("bytes of a different file")
        respondWith(response(code = 200, body = "correct"))

        val outcome = downloader.download(url, "model.bin", authToken = null) {}

        // Splicing two different files together would yield a bundle that looks
        // complete and is unusable — so the mismatched part goes.
        assertFalse(stale.exists())
        assertEquals("correct", File((outcome as ResumableFileDownloader.Outcome.Success).path).readText())
    }

    @Test
    fun `given a transport failure when downloading then the partial bytes survive for the next attempt`() = runTest {
        partFileFor("model.bin").writeText("abc")
        val call = mockk<Call>()
        every { call.execute() } throws IOException("Network timeout")
        every { client.newCall(any()) } returns call

        val outcome = downloader.download(url, "model.bin", authToken = null) {}

        val failure = outcome as ResumableFileDownloader.Outcome.Failure
        assertTrue(failure.message.contains("Network timeout"))
        // No HTTP status — the caller reads this as "retrying may help".
        assertNull(failure.httpCode)
        assertEquals("abc", partFileFor("model.bin").readText())
    }

    @Test
    fun `given an HTTP error when downloading then the status is carried on the failure`() = runTest {
        respondWith(response(code = 404, body = ""))

        val outcome = downloader.download(url, "model.bin", authToken = null) {}

        val failure = outcome as ResumableFileDownloader.Outcome.Failure
        assertEquals(404, failure.httpCode)
        assertTrue(failure.message.contains("404"))
        assertFalse(File(tempDir, "model.bin").exists())
    }

    @Test
    fun `given a traversal-only file name when downloading then it is rejected`() = runTest {
        respondWith(response(code = 200, body = "data"))

        val outcome = downloader.download(url, "..", authToken = null) {}

        val failure = outcome as ResumableFileDownloader.Outcome.Failure
        assertTrue(failure.message.contains("Invalid model file name"))
    }

    @Test
    fun `given separators in the file name when downloading then they flatten inside the models dir`() = runTest {
        respondWith(response(code = 200, body = "payload"))

        val outcome = downloader.download(url, "../../evil.bin", authToken = null) {}

        val success = outcome as ResumableFileDownloader.Outcome.Success
        assertEquals(File(tempDir, ".._.._evil.bin").absolutePath, success.path)
        assertEquals("payload", File(success.path).readText())
    }

    @Test
    fun `given names differing only by subdirectory when downloading then they stay distinct on disk`() = runTest {
        val q4 = mockk<Call>()
        every { q4.execute() } returns response(code = 200, body = "four")
        val q8 = mockk<Call>()
        every { q8.execute() } returns response(code = 200, body = "eight")
        every { client.newCall(any()) } returnsMany listOf(q4, q8)

        val first = downloader.download(url, "q4/model.litertlm", authToken = null) {}
        val second = downloader.download(url, "q8/model.litertlm", authToken = null) {}

        val pathA = (first as ResumableFileDownloader.Outcome.Success).path
        val pathB = (second as ResumableFileDownloader.Outcome.Success).path
        assertNotEquals(pathA, pathB)
        assertEquals("four", File(pathA).readText())
        assertEquals("eight", File(pathB).readText())
    }

    @Test
    fun `given a Hugging Face download when a token is saved then the bearer token is sent`() = runTest {
        respondWith(response(code = 200, body = "gated"))

        downloader.download(HF_URL, "model.bin", authToken = "hf_secret") {}

        assertEquals("Bearer hf_secret", sentRequest.captured.header("Authorization"))
    }

    @Test
    fun `given a saved token when the URL only resembles Hugging Face then no token is sent`() = runTest {
        // The token belongs to one account on one host. Everything below either is a
        // different host that merely contains the name, or would put the token on the
        // wire in cleartext.
        val lookalikes = listOf(
            "https://huggingface.co.evil.example/model.bin",
            "https://evil-huggingface.co/model.bin",
            "https://evil.example/huggingface.co/model.bin",
            "https://huggingface.co@evil.example/model.bin",
            "http://huggingface.co/org/repo/resolve/main/model.bin",
            "https://hf.co/org/repo/resolve/main/model.bin",
            "https://us.aws.cdn.hf.co/xet-bridge-us/model.bin",
        )
        for (lookalike in lookalikes) {
            respondWith(response(code = 200, body = "data"))

            downloader.download(lookalike, "model.bin", authToken = "hf_secret") {}

            assertNull("token sent to $lookalike", sentRequest.captured.header("Authorization"))
        }
    }

    @Test
    fun `given a saved token when the model URL is not Hugging Face then the host receives no token`() = runTest {
        startedServer().use { mirror ->
            mirror.enqueue(MockResponse.Builder().code(200).body("mirrored").build())
            val wired = wiredDownloader(mapOf("mirror.example" to mirror))

            val outcome = wired.download("https://mirror.example/model.bin", "model.bin", authToken = "hf_secret") {}

            assertTrue(outcome is ResumableFileDownloader.Outcome.Success)
            assertNull(mirror.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `given a saved token when Hugging Face serves the file then the request carries it`() = runTest {
        startedServer().use { hub ->
            hub.enqueue(MockResponse.Builder().code(200).body("gated").build())
            val wired = wiredDownloader(mapOf(HF_HOST to hub))

            wired.download(HF_URL, "model.bin", authToken = "hf_secret") {}

            assertEquals("Bearer hf_secret", hub.takeRequest().headers["Authorization"])
        }
    }

    @Test
    fun `given Hugging Face redirects to its CDN when downloading then the CDN never sees the token`() = runTest {
        // Measured on the real Hub: a `resolve/main` URL answers 302 with a signed URL on
        // a different host (`us.aws.cdn.hf.co`). The signature authorises that hop; the
        // token must stop at the first one. OkHttp drops `Authorization` whenever a
        // redirect changes host, port or scheme — pinned here, since the Hub download
        // working at all depends on it and a future OkHttp could change it.
        startedServer().use { hub ->
            startedServer().use { cdn ->
                hub.enqueue(
                    MockResponse.Builder().code(302).addHeader("Location", cdn.url("/signed/model.bin")).build(),
                )
                cdn.enqueue(MockResponse.Builder().code(200).body("weights").build())
                val wired = wiredDownloader(mapOf(HF_HOST to hub))

                val outcome = wired.download(HF_URL, "model.bin", authToken = "hf_secret") {}

                assertEquals("weights", File((outcome as ResumableFileDownloader.Outcome.Success).path).readText())
                assertEquals("Bearer hf_secret", hub.takeRequest().headers["Authorization"])
                assertNull(cdn.takeRequest().headers["Authorization"])
            }
        }
    }

    /**
     * A downloader over a real [OkHttpClient] whose requests reach [routes] instead of
     * the internet. The interceptor stands in for DNS and TLS only: the downloader still
     * builds its request — and decides on the token — from the real `https://` URL, and
     * redirects are followed by OkHttp itself, after the interceptor, exactly as on a
     * device.
     */
    private fun wiredDownloader(routes: Map<String, MockWebServer>): ResumableFileDownloader {
        val routed = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val server = routes.getValue(request.url.host)
                val local = request.url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
                chain.proceed(request.newBuilder().url(local).build())
            }
            .build()
        return ResumableFileDownloader(context, routed, networkActivity)
    }

    /** Mirrors the downloader's own part-file naming so tests can seed one. */
    private fun partFileFor(fileName: String): File =
        File(tempDir, "$fileName.${Integer.toHexString(url.hashCode())}.part")

    private fun response(code: Int, body: String): Response = Response.Builder()
        .request(Request.Builder().url(url).build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("test")
        .body(body.toResponseBody())
        .build()

    private fun startedServer(): MockWebServer = MockWebServer().also { it.start() }

    /** Stubs a single response and captures the request that asked for it. */
    private fun respondWith(response: Response) {
        val call = mockk<Call>()
        every { call.execute() } returns response
        every { client.newCall(capture(sentRequest)) } returns call
    }

    private companion object {
        const val HF_HOST = "huggingface.co"
        const val HF_URL = "https://huggingface.co/org/repo/resolve/main/model.bin"
    }
}
