package org.tiqian.markdown

/** Direct prose in reading order; structural descendants are supplied by [childBlocks]. */
internal fun MarkdownBlock.directTexts(): Sequence<MarkdownText> = sequence {
    when (val block = this@directTexts) {
        is MarkdownParagraph -> yield(block.text)
        is MarkdownHeading -> yield(block.text)
        is MarkdownImageBlock -> block.caption?.let { yield(it) }
        is MarkdownTable -> {
            block.caption?.let { yield(it) }
            block.rows.forEach { row -> row.cells.forEach { cell -> yield(cell.text) } }
        }
        is MarkdownContainerBlock -> block.title?.let { yield(it) }
        is MarkdownBibliographyBlock -> block.entries.forEach { yield(it.text) }
        else -> Unit
    }
}
