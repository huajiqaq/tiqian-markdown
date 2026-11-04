package org.tiqian.markdown.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import org.tiqian.core.LayoutResult
import org.tiqian.core.getBoundingBoxes
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownCustomBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownHtmlBlock
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownMathBlock
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.MarkdownNodeMetadata
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownSourceSpan
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownThematicBreak
import org.tiqian.markdown.MarkdownUnsupportedBlock

/**
 * One rendered AST node. [textRange] is a half-open UTF-16 range in its containing MarkdownText
 * (or code source), never in the renderer's inline-object projection. Unknown source positions
 * are explicitly identified by [MarkdownNodeMetadata.hasSourceSpan].
 */
data class MarkdownNodeClickEvent(
    val nodeType: String,
    val text: String,
    val metadata: MarkdownNodeMetadata,
    val textRange: MarkdownTextRange? = null,
    val attributes: Map<String, String> = metadata.attributes,
    val payload: Map<String, String> = metadata.payload,
    val mark: MarkdownTextMark? = null,
    val block: MarkdownBlock? = null,
    val documentRevision: Long? = null,
    val anchorBoundsInWindow: Rect? = null,
)

internal val LocalMarkdownNodeClick = staticCompositionLocalOf<State<((MarkdownNodeClickEvent) -> Boolean)?>?> { null }
internal val LocalMarkdownNodeClickTarget = staticCompositionLocalOf<MarkdownNodeClickTarget?> { null }
internal val LocalMarkdownDocumentRevision = staticCompositionLocalOf<Long?> { null }
internal val LocalMarkdownInlineNodeClick = staticCompositionLocalOf<(() -> Boolean)?> { null }

/**
 * Content slots with their own clickable controls can offer the current node to the host before
 * their default action. True means handled; do not also run the default action in that case.
 * Ordinary child controls (for example code copy) need not call this function.
 */
@Composable
fun rememberMarkdownNodeClickHandler(): (() -> Boolean)? {
    LocalMarkdownInlineNodeClick.current?.let { return it }
    val target = LocalMarkdownNodeClickTarget.current ?: return null
    return remember(target) { { target.dispatch() } }
}

internal class MarkdownNodeClickTarget(
    private val handler: State<((MarkdownNodeClickEvent) -> Boolean)?>,
    val event: State<() -> MarkdownNodeClickEvent>,
    val parent: MarkdownNodeClickTarget?,
) {
    var coordinates: LayoutCoordinates? = null

    fun dispatch(): Boolean {
        val callback = handler.value ?: return false
        val bounds = coordinates?.takeIf { it.isAttached }?.boundsInWindow()
        if (callback(event.value().copy(anchorBoundsInWindow = bounds))) return true
        return parent?.dispatch() == true
    }
}

/** Native click consumption handles child controls; declined AST nodes are offered inside-out. */
@Composable
internal fun MarkdownNodeClickScope(
    event: () -> MarkdownNodeClickEvent,
    modifier: Modifier = Modifier,
    stackVertically: Boolean = false,
    content: @Composable () -> Unit,
) {
    val handler = LocalMarkdownNodeClick.current
    if (handler?.value == null) {
        content()
        return
    }
    val parent = LocalMarkdownNodeClickTarget.current
    val currentEvent = rememberUpdatedState(event)
    val target = remember(handler, parent) { MarkdownNodeClickTarget(handler, currentEvent, parent) }
    Box(
        modifier.onGloballyPositioned { target.coordinates = it }
            .clickable(interactionSource = null, indication = null) { target.dispatch() },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalMarkdownNodeClickTarget provides target) {
            if (stackVertically) Column { content() } else content()
        }
    }
}

@Composable
internal fun rememberMarkdownTextNodeClicks(): MarkdownTextNodeClicks? {
    val handler = LocalMarkdownNodeClick.current
    if (handler?.value == null) return null
    val parent = LocalMarkdownNodeClickTarget.current
    val revision = LocalMarkdownDocumentRevision.current
    val uriHandler = rememberUpdatedState(LocalUriHandler.current)
    return remember(handler, parent, revision) { MarkdownTextNodeClicks(handler, parent, revision, uriHandler) }
}

internal class MarkdownTextNodeClicks(
    val handler: State<((MarkdownNodeClickEvent) -> Boolean)?>,
    val parent: MarkdownNodeClickTarget?,
    val revision: Long?,
    val uriHandler: State<UriHandler>,
) {
    var coordinates: LayoutCoordinates? = null
    var layout: LayoutResult? = null
    var composeLayout: TextLayoutResult? = null
    var inlineHandlers: Map<Int, () -> Boolean> = emptyMap()
    var defaultActionInProgress = false
    var sourceToRenderedOffset: (Int, Boolean) -> Int = { offset, _ -> offset }

    fun dispatch(event: MarkdownNodeClickEvent): Boolean {
        val range = event.textRange
        val coordinates = coordinates?.takeIf { it.isAttached }
        val bounds = if (range == null || coordinates == null) null else {
            val start = sourceToRenderedOffset(range.start, false)
            val end = sourceToRenderedOffset(range.endExclusive, true)
            val boxes = layout?.getBoundingBoxes(start, end)?.map { Rect(it.left, it.top, it.right, it.bottom) }
                ?: composeLayout?.let { result -> (start until end).map(result::getBoundingBox) }.orEmpty()
            boxes.filter { it.width > 0f && it.height > 0f }
                .map { box -> Rect(coordinates.localToWindow(box.topLeft), coordinates.localToWindow(box.bottomRight)) }
                .reduceOrNull { left, right ->
                    Rect(minOf(left.left, right.left), minOf(left.top, right.top),
                        maxOf(left.right, right.right), maxOf(left.bottom, right.bottom))
                }
        }
        return handler.value?.invoke(event.copy(documentRevision = revision, anchorBoundsInWindow = bounds)) == true
    }
}

internal fun MarkdownBlock.nodeClickEvent(revision: Long?): MarkdownNodeClickEvent {
    val type = when (this) {
        is MarkdownParagraph -> "Paragraph"
        is MarkdownHeading -> "Heading"
        is MarkdownBlockQuote -> "BlockQuote"
        is MarkdownList -> "List"
        is MarkdownCodeBlock -> "CodeBlock"
        is MarkdownThematicBreak -> "ThematicBreak"
        is MarkdownImageBlock -> "Image"
        is MarkdownMathBlock -> "MathBlock"
        is MarkdownHtmlBlock -> "HtmlBlock"
        is MarkdownTable -> "Table"
        is MarkdownFootnoteDefinition -> "FootnoteDefinition"
        is MarkdownCustomBlock -> kind
        is MarkdownUnsupportedBlock -> nodeType
    }
    val payload = when (this) {
        is MarkdownHeading -> mapOf("level" to level.toString()) + listOfNotNull(id?.let { "id" to it }).toMap()
        is MarkdownList -> mapOf("ordered" to ordered.toString(), "startNumber" to startNumber.toString(), "tight" to tight.toString())
        is MarkdownCodeBlock -> listOfNotNull(language?.let { "language" to it }, info?.let { "info" to it }, fileName?.let { "fileName" to it }).toMap()
        is MarkdownImageBlock -> mapOf("destination" to destination, "description" to description) + listOfNotNull(title?.let { "title" to it }).toMap()
        is MarkdownMathBlock -> mapOf("expression" to expression)
        is MarkdownHtmlBlock -> mapOf("htmlType" to htmlType.toString())
        is MarkdownFootnoteDefinition -> mapOf("label" to label, "index" to index.toString())
        is MarkdownCustomBlock -> mapOf("kind" to kind)
        else -> emptyMap()
    }
    val attributes = when (this) {
        is MarkdownImageBlock -> attributes
        is MarkdownCustomBlock -> attributes
        else -> emptyMap()
    }
    return MarkdownNodeClickEvent(
        nodeType = metadata.nodeType ?: type,
        text = readableText(),
        metadata = metadata,
        attributes = attributes + metadata.attributes,
        payload = payload + metadata.payload,
        block = this,
        documentRevision = revision,
    )
}

internal fun MarkdownBlock.readableText(): String = when (this) {
    is MarkdownParagraph -> text.value
    is MarkdownHeading -> text.value
    is MarkdownBlockQuote -> blocks.joinToString("\n") { it.readableText() }
    is MarkdownList -> items.joinToString("\n") { item -> item.blocks.joinToString("\n") { it.readableText() } }
    is MarkdownCodeBlock -> code
    is MarkdownThematicBreak -> metadata.sourceMarkdown.orEmpty()
    is MarkdownImageBlock -> description
    is MarkdownMathBlock -> expression
    is MarkdownHtmlBlock -> html
    is MarkdownTable -> rows.joinToString("\n") { row -> row.cells.joinToString("\t") { it.text.value } }
    is MarkdownFootnoteDefinition -> blocks.joinToString("\n") { it.readableText() }
    is MarkdownCustomBlock -> attributes["text"] ?: metadata.sourceMarkdown.orEmpty()
    is MarkdownUnsupportedBlock -> fallbackText
}

internal fun MarkdownTextMark?.nodeType(): String = when (this) {
    null -> "Text"
    MarkdownTextMark.Strong -> "Strong"
    MarkdownTextMark.Emphasis -> "Emphasis"
    MarkdownTextMark.Strikethrough -> "Strikethrough"
    MarkdownTextMark.InlineCode -> "InlineCode"
    MarkdownTextMark.Highlight -> "Highlight"
    MarkdownTextMark.Superscript -> "Superscript"
    MarkdownTextMark.Subscript -> "Subscript"
    MarkdownTextMark.Inserted -> "Inserted"
    MarkdownTextMark.KeyboardInput -> "KeyboardInput"
    is MarkdownTextMark.Link -> "Link"
    is MarkdownTextMark.Abbreviation -> "Abbreviation"
    is MarkdownTextMark.Footnote -> "Footnote"
    is MarkdownTextMark.Ruby -> "Ruby"
    is MarkdownTextMark.InlineMath -> "InlineMath"
    is MarkdownTextMark.InlineImage -> "Image"
    is MarkdownTextMark.Custom -> kind
}

internal fun MarkdownTextMark?.nodePayload(): Map<String, String> = when (this) {
    is MarkdownTextMark.Link -> mapOf("destination" to destination) + listOfNotNull(title?.let { "title" to it }).toMap()
    is MarkdownTextMark.Abbreviation -> mapOf("fullText" to fullText)
    is MarkdownTextMark.Footnote -> mapOf("label" to label, "index" to index.toString())
    is MarkdownTextMark.Ruby -> mapOf("annotation" to annotation)
    is MarkdownTextMark.InlineMath -> mapOf("expression" to expression)
    is MarkdownTextMark.InlineImage -> mapOf("destination" to destination, "description" to description) + listOfNotNull(title?.let { "title" to it }).toMap()
    is MarkdownTextMark.Custom -> mapOf("kind" to kind)
    else -> emptyMap()
}

internal fun generatedMarkdownNodeMetadata(parent: MarkdownNodeMetadata?, index: Int): MarkdownNodeMetadata =
    MarkdownNodeMetadata(
        key = MarkdownNodeKey(parent?.key?.parserStableKey ?: 0, parent?.key?.path.orEmpty() + listOf(-1, index)),
        sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
        hasSourceSpan = false,
    )
