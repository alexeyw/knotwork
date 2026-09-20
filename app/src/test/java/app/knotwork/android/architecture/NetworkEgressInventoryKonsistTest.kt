package app.knotwork.android.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
         */
        data class Opens(val privacySection: String) : Egress

        /**
         * The file imports a network namespace without opening anything — a URL builder,
         * a model descriptor, an interceptor on somebody else's client.
         *
         * @property reason Why the import is not an egress, in words a reviewer can check
         *   against the file.
         */
        data class None(val reason: String) : Egress
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

    /**
     * Repository-relative paths, under `app/src/main`, of every production file whose
     * imports include a network client.
     *
     * Konsist parses the imports rather than the file text: an `import` line is easy to
     * match with a regular expression and just as easy to miss one of, and a missed file
     * is a missed path — the exact false green this gate exists to prevent.
     */
    private fun filesImportingANetworkClient(): Set<String> = ArchitectureScope.production
        .files
        .filter { file ->
            file.imports.any { import -> NETWORK_IMPORT_PREFIXES.any(import.name::startsWith) }
        }
        .mapTo(mutableSetOf()) { file -> file.path.substringAfter("$PRODUCTION_SOURCE_PATH/") }

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

        /** Path prefix Konsist reports files under, stripped to get repository-relative paths. */
        const val PRODUCTION_SOURCE_PATH = "app/src/main"

        /** Inventory key of the file the gate was written for. */
        const val SEARCH_TOOL_PATH = "java/app/knotwork/android/data/tools/local/SearchTool.kt"

        /**
         * Import prefixes that mean "this file can speak to the network", matching the
         * three deny-list guards so the two halves of the rule cannot drift apart.
         */
        val NETWORK_IMPORT_PREFIXES = listOf(
            "okhttp3.",
            "retrofit2.",
            "ai.koog.",
            "io.ktor.",
            "java.net.",
        )

        /** A numbered third-level heading in `PRIVACY.md`: `### 3.4 Outbound requests…`. */
        val SUBSECTION_HEADING = Regex("""(?m)^###\s+(\d+\.\d+)\s""")

        /**
         * Every production file that imports a network client, and what it does with it.
         *
         * Keys are paths under `app/src/main`. Ordered by package so a new entry lands
         * next to its neighbours and a reviewer can see the whole egress surface at once.
         */
        val INVENTORY: Map<String, Egress> = mapOf(
            // --- Cloud model providers (PRIVACY 3.1) -------------------------------
            "java/app/knotwork/android/data/engine/KoogClientFactory.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/engine/KoogStructuredInferenceClientFactory.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/engine/retry/CloudRetryWrapper.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/engine/retry/RetryObservingLLMClient.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/domain/engine/executors/CloudLlmNodeExecutor.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/tools/local/DelegateTaskTool.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/services/embedding/CloudEmbeddingProvider.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/services/embedding/OllamaEmbeddingProvider.kt" to
                Egress.Opens("3.1"),
            "java/app/knotwork/android/data/services/embedding/KoogEmbedderFactory.kt" to
                Egress.Opens("3.1"),

            // --- MCP servers (PRIVACY 3.2) ----------------------------------------
            "java/app/knotwork/android/data/mcp/KoogMcpClient.kt" to
                Egress.Opens("3.2"),

            // --- Model downloads (PRIVACY 3.3) ------------------------------------
            "java/app/knotwork/android/data/network/ResumableFileDownloader.kt" to
                Egress.Opens("3.3"),
            "java/app/knotwork/android/data/network/huggingface/HuggingFaceModelApi.kt" to
                Egress.Opens("3.3"),

            // --- Tools that reach the network (PRIVACY 3.4) ------------------------
            SEARCH_TOOL_PATH to
                Egress.Opens("3.4"),
            "java/app/knotwork/android/data/tools/local/executors/HttpRequestExecutor.kt" to
                Egress.Opens("3.4"),

            // --- Imports a network namespace, opens nothing ------------------------
            "java/app/knotwork/android/di/AppModule.kt" to
                Egress.None(
                    "provides the shared OkHttpClient; every request made with it is issued by a " +
                        "file listed above, and this module issues none itself",
                ),
            "java/app/knotwork/android/data/network/CleartextGuardInterceptor.kt" to
                Egress.None(
                    "an OkHttp interceptor on the shared client: it inspects and refuses requests " +
                        "other files originate, and never starts one",
                ),
            "java/app/knotwork/android/data/engine/KoogModelMapper.kt" to
                Egress.None("maps provider model ids to Koog model descriptors; no client, no call"),
            "java/app/knotwork/android/data/engine/KoogCloudLlmModelResolver.kt" to
                Egress.None("resolves a model descriptor per provider; the call is made by the factory"),
            "java/app/knotwork/android/presentation/ui/chat/home/ContentReportIssueUrl.kt" to
                Egress.None("uses java.net.URLEncoder to build a URL string the user opens themselves"),
        )
    }
}
