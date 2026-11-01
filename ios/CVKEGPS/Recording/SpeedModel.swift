import CVKEGPSCore
import Foundation
import Observation

enum GpsStatus: Hashable {
    case permissionRequired, reducedAccuracy, disabled, unavailable, searching, live, stale, paused, error
}

/// The Speed tab state: the status, the current reading, and the maximum speed since the app started.
/// One cancellable deadline after each fix makes an old reading stale. There is no timer and no location polling.
@MainActor
@Observable
final class SpeedModel {
    private(set) var status: GpsStatus = .searching
    private(set) var reading: GpsReading?
    /// The maximum usable speed since the app started. It stays while the app process runs.
    private(set) var maximum: Double?
    private(set) var receivedCount = 0
    private(set) var lastFixNanos: Int64?

    @ObservationIgnored private var deadline: Task<Void, Never>?
    static let freshnessNanos: Int64 = 10_000_000_000

    func update(_ event: GpsEvent) {
        deadline?.cancel()
        switch event {
        case let .fix(reading, count):
            receivedCount = count
            lastFixNanos = reading.elapsedNanos
            let age = MonotonicClock.nanos() - reading.elapsedNanos
            guard age >= 0 && age < Self.freshnessNanos else { return stale() }
            if let speed = reading.speedMetersPerSecond { maximum = max(maximum ?? speed, speed) }
            self.reading = reading
            status = .live
            deadline = Task { [weak self] in
                try? await Task.sleep(for: .nanoseconds(Self.freshnessNanos - age))
                guard !Task.isCancelled else { return }
                self?.stale()
            }
        case .permissionRequired: set(.permissionRequired)
        case .reducedAccuracy: set(.reducedAccuracy)
        case .disabled: set(.disabled)
        case .unavailable: set(.unavailable)
        case .searching: set(.searching)
        case .paused: set(.paused)
        case .error: set(.error)
        }
    }

    private func set(_ status: GpsStatus) {
        self.status = status
        reading = nil
    }

    private func stale() {
        status = .stale
        reading = nil
    }

    /// A fresh position with an accuracy estimate of 5 m or better. The GPS card then reduces to its bar.
    var ready: Bool {
        guard status == .live, let accuracy = reading?.accuracyMeters else { return false }
        return accuracy.isFinite && accuracy >= 0 && accuracy <= 5
    }

    /// A trip can start without an access problem on the Speed tab.
    var canStartTrip: Bool { status != .permissionRequired && status != .disabled && status != .unavailable && status != .reducedAccuracy }
}
