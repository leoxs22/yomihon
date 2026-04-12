package eu.kanade.tachiyomi.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.createBitmap
import eu.kanade.tachiyomi.data.database.models.toDomainChapter
import eu.kanade.tachiyomi.data.ocr.OcrPageInput
import eu.kanade.tachiyomi.data.ocr.OcrPageSourceResolver
import eu.kanade.tachiyomi.data.ocr.ResolvedOcrPages
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderSelectionCapture
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

internal class ReaderSelectionCropper(
    private val ocrPageSourceResolver: OcrPageSourceResolver,
) {
    suspend fun cropSelectionBitmap(
        manga: Manga,
        captures: List<ReaderSelectionCapture>,
    ): Bitmap {
        val resolvedPagesByChapter = mutableMapOf<Chapter, ResolvedOcrPages>()
        try {
            if (captures.size == 1) {
                return cropPageBitmap(manga, captures.first(), resolvedPagesByChapter)
            }

            logcat(LogPriority.DEBUG) {
                "Selection crop stitching ${captures.size} page segments"
            }

            val parts = captures.map { capture ->
                CroppedSelectionPart(
                    bitmap = cropPageBitmap(manga, capture, resolvedPagesByChapter),
                    screenRect = capture.screenRect,
                )
            }

            try {
                val minLeft = parts.minOf { it.screenRect.left }
                val minTop = parts.minOf { it.screenRect.top }
                val positionedParts = parts.map { part ->
                    PositionedSelectionPart(
                        bitmap = part.bitmap,
                        left = part.screenRect.left - minLeft,
                        top = part.screenRect.top - minTop,
                    )
                }
                val mergedWidth = positionedParts
                    .maxOf { it.left + it.bitmap.width }
                    .toInt()
                    .coerceAtLeast(1)
                val mergedHeight = positionedParts
                    .maxOf { it.top + it.bitmap.height }
                    .toInt()
                    .coerceAtLeast(1)
                val mergedBitmap = createBitmap(mergedWidth, mergedHeight)
                val canvas = Canvas(mergedBitmap)

                positionedParts.forEach { part ->
                    canvas.drawBitmap(part.bitmap, part.left, part.top, null)
                }

                logcat(LogPriority.DEBUG) {
                    "Selection crop stitched bitmap size=${mergedBitmap.width}x${mergedBitmap.height}"
                }
                return mergedBitmap
            } finally {
                parts.forEach { part ->
                    if (!part.bitmap.isRecycled) {
                        part.bitmap.recycle()
                    }
                }
            }
        } finally {
            resolvedPagesByChapter.values.forEach(ResolvedOcrPages::close)
        }
    }

    private suspend fun cropPageBitmap(
        manga: Manga,
        capture: ReaderSelectionCapture,
        resolvedPagesByChapter: MutableMap<Chapter, ResolvedOcrPages>,
    ): Bitmap {
        val page = capture.page
        val sourceRect = capture.sourceRect
        val chapter = page.chapter.chapter.toDomainChapter()
            ?: throw IllegalStateException("Chapter unavailable")

        capture.decodeBitmap()?.let { bitmap ->
            logcat(LogPriority.DEBUG) {
                "Selection crop page=${page.index} via loaded source size=${bitmap.width}x${bitmap.height}"
            }
            return bitmap
        }

        val pageInput = getPageInput(
            manga = manga,
            chapter = chapter,
            pageIndex = page.index,
            resolvedPagesByChapter = resolvedPagesByChapter,
        )

        pageInput.openBitmapRegion(sourceRect)?.let { bitmap ->
            logcat(LogPriority.DEBUG) {
                "Selection crop page=${page.index} via OCR source size=${bitmap.width}x${bitmap.height}"
            }
            return bitmap
        }

        logcat(LogPriority.DEBUG) {
            "Selection crop page=${page.index} fell back to full decode rect=${sourceRect.flattenToString()}"
        }
        val fullBitmap = pageInput.openBitmap()
            ?: throw IllegalStateException("Failed to decode page bitmap")

        return cropDecodedBitmap(fullBitmap, sourceRect)
    }

    private suspend fun getPageInput(
        manga: Manga,
        chapter: Chapter,
        pageIndex: Int,
        resolvedPagesByChapter: MutableMap<Chapter, ResolvedOcrPages>,
    ): OcrPageInput {
        val resolvedPages = resolvedPagesByChapter[chapter]
            ?: ocrPageSourceResolver.resolve(manga, chapter).also { resolvedPagesByChapter[chapter] = it }
        return resolvedPages.getPageInput(pageIndex)
            ?: throw IllegalStateException("Page unavailable for crop")
    }

    private fun cropDecodedBitmap(
        fullBitmap: Bitmap,
        sourceRect: Rect,
    ): Bitmap {
        var keepFullBitmap = false
        try {
            val safeRect = Rect(
                sourceRect.left.coerceIn(0, fullBitmap.width),
                sourceRect.top.coerceIn(0, fullBitmap.height),
                sourceRect.right.coerceIn(0, fullBitmap.width),
                sourceRect.bottom.coerceIn(0, fullBitmap.height),
            )
            if (safeRect.width() <= 0 || safeRect.height() <= 0) {
                throw IllegalStateException("Invalid crop rectangle")
            }

            val croppedBitmap = Bitmap.createBitmap(
                fullBitmap,
                safeRect.left,
                safeRect.top,
                safeRect.width(),
                safeRect.height(),
            )
            if (croppedBitmap === fullBitmap) {
                keepFullBitmap = true
            }
            return croppedBitmap
        } finally {
            if (!keepFullBitmap && !fullBitmap.isRecycled) {
                fullBitmap.recycle()
            }
        }
    }
}

private data class CroppedSelectionPart(
    val bitmap: Bitmap,
    val screenRect: RectF,
)

private data class PositionedSelectionPart(
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
)
