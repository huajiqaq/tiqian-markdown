package org.tiqian.markdown.compose

import org.tiqian.compose.measure
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.core.*
import kotlin.math.floor
import kotlin.math.ceil

/** Format-independent block placement. Paragraph shaping and line grids remain engine-owned. */
data class ProseFirstLine(val bodyTop: Float, val markerTop: Float, val height: Float)

fun alignProseFirstLines(bodyAscent: Float, bodyDescent: Float, markerAscent: Float, markerDescent: Float): ProseFirstLine {
    val baseline = maxOf(bodyAscent, markerAscent)
    return ProseFirstLine(baseline - bodyAscent, baseline - markerAscent, baseline + maxOf(bodyDescent, markerDescent))
}

/** Ask the paragraph engine for the parent prose frame; do not infer it from glyph ink. */
fun measureProseFrameWidth(measurer: ParagraphMeasurer, availableWidth: Float, fontSize: Float): Float =
    measurer.measure("永", LayoutConstraints(maxWidth = availableWidth), TextStyle(fontSize = fontSize),
        ParagraphStyle(firstLineIndent = Ic.Zero)).debug.lineLengthGridDecision?.measure
        ?: error("Paragraph backend must expose its line-length grid decision")

/** Compose needs an integer pixel container that still contains the engine's fractional frame. */
internal fun proseFrameContainerWidth(frameWidth: Float, availableWidth: Float): Float =
    ceil(frameWidth).coerceAtMost(availableWidth)

data class ProseInsetGeometry(val contentLeft: Float, val contentWidth: Float)

/** The outer right edge is inherited. The engine places its inner whole-cell frame at that edge. */
fun insetProseGeometry(parentWidth: Float, startInset: Float, endInset: Float = 0f): ProseInsetGeometry {
    require(parentWidth.isFinite() && parentWidth >= 0f)
    require(startInset.isFinite() && startInset >= 0f && endInset.isFinite() && endInset >= 0f)
    val start = startInset.coerceAtMost(parentWidth)
    return ProseInsetGeometry(start, (parentWidth - start - endInset).coerceAtLeast(0f))
}

fun insetProseParagraphStyle(base: ParagraphStyle = ParagraphStyle()): ParagraphStyle =
    base.copy(lineLengthGrid = base.lineLengthGrid.copy(bodyAlignment = LastLineAlignment.End))

fun proseMarkerParagraphStyle(alignment: LastLineAlignment, base: ParagraphStyle = ParagraphStyle()): ParagraphStyle =
    base.copy(firstLineIndent = Ic.Zero, lineLengthGrid = LineLengthGrid(enabled = false), lastLineAlignment = alignment)

/** Float version for native hosts; avoid rounding an exact N-cell measure below N cells. */
data class ProseListGeometry(val markerRegionWidth: Float, val contentWidth: Float)

fun wholeCellListGeometryPx(bodyMeasurePx: Float, minimumMarkerRegionWidthPx: Float, contentCellWidthPx: Float): ProseListGeometry {
    require(bodyMeasurePx.isFinite() && bodyMeasurePx >= 0f)
    require(minimumMarkerRegionWidthPx.isFinite() && minimumMarkerRegionWidthPx >= 0f)
    require(contentCellWidthPx.isFinite() && contentCellWidthPx > 0f)
    val available = (bodyMeasurePx - minimumMarkerRegionWidthPx).coerceIn(0f, bodyMeasurePx)
    val cells = floor(available / contentCellWidthPx)
    val content = if (cells < 1f) available else (cells * contentCellWidthPx).coerceAtMost(available)
    return ProseListGeometry(bodyMeasurePx - content, content)
}

fun MarkdownStyle.minimumListIndentPx(parentWidth: Float, fontSize: Float, markerWidth: Float): Float =
    maxOf(markerWidth, (if (parentWidth > listLongMeasureBreakpoint.toPx(fontSize)) listLongContentIndent else listContentIndent).toPx(fontSize))

fun MarkdownStyle.blockGapBodyLines(previousHeading: Int?, nextHeading: Int?, display: Boolean = false,
    adjacentLists: Boolean = false, compact: Boolean = false): Float =
    maxOf(headingGapBodyLines(previousHeading, nextHeading) ?: when {
        adjacentLists -> listBlockSpacingBodyLines
        compact -> compactBlockSpacingBodyLines
        else -> blockSpacingBodyLines
    }, if (display) displayBlockSpacingBodyLines else 0f)

/** Quote decoration is expressed in block coordinates, and can be sliced by a page host. */
data class ProseQuoteBar(val left: Float, val top: Float, val width: Float, val height: Float)

fun proseQuoteBar(contentLeft: Float, contentTop: Float, contentHeight: Float,
    barWidth: Float, contentPadding: Float): ProseQuoteBar =
    ProseQuoteBar(contentLeft - contentPadding - barWidth, contentTop, barWidth, contentHeight)
