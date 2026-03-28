package eu.kanade.tachiyomi.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import java.io.ByteArrayInputStream
import java.io.InputStream
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.EpubPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import mihon.core.archive.archiveReader
import mihon.core.archive.epubReader
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.LocalSource
import tachiyomi.source.local.io.Format

internal class OcrPageSourceResolver(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) {
    suspend fun resolve(
        manga: Manga,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val source = sourceManager.getOrStub(manga.source)
        val downloadedPagesReady = if (source is HttpSource) {
            awaitDownloadedChapterPages(manga, chapter)
        } else {
            false
        }
        return when {
            downloadedPagesReady -> resolveDownloadedPages(manga, chapter, source)
            source is LocalSource -> resolveLocalPages(source, chapter)
            source is HttpSource -> resolveRemotePages(source, chapter)
            else -> ResolvedOcrPages(emptyList())
        }
    }

    private suspend fun awaitDownloadedChapterPages(
        manga: Manga,
        chapter: Chapter,
    ): Boolean {
        val queuedDownload = downloadManager.getQueuedDownloadOrNull(chapter.id)
        if (
            queuedDownload == null &&
            downloadManager.isChapterDownloaded(
                chapter.name,
                chapter.scanlator,
                manga.title,
                manga.source,
                skipCache = true,
            )
        ) {
            return true
        }

        queuedDownload ?: return false
        return queuedDownload.statusFlow
            .map { status ->
                val queueEntry = downloadManager.getQueuedDownloadOrNull(chapter.id)
                when {
                    queueEntry == null &&
                        downloadManager.isChapterDownloaded(
                            chapter.name,
                            chapter.scanlator,
                            manga.title,
                            manga.source,
                            skipCache = true,
                        ) -> true
                    downloadManager.isChapterDownloaded(
                        chapter.name,
                        chapter.scanlator,
                        manga.title,
                        manga.source,
                        skipCache = true,
                    ) && status == Download.State.DOWNLOADED && queueEntry == null -> true
                    status == Download.State.ERROR || status == Download.State.NOT_DOWNLOADED -> false
                    else -> null
                }
            }
            .filterNotNull()
            .first()
    }

    private suspend fun resolveDownloadedPages(
        manga: Manga,
        chapter: Chapter,
        source: Source,
    ): ResolvedOcrPages {
        val chapterPath = downloadProvider.findChapterDir(chapter.name, chapter.scanlator, manga.title, source)
        if (chapterPath?.isFile == true) {
            return resolveArchivePages(chapterPath)
        }

        val loader = DownloadPageLoader(
            chapter = ReaderChapter(chapter),
            manga = manga,
            source = source,
            downloadManager = downloadManager,
            downloadProvider = downloadProvider,
        )
        return loader.toResolvedPages()
    }

    private suspend fun resolveLocalPages(
        source: LocalSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val loader = when (val format = source.getFormat(chapter.toSChapter())) {
            is Format.Directory -> DirectoryPageLoader(format.file)
            is Format.Archive -> return resolveArchivePages(format.file)
            is Format.Epub -> EpubPageLoader(format.file.epubReader(context))
        }
        return loader.toResolvedPages()
    }

    private suspend fun resolveRemotePages(
        source: HttpSource,
        chapter: Chapter,
    ): ResolvedOcrPages {
        val pages = source.getPageList(chapter.toSChapter())
            .mapIndexed { index, page -> Page(index, page.url, page.imageUrl, page.uri) }
            .map { page ->
                OcrPageInput(
                    pageIndex = page.index,
                    openBitmap = {
                        withIOContext {
                            if (page.imageUrl.isNullOrBlank()) {
                                page.imageUrl = source.getImageUrl(page)
                            }
                            source.getImage(page).use { response ->
                                decodeBitmap(response.body.byteStream())
                            }
                        }
                    },
                )
            }

        return ResolvedOcrPages(pages)
    }

    private suspend fun resolveArchivePages(
        file: UniFile,
    ): ResolvedOcrPages {
        val reader = file.archiveReader(context)
        val entryNames = withIOContext {
            buildList {
                reader.useEntriesAndStreams { entry, stream ->
                    if (entry.isFile && isArchiveImageEntry(entry.name, stream)) {
                        add(entry.name)
                    }
                }
            }
        }
            .sortedWith { entry1, entry2 ->
                entry1.compareToCaseInsensitiveNaturalOrder(entry2)
            }

        val pages = entryNames.mapIndexed { index, entryName ->
            OcrPageInput(
                pageIndex = index,
                openBitmap = {
                    withIOContext {
                        reader.getInputStream(entryName)?.use(::decodeArchiveBitmap)
                    }
                },
            )
        }

        return ResolvedOcrPages(
            pages = pages,
            closeBlock = reader::close,
        )
    }

    private suspend fun PageLoader.toResolvedPages(): ResolvedOcrPages {
        val pages = getPages().map { page ->
            OcrPageInput(
                pageIndex = page.index,
                openBitmap = {
                    withIOContext {
                        page.stream?.invoke()?.use(::decodeBitmap)
                    }
                },
            )
        }
        return ResolvedOcrPages(
            pages = pages,
            closeBlock = ::recycle,
        )
    }

    private fun isArchiveImageEntry(
        name: String,
        stream: InputStream,
    ): Boolean {
        if (hasKnownImageExtension(name)) {
            return true
        }

        val header = ByteArray(32)
        val length = stream.read(header)
        if (length <= 0) {
            return false
        }

        return ImageUtil.findImageType(ByteArrayInputStream(header, 0, length)) != null
    }

    private fun hasKnownImageExtension(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension == "jpeg" || ImageUtil.ImageType.entries.any { it.extension == extension }
    }

    private fun decodeBitmap(stream: InputStream): Bitmap? {
        return BitmapFactory.decodeStream(
            stream,
            null,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }

    private fun decodeArchiveBitmap(stream: InputStream): Bitmap? {
        val bytes = stream.readBytes()
        if (bytes.isEmpty()) {
            return null
        }

        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        )
    }
}

internal data class OcrPageInput(
    val pageIndex: Int,
    val openBitmap: suspend () -> Bitmap?,
)

internal class ResolvedOcrPages(
    val pages: List<OcrPageInput>,
    private val closeBlock: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        closeBlock()
    }
}
