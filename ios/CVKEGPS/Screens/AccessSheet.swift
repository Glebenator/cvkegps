import CVKEGPSCore
import SwiftUI

/// Explains recording before the first trip and offers a recovery action for each missing access.
struct AccessSheet: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let trips = model.trips
        let location = model.location
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: Space.s4) {
                    Image(systemName: "location.north.circle.fill")
                        .font(.system(size: 48))
                        .foregroundStyle(Palette.accent)
                        .accessibilityHidden(true)
                    Text("While you record a trip, the app uses precise location, also when the screen is locked. A Live Activity on the Lock Screen and in the Dynamic Island shows Pause and Stop.")
                    Text("Trips also record the motion and air pressure sensors. iOS can ask once for Motion & Fitness access.")
                    Text("The map loads from Apple Maps. Apple receives the map area that you look at. Your trips stay on this device.")
                        .foregroundStyle(.secondary)
                    if !location.servicesEnabled {
                        recovery("Location Services are off. Turn them on in Settings, Privacy & Security.", title: "Open Settings", action: trips.openSettings)
                    } else if !location.permitted {
                        recovery("Allow location while using the app to record a trip.",
                                 title: location.authorization == .notDetermined ? "Allow location" : "Open Settings",
                                 action: trips.requestAccess, identifier: "trip_permission")
                    } else if !location.preciseAccess {
                        recovery("Turn on precise location to record a trip.", title: "Allow precise location", action: trips.requestPrecise)
                    }
                    if !model.liveActivitiesEnabled {
                        Label("Live Activities are off for this app. The trip still records, and the status bar shows that the app uses location.",
                              systemImage: "info.circle")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(Space.s4)
            }
            .navigationTitle("Record a trip")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { trips.dismissAccess() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(trips.pending.tripId == nil ? "Start trip" : "Resume trip") { trips.confirmAccess() }
                        .disabled(!trips.accessComplete)
                        .accessibilityIdentifier("confirm_start")
                }
            }
        }
        .onAppear { location.checkServices() }
    }

    private func recovery(_ message: LocalizedStringKey, title: LocalizedStringKey, action: @escaping () -> Void,
                          identifier: String = "access_action") -> some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Label { Text(message) } icon: { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.warning) }
            Button(action: action) { Text(title).frame(maxWidth: .infinity, minHeight: 30) }
                .prominentButton()
                .controlSize(.large)
                .accessibilityIdentifier(identifier)
        }
        .padding(Space.s3)
        .background(Palette.warningSubtle, in: .rect(cornerRadius: 16))
    }
}
