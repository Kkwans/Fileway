# Native Markdown renderer

- Upstream: https://github.com/mikepenz/multiplatform-markdown-renderer
- Pinned Android Material 3 artifact: `com.mikepenz:multiplatform-markdown-renderer-m3-android:0.39.2`.
- License: Apache-2.0. The complete Apache license is already preserved in `coil-APACHE-2.0.txt`.
- Copyright: Mike Penz, 2023/2025. The upstream README also attributes portions derived from MarkdownComposer to Erik Hellman, 2020, under MIT; that notice is preserved in `markdown-renderer-MIT.txt`.
- Parsing dependency: JetBrains Markdown `0.7.3`, supplied by the pinned renderer, under Apache-2.0.
- Usage: reuse the native Compose renderer, Material 3 typography/color integration, GFM components and lazy rendering. Application adapters enforce safe links and literal HTML, and add accessible code-copy/wrapping controls and complete aligned table cells.
- No WebView or JavaScript runtime is introduced. Inline image loading is disabled in the initial slice; authenticated relative resources require a separate owner-bound capability adapter.
- Version selection is deliberate: `0.39.2` publishes Java 17 bytecode. Newer `0.45.0` publishes Java 21 bytecode and newer coroutine dependencies; adopting that version requires a separate toolchain and dependency gate.
