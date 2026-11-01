import Foundation

public struct ActivityEstimate: Hashable, Sendable {
    public var mode: TripMode
    public var elapsedNanos: Int64

    public init(mode: TripMode = .unknown, elapsedNanos: Int64 = 0) {
        self.mode = mode
        self.elapsedNanos = elapsedNanos
    }

    /// The estimate while it is less than 10 seconds old.
    public func fresh(at now: Int64) -> TripMode {
        let age = now - elapsedNanos
        return age >= 0 && age < 10_000_000_000 ? mode : .unknown
    }
}

/// Provisional GPS categories, not a validated activity classifier. No platform objects or stored coordinates.
public final class ActivityEstimator {
    private struct Evidence {
        var time: Int64
        var mode: TripMode
    }

    private var window: [Evidence] = []
    private var lastTime: Int64 = 0

    public init() {}

    public func reset() {
        window.removeAll()
        lastTime = 0
    }

    public func accept(_ sample: TrackSample) -> ActivityEstimate? {
        // Late history must not change the current estimate. A bad current fix clears the evidence.
        guard sample.isValid, sample.elapsedNanos > lastTime else { return nil }
        if sample.receivedElapsedNanos - sample.elapsedNanos >= 10_000_000_000 { return nil }
        if sample.elapsedNanos - lastTime >= 10_000_000_000 { window.removeAll() }
        lastTime = sample.elapsedNanos
        guard let speed = sample.usableSpeed, let accuracy = sample.accuracyMeters, accuracy <= 20,
              let speedError = sample.speedAccuracyMetersPerSecond, speedError <= 1, speed >= 2 else {
            window.removeAll()
            return ActivityEstimate(elapsedNanos: lastTime)
        }
        let mode: TripMode = speed >= 55 ? .plane : speed >= 12 ? .carOrTransit : .bikeOrSki
        window.append(Evidence(time: lastTime, mode: mode))
        // The window holds time, not a number of fixes, so a faster fix rate does not shorten it.
        while let first = window.first, lastTime - first.time > 30_000_000_000 { window.removeFirst() }
        let supported = window.filter { $0.mode == mode }.count
        let sustained = window.count >= 6 && lastTime - window[0].time >= 15_000_000_000
        return ActivityEstimate(mode: sustained && supported * 5 >= window.count * 4 ? mode : .unknown, elapsedNanos: lastTime)
    }
}
