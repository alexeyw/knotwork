package app.knotwork.android.presentation.screenshots

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * Comparison options every screenshot of this module is captured with.
 *
 * The same tolerance as `:catalog`'s `KnotworkRoborazziOptions`, for the same reason:
 * baselines are recorded on macOS and verified on Linux in CI, and the two anti-alias
 * semi-transparent edges a hair apart (at most 0.0096 measured; 0.02 is twice that and
 * far below a visible change). Test sources are not shared between modules, so the value
 * is repeated here; `AppSnapshotOptionsGuardTest` fails a capture that does not pass it.
 */
internal val AppRoborazziOptions: RoborazziOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        imageComparator = SimpleImageComparator(maxDistance = 0.02f),
    ),
)
