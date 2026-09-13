package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownTableAlignment
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.measureWithInlineContent
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.ic

internal data class MarkdownPrecomputedLayout(
    val layout: LayoutResult,
    val preparedInlineMath: Map<Int, MarkdownInlineContent>,
)

internal val LocalMarkdownPrecomputedLayouts =
    staticCompositionLocalOf<Map<Any, MarkdownPrecomputedLayout>> { emptyMap() }

// `TableSubtreePrelayout`: a table's column negotiation is the expensive half of its entry frame
// (two engine probes per cell), and it is only reusable at the width it was negotiated for — the
// available width travels with the result so the renderer can recognise its own measure.
internal data class MarkdownPrecomputedTableWidths(
    val availableWidthPx: Float,
    val resolution: MarkdownTableWidthResolution,
)

internal val LocalMarkdownPrecomputedTableWidths =
    staticCompositionLocalOf<Map<Any, MarkdownPrecomputedTableWidths>> { emptyMap() }

/**
 * Everything one background prelayout pass produced for a top-level block: prose layouts keyed by
 * their own selection keys, plus (`TableSubtreePrelayout`) any negotiated table column widths.
 */
internal data class MarkdownPrecomputedEntries(
    val layouts: List<Pair<Any, MarkdownPrecomputedLayout>>,
    val tableWidths: List<Pair<Any, MarkdownPrecomputedTableWidths>>,
)

internal fun prelayoutMarkdownBlockIndices(
    blocks: List<MarkdownBlock>,
    visibleRange: IntRange,
    forward: Boolean,
    defaultMath: Boolean = false,
    blockHeightsPx: List<Int> = emptyList(),
    prefetchDistancePx: Int = Int.MAX_VALUE,
    maximumCount: Int = BackgroundPrelayoutMaximumBlocks,
): List<Int> {
    if (blocks.isEmpty() || visibleRange.isEmpty() || maximumCount <= 0) return emptyList()
    val visible = visibleRange
        .asSequence()
        .filter { it in blocks.indices && blocks[it].supportsBackgroundPrelayout(defaultMath) }
        .take(maximumCount)
        .toList()
    val ahead = prefetchMarkdownBlockIndices(
        blocks = blocks,
        visibleRange = visibleRange,
        forward = forward,
        defaultMath = defaultMath,
        blockHeightsPx = blockHeightsPx,
        prefetchDistancePx = prefetchDistancePx,
        maximumCount = maximumCount - visible.size,
    )
    return visible + ahead
}

internal fun backgroundPrelayoutPriorityIndices(
    blocks: List<MarkdownBlock>,
    visibleRange: IntRange,
    forward: Boolean,
    defaultMath: Boolean = false,
): List<Int> {
    if (blocks.isEmpty() || visibleRange.isEmpty()) return emptyList()
    val after = (visibleRange.last + 1 until blocks.size).asSequence()
    val before = (visibleRange.first - 1 downTo 0).asSequence()
    val directional = if (forward) after + before else before + after
    return directional
        .filter { blocks[it].supportsBackgroundPrelayout(defaultMath) }
        .toList()
}

internal fun prefetchMarkdownBlockIndices(
    blocks: List<MarkdownBlock>,
    visibleRange: IntRange,
    forward: Boolean,
    defaultMath: Boolean = false,
    blockHeightsPx: List<Int> = emptyList(),
    prefetchDistancePx: Int = Int.MAX_VALUE,
    maximumCount: Int = BackgroundPrelayoutMaximumBlocks,
): List<Int> {
    if (blocks.isEmpty() || visibleRange.isEmpty() || maximumCount <= 0) return emptyList()
    val indices = if (forward) {
        (visibleRange.last + 1 until blocks.size)
    } else {
        (visibleRange.first - 1 downTo 0)
    }
    val selected = mutableListOf<Int>()
    var distance = 0L
    for (index in indices) {
        if (distance >= prefetchDistancePx || selected.size >= maximumCount) break
        distance += blockHeightsPx.getOrNull(index)?.coerceAtLeast(1) ?: 1
        if (blocks[index].supportsBackgroundPrelayout(defaultMath)) selected += index
    }
    return selected
}

private fun MarkdownBlock.supportsBackgroundPrelayout(defaultMath: Boolean): Boolean = when (this) {
    is MarkdownParagraph -> !text.hasPrelayoutBlockedSpans(defaultMath)
    is MarkdownHeading -> !text.hasPrelayoutBlockedSpans(defaultMath)
    // `QuoteSubtreePrelayout`: a block quote is eligible when every nested block is; its
    // nested prose is precomputed at the quote's inset width and content style, keyed by
    // each nested block's own selection key so the ordinary consume path picks it up.
    is MarkdownBlockQuote -> blocks.isNotEmpty() &&
        blocks.all { it.supportsBackgroundPrelayout(defaultMath) }
    // `ImageFallbackPrelayout`: the URL-text fallback an image renders while offline or on
    // load error is deterministic, so it can be laid out ahead of entry like any paragraph.
    is MarkdownImageBlock -> true
    // `TableSubtreePrelayout`: a table is eligible when every cell's prose is, since the whole
    // subtree — column negotiation and every cell layout — is precomputed as one unit.
    is MarkdownTable -> rows.isNotEmpty() &&
        rows.all { row -> row.cells.all { !it.text.hasPrelayoutBlockedSpans(defaultMath) } }
    else -> false
}

/** Spans whose measure depends on host-supplied content, so they cannot be laid out ahead. */
private fun MarkdownText.hasPrelayoutBlockedSpans(defaultMath: Boolean): Boolean =
    spans.any { span ->
        when (span.mark) {
            is MarkdownTextMark.InlineImage,
            is MarkdownTextMark.Custom,
            -> true
            is MarkdownTextMark.InlineMath -> !defaultMath
            else -> false
        }
    }

/**
 * @param widthPx prose measure the block's own text is laid out at.
 * @param fullWidthPx the block's full column width — tables (`TableSubtreePrelayout`) fill it
 *   rather than the prose measure, so their negotiation needs it verbatim.
 */
internal fun precomputeMarkdownBlockEntries(
    block: MarkdownBlock,
    style: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots,
    mathRuntime: MarkdownMathRuntime?,
    density: Density,
    widthPx: Float,
    fullWidthPx: Float,
    measurer: ParagraphMeasurer,
): MarkdownPrecomputedEntries = when (block) {
    // `QuoteSubtreePrelayout`: mirror the MarkdownBlockQuote row exactly — the bar spacer and
    // content padding inset the nested prose width, and nested blocks render with the quote's
    // content style. Entries are keyed by each nested block's own selection key.
    is MarkdownBlockQuote -> {
        val innerWidthPx = insetProseGeometry(widthPx, with(density) {
            (style.quoteBarWidth + style.quoteContentPadding).roundToPx().toFloat()
        }).contentWidth.coerceAtLeast(1f)
        val quoteStyle = style.quoteContentStyle()
        val nested = block.blocks.map { nestedBlock ->
            precomputeMarkdownBlockEntries(
                block = nestedBlock,
                style = quoteStyle,
                inlineSlots = inlineSlots,
                mathRuntime = mathRuntime,
                density = density,
                widthPx = innerWidthPx,
                // The quote's content column IS the full width for everything nested in it — a
                // nested table fills that column, not the outer one. Cell layouts are consumed
                // by key without a width check, so handing the outer width down here would
                // publish cell layouts measured for columns the renderer never uses.
                fullWidthPx = innerWidthPx,
                measurer = measurer,
            )
        }
        MarkdownPrecomputedEntries(
            layouts = nested.flatMap { it.layouts },
            tableWidths = nested.flatMap { it.tableWidths },
        )
    }
    // `TableSubtreePrelayout`: negotiate the columns and lay out every cell off the UI thread,
    // so the entering frame makes zero engine calls for the table.
    is MarkdownTable -> precomputeMarkdownTableEntries(
        block = block,
        style = style,
        inlineSlots = inlineSlots,
        mathRuntime = mathRuntime,
        density = density,
        availableWidthPx = fullWidthPx,
        measurer = measurer,
    )
    // `ImageFallbackPrelayout`: precompute the deterministic URL-text fallback under the
    // fallback's own selection key ("description" scope). When the image loads normally the
    // entry is simply never consumed; captioned fallbacks keep laying out at entry because
    // they render without their own fragment key.
    is MarkdownImageBlock -> {
        val label = block.description.ifBlank { block.destination }
        val fallback = MarkdownParagraph(
            MarkdownText(
                value = label,
                spans = listOf(
                    MarkdownTextSpan(
                        MarkdownTextRange(0, label.length),
                        MarkdownTextMark.Link(block.destination, block.title),
                    ),
                ),
            ),
            block.metadata,
        )
        precomputeMarkdownBlock(
            block = fallback,
            style = style,
            inlineSlots = inlineSlots,
            mathRuntime = mathRuntime,
            density = density,
            widthPx = widthPx,
            measurer = measurer,
        )?.let {
            MarkdownPrecomputedEntries(
                layouts = listOf(markdownSelectionKey(block.metadata.key, "description") to it),
                tableWidths = emptyList(),
            )
        } ?: EmptyMarkdownPrecomputedEntries
    }
    else -> precomputeMarkdownBlock(
        block = block,
        style = style,
        inlineSlots = inlineSlots,
        mathRuntime = mathRuntime,
        density = density,
        widthPx = widthPx,
        measurer = measurer,
    )?.let {
        MarkdownPrecomputedEntries(
            layouts = listOf(markdownSelectionKey(block.metadata.key) to it),
            tableWidths = emptyList(),
        )
    } ?: EmptyMarkdownPrecomputedEntries
}

/**
 * `TableSubtreePrelayout`: mirror `DefaultMarkdownTable` exactly — the same column negotiation at
 * the table's full width, then every cell laid out at the same rounded content width the renderer
 * will hand it, keyed by the cell's own selection key so the ordinary consume path picks it up.
 */
private fun precomputeMarkdownTableEntries(
    block: MarkdownTable,
    style: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots,
    mathRuntime: MarkdownMathRuntime?,
    density: Density,
    availableWidthPx: Float,
    measurer: ParagraphMeasurer,
): MarkdownPrecomputedEntries {
    val columnCount = maxOf(
        block.columnAlignments.size,
        block.rows.maxOfOrNull { it.cells.size } ?: 0,
    )
    if (columnCount == 0) return EmptyMarkdownPrecomputedEntries
    val resolution = resolveMarkdownTableWidthResolution(
        block = block,
        style = style,
        density = density,
        measurer = measurer,
        columnCount = columnCount,
        availableWidthPx = availableWidthPx,
    )
    // The renderer sizes a cell with `Modifier.width(px.toDp()).padding(tableCellPadding)`, so the
    // measure the engine actually sees is the ROUNDED column width minus the rounded padding —
    // precomputing at the raw float width would lay out at a width the cell never gets.
    val padPx = with(density) { style.tableCellPadding.roundToPx() }
    val layouts = mutableListOf<Pair<Any, MarkdownPrecomputedLayout>>()
    block.rows.forEach { row ->
        row.cells.forEachIndexed { columnIndex, cell ->
            if (columnIndex >= columnCount) return@forEachIndexed
            val columnPx = with(density) { resolution.columnWidths[columnIndex].toDp().roundToPx() }
            val textMaxPx = (columnPx - 2 * padPx).coerceAtLeast(1)
            val textStyle = style.tableText.copy(
                fontWeight = if (cell.header) FontWeight.Medium else style.tableText.fontWeight,
                textAlign = when (cell.alignment) {
                    MarkdownTableAlignment.Start,
                    MarkdownTableAlignment.Unspecified,
                    -> TextAlign.Start
                    MarkdownTableAlignment.Center -> TextAlign.Center
                    MarkdownTableAlignment.End -> TextAlign.End
                },
            )
            val layout = precomputeMarkdownTextLayout(
                text = cell.text,
                textStyle = textStyle,
                paragraphStyle = markdownTableParagraphStyle,
                style = style,
                inlineSlots = inlineSlots,
                mathRuntime = mathRuntime,
                density = density,
                widthPx = textMaxPx.toFloat(),
                measurer = measurer,
            ) ?: return@forEachIndexed
            layouts += markdownSelectionKey(cell.metadata.key, "cell") to layout
        }
    }
    return MarkdownPrecomputedEntries(
        layouts = layouts,
        tableWidths = listOf(
            markdownSelectionKey(block.metadata.key, "widths") to MarkdownPrecomputedTableWidths(
                availableWidthPx = availableWidthPx,
                resolution = resolution,
            ),
        ),
    )
}

private fun precomputeMarkdownBlock(
    block: MarkdownBlock,
    style: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots,
    mathRuntime: MarkdownMathRuntime?,
    density: Density,
    widthPx: Float,
    measurer: ParagraphMeasurer,
): MarkdownPrecomputedLayout? {
    val text = when (block) {
        is MarkdownParagraph -> block.text
        is MarkdownHeading -> block.text
        else -> return null
    }
    val textStyle = when (block) {
        is MarkdownParagraph -> style.body
        is MarkdownHeading -> style.heading(block.level)
    }
    return precomputeMarkdownTextLayout(
        text = text,
        textStyle = textStyle,
        paragraphStyle = ParagraphStyle(firstLineIndent = 0.ic),
        style = style,
        inlineSlots = inlineSlots,
        mathRuntime = mathRuntime,
        density = density,
        widthPx = widthPx,
        measurer = measurer,
    )
}

/**
 * Lays out one Markdown text run exactly as its renderer would: default inline math is prepared
 * first (a run whose math cannot be prepared is skipped rather than laid out with a placeholder),
 * then the resolved annotated string goes through the same measure path as `MarkdownTextBlock`.
 */
private fun precomputeMarkdownTextLayout(
    text: MarkdownText,
    textStyle: TextStyle,
    paragraphStyle: ParagraphStyle,
    style: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots,
    mathRuntime: MarkdownMathRuntime?,
    density: Density,
    widthPx: Float,
    measurer: ParagraphMeasurer,
): MarkdownPrecomputedLayout? = markdownTraceSection("TiqianMarkdown.prelayout") {
    val mathMarks = text.spans.mapIndexedNotNull { spanIndex, span ->
        (span.mark as? MarkdownTextMark.InlineMath)?.let { spanIndex to it }
    }
    val preparedMath = if (mathMarks.isNotEmpty() && inlineSlots.math == null && mathRuntime != null) {
        mathMarks.mapNotNull { (spanIndex, mark) ->
            prepareDefaultMarkdownInlineMath(
                mark = mark,
                style = style,
                hostTextStyle = textStyle,
                density = density,
                preparer = mathRuntime.formulaPreparer,
            )?.let { spanIndex to it }
        }.toMap()
    } else {
        emptyMap()
    }
    if (preparedMath.size != mathMarks.size) return@markdownTraceSection null
    val resolved = resolveMarkdownTextWithPreparedMath(text, style, preparedMath)
    MarkdownPrecomputedLayout(
        layout = measurer.measureWithInlineContent(
            text = resolved.annotated,
            constraints = LayoutConstraints(maxWidth = widthPx),
            density = density,
            style = textStyle,
            paragraphStyle = if (style.insetProse) insetProseParagraphStyle(paragraphStyle) else paragraphStyle,
            inlineObjects = resolved.toCjkInlineObjects(density),
            inlineDecorations = resolved.decorations.toCjkInlineDecorations(),
            inlineBackgrounds = resolved.backgrounds.toCjkInlineBackgrounds(),
        ),
        preparedInlineMath = preparedMath,
    )
}

private val EmptyMarkdownPrecomputedEntries =
    MarkdownPrecomputedEntries(layouts = emptyList(), tableWidths = emptyList())

private const val BackgroundPrelayoutMaximumBlocks = 32

// `ScrollAheadPrelayout` keeps this many viewports of upcoming content laid out ahead of the
// scroll direction; beyond it the worker goes quiet until the viewport moves again.
internal const val ScrollAheadPrelayoutViewportBudget = 2

internal const val BackgroundPrelayoutIdleDelayMillis = 250L
