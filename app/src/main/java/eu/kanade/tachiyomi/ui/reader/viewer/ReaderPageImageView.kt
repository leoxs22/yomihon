package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.AttrRes
import androidx.annotation.CallSuper
import androidx.annotation.StyleRes
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.os.postDelayed
import androidx.core.view.isVisible
import coil3.BitmapImage
import coil3.asDrawable
import coil3.dispose
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.ViewSizeResolver
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_IN_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.EASE_OUT_QUAD
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
import com.github.chrisbanes.photoview.PhotoView
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.coil.cropBorders
import eu.kanade.tachiyomi.data.coil.customDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonSubsamplingImageView
import eu.kanade.tachiyomi.util.system.animatorDurationScale
import eu.kanade.tachiyomi.util.view.isVisibleOnScreen
import mihon.domain.ocr.model.OcrBoundingBox
import mihon.domain.ocr.model.OcrPageResult
import mihon.domain.ocr.model.OcrTextOrientation
import mihon.domain.ocr.model.flattenOcrTextForQuery
import okio.BufferedSource
import tachiyomi.core.common.util.system.ImageUtil
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.math.max
import kotlin.math.min

/**
 * A wrapper view for showing page image.
 *
 * Animated image will be drawn by [PhotoView] while [SubsamplingScaleImageView] will take non-animated image.
 *
 * @param isWebtoon if true, [WebtoonSubsamplingImageView] will be used instead of [SubsamplingScaleImageView]
 * and [AppCompatImageView] will be used instead of [PhotoView]
 */
open class ReaderPageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    @AttrRes defStyleAttrs: Int = 0,
    @StyleRes defStyleRes: Int = 0,
    private val isWebtoon: Boolean = false,
) : FrameLayout(context, attrs, defStyleAttrs, defStyleRes) {

    private val alwaysDecodeLongStripWithSSIV by lazy {
        Injekt.get<BasePreferences>().alwaysDecodeLongStripWithSSIV().get()
    }

    private var pageView: View? = null

    private var config: Config? = null
    private var cachedOcrResult: OcrPageResult? = null
    private var ocrPageIdentity: ReaderOcrPageIdentity? = null
    private var activeOcrOverlay: ReaderActiveOcrOverlay? = null
    private var activeOverlayLayout: OverlayTextLayout? = null

    private val ocrOverlayBackgroundPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(176, 16, 16, 16)
        }
    private val ocrOverlayStrokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(160, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = resources.displayMetrics.density * 1.5f
        }
    private val ocrOverlayTextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
        }

    var onImageLoaded: (() -> Unit)? = null
    var onImageLoadError: ((Throwable?) -> Unit)? = null
    var onScaleChanged: ((newScale: Float) -> Unit)? = null
    var onViewClicked: (() -> Unit)? = null
    var onOcrRegionClicked: ((ReaderPageOcrRegionTap) -> Unit)? = null

    /**
     * For automatic background. Will be set as background color when [onImageLoaded] is called.
     */
    var pageBackground: Drawable? = null

    @CallSuper
    open fun onImageLoaded() {
        onImageLoaded?.invoke()
        background = pageBackground
    }

    @CallSuper
    open fun onImageLoadError(error: Throwable?) {
        onImageLoadError?.invoke(error)
    }

    @CallSuper
    open fun onScaleChanged(newScale: Float) {
        onScaleChanged?.invoke(newScale)
        invalidateActiveOverlayLayout()
        invalidate()
    }

    @CallSuper
    open fun onViewClicked() {
        onViewClicked?.invoke()
    }

    open fun onPageSelected(forward: Boolean) {
        with(pageView as? SubsamplingScaleImageView) {
            if (this == null) return
            if (isReady) {
                landscapeZoom(forward)
            } else {
                setOnImageEventListener(
                    object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                        override fun onReady() {
                            setupZoom(config)
                            landscapeZoom(forward)
                            this@ReaderPageImageView.onImageLoaded()
                        }

                        override fun onImageLoadError(e: Exception) {
                            onImageLoadError(e)
                        }
                    },
                )
            }
        }
    }

    private fun SubsamplingScaleImageView.landscapeZoom(forward: Boolean) {
        if (
            config != null &&
            config!!.landscapeZoom &&
            config!!.minimumScaleType == SCALE_TYPE_CENTER_INSIDE &&
            sWidth > sHeight &&
            scale == minScale
        ) {
            handler?.postDelayed(500) {
                val point = when (config!!.zoomStartPosition) {
                    ZoomStartPosition.LEFT -> if (forward) PointF(0F, 0F) else PointF(sWidth.toFloat(), 0F)
                    ZoomStartPosition.RIGHT -> if (forward) PointF(sWidth.toFloat(), 0F) else PointF(0F, 0F)
                    ZoomStartPosition.CENTER -> center
                }

                val targetScale = height.toFloat() / sHeight.toFloat()
                animateScaleAndCenter(targetScale, point)!!
                    .withDuration(500)
                    .withEasing(EASE_IN_OUT_QUAD)
                    .withInterruptible(true)
                    .start()
            }
        }
    }

    fun setImage(drawable: Drawable, config: Config) {
        this.config = config
        if (drawable is Animatable) {
            prepareAnimatedImageView()
            setAnimatedImage(drawable, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(drawable, config)
        }
    }

    fun setImage(source: BufferedSource, isAnimated: Boolean, config: Config) {
        this.config = config
        if (isAnimated) {
            prepareAnimatedImageView()
            setAnimatedImage(source, config)
        } else {
            prepareNonAnimatedImageView()
            setNonAnimatedImage(source, config)
        }
    }

    fun recycle() = pageView?.let {
        clearOcrPageIdentity()
        clearCachedOcrResult()
        when (it) {
            is SubsamplingScaleImageView -> it.recycle()
            is AppCompatImageView -> it.dispose()
        }
        it.isVisible = false
    }

    fun setOcrPageIdentity(
        chapterId: Long?,
        pageIndex: Int,
    ) {
        ocrPageIdentity = chapterId?.let { ReaderOcrPageIdentity(it, pageIndex) }
    }

    fun clearOcrPageIdentity() {
        ocrPageIdentity = null
        setActiveOcrOverlay(null)
    }

    fun matchesOcrPage(pageIdentity: ReaderOcrPageIdentity): Boolean {
        return ocrPageIdentity == pageIdentity
    }

    fun setActiveOcrOverlay(overlay: ReaderActiveOcrOverlay?) {
        activeOcrOverlay = overlay
        invalidateActiveOverlayLayout()
        invalidate()
    }

    /**
     * Check if the image can be panned to the left
     */
    fun canPanLeft(): Boolean = canPan { it.left }

    /**
     * Check if the image can be panned to the right
     */
    fun canPanRight(): Boolean = canPan { it.right }

    /**
     * Check whether the image can be panned.
     * @param fn a function that returns the direction to check for
     */
    private fun canPan(fn: (RectF) -> Float): Boolean {
        (pageView as? SubsamplingScaleImageView)?.let { view ->
            RectF().let {
                view.getPanRemaining(it)
                return fn(it) > 1
            }
        }
        return false
    }

    /**
     * Pans the image to the left by a screen's width worth.
     */
    fun panLeft() {
        pan { center, view -> center.also { it.x -= view.width / view.scale } }
    }

    /**
     * Pans the image to the right by a screen's width worth.
     */
    fun panRight() {
        pan { center, view -> center.also { it.x += view.width / view.scale } }
    }

    /**
     * Pans the image.
     * @param fn a function that computes the new center of the image
     */
    private fun pan(fn: (PointF, SubsamplingScaleImageView) -> PointF) {
        (pageView as? SubsamplingScaleImageView)?.let { view ->

            val target = fn(view.center ?: return, view)
            view.animateCenter(target)!!
                .withEasing(EASE_OUT_QUAD)
                .withDuration(250)
                .withInterruptible(true)
                .start()
        }
    }

    private fun prepareNonAnimatedImageView() {
        if (pageView is SubsamplingScaleImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            WebtoonSubsamplingImageView(context)
        } else {
            SubsamplingScaleImageView(context)
        }.apply {
            setMaxTileSize(ImageUtil.hardwareBitmapThreshold)
            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
            setMinimumTileDpi(180)
            setOnStateChangedListener(
                object : SubsamplingScaleImageView.OnStateChangedListener {
                    override fun onScaleChanged(newScale: Float, origin: Int) {
                        this@ReaderPageImageView.onScaleChanged(newScale)
                    }

                    override fun onCenterChanged(newCenter: PointF?, origin: Int) {
                        invalidate()
                    }
                },
            )
            setOnClickListener { this@ReaderPageImageView.onViewClicked() }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun SubsamplingScaleImageView.setupZoom(config: Config?) {
        // 5x zoom
        maxScale = scale * MAX_ZOOM_SCALE
        setDoubleTapZoomScale(scale * 2)

        when (config?.zoomStartPosition) {
            ZoomStartPosition.LEFT -> setScaleAndCenter(scale, PointF(0F, 0F))
            ZoomStartPosition.RIGHT -> setScaleAndCenter(scale, PointF(sWidth.toFloat(), 0F))
            ZoomStartPosition.CENTER -> setScaleAndCenter(scale, center)
            null -> {}
        }
    }

    private fun setNonAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? SubsamplingScaleImageView)?.apply {
        setDoubleTapZoomDuration(config.zoomDuration.getSystemScaledDuration())
        setMinimumScaleType(config.minimumScaleType)
        setMinimumDpi(1) // Just so that very small image will be fit for initial load
        setCropBorders(config.cropBorders)
        setOnImageEventListener(
            object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                override fun onReady() {
                    setupZoom(config)
                    if (isVisibleOnScreen()) landscapeZoom(true)
                    this@ReaderPageImageView.onImageLoaded()
                }

                override fun onImageLoadError(e: Exception) {
                    this@ReaderPageImageView.onImageLoadError(e)
                }
            },
        )

        when (data) {
            is BitmapDrawable -> {
                setImage(ImageSource.bitmap(data.bitmap))
                isVisible = true
            }
            is BufferedSource -> {
                if (!isWebtoon || alwaysDecodeLongStripWithSSIV) {
                    setHardwareConfig(ImageUtil.canUseHardwareBitmap(data))
                    setImage(ImageSource.inputStream(data.inputStream()))
                    isVisible = true
                    return@apply
                }

                ImageRequest.Builder(context)
                    .data(data)
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .target(
                        onSuccess = { result ->
                            val image = result as BitmapImage
                            setImage(ImageSource.bitmap(image.bitmap))
                            isVisible = true
                        },
                    )
                    .listener(
                        onError = { _, result ->
                            onImageLoadError(result.throwable)
                        },
                    )
                    .size(ViewSizeResolver(this@ReaderPageImageView))
                    .precision(Precision.INEXACT)
                    .cropBorders(config.cropBorders)
                    .customDecoder(true)
                    .crossfade(false)
                    .build()
                    .let(context.imageLoader::enqueue)
            }
            else -> {
                throw IllegalArgumentException("Not implemented for class ${data::class.simpleName}")
            }
        }
    }

    private fun prepareAnimatedImageView() {
        if (pageView is AppCompatImageView) return
        removeView(pageView)

        pageView = if (isWebtoon) {
            AppCompatImageView(context)
        } else {
            PhotoView(context)
        }.apply {
            adjustViewBounds = true

            if (this is PhotoView) {
                setScaleLevels(1F, 2F, MAX_ZOOM_SCALE)
                setOnMatrixChangeListener {
                    invalidate()
                }
                // Force 2 scale levels on double tap
                setOnDoubleTapListener(
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDoubleTap(e: MotionEvent): Boolean {
                            if (scale > 1F) {
                                setScale(1F, e.x, e.y, true)
                            } else {
                                setScale(2F, e.x, e.y, true)
                            }
                            return true
                        }

                        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                            this@ReaderPageImageView.onViewClicked()
                            return super.onSingleTapConfirmed(e)
                        }
                    },
                )
                setOnScaleChangeListener { _, _, _ ->
                    this@ReaderPageImageView.onScaleChanged(scale)
                }
            }
        }
        addView(pageView, MATCH_PARENT, MATCH_PARENT)
    }

    private fun setAnimatedImage(
        data: Any,
        config: Config,
    ) = (pageView as? AppCompatImageView)?.apply {
        if (this is PhotoView) {
            setZoomTransitionDuration(config.zoomDuration.getSystemScaledDuration())
        }

        val request = ImageRequest.Builder(context)
            .data(data)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .target(
                onSuccess = { result ->
                    val drawable = result.asDrawable(context.resources)
                    setImageDrawable(drawable)
                    (drawable as? Animatable)?.start()
                    isVisible = true
                    this@ReaderPageImageView.onImageLoaded()
                },
            )
            .listener(
                onError = { _, result ->
                    onImageLoadError(result.throwable)
                },
            )
            .crossfade(false)
            .build()
        context.imageLoader.enqueue(request)
    }

    fun setCachedOcrResult(result: OcrPageResult?) {
        cachedOcrResult = result
        invalidateActiveOverlayLayout()
        invalidate()
    }

    fun clearCachedOcrResult() {
        cachedOcrResult = null
        invalidateActiveOverlayLayout()
        invalidate()
    }

    fun tryConsumeOcrTap(rawX: Float, rawY: Float): Boolean {
        val localPoint = rawPointToLocalPoint(rawX, rawY) ?: return false
        return tryConsumeOcrTapLocal(localPoint.x, localPoint.y)
    }

    fun tryConsumeActiveOcrOverlayTap(rawX: Float, rawY: Float): ReaderActiveOcrTapResult? {
        val localPoint = rawPointToLocalPoint(rawX, rawY) ?: return null
        return tryConsumeActiveOcrOverlayTapLocal(localPoint.x, localPoint.y)
    }

    fun tryConsumeOcrTapLocal(localX: Float, localY: Float): Boolean {
        val result = cachedOcrResult ?: return false
        val sourcePoint = localPointToSourcePoint(localX, localY) ?: return false
        val region = result.findRegionAt(sourcePoint.x, sourcePoint.y) ?: return false

        onOcrRegionClicked?.invoke(
            ReaderPageOcrRegionTap(
                regionOrder = region.order,
                displayText = region.text,
                queryText = flattenOcrTextForQuery(region.text),
                boundingBox = region.boundingBox,
                textOrientation = region.textOrientation,
                anchorRectOnScreen = boundingBoxToScreenRect(region.boundingBox, result),
                initialSelectionOffset = resolveInitialSelectionOffset(region, result, localX, localY),
            ),
        )
        return true
    }

    fun tryConsumeActiveOcrOverlayTapLocal(
        localX: Float,
        localY: Float,
    ): ReaderActiveOcrTapResult? {
        val overlayLayout = getOrBuildActiveOverlayLayout() ?: return null
        if (!overlayLayout.bubbleRect.contains(localX, localY)) return null
        return if (isPointNearOverlayText(overlayLayout, localX, localY)) {
            ReaderActiveOcrTapResult.SelectWord(
                resolveSelectionOffset(overlayLayout, localX, localY),
            )
        } else {
            ReaderActiveOcrTapResult.BubbleTap
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        drawActiveOcrOverlay(canvas)
    }

    private fun drawActiveOcrOverlay(canvas: Canvas) {
        val overlayLayout = getOrBuildActiveOverlayLayout() ?: return
        canvas.drawRect(overlayLayout.bubbleRect, ocrOverlayBackgroundPaint)
        canvas.drawRect(overlayLayout.bubbleRect, ocrOverlayStrokePaint)
        when (overlayLayout) {
            is OverlayTextLayout.Horizontal -> drawHorizontalOverlayText(canvas, overlayLayout)
            is OverlayTextLayout.Vertical -> drawVerticalOverlayText(canvas, overlayLayout)
        }
    }

    private fun drawHorizontalOverlayText(canvas: Canvas, overlayLayout: OverlayTextLayout.Horizontal) {
        ocrOverlayTextPaint.textSize = overlayLayout.textSizePx
        overlayLayout.lines.forEach { line ->
            line.highlightSegments.forEach { segment ->
                canvas.drawRect(segment, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(220, 255, 214, 10)
                })
            }
            ocrOverlayTextPaint.color = Color.WHITE
            canvas.drawText(line.text, line.left, line.baselineY, ocrOverlayTextPaint)
        }
    }

    private fun drawVerticalOverlayText(canvas: Canvas, overlayLayout: OverlayTextLayout.Vertical) {
        overlayLayout.glyphs.forEach { glyph ->
            if (glyph.isHighlighted) {
                canvas.drawRoundRect(glyph.rect, resources.displayMetrics.density * 4f, resources.displayMetrics.density * 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(220, 255, 214, 10)
                })
            }
            ocrOverlayTextPaint.textSize = overlayLayout.textSizePx
            ocrOverlayTextPaint.color = if (glyph.isHighlighted) Color.BLACK else Color.WHITE
            val textWidth = ocrOverlayTextPaint.measureText(glyph.char)
            val textX = glyph.rect.left + ((glyph.rect.width() - textWidth) / 2f)
            canvas.drawText(glyph.char, textX, glyph.baselineY, ocrOverlayTextPaint)
        }
    }

    private fun resolveInitialSelectionOffset(
        region: mihon.domain.ocr.model.OcrRegion,
        pageResult: OcrPageResult,
        localX: Float,
        localY: Float,
    ): Int {
        val overlayLayout = buildOverlayTextLayout(
            bubbleRect = boundingBoxToLocalRect(region.boundingBox, pageResult) ?: return 0,
            text = region.text,
            textOrientation = region.textOrientation,
            highlightRange = null,
        ) ?: return 0
        return if (isPointNearOverlayText(overlayLayout, localX, localY)) {
            resolveSelectionOffset(overlayLayout, localX, localY)
        } else {
            0
        }
    }

    private fun getOrBuildActiveOverlayLayout(): OverlayTextLayout? {
        activeOverlayLayout?.let { return it }

        val overlay = activeOcrOverlay ?: return null
        val result = cachedOcrResult ?: return null
        val bubbleRect = boundingBoxToLocalRect(overlay.boundingBox, result) ?: return null
        return buildOverlayTextLayout(
            bubbleRect = bubbleRect,
            text = overlay.displayText,
            textOrientation = overlay.textOrientation,
            highlightRange = overlay.highlightRange,
        )?.also {
            activeOverlayLayout = it
        }
    }

    private fun buildOverlayTextLayout(
        bubbleRect: RectF,
        text: String,
        textOrientation: OcrTextOrientation,
        highlightRange: Pair<Int, Int>?,
    ): OverlayTextLayout? {
        if (text.isBlank() || bubbleRect.width() <= 0f || bubbleRect.height() <= 0f) return null

        val density = resources.displayMetrics.density
        val scaledDensity = resources.displayMetrics.scaledDensity
        val contentRect = RectF(
            bubbleRect.left,
            bubbleRect.top,
            bubbleRect.right,
            bubbleRect.bottom,
        )
        return when (textOrientation) {
            OcrTextOrientation.Horizontal -> buildHorizontalOverlayTextLayout(contentRect, bubbleRect, text, highlightRange, scaledDensity)
            OcrTextOrientation.Vertical -> buildVerticalOverlayTextLayout(contentRect, bubbleRect, text, highlightRange, scaledDensity)
        }
    }

    private fun buildOverlayTextSpan(
        text: String,
        highlightRange: Pair<Int, Int>?,
    ): CharSequence {
        if (highlightRange == null || highlightRange.first >= highlightRange.second) {
            return text
        }

        val start = highlightRange.first.coerceIn(0, text.length)
        val end = highlightRange.second.coerceIn(start, text.length)
        if (start == end) return text

        return SpannableString(text).apply {
            setSpan(BackgroundColorSpan(Color.argb(220, 255, 214, 10)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Color.BLACK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun buildHorizontalOverlayTextLayout(
        contentRect: RectF,
        bubbleRect: RectF,
        text: String,
        highlightRange: Pair<Int, Int>?,
        scaledDensity: Float,
    ): OverlayTextLayout? {
        val lines = text.split('\n')
        val maxTextSizePx = maxOf(
            12f * scaledDensity,
            minOf(contentRect.height(), contentRect.width()) * 0.9f,
        )
        val minTextSizePx = minOf(maxTextSizePx, 6f * scaledDensity)
        val step = (scaledDensity * 0.5f).coerceAtLeast(0.5f)

        var textSizePx = maxTextSizePx
        while (textSizePx >= minTextSizePx) {
            val layout = createHorizontalOverlayLayout(
                bubbleRect = bubbleRect,
                contentRect = contentRect,
                lines = lines,
                text = text,
                highlightRange = highlightRange,
                textSizePx = textSizePx,
            )
            if (layout != null) {
                return layout
            }
            textSizePx -= step
        }

        return null
    }

    private fun createHorizontalOverlayLayout(
        bubbleRect: RectF,
        contentRect: RectF,
        lines: List<String>,
        text: String,
        highlightRange: Pair<Int, Int>?,
        textSizePx: Float,
    ): OverlayTextLayout.Horizontal? {
        ocrOverlayTextPaint.textSize = textSizePx
        val fontMetrics = ocrOverlayTextPaint.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top
        if (lineHeight <= 0f) return null

        val maxLineWidth = lines.maxOfOrNull { ocrOverlayTextPaint.measureText(it) } ?: 0f
        if (maxLineWidth > contentRect.width() || lineHeight > contentRect.height()) {
            return null
        }

        val lineCount = lines.size.coerceAtLeast(1)
        val lineTopStep = if (lineCount == 1) 0f else (contentRect.height() - lineHeight) / (lineCount - 1)
        val linePlacements = mutableListOf<HorizontalLinePlacement>()
        var displayOffset = 0
        lines.forEachIndexed { index, line ->
            val lineTop = contentRect.top + (lineTopStep * index)
            val baselineY = lineTop - fontMetrics.top
            val lineBottom = lineTop + lineHeight
            val highlightSegments = mutableListOf<RectF>()

            line.forEachIndexed { charIndex, _ ->
                val charStart = displayOffset + charIndex
                if (highlightRange != null && charStart in highlightRange.first until highlightRange.second) {
                    val left = contentRect.left + ocrOverlayTextPaint.measureText(line, 0, charIndex)
                    val right = contentRect.left + ocrOverlayTextPaint.measureText(line, 0, charIndex + 1)
                    highlightSegments += RectF(left, lineTop, right, lineBottom)
                }
            }

            linePlacements += HorizontalLinePlacement(
                text = line,
                left = contentRect.left,
                top = lineTop,
                bottom = lineBottom,
                baselineY = baselineY,
                startDisplayOffset = displayOffset,
                highlightSegments = highlightSegments,
            )
            displayOffset += line.length
            if (index < lines.lastIndex) {
                displayOffset++
            }
        }

        return OverlayTextLayout.Horizontal(
            bubbleRect = RectF(bubbleRect),
            textRect = RectF(contentRect),
            lines = linePlacements,
            displayText = text,
            textSizePx = textSizePx,
        )
    }

    private fun buildVerticalOverlayTextLayout(
        contentRect: RectF,
        bubbleRect: RectF,
        text: String,
        highlightRange: Pair<Int, Int>?,
        scaledDensity: Float,
    ): OverlayTextLayout? {
        val lines = text.split('\n')
        val maxCharsInColumn = lines.maxOfOrNull(String::length)?.coerceAtLeast(1) ?: return null
        val columnCount = lines.size.coerceAtLeast(1)
        val maxTextSizePx = maxOf(
            12f * scaledDensity,
            minOf(contentRect.height() / maxCharsInColumn, contentRect.width() / columnCount) * 1.2f,
        )
        val minTextSizePx = minOf(maxTextSizePx, 6f * scaledDensity)
        val step = (scaledDensity * 0.5f).coerceAtLeast(0.5f)

        var textSizePx = maxTextSizePx
        while (textSizePx >= minTextSizePx) {
            val layout = createVerticalOverlayLayout(
                bubbleRect = bubbleRect,
                contentRect = contentRect,
                text = text,
                highlightRange = highlightRange,
                textSizePx = textSizePx,
            )
            if (layout != null) {
                return layout
            }
            textSizePx -= step
        }

        return null
    }

    private fun createVerticalOverlayLayout(
        bubbleRect: RectF,
        contentRect: RectF,
        text: String,
        highlightRange: Pair<Int, Int>?,
        textSizePx: Float,
    ): OverlayTextLayout.Vertical? {
        ocrOverlayTextPaint.textSize = textSizePx
        val fontMetrics = ocrOverlayTextPaint.fontMetrics
        val lineHeight = fontMetrics.bottom - fontMetrics.top
        if (lineHeight <= 0f) return null

        val lines = text.split('\n')
        val columnCount = lines.size.coerceAtLeast(1)
        val columnWidth = contentRect.width() / columnCount
        if (columnWidth <= 0f) return null

        val lineData = mutableListOf<VerticalLineData>()
        var displayOffset = 0
        for ((lineIndex, line) in lines.withIndex()) {
            val glyphs = mutableListOf<VerticalGlyph>()
            line.forEachIndexed { charIndex, char ->
                val glyphWidth = maxOf(ocrOverlayTextPaint.measureText(char.toString()), textSizePx * 0.85f)
                val isHighlighted = highlightRange?.let { displayOffset in it.first until it.second } == true
                glyphs += VerticalGlyph(
                    char = char.toString(),
                    displayOffset = displayOffset,
                    glyphWidth = glyphWidth,
                    isHighlighted = isHighlighted,
                    indexInColumn = charIndex,
                )
                displayOffset++
            }
            lineData += VerticalLineData(
                glyphs = glyphs,
                lineWidth = glyphs.maxOfOrNull { it.glyphWidth }?.coerceAtLeast(textSizePx * 0.85f) ?: textSizePx * 0.85f,
            )
            if (lineIndex < lines.lastIndex) {
                displayOffset++
            }
        }

        if (lineData.any { it.lineWidth > columnWidth } || lineHeight > contentRect.height()) {
            return null
        }

        val placedGlyphs = mutableListOf<VerticalGlyphPlacement>()
        lineData.forEachIndexed { columnIndex, line ->
            val columnLeft = contentRect.right - ((columnIndex + 1) * columnWidth)
            val columnRight = columnLeft + columnWidth
            val glyphTopStep = if (line.glyphs.size <= 1) 0f else (contentRect.height() - lineHeight) / (line.glyphs.size - 1)
            line.glyphs.forEach { glyph ->
                val glyphTop = contentRect.top + (glyph.indexInColumn * glyphTopStep)
                placedGlyphs += VerticalGlyphPlacement(
                    char = glyph.char,
                    displayOffset = glyph.displayOffset,
                    rect = RectF(columnLeft, glyphTop, columnRight, glyphTop + lineHeight),
                    baselineY = glyphTop - fontMetrics.top,
                    isHighlighted = glyph.isHighlighted,
                )
            }
        }

        return OverlayTextLayout.Vertical(
            bubbleRect = RectF(bubbleRect),
            textRect = RectF(contentRect),
            glyphs = placedGlyphs,
            displayText = text,
            textSizePx = textSizePx,
        )
    }

    private fun isPointNearOverlayText(
        overlayLayout: OverlayTextLayout,
        localX: Float,
        localY: Float,
    ): Boolean {
        val allowancePx = resources.displayMetrics.density * 12f
        return when (overlayLayout) {
            is OverlayTextLayout.Horizontal -> {
                overlayLayout.lines.any { line ->
                    val lineRight = line.left + ocrOverlayTextPaint.measureText(line.text)
                    localY >= line.top - allowancePx &&
                        localY <= line.bottom + allowancePx &&
                        localX >= line.left - allowancePx &&
                        localX <= lineRight + allowancePx
                }
            }
            is OverlayTextLayout.Vertical -> overlayLayout.glyphs.any { glyph ->
                localX >= glyph.rect.left - allowancePx &&
                    localX <= glyph.rect.right + allowancePx &&
                    localY >= glyph.rect.top - allowancePx &&
                    localY <= glyph.rect.bottom + allowancePx
            }
        }
    }

    private fun resolveSelectionOffset(
        overlayLayout: OverlayTextLayout,
        localX: Float,
        localY: Float,
    ): Int {
        val displayText = overlayLayout.displayText
        if (displayText.isEmpty()) return 0

        val displayOffset = when (overlayLayout) {
            is OverlayTextLayout.Horizontal -> {
                val line = overlayLayout.lines.minByOrNull { placedLine ->
                    val centerY = (placedLine.top + placedLine.bottom) / 2f
                    kotlin.math.abs(centerY - localY)
                } ?: return 0
                val relativeX = (localX - line.left).coerceAtLeast(0f)
                var charOffsetInLine = line.text.length
                for (index in line.text.indices) {
                    val charCenter = ocrOverlayTextPaint.measureText(line.text, 0, index) +
                        (ocrOverlayTextPaint.measureText(line.text[index].toString()) / 2f)
                    if (relativeX <= charCenter) {
                        charOffsetInLine = index
                        break
                    }
                }
                min(line.startDisplayOffset + charOffsetInLine, displayText.lastIndex)
            }
            is OverlayTextLayout.Vertical -> {
                overlayLayout.glyphs.minByOrNull { glyph ->
                    val centerX = (glyph.rect.left + glyph.rect.right) / 2f
                    val centerY = (glyph.rect.top + glyph.rect.bottom) / 2f
                    val deltaX = centerX - localX
                    val deltaY = centerY - localY
                    (deltaX * deltaX) + (deltaY * deltaY)
                }?.displayOffset ?: 0
            }
        }

        return displayOffsetToQueryOffset(displayText, displayOffset)
    }

    private fun boundingBoxToLocalRect(
        boundingBox: OcrBoundingBox,
        pageResult: OcrPageResult,
    ): RectF? {
        val localRect = when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                val sourceRect = boundingBox.toSourceRect(pageResult)
                val topLeft = currentPageView.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: return null
                val bottomRight = currentPageView.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: return null
                RectF(
                    minOf(topLeft.x, bottomRight.x),
                    minOf(topLeft.y, bottomRight.y),
                    maxOf(topLeft.x, bottomRight.x),
                    maxOf(topLeft.y, bottomRight.y),
                )
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                val sourceRect = boundingBox.toSourceRect(
                    imageWidth = drawable.intrinsicWidth,
                    imageHeight = drawable.intrinsicHeight,
                )
                RectF(sourceRect).also(currentPageView.imageMatrix::mapRect)
            }
            else -> return null
        }

        return RectF(
            localRect.left.coerceIn(0f, width.toFloat()),
            localRect.top.coerceIn(0f, height.toFloat()),
            localRect.right.coerceIn(0f, width.toFloat()),
            localRect.bottom.coerceIn(0f, height.toFloat()),
        ).takeIf { it.width() > 0f && it.height() > 0f }
    }

    private fun invalidateActiveOverlayLayout() {
        activeOverlayLayout = null
    }

    private fun rawPointToLocalPoint(rawX: Float, rawY: Float): PointF? {
        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        getLocationInWindow(windowLocation)

        return PointF(
            rawX - screenLocation[0] + windowLocation[0],
            rawY - screenLocation[1] + windowLocation[1],
        )
    }

    private fun localPointToSourcePoint(localX: Float, localY: Float): PointF? {
        return when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                currentPageView.viewToSourceCoord(localX, localY)
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                val inverse = Matrix()
                if (!currentPageView.imageMatrix.invert(inverse)) return null

                val points = floatArrayOf(localX, localY)
                inverse.mapPoints(points)
                val sourceX = points[0]
                val sourceY = points[1]
                if (sourceX < 0f || sourceY < 0f ||
                    sourceX > drawable.intrinsicWidth.toFloat() ||
                    sourceY > drawable.intrinsicHeight.toFloat()
                ) {
                    null
                } else {
                    PointF(sourceX, sourceY)
                }
            }
            else -> null
        }
    }

    private fun boundingBoxToScreenRect(
        boundingBox: OcrBoundingBox,
        pageResult: OcrPageResult,
    ): RectF? {
        val localRect = when (val currentPageView = pageView) {
            is SubsamplingScaleImageView -> {
                if (!currentPageView.isReady) return null
                val sourceRect = boundingBox.toSourceRect(pageResult)
                val topLeft = currentPageView.sourceToViewCoord(sourceRect.left, sourceRect.top) ?: return null
                val bottomRight = currentPageView.sourceToViewCoord(sourceRect.right, sourceRect.bottom) ?: return null
                RectF(
                    minOf(topLeft.x, bottomRight.x),
                    minOf(topLeft.y, bottomRight.y),
                    maxOf(topLeft.x, bottomRight.x),
                    maxOf(topLeft.y, bottomRight.y),
                )
            }
            is ImageView -> {
                val drawable = currentPageView.drawable ?: return null
                val sourceRect = boundingBox.toSourceRect(
                    imageWidth = drawable.intrinsicWidth,
                    imageHeight = drawable.intrinsicHeight,
                )
                RectF(sourceRect).also(currentPageView.imageMatrix::mapRect)
            }
            else -> return null
        }

        val screenLocation = IntArray(2)
        val windowLocation = IntArray(2)
        getLocationOnScreen(screenLocation)
        getLocationInWindow(windowLocation)

        return RectF(
            localRect.left + screenLocation[0] - windowLocation[0],
            localRect.top + screenLocation[1] - windowLocation[1],
            localRect.right + screenLocation[0] - windowLocation[0],
            localRect.bottom + screenLocation[1] - windowLocation[1],
        )
    }

    private fun Int.getSystemScaledDuration(): Int {
        return (this * context.animatorDurationScale).toInt().coerceAtLeast(1)
    }

    /**
     * All of the config except [zoomDuration] will only be used for non-animated image.
     */
    data class Config(
        val zoomDuration: Int,
        val minimumScaleType: Int = SCALE_TYPE_CENTER_INSIDE,
        val cropBorders: Boolean = false,
        val zoomStartPosition: ZoomStartPosition = ZoomStartPosition.CENTER,
        val landscapeZoom: Boolean = false,
    )

    enum class ZoomStartPosition {
        LEFT,
        CENTER,
        RIGHT,
    }
}

private const val MAX_ZOOM_SCALE = 5F

private sealed interface OverlayTextLayout {
    val bubbleRect: RectF
    val textRect: RectF
    val displayText: String

    data class Horizontal(
        override val bubbleRect: RectF,
        override val textRect: RectF,
        val lines: List<HorizontalLinePlacement>,
        override val displayText: String,
        val textSizePx: Float,
    ) : OverlayTextLayout

    data class Vertical(
        override val bubbleRect: RectF,
        override val textRect: RectF,
        val glyphs: List<VerticalGlyphPlacement>,
        override val displayText: String,
        val textSizePx: Float,
    ) : OverlayTextLayout
}

private data class VerticalGlyph(
    val char: String,
    val displayOffset: Int,
    val glyphWidth: Float,
    val isHighlighted: Boolean,
    val indexInColumn: Int,
)

private data class VerticalLineData(
    val glyphs: List<VerticalGlyph>,
    val lineWidth: Float,
)

private data class VerticalGlyphPlacement(
    val char: String,
    val displayOffset: Int,
    val rect: RectF,
    val baselineY: Float,
    val isHighlighted: Boolean,
)

private data class HorizontalLinePlacement(
    val text: String,
    val left: Float,
    val top: Float,
    val bottom: Float,
    val baselineY: Float,
    val startDisplayOffset: Int,
    val highlightSegments: List<RectF>,
)

private fun OcrBoundingBox.toSourceRect(pageResult: OcrPageResult): RectF {
    return toSourceRect(
        imageWidth = pageResult.imageWidth,
        imageHeight = pageResult.imageHeight,
    )
}

private fun OcrBoundingBox.toSourceRect(
    imageWidth: Int,
    imageHeight: Int,
): RectF {
    return RectF(
        left * imageWidth,
        top * imageHeight,
        right * imageWidth,
        bottom * imageHeight,
    )
}
