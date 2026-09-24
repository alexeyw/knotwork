package app.knotwork.android.architecture

import app.knotwork.android.domain.promptpack.PromptPackMarkdownSerializer
import app.knotwork.android.domain.usecases.promptpack.ImportPromptPackUseCase
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Konsist guard enforcing the provenance rule of prompt packs: **a pack is
 * imported from a local file the user picked, never fetched.**
 *
 * A prompt pack is an instruction file that ends up inside the system prompt of
 * an agent holding tools. Downloaded over a link, it would be an executable
 * artefact from an untrusted source; the file picker differs from that in one
 * decisive way — a deliberate human action stands between the source and the
 * prompt. The project decided that deliberately, and recorded the two events
 * that would justify revisiting it (a signature or provenance check for packs,
 * or a moderated preset repository of its own).
 *
 * That decision was held by prose alone, which lasts exactly until the first
 * convenient occasion. This guard makes it structural: no file on the prompt-pack
 * path may import a network client (OkHttp, Retrofit, Koog, Ktor or raw
 * `java.net`). `android.net` is deliberately **not** forbidden — the file picker
 * legitimately hands the import path an `android.net.Uri`, and a rule that
 * banned it would be a rule someone has to suppress on day one.
 *
 * Two bounds worth stating rather than discovering later:
 *
 *  - The scope is `app/src/main` (see [ArchitectureScope]), so a prompt-pack
 *    file added under a `full` or `foss` flavour source set would not be seen.
 *    There are none today, and one would have to be added deliberately.
 *  - A filter written over names and paths goes stale silently: a new file on
 *    the same logical path, named something the filter does not match, is simply
 *    not guarded and nothing says so. The second test below closes that by
 *    measuring coverage with a different instrument — the file's own text — so
 *    a file that talks about prompt packs but escapes the filter fails the
 *    build instead of quietly leaving the surface.
 */
class PromptPackNoNetworkKonsistTest {

    @Test
    fun `prompt-pack files import no network client`() {
        val files = guardedFiles()

        // A guard that matched nothing would pass everything, which is the exact
        // failure mode this surface must not have.
        assertTrue(EMPTY_SCOPE_FAILURE, files.size >= MINIMUM_GUARDED_FILES)

        files.assertFalse(additionalMessage = NETWORK_IMPORT_FAILURE) { file ->
            file.imports.any { import ->
                NetworkClientImports.PREFIXES.any { prefix -> import.name.startsWith(prefix) }
            }
        }
    }

    @Test
    fun `every production file naming a prompt pack is inside the guarded scope`() {
        val guarded = guardedFiles().map { it.projectPath }.toSet()
        val mentions = ArchitectureScope.production
            .files
            .filter { file -> PROMPT_PACK_MENTIONS.any { file.text.contains(it) } }

        // `projectPath`, not `path`: an absolute path would name this machine's
        // checkout in a failure every other reader has to translate.
        val unguarded = mentions.map { it.projectPath }.filterNot { it in guarded }.sorted()

        assertTrue(
            "these files name a prompt pack but fall outside the no-network guard, so they are unprotected: " +
                "$unguarded. Name the file into the guard (a `PromptPack…` name, or the `promptpack` package), " +
                "rather than widening the filter until it matches everything.",
            unguarded.isEmpty(),
        )
    }

    /**
     * The prompt-pack surface: the packages that own it, the UI that drives the
     * picker, the types named after it, and anything importing those types.
     *
     * @return Every production file on the prompt-pack path.
     */
    private fun guardedFiles() = ArchitectureScope.production
        .files
        .filter { file ->
            file.projectPath.contains("/promptpack/") ||
                file.projectPath.contains("/ui/prompts/") ||
                file.name.contains("PromptPack") ||
                file.imports.any { import -> GUARDED_PACKAGES.any { import.name.startsWith(it) } }
        }

    private companion object {
        /**
         * Importing anything from these packages puts a file on the prompt-pack
         * path.
         *
         * Derived from the packages of two real declarations rather than
         * written as string literals: a package rename then moves the guard
         * with the code instead of silently emptying it.
         */
        val GUARDED_PACKAGES = listOf(
            PromptPackMarkdownSerializer::class.java.packageName,
            ImportPromptPackUseCase::class.java.packageName,
        ).map { "$it." }

        /** Text that marks a file as talking about prompt packs, for the coverage test. */
        val PROMPT_PACK_MENTIONS = listOf("PromptPack", "promptpack")

        /**
         * Floor for the guarded set: the surface has eleven files today, and a
         * filter that suddenly matches a handful has stopped describing it.
         */
        const val MINIMUM_GUARDED_FILES = 8

        const val EMPTY_SCOPE_FAILURE =
            "the prompt-pack no-network guard matched almost nothing. The surface has not shrunk to a " +
                "handful of files, so the name / path / import filter has stopped describing it — fix the " +
                "filter rather than letting the guard pass by covering nothing."

        const val NETWORK_IMPORT_FAILURE =
            "a prompt pack is imported from a local file the user picked, never fetched: no file on the " +
                "prompt-pack path may import a network client (NetworkClientImports). " +
                "A pack downloaded over a link would be an executable artefact from an untrusted source " +
                "landing in the system prompt of an agent that holds tools."
    }
}
