package app.knotwork.android.data.tools.local.executors

import app.knotwork.android.domain.models.ToolExecutionContext
import app.knotwork.android.domain.models.WorkspaceError
import app.knotwork.android.domain.models.WorkspaceResult
import app.knotwork.android.domain.repositories.LocalToolExecutor
import app.knotwork.android.domain.repositories.SettingsRepository
import app.knotwork.android.domain.services.AgentWorkspace
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

/**
 * [LocalToolExecutor] for the built-in `read_file` tool (READ_ONLY).
 *
 * Reads a UTF-8 text file from the agent workspace, serving a bounded window of
 * its bytes so a single read can never overflow the local model's context
 * window:
 *
 *  - The served window is capped by the per-read token budget
 *    ([SettingsRepository.workspaceReadTokenBudget], converted to a byte ceiling
 *    via [APPROX_BYTES_PER_TOKEN]). An explicit `limit` may only *lower* the
 *    served size, never raise it past the budget.
 *  - `offset` / `limit` are measured in **bytes**, enabling page-by-page reads
 *    of a long file. The window's trailing edge is backed up to the nearest
 *    UTF-8 character boundary so a multi-byte character is never split across a
 *    page (making consecutive pages stitch back together without loss). Because
 *    that backing-up means the served byte count is not derivable from the
 *    returned text, the truncation marker reports the exact next `offset` to
 *    pass — the caller never has to count bytes itself.
 *  - When content remains past the served window, a
 *    `[... truncated, N bytes remain — use offset to continue]` marker is
 *    appended so the model knows to read on.
 *
 * Every [WorkspaceError] is mapped to a precise, human-readable observation
 * string instead of throwing — the agent sees the cause and can react.
 *
 * @property workspace The jailed file sandbox every read funnels through.
 * @property settingsRepository Source of the per-read token budget.
 */
class ReadFileExecutor @Inject constructor(
    private val workspace: AgentWorkspace,
    private val settingsRepository: SettingsRepository,
) : LocalToolExecutor {

    override val toolName: String = TOOL_NAME

    override suspend fun execute(arguments: String, context: ToolExecutionContext): String {
        val json = JSONObject(arguments)
        val path = json.optString("path", "")
        if (path.isBlank()) return "Error: missing 'path' argument."

        val offset = json.optInt("offset", 0).coerceAtLeast(0)
        val limit = if (json.has("limit")) json.optInt("limit", 0).takeIf { it > 0 } else null

        return when (val result = workspace.readText(path)) {
            is WorkspaceResult.Failure -> errorMessage(path, result.error)
            is WorkspaceResult.Success -> renderWindow(result.value, offset, limit)
        }
    }

    /**
     * Slices the [offset]..budget window out of [text]'s UTF-8 bytes, aligning
     * the trailing edge to a character boundary and appending the truncation
     * marker when content remains.
     */
    private suspend fun renderWindow(text: String, offset: Int, limit: Int?): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val total = bytes.size
        if (total == 0) return "(empty file)"
        if (offset >= total) {
            return "[offset $offset is at or past end of file ($total bytes total)]"
        }

        val budgetBytes = readBudgetBytes()
        val effectiveLimit = minOf(limit ?: budgetBytes, budgetBytes)
        val rawEnd = minOf(offset.toLong() + effectiveLimit, total.toLong()).toInt()
        val end = alignToCharBoundary(bytes, offset, rawEnd)

        val window = bytes.copyOfRange(offset, end)
        val decoded = decodeUtf8Lossy(window)
        val remaining = total - end
        return if (remaining > 0) {
            // Emit the exact next [offset] rather than asking the model to count bytes:
            // the served window is character-aligned, so its decoded length in characters
            // does not equal its byte length, and a model cannot reliably reconstruct it.
            decoded + "\n[... truncated, $remaining bytes remain — use offset $end to continue]"
        } else {
            decoded
        }
    }

    /** Resolves the per-read byte ceiling from the token budget, never below 1. */
    private suspend fun readBudgetBytes(): Int {
        val tokens = settingsRepository.workspaceReadTokenBudget.first()
        return (tokens.toLong() * APPROX_BYTES_PER_TOKEN)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Backs [rawEnd] up to the nearest UTF-8 character start so the returned
     * window never ends in the middle of a multi-byte character. Whole-file ends
     * are left untouched (the file ends on a boundary, as it decoded as valid
     * UTF-8). Falls back to [rawEnd] if backing up would yield an empty window
     * (pathologically small limit), preferring progress over a stuck read.
     */
    private fun alignToCharBoundary(bytes: ByteArray, offset: Int, rawEnd: Int): Int {
        if (rawEnd >= bytes.size) return rawEnd
        var end = rawEnd
        while (end > offset && isContinuationByte(bytes[end])) {
            end--
        }
        return if (end <= offset) rawEnd else end
    }

    /** A UTF-8 continuation byte matches the bit pattern `10xxxxxx`. */
    private fun isContinuationByte(b: Byte): Boolean =
        (b.toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX

    /**
     * Decodes [bytes] as UTF-8, silently dropping any malformed bytes. Because
     * [renderWindow] aligns the trailing edge to a character boundary, the only
     * malformed bytes possible are leading continuation bytes when the caller
     * resumed from a mid-character [offset]; dropping them keeps the output clean.
     */
    private fun decodeUtf8Lossy(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.IGNORE)
            .onUnmappableCharacter(CodingErrorAction.IGNORE)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    /** Maps a [WorkspaceError] to a concise observation string for the agent. */
    private fun errorMessage(path: String, error: WorkspaceError): String = when (error) {
        WorkspaceError.PathOutsideWorkspace -> "Error: path '$path' is outside the workspace."
        WorkspaceError.NotFound -> "Error: file '$path' not found."
        WorkspaceError.NotAText -> "Error: '$path' is not a UTF-8 text file and cannot be read as text."
        WorkspaceError.TooLarge -> "Error: '$path' exceeds the per-file read limit."
        WorkspaceError.InvalidPath -> WorkspaceToolMessages.INVALID_PATH
        WorkspaceError.AlreadyExists,
        WorkspaceError.QuotaExceeded,
        WorkspaceError.AnchorNotFound,
        is WorkspaceError.AnchorNotUnique,
        WorkspaceError.IsDirectory,
        -> "Error: '$path' could not be read."
    }

    companion object {
        /** Tool name as exposed to the LLM and used as the DI map key. */
        const val TOOL_NAME = "read_file"

        /** Human-facing label for the browser editor's tool dropdown. */
        const val TOOL_LABEL: String = "Read File"

        /**
         * Approximate bytes per token, used to convert the token-denominated
         * read budget into the byte ceiling this tool pages against. Mirrors the
         * `CHARS_PER_TOKEN` heuristic used elsewhere for ASCII-dominant content.
         */
        private const val APPROX_BYTES_PER_TOKEN: Int = 4

        /** Bit mask isolating the two high bits that mark a UTF-8 continuation byte. */
        private const val UTF8_CONTINUATION_MASK: Int = 0xC0

        /** Value of the masked high bits (`10xxxxxx`) for a UTF-8 continuation byte. */
        private const val UTF8_CONTINUATION_PREFIX: Int = 0x80

        /** Human-readable description steering the model on when/how to call. */
        const val DESCRIPTION: String =
            "Reads a UTF-8 text file from the agent's private workspace. Returns the file content, " +
                "truncated to a token budget so long files do not overflow the context window. " +
                "Use the byte 'offset' and 'limit' arguments to page through a long file: when the result " +
                "ends with a '[... truncated, N bytes remain — use offset M to continue]' marker, call again " +
                "with 'offset' set to the exact value M from that marker to read the next page."

        /** JSON-schema of the accepted arguments. */
        val PARAMETERS: String = """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Workspace-relative path of the file to read (e.g. reports/summary.md)." },
                "offset": { "type": "integer", "description": "Byte offset to start reading from. Default 0." },
                "limit": { "type": "integer", "description": "Maximum number of bytes to return. Capped by the read token budget when larger or omitted." }
              },
              "required": ["path"]
            }
        """.trimIndent()
    }
}
