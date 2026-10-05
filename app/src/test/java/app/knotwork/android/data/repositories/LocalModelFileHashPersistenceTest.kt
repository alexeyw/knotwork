package app.knotwork.android.data.repositories

import androidx.room.Room
import app.knotwork.android.data.local.AppDatabase
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileHash
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The model file hash through [LocalModelRepositoryImpl], a real (in-memory) Room
 * database and real files: a stored hash vouches for a file only while the file
 * still has the stamp the hash was computed at.
 */
@RunWith(RobolectricTestRunner::class)
class LocalModelFileHashPersistenceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: AppDatabase
    private lateinit var repository: LocalModelRepositoryImpl

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LocalModelRepositoryImpl(database.localModelDao(), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `given a registered file never hashed when read then it needs a hash and has no current one`() = runTest {
        val file = modelFile("a.litertlm", "weights")
        val id = repository.insertModel(model(file))

        assertEquals(listOf(id), repository.modelsNeedingFileHash().map { it.id })
        assertNull(repository.currentFileHash(file.path))
    }

    @Test
    fun `given a recorded hash for an unchanged file when read then it is current and needs no pass`() = runTest {
        val file = modelFile("a.litertlm", "weights")
        val id = repository.insertModel(model(file))

        repository.recordFileHash(id, stampOf(file, sha = "abc123"))

        assertEquals("abc123", repository.currentFileHash(file.path))
        assertTrue(repository.modelsNeedingFileHash().isEmpty())
    }

    @Test
    fun `given a hashed file when it is rewritten then the hash is stale and the file needs a pass`() = runTest {
        val file = modelFile("a.litertlm", "weights")
        val id = repository.insertModel(model(file))
        repository.recordFileHash(id, stampOf(file, sha = "abc123"))

        // A re-download over the same path: other bytes, another modification time.
        file.writeText("other weights")
        file.setLastModified(file.lastModified() + 60_000L)

        assertNull(repository.currentFileHash(file.path))
        assertEquals(listOf(id), repository.modelsNeedingFileHash().map { it.id })
    }

    @Test
    fun `given a hashed file when it is deleted then it has no current hash and nothing to hash`() = runTest {
        val file = modelFile("a.litertlm", "weights")
        val id = repository.insertModel(model(file))
        repository.recordFileHash(id, stampOf(file, sha = "abc123"))

        file.delete()

        assertNull(repository.currentFileHash(file.path))
        assertTrue(repository.modelsNeedingFileHash().isEmpty())
    }

    @Test
    fun `given no row for the path when read then there is no current hash`() = runTest {
        assertNull(repository.currentFileHash(File(folder.root, "unknown.litertlm").path))
    }

    @Test
    fun `given a model row when its hash is recorded then the other columns are untouched`() = runTest {
        val file = modelFile("a.litertlm", "weights")
        val id = repository.insertModel(model(file).copy(isActive = true, supportsVision = true))

        repository.recordFileHash(id, stampOf(file, sha = "abc123"))

        val row = repository.getAllModels().first().single()
        assertEquals(true, row.isActive)
        assertEquals(true, row.supportsVision)
        assertEquals(file.path, row.path)
    }

    @Test
    fun `given a hashed unchanged file when re-registered then its hash survives the update`() = runTest {
        // Registration rewrites the whole row with `@Update`; the hash must travel in it.
        val file = modelFile("a.litertlm", "weights")
        val id = repository.insertModel(model(file))
        repository.recordFileHash(id, stampOf(file, sha = "abc123"))

        val stored = repository.findByPath(file.path)!!
        repository.updateModel(stored.copy(name = "renamed.litertlm"))

        assertEquals("abc123", repository.currentFileHash(file.path))
    }

    private fun modelFile(name: String, content: String): File = folder.newFile(name).apply { writeText(content) }

    private fun model(file: File) =
        LocalModel(name = file.name, path = file.path, size = file.length(), isActive = false)

    private fun stampOf(file: File, sha: String) =
        ModelFileHash(sha256 = sha, fileSizeBytes = file.length(), fileModifiedAtMs = file.lastModified())
}
