package org.tiqian.markdown

/** Returns block images in document order, including images nested in structural blocks. */
internal fun MarkdownRenderDocument.markdownImageGallery(): List<MarkdownImageBlock> =
    blocks.markdownImageGallery()

internal fun List<MarkdownBlock>.markdownImageGallery(): List<MarkdownImageBlock> = buildList {
    this@markdownImageGallery.forEach { block -> addMarkdownImages(block) }
}

private fun MutableList<MarkdownImageBlock>.addMarkdownImages(block: MarkdownBlock) {
    if (block is MarkdownImageBlock) add(block)
    block.childBlocks().forEach(::addMarkdownImages)
}
