package app.knotwork.android.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot of what the app keeps in its preferences storage: the name and value type of every
 * DataStore key, and the names of the files the settings live in.
 *
 * **Why.** A setting is found again on the next start by its key name and type alone. Renaming a
 * key, changing its type, or renaming the file loses the value the user set, silently: the read
 * falls back to the default and nothing fails. That is the same class of defect as a downgrade that
 * recreates the database empty. Splitting the settings into sections moves every key to another
 * file, so the snapshot is pinned against the sources, not against the file a key used to live in.
 *
 * **What it reads.** The production sources with comments removed ([ProductionSources]). The eight
 * `…PreferencesKey(name)` factories are the only public way to make a key (`Preferences.Key`'s
 * constructor is internal in DataStore 1.2.1), so the census sees every key. Each call must take a
 * string literal, so a name built at run time cannot slip past it, and there is one DataStore file,
 * so two keys of the same name cannot live in different files.
 *
 * **Changing the snapshot.** Adding a key means adding it to [EXPECTED_KEYS] in the same change.
 * Removing or renaming one loses the user's value: keep reading the old name (and migrate it) or
 * accept the loss deliberately, in review. The secret-store slot names (`hugging_face_token`,
 * `mcp_auth_<sha256(url)>`, `mcp_headers_<sha256(url)>`) are pinned by `SettingsManagerTest`,
 * which asserts them by value.
 */
class PreferenceStorageSnapshotTest {

    @Test
    fun `every preference key is declared with a literal name`() {
        val offenders = ProductionSources.code
            .filter { (_, code) -> KEY_CALL.findAll(code).count() != KEY_DECLARATION.findAll(code).count() }
            .keys.toSortedSet()

        assertEquals(
            "a preference key whose name is not a string literal cannot be checked against the snapshot; " +
                "declare it as `<type>PreferencesKey(\"name\")`",
            sortedSetOf<String>(),
            offenders,
        )
    }

    @Test
    fun `the preference keys and their types match the snapshot`() {
        val declared = ProductionSources.code.values
            .flatMap { code -> KEY_DECLARATION.findAll(code).map { it.groupValues[2] to it.groupValues[1] }.toList() }
            .toSet()
        val expected = EXPECTED_KEYS.toList().toSet()

        assertEquals(
            "removed, renamed or retyped preference key — the user's stored value is lost on the next read. " +
                "Keep reading the old key (migrate it), or accept the loss deliberately in review",
            sortedSetOf<String>(),
            (expected - declared).mapTo(sortedSetOf()) { (name, type) -> "$name: $type" },
        )
        assertEquals(
            "new preference key — add it to EXPECTED_KEYS in the same change",
            sortedSetOf<String>(),
            (declared - expected).mapTo(sortedSetOf()) { (name, type) -> "$name: $type" },
        )
    }

    @Test
    fun `the settings live in the files existing installs already have`() {
        val dataStoreSites = ProductionSources.code.filterValues { DATA_STORE_FILE.containsMatchIn(it) }
        assertEquals(
            "one DataStore file: a second one would hold keys this snapshot cannot tell apart",
            listOf(APP_MODULE),
            dataStoreSites.keys.map { it.substringAfterLast('/') },
        )
        val appModule = dataStoreSites.values.single()

        val fileArgument = DATA_STORE_FILE.find(appModule)!!.groupValues
        assertEquals(
            "the DataStore file name",
            "agent_preferences",
            fileArgument[1].ifEmpty { appModule.constant(fileArgument[2]) },
        )
        val secretStore = SECRET_STORE.find(appModule)
        assertTrue("AppModule no longer provides the settings secret store as expected", secretStore != null)
        assertEquals(
            "the settings secret-store file name",
            "secure_settings_secrets",
            appModule.constant(secretStore!!.groupValues[1]),
        )
        assertEquals(
            "the settings secret-store Keystore alias",
            "knotwork.settings_secrets",
            appModule.constant(secretStore.groupValues[2]),
        )
    }

    @Test
    fun `the census recognises the declarations it claims to`() {
        // Keeps the census from passing vacuously on a regex that stopped matching.
        assertEquals(
            listOf("floatX" to "float"),
            KEY_DECLARATION.findAll("val T = androidx.datastore.preferences.core.floatPreferencesKey(\"floatX\")")
                .map { it.groupValues[2] to it.groupValues[1] }.toList(),
        )
        assertEquals(1, KEY_DECLARATION.findAll("stringSetPreferencesKey( \"s\" )").count())
        assertEquals(1, KEY_CALL.findAll("stringPreferencesKey(name)").count())
        assertEquals(0, KEY_DECLARATION.findAll("stringPreferencesKey(name)").count())
        assertEquals(0, KEY_DECLARATION.findAll("stringPreferencesKey(\"a\$b\")").count())
        assertEquals("NAME", DATA_STORE_FILE.find("preferencesDataStoreFile(NAME)")?.groupValues?.get(2))
        assertEquals("x", DATA_STORE_FILE.find("by preferencesDataStore(name = \"x\")")?.groupValues?.get(1))
    }

    /** The value of `const val [name] = "…"` in this source, or `null` when it is not declared here. */
    private fun String.constant(name: String): String? =
        Regex("""\bconst\s+val\s+${Regex.escape(name)}\s*=\s*"([^"]*)"""").find(this)?.groupValues?.get(1)

    private companion object {

        /** A preference-key factory call of any type, whatever its argument. */
        val KEY_CALL = Regex("""\b\w*PreferencesKey\s*\(""")

        /** A preference-key factory call with a literal, template-free name: type in group 1, name in 2. */
        val KEY_DECLARATION =
            Regex("""\b(boolean|int|long|float|double|string|stringSet|byteArray)PreferencesKey\(\s*"([^"$]*)"\s*\)""")

        /** Where a Preferences DataStore file is named: a literal name in group 1, or a constant in 2. */
        val DATA_STORE_FILE = Regex("""\bpreferencesDataStore(?:File)?\s*\(\s*(?:name\s*=\s*)?(?:"([^"]*)"|(\w+))""")

        /** The settings secret store's construction: file-name constant in group 1, key alias in 2. */
        val SECRET_STORE = Regex(
            """fun\s+provideSettingsSecretStore\b[\s\S]*?prefsName\s*=\s*(\w+)\s*,\s*keyAlias\s*=\s*(\w+)""",
        )

        const val APP_MODULE = "AppModule.kt"

        /**
         * Every preference key, by name, with its value type. Taken on 03.10.2026: 67 keys of the
         * settings plus the share-admission ledger, which lives in the same DataStore file.
         */
        val EXPECTED_KEYS = mapOf(
            "active_embedding_provider_id" to "string",
            "allowed_http_domains" to "string",
            // Holds MCP overrides too; the name is history, kept so stored overrides survive.
            "app_function_risk_overrides" to "string",
            "approved_cleartext_origins" to "stringSet",
            "audio_max_duration_sec" to "int",
            "auto_extract_enabled" to "boolean",
            "background_approval_window_hours" to "int",
            "block_destructive_tools" to "boolean",
            "block_network_from_local_model" to "boolean",
            "chat_history_compression_enabled" to "boolean",
            "chat_history_compression_threshold_tokens" to "int",
            "chat_history_live_window_size" to "int",
            "cloud_retry_base_delay_ms" to "long",
            "cloud_retry_max_attempts" to "int",
            "console_preferred_tab" to "string",
            "crash_reporting_enabled" to "boolean",
            "current_chat_session_id" to "string",
            "default_pipeline_id" to "string",
            "disabled_app_functions" to "stringSet",
            "disabled_mcp_tools" to "stringSet",
            "external_automation_enabled" to "boolean",
            "external_automation_pipeline_id" to "string",
            "has_completed_onboarding" to "boolean",
            "http_tool_max_response_bytes" to "long",
            // Legacy plain copy of the Hugging Face token, read once to migrate it to the secret store.
            "hugging_face_token" to "string",
            "is_first_launch" to "boolean",
            "last_init_backend_attempt" to "string",
            "last_reembed_provider_id" to "string",
            "last_test_probe_result" to "string",
            "local_backend_failure_streak" to "int",
            "local_model_backend" to "string",
            "max_context_length" to "int",
            "max_memory_chunks" to "int",
            // Legacy URL-only server list, read until the JSON form is first written.
            "mcp_server_urls" to "stringSet",
            "mcp_servers_json" to "string",
            "memory_compaction_age_days" to "int",
            "memory_compaction_enabled" to "boolean",
            "memory_last_compacted_at" to "long",
            "memory_recency_half_life_days" to "int",
            "memory_search_threshold" to "float",
            "memory_search_top_k" to "int",
            "memory_summary_default_limit" to "int",
            "pipeline_max_nesting_depth" to "int",
            "pipeline_max_steps" to "int",
            "pipeline_max_steps_background" to "int",
            "quick_settings_tile_pipeline_id" to "string",
            // Legacy approval switch, read while the typed policy has never been written.
            "requires_user_confirmation" to "boolean",
            "resume_max_age_hours" to "int",
            "run_max_tokens" to "int",
            "run_max_tokens_background" to "int",
            "scheduled_task_notifications" to "boolean",
            "share_admission_times" to "string",
            "share_reuse_session" to "boolean",
            "share_target_pipeline_id" to "string",
            "structured_output_max_repairs" to "int",
            "system_prompt_prefix" to "string",
            "temperature" to "float",
            "tool_approval_policy" to "string",
            "tool_call_timeout_ms" to "long",
            "top_k" to "int",
            "top_p" to "float",
            "trace_retention_max_age_days" to "int",
            "trace_retention_runs_per_session" to "int",
            "usage_telemetry_enabled" to "boolean",
            "verbose_memory_logging_enabled" to "boolean",
            "workspace_max_file_size_bytes" to "long",
            "workspace_max_total_bytes" to "long",
            "workspace_read_token_budget" to "int",
        )
    }
}
