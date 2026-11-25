package com.vehiclescanner.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vehiclescanner.AppContainer
import com.vehiclescanner.VehicleScannerApp
import com.vehiclescanner.core.ImportAnalyzer
import com.vehiclescanner.core.ImportPreview
import com.vehiclescanner.core.Table
import com.vehiclescanner.data.Spreadsheets
import com.vehiclescanner.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ImportUiState(
    val fileName: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val tables: List<Table> = emptyList(),
    val sheet: Int = 0,
    val column: Int = -1,
    val hasHeader: Boolean = true,
    val autoDetected: Boolean = false,
    val allowNonStandard: Boolean = false,
    val preview: ImportPreview? = null,
    val importing: Boolean = false,
    val progress: Int = 0,
    val imported: Int? = null
)

class ImportViewModel(app: Application) : AndroidViewModel(app) {
    private val c: AppContainer = (app as VehicleScannerApp).container
    private val _s = MutableStateFlow(ImportUiState(allowNonStandard = c.settings.state.value.allowNonStandard))
    val state = _s.asStateFlow()

    fun load(uri: Uri) {
        val name = runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: "Selected file"
        _s.value = ImportUiState(fileName = name, loading = true, allowNonStandard = _s.value.allowNonStandard)
        viewModelScope.launch {
            runCatching { Spreadsheets.read(getApplication(), uri) }
                .onSuccess { tables ->
                    val nonEmpty = tables.filter { it.rows.isNotEmpty() }.ifEmpty { tables }
                    _s.update { it.copy(loading = false, tables = nonEmpty) }
                    selectSheet(nonEmpty.indexOfFirst { t -> ImportAnalyzer.guessColumn(t) != null }.coerceAtLeast(0))
                }
                .onFailure { e -> _s.update { it.copy(loading = false, error = e.message ?: "Could not read the file") } }
        }
    }

    fun selectSheet(i: Int) {
        val t = _s.value.tables.getOrNull(i) ?: return
        val g = ImportAnalyzer.guessColumn(t)
        _s.update { it.copy(sheet = i, column = g?.column ?: -1, hasHeader = g?.hasHeader ?: true, autoDetected = g != null, imported = null) }
        analyze()
    }

    fun selectColumn(col: Int) {
        val t = _s.value.tables.getOrNull(_s.value.sheet) ?: return
        _s.update { it.copy(column = col, autoDetected = false, hasHeader = ImportAnalyzer.looksLikeHeader(t, col)) }
        analyze()
    }

    fun setHeader(h: Boolean) { _s.update { it.copy(hasHeader = h) }; analyze() }
    fun setAllowNonStandard(a: Boolean) { _s.update { it.copy(allowNonStandard = a) }; analyze() }

    private fun analyze() {
        val s = _s.value
        val t = s.tables.getOrNull(s.sheet) ?: return
        if (s.column < 0) { _s.update { it.copy(preview = null) }; return }
        viewModelScope.launch {
            val existing = c.vehicles.allNumbers()
            val p = withContext(Dispatchers.Default) { ImportAnalyzer.analyze(t, s.column, s.hasHeader, existing, s.allowNonStandard) }
            _s.update { it.copy(preview = p) }
        }
    }

    fun runImport() {
        val p = _s.value.preview ?: return
        _s.update { it.copy(importing = true, progress = 0) }
        viewModelScope.launch {
            val n = runCatching { c.vehicles.importNumbers(p.toImport) { done -> _s.update { it.copy(progress = done) } } }
            _s.update { it.copy(importing = false, imported = n.getOrNull(), error = n.exceptionOrNull()?.message) }
        }
    }
}

@Composable
fun ImportScreen(c: AppContainer, onBack: () -> Unit) {
    val vm: ImportViewModel = viewModel()
    val s by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.load(uri) }
    val sc = LocalStatusColors.current
    val mimes = arrayOf(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values",
        "application/vnd.ms-excel", "text/plain", "application/octet-stream"
    )

    Scaffold(topBar = { AppBar("Import Excel / CSV", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            ActionButton(s.fileName ?: "Choose file", if (s.fileName == null) "Excel .xlsx or .csv from phone storage" else "Tap to choose a different file",
                Icons.Default.UploadFile, primary = s.fileName == null) { picker.launch(mimes) }

            if (s.loading) { Spacer(Modifier.height(16.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
            s.error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = MaterialTheme.colorScheme.error) }

            val table = s.tables.getOrNull(s.sheet)
            if (table != null && !s.loading) {
                if (s.tables.size > 1) {
                    SectionTitle("Worksheet")
                    Dropdown(s.tables.map { "${it.name} (${it.rows.size} rows)" }, s.sheet, vm::selectSheet)
                }
                SectionTitle("Vehicle number column")
                val columns = (0 until table.columnCount).map { col ->
                    val head = if (s.hasHeader) table.cell(0, col).ifBlank { "(no header)" } else "Column"
                    val sample = table.rows.drop(if (s.hasHeader) 1 else 0).take(3).map { it.getOrNull(col).orEmpty() }.filter { it.isNotBlank() }
                    "${columnLetter(col)} · $head — ${sample.joinToString(", ").take(40)}"
                }
                if (s.column < 0) Text("Could not detect the vehicle number column automatically. Please choose it:",
                    color = sc.review, style = MaterialTheme.typography.bodySmall)
                Dropdown(columns, s.column, vm::selectColumn, placeholder = "Select column")
                if (s.autoDetected) Text("Detected automatically", style = MaterialTheme.typography.labelSmall, color = sc.match)

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("First row is a header", Modifier.weight(1f)); Switch(s.hasHeader, vm::setHeader)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Also import non-standard numbers")
                        Text("Old/special plates that don't match the Indian format", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(s.allowNonStandard, vm::setAllowNonStandard)
                }

                s.preview?.let { p ->
                    SectionTitle("Import summary")
                    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            SummaryRow("Total rows detected", p.totalRows)
                            SummaryRow("Will be imported", p.toImport.size, sc.match)
                            if (p.toImport.size != p.validCount) SummaryRow("  of which non-standard", p.toImport.size - p.validCount, sc.review)
                            SummaryRow("Duplicate entries in file", p.duplicatesInFile, sc.review)
                            SummaryRow("Already in database", p.alreadyInDatabase)
                            SummaryRow("Invalid entries", p.invalid.size, sc.notFound)
                            SummaryRow("Empty rows", p.emptyRows)
                        }
                    }
                    if (p.invalid.isNotEmpty()) {
                        var show by remember { mutableStateOf(false) }
                        TextButton(onClick = { show = !show }) { Text(if (show) "Hide invalid entries" else "Show invalid entries (${p.invalid.size})") }
                        if (show) p.invalid.take(200).forEach {
                            Text("Row ${it.row}: \"${it.value}\"", style = MaterialTheme.typography.bodySmall, color = sc.notFound)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    when {
                        s.importing -> {
                            Text("Importing ${s.progress} / ${p.toImport.size}")
                            LinearProgressIndicator(progress = { s.progress / p.toImport.size.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth())
                        }
                        s.imported != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = sc.match)
                            Text("  Imported ${s.imported} vehicles", fontWeight = FontWeight.Bold, color = sc.match)
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = onBack) { Text("Done") }
                        }
                        else -> Button(onClick = vm::runImport, enabled = p.toImport.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(if (p.toImport.isEmpty()) "Nothing new to import" else "Import ${p.toImport.size} vehicles")
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

private fun columnLetter(i: Int) = com.vehiclescanner.core.XlsxWriter.colName(i)

@Composable
private fun SummaryRow(label: String, value: Int, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    Row { Text(label, Modifier.weight(1f)); Text("%,d".format(value), fontWeight = FontWeight.Bold, color = color) }
}

@Composable
private fun Dropdown(options: List<String>, selected: Int, onSelect: (Int) -> Unit, placeholder: String = "") {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(options.getOrNull(selected) ?: placeholder, Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, { open = false }) {
            options.forEachIndexed { i, o -> DropdownMenuItem(text = { Text(o, maxLines = 1) }, onClick = { open = false; onSelect(i) }) }
        }
    }
}

