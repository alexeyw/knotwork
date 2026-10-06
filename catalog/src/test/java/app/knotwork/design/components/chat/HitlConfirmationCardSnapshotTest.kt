package app.knotwork.design.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.components.chips.Risk
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baselines for the request block of [HitlConfirmationCard] — what the
 * run was asked to do, above the call — in every state the design draws: each
 * source, a long request collapsed and expanded, the destructive card with its
 * typed confirmation, a trigger that can no longer be named, an image with and
 * without text, right-to-left and mixed scripts, and the card without a request.
 * The card is drawn alone in a 360 dp column; the 200 % frames pin the collapse
 * rule (two lines on a destructive card from font scale 1.5).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h1200dp-xhdpi")
class HitlConfirmationCardSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    // A1 — each source, short request, sensitive.
    @Test fun chat_light() = card("chat", asked(HitlRequestSource.Chat, DENTIST))

    @Test fun chat_dark() = card("chat", asked(HitlRequestSource.Chat, DENTIST), dark = true)

    @Test fun chat_font_scale_2x() = card("chat_font_scale_2x", asked(HitlRequestSource.Chat, DENTIST), scale = 2f)

    @Test fun shared_light() = card("shared", asked(HitlRequestSource.Shared, OFFSITE))

    @Test fun shared_dark() = card("shared", asked(HitlRequestSource.Shared, OFFSITE), dark = true)

    @Test fun trigger_light() = card("trigger", asked(HitlRequestSource.Trigger("Morning briefing"), WEATHER))

    @Test fun trigger_dark() = card(
        "trigger",
        asked(HitlRequestSource.Trigger("Morning briefing"), WEATHER),
        dark = true,
    )

    @Test fun scheduled_light() = card("scheduled", asked(HitlRequestSource.ScheduledTask, PARCEL))

    @Test fun scheduled_dark() = card("scheduled", asked(HitlRequestSource.ScheduledTask, PARCEL), dark = true)

    @Test fun tile_light() = card("tile", asked(HitlRequestSource.QuickTile, STRETCH))

    @Test fun tile_dark() = card("tile", asked(HitlRequestSource.QuickTile, STRETCH), dark = true)

    @Test fun other_app_light() = card("other_app", asked(HitlRequestSource.OtherApp, BILL))

    @Test fun other_app_dark() = card("other_app", asked(HitlRequestSource.OtherApp, BILL), dark = true)

    // A2 — a long request, clamped by the domain.
    @Test fun long_collapsed_light() = card("long_collapsed", LONG)

    @Test fun long_collapsed_dark() = card("long_collapsed", LONG, dark = true)

    @Test fun long_expanded_light() = card("long_expanded", LONG, expand = true)

    @Test fun long_collapsed_font_scale_2x() = card("long_collapsed_font_scale_2x", LONG, scale = 2f)

    // A3 — destructive, long request, typed confirmation.
    @Test fun destructive_light() = card("destructive", LONG, risk = Risk.Destructive)

    @Test fun destructive_typed_light() = card("destructive_typed", LONG, risk = Risk.Destructive, typed = "yes")

    @Test fun destructive_dark() = card("destructive", LONG, risk = Risk.Destructive, dark = true)

    @Test fun destructive_font_scale_2x() = card("destructive_font_scale_2x", LONG, risk = Risk.Destructive, scale = 2f)

    // A4 — read-only under "ask for every call".
    @Test fun readonly_light() = card("readonly", asked(HitlRequestSource.Chat, DENTIST), risk = Risk.Readonly)

    @Test fun readonly_dark() = card(
        "readonly",
        asked(HitlRequestSource.Chat, DENTIST),
        risk = Risk.Readonly,
        dark = true,
    )

    // A5 — a trigger deleted since it fired.
    @Test fun trigger_deleted_light() = card("trigger_deleted", asked(HitlRequestSource.Trigger(null), TIMESHEET))

    @Test fun trigger_deleted_dark() = card(
        "trigger_deleted",
        asked(HitlRequestSource.Trigger(null), TIMESHEET),
        dark = true,
    )

    // A6 — an image, without and with text.
    @Test fun image_only_light() = card("image_only", IMAGE_ONLY)

    @Test fun image_only_dark() = card("image_only", IMAGE_ONLY, dark = true)

    @Test fun image_with_text_light() = card("image_with_text", IMAGE_WITH_TEXT)

    @Test fun image_with_text_dark() = card("image_with_text", IMAGE_WITH_TEXT, dark = true)

    // A7 — right-to-left and mixed scripts.
    @Test fun arabic_light() = card("arabic", asked(HitlRequestSource.Chat, ARABIC))

    @Test fun arabic_dark() = card("arabic", asked(HitlRequestSource.Chat, ARABIC), dark = true)

    @Test fun arabic_font_scale_2x() = card("arabic_font_scale_2x", asked(HitlRequestSource.Chat, ARABIC), scale = 2f)

    @Test fun mixed_scripts_light() = card("mixed_scripts", asked(HitlRequestSource.Chat, MIXED))

    @Test fun mixed_scripts_dark() = card("mixed_scripts", asked(HitlRequestSource.Chat, MIXED), dark = true)

    // A8 — no request: the card as it was.
    @Test fun no_request_light() = card("no_request", request = null)

    @Test fun no_request_destructive_light() = card("no_request_destructive", request = null, risk = Risk.Destructive)

    private fun asked(source: HitlRequestSource, text: String) =
        HitlRequestContext(source = source, request = text, shortened = false, hadImage = false)

    private fun card(
        name: String,
        request: HitlRequestContext?,
        risk: Risk = Risk.Sensitive,
        dark: Boolean = false,
        scale: Float = 1f,
        typed: String = "",
        expand: Boolean = false,
    ) {
        composeTestRule.setContent {
            val base = LocalDensity.current
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = scale),
                    LocalDensity provides Density(density = base.density, fontScale = scale),
                ) { Frame { Card(request = request, risk = risk, typed = typed) } }
            }
        }
        if (expand) {
            composeTestRule.onNode(hasClickAction() and hasContentDescription("You asked", substring = true))
                .performClick()
        }
        val theme = if (dark) "dark" else "light"
        val file = if (name.endsWith("font_scale_2x")) "hitl_request_$name" else "hitl_request_${name}_$theme"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/$file.png",
        )
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background).padding(12.dp)) { content() }
    }

    @Composable
    private fun Card(request: HitlRequestContext?, risk: Risk, typed: String) {
        HitlConfirmationCard(
            model = HitlConfirmationModel(
                risk = risk,
                toolName = if (risk == Risk.Destructive) "delete_file" else "schedule_task",
                summary = "",
                arguments = if (risk == Risk.Destructive) {
                    mapOf("path" to "\"notes/2025-archive.md\"")
                } else {
                    mapOf("when" to "\"2026-10-06T16:00\"", "prompt" to "\"Call the dentist\"")
                },
                timestamp = "14:02",
                request = request,
            ),
            pendingTypedConfirm = typed,
            onTypedConfirmChange = {},
            allowOnceEnabled = risk != Risk.Destructive || typed == "yes",
            onAllowOnce = {},
            onAllowAlways = null,
            onReject = {},
        )
    }

    private companion object {
        const val DENTIST = "Remind me to call the dentist tomorrow at 4"
        const val OFFSITE = "Team offsite moved to Thursday 14:00, room 4B. Please update your calendars."
        const val WEATHER = "Check today's weather and remind me to take an umbrella if rain is forecast"
        const val PARCEL = "Check whether the parcel tracking page says delivered. If not, remind me tomorrow at 9:00"
        const val STRETCH = "Remind me to stretch every hour until 18:00"
        const val BILL = "Create reminder: pay the electricity bill on 10 October"
        const val TIMESHEET = "Every Friday afternoon, remind me to submit the timesheet"
        const val ARABIC = "ذكّرني بالاتصال بطبيب الأسنان غدًا في الساعة الرابعة"
        const val MIXED = "Send the slides to דנה כהן before Friday and copy me"

        /** A request the domain clamped to the card's 400 characters, on a word. */
        val LONG = HitlRequestContext(
            source = HitlRequestSource.Chat,
            request = "Go through the notes from last year's planning sessions and delete the archive file " +
                "we no longer need. Keep anything that mentions the budget review, because finance still asks " +
                "about it, and before you delete anything make sure the summary I wrote in March is saved " +
                "somewhere else. If the archive turns out to hold the only copy of the vendor contacts, stop and " +
                "ask me first, since…",
            shortened = true,
            hadImage = false,
        )

        val IMAGE_ONLY = HitlRequestContext(HitlRequestSource.Chat, request = null, shortened = false, hadImage = true)

        val IMAGE_WITH_TEXT = HitlRequestContext(
            source = HitlRequestSource.Chat,
            request = "Add this to my reminders",
            shortened = false,
            hadImage = true,
        )
    }
}
