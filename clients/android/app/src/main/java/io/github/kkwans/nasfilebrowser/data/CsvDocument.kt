package io.github.kkwans.nasfilebrowser.data

import io.github.kkwans.nasfilebrowser.app.ResourceRef
import java.io.StringReader
import java.util.Locale
import kotlinx.coroutines.CancellationException
import org.apache.commons.csv.CSVFormat

internal const val CSV_READING_LIMIT = 2L * 1024 * 1024
private const val MAX_CSV_ROWS = 20_000
private const val MAX_CSV_COLUMNS = 64
private const val MAX_CSV_CELLS = 100_000
private const val MAX_CSV_CELL_CHARACTERS = 2048
private const val MAX_CSV_CELL_LINES = 128

internal enum class CsvDelimiter(val separator: Char?, val label: String) {
    AUTO(null, "自动"), COMMA(',', "逗号"), SEMICOLON(';', "分号"), TAB('\t', "Tab")
}

internal fun isCsvDocument(file: ResourceRef): Boolean = !file.directory &&
    file.name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("csv", "tsv")

/** All records remain intact; header choice and ragged-row padding are display-only. */
internal data class CsvDocument(val delimiter: CsvDelimiter, val records: List<List<String>>, val columns: Int, val longCells: Boolean) {
    val unevenRows: Boolean get() = records.any { it.size != columns }
    fun headers(firstRowHeader: Boolean): List<String> = List(columns) { column ->
        if (firstRowHeader) records.firstOrNull()?.getOrNull(column)?.takeIf { it.isNotBlank() } ?: "列 ${column + 1}"
        else "列 ${column + 1}"
    }
    fun rows(firstRowHeader: Boolean): List<List<String>> =
        if (firstRowHeader && records.isNotEmpty()) records.subList(1, records.size) else records
}

internal sealed interface CsvPreview {
    data class Reading(val document: CsvDocument) : CsvPreview
    data class SourceOnly(val reason: String, val chooseDelimiter: Boolean = false) : CsvPreview
}

private class CsvReadingLimit(val explanation: String) : IllegalArgumentException(explanation)

private fun csvCellLines(value: String): Int = 1 + value.indices.count { index ->
    value[index] == '\n' || value[index] == '\r' && (index + 1 == value.length || value[index + 1] != '\n')
}

private fun parseCsv(text: String, delimiter: CsvDelimiter, checkCancelled: () -> Unit): CsvDocument {
    val format = CSVFormat.RFC4180.builder().setDelimiter(requireNotNull(delimiter.separator))
        .setIgnoreEmptyLines(false).setIgnoreSurroundingSpaces(false).setTrim(false)
        .setLenientEof(false).setTrailingData(false).get()
    val rows = mutableListOf<List<String>>()
    var cells = 0
    var columns = 0
    var longCells = false
    format.parse(StringReader(text)).use { parser ->
        for (record in parser) {
            checkCancelled()
            if (rows.size >= MAX_CSV_ROWS) throw CsvReadingLimit("表格超过 20,000 行阅读上限，请查看完整源码；原文件未改变。")
            if (record.size() > MAX_CSV_COLUMNS) throw CsvReadingLimit("表格超过 64 列阅读上限，请查看完整源码；原文件未改变。")
            cells += record.size()
            if (cells > MAX_CSV_CELLS) throw CsvReadingLimit("表格单元格数量较大，请查看完整源码；原文件未改变。")
            columns = maxOf(columns, record.size())
            val values = record.toList()
            longCells = longCells || values.any { it.length > 128 || csvCellLines(it) > 4 }
            rows.add(values)
        }
    }
    return CsvDocument(delimiter, rows, columns, longCells)
}

private fun validateCsvCells(document: CsvDocument): CsvDocument {
    if (document.records.any { row -> row.any { it.length > MAX_CSV_CELL_CHARACTERS || csvCellLines(it) > MAX_CSV_CELL_LINES } })
        throw CsvReadingLimit("表格含过长的单元格，请查看完整源码；原文件未改变。")
    return document
}

/** Commons CSV owns tokenization/escaping. AUTO compares bounded full parses, not raw delimiters. */
internal fun csvPreview(document: DocumentText, fileName: String, delimiter: CsvDelimiter = CsvDelimiter.AUTO,
    checkCancelled: () -> Unit = {}): CsvPreview {
    if (maxOf(document.byteSize, document.text.length.toLong()) > CSV_READING_LIMIT)
        return CsvPreview.SourceOnly("表格超过 2 MiB 阅读渲染上限，请查看完整源码；原文件未改变。")
    return try {
        checkCancelled()
        if (delimiter != CsvDelimiter.AUTO) return CsvPreview.Reading(validateCsvCells(parseCsv(document.text, delimiter, checkCancelled)))
        val candidates = mutableListOf<CsvDocument>()
        var limit: CsvReadingLimit? = null
        for (choice in CsvDelimiter.entries.filter { it != CsvDelimiter.AUTO }) {
            checkCancelled()
            try { candidates.add(parseCsv(document.text, choice, checkCancelled)) }
            catch (error: CancellationException) { throw error }
            catch (error: CsvReadingLimit) { limit = error }
            catch (_: Exception) { /* A strict parse failed; never return its partial rows. */ }
        }
        if (limit != null) return CsvPreview.SourceOnly(requireNotNull(limit).explanation, chooseDelimiter = true)
        if (candidates.isEmpty()) return CsvPreview.SourceOnly("表格格式无法完整解析，请检查引号或分隔符，或查看完整源码。", chooseDelimiter = true)
        val preferred = if (fileName.substringAfterLast('.', "").equals("tsv", ignoreCase = true)) CsvDelimiter.TAB else CsvDelimiter.COMMA
        val strong = candidates.filter { candidate ->
            val widths = candidate.records.groupingBy { it.size }.eachCount()
            widths.any { (width, count) -> width > 1 && count * 5 >= candidate.records.size * 4 }
        }
        val viable = when {
            strong.isNotEmpty() -> strong
            candidates.all { it.columns <= 1 } -> candidates
            else -> return CsvPreview.SourceOnly("暂不能可靠识别分隔符，请选择逗号、分号或 Tab，或查看完整源码。", chooseDelimiter = true)
        }
        if (viable.any { it.records != viable.first().records })
            return CsvPreview.SourceOnly("检测到不同的有效表格结果，请选择分隔符，或查看完整源码。", chooseDelimiter = true)
        val selected = viable.firstOrNull { it.delimiter == preferred } ?: viable.first()
        CsvPreview.Reading(validateCsvCells(selected))
    } catch (error: CancellationException) { throw error }
    catch (error: CsvReadingLimit) { CsvPreview.SourceOnly(error.explanation) }
    catch (_: Exception) { CsvPreview.SourceOnly("表格格式无法完整解析，请检查引号或分隔符，或查看完整源码。") }
}
