import CVKEGPSCore
import Foundation
import Observation

/// A read of saved trips: not answered yet, the stored value, or a failure that the user can try again.
enum StoredRead<Value> {
    case loading
    case ready(Value)
    case failed

    var value: Value? {
        if case let .ready(value) = self { return value }
        return nil
    }
}

/// The saved trips, read from the store off the main thread. A storage error shows as a failed read, never as an empty list.
@MainActor
@Observable
final class TripLibrary {
    private(set) var trips: StoredRead<[TripRecord]> = .loading
    /// True after a delete failed, until the next delete or until the list closes.
    var deleteFailed = false
    /// Changes after each delete, so screens read their trip again.
    private(set) var revision = 0

    @ObservationIgnored let store: any TripStore
    @ObservationIgnored private var reloadTask: Task<Void, Never>?
    @ObservationIgnored private var pendingReload: Task<Void, Never>?
    @ObservationIgnored private var lastReload = Date.distantPast
    @ObservationIgnored private var replayCache: (id: String, replay: TripReplay?)?

    init(store: any TripStore) {
        self.store = store
    }

    func reload() {
        reloadTask?.cancel()
        lastReload = .now
        reloadTask = Task {
            do {
                let trips = try await store.trips()
                if !Task.isCancelled { self.trips = .ready(trips) }
            } catch {
                if !Task.isCancelled { self.trips = .failed }
            }
        }
    }

    /// A recording trip saves each fix. The list reads again at most every 2 seconds.
    func reloadSoon() {
        guard pendingReload == nil else { return }
        let wait = max(0, 2 - Date.now.timeIntervalSince(lastReload))
        pendingReload = Task {
            if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
            pendingReload = nil
            reload()
        }
    }

    func detail(_ id: String) async -> StoredRead<TripDetail?> {
        do { return .ready(try await store.detail(id)) } catch { return .failed }
    }

    /// The replay of a saved trip, calculated once off the main thread. The newest replay stays in memory.
    func replay(_ id: String) async -> StoredRead<TripReplay?> {
        if let cache = replayCache, cache.id == id { return .ready(cache.replay) }
        do {
            let replay = try await store.replay(id)
            replayCache = (id, replay)
            return .ready(replay)
        } catch {
            return .failed
        }
    }

    func delete(_ id: String) async {
        deleteFailed = false
        do {
            try await store.delete(id)
            if replayCache?.id == id { replayCache = nil }
            clearExports()
            revision += 1
            reload()
        } catch {
            deleteFailed = true
        }
    }

    /// The private folder for shared GPX files. Only the newest export stays.
    static var exportFolder: URL { FileManager.default.temporaryDirectory.appendingPathComponent("exports", isDirectory: true) }

    /// Writes the trip as GPX and returns the file, or nil when the read or the write fails.
    func exportGpx(_ id: String, name: String) async -> URL? {
        do {
            guard let trip = try await store.trip(id) else { return nil }
            let lines = RouteMath.summarize(try await store.route(id)).lines
            clearExports()
            let folder = Self.exportFolder
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            let file = folder.appendingPathComponent(Gpx.fileName(startedMillis: trip.startedMillis))
            try Data(Gpx.write(name: name, lines: lines).utf8).write(to: file, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
            return file
        } catch {
            return nil
        }
    }

    private func clearExports() {
        try? FileManager.default.removeItem(at: Self.exportFolder)
    }
}
