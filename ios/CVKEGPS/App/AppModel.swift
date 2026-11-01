import ActivityKit
import CVKEGPSCore
import Foundation
import Observation
import SwiftUI

enum AppTab: Hashable {
    case speed, trips
}

enum AppRoute: Hashable {
    case gpsDetails
    case liveTrip
    case savedTrip(String)
}

/// The app's objects, made once with constructor injection. The model also owns navigation, so a Live Activity button
/// or an intent can open a screen.
@MainActor
@Observable
final class AppModel {
    let settings: SettingsStore
    let location: LocationService
    let recorder: TripRecorder
    let library: TripLibrary
    let trips: TripController
    let speed = SpeedModel()
    /// True when the trip database could not open. Trips then stay in memory only, and the app says so.
    let storageUnavailable: Bool

    var tab: AppTab = .speed
    var speedPath: [AppRoute] = []
    var tripsPath: [AppRoute] = []
    var showSettings = false

    @ObservationIgnored private let liveActivity: LiveActivityController

    init() {
        let settings = SettingsStore()
        let location = LocationService()
        let (store, unavailable) = Self.openStore()
        let recorder = TripRecorder(store: store, source: location, motion: MotionRecorder(),
                                    options: { settings.values.recordingOptions })
        self.settings = settings
        self.location = location
        self.recorder = recorder
        storageUnavailable = unavailable
        library = TripLibrary(store: store)
        trips = TripController(recorder: recorder, location: location, settings: settings)
        liveActivity = LiveActivityController(format: { Self.format(settings.values) })
        trips.openSavedTrip = { [weak self] id in self?.open(.savedTrip(id), in: .trips) }
        trips.openLiveTrip = { [weak self] in self?.openLiveTrip() }
        TripIntentBridge.handler = { [weak self] action in self?.trips.perform(action) }
        observe()
        library.reload()
    }

    /// The database in Application Support. It is not in iCloud or device backups, as on Android.
    private static func openStore() -> (any TripStore, Bool) {
        let manager = FileManager.default
        do {
            var folder = try manager.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("Trips", isDirectory: true)
            try manager.createDirectory(at: folder, withIntermediateDirectories: true,
                                        attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
            return (try SQLiteTripStore(path: folder.appendingPathComponent("trips.sqlite").path), false)
        } catch {
            // Speed and trip recording still work. Trips are lost when the app closes.
            return ((try? SQLiteTripStore(path: nil))!, true)
        }
    }

    static func format(_ values: SettingsValues, locale: Locale = .current) -> DisplayFormat {
        DisplayFormat(locale: locale, units: values.units(country: locale.region?.identifier ?? ""), text: .app)
    }

    private func observe() {
        track({ [recorder] in recorder.state }) { [weak self] state in self?.recordingChanged(state) }
        track({ [location] in location.event }) { [weak self] event in self?.speed.update(event) }
    }

    /// Calls `apply` with the value now and after each change, on the main actor. The tracking ends with the app.
    /// Swift 6.3.3 crashes when it compiles `Observations` with these closures, so the app uses `withObservationTracking`.
    private func track<Value>(_ read: @escaping @MainActor @Sendable () -> Value,
                              _ apply: @escaping @MainActor @Sendable (Value) -> Void) {
        let value = withObservationTracking { read() } onChange: {
            Task { @MainActor [weak self] in self?.track(read, apply) }
        }
        apply(value)
    }

    private func recordingChanged(_ state: RecordingState) {
        liveActivity.update(state)
        library.reloadSoon()
        // A resumed saved trip continues on the live map after the recorder resumes it.
        if let awaited = trips.awaitedResume, !state.starting {
            trips.awaitedResume = nil
            if state.trip?.id == awaited && state.trip?.status == .recording { openLiveTrip() }
        }
    }

    /// Live Activities are on for this app in the iOS settings.
    var liveActivitiesEnabled: Bool { ActivityAuthorizationInfo().areActivitiesEnabled }

    func setForeground(_ visible: Bool) {
        location.setForeground(visible)
        if visible { library.reload() }
    }

    func open(_ route: AppRoute, in tab: AppTab) {
        self.tab = tab
        switch tab {
        case .speed: speedPath = [route]
        case .trips: tripsPath = [route]
        }
    }

    func openLiveTrip() {
        guard recorder.state.trip != nil else { return open(.liveTrip, in: .trips) }
        if tab == .speed && speedPath.last == .liveTrip { return }
        if tab == .trips && tripsPath.last == .liveTrip { return }
        if tab == .speed { speedPath.append(.liveTrip) } else { tripsPath = [.liveTrip] }
    }
}
