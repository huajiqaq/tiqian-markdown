# 提椠 Markdown

[![Maven Central](https://img.shields.io/maven-central/v/org.tiqian/markdown-compose?label=maven)](https://central.sonatype.com/artifact/org.tiqian/markdown-compose)

提椠 Markdown 是基于[提椠](https://github.com/tiqian-cjk/tiqian)的 Markdown 正文渲染库。

提椠 Markdown 在语法节点映射为对应组件之上，进一步处理整篇文章的排版。段落、标题、列表和引用使用提椠的中文断行、标点空间与两端对齐。代码、公式、表格、
图片、图注和脚注则共享统一的版心、间距与样式体系。

渲染器提供文章级的选择与复制、脚注跳转、代码复制和多图浏览。文档模型与 Markdown parser 解耦，
应用可以沿用现有解析器，并保留自有语法和组件。
宿主可将选区范围的 `fragmentKey` 识别为 `MarkdownSelectionKey`，读取原始块身份 `node` 与文面角色 `role`；内部选择片段生成函数仍不公开。

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

标记通过 `CjkInlineObject` 真实预留 `gap + size` 行内宽度并参与断行；占位只存在于内部排版投影，不改变正文原文、逻辑选择索引、复制与搜索。标记开关变化会触发重新排版。

标记按横排布局框的物理右侧放置，此能力不承诺 RTL（从右向左）或 Bidi（双向文本）的逻辑末尾方向。

## 体验与构建

```shell
./gradlew jvmTest compileAndroidMain :preview:compileKotlinJvm
./gradlew :preview:run
```

## 许可证

Tiqian Markdown 以 [Mozilla Public License 2.0](LICENSE) 发布。第三方组件与字体见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

Painter 末端标记通过 `CjkInlineObject` 真实预留行内宽度并参与断行。U+FFFC 只存在于内部排版 projection；core 的 `addCjkRenderOnlyInlineObject` 映射会从逻辑文本、复制、选区索引剔除该占位，正文 `MarkdownText.value` 与搜索原文不变。此能力需要包含该 core API 的依赖版本。
