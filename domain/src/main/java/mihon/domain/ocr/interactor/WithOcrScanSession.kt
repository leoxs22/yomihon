package mihon.domain.ocr.interactor

import mihon.domain.ocr.repository.OcrRepository

class WithOcrScanSession(
    private val ocrRepository: OcrRepository,
) {
    suspend fun <T> run(block: suspend () -> T): T {
        return ocrRepository.withScanSession(block)
    }
}
