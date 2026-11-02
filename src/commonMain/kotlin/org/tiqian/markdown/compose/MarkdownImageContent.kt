package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownImageBlock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

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
    /** 正文预览宽度上限，不改变原图尺寸、图注或查看器。 */
    val maxDisplayWidth: Dp = Dp.Infinity,
    /** 正文预览高度上限，已知比例时等比缩小；默认不限制。 */
    val maxDisplayHeight: Dp = Dp.Infinity,
    val content: @Composable (Modifier) -> Unit,
) {
    init {
        require(maxDisplayWidth >= 0.dp && maxDisplayHeight >= 0.dp)
    }
}

typealias MarkdownImageProvider = @Composable (MarkdownImageBlock) -> MarkdownImageContent

internal val LocalMarkdownImageProvider = staticCompositionLocalOf<MarkdownImageProvider?> { null }

@Composable
internal fun markdownImageContent(block: MarkdownImageBlock): MarkdownImageContent? =
    LocalMarkdownImageProvider.current?.invoke(block)
