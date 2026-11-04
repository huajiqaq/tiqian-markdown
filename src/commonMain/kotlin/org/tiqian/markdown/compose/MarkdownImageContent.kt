package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownImageBlock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize

/** Loading information supplied by the host's image library. */
enum class MarkdownImageLoadState {
    Loading,
    Success,
    Error,
}

/**
 * A decoded image presentation. The host keeps ownership of fetching, authentication, caches and
 * animated/vector formats; the renderer owns figure and viewer geometry.
 */
@Immutable
class MarkdownImageContent(
    val intrinsicSize: IntSize?,
    val loadState: MarkdownImageLoadState,
    val progress: Float? = null,
    val content: @Composable (Modifier) -> Unit,
)

typealias MarkdownImageProvider = @Composable (MarkdownImageBlock) -> MarkdownImageContent

internal val LocalMarkdownImageProvider = staticCompositionLocalOf<MarkdownImageProvider?> { null }
