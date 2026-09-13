package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownCustomBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownHtmlBlock
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownMathBlock
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownThematicBreak
import org.tiqian.markdown.MarkdownUnsupportedBlock

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onLayoutRectChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.tiqian.compose.ParagraphMeasurementSession
import org.tiqian.compose.createPlatformParagraphMeasurer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil

internal data class MarkdownDeferredLayout(
    val viewportHeightPx: Int,
    val viewportTopInWindowPx: Float,
    val scrollInProgress: Boolean,
    val scrollOffsetPx: () -> Int,
    val requestedOwnerKeys: Set<Any>,
    val onMaterializeOwner: (Any) -> Boolean,
    // `AnchoredHeightCorrection`: the scroll owner applies this raw pixel delta so height
    // corrections above the viewport never move the content the reader is looking at.
    val compensateScrollBy: (Int) -> Unit = {},
)

internal val LocalMarkdownDeferredLayout = staticCompositionLocalOf<MarkdownDeferredLayout?> { null }

internal data class MarkdownViewportRangePx(
    val top: Float,
    val bottom: Float,
)

/**
 * Maps the window-space viewport into document-content coordinates.
 *
 * `OwnedScrollViewportCoordinates` keeps fixed host chrome out of the visible range: when the
 * surface starts below the window origin, both viewport edges must move down by that same inset.
 */
internal fun markdownViewportRangeRelativeToContent(
    viewportTopInWindowPx: Float,
    viewportHeightPx: Int,
    contentOriginInScrollPx: Float,
    scrollOffsetPx: Int,
): MarkdownViewportRangePx {
    val contentTopInWindowPx = contentOriginInScrollPx
        .takeUnless { it.isNaN() }
        ?.minus(scrollOffsetPx)
        // Before global-position calibration, assume content begins at the owned viewport rather
        // than at window y=0. This may compose an extra header-adjacent block, but can never skip
        // the document start merely because fixed host chrome sits above the surface.
        ?: viewportTopInWindowPx - scrollOffsetPx
    val viewportTop = viewportTopInWindowPx - contentTopInWindowPx
    return MarkdownViewportRangePx(
        top = viewportTop,
        bottom = viewportTop + viewportHeightPx.coerceAtLeast(1),
    )
}

internal fun markdownViewportRangeClampedToContent(
    viewportRange: MarkdownViewportRangePx,
    viewportHeightPx: Int,
    contentHeightPx: Long,
): MarkdownViewportRangePx {
    val height = viewportHeightPx.coerceAtLeast(1)
    val maximumTop = (contentHeightPx - height)
        .coerceAtLeast(0L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toFloat()
    val requestedTop = viewportRange.top.takeIf { it.isFinite() } ?: 0f
    val top = requestedTop.coerceIn(0f, maximumTop)
    return MarkdownViewportRangePx(top = top, bottom = top + height)
}

internal fun deferredMarkdownBlockIndices(
    estimatedHeightsDp: List<Float>,
    density: Float,
    visibleTopPx: Float,
    visibleBottomPx: Float,
    viewportHeightPx: Int,
    // Lookahead belongs to DeferredMarkdownBlocks' background pre-layout. Composing another half
    // viewport synchronously made the 205-block offline fixture lay out 23 paragraphs before first
    // draw; keep this range to blocks that actually intersect the viewport.
    prefetchViewports: Float = 0f,
): IntRange {
    if (estimatedHeightsDp.isEmpty()) return IntRange.EMPTY
    val safeDensity = density.takeIf { it.isFinite() && it > 0f } ?: 1f
    val prefetchPx = viewportHeightPx.coerceAtLeast(1) * prefetchViewports
    val targetTopDp = (visibleTopPx - prefetchPx).coerceAtLeast(0f) / safeDensity
    val targetBottomDp = (visibleBottomPx + prefetchPx).coerceAtLeast(0f) / safeDensity
    var topDp = 0f
    var first = -1
    var last = -1
    estimatedHeightsDp.forEachIndexed { index, heightDp ->
        val bottomDp = topDp + heightDp.coerceAtLeast(0f)
        if (bottomDp >= targetTopDp && topDp <= targetBottomDp) {
            if (first < 0) first = index
            last = index
        }
        topDp = bottomDp
    }
    return if (first < 0) IntRange.EMPTY else first..last
}

internal fun deferredMarkdownBlockIndicesFromPx(
    blockHeightsPx: List<Int>,
    visibleTopPx: Float,
    visibleBottomPx: Float,
    viewportHeightPx: Int,
    prefetchViewports: Float = 0f,
): IntRange {
    if (blockHeightsPx.isEmpty()) return IntRange.EMPTY
    val prefetchPx = viewportHeightPx.coerceAtLeast(1) * prefetchViewports
    val targetTopPx = (visibleTopPx - prefetchPx).coerceAtLeast(0f).toDouble()
    val targetBottomPx = (visibleBottomPx + prefetchPx).coerceAtLeast(0f).toDouble()
    var topPx = 0.0
    var first = -1
    var last = -1
    blockHeightsPx.forEachIndexed { index, heightPx ->
        val bottomPx = topPx + heightPx.coerceAtLeast(0)
        if (bottomPx >= targetTopPx && topPx <= targetBottomPx) {
            if (first < 0) first = index
            last = index
        }
        topPx = bottomPx
    }
    return if (first < 0) IntRange.EMPTY else first..last
}

internal fun shouldUpdateMarkdownMeasuredHeight(
    cachedHeightPx: Int?,
    measuredHeightPx: Int,
): Boolean = measuredHeightPx >= 0 && cachedHeightPx != measuredHeightPx

internal fun estimateMarkdownBlockHeightDp(
    block: MarkdownBlock,
    widthDp: Float,
    style: MarkdownStyle,
): Float {
    val fontSize = style.body.fontSize.value.takeIf { it.isFinite() && it > 0f } ?: 16f
    val lineHeight = style.bodyLineHeightSpOrNull()?.value
        ?.takeIf { it.isFinite() && it > 0f }
        ?: fontSize * 1.625f
    val safeWidth = widthDp.coerceAtLeast(fontSize * 8f)
    val spacing = lineHeight * style.blockSpacingBodyLines
    fun textHeight(text: MarkdownText): Float {
        val units = text.value.sumOf { character ->
            when {
                character == '\n' -> 0.0
                character.code > 0x7f -> 1.0
                character.isWhitespace() -> 0.35
                character in "ilI.,'`:;!|" -> 0.3
                character in "MW@#%&" -> 0.9
                else -> 0.6
            }
        }.toFloat()
        val forcedLines = text.value.count { it == '\n' }
        return (ceil(units * fontSize / safeWidth).toInt().coerceAtLeast(1) + forcedLines) * lineHeight
    }
    fun childrenHeight(children: List<MarkdownBlock>): Float = children
        .sumOf { estimateMarkdownBlockHeightDp(it, safeWidth, style).toDouble() }
        .toFloat() + spacing * (children.size - 1).coerceAtLeast(0)

    return when (block) {
        is MarkdownParagraph -> textHeight(block.text)
        is MarkdownHeading -> lineHeight * when (block.level.coerceIn(1, 6)) {
            1 -> 1.6f
            2 -> 1.4f
            3 -> 1.2f
            else -> 1f
        }
        is MarkdownBlockQuote -> childrenHeight(block.blocks)
        is MarkdownList -> block.items.sumOf { item ->
            childrenHeight(item.blocks).toDouble()
        }.toFloat()
        is MarkdownCodeBlock -> {
            val codeLineHeight = style.codeBlock.lineHeight.value
                .takeIf { it.isFinite() && it > 0f }
                ?: lineHeight
            block.code.lineSequence().count().coerceAtLeast(1) * codeLineHeight +
                style.codePadding.value * 2f
        }
        is MarkdownImageBlock -> {
            val ratio = block.widthPixels
                ?.takeIf { it > 0 }
                ?.let { width -> block.heightPixels?.toFloat()?.div(width) }
                ?: 0.75f
            safeWidth * ratio.coerceIn(0.25f, 2f) + if (block.caption == null) 0f else lineHeight
        }
        is MarkdownMathBlock -> lineHeight * 2f
        is MarkdownTable -> block.rows.size.coerceAtLeast(1) * lineHeight * 1.6f
        is MarkdownFootnoteDefinition -> childrenHeight(block.blocks)
        is MarkdownThematicBreak -> style.tableBorderWidth.value.coerceAtLeast(1f)
        is MarkdownHtmlBlock,
        is MarkdownCustomBlock,
        is MarkdownUnsupportedBlock,
        -> lineHeight * 1.5f
    }.coerceAtLeast(0f) + spacing
}

@Composable
internal fun DeferredMarkdownBlocks(
    blocks: List<MarkdownBlock>,
    estimatedHeightsDp: List<Float>,
    deferredLayout: MarkdownDeferredLayout,
    modifier: Modifier,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
    topLevelProseWidth: Dp?,
    mathRuntime: MarkdownMathRuntime?,
    prelayoutWidth: Dp,
    measurementSession: ParagraphMeasurementSession,
) {
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val viewportHeightPx = deferredLayout.viewportHeightPx
        .takeIf { it > 0 }
        ?: windowHeightPx
    val viewportTopInWindowPx = deferredLayout.viewportTopInWindowPx
    val stateHolder = rememberSaveableStateHolder()
    val measuredHeightsPx = remember(blocks, style, prelayoutWidth, density.density, density.fontScale) {
        // Observability marker: fires only when this retention map is (re)created — repeated
        // firings mean a remember key is unstable and all measured heights are being wiped.
        markdownTraceSection("HeightsMapInit") {}
        mutableStateMapOf<Pair<Int, Any>, Int>()
    }
    val blockKeys = remember(blocks) { blocks.map { it.metadata.key } }
    val estimatedHeightsPx = remember(estimatedHeightsDp, density.density) {
        estimatedHeightsDp.map { estimated ->
            with(density) { estimated.dp.roundToPx() }.coerceAtLeast(1)
        }
    }
    var layoutWidthPx by remember(prelayoutWidth, density.density) {
        mutableIntStateOf(with(density) { prelayoutWidth.roundToPx() }.coerceAtLeast(1))
    }
    val viewportRangeHolder = remember { MarkdownViewportRangeHolder() }
    val footnoteMarkerWidths = rememberFootnoteMarkerWidths(blocks, style)
    val measureWidthHolder = remember(blocks) { MarkdownMeasureWidthHolder() }
    var contentOriginInScrollPx by remember { mutableFloatStateOf(Float.NaN) }
    val scrollAnchor = remember(blocks) { MarkdownScrollAnchor() }
    // `MaterializationSettleHysteresis`: swipe trains pause longer than the prelayout idle delay,
    // so keying the wide ±viewport window off scrollInProgress alone made every inter-swipe gap
    // expand the window and the next swipe collapse it — thousands of churned recompositions and
    // display-list re-records. The window may only widen after a genuine reading pause.
    val materializationSettled = remember(blocks) { mutableStateOf(false) }
    val currentBlockHeightsDp by remember(
        blockKeys,
        estimatedHeightsPx,
        density.density,
    ) {
        derivedStateOf {
            blocks.indices.map { index ->
                val heightPx = measuredHeightsPx[layoutWidthPx to blockKeys[index]]
                    ?: estimatedHeightsPx[index]
                with(density) { heightPx.toDp().value }
            }
        }
    }
    // `VisibleBlockRange`: collapse pixel-by-pixel ScrollState changes into a stable block range.
    // Compose only re-enters the subcomposition measure path when that range actually changes.
    val visibleRange by remember(
        viewportHeightPx,
        viewportTopInWindowPx,
        density.density,
        deferredLayout.scrollOffsetPx,
    ) {
        derivedStateOf {
            val scrollOffsetPx = deferredLayout.scrollOffsetPx()
            val viewportRange = markdownViewportRangeRelativeToContent(
                viewportTopInWindowPx = viewportTopInWindowPx,
                viewportHeightPx = viewportHeightPx,
                contentOriginInScrollPx = contentOriginInScrollPx,
                scrollOffsetPx = scrollOffsetPx,
            )
            // This plain holder is refreshed while the derived range observes ScrollState, but
            // reading it from measure does not subscribe measure to every pixel of scrolling.
            // A range transition still invalidates measure and carries the matching pixel range.
            viewportRangeHolder.range = viewportRange
            deferredMarkdownBlockIndices(
                estimatedHeightsDp = currentBlockHeightsDp,
                density = density.density,
                visibleTopPx = viewportRange.top,
                visibleBottomPx = viewportRange.bottom,
                viewportHeightPx = viewportHeightPx,
            )
        }
    }
    // `TableSubtreePrelayout`: the prose measure is quantized, so the container width can move
    // while `prelayoutWidth` stands still — and table cell layouts were measured for columns
    // negotiated at the container width, yet are consumed by key without a width check. Both
    // caches therefore retire on the container width too, so a resize can never publish cell
    // layouts belonging to the previous column widths.
    val precomputedLayouts = remember(
        blocks,
        style,
        prelayoutWidth,
        layoutWidthPx,
        inlineSlots,
        mathRuntime,
        density.density,
        density.fontScale,
    ) {
        mutableStateMapOf<Any, MarkdownPrecomputedLayout>()
    }
    // Negotiated column widths live beside the prose layouts, keyed by the table's own "widths"
    // key; the renderer only adopts an entry negotiated for its own measure.
    val precomputedTableWidths = remember(
        blocks,
        style,
        prelayoutWidth,
        layoutWidthPx,
        inlineSlots,
        mathRuntime,
        density.density,
        density.fontScale,
    ) {
        mutableStateMapOf<Any, MarkdownPrecomputedTableWidths>()
    }
    // Top-level blocks the worker has fully processed. Container blocks (quotes) store entries
    // under their NESTED blocks' keys, so map membership alone cannot mark the owner as done.
    // `MemoizedBlockContent` cache: one stable composable lambda per block key. Reset whenever
    // any captured input changes identity so stale captures can never leak into subcompose.
    val blockContents = remember(
        blocks,
        style,
        slots,
        inlineSlots,
        onLinkClick,
        onFootnoteClick,
        topLevelProseWidth,
        footnoteMarkerWidths,
        stateHolder,
        measuredHeightsPx,
        precomputedLayouts,
        precomputedTableWidths,
    ) {
        mutableMapOf<Any, @Composable () -> Unit>()
    }
    val prelaidOwnerKeys = remember(
        blocks,
        style,
        prelayoutWidth,
        layoutWidthPx,
        inlineSlots,
        mathRuntime,
        density.density,
        density.fontScale,
    ) {
        mutableSetOf<Any>()
    }
    LaunchedEffect(
        blocks,
        style,
        topLevelProseWidth,
        prelayoutWidth,
        density.density,
        density.fontScale,
        viewportHeightPx,
        viewportTopInWindowPx,
        mathRuntime,
        inlineSlots,
        measurementSession,
        deferredLayout.scrollInProgress,
        // `TableSubtreePrelayout`: tables fill the whole column, so a container-width change
        // invalidates their negotiation even when the prose measure is unchanged.
        layoutWidthPx,
    ) {
        val scrolling = deferredLayout.scrollInProgress
        val widthPx = with(density) { prelayoutWidth.toPx() }.coerceAtLeast(1f)
        // `layoutWidthPx` is seeded from the prose width and only ever set from a positive
        // measured width, so it is already the table's own usable column width.
        val fullWidthPx = layoutWidthPx.toFloat()
        val defaultMath = inlineSlots.math == null && mathRuntime != null
        // `ScrollAheadPrelayout`: while the reader is scrolling, prelayout keeps running inside a
        // bounded look-ahead window in the scroll direction so a block's first render happens off
        // the UI thread BEFORE it enters the viewport; one block per frame keeps gestures
        // uncontended. `IdleWholeDocumentPrelayout`: once scrolling settles for a short quiet
        // period, coverage widens to the whole active document in current reading order. Visible
        // prose never waits for either.
        withFrameNanos { }
        if (scrolling) {
            materializationSettled.value = false
        } else {
            launch {
                delay(NearViewportMaterializationSettleDelayMillis)
                materializationSettled.value = true
            }
        }
        if (!scrolling) delay(BackgroundPrelayoutIdleDelayMillis)
        coroutineScope {
            val wakeWorker = Channel<Unit>(capacity = Channel.CONFLATED)
            var latestVisibleRange: IntRange = IntRange.EMPTY
            var latestForward = true
            var latestHeightsPx = blocks.indices.map { blockIndex ->
                with(density) { estimatedHeightsDp[blockIndex].dp.roundToPx() }
            }

            launch {
                var previousScrollOffsetPx = deferredLayout.scrollOffsetPx()
                snapshotFlow {
                    Triple(
                        deferredLayout.scrollOffsetPx(),
                        contentOriginInScrollPx,
                        measuredHeightsPx.toMap(),
                    )
                }.collect { snapshot ->
                    val currentScrollOffsetPx = snapshot.first
                    val viewportRange = markdownViewportRangeRelativeToContent(
                        viewportTopInWindowPx = viewportTopInWindowPx,
                        viewportHeightPx = viewportHeightPx,
                        contentOriginInScrollPx = snapshot.second,
                        scrollOffsetPx = currentScrollOffsetPx,
                    )
                    val measuredByBlockKey = snapshot.third.entries.associate { (key, height) ->
                        key.second to height
                    }
                    latestHeightsPx = blocks.indices.map { blockIndex ->
                        val blockKey = blocks[blockIndex].metadata.key
                        measuredByBlockKey[blockKey]
                            ?: with(density) { estimatedHeightsDp[blockIndex].dp.roundToPx() }
                    }
                    latestVisibleRange = deferredMarkdownBlockIndices(
                        estimatedHeightsDp = latestHeightsPx.map { with(density) { it.toDp().value } },
                        density = density.density,
                        visibleTopPx = viewportRange.top,
                        visibleBottomPx = viewportRange.bottom,
                        viewportHeightPx = viewportHeightPx,
                    )
                    latestForward = currentScrollOffsetPx >= previousScrollOffsetPx
                    previousScrollOffsetPx = currentScrollOffsetPx
                    wakeWorker.trySend(Unit)
                }
            }

            launch {
                val workerMeasurer = createPlatformParagraphMeasurer(session = measurementSession)
                while (isActive) {
                    wakeWorker.receive()
                    val priority = if (scrolling) {
                        prelayoutMarkdownBlockIndices(
                            blocks = blocks,
                            visibleRange = latestVisibleRange,
                            forward = latestForward,
                            defaultMath = defaultMath,
                            blockHeightsPx = latestHeightsPx,
                            prefetchDistancePx = viewportHeightPx.coerceAtLeast(1) *
                                ScrollAheadPrelayoutViewportBudget,
                        )
                    } else {
                        backgroundPrelayoutPriorityIndices(
                            blocks = blocks,
                            visibleRange = latestVisibleRange,
                            forward = latestForward,
                            defaultMath = defaultMath,
                        )
                    }
                    val index = priority.firstOrNull { candidate ->
                        markdownSelectionKey(blocks[candidate].metadata.key) !in prelaidOwnerKeys
                    } ?: continue
                    val block = blocks[index]
                    val ownerKey = markdownSelectionKey(block.metadata.key)
                    val entries = withContext(Dispatchers.Default) {
                        precomputeMarkdownBlockEntries(
                            block = block,
                            style = style,
                            inlineSlots = inlineSlots,
                            mathRuntime = mathRuntime,
                            density = density,
                            widthPx = widthPx,
                            fullWidthPx = fullWidthPx,
                            measurer = workerMeasurer,
                        )
                    }
                    prelaidOwnerKeys += ownerKey
                    entries.layouts.forEach { (key, layout) -> precomputedLayouts[key] = layout }
                    entries.tableWidths.forEach { (key, widths) ->
                        precomputedTableWidths[key] = widths
                    }
                    // At most one paragraph is prepared per display frame, so neither the idle
                    // whole-document pass nor scroll-ahead prefetch becomes a second eager document
                    // render competing with Compose traversal and drawing.
                    withFrameNanos { }
                    wakeWorker.trySend(Unit)
                }
            }
        }
    }

    SubcomposeLayout(
        modifier = modifier
            .onSizeChanged { size ->
                if (size.width > 0 && size.width != layoutWidthPx) layoutWidthPx = size.width
            }
            .onLayoutRectChanged(
                throttleMillis = ScrollOriginCalibrationThrottleMillis,
                debounceMillis = 0,
            ) { bounds ->
                if (contentOriginInScrollPx.isNaN() || !deferredLayout.scrollInProgress) {
                    val newOrigin = bounds.positionInWindow.y.toFloat() + deferredLayout.scrollOffsetPx()
                    if (newOrigin != contentOriginInScrollPx) contentOriginInScrollPx = newOrigin
                }
            },
    ) { constraints ->
        val width = constraints.maxWidth
        val blockHeights = blocks.indices.mapTo(mutableListOf()) { index ->
            measuredHeightsPx[width to blockKeys[index]] ?: estimatedHeightsPx[index]
        }
        var localTotalHeight = blockHeights.sumOf { height -> height.toLong() }
        val childConstraints = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        // `NearViewportMaterialization`: while the scroll is settled, keep blocks around the
        // visible range composed and truly measured before they scroll on-screen, admitting a
        // bounded number of not-yet-measured blocks per pass (each measured height updates state
        // and re-enters this measure, so the window fills progressively and then goes quiet).
        // `ScrollAheadMaterialization`: during an active scroll, traversing the settled ±window
        // every frame is a steady tax the SD835 class cannot absorb, but ZERO lead makes prose
        // visibly pop in blank — its region scrolls on-screen before its first composition and
        // paint. Keep a sub-viewport strip composed ahead of the travel direction (inferred from
        // visible-range movement, NOT the raw scroll offset, so this measure does not re-run on
        // every scrolled pixel) so first paint happens off-screen.
        // Direction updates ONLY when the range start actually moves; passes triggered by
        // admissions or state flips keep the last known direction — comparing with >= made
        // every same-start pass read as "forward", flapping the strip on upward scrolls.
        val forward = when {
            visibleRange.isEmpty() -> scrollAnchor.prevForward
            visibleRange.first > scrollAnchor.prevRangeFirst -> true
            visibleRange.first < scrollAnchor.prevRangeFirst -> false
            else -> scrollAnchor.prevForward
        }
        scrollAnchor.prevForward = forward
        if (!visibleRange.isEmpty()) scrollAnchor.prevRangeFirst = visibleRange.first
        val materializeRange = if (visibleRange.isEmpty()) {
            IntRange.EMPTY
        } else if (!materializationSettled.value) {
            val viewportPx = viewportHeightPx.coerceAtLeast(1)
            val aheadPx = viewportPx * ScrollAheadMaterializationViewportQuarters / 4
            // Trailing quarter-viewport: just-passed blocks stay composed briefly so a swipe
            // train does not dispose and recompose them at every direction wobble.
            val behindPx = viewportPx / 4
            val abovePx = if (forward) behindPx else aheadPx
            val belowPx = if (forward) aheadPx else behindPx
            var first = visibleRange.first.coerceIn(0, blocks.lastIndex)
            var above = 0
            while (first > 0 && above < abovePx) {
                first--
                above += blockHeights[first]
            }
            var last = visibleRange.last.coerceIn(0, blocks.lastIndex)
            var below = 0
            while (last < blocks.lastIndex && below < belowPx) {
                last++
                below += blockHeights[last]
            }
            first..last
        } else {
            val windowPx = viewportHeightPx.coerceAtLeast(1) * NearViewportMaterializationViewports
            var first = visibleRange.first.coerceIn(0, blocks.lastIndex)
            var above = 0
            while (first > 0 && above < windowPx) {
                first--
                above += blockHeights[first]
            }
            var last = visibleRange.last.coerceIn(0, blocks.lastIndex)
            var below = 0
            while (last < blocks.lastIndex && below < windowPx) {
                last++
                below += blockHeights[last]
            }
            first..last
        }
        val materializationBudget = if (materializationSettled.value) {
            NearViewportMaterializationPerPass
        } else {
            ScrollAheadMaterializationPerPass
        }
        measureWidthHolder.widthPx = width
        val placeables = mutableMapOf<Int, Placeable>()
        fun measureBlock(index: Int): Placeable = placeables.getOrPut(index) {
            val ownerKey = blockKeys[index]
            val saveableKey = ownerKey.toSaveableMarkdownBlockKey()
            // `MemoizedBlockContent`: subcompose content must be the SAME lambda instance
            // across measure passes — an inline lambda here is a fresh instance every pass,
            // which forces every composed block to RECOMPOSE on every scroll frame (~10
            // block compositions and ~4 display-list re-records per frame measured on
            // SD835). The cached lambda reads the pass width through [measureWidthHolder]
            // instead of capturing it.
            val content = blockContents.getOrPut(ownerKey) {
                @Composable {
                    stateHolder.SaveableStateProvider(saveableKey) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .onSizeChanged { size ->
                                    val passWidth = measureWidthHolder.widthPx
                                    if (shouldUpdateMarkdownMeasuredHeight(
                                            cachedHeightPx = measuredHeightsPx[passWidth to ownerKey],
                                            measuredHeightPx = size.height,
                                        )
                                    ) {
                                        measuredHeightsPx[passWidth to ownerKey] = size.height
                                    }
                                },
                        ) {
                            CompositionLocalProvider(
                                LocalMarkdownSelectionRetentionKey provides ownerKey,
                                LocalMarkdownPrecomputedLayouts provides precomputedLayouts,
                                LocalMarkdownPrecomputedTableWidths provides precomputedTableWidths,
                            ) {
                                MarkdownBlockItem(
                                    index = index,
                                    blocks = blocks,
                                    style = style,
                                    slots = slots,
                                    inlineSlots = inlineSlots,
                                    onLinkClick = onLinkClick,
                                    onFootnoteClick = onFootnoteClick,
                                    topLevelProseWidth = topLevelProseWidth,
                                    footnoteMarkerWidth = footnoteMarkerWidths[index],
                                )
                            }
                        }
                    }
                }
            }
            subcompose(ownerKey, content).single().measure(childConstraints).also { placeable ->
                val previousHeight = blockHeights[index]
                blockHeights[index] = placeable.height
                localTotalHeight += placeable.height.toLong() - previousHeight
            }
        }
        val mandatoryIndices = blocks.indices.filter { index ->
            index in visibleRange || blockKeys[index] in deferredLayout.requestedOwnerKeys
        }
        mandatoryIndices.forEach(::measureBlock)
        materializeRange
            .filter { index -> (width to blockKeys[index]) in measuredHeightsPx }
            .forEach(::measureBlock)
        val forwardMaterializationOrder =
            (visibleRange.last + 1..materializeRange.last) +
                (visibleRange.first - 1 downTo materializeRange.first)
        val backwardMaterializationOrder =
            (visibleRange.first - 1 downTo materializeRange.first) +
                (visibleRange.last + 1..materializeRange.last)
        (if (forward) forwardMaterializationOrder else backwardMaterializationOrder)
            .asSequence()
            .filter { index ->
                index in blocks.indices &&
                    index !in mandatoryIndices &&
                    (width to blockKeys[index]) !in measuredHeightsPx
            }
            .take(materializationBudget)
            .forEach(::measureBlock)

        // `SynchronousVisibleFallback`: a newly composed block often resolves shorter than its
        // estimate. Freezing its old slot until onSizeChanged schedules another pass leaves the
        // remainder blank and can expose one additional estimated slot per frame during a fast
        // wheel or scrollbar jump. Adopt measured heights immediately, recompute the real visible
        // range, and measure any newly exposed block in this same pass. The set only grows, so the
        // loop terminates after at most one admission per document block and never expands the
        // ScrollState viewport or accessibility range.
        val requestedViewportRange = viewportRangeHolder.range
        var passVisibleRange = visibleRange
        var remainingVisibleAdmissions = SynchronousVisibleFallbackAdmissionLimit
        while (blocks.isNotEmpty()) {
            val viewportRange = markdownViewportRangeClampedToContent(
                viewportRange = requestedViewportRange,
                viewportHeightPx = viewportHeightPx,
                contentHeightPx = localTotalHeight,
            )
            val correctedVisibleRange = deferredMarkdownBlockIndicesFromPx(
                blockHeightsPx = blockHeights,
                visibleTopPx = viewportRange.top,
                visibleBottomPx = viewportRange.bottom,
                viewportHeightPx = viewportHeightPx,
            )
            val missingVisibleBlocks = correctedVisibleRange.filterNot(placeables::containsKey)
            passVisibleRange = correctedVisibleRange
            if (missingVisibleBlocks.isEmpty()) break
            val admittedBlocks = missingVisibleBlocks.take(remainingVisibleAdmissions)
            if (admittedBlocks.isEmpty()) break
            admittedBlocks.forEach(::measureBlock)
            remainingVisibleAdmissions -= admittedBlocks.size
        }

        val blockTops = IntArray(blocks.size)
        var totalHeight = 0L
        blockHeights.forEachIndexed { index, height ->
            blockTops[index] = totalHeight.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            totalHeight += height
        }
        // `AnchoredHeightCorrection`: block positions accumulate from the document top, so ANY
        // resolved-height change above the first visible block (estimate→measured swap, image
        // arrival) moves every later block — the reader sees their content jump. Pin the anchor
        // block by handing the scroll owner exactly the anchor's document-position delta in the
        // same measure pass, then re-anchor to the current first visible block.
        if (blocks.isNotEmpty() && !passVisibleRange.isEmpty()) {
            val anchorIndex = scrollAnchor.key
                ?.let { key -> blockKeys.indexOf(key) }
                ?.takeIf { it >= 0 }
            if (anchorIndex != null) {
                val delta = blockTops[anchorIndex] - scrollAnchor.topPx
                if (delta != 0) deferredLayout.compensateScrollBy(delta)
            }
            val nextAnchor = passVisibleRange.first.coerceIn(0, blocks.lastIndex)
            scrollAnchor.key = blockKeys[nextAnchor]
            scrollAnchor.topPx = blockTops[nextAnchor]
        }
        val resolvedHeight = totalHeight
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width.coerceIn(constraints.minWidth, constraints.maxWidth), resolvedHeight) {
            placeables.forEach { (index, placeable) ->
                placeable.placeRelative(0, blockTops[index])
            }
        }
    }
}

// `NearViewportMaterialization` keeps this many viewports of surrounding content composed and
// truly measured on each side of the visible range, admitting at most this many not-yet-measured
// blocks per measure pass so a fling gap never turns into one mega-frame of catch-up work.
private const val NearViewportMaterializationViewports = 1
private const val NearViewportMaterializationPerPass = 2

// `AnchoredHeightCorrection` state: the block whose document position the reader is visually
// anchored to, and that position at the previous measure pass. Deliberately NOT snapshot state —
// it is read and written only inside the measure pass.
// Width of the current measure pass, read by `MemoizedBlockContent` lambdas at callback time so
// cached content never captures a per-pass value.
private class MarkdownMeasureWidthHolder {
    var widthPx: Int = 0
}

private class MarkdownViewportRangeHolder {
    var range: MarkdownViewportRangePx = MarkdownViewportRangePx(top = 0f, bottom = 1f)
}

private class MarkdownScrollAnchor {
    var key: Any? = null
    var topPx: Int = 0
    // Last pass's visible-range start and travel direction, for inferring scroll direction
    // without reading the raw scroll offset inside measure.
    var prevRangeFirst: Int = 0
    var prevForward: Boolean = true
}

// `ScrollAheadMaterialization`: how far ahead of the travel direction blocks stay composed
// during a scroll (in quarter-viewports), and how many new blocks one pass may admit.
private const val ScrollAheadMaterializationViewportQuarters = 3
private const val ScrollAheadMaterializationPerPass = 1

// Visible content remains mandatory, but malformed/custom documents with hundreds of zero-height
// estimates must not turn one correction pass into an unbounded synchronous whole-document render.
private const val SynchronousVisibleFallbackAdmissionLimit = 64

// `MaterializationSettleHysteresis`: quiet time required before the composed window may widen
// from the directional scroll strip to the settled ±viewport window. Longer than any inter-swipe
// gap in a reading scroll train, shorter than a genuine pause.
private const val NearViewportMaterializationSettleDelayMillis = 700L

private const val ScrollOriginCalibrationThrottleMillis = 64L

private fun org.tiqian.markdown.MarkdownNodeKey.toSaveableMarkdownBlockKey(): String =
    buildString {
        append(parserStableKey)
        path.forEach { part -> append(':').append(part) }
    }

@Composable
private fun MarkdownBlockItem(
    index: Int,
    blocks: List<MarkdownBlock>,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
    topLevelProseWidth: Dp?,
    footnoteMarkerWidth: Dp?,
) {
    val block = blocks[index]
    Column(Modifier.fillMaxWidth()) {
        markdownBlockSpacing(
            previousBlock = blocks.getOrNull(index - 1),
            block = block,
            style = style,
            compact = false,
        )?.let { spacing -> Spacer(Modifier.height(spacing)) }
        if (topLevelProseWidth == null) {
            MarkdownBlock(
                block = block,
                style = style,
                slots = slots,
                inlineSlots = inlineSlots,
                onLinkClick = onLinkClick,
                onFootnoteClick = onFootnoteClick,
                footnoteMarkerWidth = footnoteMarkerWidth,
            )
        } else {
            val blockWidthModifier = if (block is MarkdownTable) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.width(topLevelProseWidth)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(blockWidthModifier, propagateMinConstraints = true) {
                    MarkdownBlock(
                        block = block,
                        style = style,
                        slots = slots,
                        inlineSlots = inlineSlots,
                        onLinkClick = onLinkClick,
                        onFootnoteClick = onFootnoteClick,
                        footnoteMarkerWidth = footnoteMarkerWidth,
                    )
                }
            }
        }
    }
}
