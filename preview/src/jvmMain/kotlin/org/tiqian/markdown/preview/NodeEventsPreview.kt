@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@file:Suppress("DEPRECATION")

package org.tiqian.markdown.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import org.tiqian.markdown.*
import org.tiqian.markdown.compose.TiqianMarkdown
import org.tiqian.markdown.compose.MarkdownStyle
import java.io.File

/** Parser-independent examples; every source position is explicitly unknown. */
internal val NodeEventsPreviewDocument: MarkdownRenderDocument = run {
    var next = 0
    fun metadata(type: String, children: List<MarkdownNodeMetadata> = emptyList()) = MarkdownNodeMetadata(
        key = MarkdownNodeKey(next, listOf(next++)), sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
        nodeType = type, hasSourceSpan = false, attributes = mapOf("owner" to "preview"),
        properties = mapOf("optional" to MarkdownNodeValue.Null,
            "tags" to MarkdownNodeValue.ListValue(listOf(MarkdownNodeValue.Text("example"), MarkdownNodeValue.Text("events")))),
        children = children,
    )
    fun text(value: String, mark: MarkdownTextMark? = null): MarkdownText {
        val child = metadata(if (mark == null) "Text" else when (mark) {
            MarkdownTextMark.Spoiler -> "Spoiler"
            is MarkdownTextMark.Citation -> "CitationReference"
            else -> "StrongEmphasis"
        })
        return MarkdownText(value, listOf(MarkdownTextSpan(MarkdownTextRange(0, value.length), mark, child)))
    }
    fun paragraph(value: String, mark: MarkdownTextMark? = null): MarkdownParagraph {
        val content = text(value, mark)
        return MarkdownParagraph(content, metadata("Paragraph", content.spans.mapNotNull { it.metadata }))
    }
    fun container(kind: MarkdownContainerKind, blocks: List<MarkdownBlock>, title: String? = null, width: String? = null) =
        MarkdownContainerBlock(kind, blocks, metadata(kind.name, blocks.map { it.metadata }), title?.let(::MarkdownText), width)
    val blocks = listOf(
        MarkdownHeading(2, "node-events", text("完整节点与事件"), metadata("Heading")),
        paragraph("点击文字后，宿主可以读取原类型、属性、结构化字段与有序子节点。"),
        container(MarkdownContainerKind.Admonition, listOf(paragraph("提示容器保留内部的段落与文字事件。")), "完整子树"),
        container(MarkdownContainerKind.DefinitionList, listOf(
            container(MarkdownContainerKind.DefinitionTerm, emptyList(), "术语与定义"),
            container(MarkdownContainerKind.DefinitionDescription, listOf(paragraph("可见结构项各有身份，原文范围不依赖显示占位。"))),
        )),
        container(MarkdownContainerKind.Columns, listOf(
            container(MarkdownContainerKind.Column, listOf(paragraph("左列：子节点点击")), width = "50%"),
            container(MarkdownContainerKind.Column, listOf(paragraph("右列：属性与字段")), width = "50%"),
        )),
        container(MarkdownContainerKind.Tabs, listOf(
            container(MarkdownContainerKind.Tab, listOf(paragraph("首项正文保留 Text → Paragraph → Tab → Tabs → Document 顺序。")), "事件顺序"),
            container(MarkdownContainerKind.Tab, listOf(paragraph("未消费的标签事件执行原切换动作，已消费的事件停止。")), "默认动作"),
        )),
        paragraph("点击揭示这段剧透文字", MarkdownTextMark.Spoiler),
        paragraph("[node-events]", MarkdownTextMark.Citation("node-events")),
        MarkdownBibliographyBlock(listOf(MarkdownBibliographyEntry("node-events", text("参考条目具有独立元数据，引用仍可定位。"), metadata("BibliographyEntry"))), metadata("BibliographyDefinition")),
        MarkdownDiagramBlock("mermaid", "graph LR\n  Node --> Parent\n  Parent --> Document", metadata("DiagramBlock")),
        MarkdownTocBlock(metadata("TocPlaceholder")),
        MarkdownPageBreakBlock(metadata("PageBreak")),
    )
    MarkdownRenderDocument(blocks, metadata = metadata("Document", blocks.map { it.metadata }), revision = 1L)
}

/** Export reproducible evidence from the actual library renderer without opening a desktop window. */
internal fun exportNodeEventsSpecimen(directory: File) {
    directory.mkdirs()
    var lastEvent = "尚未点击节点"
    ImageComposeScene(width = 1000, height = 1440, density = Density(1.25f)) {
        MaterialTheme {
            var eventText by remember { mutableStateOf(lastEvent) }
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(24.dp)) {
                Text("Tiqian Markdown · AST 事件", style = MaterialTheme.typography.headlineSmall)
                Text(eventText, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 3)
                TiqianMarkdown(NodeEventsPreviewDocument,
                    style = MarkdownStyle(body = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 26.sp)),
                    onNodeClick = { event ->
                    lastEvent = "${event.nodeType} · path=${event.metadata.key.path} · properties=${event.properties.keys} · children=${event.children.size}"
                    eventText = if (eventText == "尚未点击节点") lastEvent else "$eventText → $lastEvent"
                    false
                })
            }
        }
    }.use { scene ->
        fun save(name: String, time: Long) {
            scene.render(time).use { image -> checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data -> File(directory, name).writeBytes(data.bytes) } }
        }
        save("node-events-initial.png", 0L)
        val textNode = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(false) }
            .first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "左列：子节点点击" } == true && SemanticsActions.CustomActions in it.config }
        check(textNode.config[SemanticsActions.CustomActions].first().action())
        Snapshot.sendApplyNotifications()
        save("node-events-click.png", 16_000_000L)
        val tab = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(true) }
            .first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "默认动作" } == true && SemanticsActions.OnClick in it.config }
        checkNotNull(tab.config[SemanticsActions.OnClick].action).invoke()
        Snapshot.sendApplyNotifications()
        save("node-events-tab.png", 32_000_000L)
        check(lastEvent.contains("Document"))
    }
    println("node-events-specimens=${directory.absolutePath}")
}
