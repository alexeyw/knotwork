package app.knotwork.android.presentation.ui.chat.home

import app.knotwork.android.domain.constants.RepositoryLinks
import java.net.URLEncoder

/**
 * Builds the URL that opens the public issue tracker with a report prefilled.
 *
 * The body travels in the query string, and a query string is not unbounded:
 * an over-long URL is silently refused or clipped by the browser, which would
 * hand the maintainer a half-report. So the body is truncated **here**, to a
 * length measured after percent-encoding, and the truncation is stated in the
 * text — the same rule the report body itself follows.
 *
 * @param subject Issue title.
 * @param body Rendered report body.
 * @return Fully-encoded `issues/new` URL.
 */
internal fun contentReportIssueUrl(subject: String, body: String): String {
    val encodedSubject = encode(subject)
    var candidate = body
    var encodedBody = encode(candidate)
    val budget = MAX_ISSUE_URL_CHARS - ISSUE_TRACKER_NEW_URL.length - encodedSubject.length - QUERY_OVERHEAD_CHARS
    if (encodedBody.length > budget) {
        // Percent-encoding expands by a factor that depends on the text (1x for
        // plain ASCII, up to 9x for one multi-byte character), so the fitting
        // length cannot be computed directly. Estimate it from the ratio the
        // full body just produced, then shave in small steps — a coarser search
        // (halving, say) fits just as well but throws away far more of the
        // report than the limit actually requires, and what it throws away is
        // the part the maintainer needs.
        var keep = (body.length.toLong() * budget / encodedBody.length).toInt()
        while (keep > 0) {
            candidate = body.take(keep) + ISSUE_TRUNCATION_MARKER
            encodedBody = encode(candidate)
            if (encodedBody.length <= budget) break
            keep -= (keep / SHAVE_DIVISOR).coerceAtLeast(1)
        }
    }
    return "$ISSUE_TRACKER_NEW_URL?title=$encodedSubject&body=$encodedBody"
}

/**
 * Percent-encodes a query-parameter value.
 *
 * @param value Raw text.
 * @return The value encoded for use inside a query string.
 */
private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

/**
 * `issues/new` endpoint of the public repository.
 *
 * Resolved through [RepositoryLinks] rather than spelled out again: the host
 * used to be written three times, and three copies survive a repository rename
 * by staying wrong in three places.
 */
private val ISSUE_TRACKER_NEW_URL = RepositoryLinks.ISSUES_NEW_URL

/**
 * Upper bound on the generated URL. Browsers differ on where they give up;
 * 8000 characters is below every limit in circulation and far above a report
 * anyone will read.
 */
private const val MAX_ISSUE_URL_CHARS = 8000

/** Room reserved for `?title=` and `&body=` around the encoded values. */
private const val QUERY_OVERHEAD_CHARS = 16

/** Each shave step removes a twentieth of the remaining text (5%). */
private const val SHAVE_DIVISOR = 20

/** Appended to a report body that had to be shortened to fit the URL. */
private const val ISSUE_TRUNCATION_MARKER =
    "\n\n_[report truncated to fit the link — copy the full report instead]_"
