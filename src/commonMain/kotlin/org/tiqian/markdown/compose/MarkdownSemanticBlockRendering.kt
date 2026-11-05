package org.tiqian.markdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.tiqian.markdown.MarkdownBibliographyBlock
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownContainerBlock
import org.tiqian.markdown.MarkdownContainerKind
import org.tiqian.markdown.MarkdownDiagramBlock
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextSpan
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTocBlock
import org.tiqian.markdown.childBlocks

@Composable
fun DefaultMarkdownContainerBlock(
    block: MarkdownContainerBlock,
    style: MarkdownStyle,
    slots: MarkdownBlockSlots = DefaultMarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
    children: @Composable () -> Unit = {
        MarkdownBlocks(block.blocks, modifier = Modifier.fillMaxWidth(), style = style, slots = slots, inlineSlots = inlineSlots,
            onLinkClick = onLinkClick, onFootnoteClick = onFootnoteClick, compact = false)
    },
) {
    when (block.kind) {
        MarkdownContainerKind.Columns -> BoxWithConstraints(Modifier.fillMaxWidth()) {
            val availableWidthPx = with(LocalDensity.current) { maxWidth.toPx() }.coerceAtLeast(1f)
            val shares = block.blocks.map { child ->
                val width = (child as? MarkdownContainerBlock)?.width.orEmpty()
                when {
                    width.endsWith("%") -> width.dropLast(1).toFloatOrNull()?.div(100f)
                    width.endsWith("px") -> width.dropLast(2).toFloatOrNull()?.div(availableWidthPx)
                    else -> null
                }?.takeIf { it.isFinite() && it > 0f }
            }
            val defaultShare = ((1f - shares.filterNotNull().sum()).coerceAtLeast(0f) /
                shares.count { it == null }.coerceAtLeast(1)).takeIf { it > 0f } ?: 1f
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(style.blockSpacing)) {
                block.blocks.forEachIndexed { index, child ->
                    Box(Modifier.weight(shares[index] ?: defaultShare)) {
                        MarkdownBlock(child, style, slots, inlineSlots, onLinkClick, onFootnoteClick)
                    }
                }
            }
        }
        MarkdownContainerKind.Tabs -> {
            var localSelected by rememberSaveable(block.metadata.key) { mutableIntStateOf(0) }
            val navigation = LocalMarkdownHeadingNavigation.current
            val selected = (navigation?.selectedTabs?.get(block.metadata.key) ?: localSelected)
                .coerceIn(0, block.blocks.lastIndex.coerceAtLeast(0))
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    block.blocks.forEachIndexed { index, child ->
                        val revision = LocalMarkdownDocumentRevision.current
                        MarkdownNodeClickScope(event = { child.nodeClickEvent(revision) }) {
                            val click = rememberMarkdownNodeClickHandler()
                            TextButton(
                                onClick = {
                                    if (click?.invoke() != true) {
                                        localSelected = index
                                        navigation?.selectedTabs?.set(block.metadata.key, index)
                                    }
                                },
                                modifier = Modifier.semantics { role = Role.Tab; this.selected = selected == index },
                            ) {
                                Text((child as? MarkdownContainerBlock)?.title?.value ?: "${index + 1}",
                                    style = style.body.copy(fontWeight = if (selected == index) FontWeight.Bold else FontWeight.Normal),
                                    color = if (selected == index) style.link.color else style.body.color)
                            }
                        }
                    }
                }
                block.blocks.getOrNull(selected)?.let { child ->
                    // The title is the tab control, not a second heading in its body.
                    val body = (child as? MarkdownContainerBlock)?.copy(title = null) ?: child
                    MarkdownBlock(body, style, slots, inlineSlots, onLinkClick, onFootnoteClick)
                }
            }
        }
        else -> {
            val inset = block.kind == MarkdownContainerKind.DefinitionDescription
            val panel = block.kind == MarkdownContainerKind.Admonition
            Column(Modifier.fillMaxWidth()
                .then(if (panel) Modifier.background(style.quoteBarColor.copy(alpha = 0.08f)).padding(style.quoteContentPadding) else Modifier)
                .then(if (inset) Modifier.padding(start = style.quoteContentPadding) else Modifier)) {
                block.title?.let { title ->
                    MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "title")) {
                        MarkdownTextBlock(title,
                            if (block.kind == MarkdownContainerKind.DefinitionTerm || panel) style.body.copy(fontWeight = FontWeight.Bold) else style.body,
                            style, inlineSlots, onLinkClick, onFootnoteClick)
                    }
                }
                children()
            }
        }
    }
}

@Composable
fun DefaultMarkdownBibliographyBlock(
    block: MarkdownBibliographyBlock,
    style: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(style.compactBlockSpacing)) {
        MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "title")) {
            val title = MarkdownText(block.title, listOf(MarkdownTextSpan(MarkdownTextRange(0, block.title.length),
                metadata = generatedMarkdownNodeMetadata(block.metadata, -1).copy(nodeType = "BibliographyTitle"))))
            MarkdownTextBlock(title, style.body.copy(fontWeight = FontWeight.Bold), style, inlineSlots, onLinkClick, onFootnoteClick)
        }
        block.entries.forEach { entry ->
            val navigation = LocalMarkdownFootnoteNavigationState.current
            val requester = remember { BringIntoViewRequester() }
            DisposableEffect(navigation, entry.key, requester) {
                navigation?.registerDefinition("bibliography:" + entry.key, requester)
                onDispose { navigation?.unregisterDefinition("bibliography:" + entry.key, requester) }
            }
            val revision = LocalMarkdownDocumentRevision.current
            MarkdownNodeClickScope(event = { MarkdownNodeClickEvent(entry.metadata.nodeType ?: "BibliographyEntry",
                entry.text.value, entry.metadata, documentRevision = revision) }) {
                Row(Modifier.fillMaxWidth().bringIntoViewRequester(requester)) {
                    Text("[${entry.key}] ", style = style.body, color = style.body.color)
                    Box(Modifier.weight(1f)) {
                        MarkdownSelectionScope(markdownSelectionKey(entry.metadata.key)) {
                            MarkdownTextBlock(entry.text, style.body, style, inlineSlots, onLinkClick, onFootnoteClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DefaultMarkdownTocBlock(
    block: MarkdownTocBlock,
    style: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
) {
    val documentBlocks = LocalMarkdownDocumentBlocks.current
    val headings = remember(documentBlocks, block) {
        buildList {
            fun collect(blocks: List<MarkdownBlock>) {
                blocks.forEach { candidate ->
                    if (candidate is MarkdownHeading && candidate.level in block.minDepth..block.maxDepth && candidate.id !in block.excludedIds) add(candidate)
                    collect(candidate.childBlocks())
                }
            }
            collect(documentBlocks)
        }.let { if (block.reversed) it.reversed() else it }
    }
    val navigation = LocalMarkdownHeadingNavigation.current
    Column(Modifier.fillMaxWidth()) {
        headings.forEach { heading ->
            val revision = LocalMarkdownDocumentRevision.current
            MarkdownNodeClickScope(event = { heading.nodeClickEvent(revision) }) {
                val click = rememberMarkdownNodeClickHandler()
                TextButton(onClick = { if (click?.invoke() != true) navigation?.navigate(heading.metadata.key) },
                    modifier = Modifier.padding(start = 12.dp * (heading.level - block.minDepth).coerceAtLeast(0))) {
                    Text(heading.text.value, style = style.body, color = style.link.color)
                }
            }
        }
    }
}

@Composable
fun DefaultMarkdownDiagramBlock(block: MarkdownDiagramBlock, style: MarkdownStyle) {
    MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "code")) {
        DefaultMarkdownCodeBlock(MarkdownCodeBlock(block.source, block.language, block.language, block.metadata, showLineNumbers = false), style)
    }
}
