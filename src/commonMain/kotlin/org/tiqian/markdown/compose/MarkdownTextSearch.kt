package org.tiqian.markdown.compose

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.tiqian.core.LayoutResult
import org.tiqian.core.getBoundingBoxes
import org.tiqian.core.getLineForOffset
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange

/** A match in a renderer fragment. Offsets are source UTF-16 indices; the end is exclusive. */
@Immutable
class MarkdownTextSearchMatch internal constructor(
    val documentRevision: Long,
    val fragmentKey: Any,
    val startInFragment: Int,
    val endInFragment: Int,
    val excerpt: String,
)

/**
 * Find-in-document for one [TiqianMarkdownSurface], independent of native text selection.
 *
 * Queries trim/collapse Unicode whitespace and use Kotlin's case-insensitive literal matching.
 * Matches never cross fragments or inline image/math source ranges. Built-in prose, headings,
 * nested prose, table cells and captions are searchable; replaced block-slot subtrees are not.
 * Use this state on the UI thread. Closing the host's search UI should call [clear].
 */
@Stable
class MarkdownTextSearchState internal constructor(
    // BoundedSearchReveal: a missing/unplaced target or a non-completing parent relocation must
    // not retain an offscreen owner forever. Internal override allows focused lifecycle tests.
    private val revealTimeoutMillis: Long = 5_000L,
) {
    private var queryValue by mutableStateOf("")
    val query: String get() = queryValue

    var matches: List<MarkdownTextSearchMatch> by mutableStateOf(emptyList())
        private set
    var activeMatch: MarkdownTextSearchMatch? by mutableStateOf(null)
        private set
    var highlightActiveMatch: Boolean by mutableStateOf(true)

    internal var requestedOwnerKey: Any? by mutableStateOf(null)
        private set
    internal var document: MarkdownTextSearchDocument? = null
        private set
    private var documentRevision = 0L
    private var revealRequest: Deferred<Boolean>? = null

    fun setQuery(query: String) {
        if (queryValue == query) return
        cancelReveal()
        queryValue = query
        updateMatches()
    }

    /** Clear results/highlight, cancel relocation and release the retained target owner. */
    fun clear() {
        cancelReveal()
        queryValue = ""
        matches = emptyList()
    }

    /**
     * Materialize only the target owner and bring the match's first actual line into view.
     * Returns false for stale matches, interrupted or timed-out relocation. Caller cancellation
     * propagates normally. No scroll estimate, paragraph-top shortcut or selection mutation is used.
     */
    suspend fun reveal(match: MarkdownTextSearchMatch): Boolean {
        val currentDocument = document ?: return false
        if (match.documentRevision != documentRevision || matches.none { it === match }) return false
        val fragment = currentDocument.fragments[match.fragmentKey] ?: return false
        cancelReveal()
        activeMatch = match
        requestedOwnerKey = fragment.ownerKey
        return coroutineScope {
            val request = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(revealTimeoutMillis) {
                    val target = snapshotFlow {
                        currentDocument.targets[match.fragmentKey]?.takeIf { it.isReady }
                    }.filterNotNull().first()
                    val rect = target.firstLineRect(match) ?: return@withTimeoutOrNull false
                    target.requester.bringIntoView(rect)
                    currentCoroutineContext().ensureActive()
                    document === currentDocument && activeMatch === match && target.isReady &&
                        currentDocument.targets[match.fragmentKey] === target
                } ?: false
            }
            revealRequest = request
            var revealed = false
            try {
                request.await().also { revealed = it }
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } finally {
                if (revealRequest === request) {
                    revealRequest = null
                    if (!revealed) {
                        activeMatch = null
                        requestedOwnerKey = null
                    }
                }
            }
        }
    }

    internal fun attach(document: MarkdownTextSearchDocument) {
        check(this.document == null || this.document === document) {
            "MarkdownTextSearchState must belong to only one Markdown surface"
        }
        if (this.document === document) return
        cancelReveal()
        this.document = document
        documentRevision++
        updateMatches()
    }

    internal fun detach(document: MarkdownTextSearchDocument) {
        if (this.document !== document) return
        cancelReveal()
        this.document = null
        matches = emptyList()
    }

    private fun cancelReveal() {
        revealRequest?.cancel()
        revealRequest = null
        activeMatch = null
        requestedOwnerKey = null
    }

    private fun updateMatches() {
        val needle = NormalizedSearchText(query).value
        matches = if (needle.isEmpty()) emptyList() else buildList {
            document?.fragments?.values?.forEach { fragment ->
                val text = fragment.text
                var from = 0
                while (from <= text.value.length - needle.length) {
                    val found = text.value.indexOf(needle, startIndex = from, ignoreCase = true)
                    if (found < 0) break
                    val start = text.starts[found]
                    val end = text.ends[found + needle.length - 1]
                    if (fragment.atomicRanges.any { start < it.endExclusive && end > it.start }) {
                        // Rejected candidates must not hide an overlapping match in adjacent prose.
                        from = found + 1
                        continue
                    }
                    from = found + needle.length
                    var excerptStart = (start - 40).coerceAtLeast(0)
                    var excerptEnd = (end + 40).coerceAtMost(text.source.length)
                    if (excerptStart > 0 && text.source[excerptStart].isLowSurrogate()) excerptStart--
                    if (excerptEnd < text.source.length && text.source[excerptEnd].isLowSurrogate()) excerptEnd++
                    add(
                        MarkdownTextSearchMatch(
                            documentRevision = documentRevision,
                            fragmentKey = fragment.key,
                            startInFragment = start,
                            endInFragment = end,
                            excerpt = (if (excerptStart > 0) "…" else "") +
                                text.source.substring(excerptStart, excerptEnd) +
                                (if (excerptEnd < text.source.length) "…" else ""),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
fun rememberMarkdownTextSearchState(): MarkdownTextSearchState = remember { MarkdownTextSearchState() }

/** Preserve the original range of each normalized UTF-16 unit, including collapsed whitespace. */
internal class NormalizedSearchText(val source: String) {
    val starts = ArrayList<Int>()
    val ends = ArrayList<Int>()
    val value = buildString {
        var offset = 0
        while (offset < source.length) {
            val start = offset
            if (source[offset].isWhitespace()) {
                while (offset < source.length && source[offset].isWhitespace()) offset++
                if (isEmpty() || offset == source.length) continue
                append(' ')
            } else {
                append(source[offset++])
            }
            starts.add(start)
            ends.add(offset)
        }
    }
}

internal class MarkdownSearchFragment(
    val key: Any,
    val ownerKey: Any,
    val text: NormalizedSearchText,
    val atomicRanges: List<MarkdownTextRange>,
)

/** One surface binding: search metadata is collected by the existing logical fragment traversal. */
internal class MarkdownTextSearchDocument(
    val state: MarkdownTextSearchState,
    blocks: List<MarkdownBlock>,
    slots: MarkdownBlockSlots,
) {
    val fragments = linkedMapOf<Any, MarkdownSearchFragment>()
    val targets = mutableStateMapOf<Any, MarkdownTextSearchTarget>()
    val selectionFragments = blocks.markdownSelectionFragments(slots) { fragment, text, owner ->
        fragments[fragment.key] = MarkdownSearchFragment(
            key = fragment.key,
            ownerKey = owner,
            text = NormalizedSearchText(fragment.text.text),
            atomicRanges = text.spans.filter {
                it.mark is MarkdownTextMark.InlineMath || it.mark is MarkdownTextMark.InlineImage
            }.map { it.range },
        )
    }
}

internal val LocalMarkdownTextSearchDocument = compositionLocalOf<MarkdownTextSearchDocument?> { null }

@Composable
internal fun rememberMarkdownTextSearchTarget(fragmentKey: Any?): MarkdownTextSearchTarget? {
    val document = LocalMarkdownTextSearchDocument.current ?: return null
    if (fragmentKey == null || fragmentKey !in document.fragments) return null
    val target = remember(document, fragmentKey) { MarkdownTextSearchTarget(document, fragmentKey) }
    DisposableEffect(document, fragmentKey, target) {
        document.targets[fragmentKey] = target
        onDispose {
            target.detach()
            if (document.targets[fragmentKey] === target) document.targets.remove(fragmentKey)
        }
    }
    return target
}

/** Geometry and requester on the actual CjkText node; no second measurement or host coordinate guess. */
internal class MarkdownTextSearchTarget(
    private val document: MarkdownTextSearchDocument,
    private val fragmentKey: Any,
) {
    val requester = BringIntoViewRequester()
    var layout: LayoutResult? by mutableStateOf(null)
        private set
    private var resolvedText: ResolvedMarkdownText? by mutableStateOf(null)
    private var placedLayout: LayoutResult? by mutableStateOf(null)
    private var coordinates: LayoutCoordinates? = null
    val isReady: Boolean get() = layout != null && placedLayout === layout && coordinates?.isAttached == true

    fun updateLayout(layout: LayoutResult, text: ResolvedMarkdownText) {
        resolvedText = text
        this.layout = layout
    }

    fun detach() {
        coordinates = null
        placedLayout = null
    }

    val modifier = Modifier
        .bringIntoViewRequester(requester)
        .onGloballyPositioned {
            coordinates = it
            placedLayout = layout
        }
        .drawBehind {
            if (document.state.highlightActiveMatch) {
                val match = document.state.activeMatch
                if (match != null && match.fragmentKey == fragmentKey && document.state.document === document) {
                    val text = resolvedText
                    if (text != null) layout?.getBoundingBoxes(
                        text.sourceToRenderedOffset(match.startInFragment, false),
                        text.sourceToRenderedOffset(match.endInFragment, true),
                    )?.forEach { box ->
                        drawRect(
                            color = Color.Yellow.copy(alpha = 0.3f),
                            topLeft = Offset(box.left, box.top),
                            size = Size(box.right - box.left, box.bottom - box.top),
                        )
                    }
                }
            }
        }

    fun firstLineRect(match: MarkdownTextSearchMatch): Rect? {
        val layout = layout ?: return null
        val text = resolvedText ?: return null
        val start = text.sourceToRenderedOffset(match.startInFragment, false)
        val line = layout.lines.getOrNull(layout.getLineForOffset(start)) ?: return null
        val end = minOf(text.sourceToRenderedOffset(match.endInFragment, true), line.range.end)
        // Request only the engine's first matched line, even for a paragraph/match taller than
        // the viewport. Mixed inline styles can have different box heights on the same line.
        return layout.getBoundingBoxes(start, end)
            .filter { box ->
                box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                    box.right > box.left && box.bottom > box.top
            }
            .map { Rect(it.left, it.top, it.right, it.bottom) }
            .reduceOrNull { left, right ->
                Rect(
                    minOf(left.left, right.left), minOf(left.top, right.top),
                    maxOf(left.right, right.right), maxOf(left.bottom, right.bottom),
                )
            }
    }
}
