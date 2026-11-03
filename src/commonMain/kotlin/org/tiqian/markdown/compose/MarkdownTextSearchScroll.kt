package org.tiqian.markdown.compose

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlin.math.abs

/**
 * Centers fully offscreen search lines, leaves visible lines still and uses the nearest edge
 * for partially visible lines. Owned scrolling installs this automatically; an external host
 * provides it before creating its scroll container, with its actual occlusion in pixels.
 * Non-search relocation is delegated unchanged to [fallback].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun rememberMarkdownTextSearchBringIntoViewSpec(
    state: MarkdownTextSearchState,
    obscuredTopPx: Float = 0f,
    obscuredBottomPx: Float = 0f,
    fallback: BringIntoViewSpec = LocalBringIntoViewSpec.current,
): BringIntoViewSpec {
    val topInset by rememberUpdatedState(obscuredTopPx)
    val bottomInset by rememberUpdatedState(obscuredBottomPx)
    val fallbackSpec by rememberUpdatedState(fallback)
    return remember(state) {
        object : BringIntoViewSpec {
            private var request: MarkdownTextSearchRelocation? = null
            private var centerMatch = false

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val current = state.relocation
                if (current == null || !current.job.isActive || state.selectionInProgress) {
                    if (state.selectionInProgress) current?.job?.cancel()
                    request = null
                    return fallbackSpec.calculateScrollDistance(offset, size, containerSize)
                }
                val bottom = (containerSize - bottomInset).coerceIn(0f, containerSize)
                val top = topInset.coerceIn(0f, bottom)
                val end = offset + size
                if (request !== current) {
                    request = current
                    // Compose recalculates every frame; entering the viewport must not undo centering.
                    centerMatch = end <= top || offset >= bottom
                }
                return when {
                    centerMatch -> offset + size / 2f - (top + bottom) / 2f
                    offset >= top && end <= bottom -> 0f
                    offset < top && end > bottom -> 0f
                    abs(offset - top) < abs(end - bottom) -> offset - top
                    else -> end - bottom
                }
            }
        }
    }
}

internal class MarkdownTextSearchRelocation(val job: Job)
