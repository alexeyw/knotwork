package app.knotwork.design.screens.about

/**
 * One acknowledgment entry surfaced on the About surface.
 *
 * @property name dependency display name (e.g. `"Jetpack Compose"`).
 * @property license SPDX-style license name (e.g. `"Apache 2.0"`).
 */
data class AcknowledgmentEntry(val name: String, val license: String)

/**
 * One document the About screen offers to open.
 *
 * @property id Stable registry id handed back to the caller on click. The
 *   catalog never resolves it to a URL: which revision of the documentation a
 *   build is entitled to show is a property of the build, which the design
 *   system cannot see.
 * @property title Display name of the document.
 * @property summary One line on what the document answers, so the list is a
 *   router rather than five identical buttons.
 */
/**
 * Top-level immutable input to `AboutContent`.
 *
 * @property appName brand text rendered under the logo.
 * @property tagline subtitle rendered below the brand.
 * @property versionLine e.g. `"Version 0.1.0"`.
 * @property buildLine e.g. `"Build 1 · debug"`.
 * @property commitSha shortened git SHA.
 * @property licenseName license display name (e.g. `"Apache 2.0"`).
 * @property acknowledgments list of library credits.
 * @property privacyBody paragraph summarising the privacy stance.
 * @property documentationSummary How many documents there are and how many
 *   read offline — the same line the Help row shows, so the two agree by
 *   construction. Empty
 *   renders no section at all.
 */
data class AboutViewState(
    val appName: String,
    val tagline: String,
    val versionLine: String,
    val buildLine: String,
    val commitSha: String,
    val licenseName: String,
    val acknowledgments: List<AcknowledgmentEntry>,
    val privacyBody: String,
    val documentationSummary: String = "",
)

/** One-shot callbacks consumed by `AboutContent`. */
class AboutCallbacks(
    val onBack: () -> Unit = {},
    val onOpenLicense: () -> Unit = {},
    val onOpenPrivacyPolicy: () -> Unit = {},
    val onOpenDocumentation: () -> Unit = {},
)

/** Convenience factory returning a no-op callback bundle. */
fun noopAboutCallbacks(): AboutCallbacks = AboutCallbacks()

/** Localised string bundle threaded into `AboutContent`. */
@Suppress("LongParameterList")
data class AboutStrings(
    val title: String = "About",
    val backCd: String = "Back",
    val sectionVersion: String = "VERSION",
    val sectionLicense: String = "LICENSE",
    val sectionAcknowledgments: String = "ACKNOWLEDGMENTS",
    val sectionPrivacy: String = "PRIVACY",
    val licenseCta: String = "Open license text",
    val privacyCta: String = "Read privacy policy",
    val sectionDocumentation: String = "DOCUMENTATION",
    val documentationBody: String = "Troubleshooting, FAQ and the guides.",
    val documentationCta: String = "Help",
)
