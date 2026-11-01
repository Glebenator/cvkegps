import ActivityKit
import CVKEGPSCore
import Foundation

/// Shows the recording trip as a Live Activity, as the Android app shows its recording notification.
/// State changes show at once. New values show at most every 30 seconds, so the activity does not update with each fix.
/// While recording, the system counts the time without updates. The current speed is never shown.
@MainActor
final class LiveActivityController {
    private struct Key: Equatable {
        var id: String
        var recording: Bool
        var segmentStart: Int64?
        var noSignal: Bool
    }

    /// The shown activity and its trip. `Activity` is not `Sendable`, so the tasks that update it look it up by its ID.
    private var activityId: String?
    private var activityTrip: String?
    private var shownKey: Key?
    private var shownValues: [String?] = []
    private var shownAt: Int64 = 0
    private var signalWatch: Task<Void, Never>?
    private let format: @MainActor () -> DisplayFormat
    private static let valueIntervalNanos: Int64 = 30_000_000_000

    init(format: @escaping @MainActor () -> DisplayFormat) {
        self.format = format
        // A Live Activity of an ended app process shows a trip that no longer records.
        Task { await Self.end(nil) }
    }

    /// Live Activities are on for this app in the iOS settings.
    var enabled: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    func update(_ state: RecordingState) {
        signalWatch?.cancel()
        guard state.active, let trip = state.trip else { return end() }
        let now = MonotonicClock.nanos()
        let key = Key(id: trip.id, recording: trip.status == .recording, segmentStart: state.segmentStartNanos,
                      noSignal: state.signalLost(at: now))
        let content = content(state, trip, now, key)
        let values = [content.distanceText, content.averageText]
        if activityTrip != trip.id { end() }
        if let id = activityId {
            if key != shownKey || (values != shownValues && now - shownAt >= Self.valueIntervalNanos) {
                Task { await Self.update(id, content) }
                remember(key, values, now)
            }
        } else {
            guard enabled else { return }
            let activity = try? Activity.request(attributes: TripActivityAttributes(tripId: trip.id),
                                                 content: ActivityContent(state: content, staleDate: nil), pushType: nil)
            activityId = activity?.id
            activityTrip = activity == nil ? nil : trip.id
            remember(key, values, now)
        }
        // A new fix can move the time of the signal loss, so each state change starts a new wait.
        if let lostAt = state.signalLostAtNanos, lostAt > now {
            signalWatch = Task { [weak self] in
                try? await Task.sleep(for: .nanoseconds(lostAt - now + 1_000_000))
                guard !Task.isCancelled else { return }
                self?.update(state)
            }
        }
    }

    private func remember(_ key: Key, _ values: [String?], _ now: Int64) {
        shownKey = key
        shownValues = values
        shownAt = now
    }

    private func content(_ state: RecordingState, _ trip: TripRecord, _ now: Int64, _ key: Key) -> TripActivityAttributes.ContentState {
        let format = format()
        let elapsed = state.elapsedNanos(at: now)
        let distance = trip.distanceMeters
        let average = averageSpeed(distanceMeters: distance, durationNanos: elapsed)
        return TripActivityAttributes.ContentState(
            recording: key.recording, noSignal: key.recording && key.noSignal,
            timerStart: key.recording ? Date.now.addingTimeInterval(-Double(elapsed) / 1e9) : nil,
            durationText: format.duration(elapsed),
            distanceText: distance.map(format.distance), averageText: average.map(format.speed)
        )
    }

    private func end() {
        signalWatch?.cancel()
        guard let id = activityId else { return }
        activityId = nil
        activityTrip = nil
        shownKey = nil
        Task { await Self.end(id) }
    }

    @concurrent private static func update(_ id: String, _ state: TripActivityAttributes.ContentState) async {
        for activity in Activity<TripActivityAttributes>.activities where activity.id == id {
            await activity.update(ActivityContent(state: state, staleDate: nil))
        }
    }

    /// Ends the activity with this ID, or all activities of the app for nil.
    @concurrent private static func end(_ id: String?) async {
        for activity in Activity<TripActivityAttributes>.activities where id == nil || activity.id == id {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
    }
}
