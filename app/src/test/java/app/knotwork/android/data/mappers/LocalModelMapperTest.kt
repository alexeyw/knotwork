package app.knotwork.android.data.mappers

import app.knotwork.android.data.local.models.LocalModelEntity
import app.knotwork.android.domain.models.LocalModel
import app.knotwork.android.domain.models.ModelFileHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [LocalModelEntity] and [LocalModel] mapping extensions.
 */
class LocalModelMapperTest {

    @Test
    fun `toDomain maps entity to domain model correctly`() {
        val entity = LocalModelEntity(
            id = 1L,
            name = "Test Model",
            path = "/data/local/tmp/model.tflite",
            size = 1024L,
            isActive = true,
            supportsVision = true,
            supportsAudio = true,
        )

        val domain = entity.toDomain()

        assertEquals(1L, domain.id)
        assertEquals("Test Model", domain.name)
        assertEquals("/data/local/tmp/model.tflite", domain.path)
        assertEquals(1024L, domain.size)
        assertEquals(true, domain.isActive)
        assertEquals(true, domain.supportsVision)
        assertEquals(true, domain.supportsAudio)
    }

    @Test
    fun `toEntity maps domain model to entity correctly`() {
        val domain = LocalModel(
            id = 2L,
            name = "Another Model",
            path = "/path/to/another/model.bin",
            size = 2048L,
            isActive = false,
            supportsVision = true,
            supportsAudio = true,
        )

        val entity = domain.toEntity()

        assertEquals(2L, entity.id)
        assertEquals("Another Model", entity.name)
        assertEquals("/path/to/another/model.bin", entity.path)
        assertEquals(2048L, entity.size)
        assertEquals(false, entity.isActive)
        assertEquals(true, entity.supportsVision)
        assertEquals(true, entity.supportsAudio)
    }

    @Test
    fun `given all three hash columns when mapped both ways then the file hash round-trips`() {
        val hash = ModelFileHash(sha256 = "deadbeef", fileSizeBytes = 4_096L, fileModifiedAtMs = 1_700L)
        val domain = LocalModel(id = 3L, name = "m", path = "/m", size = 4_096L, isActive = false, fileHash = hash)

        val entity = domain.toEntity()

        assertEquals("deadbeef", entity.sha256)
        assertEquals(4_096L, entity.sha256FileSize)
        assertEquals(1_700L, entity.sha256FileModifiedAt)
        assertEquals(domain, entity.toDomain())
    }

    @Test
    fun `given a partly written stamp when mapped to domain then there is no file hash`() {
        // A hash without the stamp of the file it describes could vouch for a
        // replaced file, so a row missing any of the three columns reads as unhashed.
        val base = LocalModelEntity(id = 4L, name = "m", path = "/m", size = 1L, isActive = false)

        assertNull(base.copy(sha256 = "aa", sha256FileSize = 1L).toDomain().fileHash)
        assertNull(base.copy(sha256 = "aa", sha256FileModifiedAt = 2L).toDomain().fileHash)
        assertNull(base.copy(sha256FileSize = 1L, sha256FileModifiedAt = 2L).toDomain().fileHash)
        assertNull(base.toDomain().fileHash)
    }
}
