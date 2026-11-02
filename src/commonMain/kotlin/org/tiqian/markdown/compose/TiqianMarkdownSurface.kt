package org.tiqian.markdown.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import org.tiqian.compose.CjkSelectionDocument
import org.tiqian.compose.CjkSelectionContainer
import org.tiqian.compose.CjkSelectionState
import org.tiqian.compose.rememberCjkSelectionState
import org.tiqian.markdown.DefaultMarkdownCodeHighlighter
import org.tiqian.markdown.MarkdownCodeHighlighter
import org.tiqian.markdown.MarkdownFootnotePlacement
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownRenderDocument
import org.tiqian.markdown.placeFootnotes

/**
 * Complete article surface around [TiqianMarkdown].
 *
 * The renderer owns scrolling, whole-document selection and its image-viewer session. Hosts only
 * adapt their document model and supply platform services through slots and [imageProvider].
 * Pass [selectionState] to read the current selection, for example from host context-menu items.
 * Optional [searchState] finds and reveals renderer-owned prose without modifying that selection.
 */
@Composable
fun TiqianMarkdownSurface(
    document: MarkdownRenderDocument,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = rememberMarkdownStyle(),
    slots: MarkdownBlockSlots = DefaultMarkdownBlockSlots,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
    scrollState: ScrollState,
    selectable: Boolean = true,
    selectionState: CjkSelectionState = rememberCjkSelectionState(),
    enableScroll: Boolean = true,
    imageProvider: MarkdownImageProvider? = null,
    imageViewerPresentation: MarkdownImageViewerPresentation = MarkdownImageViewerPresentation.PlatformWindow,
    imageViewerActions: @Composable RowScope.(MarkdownImageBlock) -> Unit = {},
    onLinkClick: ((String) -> Unit)? = null,
    onFootnoteClick: ((String) -> Unit)? = null,
    footnotePlacement: MarkdownFootnotePlacement = MarkdownFootnotePlacement.AfterBlock,
    codeHighlighter: MarkdownCodeHighlighter = DefaultMarkdownCodeHighlighter,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    searchState: MarkdownTextSearchState? = null,
) {
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    var viewportHeightPx by remember { mutableIntStateOf(windowHeightPx) }
    var viewportTopInWindowPx by remember { mutableFloatStateOf(0f) }
    val requestedOwnerKeys = remember(document) { mutableStateMapOf<Any, Unit>() }
    val placedBlocks = remember(document, footnotePlacement) {
        markdownTraceSection("TiqianMarkdownSurface.placeFootnotes") {
            document.placeFootnotes(footnotePlacement)
        }
    }
    val searchDocument = if (searchState == null) null else remember(searchState, placedBlocks, slots) {
        MarkdownTextSearchDocument(searchState, placedBlocks, slots)
    }
    // Selection clears on document identity changes. Search/slot toggles do not change its
    // logical text projection, so keep this identity tied only to the placed document.
    val selectionDocument = remember(placedBlocks) {
        markdownTraceSection("TiqianMarkdown.selectionDocument") {
            CjkSelectionDocument(searchDocument?.selectionFragments ?: placedBlocks.markdownSelectionFragments())
        }
    }
    if (searchDocument != null) {
        DisposableEffect(searchDocument) {
            searchDocument.state.attach(searchDocument)
            onDispose { searchDocument.state.detach(searchDocument) }
        }
    }
    val currentScrollOffsetPx = remember(scrollState) { { scrollState.value } }
    val deferredLayout = MarkdownDeferredLayout(
        viewportHeightPx = markdownViewportHeightPx(
            surfaceOwnsScroll = enableScroll,
            measuredSurfaceHeightPx = viewportHeightPx,
            windowHeightPx = windowHeightPx,
        ),
        // `OwnedScrollViewportCoordinates`: an internally scrolling surface can sit below fixed
        // host chrome, so its viewport is [top, top + height] rather than [0, height]. An
        // externally scrolling surface still uses the window viewport and therefore keeps zero.
        viewportTopInWindowPx = markdownSurfaceViewportTopInWindowPx(
            surfaceOwnsScroll = enableScroll,
            measuredSurfaceTopInWindowPx = viewportTopInWindowPx,
        ),
        scrollInProgress = scrollState.isScrollInProgress,
        scrollOffsetPx = currentScrollOffsetPx,
        requestedOwnerKeys = (requestedOwnerKeys.keys + selectionState.activeGestureOwnerKeys).let { owners ->
            searchState?.requestedOwnerKey?.let { owners + it } ?: owners
        },
        onMaterializeOwner = { ownerKey ->
            if (requestedOwnerKeys.containsKey(ownerKey)) false else {
                requestedOwnerKeys[ownerKey] = Unit
                true
            }
        },
        compensateScrollBy = { deltaPx -> scrollState.dispatchRawDelta(deltaPx.toFloat()) },
    )
    val article: @Composable () -> Unit = {
        CompositionLocalProvider(
            LocalMarkdownDeferredLayout provides deferredLayout,
            LocalMarkdownTextSearchDocument provides searchDocument,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { if (enableScroll) it.verticalScroll(scrollState) else it },
            ) {
                header?.invoke()
                TiqianMarkdown(
                    document = document,
                    modifier = Modifier.fillMaxWidth(),
                    style = style,
                    slots = slots,
                    inlineSlots = inlineSlots,
                    onLinkClick = onLinkClick,
                    onFootnoteClick = onFootnoteClick,
                    footnotePlacement = footnotePlacement,
                    codeHighlighter = codeHighlighter,
                )
                footer?.invoke()
            }
        }
    }
    val selectableArticle: @Composable () -> Unit = {
        if (selectable) {
            CjkSelectionContainer(
                state = selectionState,
                scrollState = scrollState,
                document = selectionDocument,
                content = article,
            )
        } else {
            article()
        }
    }
    val viewportModifier = modifier
        .onSizeChanged { viewportHeightPx = it.height }
        .let { measuredModifier ->
            if (enableScroll) {
                measuredModifier.onGloballyPositioned { coordinates ->
                    val top = coordinates.positionInWindow().y
                    if (top != viewportTopInWindowPx) viewportTopInWindowPx = top
                }
            } else {
                measuredModifier
            }
        }

    if (imageProvider == null) {
        Box(viewportModifier) { selectableArticle() }
    } else {
        MarkdownImageViewerHost(
            imageProvider = imageProvider,
            modifier = viewportModifier,
            presentation = imageViewerPresentation,
            viewerActions = imageViewerActions,
        ) {
            selectableArticle()
        }
    }
}

internal fun markdownViewportHeightPx(
    surfaceOwnsScroll: Boolean,
    measuredSurfaceHeightPx: Int,
    windowHeightPx: Int,
): Int = if (surfaceOwnsScroll) {
    measuredSurfaceHeightPx.takeIf { it > 0 } ?: windowHeightPx
} else {
    // An externally scrolled surface is measured at its complete estimated content height, not at
    // the clipping ancestor's viewport. Window height is the stable observable viewport available
    // at this boundary; global block coordinates still account for headers and other host content.
    windowHeightPx
}.coerceAtLeast(1)

internal fun markdownSurfaceViewportTopInWindowPx(
    surfaceOwnsScroll: Boolean,
    measuredSurfaceTopInWindowPx: Float,
): Float = if (surfaceOwnsScroll) measuredSurfaceTopInWindowPx else 0f
