# 提椠 Markdown

[![Maven Central](https://img.shields.io/maven-central/v/org.tiqian/markdown-compose?label=maven)](https://central.sonatype.com/artifact/org.tiqian/markdown-compose)

提椠 Markdown 是基于[提椠](https://github.com/tiqian-cjk/tiqian)的 Markdown 正文渲染库。

提椠 Markdown 在语法节点映射为对应组件之上，进一步处理整篇文章的排版。段落、标题、列表和引用使用提椠的中文断行、标点空间与两端对齐。代码、公式、表格、
图片、图注和脚注则共享统一的版心、间距与样式体系。

渲染器提供文章级的选择与复制、脚注跳转、代码复制和多图浏览。文档模型与 Markdown parser 解耦，
应用可以沿用现有解析器，并保留自有语法和组件。

默认样式跟随 Material 3，也可以自定义。当前仍处于早期开发阶段，支持 Compose Desktop 和 Android 8.1 (API 27) 及以上版本。

## 使用

```kotlin
implementation("org.tiqian:markdown-compose:<version>")
```

应用把 Markdown 解析结果转换成 `MarkdownRenderDocument` 后即可渲染：

```kotlin
TiqianMarkdown(
    document = document,
    onLinkClick = ::openLink,
)
```

文档转换、自定义样式和图片加载见[架构说明](docs/architecture.md)。

## 宿主可以接收所有实际呈现节点的点击

`TiqianMarkdown` 和 `TiqianMarkdownSurface` 的 `onNodeClick` 接收普通文字、全部行内 mark、段落、标题、引用、列表／列表项、代码／token、图片、公式、HTML、表格／行／单元格、脚注、自定义和可读回退节点。表格行的 `sectionMetadata` 可保留原 TableHead／TableBody 身份，文档的 `metadata` 可保留原 Document 身份。

```kotlin
TiqianMarkdown(
    document = document,
    onNodeClick = { event ->
        if (event.nodeType == "host.note") {
            println("${event.metadata.key}: ${event.textRange} ${event.text}")
            true
        } else false
    },
)
```

处理函数返回 `true` 时停止分发；返回 `false` 时从内部节点继续到父节点，全部拒绝后执行既有链接、脚注、图片查看或 Custom 默认动作。代码复制等独立按钮继续执行自己的动作，不触发父块事件。未设置处理函数时保留原有行为。文字长按仍使用原生选择。

事件包含节点类型、原文内容、metadata、attributes、payload、结构化 properties、有序 children、文档 revision 和可用时的窗口位置。metadata 的 children 可保留所有原语法节点，包括不产生可见控件的定义与空行。properties 使用 MarkdownNodeValue 的字符串、布尔、整数、小数、null、列表与映射，避免将范围、列表或文献条目拼成有歧义的字符串。`textRange` 使用所属 `MarkdownText.value` 或代码正文的 UTF-16 半开范围，内部图片／公式替换与排版占位不会进入该范围。适配器应为每个原节点提供 `MarkdownTextSpan.metadata`；没有视觉样式的 Text 或自有语法可使用 `mark = null`。缺失 metadata 时生成的 key 只表示当前结构位置，不承诺跨编辑持久身份。未知源码位置应设置 `hasSourceSpan = false`，不能把父块位置当作精确行内位置。

只有实际呈现的节点具有点击区域；链接定义、空行、FrontMatter 和零长度文字不会新增隐藏控件。宿主替换 table 等块槽时负责其内部结构。已有独立 clickable 的内容槽可在默认动作前调用 `rememberMarkdownNodeClickHandler()`；普通按钮无需调用该函数。

## 宿主可以构造自定义文档节点

`MarkdownCustomBlock(kind, attributes, metadata)` 通过 `MarkdownBlockSlots.customBlock` 呈现；`MarkdownTextMark.Custom(kind, attributes)` 通过 `MarkdownInlineSlots.custom` 提供文字样式、装饰、可访问名称和默认点击。

```kotlin
val metadata = MarkdownNodeMetadata(
    key = MarkdownNodeKey(0, listOf(0)),
    sourceSpan = MarkdownSourceSpan(0, 0, 0, 0, 0, 0),
    hasSourceSpan = false,
)
val document = MarkdownRenderDocument(listOf(
    MarkdownCustomBlock("host.card", mapOf("text" to "自定义块"), metadata),
    MarkdownParagraph(
        MarkdownText("注释", listOf(MarkdownTextSpan(
            MarkdownTextRange(0, 2), MarkdownTextMark.Custom("host.note"),
            metadata.copy(key = MarkdownNodeKey(0, listOf(1, 0))),
        ))),
        metadata.copy(key = MarkdownNodeKey(0, listOf(1))),
    ),
))
TiqianMarkdown(
    document = document,
    slots = MarkdownBlockSlots(customBlock = { block, style ->
        BasicText(block.attributes.getValue("text"), style = style.body)
    }),
    inlineSlots = MarkdownInlineSlots(custom = { _, _, _ ->
        MarkdownCustomInlinePresentation(style = SpanStyle(fontWeight = FontWeight.Bold))
    }),
    onNodeClick = { event -> println(event); true },
)
```

库只渲染宿主传入的模型，不包含 Markdown parser 或新语法插件。识别自有语法并转换节点属于宿主解析器的职责；sealed 节点接口不允许外部任意继承新 Kotlin 子类。Custom 块的 blocks 保存有序子树；Custom 行内槽提供文字呈现，行内图片和公式使用各自的内容槽。

`MarkdownContainerBlock` 以共享结构表达定义列表、提示、自定义容器、列与标签页，各结构项保留自己的 metadata。`MarkdownBlockSlots.container` 提供当前上下文中的 defaultContent 函数，替换外观时可以委托完整默认标题、结构与子树，选择、链接、图片、公式和事件使用原有实现。文献、目录、图表源码及分页标记分别使用 `MarkdownBibliographyBlock`、`MarkdownTocBlock`、`MarkdownDiagramBlock` 与 `MarkdownPageBreakBlock`。文献引用和目录点击可导航到当前文档目标，标签页只有处理函数拒绝事件时才切换，剧透文本在揭示前不触发隐藏子节点动作。

图表默认展示可选择的源码，不执行 Mermaid／PlantUML 或请求外部服务；分页标记默认是屏幕分隔线，打印宿主可通过 pageBreak 槽替换。原节点类型和公开字段保存于 metadata，不声称这两种默认呈现完成图形渲染或打印分页。

## 体验与构建

```shell
./gradlew jvmTest compileAndroidMain :preview:compileKotlinJvm
./gradlew :preview:run
./gradlew :preview:run --args="--node-events-specimen build/specimens/node-events"
```

“完整节点与事件”样张使用明确标注为未知源码位置的中立模型。无窗口导出命令调用正式渲染器和语义动作，保存点击前、子节点事件和标签切换后的 PNG；不需要网络图片或账号。

## 许可证

Tiqian Markdown 以 [Mozilla Public License 2.0](LICENSE) 发布。第三方组件与字体见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
