package eu.kanade.tachiyomi.ui.download

import android.view.MenuItem
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.ocr.OcrScanManager
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueEntry
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueState
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.source.model.Page
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val ocrScanManager: OcrScanManager = Injekt.get(),
    private val getChapter: GetChapter = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : ScreenModel {

    private val _state = MutableStateFlow(emptyList<DownloadHeaderItem>())
    val state = _state.asStateFlow()

    private val _ocrQueueState = MutableStateFlow(OcrQueueUiState())
    internal val ocrQueueState = _ocrQueueState.asStateFlow()

    private val ocrChapterMetadataCache = mutableMapOf<Long, OcrQueueChapterMetadata>()

    lateinit var controllerBinding: DownloadListBinding

    /**
     * Adapter containing the active downloads.
     */
    var adapter: DownloadAdapter? = null

    /**
     * Map of jobs for active downloads.
     */
    private val progressJobs = mutableMapOf<Download, Job>()

    val listener = object : DownloadAdapter.DownloadItemListener {
        /**
         * Called when an item is released from a drag.
         *
         * @param position The position of the released item.
         */
        override fun onItemReleased(position: Int) {
            val adapter = adapter ?: return
            val downloads = adapter.headerItems.flatMap { header ->
                adapter.getSectionItems(header).map { item ->
                    (item as DownloadItem).download
                }
            }
            reorder(downloads)
        }

        /**
         * Called when the menu item of a download is pressed
         *
         * @param position The position of the item
         * @param menuItem The menu Item pressed
         */
        override fun onMenuItemClick(position: Int, menuItem: MenuItem) {
            val item = adapter?.getItem(position) ?: return
            if (item is DownloadItem) {
                when (menuItem.itemId) {
                    R.id.move_to_top, R.id.move_to_bottom -> {
                        val headerItems = adapter?.headerItems ?: return
                        val newDownloads = mutableListOf<Download>()
                        headerItems.forEach { headerItem ->
                            headerItem as DownloadHeaderItem
                            if (headerItem == item.header) {
                                headerItem.removeSubItem(item)
                                if (menuItem.itemId == R.id.move_to_top) {
                                    headerItem.addSubItem(0, item)
                                } else {
                                    headerItem.addSubItem(item)
                                }
                            }
                            newDownloads.addAll(headerItem.subItems.map { it.download })
                        }
                        reorder(newDownloads)
                    }
                    R.id.move_to_top_series, R.id.move_to_bottom_series -> {
                        val (selectedSeries, otherSeries) = adapter?.currentItems
                            ?.filterIsInstance<DownloadItem>()
                            ?.map(DownloadItem::download)
                            ?.partition { item.download.manga.id == it.manga.id }
                            ?: Pair(emptyList(), emptyList())
                        if (menuItem.itemId == R.id.move_to_top_series) {
                            reorder(selectedSeries + otherSeries)
                        } else {
                            reorder(otherSeries + selectedSeries)
                        }
                    }
                    R.id.cancel_download -> {
                        cancel(listOf(item.download))
                    }
                    R.id.cancel_series -> {
                        val allDownloadsForSeries = adapter?.currentItems
                            ?.filterIsInstance<DownloadItem>()
                            ?.filter { item.download.manga.id == it.download.manga.id }
                            ?.map(DownloadItem::download)
                        if (!allDownloadsForSeries.isNullOrEmpty()) {
                            cancel(allDownloadsForSeries)
                        }
                    }
                }
            }
        }
    }

    init {
        screenModelScope.launch {
            downloadManager.queueState
                .map { downloads ->
                    downloads
                        .groupBy { it.source }
                        .map { entry ->
                            DownloadHeaderItem(entry.key.id, entry.key.name, entry.value.size).apply {
                                addSubItems(0, entry.value.map { DownloadItem(it, this) })
                            }
                        }
                }
                .collect { newList -> _state.update { newList } }
        }

        screenModelScope.launch {
            ocrScanManager.queueState
                .mapLatest(::buildOcrQueueUiState)
                .collectLatest { state -> _ocrQueueState.value = state }
        }
    }

    private suspend fun buildOcrQueueUiState(queueState: OcrScanQueueState): OcrQueueUiState {
        if (!queueState.isActive) {
            return OcrQueueUiState(isPaused = queueState.isPaused)
        }

        val items = mutableListOf<OcrQueueChapterItem>()

        queueState.entries.forEach { entry ->
            val progress = queueState.activeProgress?.takeIf { it.chapterId == entry.chapterId }
            val chapterMetadata = resolveOcrQueueChapter(entry.chapterId)
            items += OcrQueueChapterItem(
                chapterId = entry.chapterId,
                mangaId = chapterMetadata.mangaId,
                sourceName = chapterMetadata.sourceName,
                mangaTitle = progress?.mangaTitle ?: chapterMetadata.mangaTitle,
                chapterName = progress?.chapterName ?: chapterMetadata.chapterName,
                chapterNumber = chapterMetadata.chapterNumber,
                processedPages = progress?.processedPages ?: 0,
                totalPages = progress?.totalPages,
                queueState = entry.state,
                lastError = entry.lastError,
            )
        }

        return OcrQueueUiState(
            items = items,
            isPaused = queueState.isPaused,
        )
    }

    private suspend fun resolveOcrQueueChapter(chapterId: Long): OcrQueueChapterMetadata {
        return ocrChapterMetadataCache[chapterId] ?: run {
            val chapter = getChapter.await(chapterId)
            val manga = chapter?.let { getManga.await(it.mangaId) }
            val sourceName = manga?.let { sourceManager.getOrStub(it.source).name }.orEmpty()
            val mangaTitle = manga?.title.orEmpty()
            val chapterName = chapter
                ?.name
                ?.takeIf(String::isNotBlank)
                ?: chapterId.toString()

            OcrQueueChapterMetadata(
                mangaId = chapter?.mangaId ?: -1L,
                sourceName = sourceName,
                mangaTitle = mangaTitle,
                chapterName = chapterName,
                chapterNumber = chapter?.chapterNumber ?: -1.0,
            ).also {
                ocrChapterMetadataCache[chapterId] = it
            }
        }
    }

    override fun onDispose() {
        for (job in progressJobs.values) {
            job.cancel()
        }
        progressJobs.clear()
        ocrChapterMetadataCache.clear()
        adapter = null
    }

    val isAnyQueueRunning = combine(
        downloadManager.isDownloaderRunning,
        ocrScanManager.isScannerRunning,
    ) { isDownloading, isScanning ->
        isDownloading || isScanning
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun getDownloadStatusFlow() = downloadManager.statusFlow()
    fun getDownloadProgressFlow() = downloadManager.progressFlow()

    fun resumeQueues() {
        downloadManager.startDownloads()
        screenModelScope.launch {
            ocrScanManager.resume()
        }
    }

    fun pauseQueues() {
        downloadManager.pauseDownloads()
        screenModelScope.launch {
            ocrScanManager.pause()
        }
    }

    fun clearQueues() {
        downloadManager.clearQueue()
        screenModelScope.launch {
            ocrScanManager.clearQueue()
        }
    }

    fun reorder(downloads: List<Download>) {
        downloadManager.reorderQueue(downloads)
    }

    fun cancel(downloads: List<Download>) {
        downloadManager.cancelQueuedDownloads(downloads)
    }

    fun <R : Comparable<R>> reorderQueue(selector: (DownloadItem) -> R, reverse: Boolean = false) {
        val adapter = adapter ?: return
        val newDownloads = mutableListOf<Download>()
        adapter.headerItems.forEach { headerItem ->
            headerItem as DownloadHeaderItem
            headerItem.subItems = headerItem.subItems.sortedBy(selector).toMutableList().apply {
                if (reverse) {
                    reverse()
                }
            }
            newDownloads.addAll(headerItem.subItems.map { it.download })
        }
        reorder(newDownloads)
    }

    internal fun handleOcrAction(
        chapterId: Long,
        action: OcrQueueMenuAction,
    ) {
        val currentItems = _ocrQueueState.value.items
        if (currentItems.isEmpty()) return

        val selectedItem = currentItems.firstOrNull { it.chapterId == chapterId } ?: return
        val selectedSeries = currentItems.filter { it.mangaId == selectedItem.mangaId }
        val otherSeries = currentItems.filterNot { it.mangaId == selectedItem.mangaId }

        when (action) {
            OcrQueueMenuAction.MoveToTop -> {
                val reorderedItems = currentItems.toMutableList().apply {
                    removeAll { it.chapterId == chapterId }
                    add(0, selectedItem)
                }
                reorderOcrQueue(reorderedItems.map(OcrQueueChapterItem::chapterId))
            }
            OcrQueueMenuAction.MoveSeriesToTop -> {
                reorderOcrQueue((selectedSeries + otherSeries).map(OcrQueueChapterItem::chapterId))
            }
            OcrQueueMenuAction.MoveToBottom -> {
                val reorderedItems = currentItems.toMutableList().apply {
                    removeAll { it.chapterId == chapterId }
                    add(selectedItem)
                }
                reorderOcrQueue(reorderedItems.map(OcrQueueChapterItem::chapterId))
            }
            OcrQueueMenuAction.MoveSeriesToBottom -> {
                reorderOcrQueue((otherSeries + selectedSeries).map(OcrQueueChapterItem::chapterId))
            }
            OcrQueueMenuAction.Cancel -> {
                cancelOcrQueue(listOf(chapterId))
            }
            OcrQueueMenuAction.CancelSeries -> {
                cancelOcrQueue(selectedSeries.map(OcrQueueChapterItem::chapterId))
            }
        }
    }

    private fun reorderOcrQueue(chapterIds: List<Long>) {
        screenModelScope.launch {
            ocrScanManager.reorderQueue(chapterIds)
        }
    }

    private fun cancelOcrQueue(chapterIds: Collection<Long>) {
        screenModelScope.launch {
            ocrScanManager.cancelQueuedChapters(chapterIds)
        }
    }

    /**
     * Called when the status of a download changes.
     *
     * @param download the download whose status has changed.
     */
    fun onStatusChange(download: Download) {
        when (download.status) {
            Download.State.DOWNLOADING -> {
                launchProgressJob(download)
                // Initial update of the downloaded pages
                onUpdateDownloadedPages(download)
            }
            Download.State.DOWNLOADED -> {
                cancelProgressJob(download)
                onUpdateProgress(download)
                onUpdateDownloadedPages(download)
            }
            Download.State.ERROR -> cancelProgressJob(download)
            else -> {
                /* unused */
            }
        }
    }

    /**
     * Observe the progress of a download and notify the view.
     *
     * @param download the download to observe its progress.
     */
    private fun launchProgressJob(download: Download) {
        val job = screenModelScope.launch {
            while (download.pages == null) {
                delay(50)
            }

            val progressFlows = download.pages!!.map(Page::progressFlow)
            combine(progressFlows, Array<Int>::sum)
                .distinctUntilChanged()
                .debounce(50)
                .collectLatest {
                    onUpdateProgress(download)
                }
        }

        // Avoid leaking jobs
        progressJobs.remove(download)?.cancel()

        progressJobs[download] = job
    }

    /**
     * Unsubscribes the given download from the progress subscriptions.
     *
     * @param download the download to unsubscribe.
     */
    private fun cancelProgressJob(download: Download) {
        progressJobs.remove(download)?.cancel()
    }

    /**
     * Called when the progress of a download changes.
     *
     * @param download the download whose progress has changed.
     */
    private fun onUpdateProgress(download: Download) {
        getHolder(download)?.notifyProgress()
    }

    /**
     * Called when a page of a download is downloaded.
     *
     * @param download the download whose page has been downloaded.
     */
    fun onUpdateDownloadedPages(download: Download) {
        getHolder(download)?.notifyDownloadedPages()
    }

    /**
     * Returns the holder for the given download.
     *
     * @param download the download to find.
     * @return the holder of the download or null if it's not bound.
     */
    private fun getHolder(download: Download): DownloadHolder? {
        return controllerBinding.root.findViewHolderForItemId(download.chapter.id) as? DownloadHolder
    }
}

internal data class OcrQueueUiState(
    val items: List<OcrQueueChapterItem> = emptyList(),
    val isPaused: Boolean = false,
) {
    val totalCount: Int
        get() = items.size
}

internal data class OcrQueueChapterItem(
    val chapterId: Long,
    val mangaId: Long,
    val sourceName: String,
    val mangaTitle: String,
    val chapterName: String,
    val chapterNumber: Double,
    val processedPages: Int,
    val totalPages: Int?,
    val queueState: OcrScanQueueEntry.State,
    val lastError: String?,
)

internal enum class OcrQueueMenuAction {
    MoveToTop,
    MoveSeriesToTop,
    MoveToBottom,
    MoveSeriesToBottom,
    Cancel,
    CancelSeries,
}

private data class OcrQueueChapterMetadata(
    val mangaId: Long,
    val sourceName: String,
    val mangaTitle: String,
    val chapterName: String,
    val chapterNumber: Double,
)
