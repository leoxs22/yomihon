package eu.kanade.presentation.reader

import android.graphics.RectF
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.dictionary.components.DictResultContentScale
import eu.kanade.presentation.dictionary.components.DictionaryResults
import eu.kanade.tachiyomi.ui.dictionary.DictionarySearchScreenModel
import mihon.domain.dictionary.model.DictionaryTerm
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun OcrResultPopup(
    onDismissRequest: () -> Unit,
    anchorRect: RectF,
    settings: OcrResultPopupSettings,
    onCopyText: () -> Unit,
    searchState: DictionarySearchScreenModel.State,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onTermGroupClick: (List<DictionaryTerm>) -> Unit,
    onPlayAudioClick: (List<DictionaryTerm>) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val marginPx = with(density) { 8.dp.toPx() }
        val gapPx = marginPx
        val viewportWidthPx = constraints.maxWidth.toFloat()
        val viewportHeightPx = constraints.maxHeight.toFloat()
        val preferredPopupWidthPx = with(density) {
            settings.widthDp.dp.toPx().coerceAtMost((viewportWidthPx - (marginPx * 2)).coerceAtLeast(1f))
        }
        val preferredPopupHeightPx = with(density) {
            settings.heightDp.dp.toPx().coerceAtMost((viewportHeightPx - (marginPx * 2)).coerceAtLeast(1f))
        }
        val contentScale = settings.contentScale.coerceAtLeast(0.1f)
        val scaledDensity =
            remember(density, contentScale) {
                Density(
                    density = density.density * contentScale,
                    fontScale = density.fontScale,
                )
            }

        val placement =
            remember(anchorRect, preferredPopupWidthPx, preferredPopupHeightPx, viewportWidthPx, viewportHeightPx, gapPx, marginPx) {
                calculatePopupPlacement(
                    anchorRect = anchorRect,
                    preferredPopupWidthPx = preferredPopupWidthPx,
                    preferredPopupHeightPx = preferredPopupHeightPx,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    gapPx = gapPx,
                    marginPx = marginPx,
                )
            }

        if (placement == null) {
            OcrResultBottomSheet(
                onDismissRequest = onDismissRequest,
                onCopyText = onCopyText,
                searchState = searchState,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                onTermGroupClick = onTermGroupClick,
                onPlayAudioClick = onPlayAudioClick,
            )
        } else {
            Surface(
                modifier = Modifier
                    .width(with(density) { placement.width.toDp() })
                    .height(with(density) { placement.height.toDp() })
                    .offset {
                        IntOffset(
                            placement.x.roundToInt(),
                            placement.y.roundToInt(),
                        )
                    }
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds(),
                ) {
                    CompositionLocalProvider(
                        LocalDensity provides scaledDensity,
                        DictResultContentScale provides contentScale,
                    ) {
                        DictionaryResults(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxSize(),
                            query = searchState.results?.query ?: "",
                            highlightRange = searchState.results?.highlightRange,
                            showQueryHeader = false,
                            isLoading = searchState.isLoading,
                            isSearching = searchState.isSearching,
                            hasSearched = searchState.hasSearched,
                            searchResults = searchState.results?.items ?: emptyList(),
                            dictionaries = searchState.dictionaries,
                            enabledDictionaryIds = searchState.enabledDictionaryIds.toSet(),
                            termMetaMap = searchState.results?.termMetaMap ?: emptyMap(),
                            existingTermExpressions = searchState.existingTermExpressions,
                            audioStates = searchState.audioStates,
                            onTermGroupClick = onTermGroupClick,
                            onPlayAudioClick = onPlayAudioClick,
                            onQueryChange = onQueryChange,
                            onSearch = onSearch,
                            onCopyText = null,
                            contentPadding = PaddingValues(8.dp),
                        )
                    }
                }
            }
        }
    }
}

internal data class PopupPlacement(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

internal fun calculatePopupPlacement(
    anchorRect: RectF,
    preferredPopupWidthPx: Float,
    preferredPopupHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
): PopupPlacement? {
    return calculatePopupPlacement(
        anchorLeft = anchorRect.left,
        anchorTop = anchorRect.top,
        anchorRight = anchorRect.right,
        anchorBottom = anchorRect.bottom,
        preferredPopupWidthPx = preferredPopupWidthPx,
        preferredPopupHeightPx = preferredPopupHeightPx,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        gapPx = gapPx,
        marginPx = marginPx,
    )
}

internal fun calculatePopupPlacement(
    anchorLeft: Float,
    anchorTop: Float,
    anchorRight: Float,
    anchorBottom: Float,
    preferredPopupWidthPx: Float,
    preferredPopupHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    gapPx: Float,
    marginPx: Float,
): PopupPlacement? {
    val availableViewportWidth = (viewportWidthPx - (marginPx * 2)).coerceAtLeast(0f)
    val availableViewportHeight = (viewportHeightPx - (marginPx * 2)).coerceAtLeast(0f)
    if (availableViewportWidth <= 0f || availableViewportHeight <= 0f) {
        return null
    }

    fun clampHorizontal(x: Float, width: Float): Float {
        return x.coerceIn(marginPx, viewportWidthPx - marginPx - width)
    }

    fun clampVertical(y: Float, height: Float): Float {
        return y.coerceIn(marginPx, viewportHeightPx - marginPx - height)
    }

    data class CandidatePlacement(
        val placement: PopupPlacement,
        val score: Float,
        val priority: Int,
    )

    val candidates = buildList {
        val rightX = max(anchorRight + gapPx, marginPx)
        val rightWidth = min(preferredPopupWidthPx, viewportWidthPx - marginPx - rightX)
        if (rightWidth > 0f) {
            val rightHeight = min(preferredPopupHeightPx, availableViewportHeight)
            add(
                CandidatePlacement(
                    placement = PopupPlacement(
                        x = rightX,
                        y = clampVertical(anchorTop, rightHeight),
                        width = rightWidth,
                        height = rightHeight,
                    ),
                    score = rightWidth * rightHeight,
                    priority = 0,
                ),
            )
        }

        val leftWidth = min(preferredPopupWidthPx, anchorLeft - gapPx - marginPx)
        if (leftWidth > 0f) {
            val leftHeight = min(preferredPopupHeightPx, availableViewportHeight)
            add(
                CandidatePlacement(
                    placement = PopupPlacement(
                        x = anchorLeft - gapPx - leftWidth,
                        y = clampVertical(anchorTop, leftHeight),
                        width = leftWidth,
                        height = leftHeight,
                    ),
                    score = leftWidth * leftHeight,
                    priority = 1,
                ),
            )
        }

        val belowY = max(anchorBottom + gapPx, marginPx)
        val belowHeight = min(preferredPopupHeightPx, viewportHeightPx - marginPx - belowY)
        if (belowHeight > 0f) {
            val belowWidth = min(preferredPopupWidthPx, availableViewportWidth)
            add(
                CandidatePlacement(
                    placement = PopupPlacement(
                        x = clampHorizontal(anchorLeft, belowWidth),
                        y = belowY,
                        width = belowWidth,
                        height = belowHeight,
                    ),
                    score = belowWidth * belowHeight,
                    priority = 2,
                ),
            )
        }

        val aboveHeight = min(preferredPopupHeightPx, anchorTop - gapPx - marginPx)
        if (aboveHeight > 0f) {
            val aboveWidth = min(preferredPopupWidthPx, availableViewportWidth)
            add(
                CandidatePlacement(
                    placement = PopupPlacement(
                        x = clampHorizontal(anchorLeft, aboveWidth),
                        y = anchorTop - gapPx - aboveHeight,
                        width = aboveWidth,
                        height = aboveHeight,
                    ),
                    score = aboveWidth * aboveHeight,
                    priority = 3,
                ),
            )
        }

        add(
            CandidatePlacement(
                placement = PopupPlacement(
                    x = clampHorizontal((viewportWidthPx - preferredPopupWidthPx) / 2f, min(preferredPopupWidthPx, availableViewportWidth)),
                    y = clampVertical((viewportHeightPx - preferredPopupHeightPx) / 2f, min(preferredPopupHeightPx, availableViewportHeight)),
                    width = min(preferredPopupWidthPx, availableViewportWidth),
                    height = min(preferredPopupHeightPx, availableViewportHeight),
                ),
                score = availableViewportWidth * availableViewportHeight,
                priority = 4,
            ),
        )
    }

    return candidates
        .sortedWith(compareByDescending<CandidatePlacement> { it.score }.thenBy { it.priority })
        .firstOrNull()
        ?.placement
}

internal fun rectsIntersect(
    firstLeft: Float,
    firstTop: Float,
    firstRight: Float,
    firstBottom: Float,
    secondLeft: Float,
    secondTop: Float,
    secondRight: Float,
    secondBottom: Float,
): Boolean {
    return firstLeft < secondRight &&
        firstRight > secondLeft &&
        firstTop < secondBottom &&
        firstBottom > secondTop
}
