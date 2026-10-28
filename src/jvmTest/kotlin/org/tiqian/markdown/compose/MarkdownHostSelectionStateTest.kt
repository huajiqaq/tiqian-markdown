package org.tiqian.markdown.compose

import org.tiqian.markdown.*

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.use
import org.tiqian.compose.CjkSelectionState
import org.tiqian.compose.rememberCjkSelectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class)
class MarkdownHostSelectionStateTest {
    private class RecordingRepresentation : ContextMenuRepresentation {
        var openItems: (() -> List<ContextMenuItem>)? = null

        @Composable
        override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
            openItems = if (state.status is ContextMenuState.Status.Open) items else null
        }
    }

    @Test
    fun hostMenuItemReadsSelectionThroughHoistedState() {
        val representation = RecordingRepresentation()
        var invokedWith: String? = null
        var state: CjkSelectionState? = null
        val document = MarkdownRenderDocument(
            blocks = listOf(
                paragraph("context word", 0),
                paragraph("另一段正文", 1),
            ),
        )

        ImageComposeScene(width = 360, height = 200) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalContextMenuRepresentation provides representation) {
                ContextMenuDataProvider(
                    items = {
                        listOf(ContextMenuItem("Host") { invokedWith = selectionState.selectedText?.text })
                    },
                ) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TiqianMarkdownSurface(
                            document = document,
                            scrollState = rememberScrollState(),
                            selectionState = selectionState,
                        )
                    }
                }
            }
        }.use { scene ->
            val pixels = scene.render().toComposeImageBitmap().toPixelMap()
            val firstLineY = (0 until pixels.height).first { y ->
                (0 until pixels.width).any { x -> pixels[x, y].red < 0.5f }
            } + 6
            val inkX = (0 until pixels.width).first { x -> pixels[x, firstLineY].red < 0.5f }
            val selectionState = assertNotNull(state)
            assertTrue(selectionState.selectAll())
            scene.render()
            val selected = assertNotNull(selectionState.selectedText?.text)
            assertTrue("context word" in selected)

            scene.secondaryClick(Offset(inkX + 12f, firstLineY.toFloat()))
            scene.render()

            val items = assertNotNull(representation.openItems, "context menu did not open")
            items().single { it.label == "Host" }.onClick()
            assertEquals(selected, invokedWith)
        }
    }

    private fun ImageComposeScene.secondaryClick(point: Offset) {
        sendPointerEvent(
            PointerEventType.Press,
            point,
            timeMillis = 1_000L,
            type = PointerType.Mouse,
            buttons = PointerButtons(isSecondaryPressed = true),
            button = PointerButton.Secondary,
        )
        sendPointerEvent(
            PointerEventType.Release,
            point,
            timeMillis = 1_050L,
            type = PointerType.Mouse,
            buttons = PointerButtons(),
            button = PointerButton.Secondary,
        )
    }

    private fun paragraph(text: String, key: Int) = MarkdownParagraph(
        text = MarkdownText(text),
        metadata = MarkdownNodeMetadata(
            key = MarkdownNodeKey(key, listOf(key)),
            sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
        ),
    )
}
