package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownCodeHighlightKind
import org.tiqian.markdown.MarkdownHeading

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.tiqian.clreq.KinsokuMode
import org.tiqian.core.Ic
import org.tiqian.core.ic

/** Colour-source choice; layout geometry is identical in both modes. */
enum class MarkdownPalette {
    /** Tiqian's stable built-in colour palette. */
    Default,

    /** Resolve semantic colours and base typography from the host-platform adapter. */
    Platform,
}

/** Relative type geometry for one heading level. */
@Immutable
data class MarkdownHeadingScale(
    /** Heading font size divided by the body font size. */
    val fontSizeFromBody: Float,
    /** Heading line height divided by its resolved font size. */
    val lineHeightFromHeading: Float,
) {
    init {
        require(fontSizeFromBody.isFinite() && fontSizeFromBody > 0f)
        require(lineHeightFromHeading.isFinite() && lineHeightFromHeading > 0f)
    }
}

/** The six Markdown heading levels, all resolved from [MarkdownStyle.body]. */
@Immutable
data class MarkdownHeadingScales(
    /** Large heading: the traditional 14 pt step over a 9 pt body. */
    val level1: MarkdownHeadingScale = MarkdownHeadingScale(14f / 9f, 7f / 5f),
    /** Medium heading: the traditional 12 pt step over a 9 pt body. */
    val level2: MarkdownHeadingScale = MarkdownHeadingScale(12f / 9f, 29f / 20f),
    /** Small heading: the traditional 10 pt step over a 9 pt body. */
    val level3: MarkdownHeadingScale = MarkdownHeadingScale(10f / 9f, 31f / 20f),
    /** Lower levels stay at body size and distinguish hierarchy by weight. */
    val level4: MarkdownHeadingScale = MarkdownHeadingScale(1f, 13f / 8f),
    val level5: MarkdownHeadingScale = MarkdownHeadingScale(1f, 13f / 8f),
    /** The smallest heading uses 7/8 body size while retaining one lower-heading line box. */
    val level6: MarkdownHeadingScale = MarkdownHeadingScale(7f / 8f, 13f / 7f),
) {
    internal fun level(level: Int): MarkdownHeadingScale = when (level) {
        1 -> level1
        2 -> level2
        3 -> level3
        4 -> level4
        5 -> level5
        else -> level6
    }
}

/** Gaps around one heading level, expressed in multiples of the body line height. */
@Immutable
data class MarkdownHeadingLevelSpacing(
    val beforeBodyLines: Float,
    val afterBodyLines: Float,
) {
    init {
        require(beforeBodyLines.isFinite() && beforeBodyLines >= 0f)
        require(afterBodyLines.isFinite() && afterBodyLines >= 0f)
    }
}

/** Context- and level-sensitive heading gaps. */
@Immutable
data class MarkdownHeadingSpacing(
    val level1: MarkdownHeadingLevelSpacing = MarkdownHeadingLevelSpacing(1f, 1f / 2f),
    val level2: MarkdownHeadingLevelSpacing = MarkdownHeadingLevelSpacing(1f, 1f / 2f),
    val level3: MarkdownHeadingLevelSpacing = MarkdownHeadingLevelSpacing(1f / 2f, 1f / 4f),
    val level4: MarkdownHeadingLevelSpacing = MarkdownHeadingLevelSpacing(1f / 2f, 1f / 4f),
    val level5: MarkdownHeadingLevelSpacing = MarkdownHeadingLevelSpacing(1f / 2f, 0f),
    val level6: MarkdownHeadingLevelSpacing = MarkdownHeadingLevelSpacing(1f / 2f, 0f),
    /** One shared gap between two adjacent headings. */
    val betweenBodyLines: Float = 1f,
) {
    init {
        require(betweenBodyLines.isFinite() && betweenBodyLines >= 0f)
    }

    internal fun linesBetween(previousLevel: Int?, nextLevel: Int?): Float? = when {
        previousLevel != null && nextLevel != null -> betweenBodyLines
        nextLevel != null -> level(nextLevel).beforeBodyLines
        previousLevel != null -> level(previousLevel).afterBodyLines
        else -> null
    }

    private fun level(level: Int): MarkdownHeadingLevelSpacing = when (level) {
        1 -> level1
        2 -> level2
        3 -> level3
        4 -> level4
        5 -> level5
        else -> level6
    }
}

/** Character-measure policy for the top-level prose column. */
@Immutable
data class MarkdownProseMeasure(
    /** Disable when the host already owns and constrains the prose measure. */
    val enabled: Boolean = true,
    /** Below this available measure, prose uses the whole integral-cell width. */
    val fluidStart: Ic = 32.ic,
    /** Additional prose cells admitted whenever the available measure doubles. */
    val growthPerDoubling: Ic = 8.ic,
    /** Absolute horizontal-writing limit. */
    val maximum: Ic = 48.ic,
) {
    init {
        require(fluidStart.count.isFinite() && fluidStart.count > 0f)
        require(growthPerDoubling.count.isFinite() && growthPerDoubling.count > 0f)
        require(maximum.count.isFinite() && maximum.count >= fluidStart.count)
    }
}

@Immutable
data class MarkdownStyle(
    val body: TextStyle = TextStyle(
        color = Color(0xFF202124),
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    /** Per-level visual overrides. Unspecified size and line height use [headingScales]. */
    val heading1: TextStyle = TextStyle(fontWeight = FontWeight.Medium),
    val heading2: TextStyle = TextStyle(fontWeight = FontWeight.Medium),
    val heading3: TextStyle = TextStyle(fontWeight = FontWeight.Bold),
    val heading4: TextStyle = TextStyle(fontWeight = FontWeight.Bold),
    val heading5: TextStyle = TextStyle(fontWeight = FontWeight.Bold),
    val heading6: TextStyle = TextStyle(fontWeight = FontWeight.Bold),
    val codeBlock: TextStyle = body.merge(
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 22.sp,
        ),
    ),
    val codeMeta: TextStyle = body.merge(
        TextStyle(
            fontSize = body.fontSize.scaledIfSpecified(13f / 16f),
            lineHeight = body.lineHeight.scaledIfSpecified(13f / 16f),
            fontWeight = FontWeight.Medium,
        ),
    ),
    /** All-caps language label: optically smaller than a mixed-case file name on the same line. */
    val codeLanguage: TextStyle = codeMeta.copy(
        fontSize = codeMeta.fontSize.scaledIfSpecified(7f / 8f),
        fontWeight = codeMeta.fontWeight?.let { weight ->
            FontWeight((weight.weight + 100).coerceAtMost(1000))
        },
    ),
    val link: SpanStyle = SpanStyle(
        color = Color(0xFF0969DA),
        textDecoration = TextDecoration.Underline,
    ),
    val inlineCode: SpanStyle = SpanStyle(
        background = Color(0xFFF1F3F5),
        fontFamily = FontFamily.Monospace,
        fontSize = 0.875.em,
    ),
    val inlineCodeHorizontalPadding: Dp = 4.dp,
    val inlineCodeVerticalPadding: Dp = 3.dp,
    val inlineCodeCornerRadius: Dp = 3.dp,
    /** Radius on the side where one inline-code box continues across a visual line break. */
    val inlineCodeContinuationCornerRadius: Dp = 1.dp,
    val highlight: SpanStyle = SpanStyle(background = Color(0xFFFFE58F)),
    val highlightVerticalPadding: Dp = 3.dp,
    val highlightCornerRadius: Dp = 3.dp,
    val adjacentSameStyleClearance: Dp = 1.dp,
    /** Additional abbreviation styling; the renderer supplies the conventional dotted underline. */
    val abbreviation: SpanStyle = SpanStyle(),
    /** Inline footnote reference uses the same optical-size compensation as other superscripts. */
    val footnoteReference: SpanStyle = SpanStyle(
        fontSize = 0.75.em,
        fontWeight = FontWeight.Medium,
    ),
    /** Editorial superscript: smaller type gains one weight step to preserve stroke presence. */
    val superscript: SpanStyle = SpanStyle(
        fontSize = 0.75.em,
        fontWeight = FontWeight.Medium,
        baselineShift = BaselineShift.Superscript,
    ),
    /** Editorial subscript follows the same optical-size compensation as superscript. */
    val subscript: SpanStyle = SpanStyle(
        fontSize = 0.75.em,
        fontWeight = FontWeight.Medium,
        baselineShift = BaselineShift(-0.25f),
    ),
    /** Footnote-definition text relative to the body. */
    val footnote: SpanStyle = SpanStyle(fontSize = 0.875.em),
    /** Keyboard input reuses inline-code typography; only its box paint differs. */
    val keyboardInput: SpanStyle = inlineCode.copy(background = Color.Unspecified),
    val keyboardInputBorderColor: Color = Color(0xFFD0D7DE),
    val keyboardInputBorderWidth: Dp = 1.dp,
    val keyboardInputHorizontalPadding: Dp = inlineCodeHorizontalPadding,
    val keyboardInputVerticalPadding: Dp = inlineCodeVerticalPadding,
    val keyboardInputCornerRadius: Dp = inlineCodeCornerRadius,
    /** Fallbacks used only when [body] has no resolvable line height. */
    val blockSpacing: Dp = 16.dp,
    val compactBlockSpacing: Dp = 6.dp,
    val blockSpacingBodyLines: Float = 1f / 2f,
    val compactBlockSpacingBodyLines: Float = 1f / 4f,
    /** Minimum outer gap when either side is a code, math, image, or table display block. */
    val displayBlockSpacing: Dp = 24.dp,
    val displayBlockSpacingBodyLines: Float = 1f,
    val proseMeasure: MarkdownProseMeasure = MarkdownProseMeasure(),
    /** Hosts can select marker alignment without replacing list layout. Null retains ordinal/bullet defaults. */
    val listMarkerAlignment: org.tiqian.core.LastLineAlignment? = null,
    /** Nested prose inherits the parent right edge independently of last-line alignment. */
    val insetProse: Boolean = false,
    val quoteBarColor: Color = Color(0xFFD0D7DE),
    val quoteBarWidth: Dp = 3.dp,
    val quoteContentPadding: Dp = 12.dp,
    /** Additional text style inherited by prose nested inside a block quote. */
    val quoteText: TextStyle = TextStyle(),
    /** Minimum list-body indent at ordinary measures. */
    val listContentIndent: Ic = 1.ic,
    /** Minimum list-body indent only on a long measure. */
    val listLongContentIndent: Ic = 2.ic,
    /** Long-measure boundary, shared with Tiqian's measure-adaptive strict CLREQ tier. */
    val listLongMeasureBreakpoint: Ic = KinsokuMode.MeasureAdaptive().strictAboveEm.ic,
    /** Tight items continue on the normal body-line rhythm without a synthetic gap. */
    val tightListItemSpacing: Dp = 0.dp,
    val tightListItemSpacingBodyLines: Float = 0f,
    /** Adjacent list blocks remain a group while still exposing a marker-style change. */
    val listBlockSpacing: Dp = 6.dp,
    val listBlockSpacingBodyLines: Float = 1f / 4f,
    /** Loose items retain the blank-line separation expressed by the source document. */
    val listItemSpacing: Dp = 8.dp,
    val listItemSpacingBodyLines: Float = 1f / 2f,
    val codeBackground: Color = Color(0xFFF6F8FA),
    val codeMetaBackground: Color = Color(0xFFEAEEF2),
    val codeLineNumberColor: Color = Color(0xFF6E7781),
    val codePadding: Dp = 12.dp,
    val codeCornerRadius: Dp = 8.dp,
    val codeMetaHorizontalPadding: Dp = 8.dp,
    val codeMetaVerticalPadding: Dp = 6.dp,
    val codeMetaControlSpacing: Dp = 4.dp,
    val codeCopyOuterInset: Dp = 2.dp,
    val codeCopyIconSize: Dp = 16.dp,
    val codeColumnSpacing: Dp = 12.dp,
    val codeHighlight: MarkdownCodeHighlightStyle = MarkdownCodeHighlightStyle(),
    val mathBackground: Color = Color.Unspecified,
    val math: MarkdownMathStyle = MarkdownMathStyle(),
    val tableBorderColor: Color = Color(0xFFD8DEE4),
    val tableBorderWidth: Dp = 1.dp,
    val tableCornerRadius: Dp = 8.dp,
    val tableHeaderBackground: Color = Color(0xFFF6F8FA),
    val tableCellPadding: Dp = 8.dp,
    /** Readable content floor for a non-short table column; padding is added separately. */
    val tableReadableColumnWidth: Ic = 4.ic,
    /** Preferred-width cap for one column before its text is allowed to wrap. */
    val tableColumnWidth: Dp = 160.dp,
    val tableText: TextStyle = body.merge(
        TextStyle(
            fontSize = body.fontSize.scaledIfSpecified(7f / 8f),
            lineHeight = body.lineHeight.scaledIfSpecified(7f / 8f),
        ),
    ),
    /** Corner radius for images embedded in the article; the full-screen viewer remains uncropped. */
    val imageCornerRadius: Dp = 8.dp,
    /** One physical-pixel edge that keeps pale images distinct from the article surface. */
    val imageOutlineWidth: Dp = Dp.Hairline,
    val imageOutlineColor: Color = Color.Black.copy(alpha = 0.15f),
    /** Figure/table captions are upright even when surrounding prose is emphasized. */
    val caption: TextStyle = body.merge(
        TextStyle(
            fontSize = body.fontSize.scaledIfSpecified(13f / 16f),
            lineHeight = body.lineHeight.scaledIfSpecified(13f / 16f),
            fontStyle = FontStyle.Normal,
        ),
    ),
    /** Figure/table captions inset by half a body ideographic character on both sides. */
    val captionHorizontalIndent: Ic = 0.5f.ic,
    /** A narrow image stays centred while its caption keeps a readable line measure. */
    val figureCaptionMinimumWidth: Ic = 12.ic,
    val thematicBreakColor: Color = Color(0xFFD8DEE4),
    val headingScales: MarkdownHeadingScales = MarkdownHeadingScales(),
    val headingSpacing: MarkdownHeadingSpacing = MarkdownHeadingSpacing(),
)

/** Scales article rhythm while preserving every typography and colour decision. */
fun MarkdownStyle.withBlockSpacingScale(scale: Float): MarkdownStyle {
    require(scale.isFinite() && scale >= 0f) { "scale must be finite and non-negative" }

    fun MarkdownHeadingLevelSpacing.scaled() = copy(
        beforeBodyLines = beforeBodyLines * scale,
        afterBodyLines = afterBodyLines * scale,
    )
    return copy(
        blockSpacing = blockSpacing * scale,
        compactBlockSpacing = compactBlockSpacing * scale,
        blockSpacingBodyLines = blockSpacingBodyLines * scale,
        compactBlockSpacingBodyLines = compactBlockSpacingBodyLines * scale,
        displayBlockSpacing = displayBlockSpacing * scale,
        displayBlockSpacingBodyLines = displayBlockSpacingBodyLines * scale,
        tightListItemSpacing = tightListItemSpacing * scale,
        tightListItemSpacingBodyLines = tightListItemSpacingBodyLines * scale,
        listBlockSpacing = listBlockSpacing * scale,
        listBlockSpacingBodyLines = listBlockSpacingBodyLines * scale,
        listItemSpacing = listItemSpacing * scale,
        listItemSpacingBodyLines = listItemSpacingBodyLines * scale,
        headingSpacing = headingSpacing.copy(
            level1 = headingSpacing.level1.scaled(),
            level2 = headingSpacing.level2.scaled(),
            level3 = headingSpacing.level3.scaled(),
            level4 = headingSpacing.level4.scaled(),
            level5 = headingSpacing.level5.scaled(),
            level6 = headingSpacing.level6.scaled(),
            betweenBodyLines = headingSpacing.betweenBodyLines * scale,
        ),
    )
}

/** Scales body typography in the library so packed [TextUnit] arithmetic stays out of host code. */
fun TextStyle.withMarkdownReadingScale(
    fontSizeScale: Float,
    lineHeightFromFontSize: Float,
): TextStyle {
    require(fontSizeScale.isFinite() && fontSizeScale > 0f) {
        "fontSizeScale must be finite and positive"
    }
    require(lineHeightFromFontSize.isFinite() && lineHeightFromFontSize > 0f) {
        "lineHeightFromFontSize must be finite and positive"
    }
    require(fontSize.isSp) {
        "TextStyle.fontSize must be sp so Markdown reading scale has a stable body basis"
    }
    // `ExplicitSpReadingScale`: body size is the absolute basis for headings, footnotes and
    // measure-in-ic calculations. Rebuild an sp value explicitly instead of applying generic
    // packed TextUnit arithmetic, so the host cannot accidentally pass an em-typed body through
    // platform typography merging.
    val scaledFontSize = (fontSize.value * fontSizeScale).sp
    return copy(
        fontSize = scaledFontSize,
        lineHeight = (scaledFontSize.value * lineHeightFromFontSize).sp,
    )
}

@Immutable
data class MarkdownCodeHighlightStyle(
    val comment: SpanStyle = SpanStyle(color = Color(0xFF6A737D)),
    val keyword: SpanStyle = SpanStyle(color = Color(0xFFCF222E)),
    val string: SpanStyle = SpanStyle(color = Color(0xFF0A3069)),
    val number: SpanStyle = SpanStyle(color = Color(0xFF0550AE)),
    val type: SpanStyle = SpanStyle(color = Color(0xFF8250DF)),
    val function: SpanStyle = SpanStyle(color = Color(0xFF8250DF)),
    val property: SpanStyle = SpanStyle(color = Color(0xFF953800)),
    val annotation: SpanStyle = SpanStyle(color = Color(0xFF8250DF)),
    val variable: SpanStyle = SpanStyle(),
    val operator: SpanStyle = SpanStyle(),
    val punctuation: SpanStyle = SpanStyle(),
    val tag: SpanStyle = SpanStyle(color = Color(0xFF116329)),
    val attribute: SpanStyle = SpanStyle(color = Color(0xFF8250DF)),
    val constant: SpanStyle = SpanStyle(color = Color(0xFF0550AE)),
    val escape: SpanStyle = SpanStyle(color = Color(0xFF953800)),
    val markup: SpanStyle = SpanStyle(),
) {
    internal fun span(kind: MarkdownCodeHighlightKind): SpanStyle = when (kind) {
        MarkdownCodeHighlightKind.Comment -> comment
        MarkdownCodeHighlightKind.Keyword -> keyword
        MarkdownCodeHighlightKind.String -> string
        MarkdownCodeHighlightKind.Number -> number
        MarkdownCodeHighlightKind.Type -> type
        MarkdownCodeHighlightKind.Function -> function
        MarkdownCodeHighlightKind.Property -> property
        MarkdownCodeHighlightKind.Annotation -> annotation
        MarkdownCodeHighlightKind.Variable -> variable
        MarkdownCodeHighlightKind.Operator -> operator
        MarkdownCodeHighlightKind.Punctuation -> punctuation
        MarkdownCodeHighlightKind.Tag -> tag
        MarkdownCodeHighlightKind.Attribute -> attribute
        MarkdownCodeHighlightKind.Constant -> constant
        MarkdownCodeHighlightKind.Escape -> escape
        MarkdownCodeHighlightKind.Markup -> markup
    }
}

/** Resolves heading typography for hosts that place paragraphs outside the Markdown renderer. */
fun MarkdownStyle.heading(level: Int): TextStyle {
    val override = when (level) {
        1 -> heading1
        2 -> heading2
        3 -> heading3
        4 -> heading4
        5 -> heading5
        else -> heading6
    }
    val scale = headingScales.level(level)
    val bodyFontSize = body.fontSize.requireSp("MarkdownStyle.body.fontSize")
    val resolvedFontSize = override.fontSize.resolveAgainst(
        base = bodyFontSize,
        fallback = bodyFontSize * scale.fontSizeFromBody,
        label = "MarkdownStyle.heading$level.fontSize",
    )
    val resolvedLineHeight = override.lineHeight.resolveAgainst(
        base = resolvedFontSize,
        fallback = resolvedFontSize * scale.lineHeightFromHeading,
        label = "MarkdownStyle.heading$level.lineHeight",
    )
    return body.merge(override).copy(
        fontSize = resolvedFontSize,
        lineHeight = resolvedLineHeight,
    )
}

internal fun MarkdownStyle.headingGapBodyLines(previous: MarkdownBlock, next: MarkdownBlock): Float? =
    headingGapBodyLines(
        previousLevel = (previous as? MarkdownHeading)?.level,
        nextLevel = (next as? MarkdownHeading)?.level,
    )

/** Heading spacing in body lines; null means the host should use ordinary block spacing. */
fun MarkdownStyle.headingGapBodyLines(previousLevel: Int?, nextLevel: Int?): Float? =
    headingSpacing.linesBetween(previousLevel, nextLevel)

/** Resolves the inherited prose style inside a quote. */
fun MarkdownStyle.quoteContentStyle(): MarkdownStyle {
    val bodyFontSize = body.fontSize.requireSp("MarkdownStyle.body.fontSize")
    val resolvedFontSize = quoteText.fontSize.resolveAgainst(
        base = bodyFontSize,
        fallback = bodyFontSize,
        label = "MarkdownStyle.quoteText.fontSize",
    )
    val inheritedLineHeight = when {
        body.lineHeight.isSp -> body.lineHeight
        body.lineHeight.isEm -> resolvedFontSize * body.lineHeight.value
        else -> TextUnit.Unspecified
    }
    val resolvedLineHeight = quoteText.lineHeight.resolveAgainst(
        base = resolvedFontSize,
        fallback = inheritedLineHeight,
        label = "MarkdownStyle.quoteText.lineHeight",
    )
    return copy(
        insetProse = true,
        body = body.merge(quoteText).copy(
            fontSize = resolvedFontSize,
            lineHeight = resolvedLineHeight,
        ),
    )
}

/** Resolves note typography independently of its inline, sheet, or page-bottom placement. */
fun MarkdownStyle.footnoteContentTextStyle(): TextStyle {
    val bodyFontSize = body.fontSize.requireSp("MarkdownStyle.body.fontSize")
    val resolvedFontSize = footnote.fontSize.resolveAgainst(
        base = bodyFontSize,
        fallback = bodyFontSize * 7f / 8f,
        label = "MarkdownStyle.footnote.fontSize",
    )
    val resolvedLineHeight = when {
        body.lineHeight.isSp -> body.lineHeight * 7f / 8f
        body.lineHeight.isEm -> body.lineHeight
        else -> TextUnit.Unspecified
    }
    return body.merge(footnote).copy(
        fontSize = resolvedFontSize,
        lineHeight = resolvedLineHeight,
    )
}

/** Body line height in sp when it is explicit enough to anchor block spacing. */
internal fun MarkdownStyle.bodyLineHeightSpOrNull(): TextUnit? {
    val bodyFontSize = body.fontSize.takeIf { it.isSp } ?: return null
    return when {
        body.lineHeight.isSp -> body.lineHeight
        body.lineHeight.isEm -> bodyFontSize * body.lineHeight.value
        else -> null
    }
}

private fun TextUnit.resolveAgainst(
    base: TextUnit,
    fallback: TextUnit,
    label: String,
): TextUnit = when {
    isSp -> this
    isEm -> base * value
    this == TextUnit.Unspecified -> fallback
    else -> error("$label must be sp, em, or unspecified")
}

private fun TextUnit.requireSp(label: String): TextUnit {
    require(isSp) { "$label must be sp so relative heading sizes have a stable body basis" }
    return this
}

private fun TextUnit.scaledIfSpecified(scale: Float): TextUnit =
    if (this == TextUnit.Unspecified) this else this * scale
