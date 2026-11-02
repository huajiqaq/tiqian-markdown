package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tiqian.compose.ruby
import org.tiqian.compose.CjkInlineBackground
import org.tiqian.compose.CjkInlineBackgroundDrawStyle
import org.tiqian.compose.CjkInlineBackgroundMetricPolicy
import org.tiqian.compose.CjkInlineDecoration
import org.tiqian.compose.CjkInlineDecorationStyle
import org.tiqian.compose.addCjkInteractionOnlyAnnotation
import org.tiqian.compose.addCjkInlineAttachment
import org.tiqian.compose.addTechnicalInlineAnnotation
import org.tiqian.core.InlineAttachment

/** Compose-facing extension points for inline objects and host-defined inline semantics. */
class MarkdownInlineSlots(
    val image: (@Composable (MarkdownTextMark.InlineImage, MarkdownStyle, TextStyle) -> MarkdownInlineContent?)? = null,
    val math: (@Composable (MarkdownTextMark.InlineMath, MarkdownStyle, TextStyle) -> MarkdownInlineContent?)? = null,
    val custom: (@Composable (MarkdownTextMark.Custom, MarkdownStyle, TextStyle) -> MarkdownCustomInlinePresentation?)? = null,
)

val DefaultMarkdownInlineSlots: MarkdownInlineSlots = MarkdownInlineSlots()

enum class MarkdownInlinePreferredStretchKind {
    PunctuationTrailing,
    Relation,
    BinaryOperator,
}

data class MarkdownInlinePreferredStretch(
    val kind: MarkdownInlinePreferredStretchKind,
    val naturalWidthPx: Float,
    val targetWidthPx: Float,
) {
    val capacityPx: Float get() = targetWidthPx - naturalWidthPx

    init {
        require(naturalWidthPx.isFinite() && naturalWidthPx >= 0f) {
            "preferred stretch natural width must be finite and non-negative"
        }
        require(targetWidthPx.isFinite() && targetWidthPx > naturalWidthPx) {
            "preferred stretch target must be finite and exceed its natural width"
        }
    }
}

/** Paragraph adjustment and independently controlled breaking at one measured object edge. */
data class MarkdownInlineBoundaryAdjustment(
    val participatesInUniformStretch: Boolean = false,
    val preferredStretch: MarkdownInlinePreferredStretch? = null,
    /** Trailing blank already included in the measured width that may be removed. */
    val shrinkCapacityPx: Float = 0f,
    /** Trailing blank removed only when this boundary becomes an automatic line end. */
    val lineEndDiscardableAdvancePx: Float = 0f,
    val preventsLineBreak: Boolean = false,
) {
    init {
        require(shrinkCapacityPx.isFinite() && shrinkCapacityPx >= 0f) {
            "shrinkCapacityPx must be finite and non-negative"
        }
        require(lineEndDiscardableAdvancePx.isFinite() && lineEndDiscardableAdvancePx >= 0f) {
            "lineEndDiscardableAdvancePx must be finite and non-negative"
        }
    }

    companion object {
        val Fixed = MarkdownInlineBoundaryAdjustment()
    }
}

data class MarkdownInlineContent(
    val alternateText: String,
    val placeholder: Placeholder,
    /** True formula/object metrics when the provider exposes a baseline. */
    val metrics: MarkdownInlineMetrics? = null,
    /**
     * Optional source-contiguous presentations used for host line breaking and width adjustment.
     * A fragment edge may be adjustment-only when its boundary sets [preventsLineBreak]. Ranges are
     * relative to the enclosing Markdown mark and must cover its source exactly, without gaps or
     * overlap.
     */
    val layoutFragments: List<MarkdownInlineFragment> = emptyList(),
    val leadingBoundary: MarkdownInlineBoundaryAdjustment = MarkdownInlineBoundaryAdjustment.Fixed,
    val trailingBoundary: MarkdownInlineBoundaryAdjustment = MarkdownInlineBoundaryAdjustment.Fixed,
    val content: @Composable (alternateText: String) -> Unit,
) {
    init {
        require(leadingBoundary.shrinkCapacityPx == 0f) {
            "leadingBoundary cannot shrink because that would move the inline object's paint origin"
        }
    }
}

data class MarkdownInlineFragment(
    val sourceRange: MarkdownTextRange,
    val content: MarkdownInlineContent,
)

data class MarkdownCustomInlinePresentation(
    val style: SpanStyle = SpanStyle(),
    val decoration: MarkdownInlineDecoration? = null,
    val onClick: ((text: String) -> Unit)? = null,
    val accessibilityLabel: ((text: String) -> String)? = null,
)

sealed interface MarkdownInlineDecoration {
    data class DashedUnderline(
        val color: Color,
        val strokeWidth: Dp = 1.dp,
        val dashWidth: Dp = 6.dp,
        val gapWidth: Dp = 4.dp,
    ) : MarkdownInlineDecoration

    data class DottedUnderline(
        val color: Color = Color.Unspecified,
        val dotDiameter: Dp = 1.5.dp,
        val gapWidth: Dp = 2.dp,
    ) : MarkdownInlineDecoration
}

internal data class ResolvedMarkdownText(
    val annotated: AnnotatedString,
    val inlineContent: Map<String, InlineTextContent>,
    val tiqianInlineObjects: List<ResolvedTiqianInlineObject>,
    val backgrounds: List<ResolvedInlineBackground>,
    val decorations: List<ResolvedInlineDecoration>,
    val interactions: List<ResolvedInlineInteraction>,
    private val replacements: List<InlineReplacement>,
) {
    fun sourceToRenderedOffset(offset: Int, end: Boolean): Int = mapOffset(offset, replacements, end)
}

internal data class ResolvedTiqianInlineObject(
    val start: Int,
    val endExclusive: Int,
    val content: MarkdownInlineContent,
)

internal data class ResolvedInlineDecoration(
    val start: Int,
    val endExclusive: Int,
    val decoration: MarkdownInlineDecoration,
)

internal data class ResolvedInlineBackground(
    val start: Int,
    val endExclusive: Int,
    val color: Color,
    val horizontalPadding: Dp,
    val verticalPadding: Dp,
    val cornerRadius: Dp,
    val continuationCornerRadius: Dp = cornerRadius,
    val adjacentSameStyleClearance: Dp,
    val drawStyle: CjkInlineBackgroundDrawStyle = CjkInlineBackgroundDrawStyle.Fill,
    val metricPolicy: CjkInlineBackgroundMetricPolicy = CjkInlineBackgroundMetricPolicy.SpanTextStyle,
)

internal fun List<ResolvedInlineBackground>.toCjkInlineBackgrounds(): List<CjkInlineBackground> =
    map { resolved ->
        CjkInlineBackground(
            range = TextRange(resolved.start, resolved.endExclusive),
            color = resolved.color,
            horizontalPadding = resolved.horizontalPadding,
            verticalPadding = resolved.verticalPadding,
            cornerRadius = resolved.cornerRadius,
            continuationCornerRadius = resolved.continuationCornerRadius,
            adjacentSameStyleClearance = resolved.adjacentSameStyleClearance,
            drawStyle = resolved.drawStyle,
            metricPolicy = resolved.metricPolicy,
        )
    }

internal fun List<ResolvedInlineDecoration>.toCjkInlineDecorations(): List<CjkInlineDecoration> =
    map { resolved ->
        when (val decoration = resolved.decoration) {
            is MarkdownInlineDecoration.DashedUnderline -> CjkInlineDecoration(
                range = TextRange(resolved.start, resolved.endExclusive),
                style = CjkInlineDecorationStyle.DashedUnderline(
                    color = decoration.color,
                    strokeWidth = decoration.strokeWidth,
                    dashLength = decoration.dashWidth,
                    gapLength = decoration.gapWidth,
                ),
            )
            is MarkdownInlineDecoration.DottedUnderline -> CjkInlineDecoration(
                range = TextRange(resolved.start, resolved.endExclusive),
                style = CjkInlineDecorationStyle.DottedUnderline(
                    color = decoration.color,
                    dotDiameter = decoration.dotDiameter,
                    gapLength = decoration.gapWidth,
                ),
            )
        }
    }

internal data class ResolvedInlineInteraction(
    val start: Int,
    val endExclusive: Int,
    val accessibilityLabel: String,
    val onClick: () -> Unit,
)

internal data class InlineReplacement(
    val spanIndex: Int,
    val range: MarkdownTextRange,
    val id: String,
    val content: MarkdownInlineContent,
)

private data class CustomPresentation(
    val spanIndex: Int,
    val presentation: MarkdownCustomInlinePresentation,
)

@Composable
internal fun resolveMarkdownText(
    text: MarkdownText,
    style: MarkdownStyle,
    textStyle: TextStyle,
    inlineSlots: MarkdownInlineSlots,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
    preparedInlineMath: Map<Int, MarkdownInlineContent> = emptyMap(),
): ResolvedMarkdownText {
    val needsInlineSlotResolution = remember(text.spans, inlineSlots) {
        text.spans.any { span ->
            when (span.mark) {
                is MarkdownTextMark.InlineMath -> true
                is MarkdownTextMark.InlineImage -> inlineSlots.image != null
                is MarkdownTextMark.Custom -> inlineSlots.custom != null
                else -> false
            }
        }
    }
    if (!needsInlineSlotResolution) {
        return remember(text, style, onLinkClick, onFootnoteClick) {
            buildResolvedMarkdownText(
                text = text,
                style = style,
                replacements = emptyList(),
                customPresentations = emptyMap(),
                onLinkClick = onLinkClick,
                onFootnoteClick = onFootnoteClick,
            )
        }
    }

    val replacements = mutableListOf<InlineReplacement>()
    val customPresentations = mutableListOf<CustomPresentation>()
    text.spans.forEachIndexed { index, span ->
        when (val mark = span.mark) {
            is MarkdownTextMark.InlineImage -> inlineSlots.image?.let { slot ->
                key("image", index) { slot(mark, style, textStyle) }?.let { content ->
                    if (content.metrics == null) return@let
                    replacements += content.toInlineReplacements(
                        spanIndex = index,
                        sourceRange = span.range,
                        idPrefix = "markdown-inline-$index",
                        sourceText = text.value.substring(span.range.start, span.range.endExclusive),
                    )
                }
            }

            is MarkdownTextMark.InlineMath -> {
                val prepared = preparedInlineMath[index]
                val content = prepared ?: (inlineSlots.math ?: DefaultMarkdownMathInlineSlot).let { slot ->
                    key("math", index) { slot(mark, style, textStyle) }
                }
                content?.let {
                    if (it.metrics == null) return@let
                    replacements += it.toInlineReplacements(
                        spanIndex = index,
                        sourceRange = span.range,
                        idPrefix = "markdown-inline-$index",
                        sourceText = text.value.substring(span.range.start, span.range.endExclusive),
                    )
                }
            }

            is MarkdownTextMark.Custom -> inlineSlots.custom?.let { slot ->
                key("custom", index) { slot(mark, style, textStyle) }?.let { presentation ->
                    customPresentations += CustomPresentation(index, presentation)
                }
            }

            else -> Unit
        }
    }

    val orderedReplacements = replacements.sortedBy { it.range.start }
    val presentationsByIndex = customPresentations.associateBy { it.spanIndex }
    return remember(
        text,
        style,
        orderedReplacements,
        presentationsByIndex,
        onLinkClick,
        onFootnoteClick,
    ) {
        buildResolvedMarkdownText(
            text = text,
            style = style,
            replacements = orderedReplacements,
            customPresentations = presentationsByIndex,
            onLinkClick = onLinkClick,
            onFootnoteClick = onFootnoteClick,
        )
    }
}

internal fun resolveMarkdownTextWithPreparedMath(
    text: MarkdownText,
    style: MarkdownStyle,
    preparedInlineMath: Map<Int, MarkdownInlineContent>,
): ResolvedMarkdownText {
    val replacements = text.spans.mapIndexedNotNull { index, span ->
        val content = preparedInlineMath[index] ?: return@mapIndexedNotNull null
        content.toInlineReplacements(
            spanIndex = index,
            sourceRange = span.range,
            idPrefix = "markdown-inline-$index",
            sourceText = text.value.substring(span.range.start, span.range.endExclusive),
        )
    }.flatten().sortedBy { it.range.start }
    return buildResolvedMarkdownText(
        text = text,
        style = style,
        replacements = replacements,
        customPresentations = emptyMap(),
        onLinkClick = null,
        onFootnoteClick = null,
    )
}

private fun MarkdownInlineContent.withSourceAlternateText(sourceText: String): MarkdownInlineContent =
    if (alternateText.isNotEmpty()) this else copy(alternateText = sourceText)

private fun MarkdownInlineContent.toInlineReplacements(
    spanIndex: Int,
    sourceRange: MarkdownTextRange,
    idPrefix: String,
    sourceText: String,
): List<InlineReplacement> {
    val whole = InlineReplacement(
        spanIndex = spanIndex,
        range = sourceRange,
        id = idPrefix,
        content = withSourceAlternateText(sourceText),
    )
    if (layoutFragments.size < 2) return listOf(whole)

    val ranges = layoutFragments.map { it.sourceRange }
    val isExactPartition = ranges.first().start == 0 &&
        ranges.last().endExclusive == sourceText.length &&
        ranges.all { it.start < it.endExclusive } &&
        ranges.zipWithNext().all { (left, right) -> left.endExclusive == right.start }
    val isSourceFaithful = isExactPartition && layoutFragments.all { fragment ->
        val range = fragment.sourceRange
        fragment.content.metrics != null &&
            fragment.content.alternateText == sourceText.substring(range.start, range.endExclusive)
    }
    if (!isSourceFaithful) return listOf(whole)

    return layoutFragments.mapIndexed { fragmentIndex, fragment ->
        InlineReplacement(
            spanIndex = spanIndex,
            range = MarkdownTextRange(
                start = sourceRange.start + fragment.sourceRange.start,
                endExclusive = sourceRange.start + fragment.sourceRange.endExclusive,
            ),
            id = "$idPrefix-$fragmentIndex",
            content = fragment.content,
        )
    }
}

internal fun MarkdownText.toAnnotatedString(
    style: MarkdownStyle,
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
): AnnotatedString = buildResolvedMarkdownText(
    text = this,
    style = style,
    replacements = emptyList(),
    customPresentations = emptyMap(),
    onLinkClick = onLinkClick,
    onFootnoteClick = onFootnoteClick,
).annotated

private fun buildResolvedMarkdownText(
    text: MarkdownText,
    style: MarkdownStyle,
    replacements: List<InlineReplacement>,
    customPresentations: Map<Int, CustomPresentation>,
    onLinkClick: ((String) -> Unit)?,
    onFootnoteClick: ((String) -> Unit)?,
): ResolvedMarkdownText {
    val nonOverlappingReplacements = replacements.fold(mutableListOf<InlineReplacement>()) { accepted, candidate ->
        if (accepted.lastOrNull()?.range?.endExclusive?.let { it > candidate.range.start } != true) {
            accepted += candidate
        }
        accepted
    }
    val replacementIndexes = nonOverlappingReplacements.mapTo(mutableSetOf()) { it.spanIndex }
    val base = text.buildInlineBase(nonOverlappingReplacements)
    val backgrounds = mutableListOf<ResolvedInlineBackground>()
    val decorations = mutableListOf<ResolvedInlineDecoration>()
    val interactions = mutableListOf<ResolvedInlineInteraction>()
    val annotated = AnnotatedString.Builder(base).apply {
        fun addRendererOwnedInlineCode(start: Int, end: Int) {
            addStyle(style.inlineCode.copy(background = Color.Unspecified), start, end)
            addTechnicalInlineAnnotation(start, end)
            if (style.inlineCode.background != Color.Unspecified) {
                backgrounds += ResolvedInlineBackground(
                    start = start,
                    endExclusive = end,
                    color = style.inlineCode.background,
                    horizontalPadding = style.inlineCodeHorizontalPadding,
                    verticalPadding = style.inlineCodeVerticalPadding,
                    cornerRadius = style.inlineCodeCornerRadius,
                    continuationCornerRadius = style.inlineCodeContinuationCornerRadius,
                    adjacentSameStyleClearance = style.adjacentSameStyleClearance,
                    metricPolicy = CjkInlineBackgroundMetricPolicy.ParagraphTextStyle,
                )
            }
        }

        text.spans.forEachIndexed { index, span ->
            val start = mapOffset(span.range.start, nonOverlappingReplacements, end = false).coerceIn(0, length)
            val end = mapOffset(span.range.endExclusive, nonOverlappingReplacements, end = true).coerceIn(start, length)
            if (start == end) return@forEachIndexed
            when (val mark = span.mark) {
                MarkdownTextMark.Strong -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, end)
                MarkdownTextMark.Emphasis -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), start, end)
                MarkdownTextMark.Strikethrough -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough),
                    start,
                    end,
                )

                MarkdownTextMark.InlineCode -> {
                    addRendererOwnedInlineCode(start, end)
                }
                MarkdownTextMark.Highlight -> {
                    addStyle(style.highlight.copy(background = Color.Unspecified), start, end)
                    if (style.highlight.background != Color.Unspecified) {
                        backgrounds += ResolvedInlineBackground(
                            start = start,
                            endExclusive = end,
                            color = style.highlight.background,
                            horizontalPadding = 0.dp,
                            verticalPadding = style.highlightVerticalPadding,
                            cornerRadius = style.highlightCornerRadius,
                            adjacentSameStyleClearance = style.adjacentSameStyleClearance,
                        )
                    }
                }
                MarkdownTextMark.Superscript -> addStyle(style.superscript, start, end)

                MarkdownTextMark.Subscript -> addStyle(style.subscript, start, end)

                MarkdownTextMark.Inserted -> addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    start,
                    end,
                )

                MarkdownTextMark.KeyboardInput -> {
                    addStyle(style.keyboardInput.copy(background = Color.Unspecified), start, end)
                    addTechnicalInlineAnnotation(start, end)
                    if (style.keyboardInputBorderColor != Color.Unspecified) {
                        backgrounds += ResolvedInlineBackground(
                            start = start,
                            endExclusive = end,
                            color = style.keyboardInputBorderColor,
                            horizontalPadding = style.keyboardInputHorizontalPadding,
                            verticalPadding = style.keyboardInputVerticalPadding,
                            cornerRadius = style.keyboardInputCornerRadius,
                            adjacentSameStyleClearance = style.adjacentSameStyleClearance,
                            drawStyle = CjkInlineBackgroundDrawStyle.Border(
                                strokeWidth = style.keyboardInputBorderWidth,
                            ),
                            metricPolicy = CjkInlineBackgroundMetricPolicy.ParagraphTextStyle,
                        )
                    }
                }

                is MarkdownTextMark.Link -> addLink(
                    LinkAnnotation.Url(
                        url = mark.destination,
                        styles = TextLinkStyles(style = style.link),
                        linkInteractionListener = onLinkClick?.let { callback ->
                            LinkInteractionListener { link ->
                                if (link is LinkAnnotation.Url) callback(link.url)
                            }
                        },
                    ),
                    start,
                    end,
                )

                is MarkdownTextMark.Abbreviation -> {
                    addStyle(style.abbreviation, start, end)
                    decorations += ResolvedInlineDecoration(
                        start = start,
                        endExclusive = end,
                        decoration = MarkdownInlineDecoration.DottedUnderline(
                            color = style.abbreviation.color,
                        ),
                    )
                }

                is MarkdownTextMark.Footnote -> {
                    addCjkInlineAttachment(InlineAttachment.Previous, start, end)
                    addCjkInteractionOnlyAnnotation(start, end)
                    addStyle(
                        style.footnoteReference.merge(
                            SpanStyle(baselineShift = BaselineShift.Superscript),
                        ),
                        start,
                        end,
                    )
                    addLink(
                        LinkAnnotation.Clickable(
                            tag = "footnote",
                            styles = TextLinkStyles(
                                style = style.link.merge(style.footnoteReference).copy(
                                    textDecoration = TextDecoration.None,
                                ),
                            ),
                            linkInteractionListener = { onFootnoteClick?.invoke(mark.label) },
                        ),
                        start,
                        end,
                    )
                }

                is MarkdownTextMark.Ruby -> Unit
                is MarkdownTextMark.InlineMath -> if (index !in replacementIndexes) {
                    addRendererOwnedInlineCode(start, end)
                }

                is MarkdownTextMark.InlineImage -> if (index !in replacementIndexes) {
                    addLink(
                        LinkAnnotation.Url(
                            url = mark.destination,
                            styles = TextLinkStyles(style = style.link),
                            linkInteractionListener = onLinkClick?.let { callback ->
                                LinkInteractionListener { link ->
                                    if (link is LinkAnnotation.Url) callback(link.url)
                                }
                            },
                        ),
                        start,
                        end,
                    )
                }

                is MarkdownTextMark.Custom -> customPresentations[index]?.presentation?.let { presentation ->
                    addStyle(presentation.style, start, end)
                    presentation.onClick?.let { callback ->
                        val markedText = text.value.substring(span.range.start, span.range.endExclusive)
                        addCjkInteractionOnlyAnnotation(start, end)
                        addLink(
                            LinkAnnotation.Clickable(
                                tag = "markdown-custom-$index",
                                styles = TextLinkStyles(),
                                linkInteractionListener = { callback(markedText) },
                            ),
                            start,
                            end,
                        )
                        interactions += ResolvedInlineInteraction(
                            start = start,
                            endExclusive = end,
                            accessibilityLabel = presentation.accessibilityLabel?.invoke(markedText) ?: markedText,
                            onClick = { callback(markedText) },
                        )
                    }
                    presentation.decoration?.let { decoration ->
                        decorations += ResolvedInlineDecoration(start, end, decoration)
                    }
                }
            }
        }
    }.toAnnotatedString()

    return ResolvedMarkdownText(
        annotated = annotated,
        inlineContent = nonOverlappingReplacements.associate { replacement ->
            replacement.id to InlineTextContent(
                placeholder = replacement.content.placeholder,
                children = replacement.content.content,
            )
        },
        tiqianInlineObjects = nonOverlappingReplacements.mapNotNull { replacement ->
            val start = mapOffset(replacement.range.start, nonOverlappingReplacements, end = false)
            val endExclusive = mapOffset(
                replacement.range.endExclusive,
                nonOverlappingReplacements,
                end = true,
            )
            replacement.content.metrics?.takeIf { endExclusive > start }?.let {
                ResolvedTiqianInlineObject(
                    start = start,
                    endExclusive = endExclusive,
                    content = replacement.content,
                )
            }
        },
        backgrounds = backgrounds,
        decorations = decorations,
        interactions = interactions,
        replacements = nonOverlappingReplacements,
    )
}

private fun MarkdownText.buildInlineBase(replacements: List<InlineReplacement>): AnnotatedString {
    val replacementByStart = replacements.associateBy { it.range.start }
    val rubyByStart = spans
        .filter { it.mark is MarkdownTextMark.Ruby }
        .associateBy { it.range.start }
    val eventStarts = (replacementByStart.keys + rubyByStart.keys).distinct().sorted()
    return AnnotatedString.Builder().apply {
        var cursor = 0
        var eventIndex = 0
        while (cursor < value.length) {
            val replacement = replacementByStart[cursor]
            if (replacement != null) {
                appendInlineContent(replacement.id, replacement.content.alternateText)
                cursor = replacement.range.endExclusive
                continue
            }
            val rubySpan = rubyByStart[cursor]
            if (rubySpan != null) {
                ruby(
                    base = value.substring(rubySpan.range.start, rubySpan.range.endExclusive),
                    ruby = (rubySpan.mark as MarkdownTextMark.Ruby).annotation,
                )
                cursor = rubySpan.range.endExclusive
                continue
            }
            while (eventIndex < eventStarts.size && eventStarts[eventIndex] <= cursor) eventIndex++
            val nextEvent = eventStarts.getOrNull(eventIndex) ?: value.length
            append(value.substring(cursor, nextEvent))
            cursor = nextEvent
        }
    }.toAnnotatedString()
}

private fun mapOffset(
    originalOffset: Int,
    replacements: List<InlineReplacement>,
    end: Boolean,
): Int {
    var delta = 0
    replacements.forEach { replacement ->
        if (originalOffset <= replacement.range.start) return originalOffset + delta
        val outputLength = replacement.content.alternateText.length
        if (originalOffset < replacement.range.endExclusive) {
            return replacement.range.start + delta + if (end) outputLength else 0
        }
        delta += outputLength - (replacement.range.endExclusive - replacement.range.start)
    }
    return originalOffset + delta
}

/**
 * Compose does not expose its underline metric. On the capability fallback path, custom dashed
 * underlines therefore become native solid underlines instead of maintaining a second guessed
 * `lineBottom - offset` geometry. The normal Tiqian path retains the requested dashed stroke.
 */
internal fun ResolvedMarkdownText.composeFallbackAnnotatedString(): AnnotatedString {
    if (decorations.isEmpty()) return annotated
    return AnnotatedString.Builder(annotated).apply {
        decorations.forEach { resolved ->
            if (resolved.decoration is MarkdownInlineDecoration.DashedUnderline) {
                addStyle(
                    SpanStyle(textDecoration = TextDecoration.Underline),
                    resolved.start,
                    resolved.endExclusive,
                )
            }
        }
    }.toAnnotatedString()
}

internal fun Modifier.markdownInlineInteractionSemantics(
    interactions: List<ResolvedInlineInteraction>,
): Modifier {
    if (interactions.isEmpty()) return this
    return semantics {
        customActions = interactions.map { interaction ->
            CustomAccessibilityAction(interaction.accessibilityLabel) {
                interaction.onClick()
                true
            }
        }
    }
}
