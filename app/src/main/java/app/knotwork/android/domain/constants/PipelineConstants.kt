package app.knotwork.android.domain.constants

/**
 * Limits shared by every path that stores a pipeline, so the library, the
 * editor toolbar and the import dialogs see one ceiling rather than one per
 * feature.
 */
object PipelineConstants {

    /**
     * Longest pipeline name any path stores.
     *
     * Enforced by create, rename, duplicate, save-as-preset and
     * load-from-preset, and — by truncation, so a long name does not fail an
     * otherwise good file — by the JSON importer. The importer is the path that
     * needed it most: a name comes from a file the user did not write, and it is
     * rendered in the library list, the editor toolbar and the import dialogs.
     */
    const val MAX_NAME_LENGTH: Int = 60

    /**
     * Longest pipeline id a pipeline file may carry.
     *
     * Every id the app mints is a UUID (36 characters) or a bundled preset's
     * snake_case slug; the ceiling leaves room for any other tool's scheme. The
     * importer refuses a longer id, and one carrying a line break, control or
     * bidi character, rather than rewriting it: an id is an identity other
     * pipelines, triggers and bindings refer to, and it is quoted back in
     * validation errors, so it has to be stored exactly as it reads.
     */
    const val MAX_ID_LENGTH: Int = 128

    /**
     * Longest node label a pipeline file may store.
     *
     * The label is the canvas card's title, the sheet's title, and — for a
     * TOOL node that resolves no tool — the name a tool result is attributed to
     * in the model's context. The editor has no ceiling of its own; this one
     * applies to imported files only, matching the name ceiling.
     */
    const val MAX_IMPORTED_LABEL_LENGTH: Int = 60

    /**
     * Largest pipeline or bundle file the importer reads, in bytes (8 MB).
     *
     * Decimal, because the platform's size formatter prints decimal units: the
     * refusal message and the documentation then name the same "8 MB".
     *
     * A bundle holds at most 50 pipelines, and the largest bundled preset is
     * 56 KB, so fifty of them come to about 2.8 MB: the ceiling leaves roughly
     * three times that. A larger file is refused before it is read into memory
     * rather than parsed until the heap runs out.
     */
    const val MAX_IMPORT_FILE_BYTES: Long = 8_000_000L
}
