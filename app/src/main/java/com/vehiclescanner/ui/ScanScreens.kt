package com.vehiclescanner.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.vehiclescanner.AppContainer
import com.vehiclescanner.core.PlateNormalizer
import com.vehiclescanner.core.PlateStatus
import com.vehiclescanner.data.AddResult
import com.vehiclescanner.data.FileFormat
import com.vehiclescanner.data.ScanResultEntity
import com.vehiclescanner.scan.ScanState
import com.vehiclescanner.ui.theme.LocalStatusColors
import com.vehiclescanner.ui.theme.PlateTextStyle
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ scanning progress

@Composable
fun ScanningScreen(c: AppContainer, onBack: () -> Unit) {
    val state by c.scanner.state.collectAsStateWithLifecycle()
    BackHandler { onBack() }
    Scaffold(topBar = { AppBar("Scanning", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
        ) {
            when (val s = state) {
                is ScanState.Running -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { s.progress }, Modifier.size(140.dp), strokeWidth = 10.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        Text("${(s.progress * 100).toInt()}%", style = MaterialTheme.typography.headlineMedium)
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(s.stage, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Everything runs on this phone. You can leave this screen; the scan continues.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    OutlinedButton(onClick = { c.scanner.cancel() }) { Text("Cancel scan") }
                }
                is ScanState.Failed -> {
                    Text("Scan failed", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(8.dp))
                    Text(s.message)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { c.scanner.acknowledge(); onBack() }) { Text("Back") }
                }
                else -> {
                    // Done is handled by navigation; Idle means cancelled.
                    LaunchedEffect(Unit) { onBack() }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ results dashboard

@Composable
fun ResultsScreen(c: AppContainer, sessionId: Long, onBack: () -> Unit, onPhoto: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session by c.db.scans().sessionFlow(sessionId).collectAsStateWithLifecycle(null)
    val results by c.db.scans().results(sessionId).collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.state.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<PlateStatus?>(null) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScanResultEntity?>(null) }
    val sc = LocalStatusColors.current

    fun exportLauncherResult(format: FileFormat) = { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            runCatching { c.export.exportSession(sessionId, uri, format, filter) }
                .onSuccess { Toast.makeText(context, "Exported ${format.ext.uppercase()}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
        Unit
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileFormat.CSV.mime), exportLauncherResult(FileFormat.CSV))
    val exportXlsx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(FileFormat.XLSX.mime), exportLauncherResult(FileFormat.XLSX))

    Scaffold(topBar = {
        AppBar("Scan results", onBack) {
            IconButton(onClick = onPhoto) { Icon(Icons.Default.Image, "Original photo") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.FileDownload, "Export") }
                DropdownMenu(menu, { menu = false }) {
                    val suffix = filter?.let { "_" + it.name.lowercase() } ?: ""
                    DropdownMenuItem(text = { Text("Export CSV") }, onClick = { menu = false; exportCsv.launch("scan_${sessionId}$suffix.csv") })
                    DropdownMenuItem(text = { Text("Export Excel (.xlsx)") }, onClick = { menu = false; exportXlsx.launch("scan_${sessionId}$suffix.xlsx") })
                    DropdownMenuItem(text = { Text("Re-check against database") }, leadingIcon = { Icon(Icons.Default.Refresh, null) },
                        onClick = { menu = false; scope.launch { c.scanner.recheck(sessionId); Toast.makeText(context, "Re-checked", Toast.LENGTH_SHORT).show() } })
                    DropdownMenuItem(text = { Text("Delete scan") }, leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menu = false; confirmDelete = true })
                }
            }
        }
    }) { pad ->
        val shown = results.filter { filter == null || it.status == filter }
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                session?.let { s ->
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.timestamp)) +
                        " · ${s.detector} · ${"%.1f".format(s.durationMs / 1000f)} s",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Total plates detected", "${results.size}", Modifier.weight(1f), selected = filter == null, onClick = { filter = null })
                    StatTile("Match found", "${results.count { it.status == PlateStatus.MATCH }}", Modifier.weight(1f),
                        accent = sc.match, bg = sc.matchBg, selected = filter == PlateStatus.MATCH, onClick = { filter = PlateStatus.MATCH })
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatTile("Not found", "${results.count { it.status == PlateStatus.NOT_FOUND }}", Modifier.weight(1f),
                        accent = sc.notFound, bg = sc.notFoundBg, selected = filter == PlateStatus.NOT_FOUND, onClick = { filter = PlateStatus.NOT_FOUND })
                    StatTile("Review required", "${results.count { it.status == PlateStatus.REVIEW }}", Modifier.weight(1f),
                        accent = sc.review, bg = sc.reviewBg, selected = filter == PlateStatus.REVIEW, onClick = { filter = PlateStatus.REVIEW })
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(filter == null, { filter = null }, { Text("All") })
                    PlateStatus.entries.forEach { st -> FilterChip(filter == st, { filter = st }, { Text(statusLabel(st)) }) }
                }
            }
            if (shown.isEmpty()) item {
                EmptyState(Icons.Default.SearchOff,
                    if (results.isEmpty()) "No number plates found" else "Nothing in this category",
                    if (results.isEmpty()) "Try a sharper photo taken closer to the vehicles, enable tiled detection in Settings, or install a YOLO plate model."
                    else "Choose another filter above.")
            }
            items(shown, key = { it.id }) { r ->
                PlateCard(r,
                    onEdit = { editing = r },
                    onUseSuggestion = { s -> scope.launch { c.scanner.correct(r.id, s) } },
                    onConfirm = { scope.launch { c.scanner.correct(r.id, r.number) } },
                    onAddToDb = {
                        scope.launch {
                            when (val res = c.vehicles.add(r.number, "Added from scan", settings.allowNonStandard)) {
                                is AddResult.Added -> { c.scanner.recheck(sessionId); Toast.makeText(context, "Added ${PlateNormalizer.format(res.number)}", Toast.LENGTH_SHORT).show() }
                                is AddResult.Duplicate -> { c.scanner.recheck(sessionId); Toast.makeText(context, "Already in database", Toast.LENGTH_SHORT).show() }
                                is AddResult.Invalid -> Toast.makeText(context, res.reason, Toast.LENGTH_LONG).show()
                            }
                        }
                    })
            }
        }
    }

    editing?.let { r ->
        EditReadingDialog(r, onDismiss = { editing = null }, onSave = { text ->
            scope.launch {
                val st = c.scanner.correct(r.id, text)
                if (st != null) Toast.makeText(context, "Saved: ${statusLabel(st)}", Toast.LENGTH_SHORT).show()
            }
            editing = null
        })
    }
    if (confirmDelete) ConfirmDialog("Delete this scan?", "The photo, plate crops and results of this scan will be removed from the phone.",
        "Delete", destructive = true, onConfirm = { scope.launch { c.scanner.deleteSession(sessionId); onBack() } }, onDismiss = { confirmDelete = false })
}

@Composable
private fun PlateCard(
    r: ScanResultEntity, onEdit: () -> Unit, onUseSuggestion: (String) -> Unit, onConfirm: () -> Unit, onAddToDb: () -> Unit
) {
    val sc = LocalStatusColors.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.5.dp, sc.fg(r.status).copy(alpha = 0.55f))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(r.status)
                Spacer(Modifier.weight(1f))
                if (r.verified) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Verified, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text("Verified", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(10.dp))
            AsyncImage(
                model = File(r.cropPath), contentDescription = "Plate crop",
                modifier = Modifier.fillMaxWidth().heightIn(max = 110.dp).clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(10.dp))
            Text(if (r.number.isEmpty()) "— unreadable —" else PlateNormalizer.format(r.number), style = PlateTextStyle)
            if (r.rawText.isNotEmpty() && r.rawText != r.number) {
                Text("OCR read: ${r.rawText}" + if (r.corrections > 0) " (${r.corrections} char auto-corrected)" else "",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            r.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sc.fg(r.status)) }
            Spacer(Modifier.height(8.dp))
            ConfidenceBar(r.confidence, sc.fg(r.status))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onEdit) { Icon(Icons.Default.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Edit") }
                if (r.status == PlateStatus.REVIEW && r.matchedNumber != null && r.matchedNumber != r.number) {
                    TextButton(onClick = { onUseSuggestion(r.matchedNumber) }) { Text("Use ${PlateNormalizer.format(r.matchedNumber)}") }
                }
                if (r.status == PlateStatus.REVIEW && r.number.length >= 4) {
                    TextButton(onClick = onConfirm) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Reading is correct") }
                }
                if (r.status == PlateStatus.NOT_FOUND) {
                    TextButton(onClick = onAddToDb) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add to database") }
                }
            }
        }
    }
}

@Composable
private fun EditReadingDialog(r: ScanResultEntity, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(PlateNormalizer.format(r.number)) }
    val cleaned = PlateNormalizer.clean(text)
    val valid = PlateNormalizer.isValid(cleaned)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Verify plate") },
        text = {
            Column {
                AsyncImage(File(r.cropPath), "Plate crop", Modifier.fillMaxWidth().heightIn(max = 140.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it.uppercase() }, label = { Text("Registration number") }, singleLine = true,
                    textStyle = PlateTextStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (cleaned.isEmpty()) "Type the number exactly as on the plate"
                    else if (valid) "✓ ${PlateNormalizer.format(cleaned)} – valid Indian format"
                    else "⚠ ${cleaned} – not a standard format (will still be checked)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (valid) LocalStatusColors.current.match else LocalStatusColors.current.review
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = cleaned.length >= 4) { Text("Save & check") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ------------------------------------------------------------------ original photo with boxes

@Composable
fun PhotoScreen(c: AppContainer, sessionId: Long, onBack: () -> Unit) {
    val session by c.db.scans().sessionFlow(sessionId).collectAsStateWithLifecycle(null)
    val results by c.db.scans().results(sessionId).collectAsStateWithLifecycle(emptyList())
    val sc = LocalStatusColors.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val tf = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 8f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Scaffold(topBar = { AppBar("Original photo", onBack) }) { pad ->
        val s = session ?: return@Scaffold
        Box(Modifier.fillMaxSize().padding(pad).background(androidx.compose.ui.graphics.Color.Black).transformable(tf),
            contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().aspectRatio(s.imageWidth.toFloat() / s.imageHeight.coerceAtLeast(1))
                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)) {
                AsyncImage(File(s.imagePath), "Scanned photo", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()) {
                    val k = size.width / s.imageWidth
                    for (r in results) {
                        drawRect(sc.fg(r.status), Offset(r.boxLeft * k, r.boxTop * k),
                            Size((r.boxRight - r.boxLeft) * k, (r.boxBottom - r.boxTop) * k), style = Stroke(width = 3.dp.toPx() / scale))
                    }
                }
            }
            Text("Pinch to zoom · ${results.size} plates", Modifier.align(Alignment.BottomCenter).padding(12.dp),
                color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}
