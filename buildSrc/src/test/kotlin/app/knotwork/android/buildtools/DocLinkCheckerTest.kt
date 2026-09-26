package app.knotwork.android.buildtools

import app.knotwork.android.buildtools.DocLinkChecker.PathKind
import app.knotwork.android.buildtools.DocLinkChecker.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DocLinkChecker].
 *
 * The file system arrives as a lambda, so these describe a repository that does
 * not exist — including the shapes that are awkward to create on disk (a target
 * climbing above the root, a Markdown file outside the scanned set). Run with
 * `./gradlew -p buildSrc test`.
 */
class DocLinkCheckerTest {

    /** Resolver for a repository holding exactly the named files and directories. */
    private fun repository(vararg entries: Pair<String, PathKind>): (String) -> PathKind {
        val known = entries.toMap()
        return { path -> known[path] ?: PathKind.MISSING }
    }

    @Test
    fun `given resolvable links when checked then no violations`() {
        val docs = mapOf(
            "README.md" to "See [the guide](docs/user-guide.md) and [a section](#features).\n\n## Features\n",
            "docs/user-guide.md" to "Back to [the readme](../README.md).\n",
        )

        val repository = repository("docs/user-guide.md" to PathKind.FILE, "README.md" to PathKind.FILE)
        val result = DocLinkChecker.check(docs, repository)

        assertTrue(result.violations.isEmpty())
        assertEquals(3, result.internalLinkCount)
    }

    @Test
    fun `given a missing file when checked then reported with its line`() {
        val docs = mapOf("README.md" to "text\n\n[gone](docs/gone.md)\n")

        val violations = DocLinkChecker.check(docs, repository()).violations

        assertEquals(1, violations.size)
        assertEquals(Reason.MISSING_FILE, violations[0].reason)
        assertEquals(3, violations[0].line)
        assertEquals("README.md:3: target does not exist -> `docs/gone.md`", violations[0].format())
    }

    @Test
    fun `given a missing anchor when checked then reported`() {
        val docs = mapOf(
            "README.md" to "[x](docs/a.md#no-such-heading)\n",
            "docs/a.md" to "## Real heading\n",
        )

        val violations = DocLinkChecker.check(docs, repository("docs/a.md" to PathKind.FILE)).violations

        assertEquals(listOf(Reason.MISSING_ANCHOR), violations.map { it.reason })
    }

    @Test
    fun `given an anchor into the same document when checked then it resolves`() {
        val docs = mapOf("docs/a.md" to "[x](#real-heading)\n\n## Real heading\n")

        assertTrue(DocLinkChecker.check(docs, repository()).violations.isEmpty())
    }

    @Test
    fun `given a target above the repository root when checked then reported`() {
        val docs = mapOf(".github/pull_request_template.md" to "[c](../../CONTRIBUTING.md)\n")

        val violations = DocLinkChecker.check(docs, repository()).violations

        assertEquals(listOf(Reason.OUTSIDE_REPOSITORY), violations.map { it.reason })
    }

    @Test
    fun `given a site-absolute target when checked then reported`() {
        val docs = mapOf("README.md" to "[c](/CONTRIBUTING.md)\n")

        val violations = DocLinkChecker.check(docs, repository()).violations

        assertEquals(listOf(Reason.SITE_ABSOLUTE), violations.map { it.reason })
    }

    @Test
    fun `given an anchor on a directory when checked then reported`() {
        val docs = mapOf("README.md" to "[d](docs/decisions#x)\n")

        val violations = DocLinkChecker.check(docs, repository("docs/decisions" to PathKind.DIRECTORY)).violations

        assertEquals(listOf(Reason.ANCHOR_ON_DIRECTORY), violations.map { it.reason })
    }

    @Test
    fun `given a directory target without an anchor when checked then it resolves`() {
        val docs = mapOf("README.md" to "[d](docs/decisions/)\n")

        val result = DocLinkChecker.check(docs, repository("docs/decisions" to PathKind.DIRECTORY))

        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `given an anchor into a Markdown file outside the scanned set when checked then the hole is reported`() {
        val docs = mapOf("README.md" to "[x](other/notes.md#section)\n")

        val violations = DocLinkChecker.check(docs, repository("other/notes.md" to PathKind.FILE)).violations

        assertEquals(listOf(Reason.UNSCANNED_TARGET), violations.map { it.reason })
    }

    @Test
    fun `given an anchor into a non-Markdown file when checked then only the file is required`() {
        val docs = mapOf("README.md" to "[x](app/build.gradle.kts#L10)\n")

        val result = DocLinkChecker.check(docs, repository("app/build.gradle.kts" to PathKind.FILE))

        assertTrue(result.violations.isEmpty())
    }

    @Test
    fun `given external and mail links when checked then they are not gated`() {
        val docs = mapOf("README.md" to "[a](https://example.com/x) [b](mailto:x@example.com)\n")

        val result = DocLinkChecker.check(docs, repository())

        assertTrue(result.violations.isEmpty())
        assertEquals(0, result.internalLinkCount)
        assertEquals(listOf("https://example.com/x"), result.external.map { it.url })
    }

    @Test
    fun `given external links when only those are asked for then no file system is consulted`() {
        val docs = mapOf(
            "README.md" to "[a](https://example.com/x) and [b](docs/gone.md)\n",
            "docs/a.md" to "[c](HTTPS://Example.com/Y)\n",
        )

        val external = DocLinkChecker.externalLinksOf(docs)

        assertEquals(listOf("https://example.com/x", "HTTPS://Example.com/Y"), external.map { it.url })
        assertEquals(listOf("README.md", "docs/a.md"), external.map { it.file })
    }

    @Test
    fun `given a percent-encoded anchor when checked then it resolves against the decoded slug`() {
        val docs = mapOf(
            "README.md" to "[x](docs/a.md#%D1%82%D0%B5%D1%81%D1%82)\n",
            "docs/a.md" to "## тест\n",
        )

        assertTrue(DocLinkChecker.check(docs, repository("docs/a.md" to PathKind.FILE)).violations.isEmpty())
    }

    // ─── Inline-code paths ───────────────────────────────────────────────────

    /** A tree holding one source file under a package-relative `domain/` directory, plus two root documents. */
    private val tree = setOf(
        "SECURITY.md",
        "app/build.gradle.kts",
        "app/src/main/java/app/knotwork/android/domain/models/NodeType.kt",
        "docs/a.md",
    )

    @Test
    fun `given an inline code path to a source file that does not exist when checked then it is reported`() {
        val docs = mapOf("docs/a.md" to "Rule.\n\n- use the parser in `domain/parser/ToolArgumentParser.kt`.\n")

        val result = DocLinkChecker.checkCodePaths(docs, tree)

        assertEquals(1, result.violations.size)
        assertEquals(Reason.MISSING_CODE_PATH, result.violations[0].reason)
        assertEquals(3, result.violations[0].line)
        assertEquals(
            "docs/a.md:3: inline-code path names no file in the repository -> `domain/parser/ToolArgumentParser.kt`",
            result.violations[0].format(),
        )
    }

    @Test
    fun `given inline code paths written from the root, the document or the package when checked then all resolve`() {
        val docs = mapOf(
            "docs/a.md" to "`app/build.gradle.kts`, `../SECURITY.md`, `./a.md` and `domain/models/NodeType.kt`.\n",
        )

        val result = DocLinkChecker.checkCodePaths(docs, tree)

        assertTrue(result.violations.isEmpty())
        assertEquals(4, result.checkedCount)
    }

    @Test
    fun `given an inline code path with a line or an anchor when checked then only the path is resolved`() {
        val docs = mapOf("docs/a.md" to "`app/build.gradle.kts:42`, `domain/models/NodeType.kt#L10`, `docs/gone.md:7`\n")

        val result = DocLinkChecker.checkCodePaths(docs, tree)

        assertEquals(listOf("docs/gone.md:7"), result.violations.map { it.target })
        assertEquals(3, result.checkedCount)
    }

    @Test
    fun `given spans that do not name one repository file when checked then none is read as a path`() {
        val docs = mapOf(
            "docs/a.md" to listOf(
                "`app/src/main/…/FILE_MAP.md`", // elided
                "`catalog/.../NodeConfig.kt`", // elided, ASCII
                "`docs/*.md`", // glob
                "`fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt`", // template
                "`app/build/reports/kover/reportFullDebug.xml`", // build output
                "`reports/name.md`", // first segment is no directory of the repository
                "`/data/local/tmp/domain/x.json`", // absolute: a device path, not a repository one
                "`NodeType.kt`", // no directory at all
                "`./gradlew check`", // a command
                "`https://example.com/domain/x.md`", // a URL
                "`domain/models/`", // a directory, no file extension
                "`domain/models/NodeType`", // a class name, no file extension
            ).joinToString("\n"),
        )

        val result = DocLinkChecker.checkCodePaths(docs, tree)

        assertTrue(result.violations.isEmpty())
        assertEquals(0, result.checkedCount)
    }

    @Test
    fun `given a missing path inside a fenced block or an HTML comment when checked then it is not read`() {
        val docs = mapOf(
            "docs/a.md" to "```\nsee `domain/gone/Gone.kt`\n```\n<!-- `domain/gone/Gone.kt` -->\n",
        )

        val result = DocLinkChecker.checkCodePaths(docs, tree)

        assertTrue(result.violations.isEmpty())
        assertEquals(0, result.checkedCount)
    }

    @Test
    fun `given a path climbing above the repository root when checked then it is reported`() {
        val docs = mapOf("docs/a.md" to "`../../SECURITY.md`\n")

        val violations = DocLinkChecker.checkCodePaths(docs, tree).violations

        assertEquals(listOf(Reason.MISSING_CODE_PATH), violations.map { it.reason })
    }

    @Test
    fun `given a relative path whose suffix exists elsewhere when checked then it is not resolved by suffix`() {
        val docs = mapOf("docs/a.md" to "`../models/NodeType.kt`\n")

        val violations = DocLinkChecker.checkCodePaths(docs, tree).violations

        assertEquals(listOf("../models/NodeType.kt"), violations.map { it.target })
    }
}
