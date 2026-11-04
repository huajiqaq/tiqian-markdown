@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package org.tiqian.markdown.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import org.tiqian.compose.addCjkInteractionOnlyAnnotation
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan

private class InlineNodeClick(
    val span: MarkdownTextSpan,
    val event: MarkdownNodeClickEvent,
    val defaultAction: (() -> Unit)?,
    val label: String,
    val renderedStart: Int,
    val renderedEnd: Int,
)

/** Disjoint annotations use the engine's existing hit testing; the model retains every overlap. */
internal fun ResolvedMarkdownText.withNodeClicks(
    text: MarkdownText,
    clicks: MarkdownTextNodeClicks?,
    accessibilityLabels: Map<Int, String> = emptyMap(),
): ResolvedMarkdownText {
    if (clicks == null || text.value.isEmpty()) return this
    require(text.spans.all { it.range.endExclusive <= text.value.length }) { "Node range exceeds MarkdownText.value" }
    val parentMetadata = clicks.parent?.event?.value?.invoke()?.metadata
    val links = annotated.getLinkAnnotations(0, annotated.length)
    val authored = text.spans.withIndex().filter { it.value.range.start < it.value.range.endExclusive }
    val gaps = buildList {
        var end = 0
        for ((_, span) in authored.sortedBy { it.value.range.start }) {
            if (span.range.start > end) add(IndexedValue(text.spans.size + size, MarkdownTextSpan(MarkdownTextRange(end, span.range.start))))
            end = maxOf(end, span.range.endExclusive)
        }
        if (end < text.value.length) add(IndexedValue(text.spans.size + size, MarkdownTextSpan(MarkdownTextRange(end, text.value.length))))
    }
    val nodes = (authored + gaps).map { (index, span) ->
        require(span.range.endExclusive <= text.value.length) { "Node range exceeds MarkdownText.value" }
        val metadata = span.metadata ?: generatedMarkdownNodeMetadata(parentMetadata, index)
        val value = text.value.substring(span.range.start, span.range.endExclusive)
        val start = sourceToRenderedOffset(span.range.start, false)
        val end = sourceToRenderedOffset(span.range.endExclusive, true)
        val link = links.firstOrNull { it.start <= start && it.end >= end }?.item
        val objectClick = tiqianInlineObjects.firstOrNull { it.start == start && it.endExclusive == end }?.content?.onClick
        val attributes = when (val mark = span.mark) {
            is MarkdownTextMark.Custom -> mark.attributes
            is MarkdownTextMark.InlineImage -> mark.attributes
            else -> emptyMap()
        }
        val action: (() -> Unit)? = when {
            objectClick != null -> objectClick
            link?.linkInteractionListener != null -> { { link.linkInteractionListener?.onClick(link) } }
            link is LinkAnnotation.Url -> { { clicks.uriHandler.value.openUri(link.url) } }
            else -> null
        }
        InlineNodeClick(
            span = span,
            event = MarkdownNodeClickEvent(
                nodeType = metadata.nodeType ?: span.mark.nodeType(),
                text = value,
                metadata = metadata,
                textRange = span.range,
                attributes = attributes + metadata.attributes,
                payload = span.mark.nodePayload() + metadata.payload,
                mark = span.mark,
                documentRevision = clicks.revision,
            ),
            defaultAction = action,
            label = accessibilityLabels[index] ?: interactions.firstOrNull { it.start == start && it.endExclusive == end }?.accessibilityLabel ?: value,
            renderedStart = start,
            renderedEnd = end,
        )
    }
    fun ordered(candidates: List<InlineNodeClick>) = candidates.sortedWith(
        compareByDescending<InlineNodeClick> { it.span.metadata?.key?.path?.size ?: 0 }
            .thenBy { it.span.range.endExclusive - it.span.range.start },
    )
    fun dispatch(candidates: List<InlineNodeClick>, runDefaultAction: Boolean = true, defaultAction: (() -> Unit)? = null): Boolean {
        if (clicks.defaultActionInProgress) return false
        val ordered = ordered(candidates)
        for (node in ordered) if (clicks.dispatch(node.event)) return true
        if (clicks.parent?.dispatch() == true) return true
        if (runDefaultAction) (defaultAction ?: ordered.firstOrNull { it.defaultAction != null }?.defaultAction)?.let { action ->
            clicks.defaultActionInProgress = true
            try { action() } finally { clicks.defaultActionInProgress = false }
            return true
        }
        return false
    }
    clicks.inlineHandlers = authored.mapIndexed { nodeIndex, (spanIndex, _) ->
        val node = nodes[nodeIndex]
        spanIndex to {
            dispatch(nodes.filter {
                it.span.range.start <= node.span.range.start && it.span.range.endExclusive >= node.span.range.endExclusive
            }, runDefaultAction = false)
        }
    }.toMap()
    // Preserve native link visual styles while replacing only their event annotations.
    val withoutLinks = annotated.flatMapAnnotations { range ->
        if (range.item is LinkAnnotation) emptyList() else listOf(range)
    }
    val builder = AnnotatedString.Builder(withoutLinks)
    val visible = nodes.filter { it.renderedStart < it.renderedEnd }
    val starts = visible.groupBy { it.renderedStart }
    val ends = visible.groupBy { it.renderedEnd }
    val renderedEdges = (starts.keys + ends.keys).sorted()
    val active = mutableListOf<InlineNodeClick>()
    renderedEdges.zipWithNext().forEachIndexed { index, (start, end) ->
        ends[start]?.let(active::removeAll)
        starts[start]?.let(active::addAll)
        val candidates = active.toList()
        if (start < end && candidates.isNotEmpty()) {
            builder.addLink(LinkAnnotation.Clickable("markdown-node-$index", styles = TextLinkStyles(), linkInteractionListener = { dispatch(candidates) }), start, end)
            builder.addCjkInteractionOnlyAnnotation(start, end)
        }
    }
    // CjkText checks the disjoint handlers first. Original URL/footnote roles still own breaking
    // and styling; removing or splitting those roles would change layout when events are enabled.
    links.forEach { link ->
        when (val annotation = link.item) {
            is LinkAnnotation.Url -> builder.addLink(annotation, link.start, link.end)
            is LinkAnnotation.Clickable -> builder.addLink(annotation, link.start, link.end)
        }
    }
    return copy(
        annotated = builder.toAnnotatedString(),
        interactions = visible.map { node ->
            ResolvedInlineInteraction(node.renderedStart, node.renderedEnd, node.label) {
                dispatch(nodes.filter {
                    it.span.range.start <= node.span.range.start && it.span.range.endExclusive >= node.span.range.endExclusive
                })
            }
        },
        tiqianInlineObjects = tiqianInlineObjects.map { inline ->
            val candidates = nodes.filter {
                it.renderedStart <= inline.start && it.renderedEnd >= inline.endExclusive
            }
            inline.copy(onNodeClick = { runDefaultAction -> dispatch(candidates, runDefaultAction, inline.content.onClick) })
        },
    )
}
