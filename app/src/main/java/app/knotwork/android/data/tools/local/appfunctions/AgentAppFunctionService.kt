package app.knotwork.android.data.tools.local.appfunctions

import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunction
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * The AppFunctions Knotwork publishes to other apps — today one, `search`.
 *
 * Every `@AppFunction` of the app is declared here and nowhere else: the AppFunctions
 * compiler reads this entry point, generates the concrete service named in
 * [AppFunctionServiceEntryPoint.serviceName] (`KnotworkAppFunctionService`, same package)
 * with the dispatch code, and writes the function inventory to
 * `assets/knotwork_app_functions.xml`. The app registers that generated service in its
 * manifest; `AppFunctionServiceManifestGuardTest` keeps the two in step, because a
 * mismatch compiles and runs and only shows on a device, as a function nobody can find.
 *
 * **Android 16 and later only.** The service extends the platform `AppFunctionService`
 * added in API 36. Below it, AppFunctions exist only through a manufacturer's extension
 * library, which would need a second entry point declaring each function again; the app
 * does not publish there.
 *
 * A function's wire id is `<this class's name>#<method name>` — see the generated
 * `AgentAppFunctionServiceIds`. Each method only delegates: the logic lives in injectable
 * classes ([SearchAppFunction]) that share caches and limits with the in-agent path and
 * are tested without a service.
 */
@RequiresApi(36)
@AndroidEntryPoint
@AppFunctionServiceEntryPoint(
    serviceName = "KnotworkAppFunctionService",
    appFunctionXmlFileName = "knotwork_app_functions",
)
abstract class AgentAppFunctionService : AppFunctionService() {

    /** Body of [search]; injected by Hilt when the platform starts the service. */
    @Inject
    internal lateinit var searchAppFunction: SearchAppFunction

    /**
     * Looks up a topic on Wikipedia and returns a short plain-text extract of the best
     * matching article. Read-only: it changes nothing on the device. It is refused while
     * the user has blocked network access for local models.
     *
     * @param query What to look up, in the language given as lang. Must not be blank.
     * @param lang Wikipedia language code matching the language of the query, such as
     *   en, de or simple. Leave empty for English.
     * @return A plain-text extract of the article that best matches the query.
     */
    // The KDoc above is published: `isDescribedByKDoc` turns it into the description the
    // calling agent reads, so it is written for that agent and names parameters in plain
    // words — KDoc links would reach it as literal brackets.
    @AppFunction(isDescribedByKDoc = true)
    suspend fun search(query: String, lang: String): String = searchAppFunction.search(query, lang)
}
