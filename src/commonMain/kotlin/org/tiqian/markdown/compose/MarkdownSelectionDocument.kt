package org.tiqian.markdown.compose

import androidx.compose.ui.text.AnnotatedString
import org.tiqian.compose.CjkSelectionDocumentFragment
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownCustomBlock
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

internal data class MarkdownSelectionKey(
    val node: MarkdownNodeKey,
    val role: String = "text",
)

internal fun markdownSelectionKey(node: MarkdownNodeKey, role: String = "text"): MarkdownSelectionKey =
    MarkdownSelectionKey(node, role)

/**
 * Logical reading-order projection shared by selection and optional text search.
 * [onSearchableText] receives only built-in MarkdownTextBlock surfaces, with their top-level
 * deferred owner. Slots affect search eligibility, never the existing selection/copy projection.
 */
internal fun List<MarkdownBlock>.markdownSelectionFragments(
    slots: MarkdownBlockSlots = DefaultMarkdownBlockSlots,
    onSearchableText: ((CjkSelectionDocumentFragment, MarkdownText, MarkdownNodeKey) -> Unit)? = null,
): List<CjkSelectionDocumentFragment> = buildList {
    fun addText(
        node: MarkdownNodeKey,
        text: MarkdownText,
        owner: MarkdownNodeKey,
        searchable: Boolean,
        role: String = "text",
        separator: String = "\n",
    ) {
        val fragment = CjkSelectionDocumentFragment(
            key = markdownSelectionKey(node, role),
            text = AnnotatedString(text.value),
            separatorAfter = separator,
        )
        add(fragment)
        if (searchable) onSearchableText?.invoke(fragment, text, owner)
    }

    fun addBlocks(blocks: List<MarkdownBlock>, owner: MarkdownNodeKey? = null, searchable: Boolean = true) {
        blocks.forEach { block ->
            val retentionOwner = owner ?: block.metadata.key
            when (block) {
                is MarkdownParagraph -> addText(block.metadata.key, block.text, retentionOwner, searchable)
                is MarkdownHeading -> addText(block.metadata.key, block.text, retentionOwner, searchable)
                is MarkdownBlockQuote -> addBlocks(block.blocks, retentionOwner, searchable)
                is MarkdownList -> block.items.forEachIndexed { index, item ->
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
                    addBlocks(item.blocks, retentionOwner, searchable)
                }
                is MarkdownCodeBlock -> add(
                    CjkSelectionDocumentFragment(
                        markdownSelectionKey(block.metadata.key, "code"),
                        AnnotatedString(block.code),
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
                    block.caption?.let { caption ->
                        addText(
                            block.metadata.key, caption, retentionOwner,
                            searchable && slots.imageBlock == null, role = "caption",
                        )
                    }
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
                    block.caption?.let { caption ->
                        addText(
                            block.metadata.key, caption, retentionOwner,
                            searchable && slots.table == null, role = "caption",
                        )
                    }
                    block.rows.forEach { row ->
                        row.cells.forEachIndexed { index, cell ->
                            addText(
                                node = cell.metadata.key,
                                text = cell.text,
                                owner = retentionOwner,
                                searchable = searchable && slots.table == null,
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
                    addBlocks(block.blocks, retentionOwner, searchable && slots.footnoteDefinition == null)
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
        }
    }

    addBlocks(this@markdownSelectionFragments)
}
