package org.tiqian.markdown.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import org.tiqian.compose.CjkInlineObject
import org.tiqian.compose.CjkInlineObjectBoundary
import org.tiqian.compose.CjkInlineObjectPreferredStretch
import org.tiqian.compose.CjkInlineObjectPreferredStretchKind
import org.tiqian.compose.CjkSelectionScope
import org.tiqian.compose.material3.CjkText
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.rememberParagraphMeasurer
import org.tiqian.core.ParagraphStyle
import org.tiqian.markdown.MarkdownText

private val LocalMarkdownSelectionFragmentKey = staticCompositionLocalOf<Any?> { null }

internal val LocalMarkdownSelectionRetentionKey = staticCompositionLocalOf<Any?> { null }

@Composable
internal fun MarkdownSelectionScope(key: Any, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMarkdownSelectionFragmentKey provides key) {
        CjkSelectionScope(
            ownerKey = key,
            retentionKey = LocalMarkdownSelectionRetentionKey.current ?: key,
            content = content,
        )
    }
}

@Composable
internal fun rememberMarkdownParagraphMeasurer(): ParagraphMeasurer =
    LocalMarkdownParagraphMeasurer.current ?: rememberParagraphMeasurer()

@Composable
internal fun MarkdownTextBlock(
    text: MarkdownText,
    textStyle: TextStyle,
    markdownStyle: MarkdownStyle,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    paragraphStyle: ParagraphStyle? = null,
) {
    val paragraphMeasurer = rememberMarkdownParagraphMeasurer()
    val selectionFragmentKey = LocalMarkdownSelectionFragmentKey.current
    val precomputed = selectionFragmentKey?.let(LocalMarkdownPrecomputedLayouts.current::get)
    val footnoteNavigationState = LocalMarkdownFootnoteNavigationState.current
    val currentLinkClick = rememberUpdatedState(onLinkClick)
    val currentFootnoteClick = rememberUpdatedState(onFootnoteClick)
    val currentFootnoteNavigation = rememberUpdatedState(footnoteNavigationState)
    val stableLinkClick = remember(onLinkClick != null) {
        if (onLinkClick == null) {
            null
        } else {
            { destination: String ->
                currentLinkClick.value?.invoke(destination)
                Unit
            }
        }
    }
    val handleFootnoteClick = remember(footnoteNavigationState != null || onFootnoteClick != null) {
        if (footnoteNavigationState == null && onFootnoteClick == null) {
            null
        } else {
            { label: String ->
                currentFootnoteNavigation.value?.bringDefinitionIntoView(label)
                currentFootnoteClick.value?.invoke(label)
                Unit
            }
        }
    }
    val resolved = resolveMarkdownText(
        text = text,
        style = markdownStyle,
        textStyle = textStyle,
        inlineSlots = inlineSlots,
        onLinkClick = stableLinkClick,
        onFootnoteClick = handleFootnoteClick,
        preparedInlineMath = precomputed?.preparedInlineMath.orEmpty(),
    )
    val density = LocalDensity.current
    val tiqianInlineObjects = remember(resolved.tiqianInlineObjects, resolved.endMarkers, density) {
        resolved.toCjkInlineObjects(density)
    }
    val inlineDecorations = remember(resolved.decorations) {
        resolved.decorations.toCjkInlineDecorations()
    }
    val inlineBackgrounds = remember(resolved.backgrounds) {
        resolved.backgrounds.toCjkInlineBackgrounds()
    }
    val textModifier = modifier
        .markdownInlineInteractionSemantics(resolved.interactions)
    val effectiveParagraph = if (markdownStyle.insetProse) insetProseParagraphStyle(paragraphStyle ?: ParagraphStyle(firstLineIndent = org.tiqian.core.Ic.Zero)) else paragraphStyle
    if (effectiveParagraph == null) {
        CjkText(
            text = resolved.annotated,
            modifier = textModifier,
            style = textStyle,
            inlineObjects = tiqianInlineObjects,
            inlineBackgrounds = inlineBackgrounds,
            inlineDecorations = inlineDecorations,
            measurer = paragraphMeasurer,
            precomputedLayout = precomputed?.layout?.takeIf { resolved.endMarkers.isEmpty() },
        )
    } else {
        CjkText(
            text = resolved.annotated,
            modifier = textModifier,
            style = textStyle,
            paragraphStyle = effectiveParagraph,
            inlineObjects = tiqianInlineObjects,
            inlineBackgrounds = inlineBackgrounds,
            inlineDecorations = inlineDecorations,
            measurer = paragraphMeasurer,
            precomputedLayout = precomputed?.layout?.takeIf { resolved.endMarkers.isEmpty() },
        )
    }
}

internal fun ResolvedMarkdownText.toCjkInlineObjects(
    density: androidx.compose.ui.unit.Density,
): List<CjkInlineObject> = (tiqianInlineObjects.map { resolvedObject ->
    val metrics = requireNotNull(resolvedObject.content.metrics)
    CjkInlineObject(
                range = TextRange(resolvedObject.start, resolvedObject.endExclusive),
                advance = with(density) { metrics.widthPx.toDp() },
                ascent = with(density) { metrics.ascentPx.toDp() },
                descent = with(density) { metrics.descentPx.toDp() },
                leadingBoundary = CjkInlineObjectBoundary(
                    participatesInUniformStretch =
                        resolvedObject.content.leadingBoundary.participatesInUniformStretch,
                    preferredStretch = resolvedObject.content.leadingBoundary.preferredStretch?.let {
                        CjkInlineObjectPreferredStretch(
                            kind = when (it.kind) {
                                MarkdownInlinePreferredStretchKind.PunctuationTrailing ->
                                    CjkInlineObjectPreferredStretchKind.PunctuationTrailing
                                MarkdownInlinePreferredStretchKind.Relation ->
                                    CjkInlineObjectPreferredStretchKind.Relation
                                MarkdownInlinePreferredStretchKind.BinaryOperator ->
                                    CjkInlineObjectPreferredStretchKind.BinaryOperator
                            },
                            naturalWidth = with(density) { it.naturalWidthPx.toDp() },
                            targetWidth = with(density) { it.targetWidthPx.toDp() },
                        )
                    },
                    shrinkCapacity = with(density) {
                        resolvedObject.content.leadingBoundary.shrinkCapacityPx.toDp()
                    },
                    lineEndDiscardableAdvance = with(density) {
                        resolvedObject.content.leadingBoundary.lineEndDiscardableAdvancePx.toDp()
                    },
                    preventsLineBreak = resolvedObject.content.leadingBoundary.preventsLineBreak,
                ),
                trailingBoundary = CjkInlineObjectBoundary(
                    participatesInUniformStretch =
                        resolvedObject.content.trailingBoundary.participatesInUniformStretch,
                    preferredStretch = resolvedObject.content.trailingBoundary.preferredStretch?.let {
                        CjkInlineObjectPreferredStretch(
                            kind = when (it.kind) {
                                MarkdownInlinePreferredStretchKind.PunctuationTrailing ->
                                    CjkInlineObjectPreferredStretchKind.PunctuationTrailing
                                MarkdownInlinePreferredStretchKind.Relation ->
                                    CjkInlineObjectPreferredStretchKind.Relation
                                MarkdownInlinePreferredStretchKind.BinaryOperator ->
                                    CjkInlineObjectPreferredStretchKind.BinaryOperator
                            },
                            naturalWidth = with(density) { it.naturalWidthPx.toDp() },
                            targetWidth = with(density) { it.targetWidthPx.toDp() },
                        )
                    },
                    shrinkCapacity = with(density) {
                        resolvedObject.content.trailingBoundary.shrinkCapacityPx.toDp()
                    },
                    lineEndDiscardableAdvance = with(density) {
                        resolvedObject.content.trailingBoundary.lineEndDiscardableAdvancePx.toDp()
                    },
                    preventsLineBreak = resolvedObject.content.trailingBoundary.preventsLineBreak,
                ),
                content = {
                    resolvedObject.content.content(resolvedObject.content.alternateText)
                },
    )
} + endMarkers.map { marker ->
            CjkInlineObject(
                range = TextRange(marker.endExclusive - 1, marker.endExclusive),
                advance = marker.marker.gap + marker.marker.size,
                ascent = marker.marker.size + marker.marker.offset,
                descent = 0.dp,
            ) {
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Canvas(Modifier.fillMaxSize().clipToBounds()) {
                    val side = marker.marker.size.toPx()
                    translate(left = if (rtl) 0f else marker.marker.gap.toPx()) {
                        clipRect(right = side, bottom = side) {
                            with(marker.marker.painter) { draw(Size(side, side), colorFilter = ColorFilter.tint(marker.marker.color)) }
                        }
                    }
                }
            }
        }).sortedBy { it.range.start }
