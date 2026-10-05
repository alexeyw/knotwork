package app.knotwork.android.domain.models

/**
 * What a run is executed with, fixed when the root run starts and kept with its
 * record: the seed every on-device call's seed is derived from, the sampler, and
 * the versions and device it ran on.
 *
 * The header is chosen once per run — a run resumed after a pause continues with
 * its own header, not with whatever the settings say by then — so every model
 * call of the run, before and after a pause, uses the same sampler. Which model
 * file, backend and context window a call used is recorded with the call itself
 * ([RunTraceRecord.LocalModelCall]), because a node may pick its own model.
 *
 * @property seed The run seed. Each on-device call's seed is derived from it, the
 *   call's node and its visit, so different nodes never share one by design.
 * @property sampler The sampler every on-device call of the run uses (a
 *   structured-output repair excepted, which has its own fixed sampling).
 * @property appVersion The app's version name and code, e.g. `0.11.1 (16)`.
 * @property runtimeVersion The on-device inference runtime and its version, e.g.
 *   `LiteRT-LM 0.17.1`.
 * @property device The device descriptor, e.g. `Samsung SM-S938B · Android 16`.
 *   A repeat is promised to match only on the same device.
 */
data class RunHeader(
    val seed: Int,
    val sampler: RunSampler,
    val appVersion: String,
    val runtimeVersion: String,
    val device: String,
)
