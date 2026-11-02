import CVKEGPSCore
import SwiftUI

/// Two tabs, Speed and Trips, in the Liquid Glass tab bar. While a trip records or is paused, a recording accessory above
/// the tab bar shows the state and the time, with Pause or Resume and Stop. Selecting it opens the current trip.
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.locale) private var locale
    @State private var screen = ScreenAwake()

    var body: some View {
        @Bindable var model = model
        let format = AppModel.format(model.settings.values, locale: locale)
        // The tab bar shows icons only, as on Android and in the owner's design language. VoiceOver reads each tab name.
        TabView(selection: $model.tab) {
            Tab(value: AppTab.speed) {
                NavigationStack(path: $model.speedPath) {
                    SpeedView().navigationDestination(for: AppRoute.self, destination: destination)
                }
            } label: {
                TabIcon(name: "Speed", symbol: "gauge.with.needle")
            }
            Tab(value: AppTab.trips) {
                NavigationStack(path: $model.tripsPath) {
                    TripsView().navigationDestination(for: AppRoute.self, destination: destination)
                }
            } label: {
                TabIcon(name: "Trips", symbol: "point.topleft.down.to.point.bottomright.curvepath")
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(TripAccessory(enabled: accessoryShown, keepForOldSystem: model.recorder.state.active))
        .environment(\.displayFormat, format)
        .sheet(isPresented: $model.showSettings) { SettingsView().environment(\.displayFormat, format) }
        .sheet(isPresented: Binding(get: { model.trips.accessShown }, set: { if !$0 { model.trips.dismissAccess() } })) {
            // An opaque background: the sheet is mostly text, and glass lets the screen behind show through it.
            AccessSheet().presentationDetents([.medium, .large]).presentationBackground(Color(uiColor: .systemBackground))
        }
        .confirmationDialog("Change activity", isPresented: Binding(get: { model.trips.activityChoice != nil },
                                                                    set: { if !$0 { model.trips.activityChoice = nil } }),
                            titleVisibility: .visible, presenting: model.trips.activityChoice) { choice in
            Button("Automatic") { model.trips.chooseActivity(nil, for: choice) }
            ForEach(TripMode.choices, id: \.self) { mode in
                Button(mode.title) { model.trips.chooseActivity(mode, for: choice) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("The app estimates the activity from your GPS speed. Some activities have similar speeds, for example car and transit. Select the correct activity if necessary.")
        }
        .confirmationDialog("Delete this trip?", isPresented: Binding(get: { model.trips.deleteCandidate != nil },
                                                                      set: { if !$0 { model.trips.deleteCandidate = nil } }),
                            titleVisibility: .visible, presenting: model.trips.deleteCandidate) { id in
            Button("Delete trip", role: .destructive) {
                model.tripsPath.removeAll { $0 == .savedTrip(id) }
                model.speedPath.removeAll { $0 == .savedTrip(id) }
                Task { await model.library.delete(id) }
            }
            Button("Cancel", role: .cancel) {}
        } message: { _ in
            Text("This removes the saved route and its measurements from this device.")
        }
        .background(TouchObserver { screen.touched() })
        .onChange(of: scenePhase, initial: true) { _, phase in model.setForeground(phase == .active) }
        .onChange(of: keepOn, initial: true) { _, _ in updateScreen() }
        .onChange(of: scenePhase) { _, _ in updateScreen() }
        .onChange(of: model.settings.values.dimScreen) { _, _ in updateScreen() }
    }

    @ViewBuilder private func destination(_ route: AppRoute) -> some View {
        switch route {
        case .gpsDetails: GpsDetailsView()
        case .liveTrip: LiveTripView()
        case let .savedTrip(id): SavedTripView(id: id).id(id)
        }
    }

    /// The bar above the tabs: Start trip before a trip, and the recording bar during a trip. Start trip shows only when
    /// a trip can start; when access is missing, the GPS card on the Speed tab gives the recovery action.
    /// As on Android, the bar shows only with the tab bar: on the tab roots and on GPS details. The map screens hide both.
    private var accessoryShown: Bool {
        let path = model.tab == .speed ? model.speedPath : model.tripsPath
        guard path.last == nil || path.last == .gpsDetails else { return false }
        let state = model.recorder.state
        if state.active { return true }
        return state.trip == nil && model.speed.canStartTrip && model.trips.accessComplete
    }

    /// The screen stays on only where and when the user chose it: the Speed tab and the current trip map.
    private var keepOn: Bool {
        let settings = model.settings.values
        let recording = model.recorder.state.trip?.status == .recording
        let when = settings.screenOn == .always || (settings.screenOn == .duringTrip && recording)
        guard when else { return false }
        let path = model.tab == .speed ? model.speedPath : model.tripsPath
        if model.tab == .speed && path.isEmpty { return settings.screenOnSpeed }
        if path.last == .liveTrip { return settings.screenOnTripMap }
        return false
    }

    private func updateScreen() {
        screen.update(keepOn: keepOn, dim: model.settings.values.dimScreen, active: scenePhase == .active)
    }
}

/// The bar above the tab bar, on both tabs: Start trip and the activity before a trip, the recording bar during a trip.
/// iOS 26.1 and later keep the tab view and only hide the bar. iOS 26.0 must make the tab view again to remove it, so there
/// the recording bar stays on the current trip screen.
private struct TripAccessory: ViewModifier {
    var enabled: Bool
    /// True during a trip. On iOS 26.0 the recording bar then stays, also on the current trip screen.
    var keepForOldSystem: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: enabled) { RecordingBar() }
        } else if enabled || keepForOldSystem {
            content.tabViewBottomAccessory { RecordingBar() }
        } else {
            content
        }
    }
}

private struct RecordingBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement
    @Environment(\.displayFormat) private var format

    var body: some View {
        let state = model.recorder.state
        if let trip = state.trip {
            TimelineView(.explicit(state.changeDates())) { _ in
                let now = MonotonicClock.nanos()
                let noSignal = state.signalLost(at: now)
                let status = noSignal && trip.status == .recording
                    ? (state.approximate(at: now) ? String(localized: "No GPS: approximate position") : String(localized: "No GPS signal"))
                    : trip.status.title
                HStack(spacing: Space.s2) {
                    // VoiceOver reads the state, the time, and the distance, then the hint. The time is live text,
                    // so it is current when VoiceOver reads it.
                    Button { model.openLiveTrip() } label: {
                        HStack(spacing: Space.s2) {
                            Image(systemName: trip.status == .recording ? "record.circle.fill" : "pause.circle.fill")
                                .foregroundStyle(trip.status == .recording ? Palette.danger : Palette.inkMuted)
                                // The small bar shows no state text, so the icon gives VoiceOver the state.
                                .accessibilityLabel(Text(status))
                                .accessibilityHidden(placement != .inline)
                            VStack(alignment: .leading, spacing: 0) {
                                if placement != .inline {
                                    Text(status)
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(noSignal && trip.status == .recording ? Palette.danger : .secondary)
                                }
                                HStack(spacing: Space.s1) {
                                    TripTimeText(state: state)
                                    if placement != .inline, let distance = trip.distanceMeters {
                                        Text("· \(format.distance(distance))")
                                    }
                                }
                                .font(.subheadline.weight(.medium))
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityHint(Text("Opens the current trip"))
                    .accessibilityIdentifier("recording_bar")
                    if trip.status == .recording {
                        Button(action: model.trips.pause) { BarIcon(symbol: "pause.fill", filled: placement != .inline) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Pause trip"))
                            .accessibilityIdentifier("bar_pause")
                    } else {
                        Button { model.trips.resume(trip.id) } label: { BarIcon(symbol: "play.fill", filled: placement != .inline) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Resume trip"))
                            .accessibilityIdentifier("bar_resume")
                    }
                    if placement != .inline {
                        Button { model.trips.stop(trip.id) } label: { BarIcon(symbol: "stop.fill", filled: true) }
                            .buttonStyle(.plain)
                            .accessibilityLabel(Text("Stop and save"))
                            .accessibilityIdentifier("bar_stop")
                    }
                }
                .padding(.leading, Space.s4)
                .padding(.trailing, Space.s1)
                .font(.body)
            }
        } else if state.starting {
            Text("Starting…").font(.subheadline)
        } else {
            StartBar()
        }
    }
}

/// Start trip and the activity of the next trip, in the bar above the tabs. The small bar beside a minimised tab bar
/// shows only Start trip. The text stops growing at the largest standard size, so the bar keeps its height.
private struct StartBar: View {
    @Environment(AppModel.self) private var model
    @Environment(\.tabViewBottomAccessoryPlacement) private var placement

    var body: some View {
        HStack(spacing: Space.s2) {
            Button { model.trips.start() } label: {
                Label("Start trip", systemImage: "record.circle")
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                    .padding(.horizontal, Space.s4)
                    .frame(minHeight: 44)
                    .foregroundStyle(.white)
                    .background(Palette.accentFill, in: .capsule)
                    .contentShape(.capsule)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("trip_start")
            if placement != .inline {
                Spacer(minLength: Space.s2)
                // As on Android: the activity icon and a short name. The icon shows the chosen activity or the estimate.
                // VoiceOver hears the full choice.
                Button { model.trips.activityChoice = .next } label: {
                    HStack(spacing: Space.s2) {
                        Image(systemName: (selected ?? estimate).symbol).foregroundStyle(.primary)
                        Text(selected?.title ?? String(localized: "Automatic")).lineLimit(1)
                        Image(systemName: "chevron.down").font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
                    }
                    .font(.subheadline.weight(.medium))
                    .frame(minHeight: 44)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text(TripMode.spokenChoice(selected: selected, estimate: estimate)))
                .accessibilityHint(Text("Changes the activity of the next trip"))
                .accessibilityIdentifier("activity_chip")
            }
        }
        .padding(.leading, Space.s1)
        .padding(.trailing, Space.s4)
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }

    private var selected: TripMode? { model.trips.selectedMode }
    private var estimate: TripMode { model.location.activityEstimate.fresh(at: MonotonicClock.nanos()) }
}

/// A tab bar icon without visible text. The name is for VoiceOver and Voice Control.
private struct TabIcon: View {
    var name: LocalizedStringKey
    var symbol: String

    var body: some View {
        Label { Text(name) } icon: { Image(systemName: symbol) }
            .labelStyle(.iconOnly)
    }
}

/// A button symbol in the recording bar. The touch target is 44 points. In the full bar, a light circle shows the target.
/// The small bar beside a minimised tab bar has no room for the circle, but it keeps the target.
private struct BarIcon: View {
    var symbol: String
    var filled: Bool

    var body: some View {
        Image(systemName: symbol)
            .foregroundStyle(Palette.accent)
            .frame(width: 44, height: 44)
            .background { if filled { Circle().fill(.fill.tertiary) } }
            .contentShape(.circle)
    }
}
