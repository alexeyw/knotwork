package app.knotwork.android.domain.models

/**
 * Outcome of parsing a memory export JSON document produced by another
 * instance of this application (or an external tool that honours the schema).
 *
 * Mirrors the pipeline / prompt-preset import outcomes: the parsing layer
 * produces this value before any persistence happens, so the UI can decide
 * whether to load immediately, warn first, or abort.
 *
 * @see app.knotwork.android.domain.memoryio.MemoryJsonSerializer
 * @see app.knotwork.android.domain.usecases.MemoryImportUseCase
 */
sealed class MemoryImportOutcome {

    /**
     * The JSON parsed cleanly and its `schemaVersion` matches what this build
     * expects. The document is ready to be imported as-is.
     *
     * @property document Fully parsed export document.
     */
    data class Success(val document: MemoryExportDocument) : MemoryImportOutcome()

    /**
     * The JSON parsed but its `schemaVersion` differs from the version this
     * build understands. The document is still produced on a best-effort basis
     * (unknown fields dropped), but the UI should surface a warning before
     * importing because some data may not round-trip.
     *
     * @property document Best-effort parsed document.
     * @property foundVersion The `schemaVersion` value read from the file.
     * @property expectedVersion The version this build expects.
     */
    data class SchemaMismatch(val document: MemoryExportDocument, val foundVersion: Int, val expectedVersion: Int) :
        MemoryImportOutcome()

    /**
     * Parsing failed irrecoverably (malformed JSON, missing required fields).
     *
     * @property problem Why, for the screen to word in the interface language.
     */
    data class Failure(val problem: ImportProblem) : MemoryImportOutcome()
}
