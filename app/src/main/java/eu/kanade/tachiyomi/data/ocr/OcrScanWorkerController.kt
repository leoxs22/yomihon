package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import kotlinx.coroutines.flow.Flow

internal interface OcrScanWorkerController {
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun isRunningFlow(): Flow<Boolean>
}

internal class WorkManagerOcrScanWorkerController(
    private val context: Context,
) : OcrScanWorkerController {
    override fun start() {
        OcrScanJob.start(context)
    }

    override fun stop() {
        OcrScanJob.stop(context)
    }

    override fun isRunning(): Boolean {
        return OcrScanJob.isRunning(context)
    }

    override fun isRunningFlow(): Flow<Boolean> {
        return OcrScanJob.isRunningFlow(context)
    }
}
