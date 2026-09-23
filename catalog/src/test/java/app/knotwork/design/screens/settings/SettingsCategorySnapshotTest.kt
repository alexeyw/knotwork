package app.knotwork.design.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Roborazzi baseline for the settings category sub-screens. Memory exercises the
 * full state matrix (Basic / Advanced / validation-error / pending re-embed /
 * provider-mismatch banner); Generation, Tools, Pipelines, Background, Privacy
 * and About cover breadth, with a font-scale 200% Tools board.
 *
 * The destructive typed-confirm dialog is intentionally omitted (its
 * `OutlinedTextField` cursor-blink animation trips Roborazzi's idle guard); that
 * flow is asserted at the VM / instrumented level instead.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class SettingsCategorySnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun generation_basic_light() = snapshot("generation_basic", dark = false) {
        GenerationSettingsContent(state = SettingsPreview.generation())
    }

    @Test
    fun generation_advanced_light() = snapshot("generation_advanced", dark = false) {
        GenerationSettingsContent(state = SettingsPreview.generation(), advancedExpanded = true)
    }

    @Test
    fun models_default_light() = snapshot("models_default", dark = false) {
        ModelsSettingsContent(state = SettingsPreview.models())
    }

    @Test
    fun models_restart_required_light() = snapshot("models_restart", dark = false) {
        ModelsSettingsContent(state = SettingsPreview.modelsRestart())
    }

    @Test
    fun memory_basic_light() = snapshot("memory_basic", dark = false) {
        MemorySettingsContent(state = SettingsPreview.memory())
    }

    @Test
    fun memory_basic_dark() = snapshot("memory_basic", dark = true) {
        MemorySettingsContent(state = SettingsPreview.memory())
    }

    @Test
    fun memory_advanced_light() = snapshot("memory_advanced", dark = false) {
        MemorySettingsContent(state = SettingsPreview.memory(), advancedExpanded = true)
    }

    @Test
    fun memory_validation_error_light() = snapshot("memory_error", dark = false) {
        MemorySettingsContent(state = SettingsPreview.memoryError(), advancedExpanded = true)
    }

    @Test
    fun memory_pending_reembed_light() = snapshot("memory_pending", dark = false) {
        MemorySettingsContent(state = SettingsPreview.memoryPending(), advancedExpanded = true)
    }

    @Test
    fun memory_reembed_banner_light() = snapshot("memory_reembed_banner", dark = false) {
        MemorySettingsContent(state = SettingsPreview.memoryReembedBanner(), advancedExpanded = true)
    }

    @Test
    fun pipelines_advanced_light() = snapshot("pipelines_advanced", dark = false) {
        PipelinesSettingsContent(state = SettingsPreview.pipelines(), advancedExpanded = true)
    }

    @Test
    fun pipelines_advanced_dark() = snapshot("pipelines_advanced", dark = true) {
        PipelinesSettingsContent(state = SettingsPreview.pipelines(), advancedExpanded = true)
    }

    /**
     * The Basic tier on its own, which is where the run-limits entry row lives.
     * Previously the only Pipelines baseline had the Advanced disclosure open,
     * so the row a user actually lands on had no capture of its own.
     */
    @Test
    fun pipelines_basic_light() = snapshot("pipelines_basic", dark = false) {
        PipelinesSettingsContent(state = SettingsPreview.pipelines())
    }

    @Test
    fun pipelines_basic_dark() = snapshot("pipelines_basic", dark = true) {
        PipelinesSettingsContent(state = SettingsPreview.pipelines())
    }

    @Test
    fun tools_basic_light() = snapshot("tools_basic", dark = false) {
        ToolsSettingsContent(state = SettingsPreview.tools())
    }

    // The 2x shot below opens the same disclosure, but a settings capture only
    // holds the top viewport — at that scale the five ceilings are already past
    // the bottom edge. These two are what actually photograph them.
    @Test
    fun tools_advanced_light() = snapshot("tools_advanced", dark = false) {
        ToolsSettingsContent(state = SettingsPreview.tools(), advancedExpanded = true)
    }

    @Test
    fun tools_advanced_dark() = snapshot("tools_advanced", dark = true) {
        ToolsSettingsContent(state = SettingsPreview.tools(), advancedExpanded = true)
    }

    @Test
    fun tools_advanced_font_scale_2x_light() = snapshot("tools_advanced_font_scale_2x", dark = false, fontScale = 2f) {
        ToolsSettingsContent(state = SettingsPreview.tools(), advancedExpanded = true)
    }

    @Test
    fun background_advanced_light() = snapshot("background_advanced", dark = false) {
        BackgroundSettingsContent(state = SettingsPreview.background(), advancedExpanded = true)
    }

    // The three external-automation postures. Each is a different decision for
    // the reader — nothing can call in, something can call in but nothing will
    // run, something can call in and one pipeline will — so each gets a baseline
    // in both themes rather than only the working one.

    @Test
    fun background_external_off_light() = snapshot("background_external_off", dark = false) {
        BackgroundSettingsContent(state = SettingsPreview.background())
    }

    @Test
    fun background_external_off_dark() = snapshot("background_external_off", dark = true) {
        BackgroundSettingsContent(state = SettingsPreview.background())
    }

    @Test
    fun background_external_unbound_light() = snapshot("background_external_unbound", dark = false) {
        BackgroundSettingsContent(state = SettingsPreview.backgroundExternalUnbound())
    }

    @Test
    fun background_external_unbound_dark() = snapshot("background_external_unbound", dark = true) {
        BackgroundSettingsContent(state = SettingsPreview.backgroundExternalUnbound())
    }

    @Test
    fun background_external_bound_light() = snapshot("background_external_bound", dark = false) {
        BackgroundSettingsContent(state = SettingsPreview.backgroundExternalBound())
    }

    @Test
    fun background_external_bound_dark() = snapshot("background_external_bound", dark = true) {
        BackgroundSettingsContent(state = SettingsPreview.backgroundExternalBound())
    }

    @Test
    fun privacy_advanced_light() = snapshot("privacy_advanced", dark = false) {
        PrivacySettingsContent(state = SettingsPreview.privacy(), advancedExpanded = true)
    }

    @Test
    fun privacy_foss_hidden_light() = snapshot("privacy_foss_hidden", dark = false) {
        PrivacySettingsContent(state = SettingsPreview.privacyFossHidden(), advancedExpanded = true)
    }

    @Test
    fun about_advanced_light() = snapshot("about_advanced", dark = false) {
        AboutSettingsContent(state = SettingsPreview.about(), advancedExpanded = true)
    }

    @Test
    fun usage_populated_light() = snapshot("usage_populated", dark = false) {
        UsageTelemetryContent(state = SettingsPreview.usageTelemetry())
    }

    @Test
    fun usage_populated_dark() = snapshot("usage_populated", dark = true) {
        UsageTelemetryContent(state = SettingsPreview.usageTelemetry())
    }

    @Test
    fun usage_empty_light() = snapshot("usage_empty", dark = false) {
        UsageTelemetryContent(state = SettingsPreview.usageTelemetryEmpty())
    }

    // ─── Hints ───────────────────────────────────────────────────────────────
    // Closed testing found the settings screens unreadable — "Тут тумблеров" ·
    // "Я тут состарюсь" — and the explanations that did exist unread, because
    // they sat in the muted micro-type slot the app uses for machine state.
    // These baselines cover both halves of the answer: what the screen looks
    // like at rest (shorter than before, every prose subtitle gone) and what one
    // summoned explanation looks like.

    @Test
    fun memory_hints_collapsed_light() = snapshot("memory_hints_collapsed", dark = false) {
        MemorySettingsContent(state = SettingsPreview.memory(), advancedExpanded = true)
    }

    @Test
    fun memory_hints_collapsed_dark() = snapshot("memory_hints_collapsed", dark = true) {
        MemorySettingsContent(state = SettingsPreview.memory(), advancedExpanded = true)
    }

    @Test
    fun memory_hint_open_light() = snapshot("memory_hint_open", dark = false, openHint = "MEMORY_SEARCH_THRESHOLD") {
        MemorySettingsContent(state = SettingsPreview.memory(), advancedExpanded = true)
    }

    @Test
    fun memory_hint_open_dark() = snapshot("memory_hint_open", dark = true, openHint = "MEMORY_SEARCH_THRESHOLD") {
        MemorySettingsContent(state = SettingsPreview.memory(), advancedExpanded = true)
    }

    /** A toggle row's hint: the panel sits under the whole row, switch included. */
    @Test
    fun memory_hint_open_toggle_light() =
        snapshot("memory_hint_open_toggle", dark = false, openHint = "AUTO_EXTRACT_ENABLED") {
            MemorySettingsContent(state = SettingsPreview.memory())
        }

    /**
     * The worst case the 140-character ceiling was measured against: the longest
     * permitted explanation at the largest font scale. If a hint ever pushes the
     * row it explains off the top of the screen, it shows here first.
     */
    @Test
    fun memory_hint_open_font_scale_2x_light() = snapshot(
        "memory_hint_open_font_scale_2x",
        dark = false,
        fontScale = 2f,
        openHint = "AUTO_EXTRACT_ENABLED",
    ) {
        // The hint opened here is the FIRST row, not the longest one buried
        // under the Advanced disclosure: at 200 % a row that far down sits
        // below the capture frame, so the baseline would show the glyph
        // wrapping and prove nothing about the panel it opens. The panel has
        // to be inside the frame for this snapshot to be evidence.
        MemorySettingsContent(state = SettingsPreview.memory())
    }

    private fun snapshot(
        name: String,
        dark: Boolean,
        fontScale: Float = 1f,
        openHint: String? = null,
        content: @Composable () -> Unit,
    ) {
        composeTestRule.setContent {
            val baseDensity = LocalDensity.current
            // The hint controller has to be in scope or no row renders its help
            // glyph, and every one of these baselines would certify a screen
            // that looks complete while the control this task added is in none
            // of them. The fixture opens one hint when asked, so the expanded
            // panel is captured too, not just the collapsed affordance.
            val hints = remember { SettingsHintController { anchor -> SNAPSHOT_HINTS[anchor] } }
            LaunchedEffect(openHint) { if (openHint != null) hints.toggle(openHint) }
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(
                    LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true, fontScale = fontScale),
                    LocalDensity provides Density(density = baseDensity.density, fontScale = fontScale),
                    LocalSettingsHints provides hints,
                ) { content() }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/settings_${name}_$themeTag.png",
        )
    }

    private companion object {
        /**
         * The row whose fixture hint sits at the 140-character ceiling.
         *
         * It is the FIRST Basic row on purpose: at 200 % font scale anything
         * further down is below the capture frame, and a baseline that cannot
         * show the panel proves nothing about the limit it exists to justify.
         */
        const val LONG_HINT_ANCHOR = "AUTO_EXTRACT_ENABLED"

        /**
         * Help text for the snapshot fixtures.
         *
         * **Hand-maintained, and nothing enforces it.** The catalog module
         * cannot read the app's `SettingsHelpCatalog`, so the key set is kept in
         * step by review — which is how `CHAT_HISTORY_COMPRESSION_ENABLED` came
         * to be missing while sitting between two rows that had their glyph, in
         * the most-viewed frame of the screen. When adding a hint to a row that
         * appears on a captured screen, add its anchor here too.
         *
         * Anchors here must match the rows production actually gives a glyph:
         * a fixture entry for a `BEHAVIOUR_NOT_SHIPPED` row would certify an
         * affordance the app omits, and a missing one leaves a shipped glyph in
         * no baseline. Both directions were wrong here at once.
         *
         * Deliberately **not** copies of the shipped strings. The catalog cannot
         * read the app module's resources, and a hand-kept "verbatim" copy would
         * be a fifth wording of the same sentences with nothing enforcing the
         * match — the exact failure this task exists to remove. So these are
         * fixture sentences, written to the same 140-character ceiling and the
         * same shape, chosen to exercise the layout rather than to restate the
         * product copy. What the user actually reads is asserted in
         * `SettingsHelpCatalogTest`, against the real resources.
         */
        val SNAPSHOT_HINTS: Map<String, SettingsHint> = mapOf(
            "MEMORY_SEARCH_THRESHOLD" to SettingsHint(
                "Higher recalls only close matches, so less is pulled in; " +
                    "lower recalls more, including memories that miss the point.",
            ),
            // Basic, and directly below the two rows above it — so its absence
            // showed as a row without a glyph next to two that had one, in the
            // most-viewed frame of the screen.
            "CHAT_HISTORY_COMPRESSION_ENABLED" to SettingsHint(
                "Long threads are summarised past the live window, so the agent keeps the gist.",
            ),
            "ACTIVE_EMBEDDING_PROVIDER_ID" to SettingsHint(
                "The model that turns text into the numbers memory search compares.",
            ),
            "VERBOSE_MEMORY_LOGGING_ENABLED" to SettingsHint(
                "Adds each recalled memory, text included, to the console.",
            ),
            "MEMORY_ACTIONS" to SettingsHint(
                "Re-embed rebuilds every vector for the current model.",
            ),
            "MAX_CONTEXT_LENGTH" to SettingsHint(
                "The working window of the on-device model. Larger holds more at once and runs slower.",
            ),
            "AUDIO_MAX_DURATION_SEC" to SettingsHint(
                "Recording stops on its own at this point and sends what it has.",
            ),
            "MEMORY_COMPACTION_ENABLED" to SettingsHint(
                "Old memories get merged into shorter summaries once there are many. " +
                    "Frees room; loses the exact wording.",
            ),
            // 140 characters exactly — the ceiling itself, so the font-scale
            // baseline shows the worst case the limit was measured against
            // rather than a comfortable sentence well inside it.
            LONG_HINT_ANCHOR to SettingsHint(
                "Higher recalls only the very closest matches, so less is pulled in; lower " +
                    "recalls a great deal more, including memories that miss the point.",
            ),
            "MEMORY_SEARCH_TOP_K" to SettingsHint(
                "How many memories are pulled into a reply at most. " +
                    "More context, slower start, and more room for noise.",
            ),
            "MAX_MEMORY_CHUNKS" to SettingsHint(
                "The ceiling on stored pieces. At the ceiling the oldest are dropped, compaction or not.",
            ),
            "MEMORY_RECENCY_HALF_LIFE_DAYS" to SettingsHint(
                "How fast old memories lose to new ones when both match. " +
                    "Short favours this week; long treats everything as current.",
            ),
            // Generation's own rows, so the baselines for that screen contain
            // the glyph the bespoke textarea header now carries. Without them
            // the screen looks covered while the control added here is in none
            // of its captures — the third time this phase.
            "SYSTEM_PROMPT_PREFIX" to SettingsHint(
                "Put in front of every request, in every chat and every pipeline. " +
                    "The agent treats it as standing orders.",
            ),
            // The three sliders whose hint production ships but whose fixture
            // anchor was missing, so their glyph was in no baseline at all.
            "CHAT_HISTORY_COMPRESSION_THRESHOLD_TOKENS" to SettingsHint(
                "How large a thread grows before everything past the live window is summarised.",
            ),
            "CHAT_HISTORY_LIVE_WINDOW_SIZE" to SettingsHint(
                "How many recent messages stay word-for-word after a thread is summarised.",
            ),
            "MEMORY_SUMMARY_DEFAULT_LIMIT" to SettingsHint(
                "How many recent memories the memory-summary prompt variable lists.",
            ),
            "MEMORY_COMPACTION_AGE_DAYS" to SettingsHint(
                "How old a memory must be before compaction may merge it. " +
                    "Fresher facts keep their exact wording until they pass it.",
            ),
        )
    }
}
