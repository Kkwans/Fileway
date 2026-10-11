# Markdown code highlighting

Runtime: `dev.snipme:highlights-jvm:1.0.0` (Apache-2.0), tag `1.0.0`, commit
`8437fb3fa708a49d5938e738d321d7a7fa05b19a`. Copyright 2023-2024 Tomasz Kądziołka.

- [Fixed published POM](https://repo.maven.apache.org/maven2/dev/snipme/highlights-jvm/1.0.0/highlights-jvm-1.0.0.pom)
- [Fixed source](https://github.com/SnipMeDev/Highlights/tree/8437fb3fa708a49d5938e738d321d7a7fa05b19a)
- [Original LICENSE](https://github.com/SnipMeDev/Highlights/blob/8437fb3fa708a49d5938e738d321d7a7fa05b19a/LICENSE)

Published JAR SHA256: `075ed2997b1dc7c503f1952b50101171ff9c659808926b9e41fea99a41260a10`.
Published sources JAR SHA256: `00a0c57bf05159d06bab74da15fa8ece3ff080df012a127c52ae1afcdbbe22ff`.
The bundled original LICENSE is byte-for-byte unchanged. The upstream source
and JAR have no separate NOTICE; highlights-NOTICES.txt explicitly identifies
our retained distribution attribution. Deliver both TXT files through the app's
existing `assets/licenses` route when enabling the dependency.

The renderer remains `0.39.2`; its optional code module is not included because
it requires Highlights 1.1.0, whose published JVM classes are Java 21 (major65).
The directly reused 1.0.0 engine has Java8 (major52) classes, Kotlin stdlib2.0.20,
coroutines1.9.0 and serialization JSON1.7.1 in its fixed POM. Actual resolved
versions and Android29/R8 behavior require the integrated Android gate.

The adapter uses this engine only for the seven real grammars absent from
Prism4j2.0.0: Rust, Ruby, Shell, Perl, PHP, CoffeeScript and TypeScript.
Prism4j supplies the other canonical languages and JSON/XML/HTML/SQL/YAML/CSS/
Markdown (see prism4j.md). Unknown/no-info fences remain exact plaintext.
No copied lexer, grammar or theme implementation is embedded.

The fixed Highlights1.0.0 Shell table colors builtins such as `echo`, `export`
and `printf`; it does not include control words `if`, `then` or `fi`. Shell
token coverage is therefore limited; the canonical Shell name does not imply
complete Bash/Shell syntax coverage. The adapter preserves all source text.
