@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package org.tiqian.markdown.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.tiqian.markdown.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MarkdownSemanticBlockEventsTest {
    @Test
    fun everyContainerAndReplacementSlotRetainsChildIdentityAndInsideOutDispatch() {
        for (kind in MarkdownContainerKind.entries) {
            for (replacement in listOf(false, true)) {
                val leaf = metadata(listOf(0, 0, 0), "Text")
                val paragraph = MarkdownParagraph(MarkdownText("nested", listOf(MarkdownTextSpan(MarkdownTextRange(0, 6), metadata = leaf))),
                    metadata(listOf(0, 0), "Paragraph", children = listOf(leaf)))
                val parent = metadata(listOf(0), kind.name, children = listOf(paragraph.metadata))
                val block = MarkdownContainerBlock(kind, listOf(paragraph), parent)
                val events = mutableListOf<MarkdownNodeClickEvent>()
                val slots = if (replacement) MarkdownBlockSlots(container = { _, _, children -> children() }) else DefaultMarkdownBlockSlots
                ImageComposeScene(width = 500, height = 600, density = Density(1f)) {
                    Box(Modifier.fillMaxSize()) {
                        TiqianMarkdown(MarkdownRenderDocument(listOf(block), metadata = metadata(emptyList(), "Document"), revision = 42),
                            style = MarkdownStyle(), slots = slots, onNodeClick = { events += it; false })
                    }
                }.use { scene ->
                    scene.render(0L)
                    scene.render(16_000_000L)
                    val nodes = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                    val text = nodes.first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "nested" } == true && SemanticsActions.CustomActions in it.config }
                    assertTrue(text.config[SemanticsActions.CustomActions].single().action(), "$kind replacement=$replacement")
                    assertEquals(listOf("Text", "Paragraph", kind.name, "Document"), events.map { it.nodeType }, "$kind replacement=$replacement")
                    assertEquals(leaf, events.first().metadata)
                    assertEquals(parent.children, events.first { it.nodeType == kind.name }.children)
                    assertEquals(parent.properties, events.first { it.nodeType == kind.name }.properties)
                    assertTrue(events.all { it.documentRevision == 42L })
                    assertNotNull(events.first().anchorBoundsInWindow, "$kind replacement=$replacement text anchor")
                }
            }
        }
    }

    @Test
    fun tabsOfferControlBeforeDefaultSwitchAndKeepSelectedBodyEvents() {
        val tabs = MarkdownContainerBlock(MarkdownContainerKind.Tabs, listOf(
            MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(paragraph(listOf(0, 0, 0), "body first")), metadata(listOf(0, 0), "TabItem"), MarkdownText("first")),
            MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(paragraph(listOf(0, 1, 0), "body second")), metadata(listOf(0, 1), "TabItem"), MarkdownText("second")),
        ), metadata(listOf(0), "TabBlock"))
        var consume = true
        val received = mutableListOf<MarkdownNodeClickEvent>()
        ImageComposeScene(width = 500, height = 300, density = Density(1f)) {
            TiqianMarkdown(MarkdownRenderDocument(listOf(tabs), metadata = metadata(emptyList(), "Document")), onNodeClick = {
                received += it
                consume && it.nodeType == "TabItem"
            })
        }.use { scene ->
            scene.render(0L)
            fun texts() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }
            val button = texts().first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "second" } == true && SemanticsActions.OnClick in it.config }
            assertNotNull(button.config[SemanticsActions.OnClick].action).invoke()
            scene.render(16_000_000L)
            assertTrue(texts().none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "body second" } == true })
            assertEquals("TabItem", received.first().nodeType)
            consume = false
            assertNotNull(button.config[SemanticsActions.OnClick].action).invoke()
            scene.render(32_000_000L)
            assertTrue(texts().any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "body second" } == true })
        }
    }

    @Test
    fun bibliographyDiagramTocAndPageBreakExposeTheirMetadataWithoutDuplicateControls() {
        val blocks = listOf(
            MarkdownHeading(2, "target", MarkdownText("target heading"), metadata(listOf(0), "Heading")),
            MarkdownBibliographyBlock(listOf(MarkdownBibliographyEntry("key", MarkdownText("reference"), metadata(listOf(1, -1, 0), "BibliographyEntry"))), metadata(listOf(1), "BibliographyDefinition")),
            MarkdownDiagramBlock("mermaid", "graph LR; A-->B", metadata(listOf(2), "DiagramBlock")),
            MarkdownTocBlock(metadata(listOf(3), "TocPlaceholder")),
            MarkdownPageBreakBlock(metadata(listOf(4), "PageBreak")),
        )
        val received = mutableListOf<MarkdownNodeClickEvent>()
        ImageComposeScene(width = 500, height = 700, density = Density(1f)) {
            TiqianMarkdown(MarkdownRenderDocument(blocks, metadata = metadata(emptyList(), "Document")), onNodeClick = { received += it; true })
        }.use { scene ->
            scene.render(0L)
            val firstEvents = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
                .filter { SemanticsActions.OnClick in it.config && it.config.getOrNull(SemanticsProperties.Role) != androidx.compose.ui.semantics.Role.Button }
                .mapNotNull { node ->
                    received.clear()
                    node.config[SemanticsActions.OnClick].action?.invoke()
                    received.firstOrNull()
                }
            for (type in listOf("Heading", "BibliographyDefinition", "BibliographyEntry", "DiagramBlock", "TocPlaceholder", "PageBreak")) {
                assertTrue(firstEvents.any { it.nodeType == type }, "missing event: $type")
            }
        }
    }

    @Test
    fun citationActivatesNestedBibliographyTabsAfterDeclinedCurrentCallbackInMarkdownAndSurface() {
        for (surface in listOf(false, true)) {
            val citationMetadata = metadata(listOf(0, 0), "CitationReference")
            val citationRange = MarkdownTextRange(5, 11)
            val prose = MarkdownParagraph(MarkdownText("Open [@ref] now.", listOf(
                MarkdownTextSpan(citationRange, MarkdownTextMark.Citation("ref"), citationMetadata),
            )), metadata(listOf(0), "Paragraph"))
            val bibliography = MarkdownBibliographyBlock(listOf(
                MarkdownBibliographyEntry("ref", MarkdownText("reference content for ref"), metadata(listOf(1, 1, 0, 1, 1, 0), "BibliographyEntry")),
            ), metadata(listOf(1, 1, 0, 1, 1), "BibliographyDefinition"))
            val innerTabs = MarkdownContainerBlock(MarkdownContainerKind.Tabs, listOf(
                MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(paragraph(listOf(1, 1, 0, 0, 0), "inner first body")),
                    metadata(listOf(1, 1, 0, 0), "TabItem"), MarkdownText("Inner first")),
                MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(
                    paragraph(listOf(1, 1, 0, 1, 0), "reference lead-in\n".repeat(40)), bibliography,
                ), metadata(listOf(1, 1, 0, 1), "TabItem"), MarkdownText("Inner second")),
            ), metadata(listOf(1, 1, 0), "TabBlock"))
            val tabs = MarkdownContainerBlock(MarkdownContainerKind.Tabs, listOf(
                MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(paragraph(listOf(1, 0, 0), "first body")),
                    metadata(listOf(1, 0), "TabItem"), MarkdownText("First")),
                MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(innerTabs),
                    metadata(listOf(1, 1), "TabItem"), MarkdownText("Second")),
            ), metadata(listOf(1), "TabBlock"))
            val document = MarkdownRenderDocument(listOf(prose, tabs), metadata = metadata(emptyList(), "Document"), revision = 42)
            val received = mutableListOf<MarkdownNodeClickEvent>()
            val staleCalls = mutableListOf<MarkdownNodeClickEvent>()
            val scrollState = ScrollState(0)
            val callback = mutableStateOf<(MarkdownNodeClickEvent) -> Boolean>({ event ->
                received += event
                event.nodeType == "CitationReference"
            })
            ImageComposeScene(width = 640, height = 540, density = Density(1f)) {
                if (surface) TiqianMarkdownSurface(document, Modifier.fillMaxSize(), style = MarkdownStyle(),
                    scrollState = scrollState, selectable = false, onNodeClick = callback.value)
                else TiqianMarkdown(document, Modifier.fillMaxSize().verticalScroll(scrollState),
                    style = MarkdownStyle(), onNodeClick = callback.value)
            }.use { scene ->
                scene.settle(0L)
                assertTrue(scene.control("First").config[SemanticsProperties.Selected])
                assertTrue("reference content for ref" !in scene.semanticTexts())
                val citationAction = scene.nodes(false).filter { it.hasText(prose.text.value) }
                    .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }.single { it.label.contains("ref") }
                assertTrue(citationAction.action())
                scene.settle(100_000_000L)
                assertEquals(listOf("CitationReference"), received.map { it.nodeType })
                assertTrue(scene.control("First").config[SemanticsProperties.Selected], "consumed citation must not switch tabs")
                assertTrue("reference content for ref" !in scene.semanticTexts())

                callback.value = { event -> staleCalls += event; true }
                scene.settle(200_000_000L)
                callback.value = { event -> received += event; false }
                scene.settle(300_000_000L)
                received.clear()
                assertTrue(citationAction.action(), "retained action must read the latest callback")
                scene.settle(400_000_000L)
                assertTrue(staleCalls.isEmpty(), "replaced callbacks must not receive navigation events")
                assertEquals(listOf("CitationReference", "Paragraph", "Document"), received.map { it.nodeType })
                assertEquals(citationMetadata, received.first().metadata)
                assertEquals(citationRange, received.first().textRange)
                assertEquals("[@ref]", received.first().text)
                assertEquals("ref", received.first().payload["key"])
                assertTrue(received.all { it.documentRevision == 42L })
                assertTrue(scene.control("Second").config[SemanticsProperties.Selected], "surface=$surface")
                assertTrue(scene.control("Inner second").config[SemanticsProperties.Selected], "all containing tabs must activate")
                assertTrue("reference content for ref" in scene.semanticTexts(), "the real bibliography must compose, surface=$surface")
                assertTrue(scrollState.value > 0, "pending citation must survive recomposition and scroll to the newly registered entry, surface=$surface")

                assertTrue(assertNotNull(scene.control("First").config[SemanticsActions.OnClick].action).invoke())
                scene.settle(500_000_000L)
                assertTrue("reference content for ref" !in scene.semanticTexts())
                received.clear()
                assertTrue(citationAction.action(), "already materialized Surface owner must still accept another pending navigation")
                scene.settle(600_000_000L)
                assertEquals(listOf("CitationReference", "Paragraph", "Document"), received.map { it.nodeType })
                assertTrue(scene.control("Second").config[SemanticsProperties.Selected])
                assertTrue("reference content for ref" in scene.semanticTexts())
                assertTrue(scrollState.value > 0, "a previously requested owner must retain its pending label on reactivation")
            }
        }
    }

    @Test
    fun pendingDefinitionSurvivesCallbackUpdatesButNeverMovesAReplacementDocument() {
        val first = MarkdownRenderDocument(emptyList(), revision = 1)
        val document = mutableStateOf(first)
        val showTarget = mutableStateOf(false)
        val requested = mutableListOf<String>()
        val stale = mutableListOf<String>()
        val callback = mutableStateOf<(String) -> Boolean>({ stale += it; true })
        var navigation: MarkdownFootnoteNavigationState? = null
        val scroll = ScrollState(0)
        ImageComposeScene(width = 320, height = 200, density = Density(1f)) {
            val state = rememberMarkdownFootnoteNavigationState(document.value, callback.value)
            navigation = state
            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                Box(Modifier.width(100.dp).height(400.dp))
                if (showTarget.value) {
                    val requester = remember { BringIntoViewRequester() }
                    DisposableEffect(state, requester) {
                        state.registerDefinition("bibliography:same", requester)
                        onDispose { state.unregisterDefinition("bibliography:same", requester) }
                    }
                    Box(Modifier.width(100.dp).height(40.dp).bringIntoViewRequester(requester))
                }
            }
        }.use { scene ->
            scene.settle(0L)
            val initial = assertNotNull(navigation)
            initial.bringDefinitionIntoView("bibliography:same")
            assertEquals(listOf("bibliography:same"), stale)
            callback.value = { requested += it; true }
            scene.settle(100_000_000L)
            assertSame(initial, navigation, "same document must retain pending navigation when callbacks change")
            document.value = first.copy(revision = 2)
            showTarget.value = true
            scene.settle(200_000_000L)
            assertNotSame(initial, navigation, "replacement document must own a fresh definition registry and pending requests")
            assertEquals(0, scroll.value, "same-named target in the new document must not execute the old pending request")

            showTarget.value = false
            scene.settle(300_000_000L)
            assertNotNull(navigation).bringDefinitionIntoView("bibliography:same")
            assertEquals(listOf("bibliography:same"), requested, "new requests must use the updated callback")
            showTarget.value = true
            scene.settle(400_000_000L)
            assertTrue(scroll.value > 0, "a current-document pending request must reveal a newly registered target")
        }
    }

    @Test
    fun tocAndTabLabelsConcealSpoilersWhileControlsRetainOriginalEventsAndBodyReveal() {
        val source = "public secret"
        val concealed = MarkdownText(source, listOf(
            MarkdownTextSpan(MarkdownTextRange(7, source.length), MarkdownTextMark.Spoiler, metadata(listOf(2, 0), "Spoiler")),
        ))
        val heading = MarkdownHeading(2, "spoiler-heading", concealed, metadata(listOf(2), "Heading"))
        val tab = MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(paragraph(listOf(1, 1, 0), "second body")),
            metadata(listOf(1, 1), "TabItem"), concealed)
        val tabs = MarkdownContainerBlock(MarkdownContainerKind.Tabs, listOf(
            MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(paragraph(listOf(1, 0, 0), "first body")),
                metadata(listOf(1, 0), "TabItem"), MarkdownText("First")), tab,
        ), metadata(listOf(1), "TabBlock"))
        val toc = MarkdownTocBlock(metadata(listOf(0), "TocPlaceholder"))
        val document = MarkdownRenderDocument(listOf(toc, tabs, heading), metadata = metadata(emptyList(), "Document"))
        val received = mutableListOf<MarkdownNodeClickEvent>()
        val callback = mutableStateOf<(MarkdownNodeClickEvent) -> Boolean>({ event -> received += event; true })
        ImageComposeScene(width = 640, height = 540, density = Density(1f)) {
            TiqianMarkdown(document, style = MarkdownStyle(), onNodeClick = callback.value)
        }.use { scene ->
            scene.settle(0L)
            assertTrue(scene.semanticTexts().none { it.contains("secret") }, "first frame cannot expose concealed label text")
            val safeLabel = "public " + " ".repeat(6)
            val controls = scene.nodes(true).filter { it.hasText(safeLabel) && SemanticsActions.OnClick in it.config &&
                (SemanticsProperties.Selected in it.config || it.config.getOrNull(SemanticsProperties.Role) == Role.Button) }
            val tocControl = controls.single { SemanticsProperties.Selected !in it.config }
            val tabControl = controls.single { SemanticsProperties.Selected in it.config }
            assertTrue(assertNotNull(tabControl.config[SemanticsActions.OnClick].action).invoke())
            scene.settle(100_000_000L)
            assertEquals(listOf("TabItem"), received.map { it.nodeType })
            assertEquals(tab.metadata, received.single().metadata)
            assertEquals(tab.nodeClickEvent(null).text, received.single().text)
            assertTrue("second body" !in scene.semanticTexts())
            received.clear()
            assertTrue(assertNotNull(tocControl.config[SemanticsActions.OnClick].action).invoke())
            assertEquals(listOf("Heading"), received.map { it.nodeType })
            assertEquals(source, received.single().text, "public event keeps original concealed source")
            assertEquals(heading.metadata, received.single().metadata)

            callback.value = { event -> received += event; false }
            scene.settle(200_000_000L)
            received.clear()
            assertTrue(assertNotNull(tabControl.config[SemanticsActions.OnClick].action).invoke())
            scene.settle(300_000_000L)
            assertEquals(listOf("TabItem", "TabBlock", "Document"), received.map { it.nodeType })
            assertTrue("second body" in scene.semanticTexts())
            assertTrue(scene.control(safeLabel, tab = true).config[SemanticsProperties.Selected])
            received.clear()
            assertTrue(assertNotNull(tocControl.config[SemanticsActions.OnClick].action).invoke())
            scene.settle(400_000_000L)
            assertEquals(listOf("Heading", "TocPlaceholder", "Document"), received.map { it.nodeType })
            assertTrue(scene.semanticTexts().none { it.contains("secret") }, "control navigation must not reveal spoilers")
            received.clear()
            val reveal = scene.nodes(false).flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                .single { it.label == "显示隐藏内容" }
            assertTrue(reveal.action())
            scene.settle(500_000_000L)
            assertEquals(listOf("Spoiler", "Heading", "Document"), received.map { it.nodeType })
            assertTrue(source in scene.semanticTexts(), "body must retain its existing reveal action")
            assertEquals(2, scene.nodes(true).count { it.hasText(safeLabel) && SemanticsActions.OnClick in it.config },
                "both labels remain concealed without acquiring separate reveal controls")
        }
    }

    private fun ImageComposeScene.nodes(merged: Boolean) = semanticsOwners.flatMap { it.getAllSemanticsNodes(merged) }

    private fun androidx.compose.ui.semantics.SemanticsNode.hasText(value: String) =
        config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == value }

    private fun ImageComposeScene.semanticTexts() = nodes(false)
        .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    private fun ImageComposeScene.control(label: String, tab: Boolean = true) = nodes(true)
        .single { it.hasText(label) && SemanticsActions.OnClick in it.config && (!tab || SemanticsProperties.Selected in it.config) }

    private fun ImageComposeScene.settle(startTimeNanos: Long) {
        repeat(6) { frame ->
            Snapshot.sendApplyNotifications()
            render(startTimeNanos + frame * 16_000_000L).close()
        }
    }

    private fun paragraph(path: List<Int>, text: String) = MarkdownParagraph(MarkdownText(text), metadata(path, "Paragraph"))

    private fun metadata(path: List<Int>, type: String, children: List<MarkdownNodeMetadata> = emptyList()) = MarkdownNodeMetadata(
        MarkdownNodeKey(1, path), MarkdownSourceSpan(0, 0, 0, 0, 0, 0), nodeType = type,
        attributes = mapOf("custom" to "a|b\u001f😀"), properties = mapOf("nullable" to MarkdownNodeValue.Null,
            "values" to MarkdownNodeValue.ListValue(listOf(MarkdownNodeValue.Integer(7), MarkdownNodeValue.Text("a|b")))),
        children = children, hasSourceSpan = false,
    )
}
