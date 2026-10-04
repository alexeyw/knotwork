package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.data.local.crypto.AeadCipher
import app.knotwork.android.data.local.crypto.KeystoreBackedPrefsStore
import app.knotwork.android.data.local.crypto.SecureValueUnreadableException
import app.knotwork.android.domain.constants.SettingsDefaults
import app.knotwork.android.domain.models.CloudProvider
import app.knotwork.android.domain.repositories.ApiKeyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Concrete implementation of [ApiKeyRepository] that securely stores API keys
 * in a [KeystoreBackedPrefsStore] — values encrypted with AES-GCM under a
 * dedicated, non-exportable Android Keystore key.
 *
 * **Recovery semantics — deliberately different from [EncryptedDbPassphraseProvider].**
 * A stored value that can no longer be decrypted (e.g. the Keystore key was lost during a
 * backup/restore) is treated as absent: the corrupt entry is removed and the getter returns
 * `null`. That is safe here because API keys are user-re-enterable — the worst outcome is the
 * user pasting their keys again. The database passphrase provider must never do this: its
 * secret cannot be re-derived, and destroying it would render the encrypted database
 * permanently unreadable.
 *
 * Earlier releases kept the keys in `EncryptedSharedPreferences` (deprecated upstream and
 * removed from this project without a data migration, as permitted by the pre-release storage
 * policy); a leftover legacy file is ignored — previously stored keys simply have to be
 * re-entered once.
 *
 * @property context The application context used to create the shared preferences.
 * @param cipher The AEAD boundary used to protect the stored values.
 */
@Singleton
class ApiKeyManager @Inject constructor(@ApplicationContext private val context: Context, cipher: AeadCipher) :
    ApiKeyRepository {

    private val store = KeystoreBackedPrefsStore(
        context = context,
        prefsName = PREFS_NAME,
        keyAlias = KEY_ALIAS,
        cipher = cipher,
    )

    /**
     * One observable per stored entry, keyed by entry name and created on first access from
     * the stored value, so a write through one reader is seen by every other reader of the
     * same entry.
     */
    private val entries = ConcurrentHashMap<String, MutableStateFlow<String?>>()

    private val ollamaContextFlow by lazy {
        MutableStateFlow(
            readOrNull(OLLAMA_CONTEXT_ENTRY)?.toIntOrNull() ?: SettingsDefaults.OLLAMA_CONTEXT_WINDOW_DEFAULT,
        )
    }

    override fun getApiKey(provider: CloudProvider): Flow<String?> = entry(apiKeyEntry(provider)).asStateFlow()

    override suspend fun setApiKey(provider: CloudProvider, key: String?) = write(apiKeyEntry(provider), key)

    override fun getModel(provider: CloudProvider): Flow<String?> = entry(modelEntry(provider)).asStateFlow()

    override suspend fun setModel(provider: CloudProvider, model: String?) = write(modelEntry(provider), model)

    override fun getBaseUrl(provider: CloudProvider): Flow<String?> = entry(baseUrlEntry(provider)).asStateFlow()

    override suspend fun setBaseUrl(provider: CloudProvider, url: String?) = write(baseUrlEntry(provider), url)

    override fun getOllamaContextWindowSize(): Flow<Int> = ollamaContextFlow.asStateFlow()

    override suspend fun setOllamaContextWindowSize(size: Int) {
        store.putString(OLLAMA_CONTEXT_ENTRY, size.toString(), synchronous = false)
        ollamaContextFlow.value = size
    }

    /** The observable of the entry [name], seeded from the store the first time it is asked for. */
    private fun entry(name: String): MutableStateFlow<String?> =
        entries.computeIfAbsent(name) { MutableStateFlow(readOrNull(it)) }

    /** Persists [value] under [name] (removing the entry for `null`) and publishes it. */
    private fun write(name: String, value: String?) {
        saveString(name, value)
        entry(name).value = value
    }

    /**
     * Reads a stored value, applying the re-enterable-secret recovery policy: an entry that
     * cannot be decrypted is dropped and reported as absent instead of propagating the error.
     */
    private fun readOrNull(key: String): String? = try {
        store.getString(key)
    } catch (e: SecureValueUnreadableException) {
        Timber.e(e, "Stored API-key entry '%s' is unreadable; treating it as unset.", key)
        store.remove(key)
        null
    }

    private fun saveString(key: String, value: String?) {
        if (value == null) {
            store.remove(key)
        } else {
            store.putString(key, value, synchronous = false)
        }
    }

    private companion object {
        /** Name of the [KeystoreBackedPrefsStore] preferences file holding the API keys. */
        const val PREFS_NAME = "secure_api_keys_v2"

        /** Android Keystore alias of the AEAD key dedicated to the API-key store. */
        const val KEY_ALIAS = "knotwork.api_keys"

        /** Entry holding the Ollama context window, the one slot that is not per provider. */
        const val OLLAMA_CONTEXT_ENTRY = "ollama_context"

        /**
         * Entry names are derived from the provider's wire id, which can never change either.
         * Every name shipped before the store was keyed by provider already had this shape
         * (`openai_api_key`, `deepseek_model`, `ollama_base_url`, …), so the derivation reads
         * existing installs' values without a migration; `ApiKeyManagerTest` pins the shipped
         * names.
         */
        fun apiKeyEntry(provider: CloudProvider): String = "${provider.id}_api_key"

        /** Entry name of [provider]'s chosen model id. */
        fun modelEntry(provider: CloudProvider): String = "${provider.id}_model"

        /** Entry name of [provider]'s server address. */
        fun baseUrlEntry(provider: CloudProvider): String = "${provider.id}_base_url"
    }
}
