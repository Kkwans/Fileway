package io.github.kkwans.nasfilebrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kkwans.nasfilebrowser.data.CsvDelimiter
import io.github.kkwans.nasfilebrowser.data.CsvPreview
import io.github.kkwans.nasfilebrowser.data.DocumentText
import io.github.kkwans.nasfilebrowser.data.csvPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A read-only native view of decoded text; source/search/edit stay with their existing owner. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CsvReader(document: DocumentText, fileName: String, onShowSource: () -> Unit, modifier: Modifier = Modifier) {
    var delimiter by rememberSaveable(document, fileName) { mutableStateOf(CsvDelimiter.AUTO) }
    var firstRowHeader by rememberSaveable(document, fileName) { mutableStateOf(true) }
    var delimiterMenu by remember(document, fileName) { mutableStateOf(false) }
    val preview by produceState<CsvPreview?>(null, document, fileName, delimiter) {
        value = null
        value = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            csvPreview(document, fileName, delimiter) { context.ensureActive() }
        }
    }
    Column(modifier.fillMaxSize()) {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box {
                TextButton({ delimiterMenu = true }, Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "选择表格分隔符"
                    stateDescription = "当前选择：${delimiter.label}"
                }) {
                    Text("分隔符：${delimiter.label}")
                    Text("▾", Modifier.padding(start = 4.dp).clearAndSetSemantics {})
                }
                DropdownMenu(delimiterMenu, { delimiterMenu = false }) {
                    val current = preview
                    val details = if (current is CsvPreview.Reading) {
                        val table = current.document
                        "${document.encoding} · 只读 · ${table.delimiter.label}分隔 · ${table.rows(firstRowHeader).size} 行 · ${table.columns} 列"
                    } else "${document.encoding} · 只读"
                    Text(details, Modifier.widthIn(max = 320.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CsvDelimiter.entries.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice.label) }, onClick = {
                            delimiter = choice; delimiterMenu = false
                        }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                            contentDescription = "分隔符：${choice.label}"
                            role = Role.RadioButton
                            selected = delimiter == choice
                        }, trailingIcon = if (delimiter == choice) ({ Text("✓", Modifier.clearAndSetSemantics {}) }) else null)
                    }
                }
            }
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("首行作为表头", Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyMedium)
                Switch(firstRowHeader, { firstRowHeader = it }, Modifier.semantics { contentDescription = "首行作为表头" })
            }
        }
        when (val result = preview) {
            null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is CsvPreview.SourceOnly -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(result.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onShowSource, Modifier.heightIn(min = 48.dp)) { Text("查看完整源码") }
            }
            is CsvPreview.Reading -> {
                val table = result.document
                val rows = remember(table, firstRowHeader) { table.rows(firstRowHeader) }
                val headers = remember(table, firstRowHeader) { table.headers(firstRowHeader) }
                if (table.unevenRows) Text("各行列数不齐；已保留全部单元格，缺少的位置显示为空。",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (table.longCells)
                    Text("长单元格可上下滚动查看全文。", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (table.columns == 0) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("表格为空，可切换源码查看。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else key(table.delimiter, firstRowHeader) {
                    val horizontal = rememberScrollState()
                    Box(Modifier.weight(1f).fillMaxWidth().horizontalScroll(horizontal).padding(horizontal = 16.dp)) {
                        // The header and lazy body share one horizontal coordinate system.
                        Column(Modifier.width(48.dp + 160.dp * table.columns)) {
                            CsvRow(headers, "#", header = true, alternate = false)
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                itemsIndexed(rows, key = { index, _ -> index }) { index, cells ->
                                    CsvRow(List(table.columns) { cells.getOrElse(it) { "" } }, "${index + 1}",
                                        header = false, alternate = index % 2 == 1)
                                }
                                if (rows.isEmpty()) item {
                                    Text("只有表头，暂无数据行。", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CsvRow(cells: List<String>, number: String, header: Boolean, alternate: Boolean) {
    val background = when {
        header -> MaterialTheme.colorScheme.surfaceContainerHigh
        alternate -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }
    Column(Modifier.fillMaxWidth().background(background)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(48.dp).fillMaxHeight().padding(8.dp), contentAlignment = Alignment.TopCenter) {
                Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            cells.forEachIndexed { column, value ->
                SelectionContainer(Modifier.width(160.dp).heightIn(min = 48.dp, max = if (header) 96.dp else 320.dp)
                    .semantics { contentDescription = "第 ${column + 1} 列"; if (header) heading() }) {
                    Box(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
                            fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
