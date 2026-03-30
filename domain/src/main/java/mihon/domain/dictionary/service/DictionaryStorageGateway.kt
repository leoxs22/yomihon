package mihon.domain.dictionary.service

import java.io.File
import mihon.domain.dictionary.model.Dictionary

interface DictionaryStorageGateway {
    fun getDictionaryStorageParent(dictionaryId: Long): File

    suspend fun importDictionary(
        zipPath: String,
        dictionary: Dictionary,
    ): DictionaryStorageImportOutcome

    suspend fun validateImportedDictionary(
        storagePath: String,
        sampleExpression: String?,
    ): Boolean

    suspend fun rebuildSession()

    fun markDirty()
}

data class DictionaryStorageImportOutcome(
    val success: Boolean,
    val storagePath: String?,
    val termCount: Long,
    val metaCount: Long,
    val mediaCount: Long,
)
