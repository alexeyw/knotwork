package app.knotwork.android.buildtools

import java.io.File

/**
 * Decides whether the NDK named by `android.ndkVersion` is installed where the
 * Android Gradle Plugin looks for it, with a strip tool inside.
 *
 * The app compiles no native code, but it packages five prebuilt libraries and
 * AGP strips their debug symbols with the NDK's `llvm-strip`. When that NDK is
 * missing, AGP does not download it and does not fail: it prints *"Unable to
 * strip the following libraries, packaging them as they are"* and ships them
 * unstripped. A release built on such a host is a different artefact from the
 * one CI publishes — measured on the same commit, 3 of 5 libraries differed —
 * and nothing louder than an informational line says so. Pinning the version
 * fixes *which* strip tool runs; this check makes its absence fail the release
 * build instead of changing it.
 */
object PinnedNdk {

    /**
     * Describes what is missing, or confirms nothing is.
     *
     * @param sdkDirectory The Android SDK root (`sdk.dir` / `ANDROID_HOME`).
     * @param version The pinned NDK revision, e.g. `28.2.13676358`.
     * @return `null` when `ndk/<version>` holds that revision and an `llvm-strip`;
     *   otherwise the reason, ready for a build failure.
     */
    fun problem(sdkDirectory: File, version: String): String? {
        val ndk = sdkDirectory.resolve("ndk/$version")
        val revision = ndk.resolve("source.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.substringBefore('=').trim() == "Pkg.Revision" }
            ?.substringAfter('=')
            ?.trim()
        val strip = ndk.resolve("toolchains/llvm/prebuilt").listFiles().orEmpty()
            .flatMap { host -> listOf("llvm-strip", "llvm-strip.exe").map { host.resolve("bin/$it") } }
            .firstOrNull { it.isFile }
        return when {
            revision == null -> "NDK $version is not installed in ${ndk.path}."
            // A pre-release carries a suffix (`30.0.14904198-beta1`); the numeric part is the pin.
            revision.substringBefore('-') != version ->
                "${ndk.path} holds NDK $revision, not the pinned $version."
            strip == null -> "NDK $version in ${ndk.path} has no llvm-strip under toolchains/llvm/prebuilt/*/bin."
            else -> null
        }
    }
}
