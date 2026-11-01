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

    private var metrics: some View {
        let reference = model.settings.values.altitudeReference
        let altitude = model.speed.reading?.altitude(reference)
        // At the largest text sizes, each card needs the full width for its value and unit.
        let layout = typeSize.isAccessibilitySize ? AnyLayout(VStackLayout(spacing: Space.s3)) : AnyLayout(HStackLayout(spacing: Space.s3))
        return layout {
            MetricCard(title: "Altitude", value: altitude.map(format.altitudeNumber), unit: format.altitudeUnit,
                       subtitle: reference == .seaLevel ? "Above sea level" : "GPS height", identifier: "altitude")
            MetricCard(title: "Maximum speed", value: model.speed.maximum.map(format.speedNumber), unit: format.speedUnit,
                       subtitle: "Since app launch", identifier: "maximum_speed")
        }
        .fixedSize(horizontal: false, vertical: true)
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
        if state.trip == nil && model.speed.canStartTrip && model.trips.accessComplete {
            VStack(spacing: Space.s2) {
                Button { model.trips.start() } label: {
                    Label(state.starting ? "Starting…" : "Start trip", systemImage: "record.circle")
                        .frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .disabled(state.starting)
                .accessibilityIdentifier("trip_start")
                ActivityChip(text: activityText) { model.trips.activityChoice = .next }
            }
        }
    }

    private var activityText: String {
        if let selected = model.trips.selectedMode { return String(localized: "Activity: \(selected.title)") }
        let estimate = model.location.activityEstimate.fresh(at: MonotonicClock.nanos())
        if estimate != .unknown { return String(localized: "Activity: \(estimate.title) (estimate)") }
        return String(localized: "Activity: automatic")
    }
}

/// The large speed in a glass circle. A smaller dial omits the label. A dash shows a missing speed at a normal size,
/// so it does not look like a bar.
struct SpeedDial: View {
    var speed: Double?
    @Environment(\.displayFormat) private var format

    var body: some View {
        GeometryReader { geometry in
            let diameter = min(geometry.size.width, geometry.size.height, 320)
            VStack(spacing: Space.s1) {
                if diameter >= 240 {
                    Text("Current speed").font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                }
                if let speed {
                    Text(format.speedNumber(speed))
                        .font(.system(size: 104, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .minimumScaleFactor(0.2)
                        .lineLimit(1)
                } else {
                    Text("—").font(.system(size: 56, weight: .medium, design: .rounded)).foregroundStyle(.secondary)
                }
                Text(format.speedUnit).font(.title3).foregroundStyle(.secondary)
            }
            .padding(diameter * (diameter >= 240 ? 0.14 : 0.1))
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

/// A value card: a label, a value with its unit on one line, and what the value means.
struct MetricCard: View {
    var title: LocalizedStringKey
    var value: String?
    var unit: String
    var subtitle: LocalizedStringKey
    var identifier: String

    var body: some View {
        GlassCard(cornerRadius: 22, padding: Space.s3) {
            VStack(alignment: .leading, spacing: Space.s1) {
                Text(title).font(.caption.weight(.medium)).foregroundStyle(.secondary)
                HStack(alignment: .firstTextBaseline, spacing: Space.s1) {
                    Text(value ?? "—")
                        .font(.title.weight(.medium).monospacedDigit())
                        .lineLimit(1)
                        .minimumScaleFactor(0.5)
                        .accessibilityIdentifier(identifier)
                    if value != nil { Text(unit).font(.footnote).foregroundStyle(.secondary) }
                }
                Text(subtitle).font(.caption).foregroundStyle(.secondary)
            }
        }
        .accessibilityElement(children: .combine)
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
                .buttonStyle(.glassProminent)
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
