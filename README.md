# 提椠 Markdown

[![Maven Central](https://img.shields.io/maven-central/v/org.tiqian/markdown-compose?label=maven)](https://central.sonatype.com/artifact/org.tiqian/markdown-compose)

提椠 Markdown 是基于[提椠](https://github.com/tiqian-cjk/tiqian)的 Markdown 正文渲染库。

提椠 Markdown 在语法节点映射为对应组件之上，进一步处理整篇文章的排版。段落、标题、列表和引用使用提椠的中文断行、标点空间与两端对齐。代码、公式、表格、
图片、图注和脚注则共享统一的版心、间距与样式体系。

渲染器提供文章级的选择与复制、脚注跳转、代码复制和多图浏览。宿主可将内置选区的 `fragmentKey`
识别为 `MarkdownSelectionKey`：`node` 对应当前文档节点的 `metadata.key`，`role` 区分正文、标记、
单元格等。片段内偏移需单独保留，片段生成函数不对外公开。文档模型与 Markdown parser 解耦，
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

图片 provider 可通过 `MarkdownImageContent` 的 `maxDisplayWidth` 和 `maxDisplayHeight` 限制正文预览，
默认不限制。已知比例时等比缩小，不改变图注和原图查看器；具体屏幕断点与预算由应用决定。
`onLongClick` 单独处理长按。普通点击先交给节点处理器，未消费时才打开查看器。

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

## 宿主可以在 Custom 范围末尾绘制小图标

宿主用 `MarkdownTextMark.Custom` 保留范围，并通过 `endMarker` 提供 Painter。下面的
`metadata` 是该段落的现有元数据，`notePainter` 是宿主已经创建的 Painter；范围偏移采用 UTF-16 字符单元。

```kotlin
val text = MarkdownText(
    value = "正文内容",
    spans = listOf(
        MarkdownTextSpan(MarkdownTextRange(0, 4), MarkdownTextMark.Custom("note")),
    ),
)
TiqianMarkdown(
    document = MarkdownRenderDocument(listOf(MarkdownParagraph(text, metadata))),
    inlineSlots = MarkdownInlineSlots(custom = { mark, _, _ ->
        if (mark.kind == "note") {
            MarkdownCustomInlinePresentation(
                endMarker = MarkdownInlineEndMarker(painter = notePainter, color = Color.Gray),
            )
        } else null
    }),
)
```

每个非空 Custom 范围只在最后一个已排版文字端点绘制一次，跨行范围不会逐行重复；相邻的独立范围
即使 kind 和样式相同也各自保留标记。内联图片或公式改变显示文字长度时，标记使用已有映射后的末端。

标记通过 `CjkInlineObject` 预留 `gap + size` 行内宽度并参与断行；标记开关变化会触发重新排版。
此能力需要提供 `addCjkRenderOnlyInlineObject` 的 core 版本。U+FFFC 占位只存在于内部排版投影，
不改变 `MarkdownText.value`、逻辑选区索引、复制或搜索。

标记按横排布局框的物理右侧放置，此能力不承诺 RTL（从右向左）或 Bidi（双向文本）的逻辑末尾方向。

## 宿主通过搜索状态查找并定位正文

`org.tiqian.markdown.compose.rememberMarkdownTextSearchState` 创建一个 Surface 专用的搜索状态。
宿主提供搜索面板、查询输入和文档；面板关闭时调用 `clear()`。例如在宿主的 Composable 中：

```kotlin
val search = rememberMarkdownTextSearchState()
val scope = rememberCoroutineScope()

LaunchedEffect(searchVisible, queryInput) {
    if (searchVisible) search.setQuery(queryInput) else search.clear()
}
TiqianMarkdownSurface(
    document = document,
    scrollState = rememberScrollState(),
    searchState = if (searchVisible) search else null,
)
search.matches.firstOrNull()?.let { match ->
    Button(onClick = { scope.launch { search.reveal(match) } }) { Text("定位首个结果") }
}
```

宿主从 `query`、`matches` 和 `activeMatch` 读取面板内容，使用 `setQuery(String)` 更新查询，
使用挂起函数 `reveal(match): Boolean` 定位某个结果。每个结果包含片段键、片段内起止偏移和摘要；
偏移按原文 UTF-16 计数，结束位置不包含在范围内。匹配忽略大小写，合并连续 Unicode 空白并去掉
查询首尾空白；结果按阅读顺序排列，已接收的命中之间不重叠，不跨段落或表格单元格拼接。
搜索不执行 Unicode 组合字符等价转换，也不提供正则表达式语法。

搜索包含内置段落、标题、列表与引用正文、脚注正文、表格单元格及图片和表格题注。搜索排除独立的
代码块、公式块、HTML 块、列表和脚注标记、图片描述、行内图片与公式的源码范围，以及自定义 block slot
替换的整个子树。公共 `DefaultMarkdownCaption` / `DefaultMarkdownFigureCaption` helper 在
自定义 slot 中不具备独立的文档片段标识，因此这些自定义题注也不出现在结果中。普通强调、链接、
行内代码和自定义行内样式仍使用正文范围，查询可以跨越这些样式边界。

定位只请求当前命中所属的顶层延迟渲染块，并等待该块的 `CjkText` 提供布局和放置结果。
`BringIntoViewRequester` 将命中首行的实际矩形传给 Compose 祖先滚动容器，因此文首图片、宿主
header、嵌套正文和超过屏幕高度的段落都使用实际位置。外部滚动宿主应传入相同的 `scrollState`，
并设置 `enableScroll = false`。定位成功后，搜索状态保留一个当前目标 owner；其他块仍遵循
原有的可见范围与预排规则。未取得布局或未完成滚动的请求在 5 秒后返回 `false`；没有可见面积的
命中矩形也返回 `false`，例如字体未能提供字形宽度或文本只包含零宽字符时。

内部滚动自动将完全屏外的搜索行居中，完全可见时不动，部分可见时使用最近边缘；文档边界可能阻止精确居中。
外部滚动宿主可在创建滚动容器前复用同一策略，并提供实际遮挡像素：

```kotlin
val spec = rememberMarkdownTextSearchBringIntoViewSpec(
    search, obscuredTopPx = toolbarHeightPx, obscuredBottomPx = bottomBarHeightPx,
)
CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
    Column(Modifier.verticalScroll(scrollState)) {
        TiqianMarkdownSurface(document, scrollState = scrollState, enableScroll = false, searchState = search)
    }
}
```

策略只在当前搜索定位等待期间生效，选择手势接管时取消定位。普通焦点和选择滚动原样交给
`fallback`，宿主可以传入已有避遮挡策略。不会把搜索颜色或长期保留的当前命中当成滚动激活条件。

默认高亮仅覆盖当前命中，颜色为 `Color.Yellow.copy(alpha = 0.3f)`，可通过 `highlightColor` 调整。
设置 `highlightAllMatches = true` 可同时高亮其余命中，默认关闭；普通命中颜色默认为
`Color.Yellow.copy(alpha = 0.15f)`，可通过 `allMatchHighlightColor` 调整。当前颜色替代普通颜色，不叠色。
`highlightActiveMatch = false` 只关闭当前命中的特殊样式而保留定位；若开启全部高亮，该命中仍使用普通颜色。
全部高亮只复用已布局片段，不额外保留或测量离屏 owner。搜索不会修改宿主的 `selectionState`。
查询改变时结果更新，当前高亮、目标 owner 和待处理请求清除；`clear()`、文档替换、Surface 卸载或
`searchState` 改为 `null` 同样清除旧状态。新的定位请求会取消上一个请求；调用方协程取消会正常传播。
过期结果返回 `false`。卸载保留查询文字，重新绑定时重新匹配；绑定之前调用 `setQuery()` 也有效。
一个搜索状态不能同时绑定两个 Surface。

`searchState = null` 时，渲染器不创建搜索文本数据、目标注册、布局监听或高亮绘制节点。
启用搜索后，渲染器复用选区的阅读顺序遍历构建文本数据；每次查询扫描这些文本，不测量离屏文字。
搜索高亮与定位复用内联解析器的偏移映射和实际排版结果，不维护第二份排版模型。

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
