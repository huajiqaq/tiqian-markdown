@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package org.tiqian.markdown.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import org.tiqian.markdown.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    private fun paragraph(path: List<Int>, text: String) = MarkdownParagraph(MarkdownText(text), metadata(path, "Paragraph"))

    private fun metadata(path: List<Int>, type: String, children: List<MarkdownNodeMetadata> = emptyList()) = MarkdownNodeMetadata(
        MarkdownNodeKey(1, path), MarkdownSourceSpan(0, 0, 0, 0, 0, 0), nodeType = type,
        attributes = mapOf("custom" to "a|b\u001f😀"), properties = mapOf("nullable" to MarkdownNodeValue.Null,
            "values" to MarkdownNodeValue.ListValue(listOf(MarkdownNodeValue.Integer(7), MarkdownNodeValue.Text("a|b")))),
        children = children, hasSourceSpan = false,
    )
}
