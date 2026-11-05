package app.knotwork.android.presentation.ui.settings

import android.content.Context
import app.knotwork.android.R
import app.knotwork.android.domain.models.TestProbeFailure
import app.knotwork.android.domain.models.TestProbeResult

/**
 * Why the last backend probe failed, in the interface language: the engine's own text when it
 * gave one, the app's sentence for a failure it words itself.
 *
 * @param context Resource resolution.
 * @param probe A failed probe.
 * @return The reason.
 */
internal fun probeFailureText(context: Context, probe: TestProbeResult): String = probe.errorMessage
    ?: context.getString(
        when (probe.failure) {
            TestProbeFailure.NO_ACTIVE_MODEL -> R.string.settings_test_backend_failure_no_model
            TestProbeFailure.MODEL_FILE_MISSING -> R.string.settings_test_backend_failure_file_missing
            TestProbeFailure.LOAD_FAILED -> R.string.settings_test_backend_failure_load
            TestProbeFailure.UNKNOWN, null -> R.string.settings_test_backend_failure_unknown
        },
    )
