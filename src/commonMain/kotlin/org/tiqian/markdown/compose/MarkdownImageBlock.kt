package org.tiqian.markdown.compose

import org.tiqian.markdown.MarkdownBlock
import org.tiqian.markdown.MarkdownBlockQuote
import org.tiqian.markdown.MarkdownCodeBlock
import org.tiqian.markdown.MarkdownCustomBlock
import org.tiqian.markdown.MarkdownFootnoteDefinition
import org.tiqian.markdown.MarkdownHeading
import org.tiqian.markdown.MarkdownHtmlBlock
import org.tiqian.markdown.MarkdownImageBlock
import org.tiqian.markdown.MarkdownList
import org.tiqian.markdown.MarkdownMathBlock
import org.tiqian.markdown.MarkdownParagraph
import org.tiqian.markdown.MarkdownTable
import org.tiqian.markdown.MarkdownTableAlignment
import org.tiqian.markdown.MarkdownTaskState
import org.tiqian.markdown.MarkdownText
import org.tiqian.markdown.MarkdownTextMark
import org.tiqian.markdown.MarkdownTextRange
import org.tiqian.markdown.MarkdownTextSpan
import org.tiqian.markdown.MarkdownThematicBreak
import org.tiqian.markdown.MarkdownUnsupportedBlock
import org.tiqian.markdown.resolveMarkdownCodeLanguage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.tiqian.compose.material3.CjkText
import org.tiqian.compose.ParagraphMeasurer
import org.tiqian.compose.measure
import org.tiqian.compose.toCjkTextStyle
import org.tiqian.compose.toCoreTextStyle
import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutResult
import org.tiqian.core.LastLineAlignment
import org.tiqian.core.LineLengthGrid
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextStyle as CoreTextStyle
import org.tiqian.core.ic
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.tiqian.markdown.compose.generated.resources.Res
import org.tiqian.markdown.compose.generated.resources.code_copied
import org.tiqian.markdown.compose.generated.resources.ic_check_box_20dp
import org.tiqian.markdown.compose.generated.resources.ic_check_box_outline_blank_20dp
import org.tiqian.markdown.compose.generated.resources.ic_check_16dp
import org.tiqian.markdown.compose.generated.resources.ic_content_copy_16dp
import org.tiqian.markdown.compose.generated.resources.copy_code
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

@Composable
fun DefaultMarkdownImageBlock(
    block: MarkdownImageBlock,
    style: MarkdownStyle,
    onLinkClick: ((String) -> Unit)? = null,
    inlineSlots: MarkdownInlineSlots = DefaultMarkdownInlineSlots,
) {
    val label = block.description.ifBlank { block.destination }
    val imageContent = LocalMarkdownImageProvider.current?.invoke(block)
    val viewerState = LocalMarkdownImageViewerState.current
    val imageGallery = LocalMarkdownImageGallery.current
    val nodeClick = rememberMarkdownNodeClickHandler()
    Column(Modifier.fillMaxWidth()) {
        if (imageContent == null || imageContent.loadState == MarkdownImageLoadState.Error) {
            val fallbackImageText = @Composable {
                MarkdownTextBlock(
                        text = MarkdownText(
                            value = label,
                            spans = listOf(
                                MarkdownTextSpan(
                                    MarkdownTextRange(0, label.length),
                                    MarkdownTextMark.Link(block.destination, block.title),
                                ),
                            ),
                        ),
                        textStyle = style.body,
                        markdownStyle = style,
                        inlineSlots = inlineSlots,
                        onLinkClick = onLinkClick,
                        onFootnoteClick = null,
                )
            }
            if (block.caption == null) {
                MarkdownSelectionScope(
                    markdownSelectionKey(block.metadata.key, "description"),
                    fallbackImageText,
                )
            } else {
                fallbackImageText()
            }
            block.caption?.let { caption ->
                MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "caption")) {
                    DefaultMarkdownFigureCaption(
                        caption = caption,
                        style = style,
                        inlineSlots = inlineSlots,
                        onLinkClick = onLinkClick,
                        onFootnoteClick = null,
                    )
                }
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val intrinsicSize = imageContent.intrinsicSize
                val widthHint = block.widthPixels ?: intrinsicSize?.width
                val heightHint = block.heightPixels ?: intrinsicSize?.height
                val ratio = if (widthHint != null && heightHint != null && heightHint > 0) {
                    widthHint.toFloat() / heightHint
                } else {
                    null
                }
                val imageWidth = widthHint?.dp?.coerceAtMost(maxWidth) ?: maxWidth
                val captionWidth = maxOf(imageWidth, style.figureCaptionMinimumWidthDp())
                    .coerceAtMost(maxWidth)
                val imageShape = RoundedCornerShape(style.imageCornerRadius)
                val imageModifier = Modifier
                    .width(imageWidth)
                    .then(if (ratio != null && ratio > 0f) Modifier.aspectRatio(ratio) else Modifier)
                    .border(style.imageOutlineWidth, style.imageOutlineColor, imageShape)
                    .clip(imageShape)
                    .openMarkdownImageOnClick(viewerState, block, imageGallery, nodeClick)
                Column(
                    modifier = Modifier.width(captionWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(imageModifier, contentAlignment = Alignment.Center) {
                        imageContent.content(Modifier.fillMaxSize())
                        if (imageContent.loadState == MarkdownImageLoadState.Loading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                progress = { imageContent.progress ?: 0f },
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                    block.caption?.let { caption ->
                        MarkdownSelectionScope(markdownSelectionKey(block.metadata.key, "caption")) {
                            DefaultMarkdownFigureCaption(
                                caption = caption,
                                style = style,
                                modifier = Modifier.fillMaxWidth(),
                                inlineSlots = inlineSlots,
                                onLinkClick = onLinkClick,
                                onFootnoteClick = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

