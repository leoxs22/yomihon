package eu.kanade.tachiyomi.domain.dictionary

import kotlinx.coroutines.flow.Flow

internal interface DictionaryImportWorkerController {
    fun startImport(request: DictionaryImportRequest)

    fun isRunningFlow(): Flow<Boolean>
}