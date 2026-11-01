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
        TabView(selection: $model.tab) {
            Tab("Speed", systemImage: "gauge.with.needle", value: AppTab.speed) {
                NavigationStack(path: $model.speedPath) {
                    SpeedView().navigationDestination(for: AppRoute.self, destination: destination)
                }
            }
            Tab("Trips", systemImage: "point.topleft.down.to.point.bottomright.curvepath", value: AppTab.trips) {
                NavigationStack(path: $model.tripsPath) {
                    TripsView().navigationDestination(for: AppRoute.self, destination: destination)
                }
            }
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(RecordingAccessory(active: model.recorder.state.active))
        .environment(\.displayFormat, format)
        .sheet(isPresented: $model.showSettings) { SettingsView().environment(\.displayFormat, format) }
        .sheet(isPresented: Binding(get: { model.trips.accessShown }, set: { if !$0 { model.trips.dismissAccess() } })) {
            AccessSheet().presentationDetents([.medium, .large])
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

/// The recording bar above the tab bar. iOS 26.1 and later keep the tab view and only hide the accessory without a trip.
private struct RecordingAccessory: ViewModifier {
    var active: Bool

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: active) { RecordingBar() }
        } else if active {
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
                HStack(spacing: Space.s3) {
                    Button { model.openLiveTrip() } label: {
                        HStack(spacing: Space.s2) {
                            Image(systemName: trip.status == .recording ? "record.circle.fill" : "pause.circle.fill")
                                .foregroundStyle(trip.status == .recording ? Palette.danger : Palette.inkMuted)
                            VStack(alignment: .leading, spacing: 0) {
                                if placement != .inline {
                                    Text(noSignal && trip.status == .recording
                                         ? (state.approximate(at: now) ? String(localized: "No GPS: approximate position") : String(localized: "No GPS signal"))
                                         : trip.status.title)
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
                    .accessibilityLabel(Text("Open current trip"))
                    .accessibilityIdentifier("recording_bar")
                    if trip.status == .recording {
                        Button(action: model.trips.pause) { Image(systemName: "pause.fill") }
                            .accessibilityLabel(Text("Pause trip"))
                            .accessibilityIdentifier("bar_pause")
                    } else {
                        Button { model.trips.resume(trip.id) } label: { Image(systemName: "play.fill") }
                            .accessibilityLabel(Text("Resume trip"))
                            .accessibilityIdentifier("bar_resume")
                    }
                    if placement != .inline {
                        Button { model.trips.stop(trip.id) } label: { Image(systemName: "stop.fill") }
                            .accessibilityLabel(Text("Stop and save"))
                            .accessibilityIdentifier("bar_stop")
                    }
                }
                .padding(.horizontal, Space.s4)
                .font(.body)
            }
        } else if state.starting {
            Text("Starting…").font(.subheadline)
        }
    }
}
