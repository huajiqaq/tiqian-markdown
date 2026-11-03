package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownImageBlock

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.zIndex

private const val MARKDOWN_IMAGE_VIEWER_FADE_DURATION_MILLIS = 180

/** Where the viewer overlay is laid out relative to its host. */
enum class MarkdownImageViewerPresentation {
    /** The host itself already occupies the complete viewport. */
    HostBounds,

    /** Use a platform popup when Markdown is nested inside a scrolling article body. */
    PlatformWindow,
}

@Stable
class MarkdownImageViewerState internal constructor() {
    internal var activeSession: MarkdownImageViewerSession? by mutableStateOf(null)
        private set

    val activeImage: MarkdownImageBlock?
        get() = activeSession?.activeImage

    val isVisible: Boolean get() = activeSession != null

    fun show(image: MarkdownImageBlock, gallery: List<MarkdownImageBlock> = listOf(image)) {
        val normalizedGallery = gallery
            .distinctBy { it.metadata.key }
            .let { images -> if (images.any { it.metadata.key == image.metadata.key }) images else images + image }
        activeSession = MarkdownImageViewerSession(
            images = normalizedGallery,
            initialIndex = normalizedGallery.indexOfFirst { it.metadata.key == image.metadata.key },
        )
    }

    fun dismiss() {
        activeSession = null
    }
}

@Stable
internal class MarkdownImageViewerSession(
    val images: List<MarkdownImageBlock>,
    initialIndex: Int,
) {
    init {
        require(images.isNotEmpty()) { "An image viewer session needs at least one image" }
    }

    var activeIndex by mutableIntStateOf(initialIndex.coerceIn(images.indices))
        private set

    val activeImage: MarkdownImageBlock get() = images[activeIndex]

    fun select(index: Int) {
        if (index in images.indices) activeIndex = index
    }
}

@Composable
fun rememberMarkdownImageViewerState(): MarkdownImageViewerState =
    remember { MarkdownImageViewerState() }

private val LocalMarkdownImageViewerState = staticCompositionLocalOf<MarkdownImageViewerState?> { null }
internal val LocalMarkdownImageGallery = staticCompositionLocalOf<List<MarkdownImageBlock>> { emptyList() }

/**
 * Installs image loading and the optional full-window viewer around a Markdown surface.
 *
 * Put this at the screen boundary, rather than around each paragraph, so the overlay receives the
 * whole available viewport. The host can observe [state] to connect its platform back gesture.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
@Suppress("DEPRECATION")
fun MarkdownImageViewerHost(
    imageProvider: MarkdownImageProvider,
    modifier: Modifier = Modifier,
    state: MarkdownImageViewerState = rememberMarkdownImageViewerState(),
    presentation: MarkdownImageViewerPresentation = MarkdownImageViewerPresentation.HostBounds,
    viewerActions: @Composable RowScope.(MarkdownImageBlock) -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        CompositionLocalProvider(
            LocalMarkdownImageProvider provides imageProvider,
            LocalMarkdownImageViewerState provides state,
        ) {
            content()
        }
        if (state.isVisible) {
            BackHandler(onBack = state::dismiss)
        }
        when (presentation) {
            MarkdownImageViewerPresentation.HostBounds -> MarkdownImageViewerHostBoundsOverlay(
                state = state,
                imageProvider = imageProvider,
                viewerActions = viewerActions,
            )

            MarkdownImageViewerPresentation.PlatformWindow -> MarkdownImageViewerPlatformWindowOverlay(
                state = state,
                imageProvider = imageProvider,
                viewerActions = viewerActions,
            )
        }
    }
}

@Composable
private fun MarkdownImageViewerHostBoundsOverlay(
    state: MarkdownImageViewerState,
    imageProvider: MarkdownImageProvider,
    viewerActions: @Composable RowScope.(MarkdownImageBlock) -> Unit,
) {
    AnimatedContent(
        targetState = state.activeSession,
        modifier = Modifier.fillMaxSize().zIndex(1f),
        transitionSpec = {
            (fadeIn(
                animationSpec = tween(
                    durationMillis = MARKDOWN_IMAGE_VIEWER_FADE_DURATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            ) togetherWith fadeOut(
                animationSpec = tween(
                    durationMillis = MARKDOWN_IMAGE_VIEWER_FADE_DURATION_MILLIS,
                    easing = FastOutSlowInEasing,
                ),
            )) using null
        },
        contentKey = { it != null },
    ) { session ->
        if (session != null) {
            MarkdownImageViewerSessionContent(session, imageProvider, state::dismiss, viewerActions)
        }
    }
}

@Composable
private fun MarkdownImageViewerPlatformWindowOverlay(
    state: MarkdownImageViewerState,
    imageProvider: MarkdownImageProvider,
    viewerActions: @Composable RowScope.(MarkdownImageBlock) -> Unit,
) {
    state.activeSession?.let { session ->
        MarkdownImageViewerPlatformWindow(onDismissRequest = state::dismiss) {
            MarkdownImageViewerSessionContent(session, imageProvider, state::dismiss, viewerActions)
        }
    }
}

@Composable
private fun MarkdownImageViewerSessionContent(
    session: MarkdownImageViewerSession,
    imageProvider: MarkdownImageProvider,
    onDismiss: () -> Unit,
    viewerActions: @Composable RowScope.(MarkdownImageBlock) -> Unit,
) {
    MarkdownImageViewer(
        image = session.activeImage,
        images = session.images,
        imageProvider = imageProvider,
        onDismiss = onDismiss,
        onImageChange = { image ->
            session.select(session.images.indexOfFirst { it.metadata.key == image.metadata.key })
        },
        actions = viewerActions,
        modifier = Modifier.fillMaxSize(),
    )
}

internal fun Modifier.openMarkdownImageOnClick(
    state: MarkdownImageViewerState?,
    block: MarkdownImageBlock,
    gallery: List<MarkdownImageBlock>,
    onLongClick: (() -> Unit)? = null,
): Modifier = when {
    onLongClick != null -> markdownClickablePointer().combinedClickable(
        role = Role.Image,
        onLongClick = onLongClick,
        onClick = { state?.show(block, gallery) },
    )
    state != null -> markdownClickablePointer().clickable(role = Role.Image) { state.show(block, gallery) }
    else -> this
}

@Composable
internal fun currentMarkdownImageViewerState(): MarkdownImageViewerState? =
    LocalMarkdownImageViewerState.current

@Composable
internal fun currentMarkdownImageGallery(): List<MarkdownImageBlock> =
    LocalMarkdownImageGallery.current
