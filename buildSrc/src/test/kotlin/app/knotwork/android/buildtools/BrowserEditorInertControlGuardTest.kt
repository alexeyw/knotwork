package app.knotwork.android.buildtools

import app.knotwork.android.buildtools.CookbookDocsGenerator.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BrowserEditorInertControlGuard].
 */
class BrowserEditorInertControlGuardTest {

    private val reach = mapOf(
        "LiteRtConfig.systemPrompt" to Reach.Runtime("systemPrompt"),
        "LiteRtConfig.temperature" to Reach.RoundTripOnly("sampling is set from Settings"),
        "CloudConfig.timeoutMs" to Reach.RoundTripOnly("provider settings"),
    )

    private fun editor(liteRt: String, cloud: String) = """
        <script>
        function renderFormFields(typeId, cfg, contextConfig) {
            const fields = [];
            switch (typeId) {
                case 'LITE_RT':
                    $liteRt
                    break;
                case 'CLOUD':
                    $cloud
                    break;
                default:
                    break;
            }
            return fields;
        }
        </script>
    """.trimIndent()

    @Test
    fun `given forms offering round-trip-only fields when scanned then each is reported`() {
        val html = editor(
            liteRt = "fields.push(cfgNumber('temperature', 'Temperature', cfg.temperature));",
            cloud = "fields.push(cfgNumber('timeoutMs', 'Timeout', cfg.timeoutMs));",
        )

        assertEquals(listOf("CLOUD.timeoutMs", "LITE_RT.temperature"), BrowserEditorInertControlGuard.inertControls(html, reach))
    }

    @Test
    fun `given forms offering only fields a run reads when scanned then nothing is reported`() {
        val html = editor(
            liteRt = "fields.push(prompt('systemPrompt', 'System prompt', typeId, true));",
            cloud = "fields.push(cfgSelect('provider', 'Provider', OPTIONS, cfg.provider));",
        )

        assertTrue(BrowserEditorInertControlGuard.inertControls(html, reach).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `given a node type the form no longer cases when scanned then the guard fails loudly`() {
        BrowserEditorInertControlGuard.inertControls(
            "function renderFormFields(typeId) { switch (typeId) { case 'LITE_RT': break; } }",
            reach,
        )
    }

    @Test
    fun `given config class names when mapped then they become node type ids`() {
        assertEquals("LITE_RT", BrowserEditorInertControlGuard.nodeTypeOf("LiteRtConfig"))
        assertEquals("QUEUE_PROCESSOR", BrowserEditorInertControlGuard.nodeTypeOf("QueueProcessorConfig"))
        assertEquals("CLOUD", BrowserEditorInertControlGuard.nodeTypeOf("CloudConfig"))
    }
}
