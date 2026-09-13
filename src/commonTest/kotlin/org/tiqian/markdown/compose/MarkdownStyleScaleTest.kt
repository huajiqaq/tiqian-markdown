package org.tiqian.markdown.compose

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.MarkdownNodeMetadata
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownSourceSpan
import org.tiqian.markdown.MarkdownText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MarkdownStyleScaleTest {
    @Test
    fun externallyScrolledSurfaceUsesWindowViewportInsteadOfItsContentHeight() {
        assertEquals(
            800,
            markdownViewportHeightPx(
                surfaceOwnsScroll = false,
                measuredSurfaceHeightPx = 12_000,
                windowHeightPx = 800,
            ),
        )
    }

    @Test
    fun internallyScrolledSurfaceUsesItsMeasuredViewport() {
        assertEquals(
            600,
            markdownViewportHeightPx(
                surfaceOwnsScroll = true,
                measuredSurfaceHeightPx = 600,
                windowHeightPx = 800,
            ),
        )
    }

    @Test
    fun externallyScrolledSurfaceKeepsWindowViewportOrigin() {
        assertEquals(
            0f,
            markdownSurfaceViewportTopInWindowPx(
                surfaceOwnsScroll = false,
                measuredSurfaceTopInWindowPx = 120f,
            ),
        )
    }

    @Test
    fun internallyScrolledViewportAccountsForFixedChromeAboveSurface() {
        val range = markdownViewportRangeRelativeToContent(
            viewportTopInWindowPx = 120f,
            viewportHeightPx = 600,
            contentOriginInScrollPx = 120f,
            scrollOffsetPx = 400,
        )

        assertEquals(MarkdownViewportRangePx(top = 400f, bottom = 1_000f), range)
    }

    @Test
    fun uncalibratedInternalViewportDoesNotTreatFixedChromeAsScrolledContent() {
        val range = markdownViewportRangeRelativeToContent(
            viewportTopInWindowPx = 120f,
            viewportHeightPx = 600,
            contentOriginInScrollPx = Float.NaN,
            scrollOffsetPx = 0,
        )

        assertEquals(MarkdownViewportRangePx(top = 0f, bottom = 600f), range)
    }

    @Test
    fun calibratedViewportAccountsForHeaderInsideSurface() {
        val range = markdownViewportRangeRelativeToContent(
            viewportTopInWindowPx = 120f,
            viewportHeightPx = 600,
            contentOriginInScrollPx = 200f,
            scrollOffsetPx = 400,
        )

        assertEquals(MarkdownViewportRangePx(top = 320f, bottom = 920f), range)
    }

    @Test
    fun measuredPixelHeightImmediatelyExposesTheNextVisibleBlock() {
        assertEquals(
            0..0,
            deferredMarkdownBlockIndices(
                estimatedHeightsDp = listOf(800f, 800f),
                density = 1f,
                visibleTopPx = 0f,
                visibleBottomPx = 600f,
                viewportHeightPx = 600,
            ),
        )
        assertEquals(
            0..1,
            deferredMarkdownBlockIndicesFromPx(
                blockHeightsPx = listOf(200, 800),
                visibleTopPx = 0f,
                visibleBottomPx = 600f,
                viewportHeightPx = 600,
            ),
        )
    }

    @Test
    fun viewportAtEstimatedDocumentEndClampsAfterHeightCorrection() {
        assertEquals(
            MarkdownViewportRangePx(top = 400f, bottom = 1_000f),
            markdownViewportRangeClampedToContent(
                viewportRange = MarkdownViewportRangePx(top = 900f, bottom = 1_500f),
                viewportHeightPx = 600,
                contentHeightPx = 1_000L,
            ),
        )
    }

    @Test
    fun zeroHeightBlockIsRetainedAsAMeasuredResult() {
        assertTrue(
            shouldUpdateMarkdownMeasuredHeight(
                cachedHeightPx = null,
                measuredHeightPx = 0,
            ),
        )
        assertFalse(
            shouldUpdateMarkdownMeasuredHeight(
                cachedHeightPx = 0,
                measuredHeightPx = 0,
            ),
        )
    }

    @Test
    fun deferredBlockRangeCoversOnlyTheVisibleViewport() {
        assertEquals(
            0..2,
            deferredMarkdownBlockIndices(
                estimatedHeightsDp = List(10) { 100f },
                density = 2f,
                visibleTopPx = 200f,
                visibleBottomPx = 520f,
                viewportHeightPx = 320,
            ),
        )
    }

    @Test
    fun pixelScrollWithinTheSameVisibleBlocksKeepsOneStableRange() {
        val heights = List(10) { 100f }

        val first = deferredMarkdownBlockIndices(
            estimatedHeightsDp = heights,
            density = 1f,
            visibleTopPx = 10f,
            visibleBottomPx = 110f,
            viewportHeightPx = 100,
        )
        val later = deferredMarkdownBlockIndices(
            estimatedHeightsDp = heights,
            density = 1f,
            visibleTopPx = 40f,
            visibleBottomPx = 140f,
            viewportHeightPx = 100,
        )

        assertEquals(first, later)
    }

    @Test
    fun deferredBlockRangeIsEmptyPastEstimatedTail() {
        assertEquals(
            IntRange.EMPTY,
            deferredMarkdownBlockIndices(
                estimatedHeightsDp = listOf(100f, 100f, 100f),
                density = 1f,
                visibleTopPx = 1_000f,
                visibleBottomPx = 1_100f,
                viewportHeightPx = 100,
            ),
        )
    }

    @Test
    fun backgroundPrelayoutStopsAfterOneViewportOfBlockHeights() {
        val blocks = List(8) { index ->
            MarkdownParagraph(MarkdownText("paragraph $index"), metadata(index))
        }

        assertEquals(
            listOf(2, 3),
            prefetchMarkdownBlockIndices(
                blocks = blocks,
                visibleRange = 0..1,
                forward = true,
                blockHeightsPx = listOf(100, 100, 180, 180, 180, 180, 180, 180),
                prefetchDistancePx = 300,
            ),
        )
    }

    @Test
    fun backgroundPrelayoutPrioritizesTheVisibleRangeBeforeLookahead() {
        val blocks = List(8) { index ->
            MarkdownParagraph(MarkdownText("paragraph $index"), metadata(index))
        }

        assertEquals(
            listOf(3, 4, 5, 6),
            prelayoutMarkdownBlockIndices(
                blocks = blocks,
                visibleRange = 3..4,
                forward = true,
                blockHeightsPx = List(8) { 100 },
                prefetchDistancePx = 200,
            ),
        )
    }

    @Test
    fun idleBackgroundPrelayoutEventuallyCoversTheWholeDocumentInReadingDirection() {
        val blocks = List(8) { index ->
            MarkdownParagraph(MarkdownText("paragraph $index"), metadata(index))
        }

        assertEquals(
            listOf(5, 6, 7, 2, 1, 0),
            backgroundPrelayoutPriorityIndices(
                blocks = blocks,
                visibleRange = 3..4,
                forward = true,
            ),
        )
        assertEquals(
            listOf(2, 1, 0, 5, 6, 7),
            backgroundPrelayoutPriorityIndices(
                blocks = blocks,
                visibleRange = 3..4,
                forward = false,
            ),
        )
    }

    @Test
    fun scalesEveryBlockRhythmValueWithoutChangingTypography() {
        val source = MarkdownStyle()
        val scaled = source.withBlockSpacingScale(3f / 2f)

        assertEquals(source.body, scaled.body)
        assertEquals(source.blockSpacing * 1.5f, scaled.blockSpacing)
        assertEquals(source.displayBlockSpacingBodyLines * 1.5f, scaled.displayBlockSpacingBodyLines)
        assertEquals(source.listItemSpacing * 1.5f, scaled.listItemSpacing)
        assertEquals(
            source.headingSpacing.level3.beforeBodyLines * 1.5f,
            scaled.headingSpacing.level3.beforeBodyLines,
        )
        assertEquals(source.headingSpacing.betweenBodyLines * 1.5f, scaled.headingSpacing.betweenBodyLines)
    }

    @Test
    fun zeroScaleRemovesBlockGaps() {
        val scaled = MarkdownStyle().withBlockSpacingScale(0f)

        assertEquals(0.dp, scaled.blockSpacing)
        assertEquals(0f, scaled.blockSpacingBodyLines)
        assertEquals(0f, scaled.headingSpacing.betweenBodyLines)
    }

    @Test
    fun readingScaleDerivesLineHeightFromScaledFontSize() {
        val scaled = MarkdownStyle().body.withMarkdownReadingScale(
            fontSizeScale = 5f / 4f,
            lineHeightFromFontSize = 8f / 5f,
        )

        assertEquals(20.sp, scaled.fontSize)
        assertEquals(32.sp, scaled.lineHeight)
    }

    @Test
    fun readingScaleRejectsRelativeBodySizeBeforeHeadingResolution() {
        assertFailsWith<IllegalArgumentException> {
            MarkdownStyle().body.copy(fontSize = 1.em).withMarkdownReadingScale(
                fontSizeScale = 1f,
                lineHeightFromFontSize = 8f / 5f,
            )
        }
    }

    @Test
    fun quoteColorOverrideKeepsAbsoluteBodyBasisForNestedFootnotes() {
        val source = MarkdownStyle().copy(
            body = MarkdownStyle().body.withMarkdownReadingScale(5f / 4f, 8f / 5f),
            quoteText = MarkdownStyle().quoteText.copy(color = Color.Gray),
        )

        val quote = source.quoteContentStyle()

        assertEquals(20.sp, quote.body.fontSize)
        assertEquals(17.5.sp, quote.footnoteContentTextStyle().fontSize)
    }

    @Test
    fun relativeQuoteTypographyIsResolvedBeforeNestedBlockStyles() {
        val quote = MarkdownStyle().copy(
            quoteText = MarkdownStyle().quoteText.copy(
                fontSize = 0.875.em,
                lineHeight = 1.5.em,
            ),
        ).quoteContentStyle()

        assertEquals(14.sp, quote.body.fontSize)
        assertEquals(21.sp, quote.body.lineHeight)
        assertEquals(12.25.sp, quote.footnoteContentTextStyle().fontSize)
    }

    private fun metadata(key: Int) = MarkdownNodeMetadata(
        key = MarkdownNodeKey(key, emptyList()),
        sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
    )
}
