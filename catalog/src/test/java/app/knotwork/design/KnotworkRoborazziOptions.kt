package app.knotwork.design

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * Largest per-pixel colour distance a screenshot may differ from its baseline by and
 * still match — Euclidean over the normalised RGBA channels, as Roborazzi's comparator
 * measures it.
 *
 * Roborazzi's default is 0.007, just under a difference of 2/255 in one channel. The
 * baselines are recorded on macOS and verified on Linux in CI, and the two platforms
 * anti-alias semi-transparent vector edges a hair apart: on the first CI run 706 of 707
 * snapshots matched byte for byte, and the one that did not (the settings hub's loading
 * tiles, drawn at half alpha) differed in 975 pixels, at most 0.0096 apart — four of
 * them over the default. 0.02 is twice that noise, and two orders of magnitude below a
 * change anyone can see: swapping a light baseline for its dark twin puts ~800,000
 * pixels up to 1.63 apart.
 */
internal const val SNAPSHOT_MAX_COLOR_DISTANCE: Float = 0.02f

/**
 * Comparison options every catalog screenshot is captured with, so a baseline recorded on
 * one platform verifies on the other.
 *
 * Only the colour tolerance changes: no pixel shift window and no share of differing
 * pixels is allowed, so a moved or re-coloured element still fails. Passed explicitly to
 * each `captureRoboImage` call — Roborazzi has no global setting for it — and
 * `SnapshotComparisonOptionsGuardTest` fails a call that forgets.
 */
internal val KnotworkRoborazziOptions: RoborazziOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(
        imageComparator = SimpleImageComparator(maxDistance = SNAPSHOT_MAX_COLOR_DISTANCE),
    ),
)
