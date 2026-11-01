import Foundation
import Observation
#if canImport(Glibc)
import Glibc
#endif

/// A clock that counts while the phone sleeps. Fixes, sensor readings, and segments use it, so they can be compared.
public enum MonotonicClock {
    public static func nanos() -> Int64 {
        var time = timespec()
        #if os(Linux)
        clock_gettime(CLOCK_BOOTTIME, &time)
        #else
        // On Apple platforms, this clock continues to count while the system is asleep.
        clock_gettime(CLOCK_MONOTONIC, &time)
        #endif
        return Int64(time.tv_sec) * 1_000_000_000 + Int64(time.tv_nsec)
    }
}

/// The elapsed clock and the wall clock that the recorder uses. Tests replace them.
public struct RecorderClock: Sendable {
    public var elapsedNanos: @Sendable () -> Int64
    public var wallMillis: @Sendable () -> Int64

    public init(elapsedNanos: @escaping @Sendable () -> Int64, wallMillis: @escaping @Sendable () -> Int64) {
        self.elapsedNanos = elapsedNanos
        self.wallMillis = wallMillis
    }

    public static let system = RecorderClock(elapsedNanos: MonotonicClock.nanos,
                                             wallMillis: { Int64((Date().timeIntervalSince1970 * 1000).rounded()) })
}

/// The location demand of a recording trip. The platform location service implements it.
@MainActor
public protocol RecordingLocationSource: AnyObject {
    /// The newest activity estimate from recent GPS speed.
    var activityEstimate: ActivityEstimate { get }

    /// Starts or changes the trip's demand. `onBatch` receives each group of stored samples in time order, and the GPS height
    /// minus the sea-level altitude of the newest fix that has both. `onAccessLost` reports that location access ended.
    func recording(options: RecordingOptions, onBatch: @escaping @MainActor ([TrackSample], Double?) -> Void,
                   onAccessLost: @escaping @MainActor () -> Void)

    /// Keeps the trip's ownership but stops location updates.
    func pauseRecording()

    /// Ends the trip's demand.
    func releaseRecording()
}

public enum RecordingCommand: Sendable {
    case start(mode: TripMode, automatic: Bool)
    case setMode(id: String, mode: TripMode, automatic: Bool)
    /// `reopen` also continues a trip that was saved less than `reopenWindowMillis` ago.
    case resume(id: String, reopen: Bool)
    case pause
    case finish(id: String?)
    case interrupt(RecordingError)
    case batch(tripId: String, samples: [TrackSample], estimate: TripMode, seaLevelOffset: Double?)
    /// Phone sensor readings. They can arrive shortly after Pause or Stop.
    case sensors(tripId: String, chunks: [SensorChunk])
    /// A screen starts or stops showing the current route.
    case showRoute(Bool)
}

/// One ordered command queue owns database changes, location demand, and sensor recording.
/// `options` gives the user's recording choices when a segment starts.
@MainActor
@Observable
public final class TripRecorder {
    public private(set) var state = RecordingState()
    /// The current trip's route while a screen shows it. The recorder reads the saved route once, then adds each saved point,
    /// so a long trip is not read again for each fix. Nil while it loads.
    public private(set) var route: ActiveRoute?

    @ObservationIgnored private let store: any TripStore
    @ObservationIgnored private let source: any RecordingLocationSource
    @ObservationIgnored private let motion: any MotionSource
    @ObservationIgnored private let clock: RecorderClock
    @ObservationIgnored private let options: @MainActor () -> RecordingOptions
    @ObservationIgnored private let continuation: AsyncStream<RecordingCommand>.Continuation
    @ObservationIgnored private var worker: Task<Void, Never>?
    @ObservationIgnored private let processRunId = UUID().uuidString
    @ObservationIgnored private var routeViewers = 0
    @ObservationIgnored private var routeShown = false
    @ObservationIgnored private var liveRoute: LiveRoute?
    @ObservationIgnored private var current: TripRecord?
    @ObservationIgnored private var segment: TripSegment?
    @ObservationIgnored private var segments: [TripSegment] = []
    @ObservationIgnored private var baseDuration: Int64 = 0
    // Distance of the closed segments, and the open segment's distance added sample by sample.
    @ObservationIgnored private var baseDistance = 0.0
    @ObservationIgnored private var track: SegmentTrack?
    // False after a late sample. The segment close then calculates the distance again from all saved samples.
    @ObservationIgnored private var trackInOrder = true
    @ObservationIgnored private var overflow = false
    @ObservationIgnored private var lastFixNanos: Int64?
    @ObservationIgnored private var lastNetworkFixNanos: Int64?

    public init(store: any TripStore, source: any RecordingLocationSource, motion: any MotionSource, clock: RecorderClock = .system,
                options: @escaping @MainActor () -> RecordingOptions) {
        self.store = store
        self.source = source
        self.motion = motion
        self.clock = clock
        self.options = options
        let (commands, continuation) = AsyncStream.makeStream(of: RecordingCommand.self, bufferingPolicy: .bufferingOldest(256))
        self.continuation = continuation
        worker = Task { await self.run(commands) }
    }

    /// Ends the command queue. The app calls it only in tests.
    public func close() {
        continuation.finish()
        worker?.cancel()
    }

    /// The number of commands that the queue has not handled yet. Tests wait for zero.
    @ObservationIgnored public private(set) var pending = 0

    /// Shows that a start or resume is on its way. The recorder's answer clears it.
    public func expectStart() {
        state.starting = true
        state.error = nil
    }

    public func send(_ command: RecordingCommand) {
        switch command {
        case .start, .resume: expectStart()
        default: break
        }
        pending += 1
        if case .dropped = continuation.yield(command) {
            pending -= 1
            overflow = true
            source.pauseRecording()
            motion.stop()
        }
    }

    /// A screen shows the current route. Each call with true needs one call with false.
    public func showRoute(_ shown: Bool) {
        let before = routeViewers > 0
        routeViewers = max(routeViewers + (shown ? 1 : -1), 0)
        if before != (routeViewers > 0) { send(.showRoute(routeViewers > 0)) }
    }

    /// The system ended the recording, for example because location access ended while the app was not visible.
    public func serviceLost() {
        guard state.active else { return }
        source.pauseRecording()
        send(.interrupt(.service))
    }

    private func run(_ commands: AsyncStream<RecordingCommand>) async {
        do { try await store.markInterrupted() } catch { state = RecordingState(error: .storage) }
        for await command in commands {
            defer { pending = max(pending - 1, 0) }
            do {
                if overflow {
                    overflow = false
                    try await interrupt(.queue)
                }
                try await handle(command)
            } catch {
                // No error text or coordinates enter diagnostic logs.
                source.pauseRecording()
                motion.stop()
                do { try await interrupt(.storage) } catch {
                    current = nil
                    segment = nil
                    clearRoute()
                    source.releaseRecording()
                    state = RecordingState(error: .storage)
                }
            }
        }
    }

    private func handle(_ command: RecordingCommand) async throws {
        switch command {
        case let .start(mode, automatic):
            if current != nil { return publish() }
            let trip = TripRecord(id: UUID().uuidString, mode: mode, status: .recording, startedMillis: clock.wallMillis(),
                                  automaticMode: automatic, distanceMeters: 0)
            let chosen = options()
            let next = newSegment(trip.id, .start, 0, chosen)
            try await store.start(trip, next)
            current = trip
            segment = next
            segments = [next]
            baseDuration = 0
            startTrack(0)
            publish()
            request(trip.id, chosen)
            if routeShown { await loadRoute() }

        case let .setMode(id, mode, automatic):
            guard var saved = try await store.trip(id) else { return }
            saved.mode = mode
            saved.automaticMode = automatic
            try await store.update(saved)
            if current?.id == id {
                current = saved.withLive(current)
                publish()
            }

        case let .resume(id, reopen):
            if let current, current.id != id { return publish() }
            let saved = try await store.trip(id)
            let now = clock.wallMillis()
            let reopened = reopen && saved?.status == .finished &&
                (saved?.endedMillis.map { now - $0 >= 0 && now - $0 <= reopenWindowMillis } ?? false)
            guard var trip = saved, trip.status != .finished || reopened else {
                publish()
                state.error = .refused
                return
            }
            if current?.status == .recording { return publish() }
            let distance: Double
            if let known = trip.distanceMeters { distance = known } else { distance = try await routeDistance(trip.id) }
            let priorSegments = try await store.segments(trip.id)
            let reason: BreakReason = trip.status == .interrupted ? .processLost : .resume
            trip.status = .recording
            trip.endedMillis = nil
            trip.distanceMeters = distance
            let chosen = options()
            let next = newSegment(trip.id, reason, priorSegments.count, chosen)
            try await store.resume(trip, next)
            current = trip
            segment = next
            segments = try await store.segments(trip.id)
            baseDuration = trip.durationNanos
            startTrack(distance)
            publish()
            request(trip.id, chosen)
            if routeShown { await loadRoute() }

        case .pause:
            guard current?.status == .recording else { return }
            source.pauseRecording()
            motion.stop()
            try await close(.paused, .manualPause)
            publish()

        case let .finish(id):
            if let current, id == nil || id == current.id {
                source.pauseRecording()
                motion.stop()
                try await close(.finished, .stop)
                self.current = nil
                segment = nil
                clearRoute()
                source.releaseRecording()
                publish()
            } else if let id {
                if var trip = try await store.trip(id), trip.status == .interrupted {
                    trip.status = .finished
                    trip.endedMillis = trip.lastSavedMillis
                    try await store.update(trip)
                }
                publish()
            } else {
                publish()
            }

        case let .interrupt(error):
            try await interrupt(error)

        case let .showRoute(shown):
            routeShown = shown
            if shown { await loadRoute() } else { clearRoute() }

        case let .batch(tripId, samples, estimate, seaLevelOffset):
            guard let trip = current, trip.id == tripId, trip.status == .recording else { return }
            let points = samples.filter(\.isValid).compactMap { sample -> TripPoint? in
                guard let target = segments.last(where: {
                    $0.runId == processRunId && sample.elapsedNanos >= $0.startedElapsedNanos &&
                        ($0.endedElapsedNanos.map { sample.elapsedNanos < $0 } ?? true)
                }) else { return nil }
                return TripPoint(tripId: trip.id, segmentId: target.id, sample: sample)
            }
            guard !points.isEmpty else { return }
            // Only a GPS fix ends the state without a GPS signal.
            if let newest = points.filter({ $0.sample.source == .gps }).map(\.sample.elapsedNanos).max() {
                lastFixNanos = max(lastFixNanos ?? 0, newest)
            }
            if let newest = points.filter({ $0.sample.source == .network }).map(\.sample.elapsedNanos).max() {
                lastNetworkFixNanos = max(lastNetworkFixNanos ?? 0, newest)
            }
            addToTrack(points)
            var updated = trip
            updated.durationNanos = duration()
            updated.lastSavedMillis = clock.wallMillis()
            if trip.automaticMode && estimate != .unknown { updated.mode = estimate }
            updated.distanceMeters = baseDistance + (track?.distanceMeters ?? 0)
            if updated.seaLevelOffsetMeters == nil { updated.seaLevelOffsetMeters = seaLevelOffset }
            try await store.save(updated, points)
            current = updated
            publish()
            if let liveRoute {
                let ordered = points.sorted { $0.sample.elapsedNanos < $1.sample.elapsedNanos }
                if ordered.allSatisfy({ liveRoute.add($0) }) { publishRoute() } else { await loadRoute() }
            }

        case let .sensors(tripId, chunks):
            // Readings can arrive after Pause or Stop. They belong to the segment that was open at their time.
            guard segments.first?.tripId == tripId else { return }
            let rows = chunks.compactMap { chunk -> StoredSensorChunk? in
                guard let first = chunk.elapsedNanos.first, let last = chunk.elapsedNanos.last,
                      let target = segments.last(where: {
                          $0.runId == processRunId && first >= $0.startedElapsedNanos && ($0.endedElapsedNanos.map { first < $0 } ?? true)
                      }) else { return nil }
                return StoredSensorChunk(tripId: tripId, segmentId: target.id, kind: chunk.kind, firstElapsedNanos: first,
                                         lastElapsedNanos: last, count: chunk.count, data: chunk.encode())
            }
            if !rows.isEmpty { try await store.insertSensors(rows) }
        }
    }

    private func request(_ id: String, _ chosen: RecordingOptions) {
        source.recording(options: chosen, onBatch: { [weak self] samples, offset in
            guard let self else { return }
            let estimate = self.source.activityEstimate.fresh(at: self.clock.elapsedNanos())
            self.send(.batch(tripId: id, samples: samples, estimate: estimate, seaLevelOffset: offset))
        }, onAccessLost: { [weak self] in
            self?.send(.interrupt(.access))
        })
        motion.start { [weak self] chunks in self?.send(.sensors(tripId: id, chunks: chunks)) }
    }

    private func newSegment(_ id: String, _ reason: BreakReason, _ sequence: Int, _ chosen: RecordingOptions) -> TripSegment {
        TripSegment(id: UUID().uuidString, tripId: id, runId: processRunId, sequence: sequence, startedElapsedNanos: clock.elapsedNanos(),
                    startedMillis: clock.wallMillis(), reason: reason, highAccuracy: chosen.highAccuracy, networkFallback: chosen.networkFallback)
    }

    private func startTrack(_ distance: Double) {
        lastFixNanos = nil
        lastNetworkFixNanos = nil
        baseDistance = distance
        track = SegmentTrack()
        trackInOrder = true
    }

    /// Adds the open segment's new samples. A repeated sample changes nothing. Other samples need a full calculation.
    private func addToTrack(_ points: [TripPoint]) {
        guard let open = track else { return }
        for point in points.sorted(by: { $0.sample.elapsedNanos < $1.sample.elapsedNanos }) {
            let last = open.lastElapsedNanos
            if point.segmentId != segment?.id || (last.map { point.sample.elapsedNanos < $0 } ?? false) {
                trackInOrder = false
            } else if point.sample.elapsedNanos != last {
                _ = open.add(point.sample)
            }
        }
    }

    private func routeDistance(_ id: String) async throws -> Double {
        let route = try await store.route(id)
        return await Self.summarize(route).distanceMeters
    }

    @concurrent private static func summarize(_ route: [[TrackSample]]) async -> RouteSummary { RouteMath.summarize(route) }

    private func duration() -> Int64 {
        if let segment { return baseDuration + max(clock.elapsedNanos() - segment.startedElapsedNanos, 0) }
        return current?.durationNanos ?? 0
    }

    private func close(_ status: TripStatus, _ reason: BreakReason) async throws {
        guard var trip = current else { return }
        let now = clock.wallMillis()
        let running = baseDistance + (track?.distanceMeters ?? 0)
        var distance = running
        if !trackInOrder { distance = (try? await routeDistance(trip.id)) ?? running }
        trip.status = status
        trip.durationNanos = duration()
        trip.lastSavedMillis = now
        trip.endedMillis = status == .finished ? now : nil
        trip.distanceMeters = distance
        if var closed = segment {
            closed.endedElapsedNanos = clock.elapsedNanos()
            closed.endReason = reason
            try await store.closeSegment(trip, closed)
            segments = segments.map { $0.id == closed.id ? closed : $0 }
        } else {
            try await store.update(trip)
        }
        current = trip
        segment = nil
        baseDuration = trip.durationNanos
        baseDistance = distance
        track = nil
        trackInOrder = true
    }

    private func interrupt(_ error: RecordingError) async throws {
        source.pauseRecording()
        motion.stop()
        let reason: BreakReason = switch error {
        case .access: .accessLost
        case .service: .processLost
        default: .storageError
        }
        try await close(.interrupted, reason)
        current = nil
        segment = nil
        clearRoute()
        source.releaseRecording()
        state = RecordingState(error: error)
    }

    /// Reads the current trip's route once. A failed read leaves the route unavailable and does not stop the recording.
    private func loadRoute() async {
        guard let id = current?.id else { return clearRoute() }
        do {
            let segments = try await store.segments(id)
            let points = try await store.points(id)
            liveRoute = await Self.build(id, segments, points)
        } catch {
            liveRoute = nil
        }
        publishRoute()
    }

    @concurrent private static func build(_ id: String, _ segments: [TripSegment], _ points: [TripPoint]) async -> LiveRoute {
        let grouped = Dictionary(grouping: points, by: \.segmentId)
        let route = LiveRoute(tripId: id)
        for segment in segments { grouped[segment.id, default: []].forEach { route.add($0) } }
        return route
    }

    private func publishRoute() {
        route = liveRoute.map { ActiveRoute(tripId: $0.tripId, summary: $0.summary()) }
    }

    private func clearRoute() {
        liveRoute = nil
        route = nil
    }

    private func publish() {
        state = RecordingState(trip: current, segmentStartNanos: segment?.startedElapsedNanos, segmentBaseDurationNanos: baseDuration,
                               lastFixNanos: segment == nil ? nil : lastFixNanos,
                               lastNetworkFixNanos: segment == nil ? nil : lastNetworkFixNanos)
    }
}

private extension TripRecord {
    /// A stored record with the recorder's newer duration and distance, which are saved with the next fix.
    func withLive(_ live: TripRecord?) -> TripRecord {
        guard let live else { return self }
        var record = self
        record.durationNanos = live.durationNanos
        record.distanceMeters = live.distanceMeters
        record.lastSavedMillis = live.lastSavedMillis
        record.status = live.status
        return record
    }
}
