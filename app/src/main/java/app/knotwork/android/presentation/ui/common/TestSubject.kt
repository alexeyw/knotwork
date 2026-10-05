package app.knotwork.android.presentation.ui.common

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import app.knotwork.android.R
import app.knotwork.android.domain.connection.AddressRefusal
import app.knotwork.android.domain.connection.ConnectionCheckResult
import app.knotwork.android.domain.connection.ConnectionFailure
import app.knotwork.android.domain.connection.ConnectionRefusal
import app.knotwork.design.components.misc.TestProbeTone
import app.knotwork.design.components.misc.TestProbeUi
import kotlinx.coroutines.delay

/**
 * What a test row checks — it decides the words: a hosted provider is named and its failures are
 * "on their side", a server the user runs has a log to look at, an MCP server has an endpoint.
 */
sealed interface TestSubject {

    /**
     * A hosted provider.
     *
     * @property name Its name, e.g. "Groq".
     */
    data class Hosted(val name: String) : TestSubject

    /**
     * A model server the user runs.
     *
     * @property name Its name, e.g. "Ollama".
     */
    data class OwnServer(val name: String) : TestSubject

    /** An MCP server. */
    data object Mcp : TestSubject
}

/**
 * Resolves a test row's state into its words.
 *
 * Every failure states what happened, gives the code in brackets, and ends with one action; hosts
 * are named, keys never are.
 *
 * @param context Resource resolution.
 * @param subject What the row checks.
 * @param elapsedSeconds Seconds since a running check started; shown from three seconds on.
 * @return The resolved row.
 */
fun ConnectionTestState.toTestProbeUi(context: Context, subject: TestSubject, elapsedSeconds: Long): TestProbeUi {
    val label = context.getString(R.string.settings_test_label)
    val run = context.getString(R.string.settings_test_run)
    val again = context.getString(R.string.settings_test_again)
    return when (this) {
        is ConnectionTestState.Disabled ->
            TestProbeUi(label, TestProbeTone.Disabled, disabledText(context, reason, subject), actionLabel = run)
        ConnectionTestState.Idle -> TestProbeUi(
            label = label,
            tone = TestProbeTone.Idle,
            status = context.getString(
                if (subject ==
                    TestSubject.Mcp
                ) {
                    R.string.settings_test_idle_mcp
                } else {
                    R.string.settings_test_idle_provider
                },
            ),
            actionLabel = run,
        )
        is ConnectionTestState.Running -> TestProbeUi(
            label = label,
            tone = TestProbeTone.Running,
            status = runningText(context, host, subject, elapsedSeconds),
            actionLabel = context.getString(R.string.settings_test_cancel),
            accessibleStatus = context.getString(R.string.settings_test_a11y_started),
        )
        is ConnectionTestState.Finished -> when (val found = result) {
            is ConnectionCheckResult.Reachable ->
                TestProbeUi(
                    label,
                    TestProbeTone.Reachable,
                    reachableText(context, found.items.size, subject),
                    actionLabel = again,
                )
            is ConnectionCheckResult.Refused -> TestProbeUi(
                label = label,
                tone = TestProbeTone.Refused,
                status = refusedText(context, found.refusal, subject),
                head = context.getString(R.string.settings_test_refused_head),
                actionLabel = again,
            )
            is ConnectionCheckResult.Failed ->
                TestProbeUi(label, TestProbeTone.Failed, failureText(context, found, subject), actionLabel = again)
        }
    }
}

/** Why the button is disabled. An address refusal points up: its reason is under the field. */
private fun disabledText(context: Context, reason: ConnectionRefusal, subject: TestSubject): String = when (reason) {
    ConnectionRefusal.MissingAddress -> context.getString(
        if (subject == TestSubject.Mcp) {
            R.string.settings_test_disabled_no_url
        } else {
            R.string.settings_test_disabled_no_address
        },
    )
    ConnectionRefusal.NotAnAddress -> context.getString(R.string.settings_test_disabled_bad_url)
    ConnectionRefusal.MissingKey -> context.getString(R.string.settings_test_disabled_no_key)
    is AddressRefusal.CleartextNeedsApproval -> context.getString(R.string.settings_test_disabled_cleartext)
    is AddressRefusal.HostNotLocal, is AddressRefusal.PublicCleartext ->
        context.getString(R.string.settings_test_disabled_refused_address)
    ConnectionRefusal.BlockedByLocalOnlyMode ->
        context.getString(R.string.settings_test_refused_block_hosted, subject.displayName())
}

/** A refusal that a check reported: nothing was sent. */
private fun refusedText(context: Context, refusal: ConnectionRefusal, subject: TestSubject): String = when (refusal) {
    ConnectionRefusal.BlockedByLocalOnlyMode ->
        context.getString(R.string.settings_test_refused_block_hosted, subject.displayName())
    is AddressRefusal.HostNotLocal -> context.getString(R.string.settings_test_refused_block_host, refusal.host)
    is AddressRefusal.PublicCleartext -> context.getString(R.string.settings_test_refused_public_http, refusal.host)
    else -> disabledText(context, refusal, subject)
}

private fun runningText(context: Context, host: String, subject: TestSubject, elapsedSeconds: Long): String {
    val mcp = subject == TestSubject.Mcp
    return if (elapsedSeconds < ELAPSED_SHOWN_FROM_SECONDS) {
        context.getString(
            if (mcp) R.string.settings_test_running_mcp_short else R.string.settings_test_running_short,
            host,
        )
    } else {
        context.getString(
            if (mcp) R.string.settings_test_running_mcp else R.string.settings_test_running,
            host,
            elapsedSeconds.toInt(),
        )
    }
}

private fun reachableText(context: Context, count: Int, subject: TestSubject): String = when {
    subject == TestSubject.Mcp -> context.resources.getQuantityString(R.plurals.settings_test_ok_tools, count, count)
    count == 0 -> context.getString(R.string.settings_test_ok_zero)
    else -> context.resources.getQuantityString(R.plurals.settings_test_ok_models, count, count)
}

private fun failureText(context: Context, failed: ConnectionCheckResult.Failed, subject: TestSubject): String {
    val host = failed.host
    return when (val failure = failed.failure) {
        is ConnectionFailure.Unauthorized -> unauthorizedText(context, failure, subject)
        is ConnectionFailure.NotFound -> context.getString(R.string.settings_test_failed_not_found, failure.url)
        is ConnectionFailure.RateLimited -> failure.wait?.let { wait ->
            context.getString(
                R.string.settings_test_failed_rate,
                subject.displayName(host),
                wait.inWholeSeconds.toInt(),
            )
        } ?: context.getString(R.string.settings_test_failed_rate_no_wait, subject.displayName(host))
        is ConnectionFailure.ServerError -> if (subject is TestSubject.Hosted) {
            context.getString(R.string.settings_test_failed_server_hosted, failure.status, subject.name)
        } else {
            context.getString(R.string.settings_test_failed_server_own, failure.status)
        }
        is ConnectionFailure.UnexpectedAnswer -> failure.excerpt?.let {
            context.getString(R.string.settings_test_failed_other, failure.status, it)
        } ?: context.getString(R.string.settings_test_failed_other_no_body, failure.status)
        ConnectionFailure.NoModelList -> context.getString(R.string.settings_test_failed_no_list)
        ConnectionFailure.NotMcp -> context.getString(R.string.settings_test_failed_not_mcp, host)
        ConnectionFailure.UnknownHost -> {
            // A hosted provider's name always resolves when the phone is online; a server's may be a typo.
            val text = if (subject is TestSubject.Hosted) {
                R.string.settings_test_failed_offline
            } else {
                R.string.settings_test_failed_dns
            }
            context.getString(text, host)
        }
        ConnectionFailure.ConnectionRefused -> context.getString(R.string.settings_test_failed_conn_refused, host)
        is ConnectionFailure.ConnectTimeout ->
            context.getString(R.string.settings_test_failed_timeout, host, failure.after.inWholeSeconds.toInt())
        is ConnectionFailure.Silence ->
            context.getString(R.string.settings_test_failed_silence, host, failure.after.inWholeSeconds.toInt())
        is ConnectionFailure.HandshakeTimeout ->
            context.getString(R.string.settings_test_failed_handshake, host, failure.after.inWholeSeconds.toInt())
        is ConnectionFailure.RedirectRefused -> failure.reason
        is ConnectionFailure.Other -> context.getString(R.string.settings_test_failed_unknown, failure.detail)
    }
}

/** A rejected key: none sent, an MCP server's credentials, a hosted provider's key, or a server's. */
private fun unauthorizedText(context: Context, failure: ConnectionFailure.Unauthorized, subject: TestSubject): String =
    when {
        subject == TestSubject.Mcp -> context.getString(R.string.settings_test_failed_mcp_auth, failure.status)
        !failure.keySent -> context.getString(R.string.settings_test_failed_key_missing, failure.status)
        subject is TestSubject.Hosted ->
            context.getString(R.string.settings_test_failed_key_hosted, failure.status, subject.name)
        else -> context.getString(R.string.settings_test_failed_key_own, failure.status)
    }

/** The subject's name; [fallback] (the host) for an MCP server, which has none of its own. */
private fun TestSubject.displayName(fallback: String = ""): String = when (this) {
    is TestSubject.Hosted -> name
    is TestSubject.OwnServer -> name
    TestSubject.Mcp -> fallback
}

/**
 * Seconds since a running test started, ticking once a second while it runs; `0` otherwise.
 *
 * @param test The row's state.
 * @return The elapsed seconds, as state.
 */
@Composable
fun elapsedSecondsOf(test: ConnectionTestState): State<Long> = produceState(0L, test) {
    val running = test as? ConnectionTestState.Running ?: return@produceState
    while (true) {
        value = running.startedAt.elapsedNow().inWholeSeconds
        delay(ELAPSED_TICK_MS)
    }
}

/** How often a running test's elapsed seconds are read. */
private const val ELAPSED_TICK_MS = 1_000L

/** A running test shows its elapsed seconds from this many on. */
private const val ELAPSED_SHOWN_FROM_SECONDS = 3L
