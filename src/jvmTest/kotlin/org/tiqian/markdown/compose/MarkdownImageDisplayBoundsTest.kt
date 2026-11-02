package org.tiqian.markdown.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.MarkdownNodeMetadata
import org.tiqian.markdown.MarkdownSourceSpan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MarkdownImageDisplayBoundsTest {
    @Test
    fun imageFitsBothBoundsWithoutChangingItsAspectRatio() = runBlocking {
        for (intrinsic in listOf(IntSize(1200, 800), IntSize(800, 5000))) {
            var displayed = IntSize.Zero
            val block = MarkdownImageBlock("test:image", "Image", null, intrinsic.width, intrinsic.height,
                MarkdownNodeMetadata(MarkdownNodeKey(1, listOf(0)), MarkdownSourceSpan(0, 0, 0, 0, 0, 0)))
            ImageComposeScene(width = 720, height = 800, coroutineContext = coroutineContext) {
                MaterialTheme {
                    CompositionLocalProvider(LocalMarkdownImageProvider provides {
                        MarkdownImageContent(intrinsic, MarkdownImageLoadState.Success,
                            maxDisplayWidth = 480.dp, maxDisplayHeight = 240.dp) { modifier ->
                            Box(modifier.onSizeChanged { displayed = it }.background(Color.Blue))
                        }
                    }) {
                        DefaultMarkdownImageBlock(block, MarkdownStyle())
                    }
                }
            }.use { scene ->
                repeat(8) { frame -> scene.render(frame * 16_000_000L); yield() }
                assertTrue(displayed.width in 1..480 && displayed.height in 1..240, "$intrinsic -> $displayed")
                assertTrue(abs(displayed.width - displayed.height * intrinsic.width.toDouble() / intrinsic.height) <= 1.0,
                    "Image ratio changed: $intrinsic -> $displayed")
            }
        }
    }

    @Test
    fun defaultBoundsPreserveNaturalSizing() = runBlocking {
        var displayed = IntSize.Zero
        val block = MarkdownImageBlock("test:image", "Image", null, 400, 200,
            MarkdownNodeMetadata(MarkdownNodeKey(1, listOf(0)), MarkdownSourceSpan(0, 0, 0, 0, 0, 0)))
        ImageComposeScene(width = 720, height = 800, coroutineContext = coroutineContext) {
            MaterialTheme {
                CompositionLocalProvider(LocalMarkdownImageProvider provides {
                    MarkdownImageContent(IntSize(400, 200), MarkdownImageLoadState.Success) { modifier ->
                        Box(modifier.onSizeChanged { displayed = it }.background(Color.Blue))
                    }
                }) {
                    DefaultMarkdownImageBlock(block, MarkdownStyle())
                }
            }
        }.use { scene ->
            repeat(8) { frame -> scene.render(frame * 16_000_000L); yield() }
            assertEquals(IntSize(400, 200), displayed)
        }
    }
}
