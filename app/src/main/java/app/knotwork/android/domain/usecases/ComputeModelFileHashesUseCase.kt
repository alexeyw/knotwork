package app.knotwork.android.domain.usecases

import app.knotwork.android.domain.repositories.LocalModelRepository
import app.knotwork.android.domain.services.ModelFileHasher
import javax.inject.Inject

/**
 * Hashes every installed model file that has no current SHA-256 and stores the
 * result in the registry.
 *
 * The hash identifies the model a run used, so a recorded run can later be
 * checked against the same file. It is computed here, once per file, instead of
 * on every run: reading a multi-gigabyte file takes seconds. The pass covers
 * models installed before hashes existed as well as freshly registered ones and
 * files replaced on disk (the registry lists all three as needing a hash).
 *
 * @property localModelRepository The registry of installed models.
 * @property modelFileHasher Reads a file and returns its hash.
 */
class ComputeModelFileHashesUseCase @Inject constructor(
    private val localModelRepository: LocalModelRepository,
    private val modelFileHasher: ModelFileHasher,
) {

    /**
     * Runs one pass. A file the hasher cannot read, or that changes while it is
     * read, is left without a hash; the next pass tries it again.
     *
     * @return How many model files were hashed.
     */
    suspend operator fun invoke(): Int {
        var hashed = 0
        for (model in localModelRepository.modelsNeedingFileHash()) {
            val hash = modelFileHasher.hash(model.path) ?: continue
            localModelRepository.recordFileHash(model.id, hash)
            hashed++
        }
        return hashed
    }
}
