package com.vehiclescanner.scan

import android.content.Context
import android.net.Uri
import com.vehiclescanner.vision.YoloPlateDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ModelStatus(val source: Source, val description: String) {
    enum class Source { IMPORTED, BUNDLED, NONE }
}

/**
 * Locates and loads the YOLO plate model. Order of preference:
 *  1. a model the user imported from Settings (stored privately in app files),
 *  2. a model bundled at `app/src/main/assets/models/plate_detector.tflite`.
 * With neither, scanning uses the classical OpenCV detector only.
 */
class ModelManager(private val context: Context) {

    private val userModel = File(context.filesDir, "models/plate_detector.tflite")
    private val lock = Mutex()
    private var detector: YoloPlateDetector? = null
    private var loaded = false

    private val _status = MutableStateFlow(ModelStatus(ModelStatus.Source.NONE, "Not loaded yet"))
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    suspend fun detector(): YoloPlateDetector? = lock.withLock {
        if (!loaded) load()
        detector
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        loaded = true
        detector?.close(); detector = null
        if (userModel.exists()) {
            runCatching { YoloPlateDetector(mapFile(userModel)) }
                .onSuccess { detector = it; _status.value = ModelStatus(ModelStatus.Source.IMPORTED, it.description); return@withContext }
                .onFailure { _status.value = ModelStatus(ModelStatus.Source.NONE, "Imported model failed to load: ${it.message}") }
        }
        val bundled = runCatching { mapAsset(ASSET) }.getOrNull()
        if (bundled != null) {
            runCatching { YoloPlateDetector(bundled) }
                .onSuccess { detector = it; _status.value = ModelStatus(ModelStatus.Source.BUNDLED, it.description) }
                .onFailure { _status.value = ModelStatus(ModelStatus.Source.NONE, "Bundled model failed to load: ${it.message}") }
        } else if (!userModel.exists()) {
            _status.value = ModelStatus(ModelStatus.Source.NONE, "No YOLO model installed – using the classical OpenCV detector")
        }
    }

    suspend fun ensureStatus() { lock.withLock { if (!loaded) load() } }

    /** Copy a .tflite chosen by the user, validate it by loading it, then activate it. */
    suspend fun importModel(uri: Uri): Result<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, "model_import.tflite")
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: error("Cannot open file")
                val probe = YoloPlateDetector(mapFile(tmp))
                val desc = probe.description
                probe.close()
                userModel.parentFile?.mkdirs()
                tmp.copyTo(userModel, overwrite = true)
                tmp.delete()
                load()
                desc
            }.onFailure { tmp.delete() }
        }
    }

    suspend fun removeImportedModel() = lock.withLock {
        withContext(Dispatchers.IO) { userModel.delete(); load() }
    }

    private fun mapFile(f: File): MappedByteBuffer =
        FileInputStream(f).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }

    private fun mapAsset(path: String): MappedByteBuffer =
        context.assets.openFd(path).use { fd ->
            FileInputStream(fd.fileDescriptor).use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }

    companion object { const val ASSET = "models/plate_detector.tflite" }
}
