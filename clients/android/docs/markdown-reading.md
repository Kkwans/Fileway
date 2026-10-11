# Native Markdown reading

Markdown files keep their original text reader, encoding/BOM, source search and
copy, draft/conflict and conditional-save paths. Reading uses Compose Markdown
renderer 0.39.2 and JetBrains Markdown 0.7.3, with CommonMark 0.30.1's official
footnote and raw front-matter extensions providing source-position indexes.

- A closed YAML front matter block is collapsed initially. Expansion shows its
  complete original text; arbitrary YAML objects are never deserialized. An
  unclosed header remains ordinary Markdown rather than swallowing the body.
- Footnotes are numbered in first-reference order. Repeated references lead to
  the same note; return and Back lead to the reference actually followed.
  Multiline bodies reuse native Markdown components. Undefined markers, duplicate
  definitions, unreferenced definitions and unsupported nested definitions remain
  locally visible instead of hiding the document or silently losing source.
- A standalone root paragraph `[TOC]` uses the existing heading index and actual
  layout scroll targets. Inline text, lists, quotes and code do not create a TOC.
- Formula AST nodes share one document-scoped offline MathJax 4.1.3 session, with
  SVG output drawn by native Coil/Compose. Main text and footnotes have distinct
  keys. Headings, paragraphs, lists, quotes and table cells retain their native
  text, links and selection. Formula errors and budget limits retain source TeX.
  The document is never displayed by a WebView. See `licenses/markdown-math.md`.

The original source string is never replaced by a display projection. UTF-16
source spans, including CRLF, tabs and supplementary characters, address the
original text. Rich reading retains the 2 MiB input, depth 64, 20,000 rendering
node and 16,384-character/512-line block limits. Footnote indexes additionally
allow at most 256 definitions and 2,048 references. Exceeding document complexity
keeps the existing complete-source fallback. Mathematical layout has separate
bounded-input, SVG and elapsed-time limits.

Images keep the existing same-account resource resolver, capability leases and
bounded shared image pool. Footnote bodies inherit reference definitions but have
their own AST-offset composition namespace; no additional network/image loader
or external-origin automatic fetch is introduced. Author HTML stays literal and
external links still require an explicit user action and the safe Intent policy.

CommonMark uses Java collection factories introduced in Android API 30, so API 29
uses pinned core-library desugaring 2.1.5. All dependency licenses are shipped in
the app's maintained license assets. This document describes source behavior;
build, device rendering and real-NAS acceptance are separate validation gates.
