import CVKEGPSCore
import Foundation
import Observation
import SwiftUI

/// The charts of the replay details. Each has its own values and moments, and the same playhead.
enum ReplayChartKind: Hashable, CaseIterable {
    case speed, height, acceleration
}

/// The replay mode of a saved trip (ADR 0004). The saved trip screen keeps its map and changes its panel.
/// The playhead moves at most 10 times each second, and only while the replay plays and the app is on screen.
@MainActor
@Observable
final class ReplayModel {
    static let rates = [1, 4, 16, 64]
    static let tickNanos: Int64 = 100_000_000

    let tripId: String
    private(set) var load: StoredRead<TripReplay?> = .loading
    private(set) var playhead: Int64 = 0
    private(set) var playing = false
    private(set) var rateIndex = 0
    /// True when the panel shows the charts. False for the glance view.
    var details = false
    /// The chart that shows when one chart shows at a time.
    var chart: ReplayChartKind = .speed

    @ObservationIgnored private var clock: Task<Void, Never>?

    init(tripId: String) {
        self.tripId = tripId
    }

    var replay: TripReplay? { load.value ?? nil }
    var rate: Int { Self.rates[rateIndex] }
    var values: ReplayValues? { replay?.valuesAt(playhead) }

    func load(from library: TripLibrary) async {
        guard case .loading = load else { return }
        load = await library.replay(tripId)
    }

    func retry(from library: TripLibrary) async {
        load = .loading
        await load(from: library)
    }

    func seek(_ time: Int64) {
        guard let replay else { return }
        playhead = min(max(time, 0), replay.durationNanos)
    }

    /// Moves to a moment and pauses there.
    func pause(at time: Int64) {
        pause()
        seek(time)
    }

    func toggle() {
        if playing { return pause() }
        guard let replay else { return }
        if playhead >= replay.durationNanos { playhead = 0 }
        playing = true
        run(replay)
    }

    func pause() {
        playing = false
        clock?.cancel()
        clock = nil
    }

    func nextRate() {
        rateIndex = (rateIndex + 1) % Self.rates.count
    }

    /// The real elapsed time sets the step, so a late tick does not slow the replay.
    private func run(_ replay: TripReplay) {
        clock?.cancel()
        clock = Task { [weak self] in
            var last = MonotonicClock.nanos()
            while !Task.isCancelled {
                try? await Task.sleep(for: .nanoseconds(Self.tickNanos))
                guard let self, self.playing, !Task.isCancelled else { return }
                let now = MonotonicClock.nanos()
                let next = min(self.playhead + (now - last) * Int64(self.rate), replay.durationNanos)
                last = now
                // The map moves evenly between two steps.
                withAnimation(.linear(duration: Double(Self.tickNanos) / 1e9)) { self.playhead = next }
                if next >= replay.durationNanos {
                    self.playing = false
                    return
                }
            }
        }
    }
}
