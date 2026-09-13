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
import org.tiqian.markdown.MarkdownTableAlignment
import org.tiqian.markdown.MarkdownTaskState
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan
import org.tiqian.markdown.MarkdownThematicBreak
import org.tiqian.markdown.MarkdownUnsupportedBlock
import org.tiqian.markdown.resolveMarkdownCodeLanguage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tiqian.compose.material3.CjkText
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.measure
import org.tiqian.compose.toCjkTextStyle
import org.tiqian.compose.toCoreTextStyle
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextStyle as CoreTextStyle
import org.tiqian.core.ic
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.tiqian.markdown.compose.generated.resources.Res
import org.tiqian.markdown.compose.generated.resources.code_copied
import org.tiqian.markdown.compose.generated.resources.ic_check_box_20dp
import org.tiqian.markdown.compose.generated.resources.ic_check_box_outline_blank_20dp
import org.tiqian.markdown.compose.generated.resources.ic_check_16dp
import org.tiqian.markdown.compose.generated.resources.ic_content_copy_16dp
import org.tiqian.markdown.compose.generated.resources.copy_code
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private sealed interface MarkdownListMarker {
    data class Text(val value: String) : MarkdownListMarker
    data class Task(val checked: Boolean) : MarkdownListMarker
}

@Composable
internal fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
    compact: Boolean,
    topLevelProseWidth: Dp? = null,
) {
    val density = LocalDensity.current
    val footnoteMarkerWidths = rememberFootnoteMarkerWidths(blocks, style)
    val bodyLineHeight = style.bodyLineHeightSpOrNull()?.let { lineHeight ->
        with(density) { lineHeight.toDp() }
    }
    Column(modifier) {
        blocks.forEachIndexed { index, block ->
            markdownBlockSpacing(
                previousBlock = blocks.getOrNull(index - 1),
                block = block,
                style = style,
                compact = compact,
                bodyLineHeight = bodyLineHeight,
            )?.let { spacing -> Spacer(Modifier.height(spacing)) }
            key(block.metadata.key) {
                if (topLevelProseWidth == null) {
                    MarkdownBlock(
                        block = block,
                        style = style,
                        slots = slots,
                        inlineSlots = inlineSlots,
                        onLinkClick = onLinkClick,
                        onFootnoteClick = onFootnoteClick,
                        footnoteMarkerWidth = footnoteMarkerWidths[index],
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
                                footnoteMarkerWidth = footnoteMarkerWidths[index],
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun markdownBlockSpacing(
    previousBlock: MarkdownBlock?,
    block: MarkdownBlock,
    style: MarkdownStyle,
    compact: Boolean,
    bodyLineHeight: Dp? = style.bodyLineHeightSpOrNull()?.let { lineHeight ->
        with(LocalDensity.current) { lineHeight.toDp() }
    },
): Dp? {
    if (previousBlock == null) return null
    val adjacentLists = previousBlock is MarkdownList && block is MarkdownList
    val adjacentDisplayBlock = previousBlock.isMarkdownDisplayBlock() || block.isMarkdownDisplayBlock()
    return when {
        block is MarkdownFootnoteDefinition -> 0.dp
        bodyLineHeight != null -> {
            bodyLineHeight * style.blockGapBodyLines(
                (previousBlock as? MarkdownHeading)?.level, (block as? MarkdownHeading)?.level,
                adjacentDisplayBlock, adjacentLists, compact,
            )
        }
        adjacentLists -> style.listBlockSpacing
        adjacentDisplayBlock -> style.displayBlockSpacing
        else -> if (compact) style.compactBlockSpacing else style.blockSpacing
    }
}

private fun MarkdownBlock.isMarkdownDisplayBlock(): Boolean = when (this) {
    is MarkdownCodeBlock,
    is MarkdownImageBlock,
    is MarkdownMathBlock,
    is MarkdownTable,
    -> true
    else -> false
}

@Composable
internal fun rememberFootnoteMarkerWidths(
    blocks: List<MarkdownBlock>,
    style: MarkdownStyle,
): Map<Int, Dp> {
    val density = LocalDensity.current
    val measurer = rememberMarkdownParagraphMeasurer()
    val footnoteTextStyle = remember(style) { style.footnoteContentTextStyle() }
    val markerTextStyle = remember(footnoteTextStyle) { footnoteTextStyle.toCjkTextStyle() }
    val coreMarkerTextStyle = remember(markerTextStyle, density) {
        markerTextStyle.toCoreTextStyle(density)
    }
    val paragraphStyle = remember {
        proseMarkerParagraphStyle(LastLineAlignment.Start)
    }
    return remember(blocks, coreMarkerTextStyle, paragraphStyle, measurer, density) {
        buildMap {
            var start = 0
            while (start < blocks.size) {
                if (blocks[start] !is MarkdownFootnoteDefinition) {
                    start += 1
                    continue
                }
                var end = start + 1
                while (end < blocks.size && blocks[end] is MarkdownFootnoteDefinition) end += 1
                val widestMarker = (start until end).maxOf { index ->
                    val definition = blocks[index] as MarkdownFootnoteDefinition
                    measurer.measure(
                        text = "[${definition.index}]",
                        constraints = LayoutConstraints(maxWidth = 100_000f),
                        textStyle = coreMarkerTextStyle,
                        paragraphStyle = paragraphStyle,
                    ).size.width
                }
                val width = with(density) { widestMarker.toDp() }
                for (index in start until end) put(index, width)
                start = end
            }
        }
    }
}

@Composable
internal fun MarkdownBlock(
    block: MarkdownBlock,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
    footnoteMarkerWidth: Dp? = null,
) {
    when (block) {
        is MarkdownParagraph -> MarkdownSelectionScope(markdownSelectionKey(block.metadata.key)) {
            MarkdownTextBlock(
                block.text,
                style.body,
                style,
                inlineSlots,
                onLinkClick,
                onFootnoteClick,
            )
        }
        is MarkdownHeading -> MarkdownSelectionScope(markdownSelectionKey(block.metadata.key)) {
            MarkdownTextBlock(
                block.text,
                style.heading(block.level),
                style,
                inlineSlots,
                onLinkClick,
                onFootnoteClick,
            )
        }
        is MarkdownBlockQuote -> {
            val quoteStyle = remember(style) { style.quoteContentStyle() }
            val barWidth = style.quoteBarWidth
            val barColor = style.quoteBarColor
            // The bar is decoration drawn at the row's final height instead of an
            // IntrinsicSize.Min sibling: intrinsic measurement is forbidden over
            // subcompose-backed content (display math, tables), which quotes may contain.
            Row(
                Modifier.fillMaxWidth().drawBehind {
                    val bar = proseQuoteBar((barWidth + style.quoteContentPadding).toPx(), 0f, size.height,
                        barWidth.toPx(), style.quoteContentPadding.toPx())
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - bar.left - bar.width else bar.left
                    drawRect(
                        color = barColor,
                        topLeft = Offset(x, bar.top),
                        size = Size(bar.width, bar.height),
                    )
                },
            ) {
                ProseInsetLayout(
                    with(LocalDensity.current) { (barWidth + style.quoteContentPadding).toPx() },
                    Modifier.weight(1f),
                ) {
                    MarkdownBlocks(
                        blocks = block.blocks,
                        modifier = Modifier.fillMaxWidth(),
                        style = quoteStyle,
                        slots = slots,
                        inlineSlots = inlineSlots,
                        onLinkClick = onLinkClick,
                        onFootnoteClick = onFootnoteClick,
                        compact = true,
                    )
                }
            }
        }

        is MarkdownList -> MarkdownListBlock(
            block,
            style,
            slots,
            inlineSlots,
            onLinkClick,
            onFootnoteClick,
        )
        is MarkdownCodeBlock -> slots.codeBlock?.invoke(block, style) ?: DefaultMarkdownCodeBlock(block, style)
        is MarkdownImageBlock -> slots.imageBlock?.invoke(block, style)
            ?: DefaultMarkdownImageBlock(block, style, onLinkClick, inlineSlots)
        is MarkdownMathBlock -> slots.mathBlock?.invoke(block, style) ?: DefaultMarkdownMathBlock(block, style)
        is MarkdownHtmlBlock -> slots.htmlBlock?.invoke(block, style) ?: DefaultMarkdownHtmlBlock(block, style)
        is MarkdownTable -> slots.table?.invoke(block, style)
            ?: DefaultMarkdownTable(block, style, onLinkClick, onFootnoteClick, inlineSlots)
        is MarkdownFootnoteDefinition -> slots.footnoteDefinition?.invoke(block, style)
            ?: DefaultMarkdownFootnoteDefinition(
                block,
                style,
                slots,
                onLinkClick,
                onFootnoteClick,
                inlineSlots,
                footnoteMarkerWidth,
            )
        is MarkdownCustomBlock -> slots.customBlock?.invoke(block, style)
            ?: DefaultMarkdownCustomBlock(block, style)
        is MarkdownThematicBreak -> slots.thematicBreak?.invoke(block, style) ?: DefaultMarkdownThematicBreak(style)
        is MarkdownUnsupportedBlock -> slots.unsupportedBlock?.invoke(block, style)
            ?: DefaultMarkdownUnsupportedBlock(block, style)
    }
}

@Composable
private fun MarkdownListBlock(
    block: MarkdownList,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
) {
    val density = LocalDensity.current
    val markerMeasurer = rememberMarkdownParagraphMeasurer()
    val markerMeasureParagraphStyle = remember {
        ParagraphStyle(
            firstLineIndent = 0.ic,
            lineLengthGrid = LineLengthGrid(enabled = false),
        )
    }
    val markerStartParagraphStyle = remember {
        proseMarkerParagraphStyle(LastLineAlignment.Start)
    }
    val markerEndParagraphStyle = remember {
        proseMarkerParagraphStyle(LastLineAlignment.End)
    }
    val markerTextStyle = remember(style.body) { style.body.toCjkTextStyle() }
    val coreMarkerTextStyle = remember(markerTextStyle, density) {
        markerTextStyle.toCoreTextStyle(density)
    }
    val markers = remember(block) {
        block.items.mapIndexed { index, item ->
            when (item.task) {
                MarkdownTaskState.Checked -> MarkdownListMarker.Task(checked = true)
                MarkdownTaskState.Unchecked -> MarkdownListMarker.Task(checked = false)
                null -> MarkdownListMarker.Text(
                    if (block.ordered) "${block.startNumber + index}." else "•",
                )
            }
        }
    }
    val widestMarkerWidthPx = remember(markers, coreMarkerTextStyle, markerMeasureParagraphStyle, markerMeasurer) {
        markers.maxOfOrNull { marker ->
            when (marker) {
                is MarkdownListMarker.Task -> coreMarkerTextStyle.fontSize
                is MarkdownListMarker.Text -> markerMeasurer.measure(
                    text = marker.value,
                    constraints = LayoutConstraints(maxWidth = 100_000f),
                    textStyle = coreMarkerTextStyle,
                    paragraphStyle = markerMeasureParagraphStyle,
                ).size.width
            }
        } ?: 0f
    }
    val markerReferenceLayout = remember(coreMarkerTextStyle, markerMeasureParagraphStyle, markerMeasurer) {
        markerMeasurer.measure(
            text = "口",
            constraints = LayoutConstraints(maxWidth = coreMarkerTextStyle.fontSize * 2f),
            textStyle = coreMarkerTextStyle,
            paragraphStyle = markerMeasureParagraphStyle,
        )
    }
    val markerWidth = with(density) { widestMarkerWidthPx.toDp() }
    // This whole-cell band controls only where left-aligned bullets/tasks sit.
    // It is not the width subtracted from the body measure.
    val markerAlignmentBand = ceil(widestMarkerWidthPx / coreMarkerTextStyle.fontSize)
        .toInt()
        .coerceAtLeast(1)
        .ic
    val bodyLineHeight = style.bodyLineHeightSpOrNull()?.let { lineHeight ->
        with(density) { lineHeight.toDp() }
    }
    val tightItemSpacing = bodyLineHeight?.times(style.tightListItemSpacingBodyLines)
        ?: style.tightListItemSpacing
    val looseItemSpacing = bodyLineHeight?.times(style.listItemSpacingBodyLines)
        ?: style.listItemSpacing
    val longMeasureBreakpoint = with(density) {
        style.listLongMeasureBreakpoint.toPx(coreMarkerTextStyle.fontSize).toDp()
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val longMeasure = maxWidth > longMeasureBreakpoint
        val minimumContentIndent = if (longMeasure) style.listLongContentIndent else style.listContentIndent
        val minimumMarkerRegionWidth = with(density) {
            style.minimumListIndentPx(maxWidth.toPx(), coreMarkerTextStyle.fontSize, widestMarkerWidthPx).toDp()
        }
        val markerLeadingSpace = with(density) {
            (minimumContentIndent + -markerAlignmentBand)
                .toPx(coreMarkerTextStyle.fontSize)
                .coerceAtLeast(0f)
                .toDp()
        }

        Column {
            block.items.forEachIndexed { index, item ->
                if (index > 0) {
                    val itemSpacing = if (block.tight) tightItemSpacing else looseItemSpacing
                    if (itemSpacing > 0.dp) Spacer(Modifier.height(itemSpacing))
                }
                WholeCellContentMarkerRow(
                    minimumMarkerRegionWidth = minimumMarkerRegionWidth,
                    contentCellWidthPx = coreMarkerTextStyle.fontSize,
                    modifier = Modifier.fillMaxWidth(),
                ) { markerModifier, contentModifier ->
                    val marker = markers[index]
                    val ordinalMarker = (block.ordered || style.listMarkerAlignment != null) && marker is MarkdownListMarker.Text
                    when (marker) {
                        is MarkdownListMarker.Text -> if (ordinalMarker) {
                            MarkdownSelectionScope(markdownSelectionKey(item.metadata.key, "marker")) {
                                CjkText(
                                    text = marker.value,
                                    modifier = markerModifier,
                                    style = style.body,
                                    paragraphStyle = style.listMarkerAlignment?.let(::proseMarkerParagraphStyle) ?: markerEndParagraphStyle,
                                    measurer = markerMeasurer,
                                )
                            }
                        } else {
                            Row(markerModifier) {
                                Spacer(Modifier.width(markerLeadingSpace))
                                MarkdownSelectionScope(markdownSelectionKey(item.metadata.key, "marker")) {
                                    CjkText(
                                        text = marker.value,
                                        modifier = Modifier.width(markerWidth),
                                        style = style.body,
                                        paragraphStyle = markerStartParagraphStyle,
                                        measurer = markerMeasurer,
                                    )
                                }
                            }
                        }
                        is MarkdownListMarker.Task -> Row(markerModifier) {
                            Spacer(Modifier.width(markerLeadingSpace))
                            MarkdownTaskListMarker(
                                checked = marker.checked,
                                modifier = Modifier.width(markerWidth),
                                referenceLayout = markerReferenceLayout,
                                fontSize = coreMarkerTextStyle.fontSize,
                            )
                        }
                    }
                    MarkdownBlocks(
                        blocks = item.blocks,
                        modifier = contentModifier,
                        style = style.copy(insetProse = true),
                        slots = slots,
                        inlineSlots = inlineSlots,
                        onLinkClick = onLinkClick,
                        onFootnoteClick = onFootnoteClick,
                        compact = block.tight,
                    )
                }
            }
        }
    }
}

/** Shared marker/content geometry for hosts that place list rows themselves. */
data class WholeCellListGeometry(
    val markerRegionWidthPx: Int,
    val contentWidthPx: Int,
)

/**
 * Fits an integral number of content cells after the marker's measured width.
 * The marker itself is never character-cell rounded. Instead its layout region
 * absorbs the sub-cell remainder, so marker region + right-aligned content box
 * exactly equals the body measure.
 */
fun wholeCellListGeometryPx(
    bodyMeasurePx: Int,
    minimumMarkerRegionWidthPx: Int,
    contentCellWidthPx: Float,
): WholeCellListGeometry {
    val geometry = wholeCellListGeometryPx(bodyMeasurePx.coerceAtLeast(0).toFloat(),
        minimumMarkerRegionWidthPx.coerceAtLeast(0).toFloat(), contentCellWidthPx)
    val contentWidth = floor(geometry.contentWidth).toInt()
    return WholeCellListGeometry(bodyMeasurePx - contentWidth, contentWidth)
}

/** Marker/content row whose content box stays on its own whole-cell grid. */
@Composable
private fun WholeCellContentMarkerRow(
    minimumMarkerRegionWidth: Dp,
    contentCellWidthPx: Float,
    modifier: Modifier = Modifier,
    content: @Composable (markerModifier: Modifier, contentModifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val minimumMarkerRegionWidthPx = with(density) { minimumMarkerRegionWidth.roundToPx() }
    Layout(
        modifier = modifier,
        content = { content(Modifier, Modifier) },
    ) { measurables, constraints ->
        require(measurables.size == 2) { "WholeCellContentMarkerRow requires marker and content" }
        val rowWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val geometry = wholeCellListGeometryPx(
            bodyMeasurePx = rowWidth,
            minimumMarkerRegionWidthPx = minimumMarkerRegionWidthPx,
            contentCellWidthPx = contentCellWidthPx,
        )
        val fixedMarkerWidth = geometry.markerRegionWidthPx
        val contentWidth = geometry.contentWidthPx
        val markerPlaceable = measurables[0].measure(
            constraints.copy(minWidth = fixedMarkerWidth, maxWidth = fixedMarkerWidth, minHeight = 0),
        )
        val contentPlaceable = measurables[1].measure(
            constraints.copy(minWidth = contentWidth, maxWidth = contentWidth, minHeight = 0),
        )
        fun baselineOf(placeable: androidx.compose.ui.layout.Placeable): Int =
            placeable[FirstBaseline].takeUnless { it == AlignmentLine.Unspecified } ?: 0
        val markerBaseline = baselineOf(markerPlaceable)
        val contentBaseline = baselineOf(contentPlaceable)
        val row = alignProseFirstLines(contentBaseline.toFloat(), (contentPlaceable.height - contentBaseline).toFloat(),
            markerBaseline.toFloat(), (markerPlaceable.height - markerBaseline).toFloat())
        val rowBaseline = maxOf(markerBaseline, contentBaseline)
        val rowHeight = row.height.roundToInt().coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(
            width = rowWidth,
            height = rowHeight,
            alignmentLines = mapOf(FirstBaseline to rowBaseline),
        ) {
            markerPlaceable.placeRelative(0, row.markerTop.roundToInt())
            contentPlaceable.placeRelative(rowWidth - contentWidth, row.bodyTop.roundToInt())
        }
    }
}

@Composable
private fun MarkdownTaskListMarker(
    checked: Boolean,
    modifier: Modifier,
    referenceLayout: LayoutResult,
    fontSize: Float,
) {
    val density = LocalDensity.current
    val line = referenceLayout.lines.first()
    val metric = referenceLayout.debug.metricDecisions.firstOrNull()
    val faceAscent = metric?.layoutAscent ?: (line.baseline - line.top)
    val faceDescent = metric?.layoutDescent ?: (line.bottom - line.baseline)
    val firstBaseline = ceil(line.baseline).toInt()
    val markerHeight = ceil(referenceLayout.size.height).toInt()
    val markerSize = with(density) { (fontSize * 7f / 8f).toDp() }
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)

    Layout(
        content = {
            Icon(
                painter = painterResource(if (checked) {
                    Res.drawable.ic_check_box_20dp
                } else {
                    Res.drawable.ic_check_box_outline_blank_20dp
                }),
                contentDescription = null,
                modifier = Modifier.requiredSize(markerSize),
                tint = disabledColor,
            )
        },
        modifier = modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
            disabled()
        },
    ) { measurables, constraints ->
        val checkbox = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val faceHeight = faceAscent + faceDescent
        val checkboxTop = line.baseline - faceAscent + (faceHeight - checkbox.height) / 2f
        layout(
            width = constraints.maxWidth,
            height = markerHeight.coerceIn(constraints.minHeight, constraints.maxHeight),
            alignmentLines = mapOf(FirstBaseline to firstBaseline),
        ) {
            checkbox.place(0, checkboxTop.roundToInt())
        }
    }
}

@Composable
fun DefaultMarkdownCodeBlock(block: MarkdownCodeBlock, style: MarkdownStyle) {
    val codeHighlighter = LocalMarkdownCodeHighlighter.current
    val codeLanguage = remember(block.language) { resolveMarkdownCodeLanguage(block.language) }
    val highlights = remember(block.code, codeLanguage, block.highlights, codeHighlighter) {
        block.highlights.ifEmpty {
            codeHighlighter.highlight(block.code, codeLanguage?.tokenizerLabel)
        }
    }
    val highlightedCode = remember(block.code, highlights, style.codeHighlight) {
        buildAnnotatedString {
            append(block.code)
            highlights.forEach { highlight ->
                val start = highlight.range.start.coerceIn(0, block.code.length)
                val end = highlight.range.endExclusive.coerceIn(start, block.code.length)
                if (start < end) addStyle(style.codeHighlight.span(highlight.kind), start, end)
            }
        }
    }
    val lineCount = remember(block.code) { block.code.count { it == '\n' } + 1 }
    val lineNumbers = remember(lineCount) { (1..lineCount).joinToString("\n") }
    val language = codeLanguage?.displayLabel
    val fileName = block.fileName?.takeIf { it.isNotBlank() }
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val copyCodeDescription = stringResource(Res.string.copy_code)
    val codeCopiedDescription = stringResource(Res.string.code_copied)
    var copied by remember(block.metadata.key) { mutableStateOf(false) }
    var copyFeedbackGeneration by remember(block.metadata.key) { mutableIntStateOf(0) }
    LaunchedEffect(copyFeedbackGeneration) {
        if (copyFeedbackGeneration > 0) {
            delay(1_400)
            copied = false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.codeCornerRadius))
            .background(style.codeBackground),
    ) {
        val copyButtonCornerRadius = (style.codeCornerRadius - style.codeCopyOuterInset)
            .coerceAtLeast(0.dp)
        val codeMetaTextVerticalPadding = (
            style.codeMetaVerticalPadding - style.codeCopyOuterInset
        ).coerceAtLeast(0.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .background(style.codeMetaBackground)
                .padding(
                    start = style.codeMetaHorizontalPadding,
                    top = style.codeCopyOuterInset,
                    end = style.codeCopyOuterInset,
                    bottom = style.codeCopyOuterInset,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .padding(
                        top = codeMetaTextVerticalPadding,
                        bottom = codeMetaTextVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (fileName != null) BasicText(fileName, style = style.codeMeta)
                Spacer(Modifier.weight(1f))
                if (language != null) BasicText(language, style = style.codeLanguage)
            }
            Spacer(Modifier.width(style.codeMetaControlSpacing))
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(copyButtonCornerRadius))
                    .markdownClickablePointer()
                    .clickable(role = Role.Button) {
                        clipboardManager.setText(AnnotatedString(block.code))
                        copied = true
                        copyFeedbackGeneration += 1
                    }
                    .semantics {
                        contentDescription = if (copied) codeCopiedDescription else copyCodeDescription
                    },
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState = copied,
                    modifier = Modifier.size(style.codeCopyIconSize),
                    contentAlignment = Alignment.Center,
                    transitionSpec = {
                        (fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.72f)) togetherWith
                            (fadeOut(tween(90)) + scaleOut(tween(90), targetScale = 0.72f))
                    },
                ) { successful ->
                    Icon(
                        painter = painterResource(
                            if (successful) Res.drawable.ic_check_16dp
                            else Res.drawable.ic_content_copy_16dp,
                        ),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        tint = style.codeMeta.color,
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(style.codePadding),
        ) {
            if (block.showLineNumbers) {
                BasicText(
                    text = lineNumbers,
                    style = style.codeBlock.copy(
                        color = style.codeLineNumberColor,
                        textAlign = TextAlign.End,
                    ),
                )
                Spacer(Modifier.width(style.codeColumnSpacing))
            }
            BasicText(text = highlightedCode, style = style.codeBlock)
        }
    }
}

@Composable
fun DefaultMarkdownHtmlBlock(block: MarkdownHtmlBlock, style: MarkdownStyle) {
    BasicText(text = block.html, style = style.codeBlock)
}

@Composable
fun DefaultMarkdownFootnoteDefinition(
    block: MarkdownFootnoteDefinition,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots = DefaultMarkdownBlockSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    markerWidth: Dp? = null,
) {
    val footnoteNavigationState = LocalMarkdownFootnoteNavigationState.current
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    DisposableEffect(footnoteNavigationState, block.label, bringIntoViewRequester) {
        footnoteNavigationState?.registerDefinition(block.label, bringIntoViewRequester)
        onDispose {
            footnoteNavigationState?.unregisterDefinition(block.label, bringIntoViewRequester)
        }
    }
    val footnoteStyle = remember(style) {
        style.copy(body = style.footnoteContentTextStyle())
    }
    val density = LocalDensity.current
    val markerMeasurer = rememberMarkdownParagraphMeasurer()
    val markerTextStyle = remember(footnoteStyle.body) { footnoteStyle.body.toCjkTextStyle() }
    val coreMarkerTextStyle = remember(markerTextStyle, density) {
        markerTextStyle.toCoreTextStyle(density)
    }
    val markerParagraphStyle = remember {
        proseMarkerParagraphStyle(LastLineAlignment.Start)
    }
    val singleMarkerWidth = remember(block.index, coreMarkerTextStyle, markerParagraphStyle, markerMeasurer, density) {
        val measuredWidth = markerMeasurer.measure(
            text = "[${block.index}]",
            constraints = LayoutConstraints(maxWidth = 100_000f),
            textStyle = coreMarkerTextStyle,
            paragraphStyle = markerParagraphStyle,
        ).size.width
        with(density) { measuredWidth.toDp() }
    }
    val resolvedMarkerWidth = markerWidth ?: singleMarkerWidth
    WholeCellContentMarkerRow(
        minimumMarkerRegionWidth = resolvedMarkerWidth,
        contentCellWidthPx = coreMarkerTextStyle.fontSize,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester),
    ) { markerModifier, contentModifier ->
        MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "marker")) {
            CjkText(
                text = "[${block.index}]",
                modifier = markerModifier,
                style = footnoteStyle.body,
                paragraphStyle = markerParagraphStyle,
                measurer = markerMeasurer,
            )
        }
        MarkdownBlocks(
            blocks = block.blocks,
            modifier = contentModifier,
            style = footnoteStyle,
            slots = slots,
            inlineSlots = inlineSlots,
            onLinkClick = onLinkClick,
            onFootnoteClick = onFootnoteClick,
            compact = true,
        )
    }
}

@Composable
internal fun MarkdownStyle.captionSpacing() = bodyLineHeightSpOrNull()?.let { lineHeight ->
    with(LocalDensity.current) { (lineHeight * 1f / 4f).toDp() }
} ?: 6.dp

@Composable
private fun MarkdownStyle.captionHorizontalIndentDp() = with(LocalDensity.current) {
    val bodyFontSizePx = body.toCjkTextStyle().toCoreTextStyle(this).fontSize
    captionHorizontalIndent.toPx(bodyFontSizePx).toDp()
}

@Composable
internal fun MarkdownStyle.figureCaptionMinimumWidthDp() = with(LocalDensity.current) {
    val bodyFontSizePx = body.toCjkTextStyle().toCoreTextStyle(this).fontSize
    figureCaptionMinimumWidth.toPx(bodyFontSizePx).toDp()
}

/** Shared caption presentation for host-owned figures and the built-in image/table renderers. */
@Composable
fun DefaultMarkdownCaption(
    caption: MarkdownText,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
) {
    MarkdownTextBlock(
        text = caption,
        textStyle = style.caption,
        markdownStyle = style,
        inlineSlots = inlineSlots,
        onLinkClick = onLinkClick,
        onFootnoteClick = onFootnoteClick,
        modifier = modifier.padding(horizontal = style.captionHorizontalIndentDp()),
    )
}

/** Shared figure-caption rhythm for host-owned images and the built-in image renderer. */
@Composable
fun DefaultMarkdownFigureCaption(
    caption: MarkdownText,
    style: MarkdownStyle,
    modifier: Modifier = Modifier,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
) {
    Spacer(Modifier.height(style.captionSpacing()))
    DefaultMarkdownCaption(
        caption = caption,
        style = style,
        modifier = modifier,
        inlineSlots = inlineSlots,
        onLinkClick = onLinkClick,
        onFootnoteClick = onFootnoteClick,
    )
}

@Composable
fun DefaultMarkdownCustomBlock(block: MarkdownCustomBlock, style: MarkdownStyle) {
    BasicText(text = block.metadata.sourceMarkdown.orEmpty(), style = style.body)
}

@Composable
fun DefaultMarkdownThematicBreak(style: MarkdownStyle) {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(style.thematicBreakColor))
}

@Composable
fun DefaultMarkdownUnsupportedBlock(block: MarkdownUnsupportedBlock, style: MarkdownStyle) {
    val fallback = block.fallbackText.ifBlank { block.metadata.sourceMarkdown.orEmpty() }
    BasicText(text = fallback, style = style.body)
}
