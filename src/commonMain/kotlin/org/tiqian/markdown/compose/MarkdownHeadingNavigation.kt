package org.tiqian.markdown.compose

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownContainerBlock
import org.tiqian.markdown.MarkdownContainerKind
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownNodeKey
import org.tiqian.markdown.childBlocks

internal val LocalMarkdownDocumentBlocks = staticCompositionLocalOf<List<MarkdownBlock>> { emptyList() }
internal val LocalMarkdownHeadingNavigation = staticCompositionLocalOf<MarkdownHeadingNavigation?> { null }

internal class MarkdownHeadingNavigation(
    private val scope: CoroutineScope,
    private val blocks: List<MarkdownBlock>,
    private val materialize: (MarkdownNodeKey) -> Unit,
) {
    val selectedTabs = mutableStateMapOf<MarkdownNodeKey, Int>()
    private val requesters = mutableMapOf<MarkdownNodeKey, BringIntoViewRequester>()
    private val pending = mutableSetOf<MarkdownNodeKey>()

    fun register(key: MarkdownNodeKey, requester: BringIntoViewRequester) {
        requesters[key] = requester
        if (pending.remove(key)) scope.launch { requester.bringIntoView() }
    }

    fun unregister(key: MarkdownNodeKey, requester: BringIntoViewRequester) {
        if (requesters[key] === requester) requesters.remove(key)
    }

    fun activateAncestors(key: MarkdownNodeKey): MarkdownNodeKey? {
        fun activate(block: MarkdownBlock): Boolean {
            if (block.metadata.key == key) return true
            val children = block.childBlocks()
            for ((index, child) in children.withIndex()) {
                if (activate(child)) {
                    if (block is MarkdownContainerBlock && block.kind == MarkdownContainerKind.Tabs) selectedTabs[block.metadata.key] = index
                    return true
                }
            }
            return false
        }
        return blocks.firstOrNull(::activate)?.metadata?.key
    }

    fun navigate(key: MarkdownNodeKey) {
        val ownerKey = activateAncestors(key) ?: return
        val requester = requesters[key]
        if (requester != null) scope.launch { requester.bringIntoView() }
        else {
            pending += key
            materialize(ownerKey)
        }
    }
}

@Composable
internal fun rememberMarkdownHeadingNavigation(
    blocks: List<MarkdownBlock>,
    deferred: MarkdownDeferredLayout?,
): MarkdownHeadingNavigation {
    val scope = rememberCoroutineScope()
    val currentDeferred = rememberUpdatedState(deferred)
    return remember(scope, blocks) { MarkdownHeadingNavigation(scope, blocks) { currentDeferred.value?.onMaterializeOwner?.invoke(it) } }
}

@Composable
internal fun Modifier.markdownHeadingTarget(heading: MarkdownHeading): Modifier {
    val navigation = LocalMarkdownHeadingNavigation.current
    val requester = remember { BringIntoViewRequester() }
    DisposableEffect(navigation, heading.metadata.key, requester) {
        navigation?.register(heading.metadata.key, requester)
        onDispose { navigation?.unregister(heading.metadata.key, requester) }
    }
    return bringIntoViewRequester(requester)
}
