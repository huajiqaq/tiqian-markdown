package org.tiqian.markdown.compose


import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import org.tiqian.markdown.MarkdownRenderDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Stable
internal class MarkdownFootnoteNavigationState(
    private val coroutineScope: CoroutineScope,
    private val materializeDefinition: (String) -> Boolean = { false },
) {
    private val definitions = mutableMapOf<String, BringIntoViewRequester>()
    private val pendingLabels = mutableSetOf<String>()

    fun registerDefinition(label: String, requester: BringIntoViewRequester) {
        definitions[label] = requester
        if (pendingLabels.remove(label)) {
            coroutineScope.launch {
                // Registration precedes placement; relocation needs the target's attached coordinates.
                withFrameNanos { }
                if (definitions[label] === requester) requester.bringIntoView()
            }
        }
    }

    fun unregisterDefinition(label: String, requester: BringIntoViewRequester) {
        if (definitions[label] === requester) definitions.remove(label)
    }

    fun bringDefinitionIntoView(label: String) {
        val requester = definitions[label]
        if (requester != null) {
            coroutineScope.launch { requester.bringIntoView() }
        } else if (materializeDefinition(label)) {
            pendingLabels += label
        }
    }
}

internal val LocalMarkdownFootnoteNavigationState =
    compositionLocalOf<MarkdownFootnoteNavigationState?> { null }

@Composable
internal fun rememberMarkdownFootnoteNavigationState(
    document: MarkdownRenderDocument? = null,
    materializeDefinition: (String) -> Boolean = { false },
): MarkdownFootnoteNavigationState {
    val coroutineScope = rememberCoroutineScope()
    val currentMaterializeDefinition = rememberUpdatedState(materializeDefinition)
    return remember(coroutineScope, document) {
        MarkdownFootnoteNavigationState(coroutineScope) { currentMaterializeDefinition.value(it) }
    }
}
