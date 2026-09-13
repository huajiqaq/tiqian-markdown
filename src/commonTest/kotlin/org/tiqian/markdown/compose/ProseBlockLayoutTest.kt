package org.tiqian.markdown.compose

import org.tiqian.core.LastLineAlignment
import org.tiqian.core.ParagraphStyle
import kotlin.test.*

class ProseBlockLayoutTest {
    @Test fun fractionalListKeepsExactCellsAndParentRightEdge() {
        val frame = 13 * 27.225f
        val row = wholeCellListGeometryPx(frame, 32.4f, 27.225f)
        assertEquals(frame, row.markerRegionWidth + row.contentWidth, .0001f)
        assertEquals(11 * 27.225f, row.contentWidth, .0001f)
        val nested = wholeCellListGeometryPx(row.contentWidth, 32.4f, 27.225f)
        assertEquals(frame, row.markerRegionWidth + nested.markerRegionWidth + nested.contentWidth, .0001f)
    }
    @Test fun insetFrameAlignmentDoesNotChangeLastLineOrMarkerPolicy() {
        val base = ParagraphStyle(lastLineAlignment = LastLineAlignment.Start)
        val quote = insetProseParagraphStyle(base)
        assertEquals(LastLineAlignment.Start, quote.lastLineAlignment)
        assertEquals(LastLineAlignment.End, quote.lineLengthGrid.bodyAlignment)
        val marker = proseMarkerParagraphStyle(LastLineAlignment.Start, quote)
        assertFalse(marker.lineLengthGrid.enabled)
        assertEquals(LastLineAlignment.Start, marker.lastLineAlignment)
        val inset = insetProseGeometry(353.925f, 18.75f)
        assertEquals(353.925f, inset.contentLeft + inset.contentWidth, .0001f)
    }
    @Test fun markerBaselineUnionIncludesBothDescenders() {
        val row = alignProseFirstLines(18.25f, 9.5f, 24.75f, 4.25f)
        assertEquals(6.5f, row.bodyTop)
        assertEquals(0f, row.markerTop)
        assertEquals(34.25f, row.height)
    }
}
