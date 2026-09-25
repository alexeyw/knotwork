package app.knotwork.android.data.local

import android.content.Context
import app.knotwork.android.domain.services.DownloadedModelFile
import app.knotwork.android.domain.services.DownloadedModelFiles
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * [DownloadedModelFiles] over the directory `ResumableFileDownloader` writes models
 * into: the root of [Context.getExternalFilesDir] (`null` type). Read-only — it never
 * creates, moves or deletes a file.
 *
 * The same directory also holds partial downloads (`<name>.<hash>.part`), the optional
 * embedding-model override (`.tflite`) and, in debug builds, the soak-dump folder;
 * matching on the model extensions and on regular files keeps all three out.
 *
 * @property context Application context, used solely to locate the directory.
 */
class DownloadedModelFilesImpl @Inject constructor(@ApplicationContext private val context: Context) :
    DownloadedModelFiles {

    /** Dispatcher for the directory listing; swapped in unit tests. */
    internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

    override suspend fun list(): List<DownloadedModelFile> = withContext(dispatcher) {
        val directory = context.getExternalFilesDir(null) ?: return@withContext emptyList()
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.isModelFile() }
            // `absolutePath`, the form `ResumableFileDownloader` returns and the registry stores.
            .map { DownloadedModelFile(name = it.name, path = it.absolutePath, sizeBytes = it.length()) }
    }

    private fun File.isModelFile(): Boolean =
        DownloadedModelFiles.EXTENSIONS.any { extension -> name.endsWith(extension, ignoreCase = true) }
}
