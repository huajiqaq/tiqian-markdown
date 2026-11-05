package org.tiqian.markdown

/** Where footnote definitions are inserted in continuous-flow rendering. */
enum class MarkdownFootnotePlacement {
    /** Immediately after the outer block containing the first reference. */
    AfterBlock,

    /** At the end of the Markdown heading section containing the first reference. */
    AfterSection,

    /** At the end of the complete article. */
    EndOfArticle,
}

internal fun MarkdownRenderDocument.placeFootnotes(
    placement: MarkdownFootnotePlacement,
): List<MarkdownBlock> {
    val definitions = linkedMapOf<String, MarkdownFootnoteDefinition>()
    collectFootnoteDefinitions(blocks, definitions)
    if (definitions.isEmpty()) return blocks

    val content = stripFootnoteDefinitions(blocks)
    return when (placement) {
        MarkdownFootnotePlacement.AfterBlock -> content.placeFootnotesAfterFirstReferenceBlock(definitions)
        MarkdownFootnotePlacement.AfterSection -> content.placeFootnotesAtSectionEnds(definitions)
        MarkdownFootnotePlacement.EndOfArticle -> content + definitions.values
    }
}

private fun collectFootnoteDefinitions(
    blocks: List<MarkdownBlock>,
    destination: LinkedHashMap<String, MarkdownFootnoteDefinition>,
) {
    blocks.forEach { block ->
        if (block is MarkdownFootnoteDefinition) {
            destination.putIfAbsent(
                block.label,
                block.copy(blocks = stripFootnoteDefinitions(block.blocks)),
            )
        }
        collectFootnoteDefinitions(block.childBlocks(), destination)
    }
}

private fun stripFootnoteDefinitions(blocks: List<MarkdownBlock>): List<MarkdownBlock> =
    blocks.mapNotNull { block ->
        when (block) {
            is MarkdownFootnoteDefinition -> null
            is MarkdownBlockQuote -> block.copy(blocks = stripFootnoteDefinitions(block.blocks))
            is MarkdownList -> block.copy(
                items = block.items.map { item ->
                    item.copy(blocks = stripFootnoteDefinitions(item.blocks))
                },
            )
            is MarkdownContainerBlock -> block.copy(blocks = stripFootnoteDefinitions(block.blocks))
            is MarkdownCustomBlock -> block.copy(blocks = stripFootnoteDefinitions(block.blocks))
            else -> block
        }
    }

private fun List<MarkdownBlock>.placeFootnotesAfterFirstReferenceBlock(
    definitions: LinkedHashMap<String, MarkdownFootnoteDefinition>,
): List<MarkdownBlock> {
    val assigned = mutableSetOf<String>()

    fun definitionsFor(labels: List<String>): List<MarkdownFootnoteDefinition> = labels.mapNotNull { label ->
        definitions[label]?.takeIf { assigned.add(label) }
    }

    val placed = buildList {
        this@placeFootnotesAfterFirstReferenceBlock.forEach { block ->
            add(block)
            addAll(definitionsFor(block.allFootnoteReferences()))
        }
    }
    return placed + definitions.values.filterNot { it.label in assigned }
}

private fun List<MarkdownBlock>.placeFootnotesAtSectionEnds(
    definitions: LinkedHashMap<String, MarkdownFootnoteDefinition>,
): List<MarkdownBlock> {
    data class SectionFrame(
        val level: Int,
        val labels: MutableList<String> = mutableListOf(),
    )

    val result = mutableListOf<MarkdownBlock>()
    val frames = mutableListOf(SectionFrame(level = 0))
    val assigned = mutableSetOf<String>()

    fun closeLastSection() {
        val frame = frames.removeAt(frames.lastIndex)
        frame.labels.mapNotNullTo(result) { definitions[it] }
    }

    forEach { block ->
        if (block is MarkdownHeading) {
            while (frames.size > 1 && frames.last().level >= block.level) closeLastSection()
            result += block
            frames += SectionFrame(block.level)
        } else {
            result += block
        }
        block.allFootnoteReferences().forEach { label ->
            if (label in definitions && assigned.add(label)) frames.last().labels += label
        }
    }
    while (frames.size > 1) closeLastSection()
    closeLastSection()
    result += definitions.values.filterNot { it.label in assigned }
    return result
}

private fun MarkdownBlock.allFootnoteReferences(): List<String> = buildList {
    directTexts().forEach { addAll(it.footnoteReferences()) }
    childBlocks().forEach { addAll(it.allFootnoteReferences()) }
}

private fun MarkdownText.footnoteReferences(): List<String> = buildList {
    spans.sortedBy { it.range.start }.forEach { span ->
        val footnote = span.mark as? MarkdownTextMark.Footnote ?: return@forEach
        if (footnote.label !in this) add(footnote.label)
    }
}
