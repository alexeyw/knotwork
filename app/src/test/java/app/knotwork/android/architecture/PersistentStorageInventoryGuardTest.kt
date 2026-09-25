package app.knotwork.android.architecture

import android.content.Context
import androidx.datastore.preferences.preferencesDataStoreFile
import app.knotwork.android.data.local.AgentWorkspaceImpl
import app.knotwork.android.data.local.AttachmentStoreImpl
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every place the app keeps data between runs is in [StorageRoot], with two decisions
 * written down: nothing of it leaves the device through Android backup or device
 * transfer, and whether the recovery wipe (*Erase data*) erases it.
 *
 * **Why.** The backup rules once excluded the one plain directory they reasoned about
 * (`datastore/`) and left the two nobody listed — the attachments (user photos) and the
 * agent workspace — in the default backup set, while PRIVACY.md said that content stays
 * on the device. The same unlisted directories survived the wipe whose button said
 * *Erase everything*. Both were a directory the rules and the wipe did not know about;
 * this guard makes the inventory the list both are checked against.
 *
 * **What it reads.** The manifest and `res/xml/data_extraction_rules.xml` (the only
 * rules file a `minSdk` 34 device reads; `fullBackupContent` applies up to API 30), the
 * production sources with comments removed ([ProductionSources]), and the recovery-wipe
 * use case. A storage API reached some way the census does not match (a path string
 * built by hand) is not seen — which is why the rules also exclude every domain whole,
 * so what the census misses is still not backed up. The census is by file: a second root
 * reached from a file already in [KNOWN_SITES] passes it, except under `filesDir`, where
 * every line must name an inventoried directory.
 *
 * **Inputs.** Both XML files live under `src/main`, so no Test-task input is declared:
 * measured, removing one rule or flipping `allowBackup` re-runs the task (and fails
 * this guard). An edit only to an XML comment does not — the compiled resource is
 * unchanged — and nothing here reads comments.
 */
class PersistentStorageInventoryGuardTest {

    @Test
    fun `cloud backup is off and the legacy rules file is gone`() {
        val application = manifestApplication()

        assertEquals(
            "android:allowBackup must be false: nothing the app stores is usable on another device",
            "false",
            application.getAttribute("android:allowBackup"),
        )
        assertFalse(
            "android:fullBackupContent applies only up to API 30, below minSdk; a second rules file drifts",
            application.hasAttribute("android:fullBackupContent"),
        )
        assertFalse(
            "res/xml/backup_rules.xml is read by no supported device",
            File(ProductionSources.moduleDirectory(), "src/main/res/xml/backup_rules.xml").exists(),
        )
        assertEquals("@xml/data_extraction_rules", application.getAttribute("android:dataExtractionRules"))
    }

    @Test
    fun `cloud backup and device transfer exclude every storage domain whole`() {
        // allowBackup="false" does not stop device-to-device transfer on every device,
        // so the transfer section is what actually keeps the data at home.
        SECTIONS.forEach { section ->
            val rules = rulesOf(section)
            assertEquals("<$section> must not include anything", emptyList<Rule>(), rules.filter { it.include })
            assertEquals(
                "<$section> must exclude every domain from its root",
                emptySet<String>(),
                ALL_DOMAINS - rules.filter { !it.include && it.path == "." }.map { it.domain }.toSet(),
            )
        }
    }

    @Test
    fun `every plain-text root is also excluded by its own name`() {
        // Belt and braces for the directories whose content is readable as is: if a
        // platform ever matched the domain-root rule differently, these still hold.
        val named = StorageRoot.entries.filter { it.plaintextPath != null }
        assertTrue("the inventory lists no plain-text root — the parse is broken", named.isNotEmpty())
        SECTIONS.forEach { section ->
            val excluded = rulesOf(section).filter { !it.include }.map { it.domain to it.path }.toSet()
            named.forEach { root ->
                assertTrue(
                    "<$section> does not exclude ${root.domain}/${root.plaintextPath} (${root.name})",
                    (root.domain to root.plaintextPath) in excluded,
                )
            }
        }
    }

    @Test
    fun `the plain-text root names are the ones production code uses`() {
        val filesDir = File("/data/user/0/app.knotwork.android/files")
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { this@mockk.filesDir } returns filesDir
        }
        val dataStoreDir = context.preferencesDataStoreFile("agent_preferences").parentFile!!

        assertEquals("${dataStoreDir.relativeTo(filesDir).path}/", StorageRoot.DATASTORE.plaintextPath)
        assertEquals("${AttachmentStoreImpl.ATTACHMENTS_DIR}/", StorageRoot.ATTACHMENTS.plaintextPath)
        assertEquals("${AgentWorkspaceImpl.WORKSPACE_DIR_NAME}/", StorageRoot.WORKSPACE.plaintextPath)
    }

    @Test
    fun `every production use of a storage root is in the inventory`() {
        val unlisted = ProductionSources.code
            .filterValues { STORAGE_API.containsMatchIn(it) }
            .keys
            .filter { path -> KNOWN_SITES.keys.none { path.endsWith(it) } }

        assertEquals(
            "code reaches app storage from a file the inventory does not know. Add the root to StorageRoot " +
                "with its backup and wipe decision (and to data_extraction_rules.xml when it is plain text), " +
                "then list the file in KNOWN_SITES.",
            emptyList<String>(),
            unlisted,
        )
        val stale = KNOWN_SITES.keys.filter { suffix -> ProductionSources.code.keys.none { it.endsWith(suffix) } }
        assertEquals("KNOWN_SITES names a file that no longer exists", emptyList<String>(), stale)
    }

    @Test
    fun `every use of filesDir names an inventoried directory`() {
        val offenders = ProductionSources.code.values.flatMap(::filesDirOffendersIn)

        assertEquals(
            "a directory under filesDir is built without an inventoried name — it would be backed up by no " +
                "name of its own and survive the wipe unnoticed",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `the recovery wipe reaches every root it is recorded as erasing`() {
        val wipe = ProductionSources.code.entries
            .single { it.key.endsWith(WIPE_USE_CASE) }
            .value

        assertEquals(
            "every root needs exactly one wipe decision: erasedBy or kept",
            emptyList<StorageRoot>(),
            StorageRoot.entries.filter { (it.erasedBy == null) == (it.kept == null) },
        )
        StorageRoot.entries.filter { it.erasedBy != null }.forEach { root ->
            assertTrue(
                "${root.name} is recorded as erased by the recovery wipe, but $WIPE_USE_CASE never calls " +
                    root.erasedBy,
                Regex("""\b""" + Regex.escape(root.erasedBy.orEmpty()) + """\b""").containsMatchIn(wipe),
            )
        }
    }

    @Test
    fun `the census recognises the sites it claims to`() {
        // Keeps the census rules from passing vacuously.
        val sites = ProductionSources.code.values.count { STORAGE_API.containsMatchIn(it) }
        assertTrue("only $sites files with a storage API recognised", sites >= KNOWN_SITES.size)
        assertTrue(STORAGE_API.containsMatchIn("File(context.filesDir, \"exports\")"))
        assertTrue(STORAGE_API.containsMatchIn("context.getDir(\"webview\", 0)"))
        assertTrue(STORAGE_API.containsMatchIn("context.openFileOutput(\"x\", 0)"))
        assertTrue(STORAGE_API.containsMatchIn("appContext.dataStoreFile(\"settings.pb\")"))
        assertFalse(STORAGE_API.containsMatchIn("File(context.cacheDir, TransientCacheDirectory.X.dirName)"))
        assertEquals(1, filesDirOffendersIn("val dir = File(context.filesDir, \"exports\")").size)
        assertEquals(0, filesDirOffendersIn("val dir = File(context.filesDir, ATTACHMENTS_DIR)").size)
    }

    /** The lines of [code] that use `filesDir` without naming an inventoried directory. */
    private fun filesDirOffendersIn(code: String): List<String> = code.lines()
        .filter { line -> FILES_DIR_USE.containsMatchIn(line) && FILES_DIR_NAMES.none { line.contains(it) } }
        .map { it.trim() }

    /** The `<application>` element of the main manifest. */
    private fun manifestApplication(): Element {
        val manifest = File(ProductionSources.moduleDirectory(), "src/main/AndroidManifest.xml")
        return parse(manifest).getElementsByTagName("application").item(0) as Element
    }

    /** The include/exclude rules of one `data_extraction_rules.xml` section. */
    private fun rulesOf(section: String): List<Rule> {
        val file = File(ProductionSources.moduleDirectory(), "src/main/res/xml/data_extraction_rules.xml")
        val sectionElement = parse(file).getElementsByTagName(section).item(0) as? Element
            ?: error("data_extraction_rules.xml has no <$section> section")
        return listOf("include", "exclude").flatMap { tag ->
            val nodes = sectionElement.getElementsByTagName(tag)
            (0 until nodes.length).map { i ->
                val element = nodes.item(i) as Element
                Rule(
                    include = tag == "include",
                    domain = element.getAttribute("domain"),
                    path = element.getAttribute("path"),
                )
            }
        }
    }

    private fun parse(file: File) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    /** One `<include>` / `<exclude>` element. */
    private data class Rule(val include: Boolean, val domain: String, val path: String)

    /**
     * Every place the app keeps data between runs.
     *
     * @property domain The backup domain the root lives in.
     * @property plaintextPath The root's path inside [domain] when its content is readable
     *   as is (not sealed under a device-bound key) — such a root is also excluded by this
     *   name. `null` for encrypted or public content, covered by the domain-wide rule.
     * @property erasedBy The type the recovery wipe calls to erase the root, or `null` when
     *   the wipe keeps it — and then [kept] says why.
     * @property kept Why the wipe keeps the root; `null` when it erases it.
     */
    private enum class StorageRoot(
        val domain: String,
        val plaintextPath: String?,
        val erasedBy: String?,
        val kept: String?,
    ) {
        /** Chats, memory, pipelines, run history, triggers, the model registry — SQLCipher. */
        DATABASE(domain = "database", plaintextPath = null, erasedBy = "DatabaseResetService", kept = null),

        /** Settings, MCP server list, bindings — plain DataStore. */
        DATASTORE(
            domain = "file",
            plaintextPath = "datastore/",
            erasedBy = null,
            kept = "the wipe escapes an unreadable database; settings still work, and the dialog says they are kept",
        ),

        /** Downscaled copies of every image attached to a chat — plain JPEGs. */
        ATTACHMENTS(domain = "file", plaintextPath = "attachments/", erasedBy = "AttachmentStore", kept = null),

        /** Files the agent wrote and the user imported — plain files. */
        WORKSPACE(domain = "file", plaintextPath = "agent_workspace/", erasedBy = "AgentWorkspace", kept = null),

        /**
         * The three Keystore-backed secret stores. The database passphrase is erased with the
         * database; API keys, the Hugging Face token and MCP credentials are kept.
         */
        SECRET_STORES(
            domain = "sharedpref",
            plaintextPath = null,
            erasedBy = null,
            kept = "saved keys still work after the database is replaced, and the dialog says they are kept",
        ),

        /** Downloaded model files — public content from the model hub. */
        DOWNLOADED_MODELS(
            domain = "external",
            plaintextPath = null,
            erasedBy = null,
            kept = "public downloads of gigabytes, not user data; registered again at start after the wipe",
        ),

        /** Trigger-journal soak dumps of the debug build type only — never in a release. */
        DEBUG_SOAK_DUMPS(domain = "external", plaintextPath = null, erasedBy = null, kept = "debug builds only"),
    }

    private companion object {
        /** The two sections of `data_extraction_rules.xml`. */
        val SECTIONS = listOf("cloud-backup", "device-transfer")

        /** Every domain the platform accepts in a data-extraction rule. */
        val ALL_DOMAINS = setOf(
            "root", "file", "database", "sharedpref", "external",
            "device_root", "device_file", "device_database", "device_sharedpref",
        )

        /** The recovery-wipe use case, by path suffix. */
        const val WIPE_USE_CASE = "/domain/usecases/ResetLockedDatabaseUseCase.kt"

        /**
         * APIs that reach app-private or app-specific persistent storage. Cache directories
         * are the registry's business (`TransientCacheDirectoryGuardTest`), not listed here.
         */
        val STORAGE_API = Regex(
            """\bfilesDir\b|preferencesDataStoreFile|\bdataStoreFile\(|getSharedPreferences\(|""" +
                """databaseBuilder\(|getExternalFilesDir\(|\bexternalFilesDir\b|\bgetDir\(|openFileOutput\(|""" +
                """noBackupFilesDir|\bdataDir\b|getDatabasePath\(|deleteSharedPreferences\(|deleteDatabase\(""",
        )

        /** A use of `filesDir`, with or without a receiver. */
        val FILES_DIR_USE = Regex("""\bfilesDir\b""")

        /** Names a `filesDir` use must carry: the inventoried directories' production constants. */
        val FILES_DIR_NAMES = listOf("ATTACHMENTS_DIR", "WORKSPACE_DIR_NAME")

        /** Every production file that reaches a storage root, by path suffix, with the root. */
        val KNOWN_SITES = mapOf(
            "/di/AppModule.kt" to "DATASTORE (preferencesDataStoreFile) and DATABASE (Room.databaseBuilder)",
            "/data/local/AttachmentStoreImpl.kt" to "ATTACHMENTS",
            "/data/local/AgentWorkspaceImpl.kt" to "WORKSPACE",
            "/data/local/EncryptedDbPassphraseProvider.kt" to "DATABASE existence check; SECRET_STORES legacy file",
            "/data/local/DatabaseResetServiceImpl.kt" to "DATABASE, deleted by the wipe",
            "/data/local/crypto/KeystoreBackedPrefsStore.kt" to "SECRET_STORES",
            "/data/network/ResumableFileDownloader.kt" to "DOWNLOADED_MODELS",
            "/data/local/DownloadedModelFilesImpl.kt" to "DOWNLOADED_MODELS (read-only listing)",
            "/data/engine/MediaPipeTextEmbeddingEngine.kt" to "DOWNLOADED_MODELS (the embedding model)",
            "/debug/TriggerJournalDumpReceiver.kt" to "DEBUG_SOAK_DUMPS",
        )
    }
}
