package app.knotwork.android.architecture

/**
 * Every preference key the production code declares, read from the sources: the name and value type
 * of each `<type>PreferencesKey("name")` call ([ProductionSources], comments removed).
 *
 * The eight `…PreferencesKey(name)` factories are the only public way to make a key
 * (`Preferences.Key`'s constructor is internal in DataStore 1.2.1), so the census sees every key
 * declared with a literal name; [PreferenceStorageSnapshotTest] fails on any other declaration. Shared
 * by that snapshot and by the reset-coverage test, which must see keys wherever a settings section
 * declares them.
 */
internal object PreferenceKeyCensus {

    /** A preference-key factory call of any type, whatever its argument. */
    val KEY_CALL = Regex("""\b\w*PreferencesKey\s*\(""")

    /** A preference-key factory call with a literal, template-free name: type in group 1, name in 2. */
    val KEY_DECLARATION =
        Regex("""\b(boolean|int|long|float|double|string|stringSet|byteArray)PreferencesKey\(\s*"([^"$]*)"\s*\)""")

    /** Every declared key as `name to type`, e.g. `"top_k" to "int"`. Computed once per test JVM. */
    val declared: Set<Pair<String, String>> by lazy {
        ProductionSources.code.values
            .flatMap { code -> KEY_DECLARATION.findAll(code).map { it.groupValues[2] to it.groupValues[1] }.toList() }
            .toSet()
    }

    /** The names of every declared key. */
    val names: Set<String> get() = declared.mapTo(mutableSetOf()) { it.first }
}
