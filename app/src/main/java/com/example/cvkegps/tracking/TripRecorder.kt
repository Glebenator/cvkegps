package com.example.cvkegps.tracking

import android.os.SystemClock
import com.example.cvkegps.location.TrackSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class RecordingError { Access, Storage, Queue, Service }

data class RecordingState(
    val trip: TripRecord? = null,
    val error: RecordingError? = null,
    val starting: Boolean = false,
    val segmentStartNanos: Long? = null,
    val segmentBaseDurationNanos: Long = 0,
    /** Fix time of the newest saved sample in the open segment. Null before the first sample. */
    val lastFixNanos: Long? = null,
) {
    val needsService: Boolean get() = starting || trip?.status == TripStatus.Recording || trip?.status == TripStatus.Paused

    /**
     * The time at which a recording trip has had no fix for [RouteMath.SIGNAL_GAP_NANOS], or null when it is not recording.
     * The segment start counts as the newest fix, so a new segment has the same delay before it reports no signal.
     */
    val signalLostAtNanos: Long? get() {
        val start = segmentStartNanos?.takeIf { trip?.status == TripStatus.Recording } ?: return null
        return maxOf(start, lastFixNanos ?: start) + RouteMath.SIGNAL_GAP_NANOS
    }

    fun signalLost(nowNanos: Long): Boolean = signalLostAtNanos?.let { nowNanos >= it } == true
}

/** The route of the current trip, as a screen shows it. */
data class ActiveRoute(val tripId: String, val summary: RouteSummary)

/** A saved trip can continue within this time after Stop and save. This undoes an accidental stop. */
const val REOPEN_WINDOW_MILLIS = 120_000L

sealed interface RecordingCommand {
    data class Start(val mode: TripMode, val automatic: Boolean = false) : RecordingCommand
    data class SetMode(val id: String, val mode: TripMode, val automatic: Boolean) : RecordingCommand
    /** [reopen] also continues a trip that was saved less than [REOPEN_WINDOW_MILLIS] ago. */
    data class Resume(val id: String, val reopen: Boolean = false) : RecordingCommand
    data object Pause : RecordingCommand
    data class Finish(val id: String? = null) : RecordingCommand
    data class Interrupt(val error: RecordingError) : RecordingCommand
    data class Batch(val tripId: String, val samples: List<TrackSample>, val estimate: TripMode) : RecordingCommand
    /** A screen starts or stops showing the current route. */
    data class ShowRoute(val shown: Boolean) : RecordingCommand
}

/** One ordered command queue owns database changes and location demand. */
class TripRecorder(private val dao: TripDao, private val source: SharedLocationSource, scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(RecordingState())
    val state = mutableState.asStateFlow()
    private val mutableRoute = MutableStateFlow<ActiveRoute?>(null)
    /**
     * The current trip's route while a screen collects it. The recorder reads the saved route once,
     * then adds each saved point, so a long trip is not read again for each fix. Null while it loads.
     */
    val route = mutableRoute.asStateFlow()
    private var routeShown = false
    private var liveRoute: LiveRoute? = null
    private val commands = Channel<RecordingCommand>(256)
    private val processRunId = UUID.randomUUID().toString()
    private var current: TripRecord? = null
    private var segment: TripSegment? = null
    private var segments = listOf<TripSegment>()
    private var baseDuration = 0L
    // Distance of the closed segments, and the open segment's distance added sample by sample.
    private var baseDistance = 0.0
    private var track: SegmentTrack? = null
    // False after a late sample. The segment close then calculates the distance again from all saved samples.
    private var trackInOrder = true
    private var overflow = false
    private var lastFixNanos: Long? = null

    init {
        // The route is kept in memory only while a screen shows it.
        scope.launch {
            mutableRoute.subscriptionCount.map { it > 0 }.distinctUntilChanged().collect { send(RecordingCommand.ShowRoute(it)) }
        }
        scope.launch {
            try { dao.markInterrupted() }
            catch (_: Exception) { mutableState.value = RecordingState(error = RecordingError.Storage) }
            // Trips saved before database version 3 get their distance. Commands do not wait for it.
            launch {
                try { TripDistances.fillMissing(dao) }
                catch (_: Exception) { } // The distance stays unavailable. The next start tries again.
            }
            for (command in commands) {
                try {
                    if (overflow) {
                        overflow = false
                        interrupt(RecordingError.Queue)
                    }
                    handle(command)
                } catch (_: Exception) {
                    // No exception text or coordinates enter diagnostic logs.
                    source.pauseRecording()
                    try { interrupt(RecordingError.Storage) }
                    catch (_: Exception) {
                        current = null
                        segment = null
                        clearRoute()
                        source.releaseRecording()
                        mutableState.value = RecordingState(error = RecordingError.Storage)
                    }
                }
            }
        }
    }

    fun send(command: RecordingCommand) {
        if (command is RecordingCommand.Start || command is RecordingCommand.Resume) {
            mutableState.value = mutableState.value.copy(starting = true, error = null)
        }
        if (commands.trySend(command).isFailure) {
            overflow = true
            source.pauseRecording()
        }
    }

    fun serviceLost() {
        if (state.value.needsService) {
            source.pauseRecording()
            send(RecordingCommand.Interrupt(RecordingError.Service))
        }
    }

    private suspend fun handle(command: RecordingCommand) {
        when (command) {
            is RecordingCommand.Start -> {
                if (current != null) { publish(); return }
                val trip = TripRecord(UUID.randomUUID().toString(), command.mode, TripStatus.Recording, System.currentTimeMillis(),
                    automaticMode = command.automatic, distanceMeters = 0.0)
                val next = newSegment(trip.id, BreakReason.Start, 0)
                dao.start(trip, next)
                current = trip
                segment = next
                segments = listOf(next)
                baseDuration = 0
                startTrack(0.0)
                publish()
                request(trip.id)
                if (routeShown) loadRoute()
            }
            is RecordingCommand.SetMode -> {
                val saved = dao.trip(command.id) ?: return
                val updated = saved.copy(mode = command.mode, automaticMode = command.automatic)
                dao.update(updated)
                if (current?.id == command.id) { current = updated; publish() }
            }
            is RecordingCommand.Resume -> {
                if (current != null && current?.id != command.id) { publish(); return }
                val saved = dao.trip(command.id)
                val reopen = command.reopen && saved?.status == TripStatus.Finished &&
                    saved.endedMillis?.let { System.currentTimeMillis() - it in 0..REOPEN_WINDOW_MILLIS } == true
                if (saved == null || (saved.status == TripStatus.Finished && !reopen) || current?.status == TripStatus.Recording) {
                    publish(); return
                }
                val distance = saved.distanceMeters ?: routeDistance(saved.id)
                val trip = saved.copy(status = TripStatus.Recording, endedMillis = null, distanceMeters = distance)
                val priorSegments = dao.segments(trip.id)
                val reason = if (saved.status == TripStatus.Interrupted) BreakReason.ProcessLost else BreakReason.Resume
                val next = newSegment(trip.id, reason, priorSegments.size)
                dao.resume(trip, next)
                current = trip
                segment = next
                segments = dao.segments(trip.id)
                baseDuration = trip.durationNanos
                startTrack(distance)
                publish()
                request(trip.id)
                if (routeShown) loadRoute()
            }
            RecordingCommand.Pause -> {
                if (current?.status != TripStatus.Recording) return
                source.pauseRecording()
                close(TripStatus.Paused, BreakReason.ManualPause)
                publish()
            }
            is RecordingCommand.Finish -> {
                if (current != null && (command.id == null || command.id == current?.id)) {
                    source.pauseRecording()
                    close(TripStatus.Finished, BreakReason.Stop)
                    current = null
                    segment = null
                    clearRoute()
                    source.releaseRecording()
                    publish()
                } else if (command.id != null) {
                    dao.trip(command.id)?.takeIf { it.status == TripStatus.Interrupted }?.let {
                        dao.update(it.copy(status = TripStatus.Finished, endedMillis = it.lastSavedMillis))
                    }
                    publish()
                } else publish()
            }
            is RecordingCommand.Interrupt -> interrupt(command.error)
            is RecordingCommand.ShowRoute -> {
                routeShown = command.shown
                if (command.shown) loadRoute() else clearRoute()
            }
            is RecordingCommand.Batch -> {
                val trip = current?.takeIf { it.id == command.tripId && it.status == TripStatus.Recording } ?: return
                val points = command.samples.filter { it.valid() }.mapNotNull { sample ->
                    val target = segments.lastOrNull {
                        it.runId == processRunId && sample.elapsedNanos >= it.startedElapsedNanos &&
                            (it.endedElapsedNanos == null || sample.elapsedNanos < it.endedElapsedNanos)
                    } ?: return@mapNotNull null
                    TripPoint(trip.id, target.id, sample)
                }
                if (points.isNotEmpty()) {
                    lastFixNanos = maxOf(lastFixNanos ?: 0L, points.maxOf { it.sample.elapsedNanos })
                    addToTrack(points)
                    val updated = trip.copy(durationNanos = duration(), lastSavedMillis = System.currentTimeMillis(),
                        mode = if (trip.automaticMode && command.estimate != TripMode.Unknown) command.estimate else trip.mode,
                        distanceMeters = baseDistance + (track?.distanceMeters ?: 0.0))
                    dao.save(updated, points)
                    current = updated
                    publish()
                    liveRoute?.let { route ->
                        if (points.sortedBy { it.sample.elapsedNanos }.all(route::add)) publishRoute() else loadRoute()
                    }
                }
            }
        }
    }

    private fun request(id: String) = source.recording(
        onBatch = { send(RecordingCommand.Batch(id, it, source.activityEstimate.value.fresh(SystemClock.elapsedRealtimeNanos()))) },
        onAccessLost = { send(RecordingCommand.Interrupt(RecordingError.Access)) },
    )

    private fun newSegment(id: String, reason: BreakReason, sequence: Int) = TripSegment(
        UUID.randomUUID().toString(), id, processRunId, sequence, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis(),
        reason = reason,
    )

    private fun startTrack(distance: Double) {
        lastFixNanos = null
        baseDistance = distance
        track = SegmentTrack()
        trackInOrder = true
    }

    /** Adds the open segment's new samples. A repeated sample changes nothing. Other samples need a full calculation. */
    private fun addToTrack(points: List<TripPoint>) {
        val open = track ?: return
        for (point in points.sortedBy { it.sample.elapsedNanos }) {
            val last = open.lastElapsedNanos
            when {
                point.segmentId != segment?.id || (last != null && point.sample.elapsedNanos < last) -> trackInOrder = false
                point.sample.elapsedNanos != last -> open.add(point.sample)
            }
        }
    }

    private suspend fun routeDistance(id: String): Double {
        val route = dao.route(id)
        return withContext(Dispatchers.Default) { RouteMath.summarize(route).distanceMeters }
    }

    private fun duration(): Long = segment?.let {
        baseDuration + (SystemClock.elapsedRealtimeNanos() - it.startedElapsedNanos).coerceAtLeast(0)
    } ?: current?.durationNanos ?: 0

    private suspend fun close(status: TripStatus, reason: BreakReason) {
        val trip = current ?: return
        val now = System.currentTimeMillis()
        val running = baseDistance + (track?.distanceMeters ?: 0.0)
        val distance = if (trackInOrder) running else try { routeDistance(trip.id) } catch (_: Exception) { running }
        val updated = trip.copy(status = status, durationNanos = duration(), lastSavedMillis = now,
            endedMillis = if (status == TripStatus.Finished) now else null, distanceMeters = distance)
        val closed = segment?.copy(endedElapsedNanos = SystemClock.elapsedRealtimeNanos(), endReason = reason)
        if (closed != null) {
            dao.closeSegment(updated, closed)
            segments = segments.map { if (it.id == closed.id) closed else it }
        } else dao.update(updated)
        current = updated
        segment = null
        baseDuration = updated.durationNanos
        baseDistance = distance
        track = null
        trackInOrder = true
    }

    private suspend fun interrupt(error: RecordingError) {
        source.pauseRecording()
        close(TripStatus.Interrupted, when (error) {
            RecordingError.Access -> BreakReason.AccessLost
            RecordingError.Service -> BreakReason.ProcessLost
            else -> BreakReason.StorageError
        })
        current = null
        segment = null
        clearRoute()
        source.releaseRecording()
        mutableState.value = RecordingState(error = error)
    }

    /** Reads the current trip's route once. A failed read leaves the route unavailable and does not stop the recording. */
    private suspend fun loadRoute() {
        val id = current?.id ?: return clearRoute()
        liveRoute = try {
            val segments = dao.segments(id)
            val points = dao.savedPoints(id)
            withContext(Dispatchers.Default) {
                val grouped = points.groupBy { it.segmentId }
                LiveRoute(id).apply { for (segment in segments) grouped[segment.id].orEmpty().forEach { add(it) } }
            }
        } catch (_: Exception) {
            null
        }
        publishRoute()
    }

    private fun publishRoute() {
        mutableRoute.value = liveRoute?.let { ActiveRoute(it.tripId, it.summary()) }
    }

    private fun clearRoute() {
        liveRoute = null
        mutableRoute.value = null
    }

    private fun publish() {
        mutableState.value = RecordingState(current, segmentStartNanos = segment?.startedElapsedNanos,
            segmentBaseDurationNanos = baseDuration, lastFixNanos = lastFixNanos.takeIf { segment != null })
    }
}
