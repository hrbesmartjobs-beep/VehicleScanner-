package com.vehiclescanner.scan

import android.net.Uri
import com.vehiclescanner.core.PlateNormalizer
import com.vehiclescanner.core.PlateStatus
import com.vehiclescanner.data.ScanDao
import com.vehiclescanner.data.VehicleRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class ScanState {
    data object Idle : ScanState()
    data class Running(val progress: Float, val stage: String) : ScanState()
    data class Done(val sessionId: Long) : ScanState()
    data class Failed(val message: String) : ScanState()
}

/** Application-scoped so a scan keeps running across rotation / navigation. */
class ScanManager(
    private val scope: CoroutineScope,
    private val pipeline: ScanPipeline,
    private val scans: ScanDao,
    private val vehicles: VehicleRepository
) {
    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(uri: Uri) {
        if (job?.isActive == true) return
        _state.value = ScanState.Running(0f, "Starting")
        job = scope.launch {
            try {
                val id = pipeline.run(uri) { p, s -> _state.value = ScanState.Running(p, s) }
                _state.value = ScanState.Done(id)
            } catch (c: CancellationException) {
                _state.value = ScanState.Idle
            } catch (oom: OutOfMemoryError) {
                _state.value = ScanState.Failed("Not enough memory for this photo. Try a smaller image.")
            } catch (t: Throwable) {
                _state.value = ScanState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun cancel() { job?.cancel() }
    fun acknowledge() { if (_state.value !is ScanState.Running) _state.value = ScanState.Idle }

    /**
     * Manual verification / correction of one reading. Text typed by a person
     * is cleaned but never auto-corrected; the result is marked verified.
     */
    suspend fun correct(resultId: Long, text: String): PlateStatus? {
        val r = scans.result(resultId) ?: return null
        val n = PlateNormalizer.clean(text)
        if (n.isEmpty()) return null
        val matcher = vehicles.matcher()
        val status = if (matcher.contains(n)) PlateStatus.MATCH else PlateStatus.NOT_FOUND
        scans.updateResult(r.copy(
            number = n, status = status, confidence = 1f, verified = true,
            matchedNumber = if (status == PlateStatus.MATCH) n else null,
            reason = if (PlateNormalizer.isValid(n)) "Verified manually" else "Verified manually (non-standard format)"
        ))
        scans.refreshCounts(r.sessionId)
        return status
    }

    /** Re-check every result of a session against the current database (e.g. after importing more numbers). */
    suspend fun recheck(sessionId: Long) {
        val matcher = vehicles.matcher()
        for (r in scans.resultsOnce(sessionId)) {
            if (r.number.isEmpty()) continue
            val newStatus = when {
                r.verified -> if (matcher.contains(r.number)) PlateStatus.MATCH else PlateStatus.NOT_FOUND
                r.status == PlateStatus.REVIEW -> PlateStatus.REVIEW
                matcher.contains(r.number) -> PlateStatus.MATCH
                matcher.nearest(r.number) != null -> PlateStatus.REVIEW
                else -> PlateStatus.NOT_FOUND
            }
            if (newStatus != r.status) scans.updateResult(r.copy(status = newStatus,
                matchedNumber = if (newStatus == PlateStatus.MATCH) r.number else matcher.nearest(r.number)))
        }
        scans.refreshCounts(sessionId)
    }

    suspend fun deleteSession(sessionId: Long) {
        val s = scans.session(sessionId) ?: return
        scans.deleteResults(sessionId)
        scans.deleteSession(sessionId)
        File(s.imagePath).parentFile?.deleteRecursively()
    }
}
