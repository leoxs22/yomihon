package eu.kanade.tachiyomi.data.dictionary

import android.content.Context
import eu.kanade.tachiyomi.domain.dictionary.DictionaryImportRequest
import eu.kanade.tachiyomi.domain.dictionary.DictionaryImportWorkerController
import kotlinx.coroutines.flow.Flow

internal class WorkManagerDictionaryImportWorkerController(
    private val context: Context,
) : DictionaryImportWorkerController {

    override fun startImport(request: DictionaryImportRequest) {
        when (request) {
            is DictionaryImportRequest.LocalArchive -> DictionaryImportJob.startFromUriString(context, request.uriString)
            is DictionaryImportRequest.RemoteUrl -> DictionaryImportJob.start(context, request.url)
        }
    }

    override fun isRunningFlow(): Flow<Boolean> {
        return DictionaryImportJob.isRunningFlow(context)
    }
}