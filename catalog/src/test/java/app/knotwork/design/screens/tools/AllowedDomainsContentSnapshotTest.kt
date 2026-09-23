package app.knotwork.design.screens.tools

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.knotwork.design.KnotworkRoborazziOptions
import app.knotwork.design.a11y.FixedKnotworkA11y
import app.knotwork.design.a11y.LocalKnotworkA11y
import app.knotwork.design.assertNoAccentText
import app.knotwork.design.theme.KnotworkTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h760dp-xhdpi")
class AllowedDomainsContentSnapshotTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun allowed_domains_empty_light() = snapshot(name = "empty", dark = false) {
        AllowedDomainsContent(state = AllowedDomainsPreview.empty())
    }

    @Test
    fun allowed_domains_empty_dark() = snapshot(name = "empty", dark = true) {
        AllowedDomainsContent(state = AllowedDomainsPreview.empty())
    }

    @Test
    fun allowed_domains_populated_light() = snapshot(name = "populated", dark = false) {
        AllowedDomainsContent(state = AllowedDomainsPreview.populated())
    }

    @Test
    fun allowed_domains_populated_dark() = snapshot(name = "populated", dark = true) {
        AllowedDomainsContent(state = AllowedDomainsPreview.populated())
    }

    @Test
    fun allowed_domains_add_valid_light() = snapshot(name = "add_valid", dark = false) {
        AllowedDomainsContent(state = AllowedDomainsPreview.addValid())
    }

    @Test
    fun allowed_domains_add_invalid_light() = snapshot(name = "add_invalid", dark = false) {
        AllowedDomainsContent(state = AllowedDomainsPreview.addInvalid())
    }

    @Test
    fun allowed_domains_add_duplicate_light() = snapshot(name = "add_duplicate", dark = false) {
        AllowedDomainsContent(state = AllowedDomainsPreview.addDuplicate())
    }

    private fun snapshot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        composeTestRule.setContent {
            KnotworkTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalKnotworkA11y provides FixedKnotworkA11y(reducedMotion = true)) {
                    content()
                }
            }
        }
        val themeTag = if (dark) "dark" else "light"
        composeTestRule.assertNoAccentText().onRoot().captureRoboImage(
            roborazziOptions = KnotworkRoborazziOptions,
            filePath = "src/test/snapshots/allowed_domains_${name}_$themeTag.png",
        )
    }
}

internal object AllowedDomainsPreview {

    private fun hosts(): List<String> = listOf(
        "api.openai.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "export.arxiv.org",
        "static.internal-tools.corp.example.com",
    )

    fun empty(): AllowedDomainsViewState = AllowedDomainsViewState()

    fun populated(): AllowedDomainsViewState = AllowedDomainsViewState(hosts = hosts())

    fun addValid(): AllowedDomainsViewState = AllowedDomainsViewState(
        hosts = hosts(),
        addInput = "HTTPS://Api.GitHub.com/v3/",
        addState = AddHostState.NormalizedPreview(normalized = "api.github.com"),
    )

    fun addInvalid(): AllowedDomainsViewState = AllowedDomainsViewState(
        hosts = hosts(),
        addInput = "https://",
        addState = AddHostState.Invalid,
    )

    fun addDuplicate(): AllowedDomainsViewState = AllowedDomainsViewState(
        hosts = hosts(),
        addInput = "API.OpenAI.com",
        addState = AddHostState.Duplicate(existing = "api.openai.com"),
    )
}
