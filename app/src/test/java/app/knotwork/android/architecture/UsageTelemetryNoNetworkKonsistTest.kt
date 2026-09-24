package app.knotwork.android.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * Konsist guard enforcing the core privacy promise of the local usage-telemetry
 * feature: **nothing on the telemetry path may make a network call.**
 *
 * The on-device statistics are recorded, aggregated, exported and reset entirely
 * against the local (SQLCipher-encrypted) database; the voluntary export goes
 * only to a user-chosen file or the system share sheet. To make that auditable —
 * and to stop a future edit quietly wiring an upload — no file in the
 * usage-telemetry surface may import a network client (OkHttp, Retrofit, Koog,
 * Ktor, or raw `java.net`).
 *
 * The check is scoped to the telemetry files by name / package: the
 * `UsageTelemetry*` / `UsageCounter*` / `UsageActiveDay*` types, the
 * `BuildUsageTelemetryExportUseCase`, `TriggerTelemetry`, and the
 * `settings.usage` screen package.
 */
class UsageTelemetryNoNetworkKonsistTest {

    @Test
    fun `usage-telemetry files import no network client`() {
        ArchitectureScope.production
            .files
            .filter { file ->
                val name = file.name
                name.contains("Usage") || name.contains("Telemetry") || file.path.contains("/settings/usage/")
            }
            .assertFalse(additionalMessage = NETWORK_IMPORT_FAILURE) { file ->
                file.imports.any { import ->
                    NetworkClientImports.PREFIXES.any { prefix -> import.name.startsWith(prefix) }
                }
            }
    }

    private companion object {
        const val NETWORK_IMPORT_FAILURE =
            "the local usage-telemetry path must never touch the network: no usage-telemetry file may import a " +
                "network client (NetworkClientImports). The statistics stay on-device; the " +
                "voluntary export goes only to a user-chosen file or the share sheet."
    }
}
