package app.knotwork.android.data.tools.local

import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionAllOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBytesTypeMetadata
import androidx.appfunctions.metadata.AppFunctionComponentsMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionOneOfTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionParcelableTypeMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bidirectional codec between LLM-emitted JSON argument strings and the typed
 * [AppFunctionData] consumed by the system `AppFunctionManager.executeAppFunction(...)`.
 *
 * The agent's local LLM produces tool arguments as a JSON string (`{"foo": 1, "bar": "x"}`).
 * The system AppFunctions surface requires those arguments to be packed into a typed
 * [AppFunctionData] whose shape is validated against the AppFunction's declared
 * [AppFunctionParameterMetadata] tree. This codec is the single, authoritative
 * translation point between the two representations.
 *
 * The codec is stateless and injectable via Hilt. Encoding is split into two phases:
 *  1. [planWriteOps] turns the JSON tree plus the schema into a pure list of
 *     [AppFunctionWriteOp] entries — fully testable without the Android runtime.
 *  2. [encode] / [applyOps] materialize those ops onto a real `AppFunctionData.Builder`.
 *  Decoding follows the same shape: [renderError] / [renderSuccess] are pure
 *  functions, while [decode] is the thin response-routing shim.
 */
@Singleton
class AppFunctionDataCodec @Inject constructor() {

    /**
     * Parses [json] and packs the values into an [AppFunctionData] that conforms to [schema].
     *
     * Conversion rules:
     *  - Primitive types ([AppFunctionStringTypeMetadata], integer/long/double/float/boolean)
     *    are written via the matching `Builder.setX(...)` setter.
     *  - [AppFunctionArrayTypeMetadata] of primitives is written as the corresponding typed
     *    array (`setBooleanArray`, `setIntArray`, `setLongArray`, `setDoubleArray`,
     *    `setFloatArray`, `setStringList`); arrays of [AppFunctionObjectTypeMetadata] are
     *    recursively encoded and written via `setAppFunctionDataList`. Any other array item
     *    type (references, nested arrays, etc.) raises [UnsupportedAppFunctionTypeException].
     *  - [AppFunctionObjectTypeMetadata] is encoded recursively into a nested
     *    [AppFunctionData] and written via `setAppFunctionData`.
     *  - References, all-of, one-of, parcelable, bytes and unit types are not supported and
     *    raise [UnsupportedAppFunctionTypeException] with the offending field name.
     *  - Missing required fields raise [IllegalArgumentException] naming the field; missing
     *    optional fields are skipped silently.
     *  - JSON keys not declared in [schema] are dropped with a `Timber.w(...)` warning so
     *    misaligned LLM output never blocks execution.
     *  - Malformed JSON propagates the underlying `org.json.JSONException` to the caller —
     *    the codec does not try to recover.
     *
     * @param json LLM-emitted arguments object. Must be a JSON object at the top level.
     * @param schema Ordered parameter metadata as exposed by `AppFunctionMetadata.parameters`.
     * @return A built [AppFunctionData] ready to feed into `ExecuteAppFunctionRequest`.
     * @throws IllegalArgumentException when a required field is missing from [json].
     * @throws UnsupportedAppFunctionTypeException when [schema] contains a type the codec
     *   cannot represent.
     * @throws org.json.JSONException when [json] is malformed or a field's JSON shape does
     *   not match its declared type.
     */
    fun encode(json: String, schema: List<AppFunctionParameterMetadata>): AppFunctionData {
        val ops = planWriteOps(json, schema)
        val components = AppFunctionComponentsMetadata()
        val builder = AppFunctionData.Builder(schema, components)
        applyOps(builder, ops, components)
        return builder.build()
    }

    /**
     * Renders [response] as a flat JSON string for the agent's observation log.
     *
     * The output is best-effort and always a valid JSON object:
     *  - [ExecuteAppFunctionResponse.Error] → `{"error":"<message>"}` (see [renderError]).
     *  - [ExecuteAppFunctionResponse.Success] with a primitive return value →
     *    `{"result":<value>}` using the first matching scalar getter (see [renderSuccess]).
     *    When the return value is `AppFunctionData.EMPTY` (empty qualified name) the result
     *    is `{"result":null}`.
     *  - Any internal failure during rendering collapses to
     *    `{"error":"Failed to decode AppFunction response"}` so the observation log can
     *    never poison the pipeline.
     */
    fun decode(response: ExecuteAppFunctionResponse): String = runCatching {
        when (response) {
            is ExecuteAppFunctionResponse.Error -> renderError(response.error.errorMessage)
            is ExecuteAppFunctionResponse.Success -> {
                val returnValue = response.returnValue
                val key = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE
                renderSuccess(
                    qualifiedName = returnValue.qualifiedName,
                    containsKey = { runCatching { returnValue.containsKey(it) }.getOrDefault(false) },
                    getString = { runCatching { returnValue.getString(it) }.getOrNull() },
                    getInt = { k ->
                        runCatching { probeInt { default -> returnValue.getInt(k, default) } }.getOrNull()
                    },
                    getLong = { k ->
                        runCatching { probeLong { default -> returnValue.getLong(k, default) } }.getOrNull()
                    },
                    getFloat = { k ->
                        runCatching { probeFloat { default -> returnValue.getFloat(k, default) } }.getOrNull()
                    },
                    getDouble = { k ->
                        runCatching { probeDouble { default -> returnValue.getDouble(k, default) } }.getOrNull()
                    },
                    getBoolean = { k ->
                        runCatching { probeBoolean { default -> returnValue.getBoolean(k, default) } }.getOrNull()
                    },
                    key = key,
                )
            }
        }
    }.getOrElse {
        Timber.w(it, "Failed to decode AppFunction response")
        FALLBACK_DECODE_ERROR
    }

    /**
     * Pure JSON → [AppFunctionWriteOp] translation. Exposed as `internal` for unit tests; the
     * production path goes through [encode]. Implements all the validation rules documented
     * on [encode] without touching `AppFunctionData.Builder`, so the JVM unit-test classpath
     * can exercise it without Android stubs.
     */
    internal fun planWriteOps(json: String, schema: List<AppFunctionParameterMetadata>): List<AppFunctionWriteOp> {
        val root = JSONObject(json)
        return planObject(root, schema.map { it.name to it.dataType }, schema.filter { it.isRequired }.map { it.name })
    }

    private fun planObject(
        json: JSONObject,
        properties: List<Pair<String, AppFunctionDataTypeMetadata>>,
        required: List<String>,
    ): List<AppFunctionWriteOp> {
        val declared = properties.map { it.first }.toHashSet()
        val requiredSet = required.toHashSet()
        val ops = ArrayList<AppFunctionWriteOp>(properties.size)
        for ((name, type) in properties) {
            val present = json.has(name) && !json.isNull(name)
            if (!present) {
                require(name !in requiredSet) { "Missing required field '$name' for AppFunction" }
                continue
            }
            ops += planValue(name, json, type)
        }
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in declared) {
                Timber.w("Ignoring extra field '%s' not in AppFunction schema", key)
            }
        }
        return ops
    }

    private fun planValue(key: String, parent: JSONObject, dataType: AppFunctionDataTypeMetadata): AppFunctionWriteOp =
        when (dataType) {
            is AppFunctionStringTypeMetadata -> AppFunctionWriteOp.SetString(key, parent.getString(key))
            is AppFunctionIntTypeMetadata -> AppFunctionWriteOp.SetInt(key, parent.getInt(key))
            is AppFunctionLongTypeMetadata -> AppFunctionWriteOp.SetLong(key, parent.getLong(key))
            is AppFunctionDoubleTypeMetadata -> AppFunctionWriteOp.SetDouble(key, parent.getDouble(key))
            is AppFunctionFloatTypeMetadata -> AppFunctionWriteOp.SetFloat(key, parent.getDouble(key).toFloat())
            is AppFunctionBooleanTypeMetadata -> AppFunctionWriteOp.SetBoolean(key, parent.getBoolean(key))
            is AppFunctionArrayTypeMetadata -> planArray(key, parent.getJSONArray(key), dataType)
            is AppFunctionObjectTypeMetadata -> {
                val nested = planObject(
                    parent.getJSONObject(key),
                    dataType.properties.entries.map { it.key to it.value },
                    dataType.required,
                )
                AppFunctionWriteOp.SetObject(key, dataType, nested)
            }
            is AppFunctionReferenceTypeMetadata -> throw UnsupportedAppFunctionTypeException(
                "Field '$key' uses unsupported reference type '${dataType.referenceDataType}'",
            )
            is AppFunctionAllOfTypeMetadata,
            is AppFunctionOneOfTypeMetadata,
            is AppFunctionParcelableTypeMetadata,
            is AppFunctionBytesTypeMetadata,
            is AppFunctionUnitTypeMetadata,
            -> throw UnsupportedAppFunctionTypeException(
                "Field '$key' uses unsupported AppFunction type '${dataType::class.simpleName}'",
            )
            else -> throw UnsupportedAppFunctionTypeException(
                "Field '$key' uses unsupported AppFunction type '${dataType::class.simpleName}'",
            )
        }

    private fun planArray(key: String, array: JSONArray, dataType: AppFunctionArrayTypeMetadata): AppFunctionWriteOp =
        when (val itemType = dataType.itemType) {
            is AppFunctionStringTypeMetadata -> {
                val list = ArrayList<String>(array.length())
                for (i in 0 until array.length()) list.add(array.getString(i))
                AppFunctionWriteOp.SetStringList(key, list)
            }
            is AppFunctionIntTypeMetadata -> {
                val ints = IntArray(array.length()) { array.getInt(it) }
                AppFunctionWriteOp.SetIntArray(key, ints)
            }
            is AppFunctionLongTypeMetadata -> {
                val longs = LongArray(array.length()) { array.getLong(it) }
                AppFunctionWriteOp.SetLongArray(key, longs)
            }
            is AppFunctionDoubleTypeMetadata -> {
                val doubles = DoubleArray(array.length()) { array.getDouble(it) }
                AppFunctionWriteOp.SetDoubleArray(key, doubles)
            }
            is AppFunctionFloatTypeMetadata -> {
                val floats = FloatArray(array.length()) { array.getDouble(it).toFloat() }
                AppFunctionWriteOp.SetFloatArray(key, floats)
            }
            is AppFunctionBooleanTypeMetadata -> {
                val bools = BooleanArray(array.length()) { array.getBoolean(it) }
                AppFunctionWriteOp.SetBooleanArray(key, bools)
            }
            is AppFunctionObjectTypeMetadata -> {
                val items = ArrayList<List<AppFunctionWriteOp>>(array.length())
                for (i in 0 until array.length()) {
                    items += planObject(
                        array.getJSONObject(i),
                        itemType.properties.entries.map { it.key to it.value },
                        itemType.required,
                    )
                }
                AppFunctionWriteOp.SetObjectList(key, itemType, items)
            }
            else -> throw UnsupportedAppFunctionTypeException(
                "Field '$key' uses unsupported array item type '${itemType::class.simpleName}'",
            )
        }

    private fun applyOps(
        builder: AppFunctionData.Builder,
        ops: List<AppFunctionWriteOp>,
        components: AppFunctionComponentsMetadata,
    ) {
        for (op in ops) {
            when (op) {
                is AppFunctionWriteOp.SetString -> builder.setString(op.key, op.value)
                is AppFunctionWriteOp.SetInt -> builder.setInt(op.key, op.value)
                is AppFunctionWriteOp.SetLong -> builder.setLong(op.key, op.value)
                is AppFunctionWriteOp.SetDouble -> builder.setDouble(op.key, op.value)
                is AppFunctionWriteOp.SetFloat -> builder.setFloat(op.key, op.value)
                is AppFunctionWriteOp.SetBoolean -> builder.setBoolean(op.key, op.value)
                is AppFunctionWriteOp.SetStringList -> builder.setStringList(op.key, op.value)
                is AppFunctionWriteOp.SetIntArray -> builder.setIntArray(op.key, op.value)
                is AppFunctionWriteOp.SetLongArray -> builder.setLongArray(op.key, op.value)
                is AppFunctionWriteOp.SetDoubleArray -> builder.setDoubleArray(op.key, op.value)
                is AppFunctionWriteOp.SetFloatArray -> builder.setFloatArray(op.key, op.value)
                is AppFunctionWriteOp.SetBooleanArray -> builder.setBooleanArray(op.key, op.value)
                is AppFunctionWriteOp.SetObject -> {
                    val nested = AppFunctionData.Builder(op.objectType, components)
                    applyOps(nested, op.children, components)
                    builder.setAppFunctionData(op.key, nested.build())
                }
                is AppFunctionWriteOp.SetObjectList -> {
                    val list = ArrayList<AppFunctionData>(op.items.size)
                    for (entry in op.items) {
                        val nested = AppFunctionData.Builder(op.objectType, components)
                        applyOps(nested, entry, components)
                        list += nested.build()
                    }
                    builder.setAppFunctionDataList(op.key, list)
                }
            }
        }
    }

    /** Renders a `{"error":"..."}` payload, never blank. Exposed for unit tests. */
    internal fun renderError(message: String?): String {
        val text = if (message.isNullOrBlank()) "AppFunction execution failed" else message
        return JSONObject().put("error", text).toString()
    }

    /**
     * Renders a `{"result":...}` payload by probing the typed getters in
     * `string → int → long → float → double → boolean` order. Every numeric type that
     * `AppFunctionData` exposes is queried, so successful AppFunctions that return `Int`
     * or `Float` are serialized as JSON numbers instead of `null` (the previous behaviour
     * silently swallowed type-mismatch exceptions and dropped real numeric results from
     * the observation log). Pure: callers pass closure-based getters, making the function
     * trivially testable without `AppFunctionData`.
     */
    internal fun renderSuccess(
        qualifiedName: String,
        containsKey: (String) -> Boolean,
        getString: (String) -> String?,
        getInt: (String) -> Int?,
        getLong: (String) -> Long?,
        getFloat: (String) -> Float?,
        getDouble: (String) -> Double?,
        getBoolean: (String) -> Boolean?,
        key: String,
    ): String {
        val obj = JSONObject()
        if (qualifiedName.isEmpty() || !containsKey(key)) {
            obj.put("result", JSONObject.NULL)
            return obj.toString()
        }
        val resolved: Any? = getString(key)
            ?: getInt(key)
            ?: getLong(key)
            ?: getFloat(key)
            ?: getDouble(key)
            ?: getBoolean(key)
        obj.put("result", resolved ?: JSONObject.NULL)
        return obj.toString()
    }

    /**
     * Resolves whether the scalar value addressed by [getter] is actually present at the
     * scalar slot or whether the underlying `getX(key, default)` would silently fall back
     * to the supplied default.
     *
     * [AppFunctionData]'s typed scalar getters return their supplied default when the
     * property is absent from the typed slot, so naïvely treating the returned value as
     * the real result conflates "missing scalar" with "legitimate sentinel-valued result"
     * (e.g. `Long.MIN_VALUE`, `Int.MIN_VALUE`, `Double.NaN`, `false`). The double-probe
     * pattern invokes the getter twice with two distinct defaults: if the two return
     * values agree the property genuinely resolves to that value, otherwise the property
     * is absent from this scalar slot and we report `null`.
     *
     * Helpers are exposed as `internal` so unit tests can verify the probe semantics
     * directly without an Android-bound `AppFunctionData`.
     */
    internal fun probeLong(getter: (Long) -> Long): Long? {
        val first = getter(LONG_PROBE_A)
        val second = getter(LONG_PROBE_B)
        return first.takeIf { it == second }
    }

    internal fun probeInt(getter: (Int) -> Int): Int? {
        val first = getter(INT_PROBE_A)
        val second = getter(INT_PROBE_B)
        return first.takeIf { it == second }
    }

    internal fun probeDouble(getter: (Double) -> Double): Double? {
        val first = getter(DOUBLE_PROBE_A)
        val second = getter(DOUBLE_PROBE_B)
        return first.takeIf { it == second }
    }

    internal fun probeFloat(getter: (Float) -> Float): Float? {
        val first = getter(FLOAT_PROBE_A)
        val second = getter(FLOAT_PROBE_B)
        return first.takeIf { it == second }
    }

    internal fun probeBoolean(getter: (Boolean) -> Boolean): Boolean? {
        val first = getter(false)
        val second = getter(true)
        return first.takeIf { it == second }
    }

    private companion object {
        const val FALLBACK_DECODE_ERROR = """{"error":"Failed to decode AppFunction response"}"""

        // Probe defaults — two arbitrary but distinct values per scalar type. Numeric
        // probes must remain finite (NaN is not equal to itself, which would break the
        // double-probe equality check).
        const val LONG_PROBE_A = 0L
        const val LONG_PROBE_B = 1L
        const val INT_PROBE_A = 0
        const val INT_PROBE_B = 1
        const val DOUBLE_PROBE_A = 0.0
        const val DOUBLE_PROBE_B = 1.0
        const val FLOAT_PROBE_A = 0f
        const val FLOAT_PROBE_B = 1f
    }
}

/**
 * Pure-data representation of every `AppFunctionData.Builder` mutation produced by
 * [AppFunctionDataCodec.planWriteOps]. Holding the encoding plan as data keeps the JSON
 * parsing layer fully testable on the JVM (no Android types are reachable from this
 * hierarchy) while [AppFunctionDataCodec.encode] still materializes a real
 * `AppFunctionData` in production.
 */
internal sealed interface AppFunctionWriteOp {
    val key: String

    data class SetString(override val key: String, val value: String) : AppFunctionWriteOp
    data class SetInt(override val key: String, val value: Int) : AppFunctionWriteOp
    data class SetLong(override val key: String, val value: Long) : AppFunctionWriteOp
    data class SetDouble(override val key: String, val value: Double) : AppFunctionWriteOp
    data class SetFloat(override val key: String, val value: Float) : AppFunctionWriteOp
    data class SetBoolean(override val key: String, val value: Boolean) : AppFunctionWriteOp
    data class SetStringList(override val key: String, val value: List<String>) : AppFunctionWriteOp
    data class SetIntArray(override val key: String, val value: IntArray) : AppFunctionWriteOp {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SetIntArray && key == other.key && value.contentEquals(other.value))

        override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()
    }
    data class SetLongArray(override val key: String, val value: LongArray) : AppFunctionWriteOp {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SetLongArray && key == other.key && value.contentEquals(other.value))

        override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()
    }
    data class SetDoubleArray(override val key: String, val value: DoubleArray) : AppFunctionWriteOp {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SetDoubleArray && key == other.key && value.contentEquals(other.value))

        override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()
    }
    data class SetFloatArray(override val key: String, val value: FloatArray) : AppFunctionWriteOp {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SetFloatArray && key == other.key && value.contentEquals(other.value))

        override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()
    }
    data class SetBooleanArray(override val key: String, val value: BooleanArray) : AppFunctionWriteOp {
        override fun equals(other: Any?): Boolean =
            this === other || (other is SetBooleanArray && key == other.key && value.contentEquals(other.value))

        override fun hashCode(): Int = 31 * key.hashCode() + value.contentHashCode()
    }
    data class SetObject(
        override val key: String,
        val objectType: AppFunctionObjectTypeMetadata,
        val children: List<AppFunctionWriteOp>,
    ) : AppFunctionWriteOp
    data class SetObjectList(
        override val key: String,
        val objectType: AppFunctionObjectTypeMetadata,
        val items: List<List<AppFunctionWriteOp>>,
    ) : AppFunctionWriteOp
}

/**
 * Raised by [AppFunctionDataCodec] when an [AppFunctionDataTypeMetadata] sub-type cannot
 * be expressed through the JSON ↔ [AppFunctionData] mapping.
 *
 * The message always names the offending field and the unsupported type so the agent's
 * observation log surfaces an actionable hint.
 */
class UnsupportedAppFunctionTypeException(message: String) : RuntimeException(message)
