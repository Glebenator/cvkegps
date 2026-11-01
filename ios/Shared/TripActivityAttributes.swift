import ActivityKit
import Foundation

/// The Live Activity of a recording trip: on the Lock Screen, in the Dynamic Island, and in StandBy.
/// It replaces the Android recording notification. The app formats the values, so the widget extension needs no
/// measurement rules. It never shows the current speed, because a Live Activity cannot reliably remove an old speed.
struct TripActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable, Sendable {
        /// True while the trip records. False while it is paused.
        var recording: Bool
        /// True after 10 seconds without a GPS fix.
        var noSignal: Bool
        /// While recording: the time at which the recording time was zero, so the system counts the time without updates.
        var timerStart: Date?
        /// The recording time as text, for a paused trip.
        var durationText: String
        /// The distance, or nil before the trip has one.
        var distanceText: String?
        /// The average speed including stops, or nil before it is known.
        var averageText: String?
    }

    /// The trip that records.
    var tripId: String
}
