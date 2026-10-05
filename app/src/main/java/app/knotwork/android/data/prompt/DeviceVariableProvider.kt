package app.knotwork.android.data.prompt

import android.os.Build
import app.knotwork.android.domain.prompt.PromptVariableProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the value for the `$DEVICE` placeholder.
 *
 * Resolves to a short "manufacturer + model + Android version" descriptor
 * (e.g. `Samsung Galaxy S25 Ultra · Android 16`). The string is meant
 * for prompt grounding — letting the model tailor instructions to the
 * device class without leaking serial numbers or build fingerprints.
 *
 * The [androidVersionProvider] indirection keeps the provider unit
 * testable on the JVM where [Build.VERSION.RELEASE] is the empty string.
 */
@Singleton
class DeviceVariableProvider internal constructor(
    private val manufacturerProvider: () -> String,
    private val modelProvider: () -> String,
    private val androidVersionProvider: () -> String,
) : PromptVariableProvider {

    @Inject
    constructor() : this(
        manufacturerProvider = { Build.MANUFACTURER.orEmpty() },
        modelProvider = { Build.MODEL.orEmpty() },
        androidVersionProvider = { Build.VERSION.RELEASE.orEmpty() },
    )

    override fun key(): String = KEY

    override suspend fun resolve(): String = describe(manufacturerProvider(), modelProvider(), androidVersionProvider())

    /** The descriptor format, shared with the header of a pipeline run. */
    companion object {
        private const val KEY = "DEVICE"
        private const val SEPARATOR = " · "
        private const val UNKNOWN = "unknown device"

        /**
         * Formats the device descriptor: `Manufacturer model · Android version`,
         * dropping a blank part, `unknown device` when every part is blank.
         *
         * @param manufacturer The device manufacturer.
         * @param model The device model.
         * @param androidVersion The Android release.
         * @return The descriptor.
         */
        fun describe(manufacturer: String, model: String, androidVersion: String): String {
            val maker = manufacturer.trim().replaceFirstChar { it.uppercaseChar() }
            val deviceLabel = listOf(maker, model.trim()).filter { it.isNotBlank() }.joinToString(" ")
            val version = androidVersion.trim().takeIf { it.isNotBlank() }?.let { "Android $it" }
            return listOfNotNull(deviceLabel.takeIf { it.isNotBlank() }, version).joinToString(SEPARATOR)
                .ifBlank { UNKNOWN }
        }
    }
}
