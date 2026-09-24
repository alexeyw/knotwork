package app.knotwork.android.architecture

/**
 * The import prefixes that mean "this file can speak to the network", shared by every
 * guard that asks the question: the allow-list [NetworkEgressInventoryKonsistTest] and
 * the three deny-lists ([JournalExportNoNetworkKonsistTest],
 * [PromptPackNoNetworkKonsistTest], [UsageTelemetryNoNetworkKonsistTest]).
 *
 * **One list because four copies drifted as one.** Each guard used to carry its own
 * copy of the same five prefixes, so a namespace missing from one was missing from all
 * four — and three were: an image loader, the Firebase SDK and the platform `WebView`.
 * A new HTTP stack goes here, once, and every guard sees it.
 *
 * Selection is by import, so the list is a census of imports and not of behaviour: a
 * file that reaches the network through a class it never imports (a reflective call, a
 * client handed in by DI) is not seen. The inventory's KDoc says what that leaves out.
 */
internal object NetworkClientImports {

    /**
     * Import-name prefixes of network-capable APIs.
     *
     *  - `okhttp3.`, `retrofit2.`, `io.ktor.`, `java.net.` — the HTTP stacks in use.
     *  - `ai.koog.` — the model clients, whose transport is Ktor.
     *  - `coil3.` — an image loader fetches a URL it is handed once a network fetcher
     *    is on the classpath (`coil-network-*`); none is today, and the inventory checks.
     *  - `com.google.firebase.` — the Crashlytics SDK in the `full` flavour uploads on
     *    its own schedule.
     *  - `android.webkit.` — a `WebView` is a complete HTTP client. `MimeTypeMap` lives
     *    in the same package and is inventoried as opening nothing.
     */
    val PREFIXES: List<String> = listOf(
        "okhttp3.",
        "retrofit2.",
        "ai.koog.",
        "io.ktor.",
        "java.net.",
        "coil3.",
        "com.google.firebase.",
        "android.webkit.",
    )
}
