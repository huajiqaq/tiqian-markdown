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
import androidx.compose.ui.unit.Density
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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// `TableCellLineGrid`: cell prose follows the same integer-em line-length rule as body
// text (ADR 0028); the former `enabled = false` came in through an API refactor with no
// recorded rationale. Width negotiation keeps columns on the em grid so the quantized
// measure never leaves per-line slack.
internal val markdownTableParagraphStyle = ParagraphStyle(
    firstLineIndent = 0.ic,
    lastLineAlignment = LastLineAlignment.Start,
)

// Probe measures want RAW content widths — quantization belongs to the column
// allocator, and a sub-em probe width must not be floored to zero.
internal val markdownTableProbeParagraphStyle =
    markdownTableParagraphStyle.copy(lineLengthGrid = LineLengthGrid(enabled = false))

/**
 * Negotiates table column widths from two engine probes per cell.
 *
 * `TableSubtreePrelayout`: the negotiation is a pure function of the block, style, density and
 * available width, so the background prelayout worker runs exactly this off the UI thread and
 * the entering frame reuses the result instead of re-measuring every cell.
 */
internal fun resolveMarkdownTableWidthResolution(
    block: MarkdownTable,
    style: MarkdownStyle,
    density: Density,
    measurer: ParagraphMeasurer,
    columnCount: Int,
    availableWidthPx: Float,
): MarkdownTableWidthResolution {
    val horizontalPaddingPx = with(density) { style.tableCellPadding.toPx() * 2f }
    val preferredWidthCapPx = with(density) { style.tableColumnWidth.toPx() }
    val normalCoreStyle = style.tableText.toCjkTextStyle().toCoreTextStyle(density)
    val headerCoreStyle = style.tableText
        .copy(fontWeight = FontWeight.Medium)
        .toCjkTextStyle()
        .toCoreTextStyle(density)
    val preferredWidthsPx = MutableList(columnCount) { horizontalPaddingPx }
    val minimumWidthsPx = MutableList(columnCount) { horizontalPaddingPx }
    block.rows.forEach { row ->
        row.cells.forEachIndexed { columnIndex, cell ->
            if (columnIndex >= columnCount) return@forEachIndexed
            val coreStyle = if (cell.header) headerCoreStyle else normalCoreStyle
            val unwrapped = measurer.measure(
                text = cell.text.value,
                constraints = LayoutConstraints(maxWidth = 100_000f),
                textStyle = coreStyle,
                paragraphStyle = markdownTableProbeParagraphStyle,
            )
            val naturalTextWidthPx = unwrapped.lines.maxOfOrNull { line ->
                line.indent + line.visualWidth + line.hyphenAdvance
            } ?: 0f
            val preferredContentWidthPx = naturalTextWidthPx.coerceAtMost(
                (preferredWidthCapPx - horizontalPaddingPx).coerceAtLeast(0f),
            )
            val preferredCellWidthPx = preferredContentWidthPx + horizontalPaddingPx
            // `TableMinimumViaMaximalBreaking`: one measure at a minimal measure forces a
            // break at every opportunity, so the widest resulting line IS the longest
            // unbreakable chunk — replacing the former 10-round binary search that cost
            // ten engine layouts per cell.
            val maximallyBroken = measurer.measure(
                text = cell.text.value,
                constraints = LayoutConstraints(maxWidth = 1f),
                textStyle = coreStyle,
                paragraphStyle = markdownTableProbeParagraphStyle,
            )
            val minimumContentWidthPx = maximallyBroken.lines.maxOfOrNull { line ->
                line.indent + line.visualWidth + line.hyphenAdvance
            } ?: 0f
            val readableContentWidthPx = style.tableReadableColumnWidth
                .toPx(coreStyle.fontSize)
                .coerceAtMost(preferredContentWidthPx)
            val minimumCellWidthPx = maxOf(
                minimumContentWidthPx,
                readableContentWidthPx,
            ) + horizontalPaddingPx
            preferredWidthsPx[columnIndex] = maxOf(
                preferredWidthsPx[columnIndex],
                preferredCellWidthPx,
            )
            minimumWidthsPx[columnIndex] = maxOf(
                minimumWidthsPx[columnIndex],
                minimumCellWidthPx,
            )
        }
    }
    val availablePx = availableWidthPx
        .takeIf(Float::isFinite)
        ?: preferredWidthsPx.sum()
    // `TableFluidFill` target: tables stretch to the prose fluid tier, not the whole
    // prose measure — a compact table on a wide measure stays dense.
    val bodyEmPx = style.body.toCjkTextStyle().toCoreTextStyle(density).fontSize
    return resolveMarkdownTableWidths(
        preferredWidths = preferredWidthsPx,
        minimumWidths = minimumWidthsPx,
        availableWidth = availablePx,
        emPx = normalCoreStyle.fontSize,
        fillTargetWidth = minOf(availablePx, style.proseMeasure.fluidStart.count * bodyEmPx),
        horizontalPaddingPx = horizontalPaddingPx,
    )
}

@Composable
fun DefaultMarkdownTable(
    block: MarkdownTable,
    style: MarkdownStyle,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
) {
    val density = LocalDensity.current
    val tableMeasurer = rememberMarkdownParagraphMeasurer()
    val borderWidthPx = with(density) { style.tableBorderWidth.toPx() }
    val columnCount = maxOf(
        block.columnAlignments.size,
        block.rows.maxOfOrNull { it.cells.size } ?: 0,
    )
    if (columnCount == 0) {
        block.caption?.let { caption ->
            MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "caption")) {
                DefaultMarkdownCaption(
                    caption = caption,
                    style = style,
                    inlineSlots = inlineSlots,
                    onLinkClick = onLinkClick,
                    onFootnoteClick = onFootnoteClick,
                )
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableWidthPx = with(density) { maxWidth.toPx() }
        // `TableSubtreePrelayout`: the background worker negotiates the same columns at the
        // block's full width, so an entering frame only has to recognise its own measure. The
        // half-pixel window keeps the reuse tied to THIS measure; anything else negotiates here
        // exactly as before.
        val precomputedWidths = LocalMarkdownPrecomputedTableWidths
            .current[markdownSelectionKey(block.metadata.key, "widths")]
        val widthResolution = remember(block, style, density, tableMeasurer, columnCount, maxWidth) {
            precomputedWidths
                ?.takeIf { abs(it.availableWidthPx - availableWidthPx) < 0.5f }
                ?.resolution
                ?: resolveMarkdownTableWidthResolution(
                    block = block,
                    style = style,
                    density = density,
                    measurer = tableMeasurer,
                    columnCount = columnCount,
                    availableWidthPx = availableWidthPx,
                )
        }
        val tableWidth = with(density) { widthResolution.tableWidth.toDp() }
        val cellWidths = widthResolution.columnWidths.map { width ->
            with(density) { width.toDp() }
        }
        val centeringWidth = maxOf(maxWidth, tableWidth)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Box(Modifier.width(centeringWidth)) {
                Column(
                    Modifier
                        .width(tableWidth)
                        .align(Alignment.TopCenter),
                ) {
                    block.caption?.let { caption ->
                        MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "caption")) {
                            DefaultMarkdownCaption(
                                caption = caption,
                                style = style,
                                inlineSlots = inlineSlots,
                                onLinkClick = onLinkClick,
                                onFootnoteClick = onFootnoteClick,
                            )
                        }
                        Spacer(Modifier.height(style.captionSpacing()))
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(style.tableCornerRadius))
                            .border(
                                style.tableBorderWidth,
                                style.tableBorderColor,
                                RoundedCornerShape(style.tableCornerRadius),
                            ),
                    ) {
                        block.rows.forEachIndexed { rowIndex, row ->
                            val revision = LocalMarkdownDocumentRevision.current
                            val rowContent: @Composable () -> Unit = {
                                MarkdownNodeClickScope(event = {
                                    MarkdownNodeClickEvent(
                                        nodeType = row.metadata.nodeType ?: "TableRow",
                                        text = row.cells.joinToString("\t") { it.text.value },
                                        metadata = row.metadata,
                                        payload = mapOf("header" to row.header.toString()) + row.metadata.payload,
                                        documentRevision = revision,
                                    )
                                }) {
                                    Row(Modifier.height(IntrinsicSize.Min)) {
                                        row.cells.forEachIndexed { columnIndex, cell ->
                                            val cellModifier = Modifier
                                                .width(cellWidths[columnIndex])
                                                .fillMaxHeight()
                                                .let { modifier ->
                                                    if (cell.header) {
                                                        modifier.background(style.tableHeaderBackground)
                                                    } else {
                                                        modifier
                                                    }
                                                }
                                                .drawWithContent {
                                                    drawContent()
                                                    if (rowIndex > 0) {
                                                        drawLine(
                                                            color = style.tableBorderColor,
                                                            start = Offset(0f, borderWidthPx / 2f),
                                                            end = Offset(size.width, borderWidthPx / 2f),
                                                            strokeWidth = borderWidthPx,
                                                        )
                                                    }
                                                    if (columnIndex > 0) {
                                                        drawLine(
                                                            color = style.tableBorderColor,
                                                            start = Offset(borderWidthPx / 2f, 0f),
                                                            end = Offset(borderWidthPx / 2f, size.height),
                                                            strokeWidth = borderWidthPx,
                                                        )
                                                    }
                                                }
                                                .padding(style.tableCellPadding)
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
                                            MarkdownNodeClickScope(event = {
                                                MarkdownNodeClickEvent(
                                                    nodeType = cell.metadata.nodeType ?: "TableCell",
                                                    text = cell.text.value,
                                                    metadata = cell.metadata,
                                                    payload = mapOf("header" to cell.header.toString(), "alignment" to cell.alignment.name) + cell.metadata.payload,
                                                    documentRevision = revision,
                                                )
                                            }, modifier = cellModifier) {
                                                MarkdownSelectionScope(markdownSelectionKey(cell.metadata.key, "cell")) {
                                                    MarkdownTextBlock(
                                                        text = cell.text,
                                                        textStyle = textStyle,
                                                        markdownStyle = style,
                                                        inlineSlots = inlineSlots,
                                                        onLinkClick = onLinkClick,
                                                        onFootnoteClick = onFootnoteClick,
                                                        modifier = if (LocalMarkdownNodeClick.current?.value == null) cellModifier else Modifier,
                                                        paragraphStyle = markdownTableParagraphStyle,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            val section = row.sectionMetadata
                            if (section == null) rowContent() else MarkdownNodeClickScope(event = {
                                MarkdownNodeClickEvent(
                                    nodeType = section.nodeType ?: if (row.header) "TableHead" else "TableBody",
                                    text = block.rows.filter { it.sectionMetadata?.key == section.key }.joinToString("\n") {
                                        it.cells.joinToString("\t") { cell -> cell.text.value }
                                    },
                                    metadata = section,
                                    documentRevision = revision,
                                )
                            }, content = rowContent)
                        }
                    }
                }
            }
        }
    }
}

