import AppIntents
import SwiftUI

@main
struct CVKEGPSApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .tint(Palette.accent)
        }
    }
}

/// Siri, Spotlight, and the Shortcuts app can start, pause, resume, and stop a trip. A start opens the app.
struct CVKEShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartTripIntent(), phrases: ["Start a trip in \(.applicationName)", "Record a trip with \(.applicationName)"],
                    shortTitle: "Start trip", systemImageName: "record.circle")
        AppShortcut(intent: PauseTripIntent(), phrases: ["Pause the trip in \(.applicationName)"],
                    shortTitle: "Pause trip", systemImageName: "pause.circle")
        AppShortcut(intent: ResumeTripIntent(), phrases: ["Resume the trip in \(.applicationName)"],
                    shortTitle: "Resume trip", systemImageName: "play.circle")
        AppShortcut(intent: StopTripIntent(), phrases: ["Stop the trip in \(.applicationName)"],
                    shortTitle: "Stop and save", systemImageName: "stop.circle")
    }
}
