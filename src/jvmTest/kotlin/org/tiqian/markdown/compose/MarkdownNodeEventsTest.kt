@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package org.tiqian.markdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import org.tiqian.compose.CjkSelectionContainer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.rememberCjkSelectionState
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

class MarkdownNodeEventsTest {
    @Test
    fun everyInlineFamilyKeepsIdentityPayloadAndSourceRange() {
        val marks = listOf(null, MarkdownTextMark.Strong, MarkdownTextMark.Emphasis,
            MarkdownTextMark.Strikethrough, MarkdownTextMark.InlineCode, MarkdownTextMark.Highlight,
            MarkdownTextMark.Superscript, MarkdownTextMark.Subscript, MarkdownTextMark.Inserted,
            MarkdownTextMark.KeyboardInput, MarkdownTextMark.Link("https://example.test/path", "title"),
            MarkdownTextMark.Abbreviation("expanded"), MarkdownTextMark.Footnote("note", 1),
            MarkdownTextMark.Ruby("ruby"), MarkdownTextMark.InlineImage("image", "alt"),
            MarkdownTextMark.InlineMath("x"), MarkdownTextMark.Custom("host.note"), null)
        val spans = marks.mapIndexed { index, mark ->
            val range = MarkdownTextRange(index * 4, index * 4 + 3)
            MarkdownTextSpan(range, mark, metadata(listOf(0, index), if (index == marks.lastIndex) "HostSyntax" else mark.nodeType()))
        }
        val text = MarkdownText(List(marks.size) { "dup " }.joinToString(""), spans)
        val fixture = TextFixture()
        val received = mutableListOf<MarkdownNodeClickEvent>()
        val handler: (MarkdownNodeClickEvent) -> Boolean = { received += it; true }
        val objectSlot: @Composable (MarkdownStyle, TextStyle) -> MarkdownInlineContent = { _, _ ->
            MarkdownInlineContent("dup", Placeholder(36.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                MarkdownInlineMetrics(36f, 24f, 24f)) { Box(Modifier.fillMaxSize().background(Color.Red)) }
        }
        val slots = MarkdownInlineSlots(image = { _, style, host -> objectSlot(style, host) },
            math = { _, style, host -> objectSlot(style, host) })
        ImageComposeScene(width = 300, height = 600, density = Density(1f)) {
            fixture.Content(text, handler, slots)
        }.use { scene ->
            scene.render(0L)
            spans.forEachIndexed { index, span ->
                scene.tap(fixture.point(span.range.start), index * 1000L + 1000L)
                val event = received.last()
                assertEquals(index + 1, received.size, "one physical click must dispatch once")
                assertEquals(span.metadata, event.metadata)
                assertEquals(span.metadata!!.nodeType, event.nodeType)
                assertEquals(span.range, event.textRange)
                assertEquals("dup", event.text)
                assertEquals(mapOf("data-owner" to span.metadata.key.path.joinToString(".")), event.attributes)
                assertEquals(7L, event.documentRevision)
                assertNotNull(event.anchorBoundsInWindow)
                assertEquals(span.mark, event.mark)
            }
            assertEquals("https://example.test/path", received[10].payload["destination"])
            assertEquals("expanded", received[11].payload["fullText"])
            assertEquals("note", received[12].payload["label"])
            assertEquals("ruby", received[13].payload["annotation"])
            assertEquals("image", received[14].payload["destination"])
            assertEquals("x", received[15].payload["expression"])
            assertEquals("host.note", received[16].payload["kind"])
        }
    }

    @Test
    fun nestedClicksUseCurrentCallbackWithoutRemeasuringAndRetainDefaults() {
        val inner = metadata(listOf(0, 0, 0), "Text")
        val outer = metadata(listOf(0, 0), "Link")
        val text = MarkdownText("same same", listOf(
            MarkdownTextSpan(MarkdownTextRange(0, 4), metadata = inner),
            MarkdownTextSpan(MarkdownTextRange(0, 4), MarkdownTextMark.Link("https://example.test/one"), outer),
            MarkdownTextSpan(MarkdownTextRange(5, 9), metadata = metadata(listOf(0, 1), "Text")),
        ))
        val received = mutableListOf<MarkdownNodeClickEvent>()
        val opened = mutableListOf<String>()
        val handler = mutableStateOf<(MarkdownNodeClickEvent) -> Boolean>({ received += it; false })
        val fixture = TextFixture()
        ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) { fixture.Content(text, handler.value) }
        }.use { scene ->
            scene.render(0L)
            scene.tap(fixture.point(3, trailing = true), 1000L)
            assertEquals(listOf("Text", "Link", "Paragraph"), received.map { it.nodeType })
            assertEquals(inner, received.first().metadata)
            assertEquals(listOf("https://example.test/one"), opened)
            val measurementCount = fixture.layouts.size
            received.clear()
            handler.value = { received += it; it.nodeType == "Link" }
            Snapshot.sendApplyNotifications()
            scene.render(16_000_000L)
            assertEquals(measurementCount, fixture.layouts.size, "callback-only changes must not rerun the engine")
            scene.tap(fixture.point(0), 2000L)
            assertEquals(listOf("Text", "Link"), received.map { it.nodeType })
            assertEquals(1, opened.size, "handled link must suppress the default URI action")
            received.clear()
            scene.tap(fixture.point(5), 3000L)
            assertEquals(listOf("Text", "Paragraph"), received.map { it.nodeType })
            assertEquals(listOf(0, 1), received.first().metadata.key.path)
        }
    }

    @Test
    fun everyVisibleBlockAndTableNodeOffersItsActualMetadata() {
        fun paragraph(path: List<Int>, value: String) = MarkdownParagraph(MarkdownText(value), metadata(path, "Paragraph"))
        val table = MarkdownTable(listOf(MarkdownTableAlignment.Start), listOf(
            MarkdownTableRow(listOf(MarkdownTableCell(MarkdownText("cell"), MarkdownTableAlignment.Start, false,
                metadata(listOf(8, 0, 0, 0), "TableCell").copy(payload = mapOf("alignment" to "right", "header" to "parser-cell")))), false,
                metadata(listOf(8, 0, 0), "TableRow").copy(payload = mapOf("header" to "parser-row")),
                metadata(listOf(8, 0), "TableBody")),
        ), metadata(listOf(8), "Table"))
        val blocks = listOf(
            paragraph(listOf(0), "paragraph"),
            MarkdownHeading(2, "heading", MarkdownText("heading"), metadata(listOf(1), "Heading")),
            MarkdownBlockQuote(listOf(paragraph(listOf(2, 0), "quote")), metadata(listOf(2), "BlockQuote")),
            MarkdownList(false, 1, true, listOf(MarkdownListItem(listOf(paragraph(listOf(3, 0, 0), "item")),
                MarkdownTaskState.Checked, metadata(listOf(3, 0), "ListItem").copy(payload = mapOf("task" to "parser-task")))), metadata(listOf(3), "List")),
            MarkdownCodeBlock("val answer = 42", "kotlin", null, metadata(listOf(4), "CodeBlock")),
            MarkdownImageBlock("image", "image", null, 32, 32, metadata(listOf(5), "Image")),
            MarkdownMathBlock("x", metadata(listOf(6), "MathBlock")),
            MarkdownHtmlBlock("<b>html</b>", 0, metadata(listOf(7), "HtmlBlock")),
            table,
            MarkdownFootnoteDefinition("note", 1, listOf(paragraph(listOf(9, 0), "definition")), metadata(listOf(9), "FootnoteDefinition")),
            MarkdownCustomBlock("host.block", mapOf("text" to "custom"), metadata(listOf(10), "host.block")),
            MarkdownThematicBreak(metadata(listOf(11), "ThematicBreak")),
            MarkdownUnsupportedBlock("HostBlock", "fallback", metadata(listOf(12), "HostBlock")),
        )
        val document = MarkdownRenderDocument(blocks, metadata = metadata(emptyList(), "Document"), revision = 7L)
        val received = mutableListOf<MarkdownNodeClickEvent>()
        ImageComposeScene(width = 300, height = 1800, density = Density(1f)) {
            Box(Modifier.fillMaxSize().background(Color.White)) {
                TiqianMarkdown(document, style = testStyle, slots = MarkdownBlockSlots(mathBlock = { _, _ ->
                    Box(Modifier.width(48.dp).background(Color.Red)) { androidx.compose.foundation.text.BasicText("math") }
                }, customBlock = { block, _ -> androidx.compose.foundation.text.BasicText(block.attributes.getValue("text")) }), onNodeClick = { received += it; false })
            }
        }.use { scene ->
            scene.render(0L)
            val events = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = false) }
                .filter { SemanticsActions.OnClick in it.config && it.config.getOrNull(SemanticsProperties.Role) != androidx.compose.ui.semantics.Role.Button }
                .map { node ->
                    received.clear()
                    assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action).invoke())
                    assertNotNull(received.firstOrNull(), "rendered node click must use the generic handler")
                }
            val expected = mapOf("Document" to emptyList(), "Heading" to listOf(1), "BlockQuote" to listOf(2),
                "List" to listOf(3), "ListItem" to listOf(3, 0), "CodeBlock" to listOf(4), "Image" to listOf(5),
                "MathBlock" to listOf(6), "HtmlBlock" to listOf(7), "Table" to listOf(8), "TableBody" to listOf(8, 0),
                "TableRow" to listOf(8, 0, 0), "TableCell" to listOf(8, 0, 0, 0), "FootnoteDefinition" to listOf(9),
                "host.block" to listOf(10), "ThematicBreak" to listOf(11), "HostBlock" to listOf(12))
            expected.forEach { (type, path) ->
                val event = events.firstOrNull { it.nodeType == type } ?: error("missing click on $type")
                assertEquals(path, event.metadata.key.path)
                assertEquals(7L, event.documentRevision)
                assertEquals(mapOf("data-owner" to path.joinToString(".")), event.metadata.attributes)
            }
            assertEquals(mapOf("alignment" to "right", "header" to "parser-cell"), events.first { it.nodeType == "TableCell" }.payload)
            assertEquals("parser-row", events.first { it.nodeType == "TableRow" }.payload["header"])
            assertEquals("parser-task", events.first { it.nodeType == "ListItem" }.payload["task"])
            assertTrue(events.any { it.nodeType == "Paragraph" && it.text == "paragraph" })
            assertEquals(listOf("TableCell", "TableRow", "TableBody", "Table", "Document"), run {
                val cell = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                    .first { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "cell" && SemanticsActions.CustomActions in it.config }
                received.clear()
                cell.config[SemanticsActions.CustomActions].single().action()
                received.map { it.nodeType }.filter { it != "Text" }
            })
        }
    }

    @Test
    fun codeCopyIsIndependentAndSelectionPreservesSourceWithNodeEvents() {
        var copied: AnnotatedString? = null
        val clipboard = object : ClipboardManager {
            override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
            override fun getText(): AnnotatedString? = copied
        }
        val received = mutableListOf<MarkdownNodeClickEvent>()
        val code = "val answer = 42"
        val state = mutableStateOf<org.tiqian.compose.CjkSelectionState?>(null)
        ImageComposeScene(width = 300, height = 180, density = Density(1f)) {
            val selection = rememberCjkSelectionState()
            state.value = selection
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                TiqianMarkdownSurface(MarkdownRenderDocument(listOf(
                    MarkdownCodeBlock(code, "kotlin", null, metadata(listOf(0), "CodeBlock")),
                    MarkdownParagraph(MarkdownText("😀 source"), metadata(listOf(1), "Paragraph")),
                )), style = testStyle, scrollState = rememberScrollState(), selectionState = selection,
                    enableScroll = false, onNodeClick = { received += it; false })
            }
        }.use { scene ->
            scene.render(0L)
            val button = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                .single { it.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button }
            scene.tap(button.boundsInRoot.center, 1000L)
            assertEquals(code, copied?.text)
            assertTrue(received.isEmpty(), "copy control must not offer the code block or document")
            val codeNode = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == code && SemanticsActions.CustomActions in it.config }
            codeNode.config[SemanticsActions.CustomActions].first().action()
            assertEquals("CodeToken", received.first().nodeType)
            assertEquals("Keyword", received.first().payload["kind"])
            assertEquals("val", received.first().text)
            assertEquals(MarkdownTextRange(0, 3), received.first().textRange)
            assertEquals(listOf("CodeToken", "CodeBlock", "Document"), received.map { it.nodeType })
            val selection = assertNotNull(state.value)
            assertTrue(selection.selectAll())
            assertEquals("$code\n😀 source", selection.selectedText?.text)
            assertTrue(selection.copySelection())
            assertEquals("$code\n😀 source", copied?.text)
        }
    }

    @Test
    fun footnoteAndImageDefaultsRunOnlyWhenTheHostDeclines() {
        val received = mutableListOf<String>()
        val labels = mutableListOf<String>()
        val handled = mutableStateOf(false)
        val fixture = TextFixture()
        val text = MarkdownText("ref", listOf(MarkdownTextSpan(MarkdownTextRange(0, 3), MarkdownTextMark.Footnote("note", 1))))
        ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
            fixture.Content(text, { received += it.nodeType; handled.value }, onFootnoteClick = { labels += it })
        }.use { scene ->
            scene.render(0L)
            scene.tap(fixture.point(0), 1000L)
            assertEquals(listOf("Footnote", "Paragraph"), received)
            assertEquals(listOf("note"), labels)
            handled.value = true
            scene.render(16_000_000L)
            received.clear()
            scene.tap(fixture.point(0), 2000L)
            assertEquals(listOf("Footnote"), received)
            assertEquals(1, labels.size)
        }
        var viewer: MarkdownImageViewerState? = null
        var imageBounds: Rect? = null
        val image = MarkdownImageBlock("image", "image", null, 32, 32, metadata(listOf(0), "Image"))
        handled.value = false
        received.clear()
        ImageComposeScene(width = 300, height = 180, density = Density(1f)) {
            val state = rememberMarkdownImageViewerState()
            viewer = state
            MarkdownImageViewerHost(state = state, imageProvider = {
                MarkdownImageContent(IntSize(32, 32), MarkdownImageLoadState.Success) { modifier ->
                    Box(modifier.background(Color.Red).onGloballyPositioned { imageBounds = it.boundsInRoot() })
                }
            }) {
                TiqianMarkdown(MarkdownRenderDocument(listOf(image)), style = testStyle,
                    onNodeClick = { received += it.nodeType; handled.value })
            }
        }.use { scene ->
            scene.render(0L)
            val point = assertNotNull(imageBounds).center
            scene.tap(point, 3000L)
            assertEquals(listOf("Image", "Document"), received)
            assertEquals(image.metadata.key, assertNotNull(viewer).activeImage?.metadata?.key)
            assertNotNull(viewer).dismiss()
            handled.value = true
            scene.render(32_000_000L)
            received.clear()
            scene.tap(point, 4000L)
            assertEquals(listOf("Image"), received)
            assertTrue(!assertNotNull(viewer).isVisible)
        }
    }

    @Test
    fun longPressStillSelectsTextWithoutDispatchingNodeClicks() {
        val fixture = TextFixture()
        val events = mutableListOf<MarkdownNodeClickEvent>()
        var state: org.tiqian.compose.CjkSelectionState? = null
        val shortLongPress = object : ViewConfiguration {
            override val longPressTimeoutMillis = 1L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }
        ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
            val selection = rememberCjkSelectionState()
            state = selection
            CompositionLocalProvider(LocalViewConfiguration provides shortLongPress) {
                CjkSelectionContainer(state = selection) {
                    fixture.Content(MarkdownText("alpha beta gamma"), { events += it; true })
                }
            }
        }.use { scene ->
            scene.render(0L)
            val point = fixture.point(7)
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = 6000L, type = PointerType.Touch)
            Thread.sleep(20L)
            scene.render(16_000_000L)
            scene.sendPointerEvent(PointerEventType.Move, point + Offset(1f, 0f), timeMillis = 6025L, type = PointerType.Touch)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = 6050L, type = PointerType.Touch)
            assertEquals("beta", assertNotNull(state).selectedText?.text)
            assertTrue(events.isEmpty(), "selection release must not activate a node")
        }
    }

    @Test
    fun eventAnnotationsPreserveNativeBreakingAndCustomDefaultActions() {
        val value = "prefix https://example.test/long-path suffix custom"
        val customStart = value.length - 6
        val text = MarkdownText(value, listOf(
            MarkdownTextSpan(MarkdownTextRange(7, customStart - 8), MarkdownTextMark.Link("https://example.test/long-path")),
            MarkdownTextSpan(MarkdownTextRange(customStart, value.length), MarkdownTextMark.Custom("host")),
        ))
        val handled = mutableStateOf(false)
        val customClicks = mutableListOf<String>()
        val slots = MarkdownInlineSlots(custom = { _, _, _ ->
            MarkdownCustomInlinePresentation(onClick = { customClicks += it }, accessibilityLabel = { "host: $it" })
        })
        fun render(handler: ((MarkdownNodeClickEvent) -> Boolean)?): Pair<LayoutResult, List<Color>> {
            val fixture = TextFixture()
            return ImageComposeScene(width = 300, height = 180, density = Density(1f)) {
                fixture.Content(text, handler, slots)
            }.use { scene ->
                val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
                fixture.layouts.last() to List(pixels.width * pixels.height) { index -> pixels[index % pixels.width, index / pixels.width] }
            }
        }
        val (native, nativePixels) = render(null)
        val (interactive, interactivePixels) = render { false }
        assertEquals(nativePixels, interactivePixels, "node events must preserve foreground colours and underline styles")
        assertEquals(native.lines.map { it.range }, interactive.lines.map { it.range })
        assertEquals(native.size, interactive.size)
        assertEquals(native.getBoundingBoxes(0, value.length), interactive.getBoundingBoxes(0, value.length),
            "event annotations cannot alter native URL breaking or glyph positions")
        val fixture = TextFixture()
        ImageComposeScene(width = 300, height = 180, density = Density(1f)) {
            fixture.Content(text, { handled.value }, slots)
        }.use { scene ->
            scene.render(0L)
            scene.tap(fixture.point(customStart), 1000L)
            assertEquals(listOf("custom"), customClicks)
            val node = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                .single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == value && SemanticsActions.CustomActions in it.config }
            assertTrue(node.config[SemanticsActions.CustomActions].any { it.label == "host: custom" })
            handled.value = true
            scene.render(16_000_000L)
            scene.tap(fixture.point(customStart), 2000L)
            assertEquals(1, customClicks.size)
        }
    }

    @Test
    fun enclosingLinksRemainDefaultsForNonClickableInlineObjects() {
        for (mark in listOf(MarkdownTextMark.InlineImage("image", "alt"), MarkdownTextMark.InlineMath("x"))) {
          for (fragmented in listOf(false, true)) {
            val fixture = TextFixture()
            val opened = mutableListOf<String>()
            val defaults = mutableListOf<String>()
            val received = mutableListOf<String>()
            val handled = mutableStateOf(false)
            val text = MarkdownText("object", listOf(
                MarkdownTextSpan(MarkdownTextRange(0, 6), mark, metadata(listOf(0, 0, 0), mark.nodeType())),
                MarkdownTextSpan(MarkdownTextRange(0, 6), MarkdownTextMark.Link("https://example.test/object"), metadata(listOf(0, 0), "Link")),
            ))
            val objectSlot: @Composable () -> MarkdownInlineContent = {
                val fragments = if (!fragmented) emptyList() else listOf(0 to "obj", 3 to "ect").map { (start, value) ->
                    MarkdownInlineFragment(MarkdownTextRange(start, start + value.length),
                        MarkdownInlineContent(value, Placeholder(36.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                            MarkdownInlineMetrics(36f, 24f, 24f), onClick = { defaults += value }) {
                            Box(Modifier.fillMaxSize().background(Color.Red))
                        })
                }
                MarkdownInlineContent("object", Placeholder(72.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                    MarkdownInlineMetrics(72f, 24f, 24f), layoutFragments = fragments) { Box(Modifier.fillMaxSize().background(Color.Red)) }
            }
            val slots = MarkdownInlineSlots(image = { _, _, _ -> objectSlot() }, math = { _, _, _ -> objectSlot() })
            ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) { fixture.Content(text, { received += it.nodeType; handled.value }, slots) }
            }.use { scene ->
                scene.render(0L)
                scene.tap(fixture.point(0), 1000L)
                assertEquals(listOf(mark.nodeType(), "Link", "Paragraph"), received)
                assertEquals(if (fragmented) emptyList() else listOf("https://example.test/object"), opened,
                    "the object's own default takes precedence over its enclosing link")
                if (fragmented) {
                    assertEquals(listOf("obj"), defaults)
                    received.clear()
                    scene.tap(fixture.point(3), 2000L)
                    assertEquals(listOf(mark.nodeType(), "Link", "Paragraph"), received)
                    assertEquals(listOf("obj", "ect"), defaults)
                    handled.value = true
                    scene.render(16_000_000L)
                    received.clear()
                    scene.tap(fixture.point(3), 3000L)
                    assertEquals(listOf(mark.nodeType()), received)
                    assertEquals(listOf("obj", "ect"), defaults)
                }
            }
          }
        }
    }

    @Test
    fun slotFactoriesAndChildContentsShareTheInlineNodeHandler() {
        for (captureInFactory in listOf(false, true)) {
            val received = mutableListOf<String>()
            val defaults = mutableListOf<String>()
            val handled = mutableStateOf(false)
            val fixture = TextFixture()
            val text = MarkdownText("image", listOf(MarkdownTextSpan(MarkdownTextRange(0, 5),
                MarkdownTextMark.InlineImage("image", "alt"), metadata(listOf(0, 0), "Image"))))
            val slots = MarkdownInlineSlots(image = { _, _, _ ->
                val factoryHandler = rememberMarkdownNodeClickHandler()
                MarkdownInlineContent("image", Placeholder(60.sp, 24.sp, PlaceholderVerticalAlign.TextCenter),
                    MarkdownInlineMetrics(60f, 24f, 24f)) {
                    val handler = if (captureInFactory) factoryHandler else rememberMarkdownNodeClickHandler()
                    Box(Modifier.fillMaxSize().background(Color.Red).clickable {
                        if (handler?.invoke() != true) defaults += "image"
                    })
                }
            })
            ImageComposeScene(width = 300, height = 100, density = Density(1f)) {
                fixture.Content(text, { received += it.nodeType; handled.value }, slots)
            }.use { scene ->
                scene.render(0L)
                scene.tap(fixture.point(0), 1000L)
                assertEquals(listOf("Image", "Paragraph"), received)
                assertEquals(listOf("image"), defaults)
                handled.value = true
                scene.render(16_000_000L)
                received.clear()
                scene.tap(fixture.point(0), 2000L)
                assertEquals(listOf("Image"), received)
                assertEquals(1, defaults.size)
            }
        }
    }

    @Test
    fun blockSlotsPreserveSiblingLayoutWithNodeEvents() {
        val document = MarkdownRenderDocument(listOf(MarkdownCustomBlock("host", emptyMap(), metadata(listOf(0), "host"))))
        fun bounds(handler: ((MarkdownNodeClickEvent) -> Boolean)?): Map<String, Rect> {
            val result = mutableMapOf<String, Rect>()
            ImageComposeScene(width = 300, height = 180, density = Density(1f)) {
                TiqianMarkdown(document, style = testStyle, slots = MarkdownBlockSlots(customBlock = { _, _ ->
                    androidx.compose.foundation.text.BasicText("title", Modifier.onGloballyPositioned { result["title"] = it.boundsInRoot() })
                    androidx.compose.foundation.text.BasicText("body", Modifier.onGloballyPositioned { result["body"] = it.boundsInRoot() })
                }), onNodeClick = handler)
            }.use { it.render(0L) }
            return result
        }
        val native = bounds(null)
        assertTrue(native.getValue("title").bottom <= native.getValue("body").top)
        assertEquals(native, bounds { false }, "enabling events must preserve multi-root block slot layout")
    }

    private class TextFixture {
        val layouts = mutableListOf<LayoutResult>()
        private var bounds: Rect? = null
        private val engine = ExplainableStubParagraphLayoutEngine(lineBreaker = LookaheadLineBreaker(), textShaper = SkiaTextShaper(), fontMetricsResolver = SkiaFontMetricsResolver())
        private val measurer = ParagraphMeasurer(object : ParagraphLayoutEngine {
            override fun layout(input: LayoutInput) = engine.layout(input).also(layouts::add)
        })

        @Composable
        fun Content(text: MarkdownText, handler: ((MarkdownNodeClickEvent) -> Boolean)?, slots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
            onFootnoteClick: ((String) -> Unit)? = null) {
            CompositionLocalProvider(LocalMarkdownParagraphMeasurer provides measurer,
                LocalMarkdownNodeClick provides rememberUpdatedState(handler), LocalMarkdownDocumentRevision provides 7L) {
                MarkdownNodeClickScope(event = { MarkdownNodeClickEvent("Paragraph", text.value, metadata(listOf(0), "Paragraph")) }) {
                    MarkdownTextBlock(text, testStyle.body, testStyle, slots, null, onFootnoteClick,
                        Modifier.width(300.dp).onGloballyPositioned { bounds = it.boundsInRoot() },
                        ParagraphStyle(firstLineIndent = Ic.Zero, lineLengthGrid = LineLengthGrid(enabled = false)))
                }
            }
        }

        fun point(offset: Int, trailing: Boolean = false): Offset {
            val box = layouts.last().getBoundingBoxes(offset, offset + 1).last()
            assertTrue(box.right > box.left && box.bottom > box.top, "fixture must use real nonempty glyph geometry")
            return assertNotNull(bounds).topLeft + Offset(if (trailing) box.right - 0.25f else box.left + (box.right - box.left) / 4f, (box.top + box.bottom) / 2f)
        }
    }

    private fun ImageComposeScene.tap(point: Offset, time: Long) {
        sendPointerEvent(PointerEventType.Press, point, timeMillis = time, type = PointerType.Touch,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        sendPointerEvent(PointerEventType.Release, point, timeMillis = time + 50, type = PointerType.Touch,
            buttons = PointerButtons(), button = PointerButton.Primary)
    }

    private companion object {
        val testStyle = MarkdownStyle(body = TextStyle(color = Color.Black, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 36.sp),
            proseMeasure = MarkdownProseMeasure(enabled = false))
        fun metadata(path: List<Int>, type: String) = MarkdownNodeMetadata(MarkdownNodeKey(0, path), MarkdownSourceSpan(100, 110, 2, 0, 2, 10),
            nodeType = type, attributes = mapOf("data-owner" to path.joinToString(".")), persistentId = 42L)
    }
}
