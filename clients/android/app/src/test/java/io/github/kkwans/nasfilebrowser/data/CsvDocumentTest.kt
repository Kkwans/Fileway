package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CsvDocumentTest {
    private fun text(value: String) = decodeDocumentText(value.toByteArray())
    private fun read(value: String, file: String = "owned.csv", delimiter: CsvDelimiter = CsvDelimiter.AUTO): CsvDocument =
        (csvPreview(text(value), file, delimiter) as CsvPreview.Reading).document

    @Test fun quotedDelimitersEscapedQuotesMultilineCellsTrailingEmptyAndSpacesArePreserved() {
        val source = "name,notes,empty,tail\r\n\"中文,名\",\"line1\r\nline2 \"\"quoted\"\"\", ,\r\n"
        val document = read(source)
        assertEquals(CsvDelimiter.COMMA, document.delimiter)
        assertEquals(listOf("中文,名", "line1\r\nline2 \"quoted\"", " ", ""), document.rows(true).single())
        assertEquals(listOf("name", "notes", "empty", "tail"), document.headers(true))
        assertEquals(listOf("甲\r乙", "尾"), read("\"甲\r乙\",尾", delimiter = CsvDelimiter.COMMA).records.single())
    }

    @Test fun automaticSelectionUsesParsedRecordsAndIgnoresIncidentalOtherDelimiters() {
        assertEquals(CsvDelimiter.COMMA, read("标题,备注\n甲,含;分号\n乙,其他").delimiter)
        assertEquals(CsvDelimiter.SEMICOLON, read("标题;值\n甲;1\n乙;2").delimiter)
        assertEquals(CsvDelimiter.TAB, read("标题\t值\n甲\t1\n乙\t2", "owned.tsv").delimiter)
        val single = "标题\n\"含,分号;与\t制表符\"\n"
        val csv = read(single)
        val tsv = read(single, "owned.tsv")
        assertEquals(CsvDelimiter.COMMA, csv.delimiter)
        assertEquals(CsvDelimiter.TAB, tsv.delimiter)
        assertEquals(csv.records, tsv.records)
        assertEquals(listOf("含,分号;与\t制表符"), csv.rows(true).single())
        assertEquals(0, read("").columns)
    }

    @Test fun differentValidResultsRequireExplicitChoiceInsteadOfGuessingFromTheExtension() {
        val source = text("a,b;c\n1,2;3")
        val ambiguous = csvPreview(source, "owned.csv") as CsvPreview.SourceOnly
        assertTrue(ambiguous.chooseDelimiter)
        assertEquals(listOf("a", "b;c"), (csvPreview(source, "owned.csv", CsvDelimiter.COMMA) as CsvPreview.Reading).document.records.first())
        assertEquals(listOf("a,b", "c"), (csvPreview(source, "owned.csv", CsvDelimiter.SEMICOLON) as CsvPreview.Reading).document.records.first())
        assertEquals("a,b;c\n1,2;3", source.text)
    }

    @Test fun raggedRowsDuplicateEmptyHeadersAndNoHeaderModeKeepEverySourceCellByIndex() {
        val document = read("dup,dup,\n1,2\n3,4,5,6\n\n", delimiter = CsvDelimiter.COMMA)
        assertTrue(document.unevenRows)
        assertEquals(4, document.columns)
        assertEquals(listOf("dup", "dup", "列 3", "列 4"), document.headers(true))
        assertEquals(listOf("dup", "dup", ""), document.rows(false).first())
        assertEquals(listOf("3", "4", "5", "6"), document.rows(true)[1])
        assertEquals(listOf(""), document.rows(true).last())
        assertEquals(listOf("列 1", "列 2", "列 3", "列 4"), document.headers(false))
        assertEquals(document.records.size, document.rows(false).size)
    }

    @Test fun incompleteQuotesNeverReturnPartialRowsAndSourceRemainsComplete() {
        val document = text("a,b\n\"unterminated\nowned tail")
        assertTrue(csvPreview(document, "owned.csv", CsvDelimiter.COMMA) is CsvPreview.SourceOnly)
        assertTrue(csvPreview(document, "owned.csv") is CsvPreview.SourceOnly)
        assertEquals("a,b\n\"unterminated\nowned tail", document.blocks.joinToString("") { it.text })
    }

    @Test fun tableBudgetsFallBackAsAWholeWithoutReinterpretingAnOversizedTableAsOneColumn() {
        val wide = text((1..65).joinToString(",") { "c$it" } + "\n" + (1..65).joinToString(",") { "$it" })
        assertTrue(csvPreview(wide, "owned.csv") is CsvPreview.SourceOnly)
        assertTrue(csvPreview(wide, "owned.csv", CsvDelimiter.COMMA) is CsvPreview.SourceOnly)
        val many = text("a,b\n".repeat(20_001))
        assertTrue(csvPreview(many, "owned.csv", CsvDelimiter.COMMA) is CsvPreview.SourceOnly)
        val longCell = text("\"" + "x".repeat(2049) + "\",tail")
        assertTrue(csvPreview(longCell, "owned.csv", CsvDelimiter.COMMA) is CsvPreview.SourceOnly)
        assertTrue(longCell.text.endsWith(",tail"))
        val manyLines = text("\"" + "x\n".repeat(128) + "end\"")
        assertTrue(csvPreview(manyLines, "owned.csv", CsvDelimiter.COMMA) is CsvPreview.SourceOnly)
        val cells = text(("a,".repeat(63) + "a\n").repeat(1563))
        assertTrue(csvPreview(cells, "owned.csv", CsvDelimiter.COMMA) is CsvPreview.SourceOnly)
    }

    @Test fun originalByteBudgetAndUtfBomRoundTripsUseTheExistingStrictDocumentDecoder() {
        val source = "标题\t值\r\n甲\t中文\r\n"
        for ((encoding, bom) in listOf("UTF-8" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()),
            "UTF-16LE" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()), "UTF-16BE" to byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) {
            val bytes = bom + source.toByteArray(charset(encoding))
            val document = decodeDocumentText(bytes)
            val table = (csvPreview(document, "owned.tsv") as CsvPreview.Reading).document
            assertEquals(CsvDelimiter.TAB, table.delimiter)
            assertEquals(listOf("甲", "中文"), table.rows(true).single())
            assertArrayEquals(bytes, encodeEditedDocument(documentEditorText(document), document))
        }
        val oversized = decodeDocumentText((" ".repeat(CSV_READING_LIMIT.toInt()) + "\nowned tail").toByteArray())
        val fallback = csvPreview(oversized, "owned.csv") as CsvPreview.SourceOnly
        assertTrue(fallback.reason.contains("2 MiB"))
        assertEquals(oversized.text, oversized.blocks.joinToString("") { it.text })
    }

    @Test fun cancellationPropagatesAndNoCellValueIsEvaluatedOrConverted() {
        var checks = 0
        assertThrows(CancellationException::class.java) {
            csvPreview(text("a,b\n1,2\n3,4"), "owned.csv", CsvDelimiter.COMMA) {
                if (++checks == 3) throw CancellationException("owned cancellation")
            }
        }
        assertEquals(3, checks)
        assertEquals(listOf("=1+1", "NULL", "<script>literal</script>", "https://example.invalid/"),
            read("=1+1,NULL,<script>literal</script>,https://example.invalid/", delimiter = CsvDelimiter.COMMA).records.single())
        assertTrue(isCsvDocument(ResourceRef("/owned.CSV", "/owned.CSV", "owned.CSV", false, "text", 0)))
        assertFalse(isCsvDocument(ResourceRef("/owned.csv", "/owned.csv", "owned.csv", true, "", 0)))
    }
}
