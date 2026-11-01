import Foundation
import Testing
@testable import CVKEGPSCore

/// The recorder with the real SQLite store and the production rules. Only the location service, the motion sensors, and
/// the clock are test objects, because this host has no Core Location. The iOS UI tests cover the real location path.
@MainActor
final class FakeLocation: RecordingLocationSource {
    var activityEstimate = ActivityEstimate()
    var onBatch: (@MainActor ([TrackSample], Double?) -> Void)?
    var onAccessLost: (@MainActor () -> Void)?
    var demand = "released"
    var options = RecordingOptions()

    func recording(options: RecordingOptions, onBatch: @escaping @MainActor ([TrackSample], Double?) -> Void,
                   onAccessLost: @escaping @MainActor () -> Void) {
        demand = "recording"
        self.options = options
        self.onBatch = onBatch
        self.onAccessLost = onAccessLost
    }

    func pauseRecording() {
        demand = "paused"
        onBatch = nil
    }

    func releaseRecording() {
        demand = "released"
        onBatch = nil
        onAccessLost = nil
    }
}

@MainActor
final class FakeMotion: MotionSource {
    var onChunks: (@MainActor ([SensorChunk]) -> Void)?
    func start(onChunks: @escaping @MainActor ([SensorChunk]) -> Void) { self.onChunks = onChunks }
    func stop() { onChunks = nil }
}

final class TestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var nanos: Int64 = 1_000_000_000_000
    private var millis: Int64 = 1_800_000_000_000

    func advance(seconds: Int64) {
        lock.lock()
        nanos += seconds * 1_000_000_000
        millis += seconds * 1_000
        lock.unlock()
    }

    var elapsed: Int64 { lock.lock(); defer { lock.unlock() }; return nanos }
    var wall: Int64 { lock.lock(); defer { lock.unlock() }; return millis }
    var recorderClock: RecorderClock { RecorderClock(elapsedNanos: { self.elapsed }, wallMillis: { self.wall }) }
}

/// A store that fails its writes on request, as a full disk does.
actor FailingStore: TripStore {
    let base: SQLiteTripStore
    var failWrites = false

    init(_ base: SQLiteTripStore) { self.base = base }

    func fail() { failWrites = true }
    private func check() throws { if failWrites { throw StoreError(code: 13) } }

    func markInterrupted() async throws { try await base.markInterrupted() }
    func trips() async throws -> [TripRecord] { try await base.trips() }
    func trip(_ id: String) async throws -> TripRecord? { try await base.trip(id) }
    func segments(_ tripId: String) async throws -> [TripSegment] { try await base.segments(tripId) }
    func points(_ tripId: String) async throws -> [TripPoint] { try await base.points(tripId) }
    func sensorChunks(_ tripId: String) async throws -> [StoredSensorChunk] { try await base.sensorChunks(tripId) }
    func start(_ trip: TripRecord, _ segment: TripSegment) async throws { try check(); try await base.start(trip, segment) }
    func save(_ trip: TripRecord, _ points: [TripPoint]) async throws { try check(); try await base.save(trip, points) }
    func closeSegment(_ trip: TripRecord, _ segment: TripSegment) async throws { try check(); try await base.closeSegment(trip, segment) }
    func resume(_ trip: TripRecord, _ segment: TripSegment) async throws { try check(); try await base.resume(trip, segment) }
    func update(_ trip: TripRecord) async throws { try check(); try await base.update(trip) }
    func insertSensors(_ chunks: [StoredSensorChunk]) async throws { try check(); try await base.insertSensors(chunks) }
    func delete(_ tripId: String) async throws { try check(); try await base.delete(tripId) }
}

@MainActor
struct RecorderTests {
    let clock = TestClock()
    let location = FakeLocation()
    let motion = FakeMotion()

    func recorder(_ store: any TripStore, options: RecordingOptions = RecordingOptions(networkFallback: true)) -> TripRecorder {
        TripRecorder(store: store, source: location, motion: motion, clock: clock.recorderClock, options: { options })
    }

    func settle(_ recorder: TripRecorder) async {
        for _ in 0..<2_000 where recorder.pending > 0 { try? await Task.sleep(nanoseconds: 1_000_000) }
        // One more turn, so the last command's state change is visible.
        await Task.yield()
    }

    /// A GPS fix at the clock's time, `east` metres from the start.
    func fix(east: Double, speed: Double = 10, source: FixSource = .gps) -> TrackSample {
        TrackSample(latitudeRadians: 0, longitudeRadians: east / 6_371_008.8, timeMillis: clock.wall, elapsedNanos: clock.elapsed,
                    receivedElapsedNanos: clock.elapsed, accuracyMeters: source == .gps ? 3 : 30, speedMetersPerSecond: source == .gps ? speed : nil,
                    speedAccuracyMetersPerSecond: source == .gps ? 0.3 : nil, altitudeMeters: source == .gps ? 120 : nil,
                    verticalAccuracyMeters: source == .gps ? 4 : nil, bearingRadians: source == .gps ? .pi / 2 : nil, source: source)
    }

    @Test func aTripRecordsPausesResumesAndSavesWithSegmentsAndDistance() async throws {
        let store = try SQLiteTripStore(path: nil)
        let recorder = recorder(store)
        recorder.send(.start(mode: .unknown, automatic: true))
        #expect(recorder.state.starting)
        await settle(recorder)
        let id = try #require(recorder.state.trip?.id)
        #expect(recorder.state.trip?.status == .recording)
        #expect(location.demand == "recording")
        #expect(location.options.networkFallback)
        #expect(motion.onChunks != nil)
        for east in stride(from: 0.0, through: 40, by: 10) {
            clock.advance(seconds: 1)
            location.onBatch?([fix(east: east)], 30.5)
        }
        await settle(recorder)
        #expect(abs((recorder.state.trip?.distanceMeters ?? 0) - 40) < 0.01)
        #expect(recorder.state.lastFixNanos == clock.elapsed)
        #expect(recorder.state.trip?.seaLevelOffsetMeters == 30.5)
        // Sensor readings belong to the open segment.
        motion.onChunks?([try #require(SensorChunk(kind: .pressure, elapsedNanos: [clock.elapsed], values: [1013]))])
        recorder.send(.pause)
        await settle(recorder)
        #expect(recorder.state.trip?.status == .paused)
        #expect(location.demand == "paused")
        #expect(motion.onChunks == nil)
        // A fix that arrives during the pause is not stored.
        clock.advance(seconds: 60)
        recorder.send(.batch(tripId: id, samples: [fix(east: 500)], estimate: .unknown, seaLevelOffset: nil))
        recorder.send(.resume(id: id, reopen: false))
        await settle(recorder)
        #expect(recorder.state.trip?.status == .recording)
        for east in stride(from: 100.0, through: 120, by: 10) {
            clock.advance(seconds: 1)
            location.onBatch?([fix(east: east)], nil)
        }
        recorder.send(.finish(id: id))
        await settle(recorder)
        #expect(recorder.state.trip == nil)
        #expect(location.demand == "released")
        let saved = try #require(try await store.trip(id))
        #expect(saved.status == .finished)
        #expect(abs((saved.distanceMeters ?? 0) - 60) < 0.01)
        // Recording time counts the segments and not the pause.
        #expect(saved.durationNanos == 8 * 1_000_000_000)
        let segments = try await store.segments(id)
        #expect(segments.map(\.reason) == [.start, .resume])
        #expect(segments.map(\.endReason) == [.manualPause, .stop])
        #expect(try await store.points(id).count == 8)
        #expect(try await store.sensorChunks(id).count == 1)
        let detail = try #require(try await store.detail(id))
        #expect(detail.summary.lines.count == 2)
        #expect(abs(detail.summary.distanceMeters - (saved.distanceMeters ?? 0)) < 0.01)
        recorder.close()
    }

    @Test func aSavedTripContinuesOnlyWithinTwoMinutesOfStop() async throws {
        let store = try SQLiteTripStore(path: nil)
        let recorder = recorder(store)
        recorder.send(.start(mode: .car, automatic: false))
        await settle(recorder)
        let id = try #require(recorder.state.trip?.id)
        recorder.send(.finish(id: id))
        await settle(recorder)
        clock.advance(seconds: 119)
        recorder.send(.resume(id: id, reopen: true))
        await settle(recorder)
        #expect(recorder.state.trip?.status == .recording)
        recorder.send(.finish(id: id))
        await settle(recorder)
        clock.advance(seconds: 121)
        recorder.send(.resume(id: id, reopen: true))
        await settle(recorder)
        #expect(recorder.state.trip == nil)
        #expect(recorder.state.error == .refused)
        // A plain resume never continues a saved trip.
        recorder.send(.resume(id: id, reopen: false))
        await settle(recorder)
        #expect(recorder.state.error == .refused)
        recorder.close()
    }

    @Test func noGpsSignalAndApproximatePositionsFollowTheGlossary() async throws {
        let store = try SQLiteTripStore(path: nil)
        let recorder = recorder(store)
        recorder.send(.start(mode: .unknown, automatic: true))
        await settle(recorder)
        let start = try #require(recorder.state.segmentStartNanos)
        #expect(recorder.state.signalLostAtNanos == start + RouteMath.signalGapNanos)
        clock.advance(seconds: 12)
        location.onBatch?([fix(east: 0, source: .network)], nil)
        await settle(recorder)
        #expect(recorder.state.signalLost(at: clock.elapsed))
        #expect(recorder.state.approximate(at: clock.elapsed))
        clock.advance(seconds: 1)
        location.onBatch?([fix(east: 10)], nil)
        await settle(recorder)
        #expect(!recorder.state.signalLost(at: clock.elapsed))
        #expect(!recorder.state.approximate(at: clock.elapsed))
        // The change between a network position and a GPS fix adds no distance.
        #expect(recorder.state.trip?.distanceMeters == 0)
        recorder.close()
    }

    @Test func aStorageFailureEndsTheRecordingAndKeepsTheSavedPart() async throws {
        let base = try SQLiteTripStore(path: nil)
        let store = FailingStore(base)
        let recorder = recorder(store)
        recorder.send(.start(mode: .unknown, automatic: true))
        await settle(recorder)
        let id = try #require(recorder.state.trip?.id)
        clock.advance(seconds: 1)
        location.onBatch?([fix(east: 0)], nil)
        await settle(recorder)
        await store.fail()
        clock.advance(seconds: 1)
        location.onBatch?([fix(east: 10)], nil)
        await settle(recorder)
        #expect(recorder.state.trip == nil)
        #expect(recorder.state.error == .storage)
        #expect(location.demand == "released")
        #expect(motion.onChunks == nil)
        #expect(try await base.points(id).count == 1)
        recorder.close()
    }

    @Test func tripsOfAnEndedProcessAreInterruptedAndResumeAsANewSegment() async throws {
        let store = try SQLiteTripStore(path: nil)
        let first = recorder(store)
        first.send(.start(mode: .bike, automatic: false))
        await settle(first)
        let id = try #require(first.state.trip?.id)
        first.close()
        // A new process marks the trip interrupted before its first command.
        let second = recorder(store)
        await settle(second)
        for _ in 0..<100 where try await store.trip(id)?.status != .interrupted { try await Task.sleep(nanoseconds: 1_000_000) }
        #expect(try await store.trip(id)?.status == .interrupted)
        second.send(.resume(id: id, reopen: false))
        await settle(second)
        #expect(second.state.trip?.status == .recording)
        #expect(try await store.segments(id).map(\.reason) == [.start, .processLost])
        // A trip that records cannot be deleted.
        try await store.delete(id)
        #expect(try await store.trip(id) != nil)
        second.send(.finish(id: id))
        await settle(second)
        try await store.delete(id)
        #expect(try await store.trip(id) == nil)
        #expect(try await store.points(id).isEmpty)
        second.close()
    }

    @Test func accessLossInterruptsTheTrip() async throws {
        let store = try SQLiteTripStore(path: nil)
        let recorder = recorder(store)
        recorder.send(.start(mode: .unknown, automatic: true))
        await settle(recorder)
        let id = try #require(recorder.state.trip?.id)
        location.onAccessLost?()
        await settle(recorder)
        #expect(recorder.state.error == .access)
        #expect(try await store.trip(id)?.status == .interrupted)
        #expect(try await store.segments(id).last?.endReason == .accessLost)
        recorder.close()
    }
}
