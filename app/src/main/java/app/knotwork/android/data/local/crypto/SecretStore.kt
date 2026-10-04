package app.knotwork.android.data.local.crypto

/**
 * Minimal key-value store for secrets consumed by the settings section stores
 * (`GenerationSettingsStore`, `ToolSettingsStore`).
 *
 * Extracted as an interface so the encrypted, Keystore-backed implementation
 * ([KeystoreBackedPrefsStore]) can be injected in production and substituted by
 * an in-memory fake in unit tests — the settings persist the Hugging Face token and
 * per-server MCP credentials through this seam, neither of which may ever touch
 * plain DataStore.
 *
 * A value read that is present but undecryptable surfaces as a
 * [SecureValueUnreadableException]; an absent value returns `null`.
 */
interface SecretStore {

    /**
     * Reads and decrypts the value stored under [key].
     *
     * @param key The entry to read.
     * @return The plaintext value, or `null` when no value is stored.
     * @throws SecureValueUnreadableException When a value is present but cannot
     *   be decoded or does not pass authenticated decryption.
     */
    fun getString(key: String): String?

    /**
     * Encrypts and stores [value] under [key].
     *
     * @param key The entry to write.
     * @param value The plaintext value to encrypt and persist.
     * @param synchronous When `true`, the write is committed before returning so
     *   a subsequent read (or a crash) observes the new value; when `false` the
     *   write may be applied asynchronously.
     */
    fun putString(key: String, value: String, synchronous: Boolean = false)

    /**
     * Removes any value stored under [key].
     *
     * @param key The entry to delete.
     */
    fun remove(key: String)
}
