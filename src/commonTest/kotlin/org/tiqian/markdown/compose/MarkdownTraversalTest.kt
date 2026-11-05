package org.tiqian.markdown.compose

import org.tiqian.markdown.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownTraversalTest {
    @Test
    fun everyContainerPreservesNestedGalleryMathFootnoteOwnershipAndSelectionOrder() {
        val body = MarkdownParagraph(richText("note"), metadata(2))
        val first = image(3)
        val second = image(7)
        val noteBody = MarkdownParagraph(MarkdownText("footnote body"), metadata(5))
        val note = MarkdownFootnoteDefinition("note", 1, listOf(noteBody, second), metadata(6))
        val custom = MarkdownCustomBlock(
            kind = "host",
            attributes = mapOf("text" to "custom"),
            metadata = metadata(20),
            blocks = listOf(body, first, note),
        )
        val tail = MarkdownParagraph(MarkdownText("tail"), metadata(30))

        MarkdownContainerKind.entries.forEach { kind ->
            val container = MarkdownContainerBlock(kind, listOf(custom), metadata(50), MarkdownText("title"))
            val document = MarkdownRenderDocument(listOf(container, tail))
            assertEquals(listOf(first, second), document.markdownImageGallery(), kind.name)
            assertTrue(document.blocks.containsMarkdownMath(), kind.name)

            val placed = document.placeFootnotes(MarkdownFootnotePlacement.AfterBlock)
            assertEquals(listOf(container.metadata.key, note.metadata.key, tail.metadata.key), placed.map { it.metadata.key }, kind.name)
            val placedCustom = assertIs<MarkdownCustomBlock>(assertIs<MarkdownContainerBlock>(placed.first()).blocks.single())
            assertEquals(listOf(body, first), placedCustom.blocks, kind.name)
            assertEquals(note.metadata, assertIs<MarkdownFootnoteDefinition>(placed[1]).metadata, kind.name)

            val fragments = placed.markdownSelectionFragments()
            assertEquals(
                listOf("title", "custom", body.text.value, first.description, "[1]", "footnote body", second.description, "tail"),
                fragments.map { it.text.text },
                kind.name,
            )
            assertEquals(
                listOf(
                    markdownSelectionKey(container.metadata.key, "title"),
                    markdownSelectionKey(custom.metadata.key, "custom"),
                    markdownSelectionKey(body.metadata.key),
                    markdownSelectionKey(first.metadata.key, "description"),
                    markdownSelectionKey(note.metadata.key, "marker"),
                    markdownSelectionKey(noteBody.metadata.key),
                    markdownSelectionKey(second.metadata.key, "description"),
                    markdownSelectionKey(tail.metadata.key),
                ),
                fragments.map { it.key },
                kind.name,
            )
        }
    }

    @Test
    fun titlesBibliographyAndCaptionsRetainInlineMathAndFirstReferenceOrder() {
        val owners = listOf(
            MarkdownContainerBlock(MarkdownContainerKind.Admonition, emptyList(), metadata(1), richText("title")),
            MarkdownBibliographyBlock(
                listOf(MarkdownBibliographyEntry("entry", richText("entry"), metadata(3))),
                metadata(2),
            ),
            image(4).copy(caption = richText("caption")),
            MarkdownTable(
                columnAlignments = listOf(MarkdownTableAlignment.Start),
                rows = listOf(
                    MarkdownTableRow(
                        cells = listOf(MarkdownTableCell(richText("cell"), MarkdownTableAlignment.Start, false, metadata(7))),
                        header = false,
                        metadata = metadata(6),
                    ),
                ),
                metadata = metadata(5),
                caption = richText("table"),
            ),
        )
        owners.forEach { assertTrue(listOf(it).containsMarkdownMath()) }
        val labels = listOf("title", "entry", "caption", "table", "cell")
        val definitions = labels.reversed().mapIndexed { index, label ->
            MarkdownFootnoteDefinition(label, index + 1, emptyList(), metadata(100 + index))
        }
        val placed = MarkdownRenderDocument(owners + definitions).placeFootnotes(MarkdownFootnotePlacement.AfterBlock)
        assertEquals(labels, placed.filterIsInstance<MarkdownFootnoteDefinition>().map { it.label })
        assertEquals(
            listOf(richText("title").value, "References", richText("entry").value, richText("caption").value, richText("table").value, richText("cell").value),
            owners.markdownSelectionFragments().map { it.text.text },
        )
    }

    @Test
    fun tabsKeepAllLogicalFragmentsWithoutRequiringVisibleLayouts() {
        val tabs = MarkdownContainerBlock(
            MarkdownContainerKind.Tabs,
            listOf(
                MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(MarkdownParagraph(MarkdownText("first body"), metadata(3))), metadata(2), MarkdownText("first tab")),
                MarkdownContainerBlock(MarkdownContainerKind.Tab, listOf(MarkdownParagraph(MarkdownText("second body"), metadata(5))), metadata(4), MarkdownText("second tab")),
            ),
            metadata(1),
        )
        assertEquals(listOf("first tab", "first body", "second tab", "second body"), listOf(tabs).markdownSelectionFragments().map { it.text.text })
        assertEquals(emptyList(), prelayoutMarkdownBlockIndices(listOf(tabs), 0..0, forward = true, defaultMath = true))
    }

    @Test
    fun newBlockHeightEstimatesReuseChildrenAndConservativelySkipBackgroundPrelayout() {
        val style = MarkdownStyle().withBlockSpacingScale(0f)
        val first = image(1)
        val second = image(2).copy(heightPixels = 200)
        val firstHeight = estimateMarkdownBlockHeightDp(first, 200f, style)
        val secondHeight = estimateMarkdownBlockHeightDp(second, 200f, style)
        val containers = MarkdownContainerKind.entries.map { kind ->
            MarkdownContainerBlock(kind, listOf(first, second), metadata(50))
        }
        containers.forEach { container ->
            val expected = when (container.kind) {
                MarkdownContainerKind.Columns, MarkdownContainerKind.Tabs -> maxOf(firstHeight, secondHeight)
                else -> firstHeight + secondHeight
            }
            assertEquals(expected, estimateMarkdownBlockHeightDp(container, 200f, style), container.kind.name)
            assertTrue(estimateMarkdownBlockHeightDp(container.copy(title = MarkdownText("title")), 200f, style) > expected, container.kind.name)
            assertTrue(listOf(container.copy(blocks = listOf(MarkdownMathBlock("x", metadata(3))))).containsMarkdownMath(), container.kind.name)
        }
        val custom = MarkdownCustomBlock("host", mapOf("text" to "custom"), metadata(4), listOf(first, second))
        assertTrue(estimateMarkdownBlockHeightDp(custom, 200f, style) > firstHeight + secondHeight)
        val diagram = MarkdownDiagramBlock("mermaid", "graph TD\nA --> B", metadata(6))
        assertEquals(
            estimateMarkdownBlockHeightDp(MarkdownCodeBlock(diagram.source, diagram.language, null, diagram.metadata), 200f, style),
            estimateMarkdownBlockHeightDp(diagram, 200f, style),
        )
        val pageBreak = MarkdownPageBreakBlock(metadata(7))
        assertEquals(estimateMarkdownBlockHeightDp(MarkdownThematicBreak(pageBreak.metadata), 200f, style), estimateMarkdownBlockHeightDp(pageBreak, 200f, style))
        val bibliography = MarkdownBibliographyBlock(listOf(MarkdownBibliographyEntry("entry", richText("note"), metadata(9))), metadata(8))
        val toc = MarkdownTocBlock(metadata(10))
        val blocks = containers + listOf(custom, bibliography, diagram, pageBreak, toc)
        blocks.forEach { assertTrue(estimateMarkdownBlockHeightDp(it, 200f, style).isFinite()) }
        assertEquals(emptyList(), prelayoutMarkdownBlockIndices(blocks, blocks.indices, forward = true, defaultMath = true))
        assertEquals(listOf(diagram.source), listOf(diagram, toc, pageBreak).markdownSelectionFragments().map { it.text.text })
        assertEquals(markdownSelectionKey(diagram.metadata.key, "code"), listOf(diagram).markdownSelectionFragments().single().key)
        assertFalse(listOf(diagram, toc, pageBreak).containsMarkdownMath())
    }

    private fun richText(label: String): MarkdownText {
        val value = "link x [$label]"
        return MarkdownText(
            value,
            listOf(
                MarkdownTextSpan(MarkdownTextRange(0, 4), MarkdownTextMark.Link("https://example.test")),
                MarkdownTextSpan(MarkdownTextRange(5, 6), MarkdownTextMark.InlineMath("x")),
                MarkdownTextSpan(MarkdownTextRange(7, value.length), MarkdownTextMark.Footnote(label, 1)),
            ),
        )
    }

    private fun image(key: Int) = MarkdownImageBlock("image-$key", "image $key", null, 100, 100, metadata(key))

    private fun metadata(key: Int) = MarkdownNodeMetadata(
        MarkdownNodeKey(key, listOf(key)),
        MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
    )
}
