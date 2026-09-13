package org.tiqian.markdown.compose

import org.tiqian.markdown.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MarkdownListAlignmentTest {
    @Test
    fun orderedMarkerSharesFirstLineBaselineWithTiqianBody() {
        render(document(start = 1, itemTexts = listOf("1.")), width = 600).let { pixels ->
            val markerInk = pixels.inkRows(0 until 24)
            val bodyInk = pixels.inkRows(24 until 100)

            assertTrue(markerInk.isNotEmpty(), "list marker did not render")
            assertTrue(bodyInk.isNotEmpty(), "list body did not render")
            assertEquals(markerInk, bodyInk)
        }
    }

    @Test
    fun contentIndentUsesTwoCellsOnlyAboveTiqiansLongMeasureBoundary() {
        val document = document(start = 1, itemTexts = listOf("1."))
        val atBoundary = render(document, width = 768)
        val long = render(document, width = 792)

        val boundaryMarkerStart = atBoundary.inkMinX(0 until 48)
        val longMarkerStart = long.inkMinX(0 until 48)
        val boundaryBodyStart = atBoundary.inkMinX(24 until 120)
        val longBodyStart = long.inkMinX(48 until 120)

        assertEquals(24, longMarkerStart - boundaryMarkerStart)
        assertEquals(24, longBodyStart - boundaryBodyStart)
    }

    @Test
    fun shorterMarkerAlignsAgainstBodyInsideTheWidestWholeCellGutter() {
        val pixels = render(document(start = 9, itemTexts = listOf("正文", "正文")), width = 600)
        val firstMarkerStart = pixels.inkMinX(0 until 48, yRange = 0 until 36)
        val secondMarkerStart = pixels.inkMinX(0 until 48, yRange = 42 until 78)
        val firstMarkerEnd = pixels.inkMaxX(0 until 48, yRange = 0 until 36)
        val secondMarkerEnd = pixels.inkMaxX(0 until 48, yRange = 42 until 78)
        val firstBodyStart = pixels.inkMinX(48 until 120, yRange = 0 until 36)
        val secondBodyStart = pixels.inkMinX(48 until 120, yRange = 42 until 78)

        assertTrue(firstMarkerStart > secondMarkerStart, "the shorter marker was not aligned against the body edge")
        assertEquals(secondMarkerEnd, firstMarkerEnd)
        assertEquals(secondBodyStart, firstBodyStart)
    }

    @Test
    fun wrappedListBodyLinesKeepTheSameContentStart() {
        val pixels = render(
            document(
                start = 1,
                itemTexts = listOf("这是一段足够长的列表正文，用来确认自动换行之后仍然从正文栏起点继续排列。"),
            ),
            width = 300,
        )

        // 300 px minus the measured marker leaves a half-cell remainder;
        // the marker region absorbs it, so the content column starts at 36 px.
        // Exclude the right-aligned ordinal ink immediately before that edge.
        val firstLineStart = pixels.inkMinX(36 until 120, yRange = 0 until 36)
        val secondLineStart = pixels.inkMinX(36 until 120, yRange = 36 until 72)

        assertEquals(firstLineStart, secondLineStart)
    }

    @Test
    fun taskMarkersUseOneCellAndPaintOnlyTheCheckedMark() {
        val pixels = render(taskDocument(), width = 300)
        val checkedBodyStart = pixels.inkMinX(24 until 120, yRange = 0 until 36)
        val uncheckedBodyStart = pixels.inkMinX(24 until 120, yRange = 42 until 78)
        val checkedMarkerInk = pixels.inkCount(0 until 24, yRange = 0 until 36)
        val uncheckedMarkerInk = pixels.inkCount(0 until 24, yRange = 42 until 78)

        assertEquals(uncheckedBodyStart, checkedBodyStart)
        assertTrue(checkedMarkerInk > uncheckedMarkerInk, "checked task marker did not paint a check")
    }

    @Test
    fun unorderedBulletAndTaskMarkerMoveIntoTheCellImmediatelyBeforeTheBody() {
        listOf(unorderedDocument(), taskDocument()).forEach { document ->
            val atBoundary = render(document, width = 768)
            val long = render(document, width = 792)
            val boundaryMarkerStart = atBoundary.inkMinX(0 until 48, yRange = 0 until 36)
            val longMarkerStart = long.inkMinX(0 until 48, yRange = 0 until 36)
            val boundaryBodyStart = atBoundary.inkMinX(24 until 120, yRange = 0 until 36)
            val longBodyStart = long.inkMinX(48 until 120, yRange = 0 until 36)

            assertEquals(24, longMarkerStart - boundaryMarkerStart)
            assertEquals(24, longBodyStart - boundaryBodyStart)
        }
    }

    @Test
    fun hostCanLeftAlignOrdinalMarkersWithoutReplacingListLayout() {
        val pixels = render(document(start = 9, itemTexts = listOf("正文", "正文")), 600,
            style.copy(listMarkerAlignment = org.tiqian.core.LastLineAlignment.Start))
        val firstStart = pixels.inkMinX(0 until 48, yRange = 0 until 36)
        val secondStart = pixels.inkMinX(0 until 48, yRange = 42 until 78)
        assertTrue(firstStart < 4 && secondStart < 4)
        assertTrue(pixels.inkMaxX(0 until 48, yRange = 0 until 36) < pixels.inkMaxX(0 until 48, yRange = 42 until 78))
    }

    private fun render(document: MarkdownRenderDocument, width: Int, renderStyle: MarkdownStyle = style): PixelMap =
        ImageComposeScene(width = width, height = 120) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMarkdown(document = document, style = renderStyle)
            }
        }.use { scene ->
            scene.render(0L).toComposeImageBitmap().toPixelMap()
        }

    private fun document(start: Int, itemTexts: List<String>) = MarkdownRenderDocument(
        blocks = listOf(
            MarkdownList(
                ordered = true,
                startNumber = start,
                tight = true,
                items = itemTexts.mapIndexed { index, text ->
                    MarkdownListItem(
                        blocks = listOf(
                            MarkdownParagraph(
                                text = MarkdownText(text),
                                metadata = metadata(2 + index, listOf(0, index, 0)),
                            ),
                        ),
                        metadata = metadata(1 + index, listOf(0, index)),
                    )
                },
                metadata = metadata(0, listOf(0)),
            ),
        ),
    )

    private fun taskDocument() = MarkdownRenderDocument(
        blocks = listOf(
            MarkdownList(
                ordered = false,
                startNumber = 1,
                tight = true,
                items = listOf(MarkdownTaskState.Checked, MarkdownTaskState.Unchecked).mapIndexed { index, task ->
                    MarkdownListItem(
                        blocks = listOf(
                            MarkdownParagraph(
                                text = MarkdownText("正文"),
                                metadata = metadata(2 + index, listOf(0, index, 0)),
                            ),
                        ),
                        task = task,
                        metadata = metadata(1 + index, listOf(0, index)),
                    )
                },
                metadata = metadata(0, listOf(0)),
            ),
        ),
    )

    private fun unorderedDocument() = MarkdownRenderDocument(
        blocks = listOf(
            MarkdownList(
                ordered = false,
                startNumber = 1,
                tight = true,
                items = listOf(
                    MarkdownListItem(
                        blocks = listOf(
                            MarkdownParagraph(
                                text = MarkdownText("正文"),
                                metadata = metadata(2, listOf(0, 0, 0)),
                            ),
                        ),
                        metadata = metadata(1, listOf(0, 0)),
                    ),
                ),
                metadata = metadata(0, listOf(0)),
            ),
        ),
    )

    private fun metadata(stableKey: Int, path: List<Int>) = MarkdownNodeMetadata(
        key = MarkdownNodeKey(stableKey, path),
        sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
    )

    private fun PixelMap.inkRows(xRange: IntRange): Set<Int> = buildSet {
        for (x in xRange) {
            for (y in 0 until height) {
                if (this@inkRows[x, y] != Color.White) add(y)
            }
        }
    }

    private fun PixelMap.inkMinX(
        xRange: IntRange,
        yRange: IntRange = 0 until height,
    ): Int = xRange.first { x -> yRange.any { y -> this[x, y] != Color.White } }

    private fun PixelMap.inkMaxX(
        xRange: IntRange,
        yRange: IntRange = 0 until height,
    ): Int = xRange.last { x -> yRange.any { y -> this[x, y] != Color.White } }

    private fun PixelMap.inkCount(xRange: IntRange, yRange: IntRange): Int =
        xRange.sumOf { x -> yRange.count { y -> this[x, y] != Color.White } }

    private companion object {
        val style = MarkdownStyle(
            body = TextStyle(color = Color.Black, fontSize = 24.sp, lineHeight = 36.sp),
            blockSpacing = 0.dp,
            proseMeasure = MarkdownProseMeasure(enabled = false),
        )
    }
}
