package org.tiqian.markdown

/** A renderer-owned document model. Parser nodes never cross this boundary. */
data class MarkdownRenderDocument(
    val blocks: List<MarkdownBlock>,
    val issues: List<MarkdownCapabilityIssue> = emptyList(),
    val metadata: MarkdownNodeMetadata? = null,
    val revision: Long? = null,
)

data class MarkdownNodeKey(
    val parserStableKey: Int,
    val path: List<Int>,
)

data class MarkdownSourceSpan(
    val startOffset: Int,
    val endOffset: Int,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int,
)

data class MarkdownNodeMetadata(
    val key: MarkdownNodeKey,
    val sourceSpan: MarkdownSourceSpan,
    /** Exact Markdown covered by this node when the compiler was given the source text. */
    val sourceMarkdown: String? = null,
    /** Original parser type, attributes and semantic data; visual marks remain independent. */
    val nodeType: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val payload: Map<String, String> = emptyMap(),
    val persistentId: Long? = null,
    /** False when the adapter has no exact source position, including generated display nodes. */
    val hasSourceSpan: Boolean = true,
    /** Lossless public semantic fields, kept separate from interpreted visual attributes. */
    val properties: Map<String, MarkdownNodeValue> = emptyMap(),
    /** Original reading-order children, including definitions which have no hit region. */
    val children: List<MarkdownNodeMetadata> = emptyList(),
    val contentHash: Long? = null,
)

/** Parser-independent snapshot values; adapters defensively copy lists and maps. */
sealed interface MarkdownNodeValue {
    data object Null : MarkdownNodeValue
    data class Text(val value: String) : MarkdownNodeValue
    data class Boolean(val value: kotlin.Boolean) : MarkdownNodeValue
    data class Integer(val value: Long) : MarkdownNodeValue
    data class Decimal(val value: Double) : MarkdownNodeValue
    data class ListValue(val values: List<MarkdownNodeValue>) : MarkdownNodeValue
    data class ObjectValue(val values: Map<String, MarkdownNodeValue>) : MarkdownNodeValue
}

sealed interface MarkdownBlock {
    val metadata: MarkdownNodeMetadata
}

data class MarkdownParagraph(
    val text: MarkdownText,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownHeading(
    val level: Int,
    val id: String?,
    val text: MarkdownText,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownBlockQuote(
    val blocks: List<MarkdownBlock>,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownList(
    val ordered: Boolean,
    val startNumber: Int,
    val tight: Boolean,
    val items: List<MarkdownListItem>,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownListItem(
    val blocks: List<MarkdownBlock>,
    val task: MarkdownTaskState? = null,
    val metadata: MarkdownNodeMetadata,
)

enum class MarkdownTaskState {
    Checked,
    Unchecked,
}

data class MarkdownCodeBlock(
    val code: String,
    val language: String?,
    val info: String?,
    override val metadata: MarkdownNodeMetadata,
    /** Optional display name supplied by the adapter; the renderer never guesses it from [info]. */
    val fileName: String? = null,
    val showLineNumbers: Boolean = true,
    /** Source ranges classified by a parser or highlighter without leaking its node types. */
    val highlights: List<MarkdownCodeHighlight> = emptyList(),
    val startLineNumber: Int = 1,
    val highlightedLines: List<IntRange> = emptyList(),
) : MarkdownBlock

data class MarkdownCodeHighlight(
    val range: MarkdownTextRange,
    val kind: MarkdownCodeHighlightKind,
    val metadata: MarkdownNodeMetadata? = null,
)

enum class MarkdownCodeHighlightKind {
    Comment,
    Keyword,
    String,
    Number,
    Type,
    Function,
    Property,
    Annotation,
    Variable,
    Operator,
    Punctuation,
    Tag,
    Attribute,
    Constant,
    Escape,
    Markup,
}

data class MarkdownThematicBreak(
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownImageBlock(
    val destination: String,
    val description: String,
    val title: String?,
    val widthPixels: Int?,
    val heightPixels: Int?,
    override val metadata: MarkdownNodeMetadata,
    val attributes: Map<String, String> = emptyMap(),
    val caption: MarkdownText? = null,
) : MarkdownBlock

data class MarkdownMathBlock(
    val expression: String,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownHtmlBlock(
    val html: String,
    val htmlType: Int,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownTable(
    val columnAlignments: List<MarkdownTableAlignment>,
    val rows: List<MarkdownTableRow>,
    override val metadata: MarkdownNodeMetadata,
    val caption: MarkdownText? = null,
) : MarkdownBlock

enum class MarkdownTableAlignment {
    Start,
    Center,
    End,
    Unspecified,
}

data class MarkdownTableRow(
    val cells: List<MarkdownTableCell>,
    val header: Boolean,
    val metadata: MarkdownNodeMetadata,
    /** Optional table head/body identity, retained while rows stay in display order. */
    val sectionMetadata: MarkdownNodeMetadata? = null,
)

data class MarkdownTableCell(
    val text: MarkdownText,
    val alignment: MarkdownTableAlignment,
    val header: Boolean,
    val metadata: MarkdownNodeMetadata,
)

data class MarkdownFootnoteDefinition(
    val label: String,
    val index: Int,
    val blocks: List<MarkdownBlock>,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

/** Host-owned content identified without carrying a parser node or Compose content in the model. */
data class MarkdownCustomBlock(
    val kind: String,
    val attributes: Map<String, String> = emptyMap(),
    override val metadata: MarkdownNodeMetadata,
    val blocks: List<MarkdownBlock> = emptyList(),
) : MarkdownBlock

/**
 * A loss-shaped boundary for syntax that this version does not render natively yet.
 * Hosts can replace it through a block slot; the default renderer keeps readable content visible.
 */
data class MarkdownUnsupportedBlock(
    val nodeType: String,
    val fallbackText: String,
    override val metadata: MarkdownNodeMetadata,
) : MarkdownBlock

data class MarkdownText(
    val value: String,
    val spans: List<MarkdownTextSpan> = emptyList(),
    val issues: List<MarkdownCapabilityIssue> = emptyList(),
)

data class MarkdownTextRange(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "start must not be negative" }
        require(endExclusive >= start) { "endExclusive must not precede start" }
    }
}

data class MarkdownTextSpan(
    val range: MarkdownTextRange,
    /** Null retains an ordinary or unsupported node without inventing a visual mark. */
    val mark: MarkdownTextMark? = null,
    val metadata: MarkdownNodeMetadata? = null,
)

sealed interface MarkdownTextMark {
    data object Strong : MarkdownTextMark
    data object Emphasis : MarkdownTextMark
    data object Strikethrough : MarkdownTextMark
    data object InlineCode : MarkdownTextMark
    data object Highlight : MarkdownTextMark
    data object Superscript : MarkdownTextMark
    data object Subscript : MarkdownTextMark
    data object Inserted : MarkdownTextMark
    data object KeyboardInput : MarkdownTextMark
    data object Spoiler : MarkdownTextMark

    data class Citation(val key: String) : MarkdownTextMark

    data class Link(
        val destination: String,
        val title: String? = null,
    ) : MarkdownTextMark

    data class Abbreviation(
        val fullText: String,
    ) : MarkdownTextMark

    data class Footnote(
        val label: String,
        val index: Int,
    ) : MarkdownTextMark

    data class Ruby(
        val annotation: String,
    ) : MarkdownTextMark

    data class InlineMath(
        val expression: String,
    ) : MarkdownTextMark

    data class InlineImage(
        val destination: String,
        val description: String,
        val title: String? = null,
        val widthPixels: Int? = null,
        val heightPixels: Int? = null,
        val attributes: Map<String, String> = emptyMap(),
    ) : MarkdownTextMark

    /** Host-defined inline semantics retained as pure data across the renderer boundary. */
    data class Custom(
        val kind: String,
        val attributes: Map<String, String> = emptyMap(),
    ) : MarkdownTextMark
}

enum class MarkdownCapabilityIssueKind {
    UnsupportedBlock,
    UnsupportedInline,
}

data class MarkdownCapabilityIssue(
    val kind: MarkdownCapabilityIssueKind,
    val nodeType: String,
    val sourceSpan: MarkdownSourceSpan,
    val textRange: MarkdownTextRange? = null,
)
