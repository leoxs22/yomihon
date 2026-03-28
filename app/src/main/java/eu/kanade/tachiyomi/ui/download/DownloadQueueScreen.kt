package eu.kanade.tachiyomi.ui.download

import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.NestedMenuItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.databinding.DownloadListBinding
import eu.kanade.tachiyomi.data.ocr.OcrScanQueueEntry
import kotlinx.collections.immutable.toPersistentList
import tachiyomi.core.common.util.lang.launchUI
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.ExtendedFloatingActionButton
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen

object DownloadQueueScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { DownloadQueueScreenModel() }
        val downloadList by screenModel.state.collectAsState()
        val ocrQueue by screenModel.ocrQueueState.collectAsState()
        val isAnyQueueRunning by screenModel.isAnyQueueRunning.collectAsState()
        val downloadCount by remember {
            derivedStateOf { downloadList.sumOf { it.subItems.size } }
        }
        val hasDownloadQueue = downloadList.isNotEmpty()
        val hasOcrQueue = ocrQueue.totalCount > 0
        val hasAnyQueue = hasDownloadQueue || hasOcrQueue

        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
        var fabExpanded by remember { mutableStateOf(true) }
        val nestedScrollConnection = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    fabExpanded = available.y >= 0
                    return scrollBehavior.nestedScrollConnection.onPreScroll(available, source)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    return scrollBehavior.nestedScrollConnection.onPostScroll(consumed, available, source)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPreFling(available)
                }

                override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                    return scrollBehavior.nestedScrollConnection.onPostFling(consumed, available)
                }
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    titleContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(MR.strings.label_download_queue),
                                maxLines = 1,
                                modifier = Modifier.weight(1f, false),
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (downloadCount > 0) {
                                val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
                                Pill(
                                    text = "$downloadCount",
                                    modifier = Modifier.padding(start = 4.dp),
                                    color = MaterialTheme.colorScheme.onBackground
                                        .copy(alpha = pillAlpha),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    },
                    navigateUp = navigator::pop,
                    actions = {
                        if (hasAnyQueue) {
                            var sortExpanded by remember { mutableStateOf(false) }
                            val onDismissRequest = { sortExpanded = false }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = onDismissRequest,
                            ) {
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_upload_date)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_newest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_oldest)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.dateUpload },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                                NestedMenuItem(
                                    text = { Text(text = stringResource(MR.strings.action_order_by_chapter_number)) },
                                    children = { closeMenu ->
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_asc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    false,
                                                )
                                                closeMenu()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(text = stringResource(MR.strings.action_desc)) },
                                            onClick = {
                                                screenModel.reorderQueue(
                                                    { it.download.chapter.chapterNumber },
                                                    true,
                                                )
                                                closeMenu()
                                            },
                                        )
                                    },
                                )
                            }

                            val actions = buildList {
                                if (hasDownloadQueue) {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_sort),
                                            icon = Icons.AutoMirrored.Outlined.Sort,
                                            onClick = { sortExpanded = true },
                                        ),
                                    )
                                }
                                add(
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_cancel_all),
                                        onClick = { screenModel.clearQueues() },
                                    ),
                                )
                            }
                            AppBarActions(actions.toPersistentList())
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = hasAnyQueue,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    ExtendedFloatingActionButton(
                        text = {
                            val id = if (isAnyQueueRunning) {
                                MR.strings.action_pause
                            } else {
                                MR.strings.action_resume
                            }
                            Text(text = stringResource(id))
                        },
                        icon = {
                            val icon = if (isAnyQueueRunning) {
                                Icons.Outlined.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            }
                            Icon(imageVector = icon, contentDescription = null)
                        },
                        onClick = {
                            if (isAnyQueueRunning) {
                                screenModel.pauseQueues()
                            } else {
                                screenModel.resumeQueues()
                            }
                        },
                        expanded = fabExpanded,
                    )
                }
            },
        ) { contentPadding ->
            if (!hasAnyQueue) {
                EmptyScreen(
                    stringRes = MR.strings.information_no_downloads,
                    modifier = Modifier.padding(contentPadding),
                )
                return@Scaffold
            }

            val layoutDirection = LocalLayoutDirection.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = contentPadding.calculateLeftPadding(layoutDirection),
                        top = contentPadding.calculateTopPadding(),
                        end = contentPadding.calculateRightPadding(layoutDirection),
                        bottom = contentPadding.calculateBottomPadding(),
                    ),
            ) {
                if (hasDownloadQueue) {
                    QueueSectionHeader(
                        title = stringResource(MR.strings.label_download_queue),
                        count = downloadCount,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .weight(1f)
                            .nestedScroll(nestedScrollConnection),
                    ) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                screenModel.controllerBinding = DownloadListBinding.inflate(LayoutInflater.from(context))
                                screenModel.adapter = DownloadAdapter(screenModel.listener)
                                screenModel.controllerBinding.root.adapter = screenModel.adapter
                                screenModel.adapter?.isHandleDragEnabled = true
                                screenModel.controllerBinding.root.layoutManager = LinearLayoutManager(context)

                                ViewCompat.setNestedScrollingEnabled(screenModel.controllerBinding.root, true)

                                scope.launchUI {
                                    screenModel.getDownloadStatusFlow()
                                        .collect(screenModel::onStatusChange)
                                }
                                scope.launchUI {
                                    screenModel.getDownloadProgressFlow()
                                        .collect(screenModel::onUpdateDownloadedPages)
                                }

                                screenModel.controllerBinding.root
                            },
                            update = {
                                screenModel.adapter?.updateDataSet(downloadList)
                            },
                        )
                    }
                }

                if (hasOcrQueue) {
                    OcrQueueSection(
                        state = ocrQueue,
                        onAction = screenModel::handleOcrAction,
                        constrainHeight = hasDownloadQueue,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (hasDownloadQueue) 12.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueSectionHeader(
    title: String,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val pillAlpha = if (isSystemInDarkTheme()) 0.16f else 0.12f

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Pill(
                text = count.toString(),
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = pillAlpha),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun OcrQueueSection(
    state: OcrQueueUiState,
    onAction: (Long, OcrQueueMenuAction) -> Unit,
    constrainHeight: Boolean,
    modifier: Modifier = Modifier,
) {
    val contentModifier = if (constrainHeight) {
        Modifier
            .heightIn(max = 280.dp)
            .verticalScroll(rememberScrollState())
    } else {
        Modifier.verticalScroll(rememberScrollState())
    }

    Column(modifier = modifier) {
        QueueSectionHeader(
            title = stringResource(MR.strings.ocr_preprocess_title),
            count = state.totalCount,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(
            modifier = contentModifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp,
            ) {
                Column {
                    state.items.forEachIndexed { index, item ->
                        if (index != 0) {
                            HorizontalDivider()
                        }

                        OcrQueueRow(
                            item = item,
                            canMoveToTop = item.queueState != OcrScanQueueEntry.State.SCANNING && index > 0,
                            canMoveToBottom = item.queueState != OcrScanQueueEntry.State.SCANNING && index < state.items.lastIndex,
                            onAction = onAction,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OcrQueueRow(
    item: OcrQueueChapterItem,
    canMoveToTop: Boolean,
    canMoveToBottom: Boolean,
    onAction: (Long, OcrQueueMenuAction) -> Unit,
) {
    val stateLabel = when (item.queueState) {
        OcrScanQueueEntry.State.QUEUED -> stringResource(MR.strings.dictionary_migration_stage_queued)
        OcrScanQueueEntry.State.ERROR -> item.lastError ?: stringResource(MR.strings.ocr_preprocess_failed, item.chapterName)
        OcrScanQueueEntry.State.SCANNING -> null
    }
    var menuExpanded by remember(item.chapterId) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                if (item.mangaTitle.isNotBlank()) {
                    Text(
                        text = item.mangaTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Text(
                    text = item.chapterName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )

                if (item.sourceName.isNotBlank()) {
                    Text(
                        text = item.sourceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.MoreVert,
                        contentDescription = stringResource(MR.strings.action_menu),
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (canMoveToTop) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_move_to_top)) },
                            onClick = {
                                menuExpanded = false
                                onAction(item.chapterId, OcrQueueMenuAction.MoveToTop)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_move_to_top_all_for_series)) },
                        onClick = {
                            menuExpanded = false
                            onAction(item.chapterId, OcrQueueMenuAction.MoveSeriesToTop)
                        },
                    )
                    if (canMoveToBottom) {
                        DropdownMenuItem(
                            text = { Text(stringResource(MR.strings.action_move_to_bottom)) },
                            onClick = {
                                menuExpanded = false
                                onAction(item.chapterId, OcrQueueMenuAction.MoveToBottom)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_move_to_bottom_all_for_series)) },
                        onClick = {
                            menuExpanded = false
                            onAction(item.chapterId, OcrQueueMenuAction.MoveSeriesToBottom)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.action_cancel)) },
                        onClick = {
                            menuExpanded = false
                            onAction(item.chapterId, OcrQueueMenuAction.Cancel)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.cancel_all_for_series)) },
                        onClick = {
                            menuExpanded = false
                            onAction(item.chapterId, OcrQueueMenuAction.CancelSeries)
                        },
                    )
                }
            }
        }

        if (item.queueState == OcrScanQueueEntry.State.SCANNING && item.totalPages != null && item.totalPages > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinearProgressIndicator(
                    progress = { item.processedPages.toFloat() / item.totalPages.toFloat() },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${item.processedPages}/${item.totalPages}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            Text(
                text = stateLabel.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (item.queueState == OcrScanQueueEntry.State.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
