@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package org.tiqian.markdown.compose

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import org.tiqian.compose.addCjkInteractionOnlyAnnotation
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan

private class InlineNodeClick(
    val spanIndex: Int,
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
    if ((clicks == null && hiddenSpoilers.isEmpty()) || text.value.isEmpty()) return this
    require(text.spans.all { it.range.endExclusive <= text.value.length }) { "Node range exceeds MarkdownText.value" }
    val parentMetadata = clicks?.parent?.event?.value?.invoke()?.metadata
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
            link is LinkAnnotation.Url && clicks != null -> { { clicks.uriHandler.value.openUri(link.url) } }
            else -> null
        }
        InlineNodeClick(
            spanIndex = index,
            span = span,
            event = MarkdownNodeClickEvent(
                nodeType = metadata.nodeType ?: span.mark.nodeType(),
                text = value,
                metadata = metadata,
                textRange = span.range,
                attributes = attributes + metadata.attributes,
                payload = span.mark.nodePayload() + metadata.payload,
                mark = span.mark,
                documentRevision = clicks?.revision,
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
    fun hiddenAt(candidates: List<InlineNodeClick>) = hiddenSpoilers
        .filter { spoiler -> candidates.any { it.spanIndex == spoiler.spanIndex } }
        .maxByOrNull { it.sourceRange.endExclusive - it.sourceRange.start }
    fun outsideHiddenChild(node: InlineNodeClick, spoiler: ResolvedMarkdownSpoiler): Boolean {
        if (node.spanIndex == spoiler.spanIndex) return true
        val range = node.span.range
        if (range.start > spoiler.sourceRange.start || range.endExclusive < spoiler.sourceRange.endExclusive) return false
        if (range != spoiler.sourceRange) return true
        val path = node.span.metadata?.key?.path ?: return false
        val spoilerPath = text.spans[spoiler.spanIndex].metadata?.key?.path ?: return false
        return path.size < spoilerPath.size && spoilerPath.take(path.size) == path
    }
    fun dispatch(candidates: List<InlineNodeClick>, runDefaultAction: Boolean = true, defaultAction: (() -> Unit)? = null): Boolean {
        if (clicks?.defaultActionInProgress == true) return false
        val hidden = hiddenAt(candidates)
        val ordered = ordered(if (hidden == null) candidates else candidates.filter { outsideHiddenChild(it, hidden) })
        for (node in ordered) if (clicks?.dispatch(node.event) == true) return true
        if (clicks?.parent?.dispatch() == true) return true
        if (runDefaultAction) (hidden?.onReveal ?: defaultAction ?: ordered.firstOrNull { it.defaultAction != null }?.defaultAction)?.let { action ->
            clicks?.defaultActionInProgress = true
            try { action() } finally { clicks?.defaultActionInProgress = false }
            return true
        }
        // A hidden object's child control cannot run its own action after declining the host.
        return hidden != null
    }
    clicks?.inlineHandlers = authored.mapIndexed { nodeIndex, (spanIndex, _) ->
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
        if (start < end && candidates.isNotEmpty() && (clicks != null || hiddenAt(candidates) != null)) {
            builder.addLink(LinkAnnotation.Clickable("markdown-node-$index", styles = TextLinkStyles(), linkInteractionListener = { dispatch(candidates) }), start, end)
            builder.addCjkInteractionOnlyAnnotation(start, end)
        }
    }
    // Native URL/footnote roles still own breaking and styling. Even concealed links retain their
    // original ranges, so enabling events or revealing a spoiler cannot change URL classification.
    links.forEach { link ->
        when (val annotation = link.item) {
            is LinkAnnotation.Url -> builder.addLink(annotation, link.start, link.end)
            is LinkAnnotation.Clickable -> builder.addLink(annotation, link.start, link.end)
        }
    }
    hiddenSpoilers.forEach { spoiler ->
        val candidates = nodes.filter { it.renderedStart <= spoiler.start && it.renderedEnd >= spoiler.endExclusive }
        val mask = SpanStyle(color = spoiler.color)
        builder.addStyle(mask, spoiler.start, spoiler.endExclusive)
        // CjkText applies link styles last; this interaction-only mask must follow child links.
        builder.addLink(LinkAnnotation.Clickable("markdown-spoiler-${spoiler.spanIndex}",
            styles = TextLinkStyles(style = mask), linkInteractionListener = { dispatch(candidates) }), spoiler.start, spoiler.endExclusive)
        builder.addCjkInteractionOnlyAnnotation(spoiler.start, spoiler.endExclusive)
    }
    val safeInteractions = visible.filter { node ->
        hiddenSpoilers.none { spoiler ->
            node.spanIndex != spoiler.spanIndex &&
                node.renderedStart < spoiler.endExclusive && node.renderedEnd > spoiler.start
        }
    }.filter { clicks != null || it.defaultAction != null || hiddenSpoilers.any { spoiler -> spoiler.spanIndex == it.spanIndex } }
    return copy(
        annotated = builder.toAnnotatedString(),
        semanticsText = if (hiddenSpoilers.isEmpty()) null else {
            val source = text.value.toCharArray()
            hiddenSpoilers.forEach { spoiler ->
                for (offset in spoiler.sourceRange.start until spoiler.sourceRange.endExclusive) source[offset] = ' '
            }
            AnnotatedString(source.concatToString())
        },
        interactions = safeInteractions.map { node ->
            ResolvedInlineInteraction(node.renderedStart, node.renderedEnd,
                if (hiddenSpoilers.any { it.spanIndex == node.spanIndex }) "显示隐藏内容" else node.label) {
                dispatch(nodes.filter {
                    it.span.range.start <= node.span.range.start && it.span.range.endExclusive >= node.span.range.endExclusive
                })
            }
        },
        tiqianInlineObjects = tiqianInlineObjects.map { inline ->
            val candidates = nodes.filter {
                it.renderedStart <= inline.start && it.renderedEnd >= inline.endExclusive
            }
            if (clicks == null && hiddenAt(candidates) == null) inline
            else inline.copy(onNodeClick = { runDefaultAction -> dispatch(candidates, runDefaultAction, inline.content.onClick) })
        },
    )
}
