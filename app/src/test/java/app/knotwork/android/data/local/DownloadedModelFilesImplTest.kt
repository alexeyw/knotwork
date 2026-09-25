package app.knotwork.android.data.local

import app.knotwork.android.domain.services.DownloadedModelFile
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * [DownloadedModelFilesImpl] against the real external-files directory: what it
 * lists is exactly the model files the downloader writes there, and none of the
 * directory's other occupants.
 */
@RunWith(RobolectricTestRunner::class)
class DownloadedModelFilesImplTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var directory: File

    @Before
    fun setup() {
        directory = context.getExternalFilesDir(null)!!
        directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
    }

    @Test
    fun `given the directory's usual occupants when listed then only finished model files come back`() = runTest {
        file("gemma-4-E2B-it.litertlm", bytes = 5)
        file("custom-model.task", bytes = 3)
        file("experimental.GGUF", bytes = 2)
        // A partial download, the embedding override and the debug soak folder.
        file("gemma-4-E4B-it.litertlm.3fa9c2.part", bytes = 1)
        file("universal_sentence_encoder.tflite", bytes = 1)
        File(directory, "soak").mkdirs()
        File(directory, "soak/run.litertlm").writeBytes(ByteArray(1))

        val listed = DownloadedModelFilesImpl(context).apply { dispatcher = StandardTestDispatcher(testScheduler) }
            .list()
            .sortedBy { it.name }

        assertEquals(
            listOf(
                DownloadedModelFile("custom-model.task", File(directory, "custom-model.task").path, 3),
                DownloadedModelFile("experimental.GGUF", File(directory, "experimental.GGUF").path, 2),
                DownloadedModelFile("gemma-4-E2B-it.litertlm", File(directory, "gemma-4-E2B-it.litertlm").path, 5),
            ),
            listed,
        )
    }

    @Test
    fun `given the path form the downloader records when listed then the paths match it`() = runTest {
        // The downloader stores `File(getExternalFilesDir(null), name).path`; a
        // different form would make every registered model look unregistered.
        file("gemma.litertlm", bytes = 1)

        val listed = DownloadedModelFilesImpl(context).apply {
            dispatcher = StandardTestDispatcher(testScheduler)
        }.list()

        assertEquals(File(context.getExternalFilesDir(null), "gemma.litertlm").path, listed.single().path)
    }

    private fun file(name: String, bytes: Int) = File(directory, name).writeBytes(ByteArray(bytes))
}
