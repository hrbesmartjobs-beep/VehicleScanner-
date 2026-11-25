package com.vehiclescanner.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.vehiclescanner.core.PlateNormalizer
import com.vehiclescanner.core.VehicleMatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed class AddResult {
    data class Added(val number: String) : AddResult()
    data class Duplicate(val number: String) : AddResult()
    data class Invalid(val reason: String) : AddResult()
}

class VehicleRepository(private val db: AppDatabase) {
    private val dao = db.vehicles()
    private val lock = Mutex()
    @Volatile private var matcherCache: VehicleMatcher? = null

    val count: Flow<Int> = dao.countFlow()
    fun search(q: String) = dao.search(PlateNormalizer.clean(q).ifEmpty { q.trim() })

    suspend fun matcher(): VehicleMatcher = lock.withLock {
        matcherCache ?: VehicleMatcher(dao.allNumbers()).also { matcherCache = it }
    }

    fun invalidateCache() { matcherCache = null }
    private fun invalidate() = invalidateCache()

    /** Clean a user-typed number; returns null + reason when not acceptable. */
    fun prepare(input: String, allowNonStandard: Boolean): Pair<String?, String?> {
        val cleaned = PlateNormalizer.clean(input)
        if (cleaned.length < 4) return null to "Too short"
        if (cleaned.length > 12) return null to "Too long"
        if (!PlateNormalizer.isValid(cleaned) && !allowNonStandard)
            return null to "Not a standard Indian registration format (enable non-standard numbers in Settings to allow it)"
        return cleaned to null
    }

    suspend fun add(input: String, note: String, allowNonStandard: Boolean): AddResult {
        val (n, err) = prepare(input, allowNonStandard)
        if (n == null) return AddResult.Invalid(err!!)
        val id = dao.insert(VehicleEntity(number = n, note = note.trim()))
        invalidate()
        return if (id == -1L) AddResult.Duplicate(n) else AddResult.Added(n)
    }

    suspend fun update(v: VehicleEntity, newInput: String, note: String, allowNonStandard: Boolean): AddResult {
        val (n, err) = prepare(newInput, allowNonStandard)
        if (n == null) return AddResult.Invalid(err!!)
        val existing = dao.byNumber(n)
        if (existing != null && existing.id != v.id) return AddResult.Duplicate(n)
        dao.update(v.copy(number = n, note = note.trim()))
        invalidate()
        return AddResult.Added(n)
    }

    suspend fun delete(id: Long) { dao.delete(id); invalidate() }
    suspend fun deleteAll() { dao.deleteAll(); invalidate() }
    suspend fun allNumbers(): Set<String> = dao.allNumbers().toHashSet()
    suspend fun all() = dao.all()

    /** Bulk insert in chunks inside one transaction. Returns number actually inserted. */
    suspend fun importNumbers(numbers: List<String>, onProgress: (Int) -> Unit = {}): Int {
        var inserted = 0
        db.withTransaction {
            numbers.chunked(500).forEachIndexed { i, chunk ->
                inserted += dao.insertAll(chunk.map { VehicleEntity(number = it) }).count { it != -1L }
                onProgress(minOf(numbers.size, (i + 1) * 500))
            }
        }
        invalidate()
        return inserted
    }
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** OCR confidence needed before a reading may be auto-classified MATCH / NOT FOUND. */
    val minConfidence: Float = 0.75f,
    /** Detector score threshold. */
    val detectorThreshold: Float = 0.30f,
    /** Run the detector on overlapping tiles too (finds small, far plates; slower). */
    val tiledDetection: Boolean = true,
    /** When a YOLO model is installed, also run the classical OpenCV detector and merge. */
    val combineDetectors: Boolean = false,
    /** Accept numbers that do not follow the standard formats into the database. */
    val allowNonStandard: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        theme = runCatching { ThemeMode.valueOf(prefs.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        minConfidence = prefs.getFloat("minConfidence", 0.75f),
        detectorThreshold = prefs.getFloat("detectorThreshold", 0.30f),
        tiledDetection = prefs.getBoolean("tiled", true),
        combineDetectors = prefs.getBoolean("combine", false),
        allowNonStandard = prefs.getBoolean("allowNonStandard", false)
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        prefs.edit()
            .putString("theme", s.theme.name)
            .putFloat("minConfidence", s.minConfidence)
            .putFloat("detectorThreshold", s.detectorThreshold)
            .putBoolean("tiled", s.tiledDetection)
            .putBoolean("combine", s.combineDetectors)
            .putBoolean("allowNonStandard", s.allowNonStandard)
            .apply()
        _state.value = s
    }
}
