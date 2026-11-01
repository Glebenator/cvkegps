import AppIntents
import Foundation

/// The trip commands that intents send. The app sets `TripIntentBridge.handler` at launch.
enum TripIntentAction: Sendable {
    case start, pause, resume, stop
}

/// Connects the intents to the app's trip controller. Live Activity buttons and the open-app intents run in the app process.
/// In the widget extension, the handler stays nil and the intents do nothing there.
@MainActor
enum TripIntentBridge {
    static var handler: (@MainActor (TripIntentAction) -> Void)?

    static func perform(_ action: TripIntentAction) {
        handler?(action)
    }
}

/// Opens the app and starts a trip. The app shows the recording notice first when the user has not read it,
/// and asks for location access when it is missing. It never starts a trip without the app on screen.
struct StartTripIntent: AppIntent {
    static let title: LocalizedStringResource = "Start trip"
    static let description = IntentDescription("Opens CVKE GPS and starts recording a trip.")
    static let openAppWhenRun = true

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        TripIntentBridge.perform(.start)
        return .result()
    }
}

/// Pauses the recording trip. The Live Activity shows this button.
struct PauseTripIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Pause trip"
    static let description = IntentDescription("Pauses the trip that CVKE GPS records.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        TripIntentBridge.perform(.pause)
        return .result()
    }
}

/// Resumes the paused trip. The Live Activity shows this button.
struct ResumeTripIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Resume trip"
    static let description = IntentDescription("Resumes the paused trip in CVKE GPS.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        TripIntentBridge.perform(.resume)
        return .result()
    }
}

/// Stops the trip and saves it. The Live Activity shows this button.
struct StopTripIntent: LiveActivityIntent {
    static let title: LocalizedStringResource = "Stop and save trip"
    static let description = IntentDescription("Stops the trip in CVKE GPS and saves it.")

    init() {}

    @MainActor
    func perform() async throws -> some IntentResult {
        TripIntentBridge.perform(.stop)
        return .result()
    }
}
