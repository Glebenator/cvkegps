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
            HStack(alignment: .center) {
                TripStatusChip(status: trip.status, noSignal: state.signalLost(at: now), approximate: state.approximate(at: now))
                Spacer()
                ActivityChip(text: trip.activityText) { model.trips.activityChoice = .trip(trip.id) }
            }
            HStack(alignment: .lastTextBaseline) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Current speed").font(.caption).foregroundStyle(.secondary)
                    HStack(alignment: .lastTextBaseline, spacing: Space.s1) {
                        Text(reading?.speedMetersPerSecond.map(format.speedNumber) ?? "—")
                            .font(.system(size: 44, weight: .medium, design: .rounded).monospacedDigit())
                            .lineLimit(1)
                            .minimumScaleFactor(0.5)
                        // A dash with a unit reads like a value. The unit shows only with a speed.
                        if reading?.speedMetersPerSecond != nil { Text(format.speedUnit).font(.headline).foregroundStyle(.secondary) }
                    }
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(reading?.speedMetersPerSecond.map(format.spokenSpeed) ?? String(localized: "Current speed unavailable"))
                .accessibilityIdentifier("tracking_speed")
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    TripTimeText(state: state).font(.title3.weight(.medium))
                    Text(trip.distanceMeters.map(format.distance) ?? "—")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("live_distance")
                }
            }
            TripActionsView(trip: trip, pause: model.trips.pause, resume: { model.trips.resume(trip.id) }, stop: { model.trips.stop(trip.id) })
        }
        .padding(Space.s4)
        .glassEffect(.regular, in: .rect(cornerRadius: 32))
        .padding(.horizontal, Space.s3)
        .padding(.bottom, Space.s2)
        .frame(maxWidth: 560)
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
