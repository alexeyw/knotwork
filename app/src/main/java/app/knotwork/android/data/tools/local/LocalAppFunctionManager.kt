package app.knotwork.android.data.tools.local

import android.content.Context
import androidx.appfunctions.AppFunctionException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import app.knotwork.android.domain.models.AgentTool
import app.knotwork.android.domain.models.ToolRisk
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manager class responsible for orchestrating AppFunctions execution.
 * It provides a unified interface for the AI agent to discover and trigger
 * application functions securely through the Android 16 AppFunctionManager.
 */
class LocalAppFunctionManager(private val context: Context, private val codec: AppFunctionDataCodec) {

    private val appFunctionManager: AppFunctionManager? by lazy {
        AppFunctionManager.getInstance(context)
    }

    /**
     * In-memory snapshot of the most recent discovery pass, keyed by the **qualified**
     * AppFunction name (`"${packageName}/${id}"` — see [qualify]). AppFunction ids are
     * unique only within their declaring package; keying by the qualified name prevents
     * a later package's entry from silently overwriting an earlier one when two
     * providers expose the same identifier, which would otherwise let `invokeByName`
     * route to the wrong target package or use mismatched parameter metadata.
     *
     * Holds the three facts the caller-side execution path needs but [AgentTool] cannot
     * carry: the source package, the un-qualified system-side identifier that
     * `ExecuteAppFunctionRequest.functionIdentifier` expects, and the typed parameter
     * metadata required by `AppFunctionDataCodec.encode`.
     *
     * Stored as an immutable [Map] behind a `@Volatile` reference, swapped wholesale on
     * every [getAvailableFunctions] call. The atomic reference replacement guarantees
     * that concurrent readers (e.g. [invokeByName] / [isDiscovered]) always observe a
     * fully consistent snapshot — they can never see a transient state where some valid
     * entries have already been evicted and the replacements have not yet been
     * published, which a two-step `retainAll` + `putAll` on a `ConcurrentHashMap` would
     * allow.
     */
    @Volatile
    private var discoveredCache: Map<String, DiscoveredAppFunction> = emptyMap()

    /**
     * Executes an AppFunction by its identifier using the system AppFunctionManager.
     *
     * @param request The request object containing targetPackageName, functionIdentifier, and parameters.
     * @return ExecuteAppFunctionResponse
     */
    suspend fun executeFunction(request: ExecuteAppFunctionRequest): ExecuteAppFunctionResponse {
        val manager =
            appFunctionManager ?: throw IllegalStateException("AppFunctionManager is not available on this device.")
        return manager.executeAppFunction(request)
    }

    /**
     * End-to-end invocation of a discovered AppFunction by name:
     *  1. Looks up the cached parameter metadata and target package (re-discovers on cache
     *     miss).
     *  2. Encodes [arguments] via [AppFunctionDataCodec.encode] into a typed payload.
     *  3. Builds [ExecuteAppFunctionRequest] and dispatches through the system
     *     `AppFunctionManager`.
     *  4. Renders the response through [AppFunctionDataCodec.decode] back into a flat JSON
     *     string suitable for the agent's observation log.
     *
     * Any [AppFunctionException] thrown by the system is wrapped into an
     * [IllegalStateException] with the original cause preserved, so the caller (typically
     * `ToolRepositoryImpl`) does not have to depend on the `androidx.appfunctions` exception
     * hierarchy. This is the single entry point that touches Android AppFunctions types on
     * behalf of the caller path.
     *
     * @throws IllegalArgumentException if [name] is not a currently discovered AppFunction.
     * @throws IllegalStateException if the system reports a failure or required metadata is
     *   missing.
     */
    suspend fun invokeByName(name: String, arguments: String): String {
        val discovered = lookup(name)
            ?: throw IllegalArgumentException("AppFunction $name is not currently discovered")
        return try {
            val data = codec.encode(arguments, discovered.parameters)
            val request = ExecuteAppFunctionRequest(
                discovered.packageName,
                discovered.functionIdentifier,
                data,
            )
            val response = executeFunction(request)
            codec.decode(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw wrapInvocationError(name, e)
        }
    }

    /**
     * Maps a raw invocation failure into the exception type the caller path (typically
     * `ToolRepositoryImpl`) expects.
     *
     * [AppFunctionException]s from the system are wrapped into [IllegalStateException] with the
     * original cause preserved, so callers do not have to depend on the `androidx.appfunctions`
     * exception hierarchy. Every other throwable propagates unchanged. Extracted as an
     * `internal` helper so the JVM unit-test classpath can verify the mapping without
     * constructing an `AppFunctionData` payload (its `init` block reaches into Android stubs
     * that are only present in instrumented tests).
     */
    internal fun wrapInvocationError(name: String, error: Throwable): Throwable {
        if (error is AppFunctionException) {
            return IllegalStateException("AppFunction $name failed: ${error.message}", error)
        }
        return error
    }

    /**
     * Queries the system to discover available AppFunctions for this application.
     *
     * Every discovered AppFunction is tagged with [ToolRisk.SENSITIVE]. The platform
     * `AppFunctionManager` metadata exposes no trustworthy signal about side effects —
     * a function called `read_*` from a third-party package may still write, send or
     * delete. Defaulting conservatively means the Human-in-the-Loop gate prompts the
     * user on the first call; users can downgrade specific AppFunctions to
     * [ToolRisk.READ_ONLY] (or upgrade to [ToolRisk.DESTRUCTIVE]) via
     * `SettingsRepository.setToolRiskOverride`. The override is the
     * authoritative source consumed by `ToolRepository.getRisk`.
     */
    suspend fun getAvailableFunctions(): List<AgentTool> {
        val manager = appFunctionManager ?: run {
            discoveredCache = emptyMap()
            return emptyList()
        }
        val searchSpec = AppFunctionSearchSpec()

        // Take one snapshot of the published functions, build the fresh discovery map, and
        // publish it atomically: the new immutable map replaces the @Volatile reference in
        // a single write, so concurrent readers cannot observe a partially-mutated cache.
        // (`observeAppFunctions(spec)`, the stream this read the first value of, is
        // restricted to the library itself from AppFunctions alpha11 on.)
        val fresh = mutableMapOf<String, DiscoveredAppFunction>()
        val tools = manager.searchAppFunctions(searchSpec).map { metadata ->
            val qualified = qualify(metadata.packageName, metadata.id)
            fresh[qualified] = DiscoveredAppFunction(
                packageName = metadata.packageName,
                functionIdentifier = metadata.id,
                parameters = metadata.parameters,
            )
            AgentTool(
                name = qualified,
                description = metadata.description ?: "App function $qualified",
                parameters = generateJsonSchema(metadata.parameters),
                risk = ToolRisk.SENSITIVE,
            )
        }
        discoveredCache = fresh.toMap()
        return tools
    }

    /**
     * Cheap existence check for a discovered AppFunction by qualified name. Hits the
     * volatile snapshot first; only when the entry is absent does it pay the cost of a
     * fresh discovery pass. Lets callers (e.g. `ToolRepositoryImpl.executeTool`) decide
     * whether to dispatch through [invokeByName] without an extra blanket
     * [getAvailableFunctions] call per execution — the common cache-hit path is O(1)
     * and IPC-free.
     */
    suspend fun isDiscovered(name: String): Boolean = lookup(name) != null

    /**
     * Returns the typed [AppFunctionParameterMetadata] tree for the qualified
     * AppFunction [name] (`"${packageName}/${id}"`), required by
     * `AppFunctionDataCodec.encode` to translate LLM-emitted JSON arguments into an
     * [androidx.appfunctions.AppFunctionData] payload.
     *
     * Performs a transparent re-discovery pass through [getAvailableFunctions] when the
     * cache misses, so callers that ask before any explicit discovery still get a
     * correct answer. Returns `null` only when [name] is genuinely not a discovered
     * AppFunction on this device.
     */
    suspend fun getParametersMetadata(name: String): List<AppFunctionParameterMetadata>? = lookup(name)?.parameters

    /**
     * Returns the source `packageName` reported by the system for the discovered
     * AppFunction with qualified name [name]. Required to build
     * [androidx.appfunctions.ExecuteAppFunctionRequest].
     *
     * Same cache-then-rediscover policy as [getParametersMetadata]; `null` means the
     * function is not visible on this device.
     */
    suspend fun getTargetPackageName(name: String): String? = lookup(name)?.packageName

    private suspend fun lookup(qualifiedName: String): DiscoveredAppFunction? {
        discoveredCache[qualifiedName]?.let { return it }
        getAvailableFunctions()
        return discoveredCache[qualifiedName]
    }

    /**
     * Snapshot of the per-AppFunction facts that [AgentTool] cannot carry — held in
     * [discoveredCache] for the caller-side execution path. [functionIdentifier] is the
     * un-qualified id reported by the system; it is what `ExecuteAppFunctionRequest`
     * expects, while [discoveredCache] is keyed by the qualified
     * `"${packageName}/${functionIdentifier}"` form.
     */
    private data class DiscoveredAppFunction(
        val packageName: String,
        val functionIdentifier: String,
        val parameters: List<AppFunctionParameterMetadata>,
    )

    internal companion object {
        /**
         * Builds the qualified AppFunction name used throughout the caller-side path
         * (`AgentTool.name`, [discoveredCache] key, and the value the agent's LLM sees
         * in `$TOOLS`). The qualified form disambiguates AppFunctions whose un-qualified
         * `id` collides across packages.
         */
        internal fun qualify(packageName: String, id: String): String = "$packageName/$id"
    }

    internal fun generateJsonSchema(parameters: List<AppFunctionParameterMetadata>): String {
        val root = JSONObject()
        root.put("type", "object")

        val properties = JSONObject()
        val required = JSONArray()

        for (param in parameters) {
            properties.put(param.name, mapTypeToJsonSchema(param.dataType, param.description))
            if (param.isRequired) {
                required.put(param.name)
            }
        }

        root.put("properties", properties)
        if (required.length() > 0) {
            root.put("required", required)
        }

        return root.toString()
    }

    internal fun mapTypeToJsonSchema(dataType: AppFunctionDataTypeMetadata, description: String?): JSONObject {
        val schema = JSONObject()

        if (description != null) {
            schema.put("description", description)
        }

        when (dataType) {
            is AppFunctionStringTypeMetadata -> schema.put("type", "string")
            is AppFunctionIntTypeMetadata, is AppFunctionLongTypeMetadata -> schema.put("type", "integer")
            is AppFunctionDoubleTypeMetadata, is AppFunctionFloatTypeMetadata -> schema.put("type", "number")
            is AppFunctionBooleanTypeMetadata -> schema.put("type", "boolean")
            is AppFunctionArrayTypeMetadata -> {
                schema.put("type", "array")
                // Assume array of strings for simplicity if we can't extract the inner type easily
                // or we could recursively call mapTypeToJsonSchema if AppFunctionArrayTypeMetadata exposes itemType
                val itemsSchema = JSONObject().put("type", "string")
                schema.put("items", itemsSchema)
            }
            is AppFunctionObjectTypeMetadata, is AppFunctionReferenceTypeMetadata -> {
                schema.put("type", "object")
            }
            else -> schema.put("type", "string")
        }

        if (dataType.isNullable) {
            // JSON Schema approach for nullable: ["type", "null"] or oneOf.
            // For simple agent usage, standard types are often enough.
        }

        return schema
    }
}
