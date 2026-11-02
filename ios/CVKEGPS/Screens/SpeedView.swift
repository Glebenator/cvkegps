import CVKEGPSCore
import SwiftUI

/// The Speed tab: a large speed, the altitude, the maximum speed, the GPS state, and Start trip.
/// iOS reports no satellites, so the dial has no satellite ring (see the iOS notes in README.md).
struct SpeedView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        GeometryReader { geometry in
            let wide = geometry.size.width > geometry.size.height
            Group {
                if wide {
                    HStack(spacing: Space.s6) {
                        SpeedDial(speed: model.speed.reading?.speedMetersPerSecond)
                        ScrollView {
                            VStack(spacing: Space.s3) {
                                metrics
                                gpsCard
                                tripControls
                            }
                            .padding(.vertical, Space.s4)
                        }
                        .scrollBounceBehavior(.basedOnSize)
                        .frame(maxWidth: 420)
                    }
                } else if typeSize.isAccessibilitySize {
                    // The largest text sizes need more height than the screen has. The values then scroll under a smaller dial.
                    ScrollView {
                        VStack(spacing: Space.s3) {
                            SpeedDial(speed: model.speed.reading?.speedMetersPerSecond).frame(height: 240)
                            metrics
                            gpsCard
                            tripControls
                        }
                    }
                } else {
                    VStack(spacing: Space.s3) {
                        SpeedDial(speed: model.speed.reading?.speedMetersPerSecond).frame(maxHeight: .infinity)
                        metrics
                        gpsCard
                        tripControls
                    }
                    .frame(maxWidth: 480)
                    .frame(maxWidth: .infinity)
                }
            }
            .padding(.horizontal, Space.s4)
            .padding(.bottom, Space.s2)
        }
        .background { BrandBackdrop() }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.showSettings = true } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel(Text("Settings"))
                    .accessibilityIdentifier("open_settings")
            }
        }
        .toolbarTitleDisplayMode(.inline)
        .onAppear { model.location.display(true) }
        .onDisappear { model.location.display(false) }
    }

    /// Two readings as plain text on the backdrop, with a thin line between them. Glass is only for controls.
    /// During a trip, as on Android, the trip distance and the average speed take the place of altitude and maximum speed.
    /// The recording bar shows the time.
    @ViewBuilder private var metrics: some View {
        let state = model.recorder.state
        if let trip = state.trip {
            // The average changes with time also without a new fix, so it updates each second during a trip.
            TimelineView(.periodic(from: .now, by: 1)) { _ in
                let distance = trip.distanceMeters.map(format.distanceAmount)
                let average = averageSpeed(distanceMeters: trip.distanceMeters, durationNanos: state.elapsedNanos(at: MonotonicClock.nanos()))
                readings(Reading(label: "Distance of this trip", value: distance.map(format.number), unit: distance?.unit ?? "",
                                 identifier: "trip_card_distance"),
                         Reading(label: "Average speed, including stops", value: average.map(format.speedNumber), unit: format.speedUnit,
                                 identifier: "trip_card_average"))
            }
        } else {
            let reference = model.settings.values.altitudeReference
            let altitude = model.speed.reading?.altitude(reference)
            readings(Reading(label: reference == .seaLevel ? "Altitude above sea level" : "GPS height",
                             value: altitude.map(format.altitudeNumber), unit: format.altitudeUnit, identifier: "altitude"),
                     Reading(label: "Maximum speed since app launch", value: model.speed.maximum.map(format.speedNumber),
                             unit: format.speedUnit, identifier: "maximum_speed"))
        }
    }

    private func readings(_ first: Reading, _ second: Reading) -> some View {
        let stacked = typeSize.isAccessibilitySize
        // At the largest text sizes, each reading needs the full width for its value and unit.
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.s3))
            : AnyLayout(HStackLayout(alignment: .top, spacing: Space.s4))
        return layout {
            first
            if stacked { Divider() } else { Divider().frame(maxHeight: .infinity) }
            second
        }
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, Space.s2)
    }

    private var gpsCard: some View {
        GpsCard(speed: model.speed, location: model.location, trips: model.trips)
    }

    @ViewBuilder private var tripControls: some View {
        let state = model.recorder.state
        if let error = state.error { ProblemBanner(message: error.message) }
        if model.storageUnavailable {
            ProblemBanner(message: String(localized: "The app cannot open its trip storage. Trips that you record now are lost when the app closes."))
        }
        // Start trip and the activity of the next trip are in the bar above the tabs (RootView), on both tabs.
    }
}

/// The large speed in a glass circle. As on Android, the dial has no title: the tab and the unit say that the number is the
/// speed, so the digits can be larger. VoiceOver still hears "Current speed". A dash shows a missing speed at a normal size,
/// so it does not look like a bar, and without a unit, so it does not look like a value.
struct SpeedDial: View {
    var speed: Double?
    @Environment(\.displayFormat) private var format

    var body: some View {
        GeometryReader { geometry in
            let diameter = min(geometry.size.width, geometry.size.height, 320)
            VStack(spacing: Space.s1) {
                if let speed {
                    Text(format.speedNumber(speed))
                        .font(.system(size: 128, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .minimumScaleFactor(0.2)
                        .lineLimit(1)
                    Text(format.speedUnit).font(.title3).foregroundStyle(.secondary)
                        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                } else {
                    Text("—").font(.system(size: 56, weight: .medium, design: .rounded)).foregroundStyle(.secondary)
                }
            }
            .padding(diameter * (diameter >= 240 ? 0.1 : 0.08))
            .frame(width: diameter, height: diameter)
            .glassEffect(.regular, in: .circle)
            .position(x: geometry.size.width / 2, y: geometry.size.height / 2)
        }
        .frame(minHeight: 120)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(speed.map(format.spokenSpeed) ?? String(localized: "Current speed unavailable"))
        .accessibilityIdentifier("current_speed")
    }
}

/// A reading without a card: the value with its unit on one line, and below it what the value means.
/// A missing value shows a dash without a unit, and VoiceOver reads it as unavailable.
struct Reading: View {
    var label: LocalizedStringKey
    var value: String?
    var unit: String
    var identifier: String

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s1) {
            HStack(alignment: .firstTextBaseline, spacing: Space.s1) {
                Text(value ?? "—")
                    .font(.title.weight(.medium).monospacedDigit())
                    .foregroundStyle(value == nil ? .secondary : .primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                if value != nil { Text(unit).font(.callout.weight(.medium)).foregroundStyle(.secondary) }
            }
            Text(label).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(label))
        .accessibilityValue(value.map { Text(verbatim: "\($0) \(unit)") } ?? Text("Unavailable"))
        .accessibilityIdentifier(identifier)
    }
}

/// The GPS state. The whole card opens GPS details. A fresh position of 5 m or better reduces the card to its bar.
struct GpsCard: View {
    var speed: SpeedModel
    var location: LocationService
    var trips: TripController

    var body: some View {
        VStack(spacing: Space.s3) {
            NavigationLink(value: AppRoute.gpsDetails) {
                if speed.ready {
                    HStack(spacing: Space.s2) {
                        AccuracyBar(accuracyMeters: speed.reading?.accuracyMeters, showsLabel: false)
                        Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, Space.s4)
                    .frame(minHeight: Size.touch)
                    .glassEffect(.regular.interactive(), in: .capsule)
                    .accessibilityIdentifier("gps_compact")
                } else {
                    GlassCard {
                        VStack(alignment: .leading, spacing: Space.s2) {
                            HStack(spacing: Space.s2) {
                                StatusDot(color: color)
                                Text(title).font(.headline).accessibilityIdentifier("gps_status")
                                Spacer()
                                Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                            }
                            if speed.status == .live {
                                AccuracyBar(accuracyMeters: speed.reading?.accuracyMeters)
                            } else {
                                Text(message).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                            }
                        }
                    }
                }
            }
            .buttonStyle(.plain)
            .accessibilityHint(Text("Opens GPS details"))
            .accessibilityIdentifier("gps_card")
            if let action {
                Button(action: action.run) {
                    Text(action.title).frame(maxWidth: .infinity, minHeight: 30)
                }
                .prominentButton()
                .controlSize(.large)
                .accessibilityIdentifier("gps_action")
            }
        }
    }

    /// Live is success. A state that needs time or an action is a warning. A failure is danger. Paused is neutral.
    private var color: Color {
        switch speed.status {
        case .live: Palette.success
        case .searching, .stale, .permissionRequired, .disabled, .reducedAccuracy: Palette.warning
        case .unavailable, .error: Palette.danger
        case .paused: Palette.inkMuted
        }
    }

    private var title: LocalizedStringKey {
        switch speed.status {
        case .permissionRequired: "Location access"
        case .reducedAccuracy: "Precise location is off"
        case .disabled: "Location Services are off"
        case .unavailable: "GPS is not available"
        case .searching: "Finding GPS signal"
        case .live: "GPS connected"
        case .stale: "GPS signal lost"
        case .paused: "GPS paused"
        case .error: "GPS could not start"
        }
    }

    private var message: LocalizedStringKey {
        switch speed.status {
        case .permissionRequired: "Use precise location for speed and altitude."
        case .reducedAccuracy: "Speed needs precise location. Approximate location has no speed."
        case .disabled: "Turn on Location Services in Settings, Privacy & Security."
        case .unavailable: "This device has no available location receiver."
        case .searching: "Move to an open area for a GPS signal."
        case .live: "Location stops when the app is not on screen unless a trip records."
        case .stale: "No current reading. Wait for a new GPS signal."
        case .paused: "Trip recording is paused. Resume the trip for new readings."
        case .error: "Try again to start location updates."
        }
    }

    private struct Action {
        var title: LocalizedStringKey
        var run: () -> Void
    }

    private var action: Action? {
        switch speed.status {
        case .permissionRequired:
            if location.authorization == .notDetermined { return Action(title: "Allow precise location", run: trips.requestAccess) }
            return Action(title: "Open Settings", run: trips.openSettings)
        case .reducedAccuracy: return Action(title: "Allow precise location", run: trips.requestPrecise)
        case .disabled: return Action(title: "Open Settings", run: trips.openSettings)
        default: return nil
        }
    }
}
