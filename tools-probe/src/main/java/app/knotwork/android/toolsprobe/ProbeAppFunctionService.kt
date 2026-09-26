package app.knotwork.android.toolsprobe

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint

/**
 * Single-function AppFunction surface shipped by the `:tools-probe` debug app for the
 * end-to-end test harness.
 *
 * The class declares one `@AppFunction`: [echo], which returns its `message` argument
 * verbatim. Its sole purpose is to give the agent's caller-side path (`LocalAppFunctionManager`
 * → `ToolRepository.executeTool` → system `AppFunctionManager`) a deterministic remote
 * target installed on the device under test.
 *
 * It is an AppFunctions entry point, the same shape the agent publishes through: the
 * compiler generates the concrete `EchoAppFunctionService` (registered in this module's
 * manifest) and writes `assets/tools_probe_app_functions.xml`, the inventory the
 * platform indexes. The probe needs no dependency injection, so there is no Hilt here —
 * the function body has nothing to reach. Android 16+ only, like the agent's.
 */
@RequiresApi(36)
@AppFunctionServiceEntryPoint(
    serviceName = "EchoAppFunctionService",
    appFunctionXmlFileName = "tools_probe_app_functions",
)
abstract class ProbeAppFunctionService : AppFunctionService() {

    /**
     * Returns the message it is given, unchanged.
     *
     * Echoing lets the caller-side e2e test verify the full round-trip path (JSON
     * arguments → `AppFunctionData` → IPC → response → decoded JSON) without depending on
     * network access or any non-deterministic side effect of a more realistic
     * AppFunction; its assertion is `expected == actual` rather than a parser over an
     * opaque payload.
     *
     * @param message Free-form text to reflect back. Empty text is accepted.
     * @return Exactly the text passed as the message.
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun echo(message: String): String = message
}
