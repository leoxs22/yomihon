package eu.kanade.tachiyomi.domain.dictionary

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import eu.kanade.tachiyomi.data.dictionary.DictionaryImportCoordinator
import mihon.domain.dictionary.model.Dictionary
import mihon.domain.dictionary.model.DictionaryMigrationStatus
import mihon.domain.dictionary.repository.DictionaryRepository

interface DictionarySettingsCoordinator {
    fun observeDictionaries(): Flow<List<Dictionary>>

    fun observeMigrationStatuses(): Flow<List<DictionaryMigrationStatus>>

    fun isRunningFlow(): Flow<Boolean>

    fun startFromUri(uri: Uri)

    fun startFromUrl(url: String)
}

internal class DictionarySettingsCoordinatorImpl(
    private val dictionaryRepository: DictionaryRepository,
    private val dictionaryImportCoordinator: DictionaryImportCoordinator,
) : DictionarySettingsCoordinator {

    override fun observeDictionaries(): Flow<List<Dictionary>> {
        return dictionaryRepository.subscribeToDictionaries()
    }

    override fun observeMigrationStatuses(): Flow<List<DictionaryMigrationStatus>> {
        return dictionaryRepository.subscribeToMigrationStatuses()
    }

    override fun isRunningFlow(): Flow<Boolean> {
        return dictionaryImportCoordinator.isRunningFlow()
    }

    override fun startFromUri(uri: Uri) {
        dictionaryImportCoordinator.startFromUri(uri)
    }

    override fun startFromUrl(url: String) {
        dictionaryImportCoordinator.startFromUrl(url)
    }
}
