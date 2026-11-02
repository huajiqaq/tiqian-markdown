package org.tiqian.markdown.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.tiqian.compose.CjkSelectionState
import org.tiqian.compose.rememberCjkSelectionState
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownHtmlBlock
import org.tiqian.markdown.MarkdownMathBlock
import org.tiqian.markdown.MarkdownCustomBlock
import org.tiqian.markdown.MarkdownUnsupportedBlock
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownTableRow
import org.tiqian.markdown.MarkdownTableCell
import org.tiqian.markdown.MarkdownTableAlignment
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownListItem
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.MarkdownNodeMetadata
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownRenderDocument
import org.tiqian.markdown.MarkdownSourceSpan
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MarkdownTextSearchTest {
    @Test
    fun unicodeWhitespaceCaseAndUtf16OffsetsComeFromRendererFragments() {
        val source = "😀前 Alpha\u00a0\t\u3000BETA e\u0301 中文 后 alpha beta"
        val blocks = listOf(paragraph(0, source))
        val state = MarkdownTextSearchState()
        state.attach(document(state, blocks))
        state.setQuery(" \nALPHA beta\u3000")
        assertEquals(2, state.matches.size)
        val first = state.matches.first()
        assertEquals(source.indexOf("Alpha"), first.startInFragment)
        assertEquals(source.indexOf("BETA") + 4, first.endInFragment)
        assertEquals("Alpha\u00a0\t\u3000BETA", source.substring(first.startInFragment, first.endInFragment))
        assertTrue(first.excerpt.contains("😀"))
        state.setQuery("e\u0301")
        assertEquals(2, state.matches.single().let { it.endInFragment - it.startInFragment })
        state.setQuery("😀")
        assertEquals(0, state.matches.single().startInFragment)
        assertEquals(2, state.matches.single().endInFragment)
        state.setQuery("\u00a0\t\u3000")
        assertTrue(state.matches.isEmpty())
    }

    @Test
    fun sameParagraphCrossesInlineMarksButNotBlocksOrAtomicSource() {
        val prose = MarkdownParagraph(
            MarkdownText("针叶林 expr 之后目标", listOf(
                MarkdownTextSpan(MarkdownTextRange(1, 2), MarkdownTextMark.Strong),
                MarkdownTextSpan(MarkdownTextRange(4, 8), MarkdownTextMark.InlineMath("expr")),
            )),
            metadata(0),
        )
        val state = MarkdownTextSearchState()
        state.attach(document(state, listOf(
            prose, paragraph(1, "alpha"), paragraph(2, "beta"),
            MarkdownCodeBlock("针叶林", null, null, metadata(3)),
        )))
        state.setQuery("针叶林")
        assertEquals(1, state.matches.size)
        assertEquals(0, state.matches.single().startInFragment)
        assertEquals(3, state.matches.single().endInFragment)
        state.setQuery("alpha beta")
        assertTrue(state.matches.isEmpty())
        state.setQuery("expr")
        assertTrue(state.matches.isEmpty())
        state.setQuery("之后目标")
        assertEquals(9, state.matches.single().startInFragment)
    }

    @Test
    fun nestedFragmentsUseOnlyTheirTopLevelRetentionOwner() {
        val nested = paragraph(3, "needle")
        val list = MarkdownList(false, 1, true, listOf(MarkdownListItem(listOf(nested), metadata = metadata(2))), metadata(1))
        val quote = MarkdownBlockQuote(listOf(list), metadata(0))
        val heading = MarkdownHeading(2, null, MarkdownText("needle"), metadata(4))
        val footnote = MarkdownFootnoteDefinition("f", 1, listOf(paragraph(6, "needle")), metadata(5))
        val state = MarkdownTextSearchState()
        val document = document(state, listOf(quote, heading, footnote))
        state.attach(document)
        state.setQuery("needle")
        assertEquals(3, state.matches.size)
        assertEquals(quote.metadata.key, document.fragments[state.matches[0].fragmentKey]?.ownerKey)
        assertEquals(heading.metadata.key, document.fragments[state.matches[1].fragmentKey]?.ownerKey)
        assertEquals(footnote.metadata.key, document.fragments[state.matches[2].fragmentKey]?.ownerKey)
        assertTrue(document.targets.isEmpty(), "Indexing must not compose or measure any text")
    }

    @Test
    fun queryCloseAndDocumentReplacementCancelPendingRevealAndRejectStaleMatches() = runBlocking {
        val state = MarkdownTextSearchState()
        var current = document(state, listOf(paragraph(0, "needle one")))
        state.attach(current)
        state.setQuery("needle")
        val stale = state.matches.single()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { state.reveal(stale) }
        assertFalse(waiting.isCompleted, "Unmaterialized fragments must wait for actual placed layout")
        assertEquals(metadata(0).key, state.requestedOwnerKey)
        state.setQuery("one")
        assertFalse(waiting.await())
        assertNull(state.requestedOwnerKey)
        assertFalse(state.reveal(stale))
        val closing = async(start = CoroutineStart.UNDISPATCHED) { state.reveal(state.matches.single()) }
        state.clear()
        assertFalse(closing.await())
        assertNull(state.activeMatch)
        assertTrue(state.matches.isEmpty())
        state.setQuery("needle")
        val oldDocumentMatch = state.matches.single()
        state.detach(current)
        current = document(state, listOf(paragraph(0, "needle replacement")))
        state.attach(current)
        assertFalse(state.reveal(oldDocumentMatch))
        assertTrue(state.matches.single().documentRevision > oldDocumentMatch.documentRevision)
        val detaching = async(start = CoroutineStart.UNDISPATCHED) { state.reveal(state.matches.single()) }
        state.detach(current)
        assertFalse(detaching.await())
        assertNull(state.requestedOwnerKey)
    }

    @Test
    fun exactRevealScrollsExternalParentToLongNestedParagraphAndPaintsOnlyActiveRange() = runBlocking {
        val state = MarkdownTextSearchState()
        val scroll = ScrollState(0)
        val nestedText = "引言文字。".repeat(140) + "针叶林" + "结尾文字。".repeat(20)
        val nested = paragraph(1001, nestedText)
        val quote = MarkdownBlockQuote(listOf(nested), metadata(1000))
        val blocks = List(90) { paragraph(it, "第${it}段 普通文字。".repeat(4)) } + quote +
            List(20) { paragraph(1100 + it, "文末正文。".repeat(4)) }
        val article = MarkdownRenderDocument(blocks)
        val style = searchTestStyle()
        lateinit var scope: CoroutineScope
        lateinit var selection: CjkSelectionState
        var shown by mutableStateOf(true)
        ImageComposeScene(width = 320, height = 240, coroutineContext = coroutineContext) {
            scope = rememberCoroutineScope()
            selection = rememberCjkSelectionState()
            Column(Modifier.fillMaxSize().background(Color.White).verticalScroll(scroll)) {
                // A tall host image/header must be scrolled through by the actual parent chain.
                Box(Modifier.fillMaxWidth().height(1200.dp).background(Color.Blue))
                if (shown) TiqianMarkdownSurface(
                    document = article,
                    style = style,
                    scrollState = scroll,
                    enableScroll = false,
                    selectionState = selection,
                    searchState = state,
                )
            }
        }.use { scene ->
            var frame = 0L
            suspend fun advance() {
                Snapshot.sendApplyNotifications()
                scene.render(frame)
                frame += 16_000_000L
                yield()
            }
            repeat(4) { advance() }
            state.setQuery("针叶林")
            val match = state.matches.single()
            val searchDocument = assertNotNull(state.document)
            assertFalse(match.fragmentKey in searchDocument.targets, "Distant target should begin unmaterialized")
            val revealing = scope.async { state.reveal(match) }
            repeat(180) { if (!revealing.isCompleted) advance() }
            assertTrue(revealing.isCompleted, "A pending reveal must wake after real deferred layout")
            assertTrue(revealing.await())
            repeat(4) { advance() }
            val target = assertNotNull(searchDocument.targets[match.fragmentKey])
            assertTrue(assertNotNull(target.firstLineRect(match)).top > 240f, "The hit is not the paragraph's top")
            assertTrue(scroll.value > 1200, "The host ScrollState must scroll past the tall header")
            assertTrue(searchDocument.targets.size < blocks.size / 2, "Search must not retain the entire article")
            assertEquals(quote.metadata.key, state.requestedOwnerKey)
            assertNull(selection.selectedText)
            assertTrue(yellowPixels(scene) > 20, "The actual body match must paint within the viewport")
            state.highlightActiveMatch = false
            advance()
            assertEquals(0, yellowPixels(scene))
            state.highlightActiveMatch = true
            advance()
            assertTrue(yellowPixels(scene) > 20)
            state.setQuery("引言文字。".repeat(140) + "针叶林")
            val multiline = state.matches.single()
            val firstLine = assertNotNull(target.firstLineRect(multiline))
            val actualLayout = assertNotNull(target.layout)
            assertTrue(actualLayout.size.height > 240f)
            assertTrue(firstLine.bottom - firstLine.top < 240f, "A multiline match must request only its first line")
            assertEquals(actualLayout.lines.first().top, firstLine.top)
            state.clear()
            advance()
            assertEquals(0, yellowPixels(scene))
            assertNull(state.requestedOwnerKey)
            shown = false
            advance()
            assertNull(state.document)
        }
    }

    @Test
    fun revealDoesNotChangeAnExistingNativeSelection() = runBlocking {
        val state = MarkdownTextSearchState()
        lateinit var scope: CoroutineScope
        lateinit var selection: CjkSelectionState
        val scroll = ScrollState(0)
        val blocks = listOf(paragraph(0, "before needle after"))
        val article = MarkdownRenderDocument(blocks)
        ImageComposeScene(width = 320, height = 240, coroutineContext = coroutineContext) {
            scope = rememberCoroutineScope()
            selection = rememberCjkSelectionState()
            TiqianMarkdownSurface(
                document = article, scrollState = scroll, searchState = state,
                selectionState = selection, style = searchTestStyle(),
            )
        }.use { scene ->
            repeat(4) { scene.render(it * 16_000_000L); yield() }
            assertTrue(selection.selectAll())
            val selected = selection.selectedText
            state.setQuery("needle")
            val match = state.matches.single()
            val revealing = scope.async { state.reveal(match) }
            repeat(60) { if (!revealing.isCompleted) { scene.render((it + 4) * 16_000_000L); yield() } }
            assertTrue(revealing.isCompleted)
            assertTrue(revealing.await())
            assertEquals(selected, selection.selectedText)
            state.clear()
            assertEquals(selected, selection.selectedText)
            assertNull(state.activeMatch)
        }
        assertNull(state.document)
        assertTrue(state.matches.isEmpty())
    }

    @Test
    fun rejectedAtomicCandidateDoesNotSkipOverlappingProseMatch() {
        for (mark in listOf(
            MarkdownTextMark.InlineImage("test:image", "x"),
            MarkdownTextMark.InlineMath("x"),
        )) {
            val state = MarkdownTextSearchState()
            val block = MarkdownParagraph(
                MarkdownText("xxx", listOf(MarkdownTextSpan(MarkdownTextRange(0, 1), mark))),
                metadata(0),
            )
            state.attach(document(state, listOf(block)))
            state.setQuery("xx")
            assertEquals(1, state.matches.single().startInFragment)
            assertEquals(3, state.matches.single().endInFragment)
        }
    }

    @Test
    fun queryBeforeAttachAndDetachReattachKeepQueryButRejectOldResults() = runBlocking {
        val state = MarkdownTextSearchState()
        state.setQuery("needle")
        assertTrue(state.matches.isEmpty())
        val first = document(state, listOf(paragraph(0, "needle")))
        state.attach(first)
        val stale = state.matches.single()
        state.detach(first)
        assertEquals("needle", state.query)
        assertTrue(state.matches.isEmpty())
        val second = document(state, listOf(paragraph(0, "new needle")))
        state.attach(second)
        assertEquals(4, state.matches.single().startInFragment)
        assertFalse(state.reveal(stale))
        state.clear()
        assertEquals("", state.query)
        assertTrue(state.matches.isEmpty())
    }

    @Test
    fun missingTargetTimesOutAndNewRequestAndCallerCancellationReleaseOwners() = runBlocking {
        val state = MarkdownTextSearchState(revealTimeoutMillis = 30)
        state.attach(document(state, listOf(paragraph(0, "needle"), paragraph(1, "needle"))))
        state.setQuery("needle")
        assertFalse(withTimeout(2_000) { state.reveal(state.matches.first()) })
        assertNull(state.activeMatch)
        assertNull(state.requestedOwnerKey)
        val cancelling = MarkdownTextSearchState()
        cancelling.attach(document(cancelling, listOf(paragraph(0, "needle"), paragraph(1, "needle"))))
        cancelling.setQuery("needle")
        val first = async(start = CoroutineStart.UNDISPATCHED) { cancelling.reveal(cancelling.matches[0]) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { cancelling.reveal(cancelling.matches[1]) }
        assertFalse(first.await())
        assertEquals(metadata(1).key, cancelling.requestedOwnerKey)
        second.cancel()
        try {
            second.await()
            error("Caller cancellation must propagate")
        } catch (_: CancellationException) {
            // Expected: internal cancellation returns false, caller cancellation propagates.
        }
        assertNull(cancelling.activeMatch)
        assertNull(cancelling.requestedOwnerKey)
    }

    @Test
    fun slotReplacementsExcludeUnrevealableSubtreesWithoutChangingSelectionProjection() {
        val table = MarkdownTable(
            columnAlignments = listOf(MarkdownTableAlignment.Start),
            rows = listOf(MarkdownTableRow(listOf(
                MarkdownTableCell(MarkdownText("needle cell"), MarkdownTableAlignment.Start, false, metadata(3)),
            ), false, metadata(2))),
            metadata = metadata(1),
            caption = MarkdownText("needle table caption"),
        )
        val image = MarkdownImageBlock(
            "test:image", "needle image description", null, 100, 100, metadata(4),
            caption = MarkdownText("needle figure caption"),
        )
        val blocks = listOf(
            paragraph(0, "needle prose"), table, image,
            MarkdownFootnoteDefinition("note", 1, listOf(paragraph(6, "needle footnote")), metadata(5)),
            MarkdownCodeBlock("needle", null, null, metadata(7)),
            MarkdownHtmlBlock("needle", 0, metadata(8)),
            MarkdownMathBlock("needle", metadata(9)),
            MarkdownCustomBlock("custom", mapOf("text" to "needle"), metadata(10)),
            MarkdownUnsupportedBlock("unsupported", "needle", metadata(11)),
            image.copy(metadata = metadata(12), caption = null),
        )
        val state = MarkdownTextSearchState()
        val builtIn = document(state, blocks)
        state.attach(builtIn)
        state.setQuery("needle")
        assertEquals(5, state.matches.size)
        assertEquals(blocks.markdownSelectionFragments(), builtIn.selectionFragments)
        state.detach(builtIn)
        val replaced = MarkdownTextSearchDocument(state, blocks, MarkdownBlockSlots(
            imageBlock = { block, style ->
                block.caption?.let { DefaultMarkdownFigureCaption(it, style) }
            },
            table = { _, _ -> },
            footnoteDefinition = { _, _ -> },
        ))
        state.attach(replaced)
        assertEquals(markdownSelectionKey(metadata(0).key), state.matches.single().fragmentKey)
        assertEquals(builtIn.selectionFragments, replaced.selectionFragments)
        assertTrue(replaced.targets.isEmpty())
    }

    @Test
    fun offsetZeroAfterTallBuiltInImageUsesActualTextLineWithSelectionDisabled() = runBlocking {
        val state = MarkdownTextSearchState()
        state.setQuery("needle")
        val scroll = ScrollState(0)
        val image = MarkdownImageBlock("test:tall", "image", null, 320, 1200, metadata(0))
        val article = MarkdownRenderDocument(listOf(image, paragraph(1, "needle at paragraph start")) +
            List(10) { paragraph(it + 2, "following body text ".repeat(8)) })
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 320, height = 240, coroutineContext = coroutineContext) {
            scope = rememberCoroutineScope()
            TiqianMarkdownSurface(
                document = article,
                style = searchTestStyle(),
                modifier = Modifier.fillMaxSize().background(Color.White),
                scrollState = scroll,
                selectable = false,
                searchState = state,
                imageProvider = {
                    MarkdownImageContent(IntSize(320, 1200), MarkdownImageLoadState.Success) { modifier ->
                        Box(modifier.background(Color.Blue))
                    }
                },
            )
        }.use { scene ->
            var frame = 0L
            suspend fun advance() {
                Snapshot.sendApplyNotifications()
                scene.render(frame)
                frame += 16_000_000L
                yield()
            }
            repeat(4) { advance() }
            val match = state.matches.single()
            assertEquals(0, match.startInFragment)
            val revealing = scope.async { state.reveal(match) }
            repeat(180) { if (!revealing.isCompleted) advance() }
            assertTrue(revealing.isCompleted)
            assertTrue(revealing.await())
            repeat(4) { advance() }
            assertTrue(scroll.value > 800, "Actual tall image height must precede the first text line")
            assertTrue(yellowPixels(scene) > 20, "Offset zero must reveal the text, not the document top")
        }
    }

    @Test
    fun openingAndClosingSearchPreservesHostSelectionAndRecomputesPreboundQuery() = runBlocking {
        val state = MarkdownTextSearchState()
        val article = MarkdownRenderDocument(listOf(paragraph(0, "before needle after")))
        val scroll = ScrollState(0)
        var enabled by mutableStateOf(false)
        lateinit var selection: CjkSelectionState
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 320, height = 240, coroutineContext = coroutineContext) {
            selection = rememberCjkSelectionState()
            scope = rememberCoroutineScope()
            TiqianMarkdownSurface(
                document = article, scrollState = scroll, selectionState = selection, style = searchTestStyle(),
                modifier = Modifier.fillMaxSize().background(Color.White),
                searchState = if (enabled) state else null,
            )
        }.use { scene ->
            var frame = 0L
            suspend fun advance() {
                Snapshot.sendApplyNotifications()
                scene.render(frame)
                frame += 16_000_000L
                yield()
            }
            repeat(4) { advance() }
            assertTrue(selection.selectAll())
            val selected = assertNotNull(selection.selectedText)
            suspend fun revealAndCheckPaint() {
                val revealing = scope.async { state.reveal(state.matches.single()) }
                repeat(60) { if (!revealing.isCompleted) advance() }
                assertTrue(revealing.isCompleted, "A newly attached search target must receive existing text geometry")
                assertTrue(revealing.await())
                assertEquals(selected, selection.selectedText)
                // The native selection paints over the search background. Explicitly remove it
                // only for the pixel assertion, then restore it before testing the next toggle.
                selection.clearSelection()
                advance()
                assertTrue(yellowPixels(scene) > 20, "Late-attached search must highlight the actual text")
                assertTrue(selection.selectAll())
                advance()
                assertEquals(selected, selection.selectedText)
            }
            state.setQuery("needle")
            assertNull(state.document)
            enabled = true
            repeat(4) { advance() }
            assertEquals(1, state.matches.size)
            assertEquals(selected, selection.selectedText)
            revealAndCheckPaint()
            enabled = false
            repeat(4) { advance() }
            assertNull(state.document)
            assertNull(state.requestedOwnerKey)
            assertTrue(state.matches.isEmpty())
            assertEquals(selected, selection.selectedText)
            enabled = true
            repeat(4) { advance() }
            assertEquals(1, state.matches.size)
            assertEquals(selected, selection.selectedText)
            revealAndCheckPaint()
        }
    }

    @Test
    fun revealAndHighlightUseInlineReplacementMappingAfterUtf16Source() = runBlocking {
        val state = MarkdownTextSearchState()
        val source = "😀LONG_IMAGE_SOURCE needle"
        val imageEnd = source.indexOf(" needle")
        val paragraph = MarkdownParagraph(MarkdownText(source, listOf(
            MarkdownTextSpan(MarkdownTextRange(2, imageEnd), MarkdownTextMark.InlineImage("test:inline", "image")),
        )), metadata(0))
        val slots = MarkdownInlineSlots(image = { _, _, _ ->
            MarkdownInlineContent(
                alternateText = "x",
                placeholder = Placeholder(20.sp, 20.sp, PlaceholderVerticalAlign.TextCenter),
                metrics = MarkdownInlineMetrics(20f, 20f, 20f),
            ) { Box(Modifier.fillMaxSize().background(Color.Red)) }
        })
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 320, height = 240, coroutineContext = coroutineContext) {
            scope = rememberCoroutineScope()
            TiqianMarkdownSurface(
                document = MarkdownRenderDocument(listOf(paragraph)),
                style = searchTestStyle(),
                modifier = Modifier.fillMaxSize().background(Color.White),
                scrollState = ScrollState(0), selectable = false,
                inlineSlots = slots, searchState = state,
            )
        }.use { scene ->
            var frame = 0L
            suspend fun advance() {
                Snapshot.sendApplyNotifications()
                scene.render(frame)
                frame += 16_000_000L
                yield()
            }
            repeat(4) { advance() }
            state.setQuery("needle")
            val match = state.matches.single()
            assertEquals(source.indexOf("needle"), match.startInFragment)
            val revealing = scope.async { state.reveal(match) }
            repeat(60) { if (!revealing.isCompleted) advance() }
            assertTrue(revealing.isCompleted)
            assertTrue(revealing.await())
            val target = assertNotNull(state.document?.targets?.get(match.fragmentKey))
            assertEquals("😀x needle", assertNotNull(target.layout).input.content.text)
            assertNotNull(target.firstLineRect(match))
            assertTrue(yellowPixels(scene) > 20, "Source offsets must map past the shorter inline object")
        }
    }

    @Test
    fun zeroWidthTextCannotReportSuccessfulReveal() = runBlocking {
        val state = MarkdownTextSearchState()
        state.setQuery("\u200b")
        lateinit var scope: CoroutineScope
        ImageComposeScene(width = 320, height = 240, coroutineContext = coroutineContext) {
            scope = rememberCoroutineScope()
            TiqianMarkdownSurface(
                document = MarkdownRenderDocument(listOf(paragraph(0, "\u200b"))),
                scrollState = ScrollState(0), style = searchTestStyle(), searchState = state,
            )
        }.use { scene ->
            repeat(4) { scene.render(it * 16_000_000L); yield() }
            val match = state.matches.single()
            val revealing = scope.async { state.reveal(match) }
            repeat(60) { if (!revealing.isCompleted) { scene.render((it + 4) * 16_000_000L); yield() } }
            assertTrue(revealing.isCompleted)
            assertFalse(revealing.await(), "A zero-area text range must not report visible content")
            assertNull(state.activeMatch)
            assertNull(state.requestedOwnerKey)
        }
    }

    // The engine's default sans candidates omit Windows' stock fonts. Serif resolves through
    // the same public style path for both shaping and drawing (SimSun / Times New Roman here).
    // Keep actual engine layout and pixel assertions; never substitute a guessed test measurer.
    private fun searchTestStyle() = MarkdownStyle(
        body = TextStyle(color = Color.Black, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 28.sp),
    )

    private fun document(state: MarkdownTextSearchState, blocks: List<MarkdownBlock>) =
        MarkdownTextSearchDocument(state, blocks, DefaultMarkdownBlockSlots)

    private fun paragraph(key: Int, value: String) = MarkdownParagraph(MarkdownText(value), metadata(key))

    private fun metadata(key: Int) = MarkdownNodeMetadata(
        MarkdownNodeKey(key, listOf(key)), MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
    )

    private fun yellowPixels(scene: ImageComposeScene): Int {
        val pixels = scene.render().toComposeImageBitmap().toPixelMap()
        var count = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val color = pixels[x, y]
            if (color.red > 0.9f && color.green > 0.9f && color.blue in 0.6f..0.8f) count++
        }
        return count
    }
}
