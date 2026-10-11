# CommonMark extension parsing

- Upstream: https://github.com/commonmark/commonmark-java
- Pinned artifacts: `org.commonmark:commonmark`, `commonmark-ext-footnotes`, and `commonmark-ext-yaml-front-matter`, all `0.30.1`.
- License: BSD 2-Clause; full upstream artifact license in `commonmark-BSD-2-Clause.txt`.
- Copyright (c) 2015, Atlassian Pty Ltd.
- Used only for native reader extension AST/source spans and raw YAML boundaries. Existing JetBrains Markdown/Compose components still render the document; no CommonMark HTML output or arbitrary YAML deserialization is used.
- Java 11 bytecode; the parser calls Java 9 collection factories (`List.of`, `Set.of`). API 29 therefore uses the pinned Android core-library desugaring configuration.
