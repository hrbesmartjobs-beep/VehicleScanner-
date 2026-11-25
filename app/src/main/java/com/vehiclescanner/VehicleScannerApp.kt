package com.vehiclescanner

import android.app.Application
import android.util.Log
import com.vehiclescanner.data.AppDatabase
import com.vehiclescanner.data.BackupService
import com.vehiclescanner.data.ExportService
import com.vehiclescanner.data.SettingsRepository
import com.vehiclescanner.data.VehicleRepository
import com.vehiclescanner.scan.ModelManager
import com.vehiclescanner.scan.ScanManager
import com.vehiclescanner.scan.ScanPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.opencv.android.OpenCVLoader

class VehicleScannerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("VehicleScanner", "OpenCV native library failed to load")
        container = AppContainer(this)
        // Warm up: load the detector and the matcher in the background so the first scan starts fast.
        container.appScope.launch {
            container.models.ensureStatus()
            container.vehicles.matcher()
            runCatching { container.scanner.deleteOlderThan(container.settings.state.value.keepScansDays) }
        }
    }
}

/** Manual dependency container (small app – no DI framework needed). */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.build(app)
    val settings = SettingsRepository(app)
    val vehicles = VehicleRepository(db)
    val models = ModelManager(app)
    val pipeline = ScanPipeline(app, vehicles, db.scans(), settings, models)
    val scanner = ScanManager(appScope, pipeline, db.scans(), vehicles)
    val export = ExportService(app, db)
    val backup = BackupService(app, db, vehicles)
}
