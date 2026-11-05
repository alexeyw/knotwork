package app.knotwork.android.di

import app.knotwork.android.data.prompt.DateVariableProvider
import app.knotwork.android.data.prompt.DeviceVariableProvider
import app.knotwork.android.data.prompt.LangVariableProvider
import app.knotwork.android.data.prompt.LocationVariableProvider
import app.knotwork.android.data.prompt.MemorySummaryVariableProvider
import app.knotwork.android.data.prompt.ModelVariableProvider
import app.knotwork.android.data.prompt.TimeVariableProvider
import app.knotwork.android.data.prompt.ToolsVariableProvider
import app.knotwork.android.data.prompt.UserVariableProvider
import app.knotwork.android.domain.prompt.PromptVariableProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds

/**
 * Declares the multibinding container for [PromptVariableProvider] implementations
 * and registers the built-in providers shipped with the app.
 *
 * Each `@Binds @IntoSet` method contributes one concrete provider into the Hilt
 * `Set<PromptVariableProvider>`. Consumers — currently [app.knotwork.android.domain.engine.GraphExecutionEngine]
 * and the prompt-preview UI — inject this set and pass it to
 * [app.knotwork.android.domain.prompt.PromptTemplateEngine.render] verbatim.
 *
 * The [app.knotwork.android.domain.prompt.PromptTemplateEngine] itself is provided
 * by Hilt through its `@Inject` constructor, so no `@Provides` for the engine
 * is required here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PromptTemplateModule {

    /**
     * Declares the (now non-empty) Hilt multibinding into which every
     * [PromptVariableProvider] implementation is contributed via
     * `@Binds @IntoSet` below. Kept explicit so removing all `@Binds` entries
     * still leaves a usable empty set for consumers.
     */
    @Multibinds
    abstract fun providePromptVariableProviders(): Set<PromptVariableProvider>

    /**
     * Contributes [DateVariableProvider] — resolves `$DATE` to the current
     * device-local date in `dd MMMM yyyy` format.
     */
    @Binds
    @IntoSet
    abstract fun bindDateVariableProvider(impl: DateVariableProvider): PromptVariableProvider

    /**
     * Contributes [TimeVariableProvider] — resolves `$TIME` to the current
     * device-local time in `HH:mm` 24-hour format.
     */
    @Binds
    @IntoSet
    abstract fun bindTimeVariableProvider(impl: TimeVariableProvider): PromptVariableProvider

    /**
     * Contributes [ToolsVariableProvider] — resolves `$TOOLS` to a newline-
     * separated `name — description` listing of currently available tools.
     */
    @Binds
    @IntoSet
    abstract fun bindToolsVariableProvider(impl: ToolsVariableProvider): PromptVariableProvider

    /**
     * Contributes [ModelVariableProvider] — resolves `$MODEL` to the display
     * name of the currently active local model.
     */
    @Binds
    @IntoSet
    abstract fun bindModelVariableProvider(impl: ModelVariableProvider): PromptVariableProvider

    /**
     * Contributes [MemorySummaryVariableProvider] — resolves `$MEMORY_SUMMARY`
     * to a numbered list of the most recent long-term memory chunks.
     */
    @Binds
    @IntoSet
    abstract fun bindMemorySummaryVariableProvider(impl: MemorySummaryVariableProvider): PromptVariableProvider

    /**
     * Contributes [LangVariableProvider] — resolves `$LANG` to the BCP-47 tag
     * of the language the app is shown in (e.g. `en-US`).
     */
    @Binds
    @IntoSet
    abstract fun bindLangVariableProvider(impl: LangVariableProvider): PromptVariableProvider

    /**
     * Contributes [LocationVariableProvider] — resolves `$LOCATION` to the
     * device's coarse region (the country of the device's languages, e.g. `US`).
     */
    @Binds
    @IntoSet
    abstract fun bindLocationVariableProvider(impl: LocationVariableProvider): PromptVariableProvider

    /**
     * Contributes [UserVariableProvider] — resolves `$USER` to the
     * identity card's display name (currently the literal "Anonymous").
     */
    @Binds
    @IntoSet
    abstract fun bindUserVariableProvider(impl: UserVariableProvider): PromptVariableProvider

    /**
     * Contributes [DeviceVariableProvider] — resolves `$DEVICE` to a short
     * "manufacturer · model · Android version" descriptor.
     */
    @Binds
    @IntoSet
    abstract fun bindDeviceVariableProvider(impl: DeviceVariableProvider): PromptVariableProvider
}
