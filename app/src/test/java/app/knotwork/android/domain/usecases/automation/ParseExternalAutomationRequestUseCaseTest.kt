package app.knotwork.android.domain.usecases.automation

import app.knotwork.android.domain.constants.ExternalAutomationContract
import app.knotwork.android.domain.models.ExternalAutomationInvocation
import app.knotwork.android.domain.models.ExternalAutomationRejectionReason
import app.knotwork.android.domain.models.ExternalAutomationTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ParseExternalAutomationRequestUseCase].
 *
 * This is the syntax surface of a public contract whose callers the project does
 * not control, so every refusal branch is asserted individually: a parser that
 * quietly repaired one of them would hand a pipeline a request nobody sent.
 */
class ParseExternalAutomationRequestUseCaseTest {

    private val useCase = ParseExternalAutomationRequestUseCase()

    // `vararg` first so a call site can list extras positionally; the action is
    // named on the one test that overrides it.
    private fun invocation(
        vararg extras: Pair<String, String?>,
        action: String = ExternalAutomationContract.ACTION_RUN_PIPELINE,
    ) = ExternalAutomationInvocation(action = action, extras = extras.toMap())

    private fun parsed(result: ExternalAutomationParseResult): ExternalAutomationParseResult.Parsed {
        assertTrue("expected a parsed request, got $result", result is ExternalAutomationParseResult.Parsed)
        return result as ExternalAutomationParseResult.Parsed
    }

    private fun reasonOf(result: ExternalAutomationParseResult): ExternalAutomationRejectionReason {
        assertTrue("expected an invalid result, got $result", result is ExternalAutomationParseResult.Invalid)
        return (result as ExternalAutomationParseResult.Invalid).reason
    }

    /* ---------------- Happy paths ---------------- */

    @Test
    fun `given a target by id and a plain prompt when parsing then the request is accepted`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "summarise my day",
            ),
        )

        val request = parsed(result).request
        assertEquals(ExternalAutomationTarget.ById("pipe-1"), request.target)
        assertEquals("summarise my day", request.prompt)
        assertEquals("req-1", request.requestId)
    }

    @Test
    fun `given a target by name when parsing then the name form is preserved rather than resolved`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_NAME to "Evening journal",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals(ExternalAutomationTarget.ByName("Evening journal"), parsed(result).request.target)
    }

    @Test
    fun `given a base64 prompt when parsing then it is decoded`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                // "summarise my day"
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "c3VtbWFyaXNlIG15IGRheQ==",
            ),
        )

        assertEquals("summarise my day", parsed(result).request.prompt)
    }

    @Test
    fun `given an unpadded base64 prompt when parsing then it is still decoded`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                // "summarise my day" without the trailing '=' padding
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "c3VtbWFyaXNlIG15IGRheQ",
            ),
        )

        assertEquals("summarise my day", parsed(result).request.prompt)
    }

    @Test
    fun `given the padded and unpadded forms documented in the contract then both decode alike`() {
        // The exact pair `docs/external-automation.md` shows a caller.
        fun promptOf(encoded: String) = parsed(
            useCase(
                invocation(
                    ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                    ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                    ExternalAutomationContract.EXTRA_PROMPT_B64 to encoded,
                ),
            ),
        ).request.prompt

        assertEquals("hi", promptOf("aGk="))
        assertEquals("hi", promptOf("aGk"))
    }

    @Test
    fun `given a non ascii base64 prompt when parsing then it round trips as UTF-8`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                // "привет — hello"
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "0L/RgNC40LLQtdGCIOKAlCBoZWxsbw==",
            ),
        )

        assertEquals("привет — hello", parsed(result).request.prompt)
    }

    @Test
    fun `given no return action when parsing then the contract default is used`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals(ExternalAutomationContract.ACTION_RUN_RESULT, parsed(result).request.returnAction)
    }

    @Test
    fun `given a return action when parsing then the caller's action is kept`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_RETURN_ACTION to "net.dinglisch.android.tasker.ACTION",
            ),
        )

        assertEquals("net.dinglisch.android.tasker.ACTION", parsed(result).request.returnAction)
    }

    @Test
    fun `given no return package when parsing then fire and forget is accepted rather than refused`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertNull(parsed(result).request.returnPackage)
    }

    @Test
    fun `given a return package when parsing then it is kept for the callback`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "net.dinglisch.android.taskerm",
            ),
        )

        assertEquals("net.dinglisch.android.taskerm", parsed(result).request.returnPackage)
    }

    @Test
    fun `given padded values when parsing then surrounding whitespace is trimmed`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "  req-1  ",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "  pipe-1  ",
                ExternalAutomationContract.EXTRA_PROMPT to "  go  ",
            ),
        )

        val request = parsed(result).request
        assertEquals("req-1", request.requestId)
        assertEquals(ExternalAutomationTarget.ById("pipe-1"), request.target)
        assertEquals("go", request.prompt)
    }

    /* ---------------- Refusals ---------------- */

    @Test
    fun `given an unknown action when parsing then it is refused as an unknown action`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                action = "app.knotwork.android.action.SOMETHING_ELSE",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.UNKNOWN_ACTION, reasonOf(result))
    }

    @Test
    fun `given a callback is asked for and no request id when parsing then it is refused`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "net.dinglisch.android.taskerm",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.REQUEST_ID_MISSING, reasonOf(result))
    }

    @Test
    fun `given a callback is asked for and a blank request id when parsing then blank counts as absent`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "   ",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "net.dinglisch.android.taskerm",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.REQUEST_ID_MISSING, reasonOf(result))
    }

    @Test
    fun `given no callback is asked for and no request id when parsing then the request is accepted`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        // The minimum call an automation app with two extra fields can make.
        assertEquals("", parsed(result).request.requestId)
    }

    @Test
    fun `given no callback is asked for and a blank request id when parsing then the request is accepted`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "   ",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals("", parsed(result).request.requestId)
    }

    @Test
    fun `given a return action but no return package and no request id when parsing then it is accepted`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_RETURN_ACTION to "com.example.RESULT",
            ),
        )

        // The callback address is the return *package*: naming only an action
        // delivers nothing, so it must not be what makes the id required.
        assertEquals("", parsed(result).request.requestId)
    }

    @Test
    fun `given no target when parsing then it is refused rather than defaulted to the binding`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.TARGET_MISSING, reasonOf(result))
    }

    @Test
    fun `given both target forms when parsing then it is refused rather than reconciled`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PIPELINE_NAME to "Evening journal",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.TARGET_AMBIGUOUS, reasonOf(result))
    }

    @Test
    fun `given no prompt when parsing then it is refused`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.PROMPT_MISSING, reasonOf(result))
    }

    @Test
    fun `given both prompt forms when parsing then it is refused rather than preferring one`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "Z28=",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.PROMPT_AMBIGUOUS, reasonOf(result))
    }

    @Test
    fun `given an undecodable base64 prompt when parsing then it is refused as undecodable`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "not base64 at all!!",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.PROMPT_UNDECODABLE, reasonOf(result))
    }

    @Test
    fun `given a base64url prompt when parsing then the foreign alphabet is refused rather than mis-decoded`() {
        // "??~" in base64url; decoding it with the standard table would hand the
        // pipeline text the caller never wrote.
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "-_8=",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.PROMPT_UNDECODABLE, reasonOf(result))
    }

    @Test
    fun `given a line-wrapped base64 prompt when parsing then the embedded newline is refused`() {
        // GNU base64 wraps its output at 76 characters, so a caller piping a long
        // prompt through it without `tr -d '\n'` sends exactly this. The documented
        // examples say so; this pins the behaviour they describe. Trimming cannot
        // save it — the newline is interior, not surrounding.
        val wrapped = "SGVsbG8sIHRoaXMgcHJvbXB0IGlzIGxvbmcgZW5vdWdoIHRoYXQgR05VIGJhc2U2NCB3b3VsZCB3\ncmFwIGl0Lg=="
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT_B64 to wrapped,
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.PROMPT_UNDECODABLE, reasonOf(result))
    }

    @Test
    fun `given a base64 prompt that decodes to blank when parsing then it is refused as missing`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                // "   "
                ExternalAutomationContract.EXTRA_PROMPT_B64 to "ICAg",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.PROMPT_MISSING, reasonOf(result))
    }

    @Test
    fun `given an entirely empty invocation when parsing then the action is reported first`() {
        // Fixed check order: a call that is wrong in several ways always reports
        // the same reason, so a caller fixing one problem at a time sees progress.
        val result = useCase(ExternalAutomationInvocation(action = "", extras = emptyMap()))

        assertEquals(ExternalAutomationRejectionReason.UNKNOWN_ACTION, reasonOf(result))
    }

    @Test
    fun `given a null extra value when parsing then it counts as absent rather than crashing`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "req-1",
                ExternalAutomationContract.EXTRA_PIPELINE_ID to null,
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.TARGET_MISSING, reasonOf(result))
    }

    /* ---------------- Value ceilings ---------------- */

    private val maxId = ExternalAutomationContract.MAX_REQUEST_ID_LENGTH
    private val maxAddress = ExternalAutomationContract.MAX_RETURN_ADDRESS_LENGTH

    private fun callbackRequest(
        requestId: String = "req-1",
        returnPackage: String = "net.dinglisch.android.taskerm",
        returnAction: String = "net.dinglisch.android.taskerm.RESULT",
    ) = useCase(
        invocation(
            ExternalAutomationContract.EXTRA_REQUEST_ID to requestId,
            ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
            ExternalAutomationContract.EXTRA_PROMPT to "go",
            ExternalAutomationContract.EXTRA_RETURN_PACKAGE to returnPackage,
            ExternalAutomationContract.EXTRA_RETURN_ACTION to returnAction,
        ),
    )

    @Test
    fun `given every callback value exactly at its ceiling when parsing then the request is accepted`() {
        val request = parsed(
            callbackRequest(
                requestId = "r".repeat(maxId),
                returnPackage = "p".repeat(maxAddress),
                returnAction = "a".repeat(maxAddress),
            ),
        ).request

        assertEquals(maxId, request.requestId.length)
    }

    @Test
    fun `given a request id one past its ceiling when parsing then it is refused rather than cut`() {
        val result = callbackRequest(requestId = "r".repeat(maxId + 1))

        assertEquals(ExternalAutomationRejectionReason.VALUE_TOO_LONG, reasonOf(result))
    }

    @Test
    fun `given a return package one past its ceiling when parsing then it is refused`() {
        val result = callbackRequest(returnPackage = "p".repeat(maxAddress + 1))

        assertEquals(ExternalAutomationRejectionReason.VALUE_TOO_LONG, reasonOf(result))
    }

    @Test
    fun `given a return action one past its ceiling when parsing then it is refused`() {
        val result = callbackRequest(returnAction = "a".repeat(maxAddress + 1))

        assertEquals(ExternalAutomationRejectionReason.VALUE_TOO_LONG, reasonOf(result))
    }

    @Test
    fun `given a fire-and-forget call with an over-long id when parsing then it is still refused`() {
        // The id is also what the journal shows; a call that asks for no answer does
        // not get to put more of its text there than one that does.
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "r".repeat(maxId + 1),
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.VALUE_TOO_LONG, reasonOf(result))
    }

    @Test
    fun `given an over-long address and no request id when parsing then the length is reported first`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_PIPELINE_ID to "pipe-1",
                ExternalAutomationContract.EXTRA_PROMPT to "go",
                ExternalAutomationContract.EXTRA_RETURN_PACKAGE to "p".repeat(maxAddress + 1),
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.VALUE_TOO_LONG, reasonOf(result))
    }

    @Test
    fun `given an unknown action and an over-long id when parsing then the action is still reported first`() {
        val result = useCase(
            invocation(
                ExternalAutomationContract.EXTRA_REQUEST_ID to "r".repeat(maxId + 1),
                action = "app.knotwork.android.action.SOMETHING_ELSE",
            ),
        )

        assertEquals(ExternalAutomationRejectionReason.UNKNOWN_ACTION, reasonOf(result))
    }
}
