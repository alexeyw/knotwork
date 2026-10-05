package app.knotwork.android.domain.services

/**
 * The versions and device a run executes on, as they go into its header.
 *
 * Whether a repeat of a run can be expected to match depends on more than the
 * seed: the same model file on another runtime version, or on another device's
 * GPU driver, may produce other text. The header records these so a reader of a
 * run — or of its exported trace — can tell.
 */
interface RunEnvironment {

    /** The app's version name and code, e.g. `0.11.1 (16)`. */
    val appVersion: String

    /** The on-device inference runtime and its version, e.g. `LiteRT-LM 0.17.1`. */
    val runtimeVersion: String

    /** The device descriptor, e.g. `Samsung SM-S938B · Android 16`. */
    val device: String
}
