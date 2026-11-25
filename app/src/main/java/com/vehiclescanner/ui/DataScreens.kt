package com.vehiclescanner.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vehiclescanner.AppContainer
import com.vehiclescanner.core.PlateNormalizer
import com.vehiclescanner.data.AddResult
import com.vehiclescanner.data.FileFormat
import com.vehiclescanner.data.VehicleEntity
import com.vehiclescanner.ui.theme.LocalStatusColors
import com.vehiclescanner.ui.theme.PlateTextStyle
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ vehicle database

@Composable
fun DatabaseScreen(c: AppContainer, onBack: () -> Unit, onImport: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    val list by remember(query) { c.vehicles.search(query) }.collectAsStateWithLifecycle(emptyList())
    val count by c.vehicles.count.collectAsStateWithLifecycle(0)
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<VehicleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<VehicleEntity?>(null) }
    var deleteAll by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    fun toast(s: String) = Toast.makeText(context, s, Toast.LENGTH_SHORT).show()
    fun report(r: AddResult) = when (r) {
        is AddResult.Added -> toast("Saved ${PlateNormalizer.format(r.number)}")
        is AddResult.Duplicate -> toast("${PlateNormalizer.format(r.number)} is already in the database")
        is AddResult.Invalid -> Toast.makeText(context, r.reason, Toast.LENGTH_LONG).show()
    }
    val exporter = { f: FileFormat -> { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            runCatching { c.export.exportVehicles(uri, f) }.onSuccess { toast("Exported") }.onFailure { toast("Export failed: ${it.message}") }
        }
        Unit
    } }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileFormat.CSV.mime), exporter(FileFormat.CSV))
    val exportXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileFormat.XLSX.mime), exporter(FileFormat.XLSX))

    Scaffold(
        topBar = {
            AppBar("Vehicle database", onBack) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Import Excel / CSV") }, onClick = { menu = false; onImport() })
                        DropdownMenuItem(text = { Text("Export list as CSV") }, onClick = { menu = false; exportCsv.launch("vehicles.csv") })
                        DropdownMenuItem(text = { Text("Export list as Excel") }, onClick = { menu = false; exportXlsx.launch("vehicles.xlsx") })
                        DropdownMenuItem(text = { Text("Delete all vehicles", color = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; deleteAll = true })
                    }
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add vehicle") })
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search number or note") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
            )
            Text(
                "%,d saved vehicles".format(count) + if (query.isNotBlank()) " · ${list.size}${if (list.size >= 500) "+" else ""} shown" else "",
                Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (count == 0) {
                EmptyState(Icons.Default.DirectionsCar, "No vehicles yet", "Add numbers one by one or import an Excel / CSV file.")
                TextButton(onClick = onImport, Modifier.align(Alignment.CenterHorizontally)) { Text("Import Excel / CSV") }
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { v ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(PlateNormalizer.format(v.number), style = PlateTextStyle.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize))
                                if (v.note.isNotBlank()) Text(v.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { editing = v }) { Icon(Icons.Default.Edit, "Edit") }
                            IconButton(onClick = { deleting = v }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }

    if (adding) VehicleDialog(null, settings.allowNonStandard, onDismiss = { adding = false }) { num, note ->
        scope.launch { report(c.vehicles.add(num, note, settings.allowNonStandard)) }
    }
    editing?.let { v ->
        VehicleDialog(v, settings.allowNonStandard, onDismiss = { editing = null }) { num, note ->
            scope.launch { report(c.vehicles.update(v, num, note, settings.allowNonStandard)) }
        }
    }
    deleting?.let { v ->
        ConfirmDialog("Delete ${PlateNormalizer.format(v.number)}?", "This vehicle will be removed from the database.", "Delete", true,
            onConfirm = { scope.launch { c.vehicles.delete(v.id) } }, onDismiss = { deleting = null })
    }
    if (deleteAll) ConfirmDialog("Delete all $count vehicles?", "This cannot be undone. Consider making a backup first (Settings → Backup).",
        "Delete all", true, onConfirm = { scope.launch { c.vehicles.deleteAll() } }, onDismiss = { deleteAll = false })
}

@Composable
private fun VehicleDialog(v: VehicleEntity?, allowNonStandard: Boolean, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var number by remember { mutableStateOf(v?.let { PlateNormalizer.format(it.number) } ?: "") }
    var note by remember { mutableStateOf(v?.note ?: "") }
    val cleaned = PlateNormalizer.clean(number)
    val valid = PlateNormalizer.isValid(cleaned)
    val ok = cleaned.length in 4..12 && (valid || allowNonStandard)
    val sc = LocalStatusColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (v == null) "Add vehicle" else "Edit vehicle") },
        text = {
            Column {
                OutlinedTextField(number, { number = it.uppercase() }, label = { Text("Registration number") }, singleLine = true,
                    placeholder = { Text("KL 08 AB 1234") }, textStyle = PlateTextStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Text(
                    when {
                        cleaned.isEmpty() -> "Spaces and hyphens are ignored"
                        valid -> "✓ Saved as ${cleaned}"
                        allowNonStandard -> "⚠ Non-standard format – will be saved as $cleaned"
                        else -> "✗ Not a valid Indian registration number"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) sc.match else if (allowNonStandard && cleaned.isNotEmpty()) sc.review else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onSave(number, note); onDismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ history

@Composable
fun HistoryScreen(c: AppContainer, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val sessions by c.db.scans().sessions().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Long?>(null) }
    val sc = LocalStatusColors.current
    Scaffold(topBar = { AppBar("Scan history", onBack) }) { pad ->
        if (sessions.isEmpty()) {
            EmptyState(Icons.Default.History, "No scans yet", "Scans are saved here automatically.", Modifier.padding(pad))
        }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) { s ->
                Surface(onClick = { onOpen(s.id) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(File(s.imagePath), null, Modifier.size(76.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.timestamp)),
                                style = MaterialTheme.typography.titleSmall)
                            Text("${s.total} plates detected", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("${s.matched} match", color = sc.match, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("${s.notFound} not found", color = sc.notFound, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text("${s.review} review", color = sc.review, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        IconButton(onClick = { deleting = s.id }) { Icon(Icons.Default.Delete, "Delete") }
                    }
                }
            }
        }
    }
    deleting?.let { id ->
        ConfirmDialog("Delete scan?", "Its photo and results will be removed.", "Delete", true,
            onConfirm = { scope.launch { c.scanner.deleteSession(id) } }, onDismiss = { deleting = null })
    }
}
