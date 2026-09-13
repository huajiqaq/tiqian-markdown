package org.tiqian.markdown.compose

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.tiqian.compose.CjkText
import org.tiqian.core.LayoutResult
import org.tiqian.markdown.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@OptIn(ExperimentalComposeUiApi::class)
class MarkdownMathKernEmbeddingTest {
    @Test
    fun zeroAdvanceFormulaUsesSourcePresentationWithoutAnInvalidHostObject() {
        for (expression in listOf("\\!", "{}", "\\!\\!")) {
            ImageComposeScene(width = 300, height = 120) {
                kotlin.test.assertNull(DefaultMarkdownMathInlineSlot(
                    MarkdownTextMark.InlineMath(expression), MarkdownStyle(), TextStyle(fontSize = 24.sp)))
                val source = "前${expression}后"
                val resolved = resolveMarkdownText(
                    text = MarkdownText(source, listOf(MarkdownTextSpan(
                        MarkdownTextRange(1, expression.length + 1), MarkdownTextMark.InlineMath(expression)))),
                    style = MarkdownStyle(), textStyle = TextStyle(fontSize = 24.sp),
                    inlineSlots = DefaultMarkdownInlineSlots, onLinkClick = null, onFootnoteClick = null)
                assertEquals(source, resolved.annotated.text)
                assertTrue(resolved.toCjkInlineObjects(LocalDensity.current).isEmpty())
                CjkText(text = resolved.annotated, style = TextStyle(fontSize = 24.sp))
            }.use { it.render(0L) }
        }
    }

    @Test
    fun operatorBreakSurvivesKernAndItsAdvanceIsNotLost() {
        ImageComposeScene(width = 300, height = 160) {
            @androidx.compose.runtime.Composable
            fun content(source: String) = assertNotNull(DefaultMarkdownMathInlineSlot(
                MarkdownTextMark.InlineMath(source), MarkdownStyle(), TextStyle(fontSize = 24.sp)))
            val kerned = content("a=\\!b")
            val fragments = kerned.layoutFragments
            assertEquals(listOf("a", "=", "\\!b"), fragments.map { it.content.alternateText })
            assertTrue(!fragments[1].content.trailingBoundary.preventsLineBreak)
            val kernedWidth = fragments.sumOf { it.content.metrics!!.widthPx.toDouble() }
            val formula = org.tiqian.math.compose.rememberTiqianMathFormula("a=\\!b", fontSizePx = 24f)
            assertEquals(formula.layoutResult!!.box.width.toDouble(), kernedWidth, 0.001)
            fragments.forEach { it.content.content(it.content.alternateText) }
        }.use { it.render(0L) }
    }

    @Test
    fun paragraphCanStillBreakAfterOperatorBeforeSignedKern() {
        val source = "a=\\!b"
        var captured: LayoutResult? = null
        ImageComposeScene(width = 120, height = 160) {
            val resolved = resolveMarkdownText(
                text = MarkdownText(source, listOf(MarkdownTextSpan(
                    MarkdownTextRange(0, source.length), MarkdownTextMark.InlineMath(source)))),
                style = MarkdownStyle(), textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots, onLinkClick = null, onFootnoteClick = null)
            CjkText(text = resolved.annotated, modifier = Modifier.width(40.dp),
                style = TextStyle(fontSize = 24.sp),
                paragraphStyle = org.tiqian.core.ParagraphStyle(
                    firstLineIndent = org.tiqian.core.Ic.Zero,
                    lineLengthGrid = org.tiqian.core.LineLengthGrid(enabled = false)),
                inlineObjects = resolved.toCjkInlineObjects(LocalDensity.current),
                onTextLayout = { captured = it })
        }.use { it.render(0L) }
        assertEquals(listOf("a=", "\\!b"), assertNotNull(captured).lines.map {
            source.substring(it.range.start, it.range.end)
        })
    }

    @Test
    fun paragraphWrapsArticleFractionWithoutCarryingTheExplicitSpace() {
        val expression = "\\frac{I}{H}\\;\\approx\\;\\frac{1.8\\times10^{-3}}{7.7}\\;\\approx\\;2.3\\times10^{-4}."
        val source = "占比$expression"
        var captured: LayoutResult? = null
        ImageComposeScene(width = 180, height = 400) {
            val resolved = resolveMarkdownText(
                text = MarkdownText(source, listOf(MarkdownTextSpan(
                    MarkdownTextRange(2, source.length), MarkdownTextMark.InlineMath(expression),
                ))),
                style = MarkdownStyle(), textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots, onLinkClick = null, onFootnoteClick = null,
            )
            CjkText(
                text = resolved.annotated, modifier = Modifier.width(120.dp),
                style = TextStyle(fontSize = 24.sp, lineHeight = 36.sp),
                inlineObjects = resolved.toCjkInlineObjects(LocalDensity.current),
                onTextLayout = { captured = it },
            )
        }.use { it.render(0L) }
        val result = assertNotNull(captured)
        val lineSources = result.lines.map { source.substring(it.range.start, it.range.end) }
        assertTrue(lineSources.any { it.startsWith("\\frac{1.8") }, lineSources.toString())
        assertTrue(lineSources.none { it.startsWith("\\;") }, lineSources.toString())
        assertTrue(result.debug.lineEdgeTrimDecisions.any {
            it.reason == "InlineObjectLineEndDiscardableGlue" && it.trimAmount >= 24f * 5f / 18f
        }, result.debug.lineEdgeTrimDecisions.toString())
    }

    @Test
    fun articleRelationOwnsDiscardableSpaceInsteadOfExportingABlankObject() {
        ImageComposeScene(width = 400, height = 160) {
            val source = "\\frac{I}{H}\\;\\approx\\;\\frac{1.8\\times10^{-3}}{7.7}\\;\\approx\\;2.3\\times10^{-4}."
            val content = assertNotNull(DefaultMarkdownMathInlineSlot(
                MarkdownTextMark.InlineMath(source), MarkdownStyle(), TextStyle(fontSize = 24.sp),
            ))
            val fragments = content.layoutFragments
            val relationIndex = fragments.indexOfFirst { it.content.alternateText == "\\approx\\;" }
            assertTrue(relationIndex >= 0, "Explicit space must belong to the preceding operator")
            val relation = fragments[relationIndex].content
            assertTrue(relation.trailingBoundary.lineEndDiscardableAdvancePx >= 24f * 5f / 18f)
            assertTrue(!relation.trailingBoundary.preventsLineBreak)
            assertTrue(fragments[relationIndex + 1].content.alternateText.startsWith("\\frac"))
            assertEquals(source, fragments.joinToString("") { it.content.alternateText })
            fragments.forEach { it.content.content(it.content.alternateText) }
        }.use { it.render(0L) }
    }

    @Test
    fun bindingReplaysTheOriginalWidthIncludingNegativeKern() {
        ImageComposeScene(width = 400, height = 160) {
            @androidx.compose.runtime.Composable
            fun content(source: String) = assertNotNull(DefaultMarkdownMathInlineSlot(
                MarkdownTextMark.InlineMath(source), MarkdownStyle(), TextStyle(fontSize = 24.sp),
            ))
            val kerned = content("a\\!b")
            val plain = content("ab")
            assertTrue(kerned.metrics!!.widthPx < plain.metrics!!.widthPx)
            val bound = kerned.layoutFragments.single()
            assertEquals("a\\!b", bound.content.alternateText)
            assertEquals(kerned.metrics!!.widthPx, bound.content.metrics!!.widthPx, 0.001f)
            bound.content.content(bound.content.alternateText)
        }.use { it.render(0L) }
    }

    @Test
    fun signedKernsEmbedWithoutZeroWidthObjectsAndRetainSource() {
        for (expression in listOf(
            "\\log_2\\!\\Big(x\\Big)", "a\\!b", "\\!x", "x\\!", "a\\!\\!b",
            "H(P_{t+1}\\mid P_t)\\;=\\;\\tfrac12\\log_2\\!\\Big(\\frac{2\\pi e\\,\\sigma^2}{\\delta^2}\\Big)",
        )) {
            ImageComposeScene(width = 800, height = 200) {
                val source = "前${expression}后"
                val resolved = resolveMarkdownText(
                    text = MarkdownText(source, listOf(MarkdownTextSpan(
                        MarkdownTextRange(1, expression.length + 1), MarkdownTextMark.InlineMath(expression),
                    ))),
                    style = MarkdownStyle(), textStyle = TextStyle(fontSize = 24.sp),
                    inlineSlots = DefaultMarkdownInlineSlots, onLinkClick = null, onFootnoteClick = null,
                )
                assertEquals(source, resolved.annotated.text)
                val objects = resolved.toCjkInlineObjects(LocalDensity.current)
                assertTrue(objects.isNotEmpty(), expression)
                assertTrue(objects.all { it.advance.value > 0f }, expression)
                assertEquals(1, resolved.tiqianInlineObjects.first().start)
                assertEquals(expression.length + 1, resolved.tiqianInlineObjects.last().endExclusive)
                objects.forEach { it.content() }
            }.use { it.render(0L) }
        }
    }
}
