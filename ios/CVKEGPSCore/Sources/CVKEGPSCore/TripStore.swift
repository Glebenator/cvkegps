import Foundation
#if canImport(SQLite3)
import SQLite3
#else
import CSQLite
#endif

/// Saved trips. All calls run off the main thread, in order.
public protocol TripStore: Sendable {
    /// Trips that were recording or paused when the app process ended. The recorder marks them interrupted at start.
    func markInterrupted() async throws
    func trips() async throws -> [TripRecord]
    func trip(_ id: String) async throws -> TripRecord?
    func segments(_ tripId: String) async throws -> [TripSegment]
    func points(_ tripId: String) async throws -> [TripPoint]
    func sensorChunks(_ tripId: String) async throws -> [StoredSensorChunk]
    func start(_ trip: TripRecord, _ segment: TripSegment) async throws
    func save(_ trip: TripRecord, _ points: [TripPoint]) async throws
    func closeSegment(_ trip: TripRecord, _ segment: TripSegment) async throws
    func resume(_ trip: TripRecord, _ segment: TripSegment) async throws
    func update(_ trip: TripRecord) async throws
    func insertSensors(_ chunks: [StoredSensorChunk]) async throws
    /// Deletes a saved trip. A trip that records or is paused stays.
    func delete(_ tripId: String) async throws
}

extension TripStore {
    /// The saved samples of each segment, in segment order.
    @concurrent public func route(_ tripId: String) async throws -> [[TrackSample]] {
        let points = try await points(tripId)
        let grouped = Dictionary(grouping: points, by: \.segmentId)
        return try await segments(tripId).map { segment in grouped[segment.id, default: []].map(\.sample) }
    }

    /// The trip with its route summary, for the saved trip screen.
    @concurrent public func detail(_ tripId: String) async throws -> TripDetail? {
        guard let trip = try await trip(tripId) else { return nil }
        let segments = try await segments(tripId)
        let points = try await points(tripId)
        let grouped = Dictionary(grouping: points, by: \.segmentId)
        let summary = RouteMath.summarize(segments.map { grouped[$0.id, default: []].map(\.sample) })
        return TripDetail(trip: trip, positions: points.count, segments: segments.count, summary: summary)
    }

    /// The trip and its sensor record, ready to replay.
    @concurrent public func replay(_ tripId: String) async throws -> TripReplay? {
        guard let trip = try await trip(tripId) else { return nil }
        let segments = try await segments(tripId)
        let samples = Dictionary(grouping: try await points(tripId), by: \.segmentId)
        let sensors = Dictionary(grouping: try await sensorChunks(tripId), by: \.segmentId)
        return ReplayAnalysis.analyze(segments.map { segment in
            ReplaySegment(startedElapsedNanos: segment.startedElapsedNanos, endedElapsedNanos: segment.endedElapsedNanos,
                          samples: samples[segment.id, default: []].map(\.sample),
                          sensors: sensors[segment.id, default: []].compactMap { $0.chunk() })
        }, gpsHeightOnly: trip.mode == .plane)
    }
}

public struct StoreError: Error, CustomStringConvertible {
    public let code: Int32
    public var description: String { "SQLite error \(code)" }
}

/// Trips in a local SQLite database. The tables match the Android Room database, so both apps store the same data.
/// The app is not published, so a schema change deletes the saved trips. Add migrations before the first release.
public actor SQLiteTripStore: TripStore {
    static let schemaVersion: Int32 = 1
    // The actor alone uses the connection. The deinit closes it after the last use.
    nonisolated(unsafe) private let db: OpaquePointer
    nonisolated(unsafe) private var statements: [String: OpaquePointer] = [:]

    /// `path` nil opens a database in memory, for tests.
    public init(path: String?) throws {
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_NOMUTEX
        let result = sqlite3_open_v2(path ?? ":memory:", &handle, flags, nil)
        guard result == SQLITE_OK, let handle else {
            if let handle { sqlite3_close(handle) }
            throw StoreError(code: result)
        }
        db = handle
        try Self.prepare(handle)
    }

    deinit {
        for statement in statements.values { sqlite3_finalize(statement) }
        sqlite3_close(db)
    }

    private static func run(_ db: OpaquePointer, _ sql: String) throws {
        let result = sqlite3_exec(db, sql, nil, nil, nil)
        guard result == SQLITE_OK else { throw StoreError(code: result) }
    }

    private static func prepare(_ db: OpaquePointer) throws {
        try run(db, "PRAGMA journal_mode = WAL")
        try run(db, "PRAGMA foreign_keys = ON")
        var statement: OpaquePointer?
        sqlite3_prepare_v2(db, "PRAGMA user_version", -1, &statement, nil)
        var version: Int32 = 0
        if sqlite3_step(statement) == SQLITE_ROW { version = sqlite3_column_int(statement, 0) }
        sqlite3_finalize(statement)
        if version != 0 && version != schemaVersion {
            try run(db, "DROP TABLE IF EXISTS sensor_chunks; DROP TABLE IF EXISTS points; DROP TABLE IF EXISTS segments; DROP TABLE IF EXISTS trips")
        }
        try run(db, """
            CREATE TABLE IF NOT EXISTS trips(id TEXT PRIMARY KEY NOT NULL, mode TEXT NOT NULL, status TEXT NOT NULL,
                startedMillis INTEGER NOT NULL, endedMillis INTEGER, durationNanos INTEGER NOT NULL, lastSavedMillis INTEGER NOT NULL,
                policyVersion INTEGER NOT NULL, automaticMode INTEGER NOT NULL, distanceMeters REAL, seaLevelOffsetMeters REAL);
            CREATE TABLE IF NOT EXISTS segments(id TEXT PRIMARY KEY NOT NULL,
                tripId TEXT NOT NULL REFERENCES trips(id) ON DELETE CASCADE, runId TEXT NOT NULL, sequence INTEGER NOT NULL,
                startedElapsedNanos INTEGER NOT NULL, startedMillis INTEGER NOT NULL, endedElapsedNanos INTEGER, reason TEXT NOT NULL,
                endReason TEXT, highAccuracy INTEGER NOT NULL, networkFallback INTEGER NOT NULL);
            CREATE INDEX IF NOT EXISTS index_segments_tripId ON segments(tripId);
            CREATE TABLE IF NOT EXISTS points(tripId TEXT NOT NULL,
                segmentId TEXT NOT NULL REFERENCES segments(id) ON DELETE CASCADE,
                latitudeRadians REAL NOT NULL, longitudeRadians REAL NOT NULL, timeMillis INTEGER NOT NULL, elapsedNanos INTEGER NOT NULL,
                receivedElapsedNanos INTEGER NOT NULL, accuracyMeters REAL, speedMetersPerSecond REAL, speedAccuracyMetersPerSecond REAL,
                altitudeMeters REAL, verticalAccuracyMeters REAL, bearingRadians REAL, bearingAccuracyRadians REAL,
                isMock INTEGER NOT NULL, source TEXT NOT NULL, satellitesUsed INTEGER, PRIMARY KEY(segmentId, elapsedNanos));
            CREATE INDEX IF NOT EXISTS index_points_tripId ON points(tripId);
            CREATE TABLE IF NOT EXISTS sensor_chunks(tripId TEXT NOT NULL,
                segmentId TEXT NOT NULL REFERENCES segments(id) ON DELETE CASCADE, kind TEXT NOT NULL,
                firstElapsedNanos INTEGER NOT NULL, lastElapsedNanos INTEGER NOT NULL, count INTEGER NOT NULL, data BLOB NOT NULL,
                PRIMARY KEY(segmentId, kind, firstElapsedNanos));
            CREATE INDEX IF NOT EXISTS index_sensor_chunks_tripId ON sensor_chunks(tripId);
            PRAGMA user_version = \(schemaVersion);
            """)
    }

    // MARK: Statements

    private func statement(_ sql: String) throws -> OpaquePointer {
        if let cached = statements[sql] {
            sqlite3_reset(cached)
            sqlite3_clear_bindings(cached)
            return cached
        }
        var statement: OpaquePointer?
        let result = sqlite3_prepare_v2(db, sql, -1, &statement, nil)
        guard result == SQLITE_OK, let statement else { throw StoreError(code: result) }
        statements[sql] = statement
        return statement
    }

    private enum Value {
        case text(String?), int(Int64?), real(Double?), blob(Data)
    }

    /// SQLite copies the bound value.
    private static var transient: sqlite3_destructor_type { unsafeBitCast(-1, to: sqlite3_destructor_type.self) }

    private func bind(_ statement: OpaquePointer, _ values: [Value]) {
        for (offset, value) in values.enumerated() {
            let index = Int32(offset + 1)
            switch value {
            case .text(let text?): sqlite3_bind_text(statement, index, text, -1, Self.transient)
            case .int(let number?): sqlite3_bind_int64(statement, index, number)
            case .real(let number?) where number.isFinite: sqlite3_bind_double(statement, index, number)
            case .blob(let data):
                _ = data.withUnsafeBytes { sqlite3_bind_blob(statement, index, $0.baseAddress, Int32(data.count), Self.transient) }
            default: sqlite3_bind_null(statement, index)
            }
        }
    }

    private func execute(_ sql: String, _ values: [Value]) throws {
        let statement = try statement(sql)
        bind(statement, values)
        let result = sqlite3_step(statement)
        sqlite3_reset(statement)
        guard result == SQLITE_DONE || result == SQLITE_ROW else { throw StoreError(code: result) }
    }

    private func query<T>(_ sql: String, _ values: [Value], _ row: (OpaquePointer) -> T?) throws -> [T] {
        let statement = try statement(sql)
        bind(statement, values)
        defer { sqlite3_reset(statement) }
        var result: [T] = []
        while true {
            let step = sqlite3_step(statement)
            if step == SQLITE_DONE { break }
            guard step == SQLITE_ROW else { throw StoreError(code: step) }
            if let value = row(statement) { result.append(value) }
        }
        return result
    }

    private func transaction(_ body: () throws -> Void) throws {
        try Self.run(db, "BEGIN IMMEDIATE")
        do {
            try body()
            try Self.run(db, "COMMIT")
        } catch {
            try? Self.run(db, "ROLLBACK")
            throw error
        }
    }

    // MARK: Columns

    private static func text(_ s: OpaquePointer, _ i: Int32) -> String? {
        sqlite3_column_type(s, i) == SQLITE_NULL ? nil : sqlite3_column_text(s, i).map { String(cString: $0) }
    }

    private static func int(_ s: OpaquePointer, _ i: Int32) -> Int64? {
        sqlite3_column_type(s, i) == SQLITE_NULL ? nil : sqlite3_column_int64(s, i)
    }

    private static func real(_ s: OpaquePointer, _ i: Int32) -> Double? {
        sqlite3_column_type(s, i) == SQLITE_NULL ? nil : sqlite3_column_double(s, i)
    }

    private static func blob(_ s: OpaquePointer, _ i: Int32) -> Data {
        let count = Int(sqlite3_column_bytes(s, i))
        guard count > 0, let bytes = sqlite3_column_blob(s, i) else { return Data() }
        return Data(bytes: bytes, count: count)
    }

    private static let tripColumns = "id, mode, status, startedMillis, endedMillis, durationNanos, lastSavedMillis, policyVersion, " +
        "automaticMode, distanceMeters, seaLevelOffsetMeters"

    private static func trip(_ s: OpaquePointer) -> TripRecord? {
        guard let id = text(s, 0), let mode = text(s, 1).flatMap(TripMode.init(rawValue:)),
              let status = text(s, 2).flatMap(TripStatus.init(rawValue:)) else { return nil }
        return TripRecord(id: id, mode: mode, status: status, startedMillis: int(s, 3) ?? 0, endedMillis: int(s, 4),
                          durationNanos: int(s, 5) ?? 0, lastSavedMillis: int(s, 6), policyVersion: Int(int(s, 7) ?? 1),
                          automaticMode: (int(s, 8) ?? 0) != 0, distanceMeters: real(s, 9), seaLevelOffsetMeters: real(s, 10))
    }

    private func tripValues(_ trip: TripRecord) -> [Value] {
        [.text(trip.id), .text(trip.mode.rawValue), .text(trip.status.rawValue), .int(trip.startedMillis), .int(trip.endedMillis),
         .int(trip.durationNanos), .int(trip.lastSavedMillis), .int(Int64(trip.policyVersion)), .int(trip.automaticMode ? 1 : 0),
         .real(trip.distanceMeters), .real(trip.seaLevelOffsetMeters)]
    }

    private static let segmentColumns = "id, tripId, runId, sequence, startedElapsedNanos, startedMillis, endedElapsedNanos, reason, " +
        "endReason, highAccuracy, networkFallback"

    private static func segment(_ s: OpaquePointer) -> TripSegment? {
        guard let id = text(s, 0), let tripId = text(s, 1), let runId = text(s, 2),
              let reason = text(s, 7).flatMap(BreakReason.init(rawValue:)) else { return nil }
        return TripSegment(id: id, tripId: tripId, runId: runId, sequence: Int(int(s, 3) ?? 0), startedElapsedNanos: int(s, 4) ?? 0,
                           startedMillis: int(s, 5) ?? 0, endedElapsedNanos: int(s, 6), reason: reason,
                           endReason: text(s, 8).flatMap(BreakReason.init(rawValue:)), highAccuracy: (int(s, 9) ?? 0) != 0,
                           networkFallback: (int(s, 10) ?? 0) != 0)
    }

    private func segmentValues(_ segment: TripSegment) -> [Value] {
        [.text(segment.id), .text(segment.tripId), .text(segment.runId), .int(Int64(segment.sequence)), .int(segment.startedElapsedNanos),
         .int(segment.startedMillis), .int(segment.endedElapsedNanos), .text(segment.reason.rawValue), .text(segment.endReason?.rawValue),
         .int(segment.highAccuracy ? 1 : 0), .int(segment.networkFallback ? 1 : 0)]
    }

    private static let pointColumns = "tripId, segmentId, latitudeRadians, longitudeRadians, timeMillis, elapsedNanos, " +
        "receivedElapsedNanos, accuracyMeters, speedMetersPerSecond, speedAccuracyMetersPerSecond, altitudeMeters, " +
        "verticalAccuracyMeters, bearingRadians, bearingAccuracyRadians, isMock, source, satellitesUsed"

    private static func point(_ s: OpaquePointer) -> TripPoint? {
        guard let tripId = text(s, 0), let segmentId = text(s, 1), let latitude = real(s, 2), let longitude = real(s, 3) else { return nil }
        let sample = TrackSample(
            latitudeRadians: latitude, longitudeRadians: longitude, timeMillis: int(s, 4) ?? 0, elapsedNanos: int(s, 5) ?? 0,
            receivedElapsedNanos: int(s, 6) ?? 0, accuracyMeters: real(s, 7), speedMetersPerSecond: real(s, 8),
            speedAccuracyMetersPerSecond: real(s, 9), altitudeMeters: real(s, 10), verticalAccuracyMeters: real(s, 11),
            bearingRadians: real(s, 12), bearingAccuracyRadians: real(s, 13), isMock: (int(s, 14) ?? 0) != 0,
            source: text(s, 15).flatMap(FixSource.init(rawValue:)) ?? .gps, satellitesUsed: int(s, 16).map { Int($0) })
        return TripPoint(tripId: tripId, segmentId: segmentId, sample: sample)
    }

    // MARK: TripStore

    public func markInterrupted() throws {
        try execute("UPDATE trips SET status = 'Interrupted' WHERE status IN ('Recording', 'Paused')", [])
    }

    public func trips() throws -> [TripRecord] {
        try query("SELECT \(Self.tripColumns) FROM trips ORDER BY startedMillis DESC", [], Self.trip)
    }

    public func trip(_ id: String) throws -> TripRecord? {
        try query("SELECT \(Self.tripColumns) FROM trips WHERE id = ?", [.text(id)], Self.trip).first
    }

    public func segments(_ tripId: String) throws -> [TripSegment] {
        try query("SELECT \(Self.segmentColumns) FROM segments WHERE tripId = ? ORDER BY sequence", [.text(tripId)], Self.segment)
    }

    public func points(_ tripId: String) throws -> [TripPoint] {
        try query("SELECT \(Self.pointColumns) FROM points WHERE tripId = ? ORDER BY elapsedNanos", [.text(tripId)], Self.point)
    }

    public func sensorChunks(_ tripId: String) throws -> [StoredSensorChunk] {
        try query("SELECT tripId, segmentId, kind, firstElapsedNanos, lastElapsedNanos, count, data FROM sensor_chunks " +
                  "WHERE tripId = ? ORDER BY kind, firstElapsedNanos", [.text(tripId)]) { s in
            guard let tripId = Self.text(s, 0), let segmentId = Self.text(s, 1),
                  let kind = Self.text(s, 2).flatMap(SensorKind.init(rawValue:)) else { return nil }
            return StoredSensorChunk(tripId: tripId, segmentId: segmentId, kind: kind, firstElapsedNanos: Self.int(s, 3) ?? 0,
                                     lastElapsedNanos: Self.int(s, 4) ?? 0, count: Int(Self.int(s, 5) ?? 0), data: Self.blob(s, 6))
        }
    }

    private func insert(_ trip: TripRecord) throws {
        try execute("INSERT INTO trips(\(Self.tripColumns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", tripValues(trip))
    }

    private func insert(_ segment: TripSegment) throws {
        try execute("INSERT INTO segments(\(Self.segmentColumns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", segmentValues(segment))
    }

    private func updateRow(_ trip: TripRecord) throws {
        try execute("UPDATE trips SET mode = ?, status = ?, startedMillis = ?, endedMillis = ?, durationNanos = ?, lastSavedMillis = ?, " +
                    "policyVersion = ?, automaticMode = ?, distanceMeters = ?, seaLevelOffsetMeters = ? WHERE id = ?",
                    Array(tripValues(trip).dropFirst()) + [.text(trip.id)])
    }

    private func updateRow(_ segment: TripSegment) throws {
        try execute("UPDATE segments SET tripId = ?, runId = ?, sequence = ?, startedElapsedNanos = ?, startedMillis = ?, " +
                    "endedElapsedNanos = ?, reason = ?, endReason = ?, highAccuracy = ?, networkFallback = ? WHERE id = ?",
                    Array(segmentValues(segment).dropFirst()) + [.text(segment.id)])
    }

    public func start(_ trip: TripRecord, _ segment: TripSegment) throws {
        try transaction {
            try insert(trip)
            try insert(segment)
        }
    }

    public func save(_ trip: TripRecord, _ points: [TripPoint]) throws {
        try transaction {
            for point in points {
                let s = point.sample
                // A repeated fix of a segment changes nothing.
                try execute("INSERT OR IGNORE INTO points(\(Self.pointColumns)) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [
                    .text(point.tripId), .text(point.segmentId), .real(s.latitudeRadians), .real(s.longitudeRadians), .int(s.timeMillis),
                    .int(s.elapsedNanos), .int(s.receivedElapsedNanos), .real(s.accuracyMeters), .real(s.speedMetersPerSecond),
                    .real(s.speedAccuracyMetersPerSecond), .real(s.altitudeMeters), .real(s.verticalAccuracyMeters), .real(s.bearingRadians),
                    .real(s.bearingAccuracyRadians), .int(s.isMock ? 1 : 0), .text(s.source.rawValue), .int(s.satellitesUsed.map { Int64($0) }),
                ])
            }
            try updateRow(trip)
        }
    }

    public func closeSegment(_ trip: TripRecord, _ segment: TripSegment) throws {
        try transaction {
            try updateRow(segment)
            try updateRow(trip)
        }
    }

    public func resume(_ trip: TripRecord, _ segment: TripSegment) throws {
        try transaction {
            try insert(segment)
            try updateRow(trip)
        }
    }

    public func update(_ trip: TripRecord) throws { try updateRow(trip) }

    public func insertSensors(_ chunks: [StoredSensorChunk]) throws {
        try transaction {
            for chunk in chunks {
                try execute("INSERT OR IGNORE INTO sensor_chunks(tripId, segmentId, kind, firstElapsedNanos, lastElapsedNanos, count, data) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?)", [
                                .text(chunk.tripId), .text(chunk.segmentId), .text(chunk.kind.rawValue), .int(chunk.firstElapsedNanos),
                                .int(chunk.lastElapsedNanos), .int(Int64(chunk.count)), .blob(chunk.data),
                            ])
            }
        }
    }

    public func delete(_ tripId: String) throws {
        try execute("DELETE FROM trips WHERE id = ? AND status NOT IN ('Recording', 'Paused')", [.text(tripId)])
    }
}
