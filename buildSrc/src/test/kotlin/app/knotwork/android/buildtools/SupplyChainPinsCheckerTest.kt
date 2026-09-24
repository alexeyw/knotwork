package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [SupplyChainPinsChecker].
 *
 * The workflow rule has to reject every ref that can move under the same text —
 * a tag, a branch, an abbreviated SHA — while accepting the two forms that
 * cannot: a local action and a full commit SHA. The wrapper and verification
 * rules each have to fail on the file as it was before the property existed,
 * since "a later regeneration silently dropped it" is the whole failure they are
 * written against.
 */
class SupplyChainPinsCheckerTest {

    private fun workflow(text: String) = SupplyChainPinsChecker.checkWorkflow(".github/workflows/ci.yml", text.trimIndent())

    @Test
    fun `given an action pinned to a tag then it is reported`() {
        val violations = workflow(
            """
            steps:
              - name: Checkout
                uses: actions/checkout@v6
            """,
        )

        assertEquals(1, violations.size)
        assertEquals(3, violations.single().line)
        assertTrue(violations.single().message.contains("actions/checkout@v6"))
    }

    @Test
    fun `given an action pinned to a branch then it is reported`() {
        assertEquals(1, workflow("- uses: some/action@main").size)
    }

    @Test
    fun `given an abbreviated commit sha then it is reported`() {
        assertEquals(1, workflow("- uses: some/action@a421e43 # v2.38.0").size)
    }

    @Test
    fun `given an action with no ref at all then it is reported`() {
        assertEquals(1, workflow("- uses: some/action").size)
    }

    @Test
    fun `given a full commit sha with a version comment then it passes`() {
        val violations = workflow(
            """
            - uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d # v2.38.0
              with:
                api-level: 36
            """,
        )

        assertEquals(emptyList<SupplyChainPinsChecker.Violation>(), violations)
    }

    @Test
    fun `given a full commit sha without a version comment then it is reported`() {
        // The SHA alone is safe but unreadable, and Dependabot rewrites the version
        // only where the comment sits on the same line.
        val violations = workflow("- uses: actions/checkout@d23441a48e516b6c34aea4fa41551a30e30af803")

        assertEquals(1, violations.size)
        assertTrue(violations.single().message.contains("version comment"))
    }

    @Test
    fun `given an action in a subdirectory of its repository then the sha rule still applies`() {
        assertEquals(1, workflow("- uses: gradle/actions/setup-gradle@v6").size)
        assertEquals(
            0,
            workflow("- uses: gradle/actions/setup-gradle@9c971963bec38e04b3d30dcc455b5382be2fdbfb # v6.3.0").size,
        )
    }

    @Test
    fun `given a quoted ref then it is read like an unquoted one`() {
        assertEquals(1, workflow("- uses: 'actions/checkout@v6'").size)
        assertEquals(1, workflow("- uses: \"actions/checkout@v6\"").size)
    }

    @Test
    fun `given a local action or reusable workflow then it passes`() {
        // Its contents are part of this commit, so there is nothing to pin.
        assertEquals(0, workflow("    uses: ./.github/workflows/check.yml").size)
    }

    @Test
    fun `given a docker image then only a digest passes`() {
        assertEquals(1, workflow("- uses: docker://alpine:3.20").size)
        assertEquals(
            0,
            workflow(
                "- uses: docker://alpine@sha256:" +
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ).size,
        )
    }

    @Test
    fun `given uses in a yaml comment then it is ignored`() {
        assertEquals(0, workflow("# uses: actions/checkout@v6 was the old form").size)
    }

    @Test
    fun `given several references then each unpinned one is reported with its own line`() {
        val violations = workflow(
            """
            - uses: actions/checkout@v6
            - uses: actions/setup-java@b6effb05e454b25005698d916606bdc6ffcbf961 # v5.7.0
            - uses: actions/upload-artifact@v4
            """,
        )

        assertEquals(listOf(1, 3), violations.map { it.line })
    }

    @Test
    fun `given a workflow then the references it found are counted`() {
        // The task refuses a tree in which nothing was found: a checker that
        // silently matches no line passes every workflow.
        val text = "- uses: actions/checkout@v6\n- uses: ./local\nrun: echo uses: nothing"

        assertEquals(2, SupplyChainPinsChecker.countReferences(text))
    }

    @Test
    fun `given wrapper properties without a distribution checksum then it is reported`() {
        val violations = SupplyChainPinsChecker.checkWrapperProperties(
            "gradle/wrapper/gradle-wrapper.properties",
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-9.7.1-bin.zip\n" +
                "validateDistributionUrl=true\n",
        )

        assertEquals(1, violations.size)
        assertTrue(violations.single().message.contains("distributionSha256Sum"))
    }

    @Test
    fun `given a malformed distribution checksum then it is reported`() {
        val violations = SupplyChainPinsChecker.checkWrapperProperties(
            "gradle-wrapper.properties",
            "distributionSha256Sum=acd53f1e\n",
        )

        assertEquals(1, violations.size)
    }

    @Test
    fun `given a well-formed distribution checksum then it passes`() {
        val violations = SupplyChainPinsChecker.checkWrapperProperties(
            "gradle-wrapper.properties",
            "distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a\n",
        )

        assertEquals(emptyList<SupplyChainPinsChecker.Violation>(), violations)
    }
}
