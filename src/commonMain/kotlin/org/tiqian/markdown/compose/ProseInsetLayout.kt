package org.tiqian.markdown.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import kotlin.math.roundToInt

/** Compose replay of the same inset geometry consumed by paginated hosts. */
@Composable
fun ProseInsetLayout(startInsetPx: Float, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        require(measurables.size == 1)
        val geometry = insetProseGeometry(constraints.maxWidth.toFloat(), startInsetPx.roundToInt().toFloat())
        val childWidth = geometry.contentWidth.roundToInt()
        val child = measurables.single().measure(constraints.copy(minWidth = childWidth, maxWidth = childWidth, minHeight = 0))
        val baseline = child[FirstBaseline]
        layout(constraints.maxWidth, child.height, if (baseline == AlignmentLine.Unspecified) emptyMap() else mapOf(FirstBaseline to baseline)) {
            child.placeRelative(geometry.contentLeft.roundToInt(), 0)
        }
    }
}
