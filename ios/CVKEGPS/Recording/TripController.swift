import CVKEGPSCore
import Foundation
import Observation
import UIKit

/// A start or resume that waits for access. A nil `tripId` starts a new trip.
/// `reopen` continues a trip that Stop and save ended a moment ago. The recorder checks the time limit.
struct PendingStart: Equatable {
    var tripId: String?
    var reopen = false
}

/// The trip whose activity the user chooses, or the next trip.
enum ActivityChoice: Identifiable, Equatable {
    case next
    case trip(String)

    var id: String {
        switch self {
        case .next: "next"
        case let .trip(id): id
        }
    }
}

/// Recording commands and access checks. A start or resume first checks access. The recording notice shows before the
/// first trip, and again when access is missing. The app remembers that the user read it.
@MainActor
@Observable
final class TripController {
    /// True while the recording notice shows.
    var accessShown = false
    /// The command that runs when access is complete.
    private(set) var pending = PendingStart()
    /// True after the first permission request. iOS then does not ask again, so the notice offers the Settings app.
    private(set) var asked = false
    /// The trip that Stop and save ended in this session. The saved trip offers Resume for a short time.
    var justSaved: String?
    /// The activity that the user chose for the next trip. Nil means automatic.
    var selectedMode: TripMode?
    /// The activity choice that shows.
    var activityChoice: ActivityChoice?
    /// The trip that the user asked to delete. A confirmation shows.
    var deleteCandidate: String?
    /// A trip that the user resumed from its saved screen. The live map opens when the recorder resumes it.
    var awaitedResume: String?

    @ObservationIgnored private let recorder: TripRecorder
    @ObservationIgnored private let location: LocationService
    @ObservationIgnored private let settings: SettingsStore
    @ObservationIgnored var openSavedTrip: @MainActor (String) -> Void = { _ in }
    @ObservationIgnored var openLiveTrip: @MainActor () -> Void = {}

    init(recorder: TripRecorder, location: LocationService, settings: SettingsStore) {
        self.recorder = recorder
        self.location = location
        self.settings = settings
    }

    /// Precise location, granted, with Location Services on.
    var accessComplete: Bool { location.permitted && location.preciseAccess && location.servicesEnabled }

    func start() {
        pending = PendingStart()
        if !settings.values.tripAccessExplained { accessShown = true } else { send(PendingStart()) }
    }

    func pause() { recorder.send(.pause) }

    func resume(_ id: String, fromSavedTrip: Bool = false) {
        if fromSavedTrip { awaitedResume = id }
        send(PendingStart(tripId: id))
    }

    func stop(_ id: String) {
        recorder.send(.finish(id: id))
        justSaved = id
        openSavedTrip(id)
    }

    /// Continues a trip that Stop and save ended a moment ago.
    func reopen(_ id: String) {
        justSaved = nil
        awaitedResume = id
        send(PendingStart(tripId: id, reopen: true))
    }

    /// The user read the notice. Start or resume the trip.
    func confirmAccess() {
        settings.set(\.tripAccessExplained, true)
        send(pending)
    }

    func dismissAccess() {
        accessShown = false
        pending = PendingStart()
    }

    func requestAccess() {
        asked = true
        if location.authorization == .notDetermined { location.requestAccess() } else { openSettings() }
    }

    func requestPrecise() {
        Task { await location.requestPrecise() }
    }

    func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// The command from an intent: the Live Activity, Siri, Shortcuts, or Control Center.
    func perform(_ action: TripIntentAction) {
        let trip = recorder.state.trip
        switch action {
        case .start: if trip == nil { start() } else { openLiveTrip() }
        case .pause: pause()
        case .resume: if let trip, trip.status == .paused { send(PendingStart(tripId: trip.id)) }
        case .stop: if let trip { stop(trip.id) }
        }
    }

    func chooseActivity(_ mode: TripMode?, for choice: ActivityChoice) {
        switch choice {
        case .next: selectedMode = mode
        case let .trip(id): recorder.send(.setMode(id: id, mode: mode ?? .unknown, automatic: mode == nil))
        }
    }

    private func send(_ command: PendingStart) {
        guard accessComplete else {
            pending = command
            accessShown = true
            return
        }
        accessShown = false
        pending = PendingStart()
        if let id = command.tripId {
            recorder.send(.resume(id: id, reopen: command.reopen))
        } else {
            let mode = selectedMode ?? location.activityEstimate.fresh(at: MonotonicClock.nanos())
            recorder.send(.start(mode: mode, automatic: selectedMode == nil))
        }
    }
}
