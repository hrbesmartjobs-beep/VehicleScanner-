package com.vehiclescanner.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import com.vehiclescanner.core.PlateStatus
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "vehicles", indices = [Index(value = ["number"], unique = true)])
data class VehicleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Normalised registration number (uppercase, no spaces/hyphens). Unique. */
    val number: String,
    val note: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "scan_sessions")
data class ScanSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** Absolute path of the copy of the photo kept in app storage. */
    @ColumnInfo(name = "image_path") val imagePath: String,
    @ColumnInfo(name = "image_width") val imageWidth: Int,
    @ColumnInfo(name = "image_height") val imageHeight: Int,
    val detector: String,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "matched") val matched: Int,
    @ColumnInfo(name = "not_found") val notFound: Int,
    @ColumnInfo(name = "review") val review: Int
)

@Entity(
    tableName = "scan_results",
    indices = [Index("session_id")]
)
data class ScanResultEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    /** Raw OCR text (cleaned) before correction. */
    @ColumnInfo(name = "raw_text") val rawText: String,
    /** Normalised number used for matching (user-editable). */
    val number: String,
    val status: PlateStatus,
    val confidence: Float,
    @ColumnInfo(name = "detector_score") val detectorScore: Float,
    @ColumnInfo(name = "crop_path") val cropPath: String,
    @ColumnInfo(name = "box_left") val boxLeft: Float,
    @ColumnInfo(name = "box_top") val boxTop: Float,
    @ColumnInfo(name = "box_right") val boxRight: Float,
    @ColumnInfo(name = "box_bottom") val boxBottom: Float,
    /** Exact DB match (MATCH) or near-miss suggestion (REVIEW). */
    @ColumnInfo(name = "matched_number") val matchedNumber: String?,
    val reason: String?,
    /** True after a person confirmed or edited the reading. */
    @ColumnInfo(name = "verified") val verified: Boolean = false,
    val corrections: Int = 0
)

class Converters {
    @TypeConverter fun fromStatus(s: PlateStatus): String = s.name
    @TypeConverter fun toStatus(s: String): PlateStatus = PlateStatus.valueOf(s)
}

@Dao
interface VehicleDao {
    @Query("SELECT COUNT(*) FROM vehicles") fun countFlow(): Flow<Int>
    @Query("SELECT COUNT(*) FROM vehicles") suspend fun count(): Int
    @Query("SELECT number FROM vehicles") suspend fun allNumbers(): List<String>
    @Query("SELECT * FROM vehicles ORDER BY number") suspend fun all(): List<VehicleEntity>

    @Query("SELECT * FROM vehicles WHERE number LIKE '%' || :q || '%' OR note LIKE '%' || :q || '%' ORDER BY number LIMIT :limit")
    fun search(q: String, limit: Int = 500): Flow<List<VehicleEntity>>

    @Query("SELECT * FROM vehicles WHERE number = :number LIMIT 1") suspend fun byNumber(number: String): VehicleEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(v: VehicleEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(v: List<VehicleEntity>): List<Long>
    @Update(onConflict = OnConflictStrategy.ABORT) suspend fun update(v: VehicleEntity)
    @Query("DELETE FROM vehicles WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM vehicles") suspend fun deleteAll()
}

@Dao
abstract class ScanDao {
    @Query("SELECT * FROM scan_sessions ORDER BY timestamp DESC") abstract fun sessions(): Flow<List<ScanSessionEntity>>
    @Query("SELECT * FROM scan_sessions ORDER BY timestamp DESC") abstract suspend fun allSessions(): List<ScanSessionEntity>
    @Query("SELECT COUNT(*) FROM scan_sessions") abstract fun sessionCount(): Flow<Int>
    @Query("SELECT * FROM scan_sessions WHERE id = :id") abstract suspend fun session(id: Long): ScanSessionEntity?
    @Query("SELECT * FROM scan_sessions WHERE id = :id") abstract fun sessionFlow(id: Long): Flow<ScanSessionEntity?>
    @Query("SELECT * FROM scan_results WHERE session_id = :id ORDER BY box_top, box_left") abstract fun results(id: Long): Flow<List<ScanResultEntity>>
    @Query("SELECT * FROM scan_results WHERE session_id = :id ORDER BY box_top, box_left") abstract suspend fun resultsOnce(id: Long): List<ScanResultEntity>
    @Query("SELECT * FROM scan_results WHERE id = :id") abstract suspend fun result(id: Long): ScanResultEntity?

    @Insert abstract suspend fun insertSession(s: ScanSessionEntity): Long
    @Insert abstract suspend fun insertResults(r: List<ScanResultEntity>)
    @Update abstract suspend fun updateResult(r: ScanResultEntity)
    @Update abstract suspend fun updateSession(s: ScanSessionEntity)
    @Query("DELETE FROM scan_sessions WHERE id = :id") abstract suspend fun deleteSession(id: Long)
    @Query("DELETE FROM scan_results WHERE session_id = :id") abstract suspend fun deleteResults(id: Long)
    @Query("DELETE FROM scan_sessions") abstract suspend fun deleteAllSessions()
    @Query("DELETE FROM scan_results") abstract suspend fun deleteAllResults()

    @Transaction
    open suspend fun insertScan(s: ScanSessionEntity, r: List<ScanResultEntity>): Long {
        val id = insertSession(s)
        insertResults(r.map { it.copy(sessionId = id) })
        return id
    }

    /** Recompute the counters of a session after a manual edit. */
    @Transaction
    open suspend fun refreshCounts(sessionId: Long) {
        val s = session(sessionId) ?: return
        val rs = resultsOnce(sessionId)
        updateSession(s.copy(
            total = rs.size,
            matched = rs.count { it.status == PlateStatus.MATCH },
            notFound = rs.count { it.status == PlateStatus.NOT_FOUND },
            review = rs.count { it.status == PlateStatus.REVIEW }
        ))
    }
}

@Database(
    entities = [VehicleEntity::class, ScanSessionEntity::class, ScanResultEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun vehicles(): VehicleDao
    abstract fun scans(): ScanDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "vehicle_scanner.db")
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
