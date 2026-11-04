# Architecture

The renderer has two boundaries:

1. The host lowers its parser AST into the renderer-owned `MarkdownRenderDocument`.
2. `TiqianMarkdown` renders prose with Tiqian and dispatches non-prose blocks and inline objects
   through Compose slots.

The module has no parser dependency. Its render model contains no Compose or parser nodes. It keeps source spans, stable keys, inline
semantics, and explicit capability issues so unsupported syntax is observable rather than silently
dropped.

The platform-neutral model and highlighter contracts live in `org.tiqian.markdown`. Compose
rendering, styles, slots, math hosting, and image-viewer APIs live in
`org.tiqian.markdown.compose`. This package boundary is independent of the current single-artifact
layout, so the model can later move to a separate core artifact without changing consumer imports.

`TiqianMarkdown` owns neither scrolling nor network image loading. Hosts compose it into their own
scroll container and may replace code, image, HTML, table, footnote, custom, thematic-break, or
unsupported-block slots as needed. For the default image path, `MarkdownImageViewerHost` accepts a
host image provider and owns figure sizing, aspect-ratio reservation, captions and a full-viewport
viewer. The provider remains responsible for fetching, authentication, caching, decoding and
animated/vector formats. The viewer retains intrinsic-pixel geometry for zooming, top-aligns long
images, preserves the gesture anchor while scaling, clamps empty edges, supports double-tap reset and
zoom, and accepts optional host action controls. Block images from the current render document form
one ordered browsing session: horizontal paging is enabled only while the active image remains at its
default fitted width, while a zoomed image retains the gesture for panning. Desktop renders previous
and next controls; the top bar reports the current and total image count. The library owns the default math boundary and ships a pinned
Lete Sans Math OpenType backend; hosts select another `MarkdownMathFont` or replace the math slots without taking a
dependency on that backend. Inline image and math slots receive pure-data marks plus the current
text style. Measured inline math keeps the original TeX source range and lowers its exact
advance/ascent/descent into `CjkInlineObject`; Tiqian therefore owns its line break, line-box expansion, and final baseline
placement instead of delegating vertical alignment to a Compose placeholder. Host-only block
and inline semantics are identified by pure-data `MarkdownCustomBlock` and `MarkdownTextMark.Custom`
values; parser nodes and Compose content stay in the host adapter.

`onNodeClick` receives every rendered block and inline family without a fixed interactive-mark
allow-list. Adapters retain each inline node as a `MarkdownTextSpan`, with an optional visual mark
and its own metadata. The event includes its authored type, attributes, payload and source identity;
unknown source coordinates are explicitly marked by `hasSourceSpan = false`. Event UTF-16 ranges
refer to the containing plain text; inline-object projections are used only to resolve geometry. Zero-length
or non-rendered syntax does not acquire a hidden click target.

Node handlers return whether they handled the event. Inline overlaps are offered inside-out, then
block ancestors, then the existing default action. Disjoint `LinkAnnotation.Clickable` ranges reuse
Tiqian hit testing; original URL and footnote annotations still preserve their breaking and styles.
Native child controls consume their own input. A slot with a node-specific default action can call
`rememberMarkdownNodeClickHandler()` before that action; an inline object's optional `onClick` is its
default action. Long presses continue through the normal text-selection or image-provider path.
Callbacks update through Compose state without rebuilding the document or measurement session.

These extension points do not introduce parsing. Hosts construct Custom block/inline values or use
metadata-only spans for their own syntax. The public sealed model does not accept arbitrary external
subclasses; Custom blocks do not expose child blocks, and Custom inline presentations do not contain
arbitrary Compose content.

Colour selection has two named modes without changing any layout geometry. `MarkdownPalette.Default`
uses Tiqian's stable built-in colours. `MarkdownPalette.Platform` maps the current Material 3 colour
scheme and base typography into `MarkdownStyle` while retaining Tiqian's body size, line height,
heading ratios, spacing and marker geometry. Because this artifact is the Compose frontend,
`TiqianMarkdown` resolves `Platform` by default; `rememberMarkdownStyle(MarkdownPalette.Default)`
selects the built-in palette explicitly. A host such as Zhihu++ can therefore follow its Material
theme without exposing the built-in palette as an application preference.
In `Platform` mode, block-quote prose inherits `onSurfaceVariant`, while the quote rule, table rules
and thematic breaks use `outlineVariant`; links and other explicitly coloured inline semantics keep
their own roles.

The formula renderer, rather than this Markdown module, derives source-contiguous fragments from
its own AST and retains the measured layout used to paint each one. It exposes real line breaks
after binary and relation operators on the main formula baseline, including those inside ordinary
or automatic delimiters; fractions, roots, scripts, matrices and other stacked structures remain
atomic. Additional fragment boundaries expose measured spacing without inventing line breaks.
When a post-operator break is chosen, the operator remains on the preceding line while its measured
following math space is discarded as line-edge glue. The same space remains present when no break
is taken, and the next fragment never receives a matching leading blank.

Tiqian first stretches the renderer-measured space after punctuation, then both sides of relation
operators, then both sides of binary operators. Each edge reports its measured natural blank and an
absolute `0.5em` preferred target. Once those preferred resources reach that target, the same edges
still join the final uniform spacing pass. The math blank already measured at an internal boundary
may be removed only as the last compression tier; formula glyphs are never scaled. Chinese and ASCII
point marks following the last formula fragment remain covered by Tiqian kinsoku and cannot start an
automatically wrapped line. If the host adapter retained a Markdown separator space between the
formula and the mark, Tiqian preserves its source range but collapses its layout advance to zero and
keeps the whole formula-space-mark sequence together.

Text blocks always use `CjkText`; a capability report is diagnostic data and never switches an
entire paragraph to a second `BasicText` layout. Inline image/math providers that expose advance,
ascent and descent lower to Tiqian-native inline objects. An unmeasured provider is not substituted:
the source alternate text remains in the paragraph instead. Host dashed and dotted decorations
lower into Tiqian's normal underline role, so they share the final source boundaries, glue trim,
underline position and skip-ink geometry.

Inline-code backgrounds lower both their true-end radius and their visual-line continuation radius
to Tiqian's background geometry. The default keeps a `3dp` radius at the two source endpoints and
uses `1dp` only on a side created by wrapping; highlights and other generic backgrounds keep their
authored radius on every fragment unless the host explicitly supplies a different continuation
radius.

Heading geometry is derived from the body style rather than stored as six unrelated absolute sizes.
`MarkdownHeadingScales` controls each level's body-size multiplier and line-height ratio; the six
heading `TextStyle` values remain full visual overrides for weight, family, colour, decoration, or an
intentional `sp`/`em` geometry override. The defaults follow the large, medium and small heading
steps described by [CLREQ 7.1.3.2](https://www.w3.org/TR/clreq/#h-heading_font): levels one through
three use `14/9`, `12/9` and `10/9` of the body size; lower levels stay at body size and use weight
instead of an increasingly compressed size ladder, except level six at `7/8` body size. Levels one
and two use medium weight; levels five and six use bold. Gaps involving a heading are selected from
the adjacent block pair and expressed in body line heights. Every heading level uses `1` line before;
levels one and two use `1/2` after, levels three and four use `1/4` after, and levels five and six add
no gap after. Adjacent headings retain one full body line between them.
The first block has no synthetic leading gap. Other block pairs use one half body line and compact
content uses one quarter line. Tight-list items add no gap beyond the normal body line height;
adjacent list blocks use one quarter line, and loose-list items use one half line. The legacy `Dp`
spacing values are only fallbacks when the body line height cannot be resolved.

List markers occupy the smallest whole-`ic` gutter that contains the widest marker in the list.
Ordered markers align against the body column. Unordered bullets and task checkboxes are left-aligned
inside the whole-`ic` marker cell immediately before the body; when the body indent grows to two
cells, the first cell remains empty and the marker occupies the second. The body column has a one-`ic`
minimum indent at ordinary measures and a two-`ic` minimum only when the measure is strictly wider
than Tiqian's
`KinsokuMode.MeasureAdaptive.strictAboveEm` boundary (32 `ic` by default). A wider marker always
expands the gutter instead of being squeezed into either minimum. Task-list source markers (`[x]`
and `[ ]`) are not rendered as text: both lower to disabled-state Material Symbols in a one-`ic`
marker cell whose vertical position
comes from the same measured body-font metrics and first baseline as the list body.

Code blocks use one rounded container with a file/language metadata row, a copy action, a line-number
column, and horizontally scrolling source. The displayed language label is uppercased without changing
the adapter-provided language identifier or copied source. The compact copy control fits inside the
metadata row without determining its height: metadata text fixes the row height, then the control takes
the remaining inset height as both its height and width. Its rounded rectangle is inset from the code
frame and uses the corresponding concentric corner radius. Syntax colour never depends on parser nodes:
explicit adapter-provided ranges take precedence, while the default pure-data lexical highlighter derives
ranges from the fenced language and source text. Hosts can replace it with a grammar-aware
`MarkdownCodeHighlighter` without leaking parser nodes. Display math uses
the body font size by default, remains horizontally centred, and has no default background; an
explicit non-transparent `mathBackground` restores the styled container.

Tables draw one outer rounded border and exactly one rule at every internal row or column boundary;
cells never stack their own complete borders. Columns start from their natural single-line widths;
spare width raises the narrowest columns toward their wider neighbours, while an insufficient measure
uses the configured minimum widths and horizontal scrolling. Table prose uses `7/8` of the body type geometry,
with medium-weight header cells. Inline formula marks inside cells use the same measured inline-math
path as prose rather than a text approximation.
Adapters can attach upright medium-size captions as pure `MarkdownText`: figure captions render
below the image and table captions above the table. A host replacing either block slot owns the
corresponding caption placement but receives the same model field.

Footnote references retain the body font size, shift to the superscript position, and use a link
colour without an underline. Definitions use the smaller footnote text style and no renderer-added
return glyph. Every occurrence of a repeated reference links to the same definition. Definitions do
not hide a stateful backlink in their plain marker; a future reverse-navigation presentation must
expose every occurrence explicitly instead of guessing a destination from interaction history.
`MarkdownFootnotePlacement` offers deterministic paragraph-end, heading-section-end,
and article-end placement; paragraph-end is the default. The first reference owns a definition,
multiple definitions follow first-reference order, and an unreferenced definition remains at the
article end. Placement never changes with viewport distance. Paged footnotes and sidenotes belong at
the future PageFlow boundary.
