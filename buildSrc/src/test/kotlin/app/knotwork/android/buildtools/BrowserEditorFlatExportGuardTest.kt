package app.knotwork.android.buildtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BrowserEditorFlatExportGuard]. The guard itself runs over the
 * real `pipeline-editor.html` in `:app:verifyBrowserEditorConstants`, which
 * declares the file as an input.
 */
class BrowserEditorFlatExportGuardTest {

    private fun editor(export: String) = """
        function richToFlat(typeId, cfg) {
            const flat = {
                systemPrompt: '', toolName: '',
            };
            switch (typeId) {
                case 'INTENT_ROUTER': flat.fallbackClass = cfg.fallbackClass || null; break;
                case 'DECOMPOSITION': if (flat.maxSubtasks == null) { flat.maxSubtasks = (cfg.maxSubtasks ?? null); } break;
            }
            return flat;
        }
        function exportToJson() {
            return nodes.map(n => ({
                id: n.id,
                config: {
                    $export
                },
                nodeConfig: {},
            }));
        }
    """.trimIndent()

    @Test
    fun `given a derived key the export leaves out when checked then it is reported`() {
        val html = editor("systemPrompt: flat.systemPrompt || null,\ntoolName: flat.toolName || null,")

        assertEquals(
            listOf("fallbackClass", "maxSubtasks"),
            BrowserEditorFlatExportGuard.unexportedFlatFields(html),
        )
    }

    @Test
    fun `given an export writing every derived key when checked then nothing is reported`() {
        val html = editor(
            "systemPrompt: flat.systemPrompt || null,\ntoolName: flat.toolName || null,\n" +
                "fallbackClass: flat.fallbackClass || null,\nmaxSubtasks: flat.maxSubtasks ?? null,",
        )

        assertTrue(BrowserEditorFlatExportGuard.unexportedFlatFields(html).isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `given an editor without richToFlat when checked then it fails loudly`() {
        BrowserEditorFlatExportGuard.unexportedFlatFields("function exportToJson() { config: { } }")
    }
}
