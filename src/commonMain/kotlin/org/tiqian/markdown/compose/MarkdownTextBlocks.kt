package org.tiqian.markdown.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
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
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.getBoundingBoxes
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
    val tiqianInlineObjects = remember(resolved.tiqianInlineObjects, density) {
        resolved.toCjkInlineObjects(density)
    }
    val inlineDecorations = remember(resolved.decorations) {
        resolved.decorations.toCjkInlineDecorations()
    }
    val inlineBackgrounds = remember(resolved.backgrounds) {
        resolved.backgrounds.toCjkInlineBackgrounds()
    }
    // CjkText 的纯样式/装饰更新可能只重绘而不回调布局。保留上次真实结果，
    // 包括尚无 Custom 的正文；新排版会在绘制前通过同一个回调更新结果。
    val markerLayout = remember { mutableStateOf<LayoutResult?>(null) }
    val onTextLayout: (LayoutResult) -> Unit = remember {
        { layout -> markerLayout.value = layout }
    }
    val textModifier = modifier
        .then(if (resolved.endMarkers.isEmpty()) Modifier else Modifier.drawBehind {
            val layout = markerLayout.value ?: return@drawBehind
            for (resolvedMarker in resolved.endMarkers) {
                val end = resolvedMarker.endExclusive
                // 每个 Custom 独立消费已映射的末字符框；跨行范围只画一次。
                val box = layout.getBoundingBoxes(end - 1, end).lastOrNull() ?: continue
                val marker = resolvedMarker.marker
                val markerSize = marker.size.toPx().coerceAtMost(size.width)
                if (!markerSize.isFinite() || markerSize <= 0f) continue
                val preferredLeft = box.right + marker.gap.toPx()
                val left = preferredLeft.coerceIn(0f, size.width - markerSize)
                // 行尾放不下时收进绘制区域并贴在末字下方，不为标记重新断行。
                val top = box.bottom - marker.offset.toPx() - if (left == preferredLeft) markerSize / 2f else 0f
                translate(left = left, top = top) {
                    // Painter 的笔画也不能越过分配的标记框或列右边界。
                    clipRect(right = markerSize, bottom = markerSize) {
                        with(marker.painter) {
                            draw(Size(markerSize, markerSize), colorFilter = ColorFilter.tint(marker.color))
                        }
                    }
                }
            }
        })
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
            precomputedLayout = precomputed?.layout,
            onTextLayout = onTextLayout,
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
            precomputedLayout = precomputed?.layout,
            onTextLayout = onTextLayout,
        )
    }
}

internal fun ResolvedMarkdownText.toCjkInlineObjects(
    density: androidx.compose.ui.unit.Density,
): List<CjkInlineObject> = tiqianInlineObjects.map { resolvedObject ->
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
}
