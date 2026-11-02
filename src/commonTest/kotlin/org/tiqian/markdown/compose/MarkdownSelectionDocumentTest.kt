package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownListItem
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.MarkdownNodeMetadata
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownRenderDocument
import org.tiqian.markdown.MarkdownSourceSpan
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownTableAlignment
import org.tiqian.markdown.MarkdownTableCell
import org.tiqian.markdown.MarkdownTableRow
import org.tiqian.markdown.MarkdownTaskState
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class MarkdownSelectionDocumentTest {
    @Test
    fun projectsVirtualizedContentInReadingOrderWithoutComposingIt() {
        val listItem = MarkdownListItem(
            blocks = listOf(paragraph(3, "列表正文")),
            metadata = metadata(2),
        )
        val taskItem = MarkdownListItem(
            blocks = listOf(paragraph(9, "任务正文")),
            task = MarkdownTaskState.Checked,
            metadata = metadata(8),
        )
        val blocks = listOf(
            paragraph(1, "首段"),
            MarkdownList(false, 1, true, listOf(listItem, taskItem), metadata(4)),
            MarkdownCodeBlock("val answer = 42", "kotlin", null, metadata(5)),
            MarkdownFootnoteDefinition("note", 1, listOf(paragraph(7, "脚注正文")), metadata(6)),
        )

        val fragments = blocks.markdownSelectionFragments()

        assertEquals(
            listOf("首段", "•", "列表正文", "[x]", "任务正文", "val answer = 42", "[1]", "脚注正文"),
            fragments.map { it.text.text },
        )
        assertEquals(listOf("\n", " ", "\n", " ", "\n", "\n", " ", "\n"), fragments.map { it.separatorAfter })
    }

    @Test
    fun selectionFragmentsExposeNodeAndRoleWithoutTextLookup() {
        val first = MarkdownParagraph(
            MarkdownText(
                value = "same text",
                spans = listOf(MarkdownTextSpan(MarkdownTextRange(0, 4), MarkdownTextMark.Strong)),
            ),
            metadata(10),
        )
        val second = paragraph(11, "same text")
        val nestedParagraph = paragraph(13, "same text")
        val item = MarkdownListItem(blocks = listOf(nestedParagraph), metadata = metadata(12))
        val list = MarkdownList(false, 1, true, listOf(item), metadata(14))
        val cell = MarkdownTableCell(MarkdownText("same text"), MarkdownTableAlignment.Start, false, metadata(15))
        val table = MarkdownTable(
            columnAlignments = listOf(MarkdownTableAlignment.Start),
            rows = listOf(MarkdownTableRow(listOf(cell), false, metadata(16))),
            metadata = metadata(17),
        )
        val footnote = MarkdownFootnoteDefinition("note", 1, emptyList(), metadata(18))
        val document = MarkdownRenderDocument(listOf(first, second, list, table, footnote))

        val fragments = document.blocks.markdownSelectionFragments()
        assertEquals(
            listOf("same text", "same text", "•", "same text", "same text", "[1]"),
            fragments.map { it.text.text },
        )
        val keys = fragments.map { assertIs<MarkdownSelectionKey>(it.key) }
        assertEquals(
            listOf(
                first.metadata.key, second.metadata.key, item.metadata.key, nestedParagraph.metadata.key,
                cell.metadata.key, footnote.metadata.key,
            ),
            keys.map { it.node },
        )
        assertEquals(listOf("text", "text", "marker", "text", "cell", "marker"), keys.map { it.role })
        assertSame(
            second,
            document.blocks.filterIsInstance<MarkdownParagraph>().single { it.metadata.key == keys[1].node },
        )
        assertNotEquals(keys[0], keys[1])
        assertNotEquals(keys[1], keys[4])
        assertNotEquals(keys[2], keys[3])
        assertNotEquals(keys[0], keys[0].copy(role = "marker"))
    }

    private fun paragraph(key: Int, text: String) = MarkdownParagraph(MarkdownText(text), metadata(key))

    private fun metadata(key: Int) = MarkdownNodeMetadata(
        key = MarkdownNodeKey(key, listOf(key)),
        sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
    )
}
