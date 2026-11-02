import CVKEGPSCore
import SwiftUI

/// The current trip: a full-screen map that follows the newest position, and a glass panel with the status, the speed,
/// the time, the distance, the activity, and Pause and Stop.
struct LiveTripView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format
    @Environment(\.dismiss) private var dismiss
    @State private var details = false
    @State private var seenTrip = false
    @State private var panelHeight: CGFloat = 0
    /// The height of the values row: a caption and a large number.
    @ScaledMetric(relativeTo: .title) private var valuesHeight: CGFloat = 60

    var body: some View {
        let state = model.recorder.state
        let summary = model.recorder.route.flatMap { $0.tripId == state.trip?.id ? $0.summary : nil }
        let lines = summary?.lines
        // The newest plotted point is the position marker. Its own fix time sets its age.
        let shown = lines?.last(where: { !$0.isEmpty })?.last
        TimelineView(.explicit(dates(state, shown))) { _ in
            let now = MonotonicClock.nanos()
            let fresh = state.trip?.status == .recording && shown.map { RouteMath.positionCurrent(fixNanos: $0.elapsedNanos, nowNanos: now) } == true
            ZStack(alignment: .bottom) {
                TripMapView(lines: lines ?? [], routeReady: lines != nil, mode: .live, position: shown.map { MapPosition(sample: $0, fresh: fresh) },
                            bottomInset: panelHeight)
                if let trip = state.trip {
                    panel(trip, state, now)
                        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { panelHeight = $0 + Space.s4 }
                } else {
                    Text("Starting…").padding().glassEffect(.regular, in: .capsule).padding(.bottom, Space.s8)
                }
            }
        }
        .navigationTitle("Current trip")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .toolbar {
            if state.trip != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { details = true } label: { Image(systemName: "info.circle") }
                        .accessibilityLabel(Text("Trip details"))
                        .accessibilityIdentifier("trip_details")
                }
            }
        }
        .sheet(isPresented: $details) {
            if let trip = state.trip {
                LiveDetailsSheet(trip: trip, state: state, summary: summary)
                    .presentationDetents([.medium, .large])
            }
        }
        .onAppear {
            model.recorder.showRoute(true)
            model.location.display(true)
        }
        .onDisappear {
            model.recorder.showRoute(false)
            model.location.display(false)
        }
        // Close this screen when the trip ends elsewhere, for example from the Live Activity.
        .onChange(of: state.trip?.id) { _, id in
            if id != nil { seenTrip = true } else if seenTrip { dismiss() }
        }
        .onAppear { if state.trip != nil { seenTrip = true } }
    }

    private func dates(_ state: RecordingState, _ shown: TrackSample?) -> [Date] {
        let now = MonotonicClock.nanos()
        var dates = state.changeDates(now: now)
        if let shown, shown.elapsedNanos + RouteMath.signalGapNanos > now {
            dates.append(Date.now.addingTimeInterval(Double(shown.elapsedNanos + RouteMath.signalGapNanos - now) / 1e9 + 0.01))
        }
        if let reading = currentReading, reading.elapsedNanos + RouteMath.signalGapNanos > now {
            dates.append(Date.now.addingTimeInterval(Double(reading.elapsedNanos + RouteMath.signalGapNanos - now) / 1e9 + 0.01))
        }
        return dates
    }

    /// The newest reading while the trip records. An old reading is not current, so it does not show.
    private var currentReading: GpsReading? {
        guard model.recorder.state.trip?.status == .recording, case let .fix(reading, _) = model.location.event else { return nil }
        let age = MonotonicClock.nanos() - reading.elapsedNanos
        return age >= 0 && age < RouteMath.signalGapNanos ? reading : nil
    }

    private func panel(_ trip: TripRecord, _ state: RecordingState, _ now: Int64) -> some View {
        let reading = currentReading
        return VStack(alignment: .leading, spacing: Space.s3) {
            if let error = state.error { ProblemBanner(message: error.message) }
            // As on Android: the status and the activity share the top row. The activity chip wraps below a long status.
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center) { statusChip(trip, state, now); Spacer(minLength: Space.s2); activityChip(trip) }
                VStack(alignment: .leading, spacing: Space.s2) { statusChip(trip, state, now); activityChip(trip) }
            }
            // Speed, time, and distance as three labelled values. Speed is the widest column and the largest value.
            let speed = reading?.speedMetersPerSecond
            let distance = trip.distanceMeters.map(format.distanceAmount)
            // The columns share the width 1.3 : 1 : 1, as on Android, so no value takes the space of another.
            GeometryReader { geometry in
                let unit = (geometry.size.width - 2 * Space.s3) / 3.3
                HStack(alignment: .lastTextBaseline, spacing: Space.s3) {
                    LiveValue(label: "Speed", value: speed.map(format.speedNumber), unit: format.speedUnit, large: true,
                              spoken: speed.map(format.spokenSpeed) ?? String(localized: "Current speed unavailable"))
                        .frame(width: unit * 1.3, alignment: .leading)
                        .accessibilityIdentifier("tracking_speed")
                    LiveValue(label: "Time", value: nil, unit: nil, spoken: format.duration(state.elapsedNanos(at: now))) {
                        TripTimeText(state: state)
                    }
                    .frame(width: unit, alignment: .leading)
                    .accessibilityIdentifier("live_time")
                    LiveValue(label: "Distance", value: distance.map(format.number), unit: distance?.unit)
                        .frame(width: unit, alignment: .leading)
                        .accessibilityIdentifier("live_distance")
                }
                .frame(maxHeight: .infinity, alignment: .bottom)
            }
            .frame(height: valuesHeight)
            TripActionsView(trip: trip, pause: model.trips.pause, resume: { model.trips.resume(trip.id) }, stop: { model.trips.stop(trip.id) })
        }
        .padding(Space.s4)
        .glassEffect(.regular, in: .rect(cornerRadius: 32))
        .padding(.horizontal, Space.s3)
        .padding(.bottom, Space.s2)
        .frame(maxWidth: 560)
    }
}

extension LiveTripView {
    fileprivate func statusChip(_ trip: TripRecord, _ state: RecordingState, _ now: Int64) -> some View {
        TripStatusChip(status: trip.status, noSignal: state.signalLost(at: now), approximate: state.approximate(at: now))
    }

    /// The activity icon and its short name, as on Android. VoiceOver hears the full activity text.
    fileprivate func activityChip(_ trip: TripRecord) -> some View {
        ActivityChip(text: trip.mode.title, symbol: trip.mode.symbol, trailing: "chevron.down", spoken: trip.activityText) {
            model.trips.activityChoice = .trip(trip.id)
        }
    }
}

/// A panel value: a label, then the number and its unit on one line. A long value gets smaller instead of breaking. A missing
/// value shows a dash without a unit. VoiceOver reads the label and the value as one item.
private struct LiveValue<Content: View>: View {
    var label: LocalizedStringKey
    var value: String?
    var unit: String?
    var large = false
    var spoken: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(label).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            HStack(alignment: .lastTextBaseline, spacing: Space.s1) {
                if Content.self == EmptyView.self {
                    Text(value ?? "—")
                        .font(large ? .system(size: 34, weight: .medium, design: .rounded) : .title2.weight(.medium))
                        .monospacedDigit()
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                    if value != nil, let unit { Text(unit).font(.footnote).foregroundStyle(.secondary).lineLimit(1) }
                } else {
                    content.font(.title2.weight(.medium)).monospacedDigit().lineLimit(1).minimumScaleFactor(0.5)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(Text(spoken ?? (value.map { v in unit.map { "\(v) \($0)" } ?? v } ?? String(localized: "Unavailable"))))
    }
}

extension LiveValue where Content == EmptyView {
    init(label: LocalizedStringKey, value: String?, unit: String?, large: Bool = false, spoken: String? = nil) {
        self.init(label: label, value: value, unit: unit, large: large, spoken: spoken) { EmptyView() }
    }
}

/// Values that most users need only sometimes.
private struct LiveDetailsSheet: View {
    var trip: TripRecord
    var state: RecordingState
    var summary: RouteSummary?
    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format

    var body: some View {
        let reading: GpsReading? = if case let .fix(reading, _) = model.location.event { reading } else { nil }
        let reference = model.settings.values.altitudeReference
        NavigationStack {
            List {
                Section {
                    LabeledContent("Activity") {
                        Button(trip.activityText) { model.trips.activityChoice = .trip(trip.id) }
                    }
                    TripTotalsRows(distance: trip.distanceMeters, duration: state.elapsedNanos(at: MonotonicClock.nanos()),
                                   maximum: summary?.maximumSpeedMetersPerSecond)
                }
                Section("Technical details") {
                    LabeledContent(reference == .seaLevel ? "Altitude above sea level" : "GPS height (WGS 84)",
                                   value: reading?.altitude(reference).map(format.altitude) ?? "—")
                    LabeledContent("Position accuracy", value: reading?.accuracyMeters.map { String(localized: "±\(format.decimal($0)) m") } ?? "—")
                    if trip.mode == .plane {
                        LabeledContent("Track", value: reading?.bearingRadians.map { String(localized: "\(format.decimal($0 * 180 / .pi))° true") } ?? "—")
                        LabeledContent("Altitude accuracy", value: reading?.verticalAccuracyMeters.map { String(localized: "±\(format.decimal($0)) m") } ?? "—")
                        Text("GPS speed is speed over the ground, not airspeed. GPS altitude is not the same as an aircraft altimeter.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle("Trip details")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// Distance, recording time, average speed, and maximum speed. A missing value shows as a dash.
struct TripTotalsRows: View {
    var distance: Double?
    var duration: Int64?
    var maximum: Double?
    @Environment(\.displayFormat) private var format

    var body: some View {
        LabeledContent("Distance", value: distance.map(format.distance) ?? "—")
            .accessibilityIdentifier("trip_distance")
        LabeledContent("Recording time", value: duration.map(format.duration) ?? "—")
        LabeledContent("Average speed, including stops",
                       value: duration.flatMap { averageSpeed(distanceMeters: distance, durationNanos: $0) }.map(format.speed) ?? "—")
            .accessibilityIdentifier("average_speed")
        LabeledContent("Maximum speed", value: maximum.map(format.speed) ?? "—")
    }
}
