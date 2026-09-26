package app.knotwork.android.data.tools.local

import android.content.Context
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.metadata.AppFunctionArrayTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDataTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionObjectTypeMetadata
import androidx.appfunctions.metadata.AppFunctionParameterMetadata
import androidx.appfunctions.metadata.AppFunctionReferenceTypeMetadata
import androidx.appfunctions.metadata.AppFunctionResponseMetadata
import androidx.appfunctions.metadata.AppFunctionStringTypeMetadata
import androidx.appfunctions.metadata.AppFunctionUnitTypeMetadata
import app.knotwork.android.domain.models.ToolRisk
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [LocalAppFunctionManager].
 *
 * Split into two halves:
 *  1. Pure-helper tests against [LocalAppFunctionManager.generateJsonSchema] and the
 *     companion [LocalAppFunctionManager.qualify] — no Android runtime mocking required.
 *  2. Discovery / invocation tests that stub the static
 *     `AppFunctionManager.getInstance(Context)` via `mockkStatic` and exercise the cache,
 *     dedup, and `invokeByName` exception-mapping logic. The codec is mocked for
 *     invocation tests so the production codec path (which constructs `AppFunctionData`)
 *     stays inside the instrumented suite where the Android runtime is real.
 */
class LocalAppFunctionManagerTest {

    private lateinit var context: Context
    private lateinit var realCodec: AppFunctionDataCodec
    private lateinit var manager: AppFunctionManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        realCodec = AppFunctionDataCodec()
        manager = mockk(relaxed = true)
        mockkObject(AppFunctionManager.Companion)
        every { AppFunctionManager.getInstance(any()) } returns manager
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // region Pure-helper tests

    @Test
    fun `qualify joins packageName and id with a slash separator`() {
        assertEquals("com.example/foo", LocalAppFunctionManager.qualify("com.example", "foo"))
    }

    @Test
    fun `generateJsonSchema emits a type object with empty properties when no parameters`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(subject.generateJsonSchema(emptyList()))

        assertEquals("object", schema.getString("type"))
        assertEquals(0, schema.getJSONObject("properties").length())
        assertFalse(schema.has("required"))
    }

    @Test
    fun `generateJsonSchema maps a string parameter to type string`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(requiredParam("title", AppFunctionStringTypeMetadata(isNullable = false))),
            ),
        )

        assertEquals("string", schema.getJSONObject("properties").getJSONObject("title").getString("type"))
        assertEquals("title", schema.getJSONArray("required").getString(0))
    }

    @Test
    fun `generateJsonSchema maps int and long parameters to type integer`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(
                    requiredParam("count", AppFunctionIntTypeMetadata(isNullable = false)),
                    requiredParam("ts", AppFunctionLongTypeMetadata(isNullable = false)),
                ),
            ),
        )

        val properties = schema.getJSONObject("properties")
        assertEquals("integer", properties.getJSONObject("count").getString("type"))
        assertEquals("integer", properties.getJSONObject("ts").getString("type"))
    }

    @Test
    fun `generateJsonSchema maps double and float parameters to type number`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(
                    requiredParam("ratio", AppFunctionDoubleTypeMetadata(isNullable = false)),
                    requiredParam("temp", AppFunctionFloatTypeMetadata(isNullable = false)),
                ),
            ),
        )

        val properties = schema.getJSONObject("properties")
        assertEquals("number", properties.getJSONObject("ratio").getString("type"))
        assertEquals("number", properties.getJSONObject("temp").getString("type"))
    }

    @Test
    fun `generateJsonSchema maps a boolean parameter to type boolean`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(requiredParam("enabled", AppFunctionBooleanTypeMetadata(isNullable = false))),
            ),
        )

        assertEquals(
            "boolean",
            schema.getJSONObject("properties").getJSONObject("enabled").getString("type"),
        )
    }

    @Test
    fun `generateJsonSchema maps an array parameter to type array with string items`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val arrayType = AppFunctionArrayTypeMetadata(
            itemType = AppFunctionStringTypeMetadata(isNullable = false),
            isNullable = false,
        )
        val schema = JSONObject(
            subject.generateJsonSchema(listOf(requiredParam("tags", arrayType))),
        )

        val tagsSchema = schema.getJSONObject("properties").getJSONObject("tags")
        assertEquals("array", tagsSchema.getString("type"))
        assertEquals("string", tagsSchema.getJSONObject("items").getString("type"))
    }

    @Test
    fun `generateJsonSchema maps object and reference parameters to type object`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val objectType = AppFunctionObjectTypeMetadata(
            properties = mapOf("street" to AppFunctionStringTypeMetadata(isNullable = false)),
            required = listOf("street"),
            qualifiedName = "com.example.Address",
            isNullable = false,
        )
        val referenceType = AppFunctionReferenceTypeMetadata(
            referenceDataType = "components/Profile",
            isNullable = false,
        )

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(
                    requiredParam("address", objectType),
                    requiredParam("profile", referenceType),
                ),
            ),
        )

        val properties = schema.getJSONObject("properties")
        assertEquals("object", properties.getJSONObject("address").getString("type"))
        assertEquals("object", properties.getJSONObject("profile").getString("type"))
    }

    @Test
    fun `generateJsonSchema falls back to type string for unsupported types`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(listOf(requiredParam("nothing", AppFunctionUnitTypeMetadata()))),
        )

        assertEquals(
            "string",
            schema.getJSONObject("properties").getJSONObject("nothing").getString("type"),
        )
    }

    @Test
    fun `generateJsonSchema includes description on a property when supplied`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val parameter = AppFunctionParameterMetadata(
            name = "title",
            isRequired = true,
            description = "The title to display",
            dataType = AppFunctionStringTypeMetadata(isNullable = false),
        )

        val schema = JSONObject(subject.generateJsonSchema(listOf(parameter)))

        assertEquals(
            "The title to display",
            schema.getJSONObject("properties").getJSONObject("title").getString("description"),
        )
    }

    @Test
    fun `mapTypeToJsonSchema omits description when caller passes null`() {
        // The public `generateJsonSchema` path never feeds a null description (the
        // platform `AppFunctionParameterMetadata.description` defaults to ""), but the
        // private mapper still has the contract — pin it so a refactor doesn't drift.
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = subject.mapTypeToJsonSchema(
            AppFunctionStringTypeMetadata(isNullable = false),
            description = null,
        )

        assertFalse(schema.has("description"))
    }

    @Test
    fun `generateJsonSchema omits required array when no parameter is required`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(
                    AppFunctionParameterMetadata(
                        name = "note",
                        isRequired = false,
                        dataType = AppFunctionStringTypeMetadata(isNullable = false),
                    ),
                ),
            ),
        )

        assertFalse(schema.has("required"))
    }

    @Test
    fun `generateJsonSchema lists only required parameters in required array`() {
        val subject = LocalAppFunctionManager(context, realCodec)

        val schema = JSONObject(
            subject.generateJsonSchema(
                listOf(
                    AppFunctionParameterMetadata(
                        name = "mandatory",
                        isRequired = true,
                        dataType = AppFunctionStringTypeMetadata(isNullable = false),
                    ),
                    AppFunctionParameterMetadata(
                        name = "optional",
                        isRequired = false,
                        dataType = AppFunctionStringTypeMetadata(isNullable = false),
                    ),
                ),
            ),
        )

        val required = schema.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("mandatory", required.getString(0))
    }

    // endregion

    // region Discovery / invocation tests

    @Test
    fun `getAvailableFunctions returns empty list when AppFunctionManager is unavailable`() = runTest {
        every { AppFunctionManager.getInstance(any()) } returns null
        val subject = LocalAppFunctionManager(context, realCodec)

        assertTrue(subject.getAvailableFunctions().isEmpty())
    }

    @Test
    fun `getAvailableFunctions clears the discovery cache when AppFunctionManager is unavailable`() = runTest {
        every { AppFunctionManager.getInstance(any()) } returns null
        val subject = LocalAppFunctionManager(context, realCodec)
        subject.getAvailableFunctions()

        assertFalse(subject.isDiscovered("com.example/foo"))
    }

    @Test
    fun `getAvailableFunctions emits one AgentTool per discovered AppFunction tagged SENSITIVE`() = runTest {
        stubDiscovery(packageFixture("com.example", metadataFixture("foo", description = "hello")))
        val subject = LocalAppFunctionManager(context, realCodec)

        val tools = subject.getAvailableFunctions()

        assertEquals(1, tools.size)
        val tool = tools.single()
        assertEquals("com.example/foo", tool.name)
        assertEquals("hello", tool.description)
        assertEquals(ToolRisk.SENSITIVE, tool.risk)
    }

    @Test
    fun `getAvailableFunctions encodes the JSON schema for each discovered AppFunction`() = runTest {
        val parameters = listOf(requiredParam("title", AppFunctionStringTypeMetadata(isNullable = false)))
        stubDiscovery(packageFixture("com.example", metadataFixture("foo", parameters = parameters)))
        val subject = LocalAppFunctionManager(context, realCodec)

        val toolSchema = JSONObject(subject.getAvailableFunctions().single().parameters)

        assertEquals("object", toolSchema.getString("type"))
        assertEquals(
            "string",
            toolSchema.getJSONObject("properties").getJSONObject("title").getString("type"),
        )
    }

    @Test
    fun `getAvailableFunctions propagates the metadata description verbatim`() = runTest {
        stubDiscovery(
            packageFixture("com.example", metadataFixture("foo", description = "Send an email")),
        )
        val subject = LocalAppFunctionManager(context, realCodec)

        val tool = subject.getAvailableFunctions().single()

        assertEquals("Send an email", tool.description)
    }

    @Test
    fun `getAvailableFunctions deduplicates AppFunctions with the same id from different packages by qualified name`() =
        runTest {
            stubDiscovery(
                packageFixture("com.first", metadataFixture("shared")),
                packageFixture("com.second", metadataFixture("shared")),
            )
            val subject = LocalAppFunctionManager(context, realCodec)

            val tools = subject.getAvailableFunctions()

            val names = tools.map { it.name }.toSet()
            assertEquals(setOf("com.first/shared", "com.second/shared"), names)
            assertEquals(2, tools.size)
            // Both qualified names remain individually addressable through the cache.
            assertTrue(subject.isDiscovered("com.first/shared"))
            assertTrue(subject.isDiscovered("com.second/shared"))
            assertEquals("com.first", subject.getTargetPackageName("com.first/shared"))
            assertEquals("com.second", subject.getTargetPackageName("com.second/shared"))
        }

    @Test
    fun `isDiscovered returns true after the cache has been populated`() = runTest {
        stubDiscovery(packageFixture("com.example", metadataFixture("foo")))
        val subject = LocalAppFunctionManager(context, realCodec)
        subject.getAvailableFunctions()

        assertTrue(subject.isDiscovered("com.example/foo"))
    }

    @Test
    fun `isDiscovered triggers a re-discovery pass when the cache misses and returns false for unknown name`() =
        runTest {
            stubDiscovery() // empty package list
            val subject = LocalAppFunctionManager(context, realCodec)

            assertFalse(subject.isDiscovered("com.example/foo"))

            // Re-discovery was attempted: searchAppFunctions was called once on the cache miss.
            coVerify(exactly = 1) { manager.searchAppFunctions(any()) }
        }

    @Test
    fun `getParametersMetadata returns the typed parameter list for a discovered function`() = runTest {
        val parameters = listOf(requiredParam("title", AppFunctionStringTypeMetadata(isNullable = false)))
        stubDiscovery(packageFixture("com.example", metadataFixture("foo", parameters = parameters)))
        val subject = LocalAppFunctionManager(context, realCodec)
        subject.getAvailableFunctions()

        val resolved = subject.getParametersMetadata("com.example/foo")

        assertNotNull(resolved)
        assertEquals(listOf("title"), resolved!!.map { it.name })
    }

    @Test
    fun `getParametersMetadata returns null after a re-discovery pass still misses`() = runTest {
        stubDiscovery() // empty
        val subject = LocalAppFunctionManager(context, realCodec)

        assertNull(subject.getParametersMetadata("com.example/missing"))
    }

    @Test
    fun `getTargetPackageName returns the source package for a discovered function`() = runTest {
        stubDiscovery(packageFixture("com.example", metadataFixture("foo")))
        val subject = LocalAppFunctionManager(context, realCodec)

        assertEquals("com.example", subject.getTargetPackageName("com.example/foo"))
    }

    @Test
    fun `getTargetPackageName returns null for an unknown function`() = runTest {
        stubDiscovery()
        val subject = LocalAppFunctionManager(context, realCodec)

        assertNull(subject.getTargetPackageName("com.example/missing"))
    }

    @Test
    fun `executeFunction throws IllegalStateException when AppFunctionManager is unavailable`() = runTest {
        every { AppFunctionManager.getInstance(any()) } returns null
        val subject = LocalAppFunctionManager(context, realCodec)
        val request = mockk<ExecuteAppFunctionRequest>(relaxed = true)

        val error = runCatching { subject.executeFunction(request) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("AppFunctionManager"))
    }

    @Test
    fun `invokeByName throws IllegalArgumentException when the function is not discovered`() = runTest {
        stubDiscovery() // empty
        val subject = LocalAppFunctionManager(context, realCodec)

        val error = runCatching { subject.invokeByName("com.example/missing", "{}") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("com.example/missing"))
    }

    @Test
    fun `wrapInvocationError wraps AppFunctionException into IllegalStateException preserving cause and name`() {
        // The full invoke chain (codec.encode → executeAppFunction → codec.decode) requires a
        // real `AppFunctionData` which cannot be constructed on the JVM unit-test classpath
        // (its `init` block touches `Bundle.classLoader`). The exception-mapping branch is
        // extracted as `wrapInvocationError` and exercised here directly; the wiring itself is
        // covered by `AppFunctionsEndToEndTest`.
        //
        // `AppFunctionAppUnknownException`'s constructor dereferences `Bundle.EMPTY` (an Android
        // stub returning `null` under unit tests), so we instantiate it through MockK / Objenesis
        // — which skips the constructor entirely — and stub only the property the helper reads.
        val subject = LocalAppFunctionManager(context, realCodec)
        val underlying = mockk<AppFunctionAppUnknownException>(relaxed = true)
        every { underlying.message } returns "backend boom"

        val mapped = subject.wrapInvocationError("com.example/foo", underlying)

        assertTrue(mapped is IllegalStateException)
        assertTrue(mapped.message!!.contains("com.example/foo"))
        assertSame(underlying, mapped.cause)
    }

    @Test
    fun `wrapInvocationError passes non-AppFunctionException throwables through unchanged`() {
        val subject = LocalAppFunctionManager(context, realCodec)
        val original = UnsupportedAppFunctionTypeException("Field 'x' uses unsupported AppFunction type 'WeirdType'")

        val mapped = subject.wrapInvocationError("com.example/foo", original)

        assertSame(original, mapped)
    }

    // endregion

    // region Helpers

    private fun stubDiscovery(vararg packages: List<AppFunctionMetadata>) {
        coEvery { manager.searchAppFunctions(any()) } returns packages.flatMap { it }
    }

    /** The [functions] as one package publishes them: each carries [packageName] itself. */
    private fun packageFixture(packageName: String, vararg functions: AppFunctionMetadata) = functions.map {
        AppFunctionMetadata(
            id = it.id,
            packageName = packageName,
            isEnabled = true,
            schema = it.schema,
            parameters = it.parameters,
            response = it.response,
            description = it.description,
        )
    }

    private fun metadataFixture(
        id: String,
        description: String = "",
        parameters: List<AppFunctionParameterMetadata> = emptyList(),
    ): AppFunctionMetadata = AppFunctionMetadata(
        id = id,
        packageName = currentFixturePackage(),
        isEnabled = true,
        schema = null,
        parameters = parameters,
        response = AppFunctionResponseMetadata(valueType = AppFunctionUnitTypeMetadata()),
        description = description,
    )

    /**
     * `AppFunctionMetadata` needs a packageName; [packageFixture] rebuilds each fixture
     * with the package it is published under, so this placeholder only satisfies the
     * constructor and never reaches the code under test.
     */
    private fun currentFixturePackage(): String = "ignored.metadata.package"

    private fun requiredParam(name: String, dataType: AppFunctionDataTypeMetadata) =
        AppFunctionParameterMetadata(name = name, isRequired = true, dataType = dataType)

    // endregion
}
