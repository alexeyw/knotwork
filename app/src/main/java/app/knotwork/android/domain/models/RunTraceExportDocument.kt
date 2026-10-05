package app.knotwork.android.domain.models

/**
 * A rendered run trace export: the JSON document, how many trace records it
 * carries, and the run digest it states.
 *
 * Rendered before the export sheet opens, so the sheet can show the file's size
 * and the digest the file will hold, and the share or save action hands on
 * exactly these bytes.
 *
 * @property json The pretty-printed JSON document, ready to be written to a file.
 * @property recordCount How many trace records the document carries, across the
 *   whole run tree.
 * @property digest The run digest in the document, or `null` for a run recorded
 *   before runs kept a header.
 */
data class RunTraceExportDocument(val json: String, val recordCount: Int, val digest: String?) {

    /** Size of [json] in UTF-8 bytes — the size of the file it becomes. */
    val sizeBytes: Int = json.encodeToByteArray().size
}
