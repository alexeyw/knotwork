package app.knotwork.android.data.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Builds the app's shared OkHttp client — the one the `http_request` tool, model
 * downloads and Hugging Face discovery go through.
 *
 * Built here rather than inline in the DI module so the wiring itself is what a
 * test exercises: which hops the cleartext guard sees depends on how it is
 * registered, not on what it checks.
 */
object SharedHttpClient {

    /** Connect, read and write timeout of the shared client, in seconds. */
    const val TIMEOUT_SECONDS: Long = 60L

    /**
     * Builds the shared client.
     *
     * @param cleartextGuard The guard refusing unencrypted requests to public hosts;
     *   injectable so a test can name its own private host.
     * @return The client.
     */
    fun build(cleartextGuard: Interceptor = CleartextGuardInterceptor()): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // The manifest permits cleartext app-wide because Android cannot express
        // "any private-LAN address" in its network-security config; this restores
        // the public-host half of that protection in app code, twice over. As an
        // application interceptor it refuses the first request before a name is
        // even resolved; as a NETWORK interceptor it sees every hop OkHttp
        // follows itself — registered only as the former, it never saw a
        // redirect, and one could downgrade the call to http:// on a public host.
        .addInterceptor(cleartextGuard)
        .addNetworkInterceptor(cleartextGuard)
        .build()
}
