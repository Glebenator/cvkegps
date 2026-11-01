import Foundation

/// The activity of a trip. `carOrTransit` and `bikeOrSki` are estimates from GPS speed.
public enum TripMode: String, CaseIterable, Hashable, Sendable {
    case car = "Car", transit = "Transit", plane = "Plane", bike = "Bike", ski = "Ski", unknown = "Unknown"
    case carOrTransit = "CarOrTransit", bikeOrSki = "BikeOrSki"

    /// The activities that the user can choose.
    public static let choices: [TripMode] = [.car, .transit, .plane, .bike, .ski]
}

public enum TripStatus: String, Hashable, Sendable {
    case recording = "Recording", paused = "Paused", interrupted = "Interrupted", finished = "Finished"
}

public enum BreakReason: String, Hashable, Sendable {
    case start = "Start", resume = "Resume", manualPause = "ManualPause", stop = "Stop", signalLoss = "SignalLoss"
    case accessLost = "AccessLost", processLost = "ProcessLost", storageError = "StorageError"
}

public struct TripRecord: Hashable, Sendable, Identifiable {
    public var id: String
    public var mode: TripMode
    public var status: TripStatus
    public var startedMillis: Int64
    public var endedMillis: Int64?
    public var durationNanos: Int64
    public var lastSavedMillis: Int64
    public var policyVersion: Int
    public var automaticMode: Bool
    /// Nil until it is known. The recorder calculates it again from the saved route when it resumes a trip without one.
    public var distanceMeters: Double?
    /// The GPS height minus the sea-level altitude near the start of the trip. Core Location reports both heights with
    /// each fix, so the replay can show heights above sea level. The geoid changes slowly, so one value serves a trip.
    public var seaLevelOffsetMeters: Double?

    public init(
        id: String, mode: TripMode, status: TripStatus, startedMillis: Int64, endedMillis: Int64? = nil, durationNanos: Int64 = 0,
        lastSavedMillis: Int64? = nil, policyVersion: Int = 1, automaticMode: Bool = false, distanceMeters: Double? = nil,
        seaLevelOffsetMeters: Double? = nil
    ) {
        self.id = id
        self.mode = mode
        self.status = status
        self.startedMillis = startedMillis
        self.endedMillis = endedMillis
        self.durationNanos = durationNanos
        self.lastSavedMillis = lastSavedMillis ?? startedMillis
        self.policyVersion = policyVersion
        self.automaticMode = automaticMode
        self.distanceMeters = distanceMeters
        self.seaLevelOffsetMeters = seaLevelOffsetMeters
    }
}

public struct TripSegment: Hashable, Sendable, Identifiable {
    public var id: String
    public var tripId: String
    /// The app process that recorded the segment. Samples of another process never join it.
    public var runId: String
    public var sequence: Int
    public var startedElapsedNanos: Int64
    public var startedMillis: Int64
    public var endedElapsedNanos: Int64?
    public var reason: BreakReason
    public var endReason: BreakReason?
    /// The recording choices of this segment. See `RecordingOptions`.
    public var highAccuracy: Bool
    public var networkFallback: Bool

    public init(
        id: String, tripId: String, runId: String, sequence: Int, startedElapsedNanos: Int64, startedMillis: Int64,
        endedElapsedNanos: Int64? = nil, reason: BreakReason, endReason: BreakReason? = nil, highAccuracy: Bool = false,
        networkFallback: Bool = false
    ) {
        self.id = id
        self.tripId = tripId
        self.runId = runId
        self.sequence = sequence
        self.startedElapsedNanos = startedElapsedNanos
        self.startedMillis = startedMillis
        self.endedElapsedNanos = endedElapsedNanos
        self.reason = reason
        self.endReason = endReason
        self.highAccuracy = highAccuracy
        self.networkFallback = networkFallback
    }
}

public struct TripPoint: Hashable, Sendable {
    public var tripId: String
    public var segmentId: String
    public var sample: TrackSample

    public init(tripId: String, segmentId: String, sample: TrackSample) {
        self.tripId = tripId
        self.segmentId = segmentId
        self.sample = sample
    }
}

/// Readings of one phone sensor in one segment, as `SensorChunk.encode` stores them.
public struct StoredSensorChunk: Hashable, Sendable {
    public var tripId: String
    public var segmentId: String
    public var kind: SensorKind
    public var firstElapsedNanos: Int64
    public var lastElapsedNanos: Int64
    public var count: Int
    public var data: Data

    public init(tripId: String, segmentId: String, kind: SensorKind, firstElapsedNanos: Int64, lastElapsedNanos: Int64, count: Int,
                data: Data) {
        self.tripId = tripId
        self.segmentId = segmentId
        self.kind = kind
        self.firstElapsedNanos = firstElapsedNanos
        self.lastElapsedNanos = lastElapsedNanos
        self.count = count
        self.data = data
    }

    public func chunk() -> SensorChunk? { SensorChunk.decode(kind, data) }
}

/// The trip details that the saved trip screen shows.
public struct TripDetail: Hashable, Sendable {
    public var trip: TripRecord
    public var positions: Int
    public var segments: Int
    public var summary: RouteSummary

    public init(trip: TripRecord, positions: Int, segments: Int, summary: RouteSummary) {
        self.trip = trip
        self.positions = positions
        self.segments = segments
        self.summary = summary
    }
}

/// `refused` is a resume that the recorder did not do, for example after the time to continue a saved trip ended.
public enum RecordingError: Hashable, Sendable {
    case access, storage, queue, service, refused
}

/// The recording choices for a segment. `highAccuracy` asks for the best accuracy that the phone can give.
/// `networkFallback` records approximate positions while the trip has no GPS signal.
public struct RecordingOptions: Hashable, Sendable {
    public var highAccuracy: Bool
    public var networkFallback: Bool

    public init(highAccuracy: Bool = false, networkFallback: Bool = false) {
        self.highAccuracy = highAccuracy
        self.networkFallback = networkFallback
    }
}

/// The route of the current trip, as a screen shows it.
public struct ActiveRoute: Hashable, Sendable {
    public var tripId: String
    public var summary: RouteSummary
}

/// A saved trip can continue within this time after Stop and save. This undoes an accidental stop.
public let reopenWindowMillis: Int64 = 120_000

public struct RecordingState: Hashable, Sendable {
    public var trip: TripRecord?
    public var error: RecordingError?
    public var starting: Bool
    public var segmentStartNanos: Int64?
    public var segmentBaseDurationNanos: Int64
    /// Fix time of the newest saved GPS sample in the open segment. Nil before the first sample.
    public var lastFixNanos: Int64?
    /// Fix time of the newest saved network sample in the open segment. Nil before the first one.
    public var lastNetworkFixNanos: Int64?

    public init(trip: TripRecord? = nil, error: RecordingError? = nil, starting: Bool = false, segmentStartNanos: Int64? = nil,
                segmentBaseDurationNanos: Int64 = 0, lastFixNanos: Int64? = nil, lastNetworkFixNanos: Int64? = nil) {
        self.trip = trip
        self.error = error
        self.starting = starting
        self.segmentStartNanos = segmentStartNanos
        self.segmentBaseDurationNanos = segmentBaseDurationNanos
        self.lastFixNanos = lastFixNanos
        self.lastNetworkFixNanos = lastNetworkFixNanos
    }

    /// True while the app must keep the recording running: a trip records, is paused, or starts.
    public var active: Bool { starting || trip?.status == .recording || trip?.status == .paused }

    /// The time at which a recording trip has had no fix for `RouteMath.signalGapNanos`, or nil when it is not recording.
    /// The segment start counts as the newest fix, so a new segment has the same delay before it reports no signal.
    public var signalLostAtNanos: Int64? {
        guard trip?.status == .recording, let start = segmentStartNanos else { return nil }
        return max(start, lastFixNanos ?? start) + RouteMath.signalGapNanos
    }

    public func signalLost(at nowNanos: Int64) -> Bool { signalLostAtNanos.map { nowNanos >= $0 } ?? false }

    /// The time until which the newest network sample is a current approximate position, or nil without one.
    public var approximateUntilNanos: Int64? {
        guard trip?.status == .recording, let last = lastNetworkFixNanos else { return nil }
        return last + RouteMath.signalGapNanos
    }

    /// True while the trip has no GPS signal and a current network position replaces it.
    public func approximate(at nowNanos: Int64) -> Bool {
        signalLost(at: nowNanos) && (approximateUntilNanos.map { nowNanos < $0 } ?? false)
    }

    /// The recording time at `nowNanos`, which includes the open segment.
    public func elapsedNanos(at nowNanos: Int64) -> Int64 {
        guard let trip else { return 0 }
        guard trip.status == .recording, let start = segmentStartNanos else { return trip.durationNanos }
        return segmentBaseDurationNanos + max(nowNanos - start, 0)
    }
}
