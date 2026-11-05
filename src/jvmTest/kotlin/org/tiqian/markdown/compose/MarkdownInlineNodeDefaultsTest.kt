@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package org.tiqian.markdown.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.core.Ic
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.getBoundingBoxes
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.layout.ParagraphLayoutEngine
import org.tiqian.markdown.*
import org.tiqian.shaping.skia.SkiaFontMetricsResolver
import org.tiqian.shaping.skia.SkiaTextShaper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MarkdownInlineNodeDefaultsTest {
    @Test
    fun citationsPreserveSourceAndNavigateBibliographyOnlyAfterDeclinedEvents() {
        for (consumeAt in listOf(null, "none", "CitationReference", "Paragraph")) {
            val destinations = mutableListOf<String>()
            val events = mutableListOf<MarkdownNodeClickEvent>()
            val source = "前[@ref😀]后"
            val range = MarkdownTextRange(1, source.length - 1)
            val reference = metadata(listOf(0, 0), "CitationReference").copy(
                attributes = mapOf("data-ref" to "authored"),
                payload = mapOf("original" to "citation"),
                sourceMarkdown = "[@ref😀]",
                properties = mapOf("relatedInfo" to MarkdownNodeValue.ObjectValue(mapOf("id" to MarkdownNodeValue.Integer(17)))),
                children = listOf(metadata(listOf(0, 0, 0), "Text")),
            )
            val text = MarkdownText(source, listOf(MarkdownTextSpan(range, MarkdownTextMark.Citation("ref😀"), reference)))
            val fixture = Fixture()
            val handler: ((MarkdownNodeClickEvent) -> Boolean)? = if (consumeAt == null) null else {
                { event -> events += event; event.nodeType == consumeAt }
            }
            ImageComposeScene(width = 300, height = 120, density = Density(1f)) {
                val navigation = rememberMarkdownFootnoteNavigationState { destinations += it; false }
                CompositionLocalProvider(LocalMarkdownFootnoteNavigationState provides navigation) {
                    fixture.Content(text, handler)
                }
            }.use { scene ->
                scene.render(0L)
                scene.tap(fixture.point(2), 1000L)
                assertEquals(if (consumeAt == null || consumeAt == "none") listOf("bibliography:ref😀") else emptyList(), destinations)
                assertEquals(when (consumeAt) {
                    null -> emptyList()
                    "CitationReference" -> listOf("CitationReference")
                    else -> listOf("CitationReference", "Paragraph")
                }, events.map { it.nodeType })
                events.firstOrNull()?.let { event ->
                    assertEquals("[@ref😀]", event.text)
                    assertEquals(range, event.textRange)
                    assertEquals(reference, event.metadata)
                    assertEquals(reference.properties, event.properties)
                    assertEquals(reference.children, event.children)
                    assertEquals(reference.attributes, event.attributes)
                    assertEquals(mapOf("key" to "ref😀", "original" to "citation"), event.payload)
                    assertEquals(9L, event.documentRevision)
                    assertNotNull(event.anchorBoundsInWindow)
                }
            }
            assertEquals(source, text.toAnnotatedString(testStyle).text, "pure conversion cannot replace citation source markers")
        }
    }

    @Test
    fun concealedSpoilersConsumeBeforeRevealingAndNeverActivateHiddenChildren() {
        for (consumeAt in listOf(null, "none", "Spoiler", "Paragraph")) {
            val events = mutableListOf<MarkdownNodeClickEvent>()
            val opened = mutableListOf<String>()
            val consume = mutableStateOf(consumeAt)
            val fixture = Fixture()
            val text = spoilerText()
            val handler: ((MarkdownNodeClickEvent) -> Boolean)? = if (consumeAt == null) null else {
                { event -> events += event; event.nodeType == consume.value }
            }
            val openLink: (String) -> Unit = { opened += it }
            ImageComposeScene(width = 300, height = 120, density = Density(1f)) {
                fixture.Content(text, handler, onLinkClick = openLink)
            }.use { scene ->
                scene.render(0L)
                val hiddenText = scene.semanticTexts()
                assertTrue(hiddenText.none { it.contains("secret") }, "concealed source cannot leak through accessibility text")
                val actions = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                    .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                assertEquals(1, actions.count { it.label == "显示隐藏内容" })
                assertTrue(actions.none { it.label.contains("secret") || it.label == "ecr" }, "hidden link/text actions must not be exposed")
                scene.tap(fixture.point(3), 1000L)
                assertTrue(opened.isEmpty(), "first click inside a hidden link must only offer its spoiler")
                assertEquals(when (consumeAt) {
                    null -> emptyList()
                    "Spoiler" -> listOf("Spoiler")
                    else -> listOf("Spoiler", "Paragraph")
                }, events.map { it.nodeType })
                events.firstOrNull()?.let { event ->
                    assertEquals("secret", event.text)
                    assertEquals(MarkdownTextRange(1, 7), event.textRange)
                    assertEquals(text.spans[2].metadata, event.metadata)
                }
                scene.render(16_000_000L)
                if (consumeAt == "Spoiler" || consumeAt == "Paragraph") {
                    assertTrue(scene.semanticTexts().none { it.contains("secret") }, "accepted events must suppress reveal")
                    consume.value = "none"
                    events.clear()
                    scene.tap(fixture.point(3), 2000L)
                    assertEquals(listOf("Spoiler", "Paragraph"), events.map { it.nodeType })
                    scene.render(32_000_000L)
                }
                assertTrue(scene.semanticTexts().any { it == text.value })
                events.clear()
                scene.tap(fixture.point(3), 3000L)
                assertEquals(listOf("https://example.test/secret"), opened, "revealed child link must run its original action once")
                assertEquals(if (handler == null) emptyList() else listOf("Text", "Link", "Spoiler", "Paragraph"), events.map { it.nodeType })
            }
        }
    }

    @Test
    fun enablingNodeEventsKeepsCitationSpoilerAndNativeLinkGeometryAndPaint() {
        val source = "https://example.test/long-path [@key] secret"
        val citationStart = source.indexOf('[')
        val spoilerStart = source.length - 6
        val text = MarkdownText(source, listOf(
            MarkdownTextSpan(MarkdownTextRange(0, citationStart - 1), MarkdownTextMark.Link("https://example.test/long-path")),
            MarkdownTextSpan(MarkdownTextRange(citationStart, spoilerStart - 1), MarkdownTextMark.Citation("key")),
            MarkdownTextSpan(MarkdownTextRange(spoilerStart, source.length), MarkdownTextMark.Spoiler),
            MarkdownTextSpan(MarkdownTextRange(spoilerStart, source.length), MarkdownTextMark.Link("https://example.test/secret")),
        ))
        fun render(handler: ((MarkdownNodeClickEvent) -> Boolean)?): Pair<LayoutResult, List<Color>> {
            val fixture = Fixture()
            return ImageComposeScene(width = 300, height = 180, density = Density(1f)) {
                fixture.Content(text, handler)
            }.use { scene ->
                val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
                fixture.layouts.last() to List(pixels.width * pixels.height) { pixels[it % pixels.width, it / pixels.width] }
            }
        }
        val native = render(null)
        val interactive = render { false }
        assertEquals(native.second, interactive.second)
        assertEquals(native.first.lines.map { it.range }, interactive.first.lines.map { it.range })
        assertEquals(native.first.size, interactive.first.size)
        assertEquals(native.first.getBoundingBoxes(0, source.length), interactive.first.getBoundingBoxes(0, source.length))
    }

    @Test
    fun customPresentationCallbackReplacementRetainsMeasurementAndDefaultConsumption() {
        val actions = mutableListOf<String>()
        val callback = mutableStateOf<(String) -> Unit>({ actions += "first:$it" })
        val consume = mutableStateOf(false)
        val fixture = Fixture()
        val text = MarkdownText("custom", listOf(MarkdownTextSpan(MarkdownTextRange(0, 6), MarkdownTextMark.Custom("host"))))
        val slots = MarkdownInlineSlots(custom = { _, _, _ ->
            MarkdownCustomInlinePresentation(onClick = callback.value, accessibilityLabel = { "host action" })
        })
        ImageComposeScene(width = 300, height = 120, density = Density(1f)) {
            fixture.Content(text, { consume.value }, slots)
        }.use { scene ->
            scene.render(0L)
            scene.tap(fixture.point(0), 1000L)
            assertEquals(listOf("first:custom"), actions)
            val measurements = fixture.layouts.size
            callback.value = { actions += "second:$it" }
            Snapshot.sendApplyNotifications()
            scene.render(16_000_000L)
            assertEquals(measurements, fixture.layouts.size, "presentation callback replacement must not invoke layout")
            scene.tap(fixture.point(0), 2000L)
            assertEquals(listOf("first:custom", "second:custom"), actions)
            consume.value = true
            scene.tap(fixture.point(0), 3000L)
            assertEquals(2, actions.size)
        }
    }

    private class Fixture {
        val layouts = mutableListOf<LayoutResult>()
        private var bounds: Rect? = null
        private val engine = ExplainableStubParagraphLayoutEngine(lineBreaker = LookaheadLineBreaker(), textShaper = SkiaTextShaper(), fontMetricsResolver = SkiaFontMetricsResolver())
        private val measurer = ParagraphMeasurer(object : ParagraphLayoutEngine {
            override fun layout(input: LayoutInput) = engine.layout(input).also(layouts::add)
        })

        @Composable
        fun Content(text: MarkdownText, handler: ((MarkdownNodeClickEvent) -> Boolean)?, slots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
            onLinkClick: ((String) -> Unit)? = null) {
            CompositionLocalProvider(LocalMarkdownParagraphMeasurer provides measurer,
                LocalMarkdownNodeClick provides rememberUpdatedState(handler), LocalMarkdownDocumentRevision provides 9L) {
                MarkdownNodeClickScope(event = { MarkdownNodeClickEvent("Paragraph", text.value, metadata(listOf(0), "Paragraph")) }) {
                    MarkdownTextBlock(text, testStyle.body, testStyle, slots, onLinkClick, null,
                        Modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
                        ParagraphStyle(firstLineIndent = Ic.Zero, lineLengthGrid = LineLengthGrid(enabled = false)))
                }
            }
        }

        fun point(offset: Int): Offset {
            val box = layouts.last().getBoundingBoxes(offset, offset + 1).last()
            return assertNotNull(bounds).topLeft + Offset(box.left + (box.right - box.left) / 4f, (box.top + box.bottom) / 2f)
        }
    }

    private fun ImageComposeScene.semanticTexts(): List<String> = semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    private fun ImageComposeScene.tap(point: Offset, time: Long) {
        sendPointerEvent(PointerEventType.Press, point, timeMillis = time, type = PointerType.Touch,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, point, timeMillis = time + 50, type = PointerType.Touch,
            buttons = PointerButtons(), button = PointerButton.Primary)
    }

    private companion object {
        val testStyle = MarkdownStyle(body = TextStyle(color = Color.Black, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 36.sp),
            proseMeasure = MarkdownProseMeasure(enabled = false))
        fun metadata(path: List<Int>, type: String) = MarkdownNodeMetadata(MarkdownNodeKey(0, path), MarkdownSourceSpan(10, 20, 1, 0, 1, 10), nodeType = type)
        fun spoilerText() = MarkdownText("前secret后", listOf(
            MarkdownTextSpan(MarkdownTextRange(2, 5), metadata = metadata(listOf(0, 0, 0, 0), "Text")),
            MarkdownTextSpan(MarkdownTextRange(2, 5), MarkdownTextMark.Link("https://example.test/secret"), metadata(listOf(0, 0, 0), "Link")),
            MarkdownTextSpan(MarkdownTextRange(1, 7), MarkdownTextMark.Spoiler, metadata(listOf(0, 0), "Spoiler")),
        ))
    }
}
