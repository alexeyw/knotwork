package app.knotwork.android.buildtools

/**
 * Reads the disassembly of a packaged dex (`dexdump -d` output) line by line for
 * the call sites of a method.
 *
 * A keep rule or a declaration says nothing about whether a method is still
 * **called**: `-assumenosideeffects` removes call sites and leaves the kept
 * declaration in place, so only the instructions show whether the removal
 * happened. The disassembly of a release APK runs to millions of lines, so the
 * checks are per line and the caller streams.
 */
object DexInvocationChecker {

    /** An `invoke-*` instruction of `dexdump -d`. */
    private val INVOKE = Regex("""\binvoke-[a-z/-]+\b""")

    /**
     * Reports whether [line] is an instruction invoking [method] on [ownerDescriptor].
     *
     * @param line One line of `dexdump -d` output.
     * @param ownerDescriptor The declaring type as a dex descriptor without the
     *   trailing `;`, e.g. `Lcom/example/Logger`.
     * @param method The method name.
     * @return `true` for such an instruction.
     */
    fun isInvocation(line: String, ownerDescriptor: String, method: String): Boolean =
        line.contains("$ownerDescriptor;.$method:") && INVOKE.containsMatchIn(line)

    /**
     * Reports whether [line] opens the definition of class [descriptor] — so a
     * check whose call-site class is missing can fail instead of passing over
     * nothing.
     *
     * @param line One line of `dexdump -d` output.
     * @param descriptor The class's dex descriptor without the trailing `;`.
     * @return `true` when the line defines that class.
     */
    fun isClassDefinition(line: String, descriptor: String): Boolean =
        line.contains("Class descriptor  : '$descriptor;'")
}
