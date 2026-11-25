package com.vehiclescanner.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TableView
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vehiclescanner.AppContainer
import com.vehiclescanner.Routes
import com.vehiclescanner.scan.ModelStatus
import com.vehiclescanner.scan.ScanState
import com.vehiclescanner.ui.theme.LocalStatusColors
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(c: AppContainer, onScanStarted: () -> Unit, onOpen: (String) -> Unit, onOpenSession: (Long) -> Unit) {
    val context = LocalContext.current
    val vehicleCount by c.vehicles.count.collectAsStateWithLifecycle(0)
    val sessions by c.db.scans().sessions().collectAsStateWithLifecycle(emptyList())
    val model by c.models.status.collectAsStateWithLifecycle()
    val scanState by c.scanner.state.collectAsStateWithLifecycle()
    val sc = LocalStatusColors.current

    LaunchedEffect(Unit) { c.models.ensureStatus() }

    fun start(uri: Uri) {
        if (vehicleCount == 0) Toast.makeText(context, "Your vehicle database is empty – every plate will show as NOT FOUND.", Toast.LENGTH_LONG).show()
        c.scanner.start(uri); onScanStarted()
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) start(uri) }

    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }
    var checking by rememberSaveable { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val u = pendingCamera
        if (ok && u != null) start(Uri.parse(u))
    }

    fun openCamera() {
        val dir = File(context.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCamera = uri.toString()
        try { camera.launch(uri) } catch (e: Exception) {
            Toast.makeText(context, "No camera app available", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VEHICLE SCANNER", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("Multi-plate offline checking", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.WifiOff, null, Modifier.padding(end = 4.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Offline", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Vehicles in database", "%,d".format(vehicleCount), Modifier.weight(1f), onClick = { onOpen(Routes.DATABASE) })
            StatTile("Scans saved", "${sessions.size}", Modifier.weight(1f), onClick = { onOpen(Routes.HISTORY) })
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Detector: " + when (model.source) {
                ModelStatus.Source.IMPORTED -> "YOLO model (imported)"
                ModelStatus.Source.BUNDLED -> "YOLO model (built-in)"
                ModelStatus.Source.NONE -> "OpenCV classical — add a YOLO model in Settings for best accuracy"
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val running = scanState as? ScanState.Running
        if (running != null) {
            Spacer(Modifier.height(14.dp))
            Surface(onClick = onScanStarted, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Scan in progress – ${running.stage}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { running.progress }, Modifier.fillMaxWidth())
                }
            }
        }

        SectionTitle("Scan")
        ActionButton("SCAN PHOTO", "Choose a photo with one or many vehicles", Icons.Default.PhotoLibrary, primary = true, enabled = running == null) {
            gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        Spacer(Modifier.height(10.dp))
        ActionButton("TAKE PHOTO", "Capture a parking area with the camera", Icons.Default.CameraAlt, enabled = running == null) { openCamera() }
        Spacer(Modifier.height(10.dp))
        ActionButton("CHECK NUMBER", "Type a number to check it without a photo", Icons.Default.Search) { checking = true }

        SectionTitle("Manage")
        ActionButton("IMPORT EXCEL", "Bulk add numbers from .xlsx or .csv", Icons.Default.TableView) { onOpen(Routes.IMPORT) }
        Spacer(Modifier.height(10.dp))
        ActionButton("VEHICLE DATABASE", "Add, edit, search and delete numbers", Icons.Default.Storage) { onOpen(Routes.DATABASE) }
        Spacer(Modifier.height(10.dp))
        ActionButton("SCAN HISTORY", "Previous scans and their results", Icons.Default.History) { onOpen(Routes.HISTORY) }
        Spacer(Modifier.height(10.dp))
        ActionButton("SETTINGS", "Accuracy, detection model, backup, theme", Icons.Default.Settings) { onOpen(Routes.SETTINGS) }

        sessions.firstOrNull()?.let { last ->
            SectionTitle("Last scan")
            Surface(onClick = { onOpenSession(last.id) }, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(last.timestamp)),
                        style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("${last.total} plates", fontWeight = FontWeight.SemiBold)
                        Text("${last.matched} match", color = sc.match, fontWeight = FontWeight.SemiBold)
                        Text("${last.notFound} not found", color = sc.notFound, fontWeight = FontWeight.SemiBold)
                        Text("${last.review} review", color = sc.review, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    if (checking) CheckNumberDialog(c) { checking = false }
}

/** Instant manual lookup: exact match, likely match (OCR-style typo) or not found. */
@Composable
private fun CheckNumberDialog(c: AppContainer, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Pair<com.vehiclescanner.core.PlateStatus, String?>?>(null) }
    val sc = LocalStatusColors.current
    val cleaned = com.vehiclescanner.core.PlateNormalizer.clean(text)
    LaunchedEffect(cleaned) {
        result = if (cleaned.length < 4) null else {
            val m = c.vehicles.matcher()
            val hit = m.lookup(cleaned)
            when {
                hit != null -> com.vehiclescanner.core.PlateStatus.MATCH to hit
                else -> m.nearest(cleaned)?.let { com.vehiclescanner.core.PlateStatus.REVIEW to it }
                    ?: (com.vehiclescanner.core.PlateStatus.NOT_FOUND to null)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check a number") },
        text = {
            Column {
                OutlinedTextField(text, { text = it.uppercase() }, label = { Text("Registration number") }, singleLine = true,
                    placeholder = { Text("KL 08 AB 1234") }, textStyle = com.vehiclescanner.ui.theme.PlateTextStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Spacer(Modifier.height(14.dp))
                result?.let { (st, num) ->
                    StatusBadge(st)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when (st) {
                            com.vehiclescanner.core.PlateStatus.MATCH -> "In your database: ${com.vehiclescanner.core.PlateNormalizer.format(num!!)}"
                            com.vehiclescanner.core.PlateStatus.REVIEW -> "Not exact. Similar saved number: ${com.vehiclescanner.core.PlateNormalizer.format(num!!)}"
                            com.vehiclescanner.core.PlateStatus.NOT_FOUND -> "This number is not in your database."
                        },
                        color = sc.fg(st), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
