package org.tiqian.markdown.compose

import org.tiqian.markdown.*

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.compose.CjkText
import org.tiqian.compose.CjkInlineBackgroundDrawStyle
import org.tiqian.compose.CjkInlineBackgroundMetricPolicy
import org.tiqian.compose.CjkSelectionState
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.createPlatformParagraphMeasurer
import org.tiqian.compose.measureWithInlineContent
import org.tiqian.compose.rememberCjkSelectionState
import org.tiqian.core.Ic
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.Rect
import org.tiqian.core.TextRange
import org.tiqian.core.getBoundingBoxes
import org.tiqian.core.positionedClusters
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.layout.ParagraphLayoutEngine
import org.tiqian.shaping.skia.SkiaFontMetricsResolver
import org.tiqian.shaping.skia.SkiaTextShaper
import kotlin.math.ceil
import kotlin.math.floor
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

    @Test
    fun composeFallbackUsesNativeUnderlineAndKeepsInteraction() {
        var clicks = 0
        var layoutResult: TextLayoutResult? = null
        var capturedFallbackText: androidx.compose.ui.text.AnnotatedString? = null

        ImageComposeScene(width = 240, height = 72) {
            val resolved = resolveSegmentHighlight(onClick = { clicks++ })
            val fallbackText = resolved.composeFallbackAnnotatedString()
            capturedFallbackText = fallbackText
            Box(Modifier.fillMaxSize().background(Color.White)) {
                BasicText(
                    text = fallbackText,
                    modifier = Modifier.markdownInlineInteractionSemantics(resolved.interactions),
                    style = TextStyle(color = Color.Black, fontSize = 24.sp, lineHeight = 36.sp),
                    onTextLayout = { layoutResult = it },
                )
            }
        }.use { scene ->
            scene.render(0L)
            val layout = assertNotNull(layoutResult)
            val box = layout.getBoundingBox(1)

            assertTrue(
                assertNotNull(capturedFallbackText).spanStyles.any {
                    it.start == 1 && it.end == 3 && it.item.textDecoration == TextDecoration.Underline
                },
                "Compose fallback must use its native underline metric",
            )
            scene.tap(box.center)
            assertEquals(1, clicks)
            scene.tap(Offset(220f, box.center.y))
            assertEquals(1, clicks, "empty trailing space must not activate the final highlighted range")
        }
    }

    @Test
    fun customEndMarkerPaintsPainterAtMappedEndpoint() {
        val fixture = EndMarkerFixture()
        val text = MarkdownText("a[image]b", listOf(
            MarkdownTextSpan(MarkdownTextRange(1, 8), MarkdownTextMark.InlineImage("inline:test", "image")),
            MarkdownTextSpan(MarkdownTextRange(8, 9), MarkdownTextMark.Custom("note")),
            MarkdownTextSpan(MarkdownTextRange(0, 0), MarkdownTextMark.Custom("note")),
        ))
        val slots = MarkdownInlineSlots(
            image = { _, _, _ ->
                MarkdownInlineContent(
                    alternateText = "@",
                    placeholder = Placeholder(24.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                    metrics = MarkdownInlineMetrics(24f, 24f, 24f),
                ) {}
            },
            custom = { _, _, _ -> MarkdownCustomInlinePresentation(endMarker = endMarker) },
        )
        ImageComposeScene(width = 240, height = 96, density = Density(1f)) {
            fixture.Content(text, slots)
        }.use { scene ->
            val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
            val layout = fixture.layouts.last()
            assertEquals("a@b￼", layout.input.content.text)
            assertEquals("a[image]b", text.value, "the source text must remain unchanged")
            val box = layout.inlineObjectBox(TextRange(3, 4))
            assertSingleEndMarker(pixels.markerPixels(), box)
            val x = ceil(box.left + 3f).toInt()
            val y = ceil(box.top).toInt()
            assertEquals(Color.Red, pixels[x + 1, y + 1], "the host Painter must receive the tint")
            assertEquals(Color.White, pixels[x + 6, y + 6], "the Painter's L-shaped path must be retained")
        }
    }

    @Test
    fun customEndMarkerSourceMappingComposesWithInlineReplacementAndBoundaryBias() {
        for (withImage in listOf(false, true)) {
            val fixture = EndMarkerFixture()
            val enabled = mutableStateOf(false)
            val source = if (withImage) "a[image]b" else "ab"
            val text = MarkdownText(source, buildList {
                add(MarkdownTextSpan(MarkdownTextRange(0, 1), MarkdownTextMark.Custom("note")))
                if (withImage) {
                    add(MarkdownTextSpan(MarkdownTextRange(1, 8), MarkdownTextMark.InlineImage("inline:test", "image")))
                    add(MarkdownTextSpan(MarkdownTextRange(1, 8), MarkdownTextMark.Custom("note")))
                }
            })
            val slots = MarkdownInlineSlots(
                image = { _, _, _ ->
                    MarkdownInlineContent(
                        alternateText = "@",
                        placeholder = Placeholder(24.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                        metrics = MarkdownInlineMetrics(24f, 24f, 24f),
                    ) {}
                },
                custom = { _, _, _ -> MarkdownCustomInlinePresentation(endMarker = endMarker.takeIf { enabled.value }) },
            )
            var resolved: ResolvedMarkdownText? = null
            ImageComposeScene(width = 240, height = 96, density = Density(1f)) {
                resolved = resolveMarkdownText(
                    text, endMarkerStyle, endMarkerStyle.body, slots,
                    onLinkClick = null, onFootnoteClick = null,
                )
                fixture.Content(text, slots)
            }.use { scene ->
                for ((frame, markerEnabled) in listOf(false, true, false).withIndex()) {
                    enabled.value = markerEnabled
                    scene.render(frame * 16_000_000L)
                    val model = assertNotNull(resolved)
                    val layout = fixture.layouts.last()
                    val expected = if (withImage) {
                        if (markerEnabled) "a\uFFFC@\uFFFCb" else "a@b"
                    } else {
                        if (markerEnabled) "a\uFFFCb" else "ab"
                    }
                    assertEquals(expected, layout.input.content.text, "LayoutResult uses internal projection offsets")
                    val sourceStart = source.lastIndexOf('b')
                    val mappedStart = model.sourceToRenderedOffset(sourceStart, false)
                    val mappedEnd = model.sourceToRenderedOffset(sourceStart + 1, true)
                    val expectedStart = expected.lastIndexOf('b')
                    assertEquals(TextRange(expectedStart, expectedStart + 1), TextRange(mappedStart, mappedEnd))
                    assertEquals("b", model.annotated.text.substring(mappedStart, mappedEnd))
                    assertEquals(layout.getBoundingBoxes(expectedStart, expectedStart + 1), layout.getBoundingBoxes(mappedStart, mappedEnd))
                    assertEquals(1, model.sourceToRenderedOffset(1, true), "a range ending at a marker must exclude the slot")
                    assertEquals(if (markerEnabled) 2 else 1, model.sourceToRenderedOffset(1, false), "a range starting at a marker must skip the slot")
                    if (withImage) {
                        assertEquals("@", model.annotated.text.substring(
                            model.sourceToRenderedOffset(1, false), model.sourceToRenderedOffset(8, true),
                        ), "image replacement and both marker boundaries must share one source mapping")
                    }
                    assertEquals(source, text.value, "the searchable source must never contain internal slots")
                }
            }
        }
    }

    @Test
    fun customEndMarkerStyleToggleReflowsWithStableLogicalText() {
        val fixture = EndMarkerFixture()
        val style = mutableStateOf(endMarkerStyle)
        val text = MarkdownText("toggle", listOf(
            MarkdownTextSpan(MarkdownTextRange(0, 6), MarkdownTextMark.Custom("note")),
        ))
        val slots = MarkdownInlineSlots(custom = { _, currentStyle, _ ->
            MarkdownCustomInlinePresentation(
                style = SpanStyle(color = currentStyle.body.color),
                onClick = {},
                endMarker = endMarker.takeIf { currentStyle.body.color == Color.Blue },
            )
        })
        ImageComposeScene(width = 240, height = 96, density = Density(1f)) {
            fixture.Content(text, slots, style = style.value)
        }.use { scene ->
            val before = scene.render(0L).toComposeImageBitmap().toPixelMap()
            assertEquals(0, before.redPixelCount())
            val layout = fixture.layouts.last()
            val layoutCount = fixture.layouts.size
            style.value = endMarkerStyle.copy(body = endMarkerStyle.body.copy(color = Color.Blue))
            val enabled = scene.render(16_000_000L).toComposeImageBitmap().toPixelMap()
            assertSingleEndMarker(enabled.markerPixels(), fixture.layouts.last().inlineObjectBox(TextRange(6, 7)))
            assertTrue((0 until enabled.height).any { y -> (0 until enabled.width).any { x ->
                val color = enabled[x, y]
                color.blue > color.red + 0.1f && color.blue > color.green + 0.1f
            } }, "the changed text style must also be painted")
            assertTrue(fixture.layouts.size > layoutCount, "adding a measured marker must invalidate layout")
            assertEquals("toggle￼", fixture.layouts.last().input.content.text)
            style.value = endMarkerStyle
            val disabled = scene.render(32_000_000L).toComposeImageBitmap().toPixelMap()
            assertEquals(0, disabled.redPixelCount(), "the marker must disappear in the same style update")
            assertEquals("toggle", fixture.layouts.last().input.content.text)
            assertTrue((0 until before.height).all { y ->
                (0 until before.width).all { x -> before[x, y] == disabled[x, y] }
            }, "returning to the initial style must restore the initial pixels")
        }
    }

    @Test
    fun customEndMarkerAcrossLinesPaintsOnlyAtFinalEndpoint() {
        val fixture = EndMarkerFixture()
        val value = "one two three four five six seven"
        val text = MarkdownText(value, listOf(
            MarkdownTextSpan(MarkdownTextRange(0, value.length), MarkdownTextMark.Custom("note")),
        ))
        val slots = MarkdownInlineSlots(custom = { _, _, _ ->
            MarkdownCustomInlinePresentation(endMarker = endMarker)
        })
        ImageComposeScene(width = 240, height = 240, density = Density(1f)) {
            fixture.Content(text, slots, width = 120.dp)
        }.use { scene ->
            val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
            val layout = fixture.layouts.last()
            assertTrue(layout.lines.size > 1, "the fixture must really wrap")
            val box = layout.inlineObjectBox(TextRange(value.length, value.length + 1))
            assertSingleEndMarker(pixels.markerPixels(), box)
        }
    }

    @Test
    fun customEndMarkerKeepsAdjacentCustomRangesIndependent() {
        val fixture = EndMarkerFixture()
        val mark = MarkdownTextMark.Custom("note")
        val text = MarkdownText("MMMM", listOf(
            MarkdownTextSpan(MarkdownTextRange(0, 2), mark),
            MarkdownTextSpan(MarkdownTextRange(2, 4), mark),
        ))
        val slots = MarkdownInlineSlots(custom = { _, _, _ ->
            MarkdownCustomInlinePresentation(endMarker = endMarker)
        })
        ImageComposeScene(width = 240, height = 96, density = Density(1f)) {
            fixture.Content(text, slots)
        }.use { scene ->
            val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
            val layout = fixture.layouts.last()
            val first = layout.inlineObjectBox(TextRange(2, 3))
            val second = layout.inlineObjectBox(TextRange(5, 6))
            val split = (first.right + second.right) / 2f
            val points = pixels.markerPixels()
            assertSingleEndMarker(points.filter { it.x < split }, first)
            assertSingleEndMarker(points.filter { it.x >= split }, second)
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun customEndMarkerDoesNotChangeSelectionOrClipboardText() {
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
            override fun getText(): AnnotatedString? = copied
        }
        var state: CjkSelectionState? = null
        val text = MarkdownText("copy source", listOf(
            MarkdownTextSpan(MarkdownTextRange(0, 4), MarkdownTextMark.Custom("note")),
        ))
        val slots = MarkdownInlineSlots(custom = { _, _, _ ->
            MarkdownCustomInlinePresentation(endMarker = endMarker)
        })
        ImageComposeScene(width = 240, height = 160, density = Density(1f)) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    TiqianMarkdownSurface(
                        document = MarkdownRenderDocument(listOf(MarkdownParagraph(text, metadata))),
                        style = endMarkerStyle,
                        inlineSlots = slots,
                        scrollState = rememberScrollState(),
                        selectionState = selectionState,
                        enableScroll = false,
                    )
                }
            }
        }.use { scene ->
            val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
            assertTrue(pixels.redPixelCount() > 0, "copy must be tested with a painted marker")
            val selectionState = assertNotNull(state)
            assertTrue(selectionState.selectAll())
            scene.render(16_000_000L)
            assertEquals(text.value, selectionState.selectedText?.text)
            assertTrue(selectionState.copySelection())
            assertEquals(text.value, copied?.text)
            val semantics = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }.single {
                it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == text.value &&
                    SemanticsActions.SetSelection in it.config
            }
            val setSelection = assertNotNull(semantics.config[SemanticsActions.SetSelection].action)
            assertTrue(setSelection(0, 4, true))
            assertEquals("copy", selectionState.selectedText?.text)
            assertTrue(selectionState.copySelection())
            assertEquals("copy", copied?.text)
        }
    }

    @Test
    fun customEndMarkerStaysInsideColumnWhenTrailingSpaceIsTooNarrow() {
        val fixture = EndMarkerFixture()
        val columnWidth = mutableStateOf(240.dp)
        val text = MarkdownText("edge", listOf(
            MarkdownTextSpan(MarkdownTextRange(0, 4), MarkdownTextMark.Custom("note")),
        ))
        val slots = MarkdownInlineSlots(custom = { _, _, _ ->
            MarkdownCustomInlinePresentation(endMarker = endMarker)
        })
        ImageComposeScene(width = 300, height = 120, density = Density(1f)) {
            fixture.Content(text, slots, width = columnWidth.value)
        }.use { scene ->
            scene.render(0L)
            val naturalEnd = fixture.layouts.last().getBoundingBoxes(3, 4).single().right
            // The real glyph width plus 10px leaves less than the required 3px gap + 8px marker.
            columnWidth.value = ceil(naturalEnd + 10f).toInt().dp
            val pixels = scene.render(16_000_000L).toComposeImageBitmap().toPixelMap()
            val layout = fixture.layouts.last()
            val bounds = assertNotNull(fixture.textBounds)
            val box = layout.getBoundingBoxes(3, 4).single()
            assertEquals(2, layout.lines.size, "the measured marker must wrap instead of overlapping the last word")
            assertEquals(0f, bounds.left, 0.01f)
            assertTrue(box.right + 3f + 8f > bounds.width, "the real trailing space must be insufficient")
            val points = pixels.markerPixels()
            assertTrue(points.size in 24..80, "the marker must remain visible at the edge")
            val markerBox = layout.inlineObjectBox(TextRange(4, 5))
            assertTrue(markerBox.top >= box.bottom, "the marker must own a box on the next line")
            assertSingleEndMarker(points, markerBox)
            assertTrue(points.all { it.x < ceil(bounds.right).toInt() }, "Painter pixels must remain inside the column")
        }
    }

    @Suppress("DEPRECATION")
    private class EndMarkerFixture {
        val layouts = mutableListOf<LayoutResult>()
        var textBounds: androidx.compose.ui.geometry.Rect? = null
        private val engine = ExplainableStubParagraphLayoutEngine(
            lineBreaker = LookaheadLineBreaker(),
            textShaper = SkiaTextShaper(),
            fontMetricsResolver = SkiaFontMetricsResolver(),
        )
        private val measurer = ParagraphMeasurer(object : ParagraphLayoutEngine {
            override fun layout(input: LayoutInput): LayoutResult = engine.layout(input).also(layouts::add)
        })

        @Composable
        fun Content(text: MarkdownText, slots: MarkdownInlineSlots, width: Dp = 240.dp, style: MarkdownStyle = endMarkerStyle) {
            CompositionLocalProvider(LocalMarkdownParagraphMeasurer provides measurer) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    MarkdownTextBlock(
                        text = text,
                        textStyle = style.body,
                        markdownStyle = style,
                        inlineSlots = slots,
                        onLinkClick = null,
                        onFootnoteClick = null,
                        modifier = Modifier.width(width).onGloballyPositioned { textBounds = it.boundsInRoot() },
                        paragraphStyle = ParagraphStyle(firstLineIndent = Ic.Zero, lineLengthGrid = LineLengthGrid(enabled = false)),
                    )
                }
            }
        }
    }

    private class EndMarkerPainter : Painter() {
        override val intrinsicSize = Size(8f, 8f)
        override fun DrawScope.onDraw() {
            // Deliberate 2px overdraw proves the production marker box clips arbitrary Painter paths.
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width + 2f, 0f)
                lineTo(size.width + 2f, size.height / 2f)
                lineTo(size.width / 2f, size.height / 2f)
                lineTo(size.width / 2f, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, Color.Black)
        }
    }

    private fun PixelMap.markerPixels(): List<IntOffset> = buildList {
        for (y in 0 until height) for (x in 0 until width) {
            val color = this@markerPixels[x, y]
            if (color.red > color.green + 0.1f && color.red > color.blue + 0.1f) add(IntOffset(x, y))
        }
    }

    /** The selection box spans the line; opaque children use the engine draw origin and their baseline metrics. */
    private fun LayoutResult.inlineObjectBox(range: TextRange): Rect {
        val positioned = positionedClusters().single { it.range == range }
        val metrics = input.inlineObjects.single { it.range == range }
        return Rect(
            positioned.drawX,
            positioned.baseline - metrics.ascent,
            positioned.drawX + metrics.advance,
            positioned.baseline + metrics.descent,
        )
    }

    private fun assertSingleEndMarker(points: List<IntOffset>, box: Rect) {
        val left = box.left + 3f
        val top = box.top
        assertTrue(points.size in 24..80, "the supplied Painter must produce one visible marker")
        assertTrue(points.all {
            it.x >= floor(left).toInt() && it.x < ceil(left + 8f).toInt() &&
                it.y >= floor(top).toInt() && it.y < ceil(top + 8f).toInt()
        }, "marker pixels must belong only to the measured inline object box: expected=($left,$top)-(${left + 8f},${top + 8f}), " +
            "pixels=(${points.minOf { it.x }},${points.minOf { it.y }})-(${points.maxOf { it.x } + 1},${points.maxOf { it.y } + 1}), box=$box")
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
        val endMarkerStyle = MarkdownStyle(
            body = TextStyle(color = Color.Black, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 40.sp),
            proseMeasure = MarkdownProseMeasure(enabled = false),
        )
        val endMarker = MarkdownInlineEndMarker(painter = EndMarkerPainter(), color = Color.Red)
        val metadata = MarkdownNodeMetadata(
            key = MarkdownNodeKey(0, listOf(0)),
            sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
        )
    }
}
