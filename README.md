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

## 宿主通过搜索状态查找并定位正文

`org.tiqian.markdown.compose.rememberMarkdownTextSearchState` 创建一个 Surface 专用的搜索状态。
宿主提供搜索面板，并在面板关闭时调用 `clear()`。以下代码中的 `searchVisible`、`queryInput` 和
`document` 由宿主提供；示例按钮定位首个结果，宿主也可以为结果列表中的每个条目提供定位按钮。

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.tiqian.markdown.compose.TiqianMarkdownSurface
import org.tiqian.markdown.compose.rememberMarkdownTextSearchState

// 放在宿主的 @Composable 函数中。
val search = rememberMarkdownTextSearchState()
val scroll = rememberScrollState()
val scope = rememberCoroutineScope()

LaunchedEffect(searchVisible, queryInput) {
    if (searchVisible) search.setQuery(queryInput) else search.clear()
}
TiqianMarkdownSurface(
    document = document,
    scrollState = scroll,
    searchState = if (searchVisible) search else null,
)
search.matches.firstOrNull()?.let { match ->
    Button(onClick = {
        scope.launch {
            val revealed = search.reveal(match)
            // revealed 为 false 时，宿主可提示结果已失效或定位已取消。
        }
    }) {
        Text("定位首个结果（共 ${search.matches.size} 项）")
    }
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

默认高亮仅覆盖当前命中，颜色为 `Color.Yellow.copy(alpha = 0.3f)`；设置
`highlightActiveMatch = false` 可关闭高亮而保留定位。搜索不会修改宿主的 `selectionState`。
查询改变、`clear()`、文档替换、Surface 卸载或 `searchState` 改为 `null` 时，待处理请求取消，
当前高亮和目标 owner 一同清除。新的定位请求会取消上一个请求；调用方协程取消会正常传播。
过期结果返回 `false`。卸载保留查询文字，重新绑定时重新匹配；绑定之前调用 `setQuery()` 也有效。
一个搜索状态不能同时绑定两个 Surface。

`searchState = null` 时，渲染器不创建搜索文本数据、目标注册、布局监听或高亮绘制节点。
启用搜索后，渲染器复用选区的阅读顺序遍历构建文本数据；每次查询扫描这些文本，不测量离屏文字。
搜索高亮与定位复用内联解析器的偏移映射和实际排版结果，不维护第二份排版模型。

## 体验与构建

```shell
./gradlew jvmTest compileAndroidMain :preview:compileKotlinJvm
./gradlew :preview:run
```

## 许可证

Tiqian Markdown 以 [Mozilla Public License 2.0](LICENSE) 发布。第三方组件与字体见
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
