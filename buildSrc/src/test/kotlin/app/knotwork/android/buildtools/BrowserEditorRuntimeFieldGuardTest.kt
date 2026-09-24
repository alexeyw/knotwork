package app.knotwork.android.buildtools

import app.knotwork.android.buildtools.CookbookDocsGenerator.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BrowserEditorRuntimeFieldGuard]. The guard itself runs over the
 * real `pipeline-editor.html` in `:app:verifyBrowserEditorConstants`, which
 * declares the file as an input.
 */
class BrowserEditorRuntimeFieldGuardTest {

    /** One run-time field (`QUEUE_PROCESSOR.stopOnError`) and one field no run reads. */
    private val reach = mapOf(
        "QueueProcessorConfig.stopOnError" to Reach.Runtime("stopOnError"),
        "LiteRtConfig.temperature" to Reach.RoundTripOnly("no run reads it"),
    )

    /** An editor in which `stopOnError` passes every link; each argument replaces one link. */
    private fun editor(
        import: String = "stopOnError: jn.config?.stopOnError ?? null,",
        derive: String = "case 'QUEUE_PROCESSOR': cfg.stopOnError = flat.stopOnError !== false; break;",
        form: String =
            "case 'QUEUE_PROCESSOR':\n fields.push(cfgCheckbox('stopOnError', 'Stop', cfg.stopOnError)); break;",
        encode: String = "case 'QUEUE_PROCESSOR':\n env.stopOnError = cfg.stopOnError !== false; break;",
        decodeShared: String = "cfg.description = env.description;",
        decode: String = "case 'LITE_RT':\n cfg.temperature = env.temperature; break;",
        flat: String = "case 'QUEUE_PROCESSOR': flat.stopOnError = cfg.stopOnError !== false; break;",
        export: String = "stopOnError: flat.stopOnError ?? null,",
    ) = """
        function importFromJson(doc) {
            const flatIn = {
                $import
            };
        }
        function deriveRichFromFlat(typeId, flat, legacyRow = true) {
            switch (typeId) {
                $derive
                default: break;
            }
        }
        function renderFormFields(typeId, cfg) {
            switch (typeId) {
                $form
                default: break;
            }
        }
        function cfgPipelinePicker(cfg) { return cfg.targetPipelineId; }
        function encodeRichEnvelope(typeId, cfg) {
            switch (typeId) {
                $encode
                default: break;
            }
        }
        function decodeRichEnvelope(typeId, env, flat) {
            $decodeShared
            switch (typeId) {
                $decode
                default: break;
            }
        }
        function richToFlat(typeId, cfg) {
            const flat = { systemPrompt: '' };
            switch (typeId) {
                $flat
                default: break;
            }
        }
        function exportToJson() {
            return nodes.map(n => ({
                config: {
                    $export
                },
                nodeConfig: {},
            }));
        }
    """.trimIndent()

    private fun broken(html: String) = BrowserEditorRuntimeFieldGuard.brokenLinks(html, reach)

    @Test
    fun `given every link of a run-time field when checked then nothing is reported`() {
        assertTrue(broken(editor()).isEmpty())
    }

    @Test
    fun `given one link missing when checked then exactly that link is reported`() {
        val prefix = "QUEUE_PROCESSOR.stopOnError (stopOnError): "
        val cases = listOf(
            editor(import = "") to "importFromJson does not read config.stopOnError",
            editor(derive = "case 'QUEUE_PROCESSOR': break;") to "deriveRichFromFlat does not set cfg.stopOnError",
            editor(form = "case 'QUEUE_PROCESSOR': break;") to "renderFormFields offers no control for stopOnError",
            editor(encode = "case 'QUEUE_PROCESSOR': break;") to "encodeRichEnvelope does not write env.stopOnError",
            editor(flat = "case 'QUEUE_PROCESSOR': break;") to "richToFlat does not set flat.stopOnError",
            editor(export = "") to "exportToJson does not write config.stopOnError",
        )

        cases.forEach { (html, expected) -> assertEquals(expected, listOf(prefix + expected), broken(html)) }
    }

    @Test
    fun `given a comparison instead of an assignment when checked then the link counts as missing`() {
        val html = editor(flat = "case 'QUEUE_PROCESSOR': if (flat.stopOnError == null) {} break;")

        assertEquals(
            listOf("QUEUE_PROCESSOR.stopOnError (stopOnError): richToFlat does not set flat.stopOnError"),
            broken(html),
        )
    }

    @Test
    fun `given the decoder reads the field from the envelope when checked then it is reported`() {
        val expected = listOf(
            "QUEUE_PROCESSOR.stopOnError (stopOnError): decodeRichEnvelope reads env.stopOnError — " +
                "the run reads the flat copy",
        )

        assertEquals(
            expected,
            broken(editor(decode = "case 'QUEUE_PROCESSOR':\n cfg.stopOnError = env.stopOnError !== false; break;")),
        )
        assertEquals(expected, broken(editor(decodeShared = "cfg.stopOnError = env.stopOnError;")))
    }

    @Test
    fun `given a legacy-fallback field read only behind its absent-key check when checked then nothing is reported`() {
        val html = editor(
            decode = "case 'QUEUE_PROCESSOR':\n" +
                " if (legacyEnvelopeOnly('stopOnError')) cfg.stopOnError = env.stopOnError !== false; break;",
        )

        assertTrue(broken(html).isEmpty())
    }

    @Test
    fun `given a field outside the legacy list read behind the absent-key check when checked then it is reported`() {
        val reach = mapOf("ToolConfig.alwaysConfirm" to Reach.Runtime("alwaysConfirm"))
        val html = editor(
            import = "alwaysConfirm: jn.config?.alwaysConfirm === true,",
            derive = "case 'TOOL': cfg.alwaysConfirm = flat.alwaysConfirm === true; break;",
            form = "case 'TOOL':\n fields.push(cfgCheckbox('alwaysConfirm', 'Ask', cfg.alwaysConfirm)); break;",
            encode = "case 'TOOL':\n env.alwaysConfirm = cfg.alwaysConfirm === true; break;",
            decode = "case 'TOOL':\n" +
                " if (legacyEnvelopeOnly('alwaysConfirm')) cfg.alwaysConfirm = env.alwaysConfirm; break;",
            flat = "case 'TOOL': flat.alwaysConfirm = cfg.alwaysConfirm === true ? true : null; break;",
            export = "alwaysConfirm: flat.alwaysConfirm === true ? true : null,",
        )

        assertEquals(
            listOf(
                "TOOL.alwaysConfirm (alwaysConfirm): decodeRichEnvelope reads env.alwaysConfirm — " +
                    "the run reads the flat copy",
            ),
            BrowserEditorRuntimeFieldGuard.brokenLinks(html, reach),
        )
    }

    @Test
    fun `given a legacy-fallback field also read without the check when checked then it is reported`() {
        val html = editor(
            decode = "case 'QUEUE_PROCESSOR':\n" +
                " if (legacyEnvelopeOnly('stopOnError')) cfg.stopOnError = env.stopOnError !== false;\n" +
                " cfg.stopOnError = env.stopOnError; break;",
        )

        assertEquals(
            listOf(
                "QUEUE_PROCESSOR.stopOnError (stopOnError): decodeRichEnvelope reads env.stopOnError — " +
                    "the run reads the flat copy",
            ),
            broken(html),
        )
    }

    @Test
    fun `given a node type with no case in a function when checked then its links are reported, not a parse error`() {
        val html = editor(derive = "", flat = "")

        assertEquals(
            listOf(
                "QUEUE_PROCESSOR.stopOnError (stopOnError): deriveRichFromFlat does not set cfg.stopOnError",
                "QUEUE_PROCESSOR.stopOnError (stopOnError): richToFlat does not set flat.stopOnError",
            ),
            broken(html),
        )
    }

    @Test
    fun `given a form case handing the whole cfg to a helper when checked then the helper's reads count as controls`() {
        val reach = mapOf("PipelineConfig.targetPipelineId" to Reach.Runtime("targetPipelineId"))
        val html = editor(
            import = "targetPipelineId: jn.config?.targetPipelineId || '',",
            derive = "case 'PIPELINE': cfg.targetPipelineId = flat.targetPipelineId || ''; break;",
            form = "case 'PIPELINE':\n fields.push(cfgPipelinePicker(cfg)); break;",
            encode = "case 'PIPELINE':\n env.targetPipelineId = cfg.targetPipelineId ?? ''; break;",
            flat = "case 'PIPELINE': flat.targetPipelineId = cfg.targetPipelineId || ''; break;",
            export = "targetPipelineId: flat.targetPipelineId || null,",
        )

        assertTrue(BrowserEditorRuntimeFieldGuard.brokenLinks(html, reach).isEmpty())
    }

    @Test
    fun `given a field no run reads with no links at all when checked then it is not reported`() {
        val html = editor(decode = "")

        assertTrue(broken(html).none { it.startsWith("LITE_RT.") })
    }

    @Test(expected = IllegalStateException::class)
    fun `given an editor without decodeRichEnvelope when checked then it fails loudly`() {
        broken(editor().replace("function decodeRichEnvelope(", "function decodeEnvelope("))
    }

    @Test(expected = IllegalStateException::class)
    fun `given verdicts with no run-time field when checked then it fails loudly`() {
        BrowserEditorRuntimeFieldGuard.brokenLinks(editor(), mapOf("LiteRtConfig.topP" to Reach.RoundTripOnly("n/a")))
    }
}
