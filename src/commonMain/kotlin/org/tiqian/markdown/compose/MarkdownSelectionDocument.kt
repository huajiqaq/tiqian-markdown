package org.tiqian.markdown.compose

import androidx.compose.ui.text.AnnotatedString
import org.tiqian.compose.CjkSelectionDocumentFragment
import org.tiqian.markdown.MarkdownBibliographyBlock
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownContainerBlock
import org.tiqian.markdown.MarkdownCustomBlock
import org.tiqian.markdown.MarkdownDiagramBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownHtmlBlock
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownMathBlock
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownTaskState
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownUnsupportedBlock
import org.tiqian.markdown.childBlocks

internal data class MarkdownSelectionKey(
    val node: MarkdownNodeKey,
    val role: String = "text",
)

internal fun markdownSelectionKey(node: MarkdownNodeKey, role: String = "text"): MarkdownSelectionKey =
    MarkdownSelectionKey(node, role)

/** Logical reading order for copying, including uncomposed blocks and inactive tabs. */
internal fun List<MarkdownBlock>.markdownSelectionFragments(): List<CjkSelectionDocumentFragment> = buildList {
    fun addText(node: MarkdownNodeKey, text: MarkdownText, role: String = "text", separator: String = "\n") {
        add(
            CjkSelectionDocumentFragment(
                key = markdownSelectionKey(node, role),
                text = AnnotatedString(text.value),
                separatorAfter = separator,
            ),
        )
    }

    fun addBlocks(blocks: List<MarkdownBlock>) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownParagraph -> addText(block.metadata.key, block.text)
                is MarkdownHeading -> addText(block.metadata.key, block.text)
                is MarkdownContainerBlock -> block.title?.let { title ->
                    addText(block.metadata.key, title, role = "title")
                }
                is MarkdownBibliographyBlock -> {
                    addText(block.metadata.key, MarkdownText(block.title), role = "title")
                    block.entries.forEach { entry -> addText(entry.metadata.key, entry.text) }
                }
                is MarkdownList -> {
                    block.items.forEachIndexed { index, item ->
                        val marker = when (item.task) {
                            MarkdownTaskState.Checked -> "[x]"
                            MarkdownTaskState.Unchecked -> "[ ]"
                            null -> if (block.ordered) "${block.startNumber + index}." else "•"
                        }
                        add(
                            CjkSelectionDocumentFragment(
                                key = markdownSelectionKey(item.metadata.key, "marker"),
                                text = AnnotatedString(marker),
                                separatorAfter = " ",
                            ),
                        )
                        addBlocks(item.blocks)
                    }
                    return@forEach
                }
                is MarkdownCodeBlock -> add(
                    CjkSelectionDocumentFragment(
                        markdownSelectionKey(block.metadata.key, "code"),
                        AnnotatedString(block.code),
                    ),
                )
                is MarkdownDiagramBlock -> add(
                    CjkSelectionDocumentFragment(
                        markdownSelectionKey(block.metadata.key, "code"),
                        AnnotatedString(block.source),
                    ),
                )
                is MarkdownImageBlock -> {
                    val description = block.description.ifBlank { block.destination }
                    if (block.caption == null) {
                        add(
                            CjkSelectionDocumentFragment(
                                markdownSelectionKey(block.metadata.key, "description"),
                                AnnotatedString(description),
                            ),
                        )
                    }
                    block.caption?.let { caption -> addText(block.metadata.key, caption, role = "caption") }
                }
                is MarkdownMathBlock -> add(
                    CjkSelectionDocumentFragment(
                        markdownSelectionKey(block.metadata.key, "math"),
                        AnnotatedString(block.expression),
                    ),
                )
                is MarkdownHtmlBlock -> add(
                    CjkSelectionDocumentFragment(
                        markdownSelectionKey(block.metadata.key, "html"),
                        AnnotatedString(block.html),
                    ),
                )
                is MarkdownTable -> {
                    block.caption?.let { caption -> addText(block.metadata.key, caption, role = "caption") }
                    block.rows.forEach { row ->
                        row.cells.forEachIndexed { index, cell ->
                            addText(
                                node = cell.metadata.key,
                                text = cell.text,
                                role = "cell",
                                separator = if (index == row.cells.lastIndex) "\n" else "\t",
                            )
                        }
                    }
                }
                is MarkdownFootnoteDefinition -> {
                    add(
                        CjkSelectionDocumentFragment(
                            key = markdownSelectionKey(block.metadata.key, "marker"),
                            text = AnnotatedString("[${block.index}]"),
                            separatorAfter = " ",
                        ),
                    )
                }
                is MarkdownCustomBlock -> block.attributes["text"]?.takeIf(String::isNotEmpty)?.let { text ->
                    add(
                        CjkSelectionDocumentFragment(
                            markdownSelectionKey(block.metadata.key, "custom"),
                            AnnotatedString(text),
                        ),
                    )
                }
                is MarkdownUnsupportedBlock -> block.fallbackText.takeIf(String::isNotEmpty)?.let { text ->
                    add(
                        CjkSelectionDocumentFragment(
                            markdownSelectionKey(block.metadata.key, "unsupported"),
                            AnnotatedString(text),
                        ),
                    )
                }
                else -> Unit
            }
            addBlocks(block.childBlocks())
        }
    }

    addBlocks(this@markdownSelectionFragments)
}
