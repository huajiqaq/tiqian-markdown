package org.tiqian.markdown.compose

import org.tiqian.markdown.DefaultMarkdownCodeHighlighter
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownCodeHighlighter
import org.tiqian.markdown.MarkdownCustomBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownFootnotePlacement
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownHtmlBlock
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownMathBlock
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownRenderDocument
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownThematicBreak
import org.tiqian.markdown.MarkdownUnsupportedBlock
import org.tiqian.markdown.markdownImageGallery
import org.tiqian.markdown.placeFootnotes

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.ParagraphMeasurementSession
import org.tiqian.compose.rememberParagraphMeasurer
import org.tiqian.compose.toCjkTextStyle
import org.tiqian.compose.toCoreTextStyle

/** Slots for non-prose blocks. The default set never performs network loading. */
class MarkdownBlockSlots(
    val codeBlock: (@Composable (MarkdownCodeBlock, MarkdownStyle) -> Unit)? = null,
    val imageBlock: (@Composable (MarkdownImageBlock, MarkdownStyle) -> Unit)? = null,
    val mathBlock: (@Composable (MarkdownMathBlock, MarkdownStyle) -> Unit)? = null,
    val htmlBlock: (@Composable (MarkdownHtmlBlock, MarkdownStyle) -> Unit)? = null,
    val table: (@Composable (MarkdownTable, MarkdownStyle) -> Unit)? = null,
    val footnoteDefinition: (@Composable (MarkdownFootnoteDefinition, MarkdownStyle) -> Unit)? = null,
    val customBlock: (@Composable (MarkdownCustomBlock, MarkdownStyle) -> Unit)? = null,
    val thematicBreak: (@Composable (MarkdownThematicBreak, MarkdownStyle) -> Unit)? = null,
    val unsupportedBlock: (@Composable (MarkdownUnsupportedBlock, MarkdownStyle) -> Unit)? = null,
)

val DefaultMarkdownBlockSlots: MarkdownBlockSlots = MarkdownBlockSlots()

internal val LocalMarkdownCodeHighlighter = staticCompositionLocalOf<MarkdownCodeHighlighter> {
    DefaultMarkdownCodeHighlighter
}

internal val LocalMarkdownParagraphMeasurer = staticCompositionLocalOf<ParagraphMeasurer?> { null }

/** Renders a host-adapted Markdown document without owning parsing or scrolling. */
@Composable
fun TiqianMarkdown(
    document: MarkdownRenderDocument,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = rememberMarkdownStyle(),
    slots: MarkdownBlockSlots = DefaultMarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
    footnotePlacement: MarkdownFootnotePlacement = MarkdownFootnotePlacement.AfterBlock,
    codeHighlighter: MarkdownCodeHighlighter = DefaultMarkdownCodeHighlighter,
) {
    val density = LocalDensity.current
    val deferredLayout = LocalMarkdownDeferredLayout.current
    val placedBlocks = remember(document, footnotePlacement) {
        markdownTraceSection("TiqianMarkdown.placeFootnotes") {
            document.placeFootnotes(footnotePlacement)
        }
    }
    val footnoteOwnerKeys = remember(placedBlocks) {
        placedBlocks.filterIsInstance<MarkdownFootnoteDefinition>()
            .associate { it.label to it.metadata.key }
    }
    val footnoteNavigationState = rememberMarkdownFootnoteNavigationState(
        materializeDefinition = { label ->
            footnoteOwnerKeys[label]?.let { key -> deferredLayout?.onMaterializeOwner?.invoke(key) } == true
        },
    )
    val imageGallery = remember(placedBlocks) {
        markdownTraceSection("TiqianMarkdown.imageGallery") {
            placedBlocks.markdownImageGallery()
        }
    }
    val measurementSession = remember(
        document,
        style,
        density.density,
        density.fontScale,
    ) {
        ParagraphMeasurementSession()
    }
    val paragraphMeasurer = rememberParagraphMeasurer(session = measurementSession)
    val containsMath = remember(placedBlocks) {
        markdownTraceSection("TiqianMarkdown.containsMath") {
            placedBlocks.containsMarkdownMath()
        }
    }
    val mathRuntime = if (containsMath) rememberMarkdownMathRuntime(style) else null
    CompositionLocalProvider(
        LocalMarkdownFootnoteNavigationState provides footnoteNavigationState,
        LocalMarkdownImageGallery provides imageGallery,
        LocalMarkdownCodeHighlighter provides codeHighlighter,
        LocalMarkdownParagraphMeasurer provides paragraphMeasurer,
        LocalMarkdownMathRuntime provides mathRuntime,
    ) {
        BoxWithConstraints(modifier) {
            val bodyCoreTextStyle = remember(style.body, density) {
                style.body.toCjkTextStyle().toCoreTextStyle(density)
            }
            val availableWidthPx = with(density) { maxWidth.toPx() }
            val proseWidth = if (style.proseMeasure.enabled) {
                availableWidthPx.takeIf(Float::isFinite)?.let { width ->
                    val availableCells = width / bodyCoreTextStyle.fontSize
                    val resolvedCells = resolveMarkdownProseMeasureCells(availableCells, style.proseMeasure)
                    val frameWidth = remember(width, resolvedCells, bodyCoreTextStyle.fontSize, paragraphMeasurer) {
                        measureProseFrameWidth(paragraphMeasurer, resolvedCells * bodyCoreTextStyle.fontSize, bodyCoreTextStyle.fontSize)
                    }
                    with(density) { proseFrameContainerWidth(frameWidth, width).toDp() }
                }
            } else {
                null
            }
            val estimatedBlockHeights = remember(
                placedBlocks,
                style,
                maxWidth,
                density.density,
                density.fontScale,
                deferredLayout != null,
            ) {
                if (deferredLayout == null) {
                    emptyList()
                } else {
                    val widthDp = maxWidth.value.coerceAtLeast(1f)
                    markdownTraceSection("TiqianMarkdown.estimateHeights") {
                        placedBlocks.map { block ->
                            estimateMarkdownBlockHeightDp(block, widthDp, style)
                        }
                    }
                }
            }
            if (deferredLayout == null) {
                MarkdownBlocks(
                    blocks = placedBlocks,
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    slots = slots,
                    inlineSlots = inlineSlots,
                    onLinkClick = onLinkClick,
                    onFootnoteClick = onFootnoteClick,
                    compact = false,
                    topLevelProseWidth = proseWidth,
                )
            } else {
                DeferredMarkdownBlocks(
                    blocks = placedBlocks,
                    estimatedHeightsDp = estimatedBlockHeights,
                    deferredLayout = deferredLayout,
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    slots = slots,
                    inlineSlots = inlineSlots,
                    onLinkClick = onLinkClick,
                    onFootnoteClick = onFootnoteClick,
                    topLevelProseWidth = proseWidth,
                    mathRuntime = mathRuntime,
                    prelayoutWidth = proseWidth ?: maxWidth,
                    measurementSession = measurementSession,
                )
            }
        }
    }
}

internal fun List<MarkdownBlock>.containsMarkdownMath(): Boolean = any { block ->
    when (block) {
        is MarkdownMathBlock -> true
        is MarkdownParagraph -> block.text.containsMarkdownMath()
        is MarkdownHeading -> block.text.containsMarkdownMath()
        is MarkdownBlockQuote -> block.blocks.containsMarkdownMath()
        is MarkdownList -> block.items.any { it.blocks.containsMarkdownMath() }
        is MarkdownImageBlock -> block.caption?.containsMarkdownMath() == true
        is MarkdownTable ->
            block.caption?.containsMarkdownMath() == true ||
                block.rows.any { row -> row.cells.any { it.text.containsMarkdownMath() } }
        is MarkdownFootnoteDefinition -> block.blocks.containsMarkdownMath()
        is MarkdownCodeBlock,
        is MarkdownCustomBlock,
        is MarkdownHtmlBlock,
        is MarkdownThematicBreak,
        is MarkdownUnsupportedBlock,
        -> false
    }
}

private fun MarkdownText.containsMarkdownMath(): Boolean =
    spans.any { it.mark is MarkdownTextMark.InlineMath }
