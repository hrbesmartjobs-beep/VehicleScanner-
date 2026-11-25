package com.vehiclescanner.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vehiclescanner.AppContainer
import com.vehiclescanner.data.ThemeMode
import com.vehiclescanner.scan.ModelStatus
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsStateWithLifecycle()
    val model by c.models.status.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf<String?>(null) }
    var includePhotos by remember { mutableStateOf(true) }
    var restoreReplace by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }

    fun toast(m: String) = Toast.makeText(context, m, Toast.LENGTH_LONG).show()
    fun task(label: String, block: suspend () -> String) {
        busy = label
        scope.launch {
            runCatching { block() }.onSuccess { toast(it) }.onFailure { toast("$label failed: ${it.message}") }
            busy = null
        }
    }

    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task("Model import") {
            c.models.importModel(uri).getOrThrow().let { "Model installed: $it" }
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) task("Backup") { c.backup.backup(uri, includePhotos); "Backup saved" }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pendingRestore = uri }

    Scaffold(topBar = { AppBar("Settings", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            busy?.let {
                Text("$it in progress…", color = MaterialTheme.colorScheme.secondary)
                androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            SectionTitle("Appearance")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = s.theme == m, onClick = { c.settings.update { it.copy(theme = m) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) {
                        Text(when (m) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark" })
                    }
                }
            }

            SectionTitle("Recognition accuracy")
            Card {
                Text("Minimum OCR confidence: ${(s.minConfidence * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                Text("Readings below this are never auto-matched; they go to REVIEW REQUIRED.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(s.minConfidence, { v -> c.settings.update { it.copy(minConfidence = v) } }, valueRange = 0.5f..0.98f)
                Text("Detector sensitivity threshold: ${(s.detectorThreshold * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                Text("Lower finds more plates (and more false boxes).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(s.detectorThreshold, { v -> c.settings.update { it.copy(detectorThreshold = v) } }, valueRange = 0.1f..0.8f)
                Toggle("High-detail (tiled) detection", "Scans overlapping sections so small, distant plates in crowded photos are found. Slower.",
                    s.tiledDetection) { v -> c.settings.update { it.copy(tiledDetection = v) } }
                Toggle("Combine YOLO + OpenCV detectors", "Only when a YOLO model is installed. Maximum recall, slower.",
                    s.combineDetectors) { v -> c.settings.update { it.copy(combineDetectors = v) } }
                Toggle("Allow non-standard numbers in database", "Accept old / special registrations that don't follow KL-08-AB-1234 style.",
                    s.allowNonStandard) { v -> c.settings.update { it.copy(allowNonStandard = v) } }
            }

            SectionTitle("Plate detection model")
            Card {
                Text(when (model.source) {
                    ModelStatus.Source.IMPORTED -> "YOLO model: imported"
                    ModelStatus.Source.BUNDLED -> "YOLO model: built into app"
                    ModelStatus.Source.NONE -> "No YOLO model – using OpenCV classical detector"
                }, style = MaterialTheme.typography.titleSmall)
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Import a YOLOv5/v8/v11 licence-plate detector exported to TensorFlow Lite (.tflite). It is copied into the app and works fully offline.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = busy == null) { Text("Import .tflite") }
                    if (model.source == ModelStatus.Source.IMPORTED) OutlinedButton(onClick = {
                        task("Remove model") { c.models.removeImportedModel(); "Imported model removed" }
                    }) { Text("Remove") }
                }
            }

            SectionTitle("Backup & restore")
            Card {
                Toggle("Include photos in backup", "Larger file, but scan history keeps its images.", includePhotos) { includePhotos = it }
                Button(onClick = {
                    backupLauncher.launch("vehicle_scanner_backup_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".zip")
                }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Create backup (.zip)") }
                Toggle("Replace existing data on restore", "Off = merge backup into current data.", restoreReplace) { restoreReplace = it }
                OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
            }

            SectionTitle("Storage")
            Card {
                Text("Keep scan history", style = MaterialTheme.typography.titleSmall)
                Text("Older scans (photo, plate crops and results) are deleted automatically when the app starts.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val keepOptions = listOf(0 to "Forever", 90 to "90 days", 30 to "30 days")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    keepOptions.forEachIndexed { i, (days, label) ->
                        SegmentedButton(selected = s.keepScansDays == days, onClick = {
                            c.settings.update { it.copy(keepScansDays = days) }
                            if (days > 0) task("Clean up") { "Removed ${c.scanner.deleteOlderThan(days)} old scans" }
                        }, shape = SegmentedButtonDefaults.itemShape(i, keepOptions.size)) { Text(label) }
                    }
                }
            }

            SectionTitle("Privacy")
            Card {
                Text("All vehicles, photos and results are stored only on this phone. The app has no internet permission and never uploads anything.",
                    style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirmWipe = true }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()) { Text("Delete all local data") }
            }

            SectionTitle("About")
            Card {
                Text("Vehicle Scanner 1.2.1", style = MaterialTheme.typography.titleSmall)
                Text("Developed by VIGHNESH  ·  © 2026 VIGHNESH. All Rights Reserved.", style = MaterialTheme.typography.bodyMedium)
                Text("Open-source components: OpenCV (Apache 2.0), TensorFlow Lite (Apache 2.0), AndroidX/Jetpack Compose/Room (Apache 2.0), " +
                    "Coil (Apache 2.0), Google ML Kit Text Recognition (ML Kit Terms of Service, on-device bundled model).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    pendingRestore?.let { uri ->
        ConfirmDialog("Restore backup?",
            if (restoreReplace) "ALL current vehicles and scan history will be replaced by the backup." else "The backup will be merged into your current data. Duplicate numbers are skipped.",
            "Restore", destructive = restoreReplace,
            onConfirm = { task("Restore") { val r = c.backup.restore(uri, restoreReplace); "Restored ${r.vehicles} vehicles and ${r.sessions} scans" } },
            onDismiss = { pendingRestore = null })
    }
    if (confirmWipe) ConfirmDialog("Delete all local data?",
        "Every vehicle number, scan, photo and result will be permanently erased from this phone. This cannot be undone.",
        "Delete everything", true, onConfirm = { task("Delete") { c.backup.deleteAllData(); "All local data deleted" } },
        onDismiss = { confirmWipe = false })
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun Toggle(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
