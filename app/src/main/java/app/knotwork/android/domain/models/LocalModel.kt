package app.knotwork.android.domain.models

/**
 * Domain model representing a downloaded LLM model's metadata.
 * This class is independent of any persistence mechanism.
 *
 * @property id Unique identifier for the model record.
 * @property name Human-readable name of the model.
 * @property path Absolute path to the model file on the device.
 * @property size Size of the model file in bytes.
 * @property isActive Flag indicating if this model is currently active/selected.
 * @property supportsVision Whether this model can accept an image alongside the
 *   text prompt (a vision-capable LiteRT-LM model such as Gemma 4). Defaults to
 *   `false`: the LiteRT-LM 0.13.1 runtime exposes no capability probe (its
 *   `Capabilities` surface reports only speculative-decoding support), so vision
 *   support cannot be auto-detected from the `.litertlm` file and is instead a
 *   user-set flag on the model screen. The pre-flight send guard reads it to
 *   block an image message against a text-only active model before a run starts.
 * @property supportsAudio Whether this model can transcribe an audio clip (a
 *   multimodal LiteRT-LM model exposing an audio backend, such as Gemma-class
 *   speech models). Defaults to `false` for the same reason as [supportsVision]:
 *   the runtime offers no capability probe, so this is a user-set flag on the
 *   model screen. Voice input transcribes the clip with the active model *before*
 *   the pipeline runs; this flag gates that preprocessing step so a text-only
 *   model yields a calm "switch model" message instead of failing in native code.
 * @property fileHash The SHA-256 of the model file with the stamp of the file it
 *   was computed from, or `null` until the background pass has hashed it. Read
 *   through `LocalModelRepository.currentFileHash`, which also checks the stamp
 *   against the file on disk; the raw value may describe a file that has since
 *   been replaced.
 */
data class LocalModel(
    val id: Long = 0,
    val name: String,
    val path: String,
    val size: Long,
    val isActive: Boolean,
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val fileHash: ModelFileHash? = null,
)
