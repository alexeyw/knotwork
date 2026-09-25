package app.knotwork.android.domain.models

import app.knotwork.android.domain.constants.ModelDiscoveryConstants

/**
 * A model link the user pasted, checked before anything is downloaded.
 *
 * The on-device engine loads `.litertlm` bundles only. A link to any other file used
 * to download in full — gigabytes — and fail only when the engine tried to load it.
 * The two places that take a pasted link also named the file differently: the Models
 * screen kept the link's tail as it was, query string included
 * (`model.litertlm?download=true`, which model rediscovery then cannot see), and
 * onboarding appended `.litertlm` to whatever the tail was (`model.task.litertlm`).
 * Both now go through [parse].
 */
sealed interface CustomModelLink {

    /**
     * The link names a `.litertlm` file.
     *
     * @property url The link to download, trimmed of surrounding whitespace.
     * @property fileName The file name to save it under: the last segment of the
     *   link's path, without its query string or fragment.
     */
    data class Accepted(val url: String, val fileName: String) : CustomModelLink

    /** Nothing but whitespace was entered. */
    data object Blank : CustomModelLink

    /**
     * The link's path does not end in a `.litertlm` file, so the engine could not load
     * what it downloads. Refused before the download starts.
     */
    data object NotLitertlm : CustomModelLink

    /** Parses a pasted link. */
    companion object {

        /**
         * Checks [input] and derives the name its file is saved under.
         *
         * Only the path counts: `…/model.litertlm?download=true` is accepted as
         * `model.litertlm`, while `…/download?file=model.litertlm` is refused — its
         * file is `download`. A trailing slash is ignored. A bare file name with no
         * slash is taken as its own last segment; whether it downloads is the
         * downloader's answer, not this check's.
         *
         * @param input The text of the link field.
         * @return [Accepted] with the trimmed link and the file name, [Blank] for an
         *   empty field, or [NotLitertlm].
         */
        fun parse(input: String): CustomModelLink {
            val url = input.trim()
            if (url.isEmpty()) return Blank
            val path = url.substringBefore('#').substringBefore('?').trimEnd('/')
            val fileName = path.substringAfterLast('/')
            val extension = ModelDiscoveryConstants.LITERTLM_EXTENSION
            val isLitertlm = fileName.length > extension.length && fileName.endsWith(extension, ignoreCase = true)
            return if (isLitertlm) Accepted(url = url, fileName = fileName) else NotLitertlm
        }
    }
}
