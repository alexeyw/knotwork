package app.knotwork.android.architecture

import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.ServiceLoader

/**
 * Allow-list guard over every file that can open a network connection.
 *
 * **Why an allow-list, when three deny-lists already exist.**
 * [JournalExportNoNetworkKonsistTest], [PromptPackNoNetworkKonsistTest] and
 * [UsageTelemetryNoNetworkKonsistTest] each answer "this surface must never reach the
 * network". None of them answers the question the public documents make a promise about:
 * *which* surfaces do. A deny-list is silent about a path nobody thought to name, and the
 * project has now shipped three rounds of the same defect — a privacy text listing the
 * ways data can leave the device while a way was missing from the list. The last round
 * was the built-in `search_tool`, added on 2026-04-06 and therefore reaching
 * `wikipedia.org` since before the first tagged release (`v0.1.0`, 2026-05-17), while four
 * documents said the paths were five and every one of them user-configured.
 *
 * **What it actually checks, and what it cannot.** Every production file that imports a
 * network client has to appear in [INVENTORY] with a verdict: either it opens a
 * connection, and then it names the section of `PRIVACY.md` that describes what goes out
 * along it, or it does not, and then it says why not. A named section must exist. That
 * makes the gate a pure function of the repository (blocking, per the project's rule for
 * such gates) and it makes adding a network path a decision somebody has to write down.
 *
 * It does **not** check that the named section describes the path truthfully — no
 * assertion can, since the truth of prose is a claim about code somewhere else. What it
 * removes is the failure mode that actually happened: a path reaching the network with
 * *no* entry anywhere, which nobody had to notice.
 *
 * **Scope: everything that ships, not `app/src/main`.** The census runs over every
 * production source set of the app and of `:catalog`
 * ([ArchitectureScope.allProductionSourceSets]). Pinned to `app/src/main`, as it first was,
 * it could not see the one egress living in a flavour — the Crashlytics upload in
 * `app/src/full`, which `PRIVACY.md` describes and this inventory had no entry for — nor
 * the image loader in `:catalog`. The prefixes are [NetworkClientImports], shared with the
 * deny-lists.
 *
 * **The privacy indicator rides on the same list.** The More tab says "no network calls"
 * until [app.knotwork.android.domain.repositories.NetworkActivityTracker] hears of one, and
 * it once heard of three of the ten paths it counts now. So every egress entry also states what the
 * indicator sees of it — the file records the call itself, a named caller records it, or
 * it is not shown and the entry says why — and the claim is checked against the code. A
 * new path now fails here until someone decides what the pill says about it.
 *
 * **Why not resolve the anchor the way `verifyDocumentationLinks` does.** That gate
 * resolves GitHub heading slugs through `MarkdownLinks.slug`, which lives in `buildSrc`
 * and is not on this module's test classpath. Copying the slug algorithm here would
 * create the second reader its own documentation warns against. Nothing links to these
 * sections by anchor — the inventory is internal — so matching the numbered heading
 * directly is both sufficient and unambiguous.
 *
 * **Reading `PRIVACY.md` from a test.** The file is outside every source set, so Gradle
 * would answer this test from a cached run after an edit to it. `app/build.gradle.kts`
 * already declares it as a `Test` input (for `AboutLinksTest`); this test rides on that
 * declaration rather than adding a second one.
 */
class NetworkEgressInventoryKonsistTest {

    /**
     * What a file listed in [INVENTORY] does with the network client it imports.
     */
    private sealed interface Egress {

        /**
         * The file opens connections. Data can leave the device here.
         *
         * @property privacySection Number of the `PRIVACY.md` subsection that describes
         *   what goes out along this path, e.g. `"3.4"`.
         * @property indicator What the More tab's privacy indicator learns of this path.
         */
        data class Opens(val privacySection: String, val indicator: Indicator) : Egress

        /**
         * The file imports a network namespace without opening anything — a URL builder,
         * a model descriptor, an interceptor on somebody else's client.
         *
         * @property reason Why the import is not an egress, in words a reviewer can check
         *   against the file.
         */
        data class None(val reason: String) : Egress
    }

    /**
     * What the privacy indicator (`NetworkActivityTracker`) learns of an egress path.
     */
    private sealed interface Indicator {

        /** The file calls `recordOutbound()` itself, on the line that sends. */
        data object RecordsItself : Indicator

        /**
         * The file builds or decorates a client, and the files that send with it record.
         *
         * @property callers Inventory keys of those files; each must record itself.
         * @property reachedThrough Type names a caller uses to get at the client — the class
         *   itself and any interface it is bound to. Every production file naming one of
         *   them in code must be in [callers], be DI wiring, or itself be an entry of this
         *   kind (a factory wrapping a factory), so the list cannot silently be incomplete.
         */
        data class RecordedBy(val callers: List<String>, val reachedThrough: List<String>) : Indicator

        /**
         * The indicator does not see this path.
         *
         * @property reason Why not — a reviewer checks it against the file.
         */
        data class NotShown(val reason: String) : Indicator
    }

    @Test
    fun `every production file importing a network client is in the inventory`() {
        val unlisted = filesImportingANetworkClient() - INVENTORY.keys

        assertTrue(
            "these files import a network client but are not in the network-egress inventory: " +
                "${unlisted.sorted()}. Add each one to NetworkEgressInventoryKonsistTest.INVENTORY — " +
                "with the PRIVACY.md section describing what leaves the device along it, or with the " +
                "reason it opens no connection. A path the privacy policy does not describe is the " +
                "defect this gate exists to refuse.",
            unlisted.isEmpty(),
        )
    }

    @Test
    fun `every inventory entry still names a file that imports a network client`() {
        // Without this half the inventory rots: a file that stops touching the network,
        // or is deleted, leaves an entry asserting a path that no longer exists — and the
        // privacy section it points at outlives the code it was written for.
        val stale = INVENTORY.keys - filesImportingANetworkClient()

        assertTrue(
            "these inventory entries name no production file that imports a network client: " +
                "${stale.sorted()}. Remove each one, and check whether the PRIVACY.md section it " +
                "pointed at still describes something the app does.",
            stale.isEmpty(),
        )
    }

    @Test
    fun `every inventory entry that opens a connection names a section of the privacy policy`() {
        val headings = privacySubsectionNumbers()
        val dangling = INVENTORY
            .filterValues { it is Egress.Opens }
            .mapValues { (_, egress) -> (egress as Egress.Opens).privacySection }
            .filterValues { section -> section !in headings }

        assertTrue(
            "these inventory entries point at a PRIVACY.md section that does not exist: $dangling. " +
                "Sections found: ${headings.sorted()}. Either the section was renumbered — update the " +
                "entry — or the path it described was never written down.",
            dangling.isEmpty(),
        )
    }

    @Test
    fun `the search tool is inventoried as an egress and the privacy policy names its destination`() {
        // The regression that produced this suite, pinned by name: `SearchTool` is the one
        // built-in that reaches the network with neither an allowlist nor a confirmation,
        // and no public text named it until 0.10.1. The second assertion is deliberately
        // weak — it proves the host appears in the document, not that the paragraph around
        // it is true — and it is here because the document shipped without the word at all.
        val searchTool = INVENTORY[SEARCH_TOOL_PATH]

        assertTrue(
            "SearchTool must be inventoried as an egress; it opens a connection to wikipedia.org",
            searchTool is Egress.Opens,
        )
        assertTrue(
            "PRIVACY.md must name the host the built-in search tool reaches",
            privacyPolicyText().contains("wikipedia.org"),
        )
    }

    @Test
    fun `every inventory entry that opens nothing gives a reason`() {
        // The reason is the whole value of a `None` entry: it is what a reviewer checks the
        // file against. An empty one turns the entry into a silent exemption, which is the
        // shape of thing this gate exists to refuse.
        val unexplained = INVENTORY
            .filterValues { it is Egress.None && it.reason.isBlank() }
            .keys

        assertTrue(
            "these inventory entries claim to open no connection without saying why: " +
                "${unexplained.sorted()}. Write the reason, or reclassify the entry as an egress.",
            unexplained.isEmpty(),
        )
    }

    @Test
    fun `every egress the privacy indicator is said to see reaches the tracker`() {
        // The More tab reads "no network calls" until the tracker hears of one. It used to
        // hear of three of the ten paths it counts now — `search_tool`, on by default, among
        // those it never heard of — while its interface KDoc listed sources from memory.
        val claims = INVENTORY.mapNotNull { (path, egress) ->
            ((egress as? Egress.Opens)?.indicator)?.let { path to it }
        }
        val broken = claims.flatMap { (path, indicator) ->
            when (indicator) {
                Indicator.RecordsItself ->
                    listOfNotNull(path.takeUnless { records(it) }?.let { "$it records nothing" })
                is Indicator.RecordedBy -> indicator.callers.mapNotNull { caller ->
                    val verdict = (INVENTORY[caller] as? Egress.Opens)?.indicator
                    when {
                        verdict != Indicator.RecordsItself -> "$path names $caller, which is not an egress that records"
                        !records(caller) -> "$path names $caller, which records nothing"
                        else -> null
                    }
                }
                is Indicator.NotShown -> emptyList()
            }
        }

        assertTrue(
            "these inventory entries claim the privacy indicator sees them, and the code disagrees: $broken. " +
                "Call NetworkActivityTracker.recordOutbound() on the line that sends, or mark the entry " +
                "NotShown with the reason the indicator cannot see it.",
            broken.isEmpty(),
        )
    }

    @Test
    fun `every file that sends through a client factory is one of the callers that record`() {
        // Without this half, RecordedBy is a list somebody wrote once: a fourth caller of a
        // factory that sends without recording would leave the three listed ones green.
        val incomplete = INVENTORY.flatMap { (path, egress) ->
            val indicator = (egress as? Egress.Opens)?.indicator as? Indicator.RecordedBy ?: return@flatMap emptyList()
            val names = indicator.reachedThrough.map { name -> Regex("""\b${Regex.escape(name)}\b""") }
            shippingFiles.keys
                .filter { other -> other != path && isOutsideWiring(other, indicator.reachedThrough) }
                .filter { other -> names.any { it.containsMatchIn(codeOf(other)) } }
                .filterNot { it in indicator.callers }
                .map { user -> "$user uses ${indicator.reachedThrough} ($path) but is not a listed caller" }
        }

        assertTrue(
            "these files reach a client whose traffic the inventory says its callers record, and are not " +
                "among those callers: $incomplete. Make the file record the call and list it, or reclassify.",
            incomplete.isEmpty(),
        )
    }

    @Test
    fun `every egress the privacy indicator does not see says why`() {
        val unexplained = INVENTORY
            .filterValues { egress ->
                val indicator = (egress as? Egress.Opens)?.indicator
                indicator is Indicator.NotShown && indicator.reason.isBlank()
            }
            .keys

        assertTrue(
            "these egress entries are hidden from the privacy indicator without a reason: ${unexplained.sorted()}",
            unexplained.isEmpty(),
        )
    }

    @Test
    fun `the network prefixes cover the image loader, the Firebase SDK and WebView`() {
        // The three namespaces all four guards were blind to, pinned so a trimmed list
        // reddens here rather than silently widening every guard's blind spot again.
        val required = listOf("coil3.", "com.google.firebase.", "android.webkit.")

        assertTrue(
            "missing from NetworkClientImports.PREFIXES: ${required - NetworkClientImports.PREFIXES.toSet()}",
            NetworkClientImports.PREFIXES.containsAll(required),
        )
    }

    @Test
    fun `the Crashlytics upload in the full flavour is inventoried as an egress`() {
        // The one egress living outside `app/src/main`. PRIVACY 3.5 describes it; the
        // inventory, pinned to `main`, had no entry for it at all.
        assertTrue(
            "$CRASHLYTICS_PATH must be inventoried as an egress described by PRIVACY 3.5",
            (INVENTORY[CRASHLYTICS_PATH] as? Egress.Opens)?.privacySection == "3.5",
        )
    }

    @Test
    fun `the image loader has no network fetcher on the classpath`() {
        // What the `None` verdicts for the `:catalog` Coil files rest on. Coil 3 ships
        // without network support; `coil-network-okhttp` / `-ktor*` add it by registering
        // a fetcher through `ServiceLoader`, and from then on any `https://` model handed
        // to `AsyncImage` is fetched. So the probe is the registry itself: once one is
        // registered, the verdicts below are false and this test says so. The class is
        // resolved by name so a Coil upgrade that moves it fails loudly instead of
        // passing an empty lookup.
        val target = Class.forName("coil3.util.FetcherServiceLoaderTarget")
        val registered = ServiceLoader.load(target).toList()

        assertTrue(
            "Coil has network fetchers registered ($registered): the catalog's image components can now " +
                "fetch a URL. Reclassify their inventory entries, and name the path in PRIVACY.md.",
            registered.isEmpty(),
        )
    }

    /**
     * Repository-relative paths of every shipping production file whose imports include a
     * network client.
     *
     * Konsist parses the imports rather than the file text: an `import` line is easy to
     * match with a regular expression and just as easy to miss one of, and a missed file
     * is a missed path — the exact false green this gate exists to prevent.
     */
    private fun filesImportingANetworkClient(): Set<String> = shippingFiles
        .filterValues { file ->
            file.imports.any { import -> NetworkClientImports.PREFIXES.any(import.name::startsWith) }
        }
        .keys

    /**
     * Whether the file at [path] calls `recordOutbound()` in code — comments removed, so
     * a KDoc naming the method does not count.
     */
    private fun records(path: String): Boolean = RECORD_CALL in codeOf(path)

    /** Code of the shipping file at [path] with comments removed; empty for an unknown path. */
    private fun codeOf(path: String): String =
        shippingFiles[path]?.let { file -> ProductionSources.stripComments(file.text) }.orEmpty()

    /**
     * Whether [path] can use a client without sending with it: not DI wiring, not the file
     * declaring one of [names], and not itself a factory whose own callers record.
     */
    private fun isOutsideWiring(path: String, names: List<String>): Boolean {
        val stem = path.substringAfterLast('/').removeSuffix(".kt")
        val wrapsAFactory = (INVENTORY[path] as? Egress.Opens)?.indicator is Indicator.RecordedBy
        return "/di/" !in path && stem !in names && !wrapsAFactory
    }

    /** Every shipping production file, keyed by its repository-relative path. */
    private val shippingFiles: Map<String, KoFileDeclaration> by lazy {
        ArchitectureScope.allProductionSourceSets.files.associateBy { it.projectPath.removePrefix("/") }
    }

    /** Full text of the privacy policy, read from the repository root. */
    private fun privacyPolicyText(): String = File(repositoryRoot(), "PRIVACY.md").readText()

    /**
     * Section numbers of every `### <n>.<m> …` heading in the privacy policy, e.g. `3.4`.
     */
    private fun privacySubsectionNumbers(): Set<String> = SUBSECTION_HEADING.findAll(privacyPolicyText())
        .mapTo(mutableSetOf()) { match -> match.groupValues[1] }

    /**
     * Gradle runs unit tests with the module directory as the working directory, so the
     * repository root is its parent. Resolved rather than assumed so a failure names a
     * missing file instead of silently reading nothing.
     */
    private fun repositoryRoot(): File {
        val working = File("").absoluteFile
        return if (File(working, "PRIVACY.md").isFile) working else working.parentFile
    }

    private companion object {

        /** Where the app's shared code lives, as an inventory key prefix. */
        const val MAIN = "app/src/main/java/app/knotwork/android"

        /** Where the `full` flavour's code lives, as an inventory key prefix. */
        const val FULL = "app/src/full/java/app/knotwork/android"

        /** Where the design-system module's code lives, as an inventory key prefix. */
        const val CATALOG = "catalog/src/main/java/app/knotwork/design"

        /** Inventory key of the file the gate was written for. */
        const val SEARCH_TOOL_PATH = "$MAIN/data/tools/local/SearchTool.kt"

        /** Inventory key of the crash-report upload — the egress outside `app/src/main`. */
        const val CRASHLYTICS_PATH = "$FULL/data/repositories/FirebaseCrashReportingRepositoryImpl.kt"

        /** The call that tells the privacy indicator about a request. */
        const val RECORD_CALL = "recordOutbound("

        /** How a caller reaches a chat client: the factory, or the domain interface it is bound to. */
        val CHAT_FACTORY = listOf("KoogClientFactory", "CloudLlmClientFactory")

        /** How a caller reaches an embedding client. */
        val EMBEDDING_FACTORY = listOf("KoogEmbedderFactory")

        /** The files that send model traffic through a client built by a factory below. */
        val CHAT_CALLERS = listOf(
            "$MAIN/domain/engine/executors/CloudLlmNodeExecutor.kt",
            "$MAIN/data/engine/KoogStructuredInferenceClientFactory.kt",
            "$MAIN/data/tools/local/DelegateTaskTool.kt",
        )

        /** The files that send memory text through an embedding client. */
        val EMBEDDING_CALLERS = listOf(
            "$MAIN/data/services/embedding/CloudEmbeddingProvider.kt",
            "$MAIN/data/services/embedding/OllamaEmbeddingProvider.kt",
        )

        /** Why the crash-report path is invisible to the privacy indicator. */
        const val FIREBASE_NOT_SHOWN =
            "the Firebase SDK sends crash reports on its own schedule, after the user's opt-in; the app " +
                "hands it a report and never sees the connection, so it has nothing to record"

        /** Why Coil in the catalog opens nothing — the premise is tested above. */
        const val COIL_WITHOUT_NETWORK =
            "renders its model with Coil 3, which fetches nothing over the network without a coil-network-* " +
                "artifact; none is on the classpath (checked by `the image loader has no network fetcher`), " +
                "and the app hands these components local absolute paths"

        /** A numbered third-level heading in `PRIVACY.md`: `### 3.4 Outbound requests…`. */
        val SUBSECTION_HEADING = Regex("""(?m)^###\s+(\d+\.\d+)\s""")

        /**
         * Every production file that imports a network client, and what it does with it.
         *
         * Keys are repository-relative paths. Ordered by package so a new entry lands
         * next to its neighbours and a reviewer can see the whole egress surface at once.
         */
        val INVENTORY: Map<String, Egress> = mapOf(
            // --- Cloud model providers (PRIVACY 3.1) -------------------------------
            "$MAIN/data/engine/KoogClientFactory.kt" to
                Egress.Opens("3.1", Indicator.RecordedBy(CHAT_CALLERS, CHAT_FACTORY)),
            "$MAIN/data/engine/KoogStructuredInferenceClientFactory.kt" to
                Egress.Opens("3.1", Indicator.RecordsItself),
            "$MAIN/data/engine/retry/CloudRetryWrapper.kt" to
                Egress.Opens(
                    "3.1",
                    Indicator.RecordedBy(CHAT_CALLERS + EMBEDDING_CALLERS, listOf("CloudRetryWrapper")),
                ),
            "$MAIN/data/engine/retry/RetryObservingLLMClient.kt" to
                Egress.Opens(
                    "3.1",
                    Indicator.RecordedBy(CHAT_CALLERS + EMBEDDING_CALLERS, listOf("RetryObservingLLMClient")),
                ),
            "$MAIN/domain/engine/executors/CloudLlmNodeExecutor.kt" to
                Egress.Opens("3.1", Indicator.RecordsItself),
            "$MAIN/data/tools/local/DelegateTaskTool.kt" to
                Egress.Opens("3.1", Indicator.RecordsItself),
            "$MAIN/data/services/embedding/CloudEmbeddingProvider.kt" to
                Egress.Opens("3.1", Indicator.RecordsItself),
            "$MAIN/data/services/embedding/OllamaEmbeddingProvider.kt" to
                Egress.Opens("3.1", Indicator.RecordsItself),
            "$MAIN/data/services/embedding/KoogEmbedderFactory.kt" to
                Egress.Opens("3.1", Indicator.RecordedBy(EMBEDDING_CALLERS, EMBEDDING_FACTORY)),

            // --- MCP servers (PRIVACY 3.2) ----------------------------------------
            "$MAIN/data/mcp/KoogMcpClient.kt" to
                Egress.Opens("3.2", Indicator.RecordsItself),

            // --- Model downloads (PRIVACY 3.3) ------------------------------------
            "$MAIN/data/network/ResumableFileDownloader.kt" to
                Egress.Opens("3.3", Indicator.RecordsItself),
            "$MAIN/data/network/huggingface/HuggingFaceModelApi.kt" to
                Egress.Opens("3.3", Indicator.RecordsItself),

            // --- Tools that reach the network (PRIVACY 3.4) ------------------------
            SEARCH_TOOL_PATH to
                Egress.Opens("3.4", Indicator.RecordsItself),
            "$MAIN/data/tools/local/executors/HttpRequestExecutor.kt" to
                Egress.Opens("3.4", Indicator.RecordsItself),

            // --- Crash reporting, `full` flavour only (PRIVACY 3.5) -----------------
            CRASHLYTICS_PATH to
                Egress.Opens("3.5", Indicator.NotShown(FIREBASE_NOT_SHOWN)),
            "$FULL/di/CrashReportingModule.kt" to
                Egress.Opens("3.5", Indicator.NotShown(FIREBASE_NOT_SHOWN)),

            // --- Imports a network namespace, opens nothing ------------------------
            "$MAIN/di/AppModule.kt" to
                Egress.None(
                    "provides the shared OkHttpClient; every request made with it is issued by a " +
                        "file listed above, and this module issues none itself",
                ),
            "$MAIN/data/network/CleartextGuardInterceptor.kt" to
                Egress.None(
                    "an OkHttp interceptor on the shared client: it inspects and refuses requests " +
                        "other files originate, and never starts one",
                ),
            "$MAIN/data/engine/CloudClientTimeouts.kt" to
                Egress.None("a deadline value the client factories pass to Koog; builds no client, sends nothing"),
            "$MAIN/data/engine/KoogModelMapper.kt" to
                Egress.None("maps provider model ids to Koog model descriptors; no client, no call"),
            "$MAIN/data/engine/KoogCloudLlmModelResolver.kt" to
                Egress.None("resolves a model descriptor per provider; the call is made by the factory"),
            "$MAIN/presentation/ui/chat/home/ContentReportIssueUrl.kt" to
                Egress.None(
                    "builds the prefilled GitHub issue URL with java.net.URLEncoder. The report — the " +
                        "user's note and up to 3000 characters of the flagged reply — rides in its query " +
                        "string, so the browser sends it to github.com when the user taps Open issue " +
                        "(PRIVACY 4 says so); the app itself opens no connection",
                ),
            "$MAIN/data/local/AudioCaptureStoreImpl.kt" to
                Egress.None("uses android.webkit.MimeTypeMap to name a recording's MIME type; no WebView"),
            "$CATALOG/components/chat/ImageThumbnail.kt" to
                Egress.None(COIL_WITHOUT_NETWORK),
            "$CATALOG/components/chat/ImageViewer.kt" to
                Egress.None(COIL_WITHOUT_NETWORK),
            "$CATALOG/components/chat/ImageAttachmentCatalogContent.kt" to
                Egress.None("catalog preview content: solid-colour placeholder images and a preview handler"),
        )
    }
}
