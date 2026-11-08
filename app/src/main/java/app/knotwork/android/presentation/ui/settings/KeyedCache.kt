package app.knotwork.android.presentation.ui.settings

/**
 * A value built on first use and again whenever its key changes.
 *
 * For text a ViewModel resolves from resources and keeps: the ViewModel
 * outlives the screen's re-creation in a new interface language, so a value
 * built once would keep the old language. Keyed by the configuration's locales,
 * it is rebuilt on the first use after the language changes.
 *
 * Not thread-safe; read it from one thread (the main thread, for a ViewModel).
 *
 * @param K The key's type; compared with `equals`.
 * @param V The value's type.
 * @property key Reads the current key on every [get].
 * @property build Builds the value for the current key.
 */
internal class KeyedCache<K, V>(private val key: () -> K, private val build: () -> V) {

    private var cached: Pair<K, V>? = null

    /**
     * The value for the current key, built now if the key changed since the last
     * build or nothing was built yet.
     *
     * @return The value.
     */
    fun get(): V {
        val current = key()
        cached?.let { (builtFor, value) -> if (builtFor == current) return value }
        return build().also { cached = current to it }
    }
}
