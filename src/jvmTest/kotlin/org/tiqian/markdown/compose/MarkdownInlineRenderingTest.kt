package org.tiqian.markdown.compose

import org.tiqian.markdown.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.compose.CjkText
import org.tiqian.compose.CjkInlineBackgroundDrawStyle
import org.tiqian.compose.CjkInlineBackgroundMetricPolicy
import org.tiqian.compose.createPlatformParagraphMeasurer
import org.tiqian.compose.measureWithInlineContent
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.TextRange
import org.tiqian.core.getBoundingBoxes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MarkdownInlineRenderingTest {
    @Test
    fun unchangedProseReusesItsResolvedInlineModelAcrossRecomposition() {
        val recompositionTrigger = mutableStateOf(0)
        val resolvedModels = mutableListOf<ResolvedMarkdownText>()
        val linkClick: (String) -> Unit = {}
        ImageComposeScene(width = 240, height = 72) {
            recompositionTrigger.value
            resolvedModels += resolveMarkdownText(
                text = MarkdownText(
                    value = "中文链接与强调",
                    spans = listOf(
                        MarkdownTextSpan(MarkdownTextRange(0, 2), MarkdownTextMark.Strong),
                        MarkdownTextSpan(
                            MarkdownTextRange(2, 4),
                            MarkdownTextMark.Link("https://example.com"),
                        ),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots,
                onLinkClick = linkClick,
                onFootnoteClick = null,
            )
        }.use { scene ->
            scene.render(0L)
            val first = resolvedModels.last()
            recompositionTrigger.value++
            scene.render(16_000_000L)
            assertSame(first, resolvedModels.last())
        }
    }

    @Test
    fun inlineImageSlotIsPlacedInsideItsParagraph() {
        val pixels = ImageComposeScene(width = 240, height = 72) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMarkdown(
                    document = MarkdownRenderDocument(
                        blocks = listOf(
                            MarkdownParagraph(
                                text = MarkdownText(
                                    value = "前图后",
                                    spans = listOf(
                                        MarkdownTextSpan(
                                            MarkdownTextRange(1, 2),
                                            MarkdownTextMark.InlineImage(
                                                destination = "inline:test",
                                                description = "图",
                                            ),
                                        ),
                                    ),
                                ),
                                metadata = metadata,
                            ),
                        ),
                    ),
                    style = MarkdownStyle(
                        body = TextStyle(color = Color.Black, fontSize = 24.sp, lineHeight = 36.sp),
                        blockSpacing = 0.dp,
                    ),
                    inlineSlots = MarkdownInlineSlots(
                        image = { _, _, _ ->
                            MarkdownInlineContent(
                                alternateText = "图",
                                placeholder = Placeholder(
                                    width = 24.sp,
                                    height = 24.sp,
                                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                                ),
                                metrics = MarkdownInlineMetrics(
                                    widthPx = 24f,
                                    heightPx = 24f,
                                    baselineFromTopPx = 24f,
                                ),
                            ) {
                                Box(Modifier.fillMaxSize().background(Color.Red))
                            }
                        },
                    ),
                )
            }
        }.use { scene ->
            scene.render(0L).toComposeImageBitmap().toPixelMap()
        }

        val redPixels = (0 until pixels.height).sumOf { y ->
            (0 until pixels.width).count { x -> pixels[x, y] == Color.Red }
        }
        assertTrue(redPixels >= 400, "inline image slot rendered only $redPixels red pixels")
    }

    @Test
    fun customDecorationAndInteractionStayOnTiqianText() {
        var resolved: ResolvedMarkdownText? = null
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "前划线后",
                    spans = listOf(
                        MarkdownTextSpan(
                            MarkdownTextRange(1, 3),
                            MarkdownTextMark.Custom("segment-highlight"),
                        ),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = MarkdownInlineSlots(
                    custom = { _, _, _ ->
                        MarkdownCustomInlinePresentation(
                            decoration = MarkdownInlineDecoration.DashedUnderline(Color.Red),
                            onClick = {},
                        )
                    },
                ),
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        assertEquals(1, lowered.decorations.size)
        assertEquals(1, lowered.interactions.size)
        assertTrue(lowered.annotated.getLinkAnnotations(1, 3).single().item is LinkAnnotation.Clickable)
    }

    @Test
    fun abbreviationUsesDottedTiqianDecorationWithoutDeadAnnotation() {
        var resolved: ResolvedMarkdownText? = null
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "CLREQ",
                    spans = listOf(
                        MarkdownTextSpan(
                            MarkdownTextRange(0, 5),
                            MarkdownTextMark.Abbreviation("Requirements for Chinese Text Layout"),
                        ),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots,
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        assertTrue(lowered.annotated.getStringAnnotations(0, lowered.annotated.length).isEmpty())
        assertTrue(lowered.decorations.single().decoration is MarkdownInlineDecoration.DottedUnderline)
        assertTrue(
            lowered.decorations.toCjkInlineDecorations().single().style is
                org.tiqian.compose.CjkInlineDecorationStyle.DottedUnderline,
        )
    }

    @Test
    fun highlightUsesOneRoundedUniformTiqianBackground() {
        var resolved: ResolvedMarkdownText? = null
        val style = MarkdownStyle(
            highlightVerticalPadding = 1.5.dp,
            highlightCornerRadius = 3.dp,
        )
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "中文 A B",
                    spans = listOf(
                        MarkdownTextSpan(MarkdownTextRange(0, 6), MarkdownTextMark.Highlight),
                    ),
                ),
                style = style,
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots,
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        assertTrue(lowered.annotated.spanStyles.none { it.item.background != Color.Unspecified })
        val background = lowered.backgrounds.single()
        assertEquals(Color(0xFFFFE58F), background.color)
        assertEquals(0.dp, background.horizontalPadding)
        assertEquals(1.5.dp, background.verticalPadding)
        assertEquals(3.dp, background.cornerRadius)
        assertEquals(3.dp, background.continuationCornerRadius)
        assertEquals(1.dp, background.adjacentSameStyleClearance)
        assertEquals(CjkInlineBackgroundMetricPolicy.SpanTextStyle, background.metricPolicy)
        assertEquals(
            androidx.compose.ui.text.TextRange(0, 6),
            lowered.backgrounds.toCjkInlineBackgrounds().single().range,
        )
    }

    @Test
    fun inlineCodeUsesCompactMonospaceAndAReservedRoundedBox() {
        var resolved: ResolvedMarkdownText? = null
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "中code中",
                    spans = listOf(
                        MarkdownTextSpan(MarkdownTextRange(1, 5), MarkdownTextMark.InlineCode),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots,
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        val codeStyle = lowered.annotated.spanStyles.single { it.start == 1 && it.end == 5 }.item
        assertEquals(0.875.em, codeStyle.fontSize)
        assertEquals(Color.Unspecified, codeStyle.background)
        val background = lowered.backgrounds.single()
        assertEquals(Color(0xFFF1F3F5), background.color)
        assertEquals(4.dp, background.horizontalPadding)
        assertEquals(3.dp, background.verticalPadding)
        assertEquals(3.dp, background.cornerRadius)
        assertEquals(1.dp, background.continuationCornerRadius)
        assertEquals(1.dp, background.adjacentSameStyleClearance)
        assertEquals(CjkInlineBackgroundMetricPolicy.ParagraphTextStyle, background.metricPolicy)
        assertEquals(
            1.dp,
            lowered.backgrounds.toCjkInlineBackgrounds().single().continuationCornerRadius,
        )
        val layout = createPlatformParagraphMeasurer().measureWithInlineContent(
            text = lowered.annotated,
            constraints = LayoutConstraints(maxWidth = 240f),
            density = Density(1f),
            style = TextStyle(fontSize = 24.sp),
            inlineObjects = emptyList(),
            inlineBackgrounds = lowered.backgrounds.toCjkInlineBackgrounds(),
        )
        assertTrue(
            layout.debug.breakOpportunityDecisions.any { decision ->
                decision.range == TextRange(1, 5) &&
                    decision.reason == "ProgressiveTechnicalWholeTokenWrap"
            },
        )
        val inlineBox = layout.debug.inlineBoxDecisions.single()
        assertEquals(TextRange(1, 5), inlineBox.range)
        assertEquals(4f, inlineBox.inlineStart, 0.01f)
        assertEquals(4f, inlineBox.inlineEnd, 0.01f)
    }

    @Test
    fun keyboardInputReusesInlineCodeTypographyAndBoxGeometryAsABorder() {
        var resolved: ResolvedMarkdownText? = null
        val style = MarkdownStyle(
            inlineCode = SpanStyle(
                background = Color(0xFFF1F3F5),
                fontFamily = FontFamily.Monospace,
                fontSize = 0.92.em,
                fontWeight = FontWeight.Bold,
            ),
        )
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "中Ctrl中",
                    spans = listOf(
                        MarkdownTextSpan(MarkdownTextRange(1, 5), MarkdownTextMark.KeyboardInput),
                    ),
                ),
                style = style,
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = DefaultMarkdownInlineSlots,
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        val keyboardStyle = lowered.annotated.spanStyles.single { it.start == 1 && it.end == 5 }.item
        assertEquals(style.inlineCode.fontFamily, keyboardStyle.fontFamily)
        assertEquals(style.inlineCode.fontSize, keyboardStyle.fontSize)
        assertEquals(style.inlineCode.fontWeight, keyboardStyle.fontWeight)
        assertEquals(Color.Unspecified, keyboardStyle.background)

        val box = lowered.backgrounds.single()
        assertEquals(style.keyboardInputHorizontalPadding, box.horizontalPadding)
        assertEquals(style.keyboardInputVerticalPadding, box.verticalPadding)
        assertEquals(style.keyboardInputCornerRadius, box.cornerRadius)
        assertEquals(style.keyboardInputCornerRadius, box.continuationCornerRadius)
        assertEquals(
            CjkInlineBackgroundDrawStyle.Border(style.keyboardInputBorderWidth),
            box.drawStyle,
        )
        assertEquals(CjkInlineBackgroundMetricPolicy.ParagraphTextStyle, box.metricPolicy)
        val layout = createPlatformParagraphMeasurer().measureWithInlineContent(
            text = lowered.annotated,
            constraints = LayoutConstraints(maxWidth = 240f),
            density = Density(1f),
            style = TextStyle(fontSize = 24.sp),
            inlineObjects = emptyList(),
            inlineBackgrounds = lowered.backgrounds.toCjkInlineBackgrounds(),
        )
        assertTrue(
            layout.debug.breakOpportunityDecisions.any { decision ->
                decision.range == TextRange(1, 5) &&
                    decision.reason == "ProgressiveTechnicalWholeTokenWrap"
            },
        )
        assertEquals(1, layout.debug.inlineBoxDecisions.size)
    }

    @Test
    fun unsupportedInlineMathUsesTheCompleteInlineCodeFallback() {
        var resolved: ResolvedMarkdownText? = null
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "中x+y中",
                    spans = listOf(
                        MarkdownTextSpan(MarkdownTextRange(1, 4), MarkdownTextMark.InlineMath("x+y")),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = MarkdownInlineSlots(math = { _, _, _ -> null }),
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        assertEquals(Color.Unspecified, lowered.annotated.spanStyles.single().item.background)
        assertEquals(1, lowered.backgrounds.size)
        assertEquals(1.dp, lowered.backgrounds.single().continuationCornerRadius)
        val layout = createPlatformParagraphMeasurer().measureWithInlineContent(
            text = lowered.annotated,
            constraints = LayoutConstraints(maxWidth = 240f),
            density = Density(1f),
            style = TextStyle(fontSize = 24.sp),
            inlineObjects = emptyList(),
            inlineBackgrounds = lowered.backgrounds.toCjkInlineBackgrounds(),
        )
        assertTrue(
            layout.debug.breakOpportunityDecisions.any { decision ->
                decision.range == TextRange(1, 4) &&
                    decision.reason == "ProgressiveTechnicalWholeTokenWrap"
            },
        )
        assertEquals(1, layout.debug.inlineBoxDecisions.size)
    }

    @Test
    fun footnoteAndCustomClicksStayInteractiveWithoutBecomingTechnicalLinks() {
        var resolved: ResolvedMarkdownText? = null
        ImageComposeScene(width = 360, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "中[1]与mark及link中",
                    spans = listOf(
                        MarkdownTextSpan(MarkdownTextRange(1, 4), MarkdownTextMark.Footnote("1", 1)),
                        MarkdownTextSpan(MarkdownTextRange(5, 9), MarkdownTextMark.Custom("click")),
                        MarkdownTextSpan(
                            MarkdownTextRange(10, 14),
                            MarkdownTextMark.Link("https://example.com"),
                        ),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = MarkdownInlineSlots(
                    custom = { _, _, _ -> MarkdownCustomInlinePresentation(onClick = {}) },
                ),
                onLinkClick = {},
                onFootnoteClick = {},
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        assertEquals(3, lowered.annotated.getLinkAnnotations(0, lowered.annotated.length).size)
        val layout = createPlatformParagraphMeasurer().measureWithInlineContent(
            text = lowered.annotated,
            constraints = LayoutConstraints(maxWidth = 360f),
            density = Density(1f),
            style = TextStyle(fontSize = 24.sp),
            inlineObjects = emptyList(),
            inlineBackgrounds = lowered.backgrounds.toCjkInlineBackgrounds(),
        )
        val technicalRanges = layout.debug.breakOpportunityDecisions.map { it.range }.toSet()
        // LinkAddressDisplayGate: "link" is prose display text, not the address itself,
        // so it keeps normal prose breaking alongside footnote and custom clicks.
        assertTrue(TextRange(10, 14) !in technicalRanges)
        assertTrue(TextRange(1, 4) !in technicalRanges)
        assertTrue(TextRange(5, 9) !in technicalRanges)
    }

    @Test
    fun measuredObjectWithEmptyAlternateTextRetainsItsSourceRange() {
        var resolved: ResolvedMarkdownText? = null
        ImageComposeScene(width = 240, height = 72) {
            resolved = resolveMarkdownText(
                text = MarkdownText(
                    value = "图",
                    spans = listOf(
                        MarkdownTextSpan(
                            MarkdownTextRange(0, 1),
                            MarkdownTextMark.InlineImage("inline:test", ""),
                        ),
                    ),
                ),
                style = MarkdownStyle(),
                textStyle = TextStyle(fontSize = 24.sp),
                inlineSlots = MarkdownInlineSlots(
                    image = { _, _, _ ->
                        MarkdownInlineContent(
                            alternateText = "",
                            placeholder = Placeholder(24.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                            metrics = MarkdownInlineMetrics(24f, 24f, 24f),
                        ) { Box(Modifier.fillMaxSize()) }
                    },
                ),
                onLinkClick = null,
                onFootnoteClick = null,
            )
        }.use { scene -> scene.render(0L) }

        val lowered = assertNotNull(resolved)
        val inlineObject = lowered.tiqianInlineObjects.single()
        assertEquals(0, inlineObject.start)
        assertEquals(1, inlineObject.endExclusive)
        assertEquals("图", inlineObject.content.alternateText)
    }

    @Test
    fun customDecorationAndInteractionReplayFromTiqianGeometry() {
        var clicks = 0
        var layoutResult: LayoutResult? = null

        ImageComposeScene(width = 240, height = 72) {
            val resolved = resolveSegmentHighlight(onClick = { clicks++ })
            Box(Modifier.fillMaxSize().background(Color.White)) {
                CjkText(
                    text = resolved.annotated,
                    modifier = Modifier.markdownInlineInteractionSemantics(resolved.interactions),
                    style = TextStyle(color = Color.Black, fontSize = 24.sp, lineHeight = 36.sp),
                    inlineDecorations = resolved.decorations.toCjkInlineDecorations(),
                    onTextLayout = { layoutResult = it },
                )
            }
        }.use { scene ->
            val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
            val box = assertNotNull(layoutResult)
                .getBoundingBoxes(1, 3)
                .reduce { left, right ->
                    org.tiqian.core.Rect(
                        left = minOf(left.left, right.left),
                        top = minOf(left.top, right.top),
                        right = maxOf(left.right, right.right),
                        bottom = maxOf(left.bottom, right.bottom),
                    )
                }

            assertTrue(pixels.redPixelCount() > 0, "Tiqian path must paint the dashed underline")
            scene.tap(Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f))
            assertEquals(1, clicks)
            scene.tap(Offset(220f, (box.top + box.bottom) / 2f))
            assertEquals(1, clicks, "empty trailing space must not activate the final highlighted range")
        }
    }

    @Test
    fun partialCustomInteractionSurvivesSelectableArticleContainer() {
        var clicks = 0
        var layoutResult: LayoutResult? = null

        ImageComposeScene(width = 320, height = 96) {
            val resolved = resolveSegmentHighlight(onClick = { clicks++ })
            SelectionContainer {
                CjkText(
                    text = resolved.annotated,
                    modifier = Modifier.markdownInlineInteractionSemantics(resolved.interactions),
                    style = TextStyle(color = Color.Black, fontSize = 24.sp, lineHeight = 36.sp),
                    onTextLayout = { layoutResult = it },
                )
            }
        }.use { scene ->
            scene.render(0L)
            val highlightBox = assertNotNull(layoutResult)
                .getBoundingBoxes(1, 3)
                .reduce { left, right ->
                    org.tiqian.core.Rect(
                        left = minOf(left.left, right.left),
                        top = minOf(left.top, right.top),
                        right = maxOf(left.right, right.right),
                        bottom = maxOf(left.bottom, right.bottom),
                    )
                }
            scene.tap(Offset((highlightBox.left + highlightBox.right) / 2f, (highlightBox.top + highlightBox.bottom) / 2f))
            assertEquals(1, clicks)
        }
    }

    @Composable
    private fun resolveSegmentHighlight(onClick: () -> Unit): ResolvedMarkdownText =
        resolveMarkdownText(
            text = MarkdownText(
                value = "前划线后",
                spans = listOf(
                    MarkdownTextSpan(
                        MarkdownTextRange(1, 3),
                        MarkdownTextMark.Custom("segment-highlight"),
                    ),
                ),
            ),
            style = MarkdownStyle(),
            textStyle = TextStyle(fontSize = 24.sp),
            inlineSlots = MarkdownInlineSlots(
                custom = { _, _, _ ->
                    MarkdownCustomInlinePresentation(
                        decoration = MarkdownInlineDecoration.DashedUnderline(Color.Red),
                        onClick = { onClick() },
                    )
                },
            ),
            onLinkClick = null,
            onFootnoteClick = null,
        )

    private fun androidx.compose.ui.graphics.PixelMap.redPixelCount(): Int =
        (0 until height).sumOf { y ->
            (0 until width).count { x ->
                val color = this[x, y]
                color.red > color.green + 0.1f && color.red > color.blue + 0.1f
            }
        }

    private fun ImageComposeScene.tap(position: Offset) {
        sendPointerEvent(PointerEventType.Press, position)
        sendPointerEvent(PointerEventType.Release, position)
    }

    private companion object {
        val metadata = MarkdownNodeMetadata(
            key = MarkdownNodeKey(0, listOf(0)),
            sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
        )
    }
}
