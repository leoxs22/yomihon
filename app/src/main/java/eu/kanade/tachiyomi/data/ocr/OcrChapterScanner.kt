package eu.kanade.tachiyomi.data.ocr

import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import mihon.domain.ocr.interactor.ClearCachedChapterOcr
import mihon.domain.ocr.interactor.RunOcrScanSession
import mihon.domain.ocr.interactor.ScanPageOcr
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga

internal class OcrChapterScanner(
    private val getChapter: GetChapter,
    private val getManga: GetManga,
    private val clearCachedChapterOcr: ClearCachedChapterOcr,
    private val runOcrScanSession: RunOcrScanSession,
    private val scanPageOcr: ScanPageOcr,
    private val pageSourceResolver: OcrPageSourceResolver,
) {
    suspend fun scanChapter(
        chapterId: Long,
        onProgress: (OcrChapterScanProgress) -> Unit,
        onComplete: (OcrChapterScanProgress) -> Unit,
        onError: (OcrChapterScanError) -> Unit,
        onCacheStateChanged: (chapterId: Long, hasResults: Boolean) -> Unit = { _, _ -> },
    ): Boolean {
        val chapter = getChapter.await(chapterId)
        if (chapter == null) {
            onError(
                OcrChapterScanError(
                    mangaId = null,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapterId.toString(),
                    error = "Chapter not found",
                ),
            )
            return false
        }

        val manga = getManga.await(chapter.mangaId)
        if (manga == null) {
            onError(
                OcrChapterScanError(
                    mangaId = chapter.mangaId,
                    mangaTitle = null,
                    chapterId = chapterId,
                    chapterName = chapter.name,
                    error = "Manga not found",
                ),
            )
            return false
        }

        return try {
            runOcrScanSession.await {
                clearCachedChapterOcr.await(chapterId)
                onCacheStateChanged(chapterId, false)

                val resolvedPages = pageSourceResolver.resolve(manga, chapter)
                resolvedPages.use { pages ->
                    if (pages.pages.isEmpty()) {
                        onError(
                            OcrChapterScanError(
                                mangaId = manga.id,
                                mangaTitle = manga.title,
                                chapterId = chapterId,
                                chapterName = chapter.name,
                                error = "No pages available for OCR scanning",
                            ),
                        )
                        false
                    } else {
                        val totalPages = pages.pages.size
                        var lastProgress = OcrChapterScanProgress(
                            mangaId = manga.id,
                            mangaTitle = manga.title,
                            chapterId = chapterId,
                            chapterName = chapter.name,
                            processedPages = 0,
                            totalPages = totalPages,
                        )

                        onProgress(lastProgress)

                        try {
                            var chapterHasCachedResults = false
                            pages.pages.forEachIndexed { index, page ->
                                val decodedBitmap = page.openBitmap() ?: error("Unable to decode page ${page.pageIndex + 1}")
                                val bitmap = decodedBitmap.toArgb8888Bitmap()
                                try {
                                    scanPageOcr.await(chapterId, page.pageIndex, bitmap)
                                } finally {
                                    if (bitmap !== decodedBitmap && !decodedBitmap.isRecycled) {
                                        decodedBitmap.recycle()
                                    }
                                    if (!bitmap.isRecycled) {
                                        bitmap.recycle()
                                    }
                                }

                                if (!chapterHasCachedResults) {
                                    chapterHasCachedResults = true
                                    onCacheStateChanged(chapterId, true)
                                }

                                lastProgress = lastProgress.copy(processedPages = index + 1)
                                onProgress(lastProgress)
                            }

                            onComplete(lastProgress)
                            true
                        } catch (e: Throwable) {
                            if (e is CancellationException) {
                                throw e
                            }
                            logcat(LogPriority.ERROR, e) { "Failed to scan OCR for chapterId=$chapterId" }
                            clearCachedChapterOcr.await(chapterId)
                            onCacheStateChanged(chapterId, false)
                            onError(
                                OcrChapterScanError(
                                    mangaId = manga.id,
                                    mangaTitle = manga.title,
                                    chapterId = chapterId,
                                    chapterName = chapter.name,
                                    error = e.message,
                                ),
                            )
                            false
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            logcat(LogPriority.ERROR, e) { "Failed to start OCR scan for chapterId=$chapterId" }
            clearCachedChapterOcr.await(chapterId)
            onCacheStateChanged(chapterId, false)
            onError(
                OcrChapterScanError(
                    mangaId = manga.id,
                    mangaTitle = manga.title,
                    chapterId = chapterId,
                    chapterName = chapter.name,
                    error = e.message,
                ),
            )
            false
        }
    }
}

internal data class OcrChapterScanProgress(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val processedPages: Int,
    val totalPages: Int,
)

internal data class OcrChapterScanError(
    val mangaId: Long?,
    val mangaTitle: String?,
    val chapterId: Long,
    val chapterName: String,
    val error: String?,
)

private fun Bitmap.toArgb8888Bitmap(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) {
        return this
    }
    return copy(Bitmap.Config.ARGB_8888, false) ?: this
}
