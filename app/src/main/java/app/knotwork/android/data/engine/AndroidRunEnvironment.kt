package app.knotwork.android.data.engine

import android.os.Build
import app.knotwork.android.BuildConfig
import app.knotwork.android.data.prompt.DeviceVariableProvider
import app.knotwork.android.domain.services.RunEnvironment
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RunEnvironment] read from the build and the device.
 *
 * The runtime version comes from the version catalog through `BuildConfig`:
 * LiteRT-LM reports no version at runtime, and the APK runs exactly the version
 * it was built against. The device descriptor has the `$DEVICE` prompt
 * variable's format, so a run's header and a prompt name a device the same way.
 */
@Singleton
class AndroidRunEnvironment @Inject constructor() : RunEnvironment {

    override val appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    override val runtimeVersion: String = "LiteRT-LM ${BuildConfig.LITERT_LM_VERSION}"

    override val device: String = DeviceVariableProvider.describe(
        manufacturer = Build.MANUFACTURER.orEmpty(),
        model = Build.MODEL.orEmpty(),
        androidVersion = Build.VERSION.RELEASE.orEmpty(),
    )
}
