package eu.kanade.tachiyomi.domain.dictionary

import kotlinx.coroutines.flow.Flow
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.repository.DictionaryMigrationStatusRepository
import mihon.domain.dictionary.repository.DictionaryRepository

sealed interface DictionaryImportRequest {
    data class LocalArchive(val uriString: String) : DictionaryImportRequest

    data class RemoteUrl(val url: String) : DictionaryImportRequest
}

interface DictionarySettingsCoordinator {
    fun observeDictionaries(): Flow<List<Dictionary>>

    fun observeMigrationStatuses(): Flow<List<DictionaryMigrationStatus>>

    fun observeImportRunning(): Flow<Boolean>

    fun startImport(request: DictionaryImportRequest)
}

internal class DictionarySettingsCoordinatorImpl(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryMigrationStatusRepository: DictionaryMigrationStatusRepository,
    private val dictionaryImportWorkerController: DictionaryImportWorkerController,
) : DictionarySettingsCoordinator {

    override fun observeDictionaries(): Flow<List<Dictionary>> {
        return dictionaryRepository.subscribeToDictionaries()
    }

    override fun observeMigrationStatuses(): Flow<List<DictionaryMigrationStatus>> {
        return dictionaryMigrationStatusRepository.subscribeToMigrationStatuses()
    }

    override fun observeImportRunning(): Flow<Boolean> {
        return dictionaryImportWorkerController.isRunningFlow()
    }

    override fun startImport(request: DictionaryImportRequest) {
        dictionaryImportWorkerController.startImport(request)
    }
}
