package org.tiqian.markdown.compose

import org.tiqian.compose.createPlatformParagraphMeasurer
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.TextStyle
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

class ProseFrameWidthTest {
    @Test fun frameMustPreserveAvailableCells() {
        val measurer = createPlatformParagraphMeasurer()
        val fontSize = 27.18f
        val original = measurer.measure("正文", LayoutConstraints(maxWidth = 360f), TextStyle(fontSize = fontSize))
        val frame = measureProseFrameWidth(measurer, 360f, fontSize)
        val actual = measurer.measure("正文", LayoutConstraints(maxWidth = proseFrameContainerWidth(frame, 360f).roundToInt().toFloat()), TextStyle(fontSize = fontSize))
        assertEquals(original.debug.lineLengthGridDecision!!.cells, actual.debug.lineLengthGridDecision!!.cells,
            "available=360 fontSize=$fontSize frame=$frame pixelWidth=${frame.roundToInt()}")
    }
}
