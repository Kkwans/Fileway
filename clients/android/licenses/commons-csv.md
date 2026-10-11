# Native CSV / TSV reader

The Android reader uses Apache Commons CSV for CSV grammar and escaping, not an application-written CSV parser. UI controls and auto-detection compare bounded parsed records. The decoded original document remains the source/search/edit input; no rendered cell is written back.

| Runtime artifact | Fixed version | License |
| --- | --- | --- |
| `org.apache.commons:commons-csv` | `1.14.1` | Apache-2.0 |
| `commons-io:commons-io` | `2.20.0` | Apache-2.0 |
| `commons-codec:commons-codec` | `1.19.0` | Apache-2.0 |

CSV 1.14.1's published POM pins IO/Codec to these versions. SpotBugs annotations are optional and not a runtime transitive requirement. Preserve the respective original `*-LICENSE.txt` and `*-NOTICE.txt` files from each fixed published JAR in redistribution. No font, spreadsheet engine, WebView or JavaScript runtime is introduced.

All three published base JAR class sets target Java 8 (class major 52); the application uses JDK17/min Android29. This static compatibility fact does not substitute for Android compilation/R8/native-reader execution acceptance. The reader uses StringReader; it does not use arbitrary file/network origins in Commons IO.

The initial table view accepts up to 2 MiB of original input, 20,000 records, 64 columns and 100,000 cells, with bounded cell text. It never silently returns a truncated table; unsupported/oversized input stays available in the original source viewer. Empty/duplicate headers and unequal row widths are handled by column index. Cell strings are never interpreted as HTML, formulas or links.

Official references:

- https://commons.apache.org/proper/commons-csv/
- https://github.com/apache/commons-csv/tree/rel/commons-csv-1.14.1
- https://github.com/apache/commons-io/tree/rel/commons-io-2.20.0
- https://github.com/apache/commons-codec/tree/rel/commons-codec-1.19.0
- https://www.apache.org/licenses/LICENSE-2.0

Published input provenance (SHA-256):

- [commons-csv 1.14.1](https://repo.maven.apache.org/maven2/org/apache/commons/commons-csv/1.14.1/commons-csv-1.14.1.jar): `32be0e1e76673092f5d12cb790bd2acb6c2ab04c4ea6efc69ea5ee17911c24fe`
- [commons-io 2.20.0](https://repo.maven.apache.org/maven2/commons-io/commons-io/2.20.0/commons-io-2.20.0.jar): `df90bba0fe3cb586b7f164e78fe8f8f4da3f2dd5c27fa645f888100ccc25dd72`
- [commons-codec 1.19.0](https://repo.maven.apache.org/maven2/commons-codec/commons-codec/1.19.0/commons-codec-1.19.0.jar): `5c3881e4f556855e9c532927ee0c9dfde94cc66760d5805c031a59887070af5f`
