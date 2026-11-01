import CVKEGPSCore
import SwiftUI

/// One page with sections for units, altitude, trip recording, maps, the screen, and the app version.
/// Each change saves at once, as iOS settings do.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let store = model.settings
        let values = store.values
        let region = Units.forCountry(Locale.current.region?.identifier ?? "")
        NavigationStack {
            Form {
                Section {
                    Picker("Speed", selection: binding(\.speedUnit, store)) {
                        Text("Region (\(DisplayText.app.speedUnit(region.speed)))").tag(SpeedUnit?.none)
                        ForEach(SpeedUnit.allCases, id: \.self) { Text(DisplayText.app.speedUnit($0)).tag(SpeedUnit?.some($0)) }
                    }
                    Picker("Distance", selection: binding(\.distanceUnit, store)) {
                        Text("Region (\(DisplayText.app.distanceUnit(region.distance)))").tag(DistanceUnit?.none)
                        ForEach(DistanceUnit.allCases, id: \.self) { Text(DisplayText.app.distanceUnit($0)).tag(DistanceUnit?.some($0)) }
                    }
                    Picker("Altitude", selection: binding(\.altitudeUnit, store)) {
                        Text("Region (\(DisplayText.app.altitudeUnit(region.altitude)))").tag(AltitudeUnit?.none)
                        ForEach(AltitudeUnit.allCases, id: \.self) { Text(DisplayText.app.altitudeUnit($0)).tag(AltitudeUnit?.some($0)) }
                    }
                } header: {
                    Text("Units")
                } footer: {
                    Text("Accuracy values and GPS details stay in metres.")
                }

                Section {
                    Picker("Altitude reference", selection: Binding(get: { values.altitudeReference }, set: { store.set(\.altitudeReference, $0) })) {
                        Text("Above sea level").tag(AltitudeReference.seaLevel)
                        Text("GPS height").tag(AltitudeReference.ellipsoid)
                    }
                    .pickerStyle(.inline)
                    .labelsHidden()
                } header: {
                    Text("Altitude reference")
                } footer: {
                    Text(values.altitudeReference == .seaLevel
                         ? "Height above mean sea level, as Core Location reports it."
                         : "The height that the GPS receiver reports, above the WGS 84 ellipsoid. It can differ from sea level by up to 100 m.")
                }

                Section {
                    Picker("Position updates", selection: Binding(get: { values.recordingAccuracy }, set: { store.set(\.recordingAccuracy, $0) })) {
                        Text("Standard").tag(RecordingAccuracy.standard)
                        Text("High accuracy").tag(RecordingAccuracy.high)
                    }
                    Toggle("Use Wi-Fi and mobile networks without GPS", isOn: binding(\.networkFallback, store))
                } header: {
                    Text("Trip recording")
                } footer: {
                    VStack(alignment: .leading, spacing: Space.s2) {
                        Text(values.recordingAccuracy == .standard
                             ? "The best accuracy for about one position each second. Uses less battery."
                             : "The best accuracy for navigation, with more sensor data in each position. Uses more battery. iOS gives about one position each second.")
                        Text("When a trip has no GPS signal, it records the approximate position that iOS finds from Wi-Fi and mobile networks. The map shows it as a dashed line.")
                        Text("Trips also record the motion sensors and the air pressure sensor of the phone. This data stays on this device.")
                        Text("Changes apply to the next trip that you start or resume.")
                    }
                }

                Section {
                    Picker("Map type", selection: Binding(get: { values.mapLayer }, set: { store.set(\.mapLayer, $0) })) {
                        Text("Standard").tag(MapLayer.standard)
                        Text("Muted").tag(MapLayer.muted)
                        Text("Satellite").tag(MapLayer.satellite)
                    }
                    Toggle("Labels on satellite", isOn: binding(\.mapSatelliteLabels, store))
                    Toggle("3D buildings and terrain", isOn: binding(\.map3dBuildings, store))
                    Toggle("Traffic on the current trip map", isOn: binding(\.mapTraffic, store))
                    Picker("Route colour on saved trips", selection: Binding(get: { values.routeColour }, set: { store.set(\.routeColour, $0) })) {
                        Text("Speed").tag(RouteColour.speed)
                        Text("One colour").tag(RouteColour.single)
                    }
                } header: {
                    Text("Maps")
                } footer: {
                    Text("Muted shows fewer colours, so the route stands out. Speed colours go from dark for slow to yellow for fast, across the speeds of each trip. A legend on the map shows the speeds. The current trip map uses one colour.")
                }

                Section {
                    Picker("Start with", selection: Binding(get: { values.mapStart }, set: { store.set(\.mapStart, $0) })) {
                        Text("North up").tag(MapStart.north)
                        Text("Travel direction up").tag(MapStart.course)
                    }
                    Toggle("Tilt with travel direction up", isOn: binding(\.mapTilt, store))
                    Toggle("Zoom with speed", isOn: binding(\.mapZoomWithSpeed, store))
                    Toggle("Accuracy circle", isOn: binding(\.mapAccuracyCircle, store))
                } header: {
                    Text("Current trip and replay maps")
                } footer: {
                    Text("The current trip map shows a larger area at higher speeds, until you use the zoom buttons. The circle around the position shows how accurate the position is.")
                }

                Section {
                    Picker("Keep the screen on", selection: Binding(get: { values.screenOn }, set: { store.set(\.screenOn, $0) })) {
                        Text("Never").tag(ScreenOn.off)
                        Text("During a trip").tag(ScreenOn.duringTrip)
                        Text("Always").tag(ScreenOn.always)
                    }
                    if values.screenOn != .off {
                        Toggle("Speed tab", isOn: binding(\.screenOnSpeed, store))
                        Toggle("Current trip map", isOn: binding(\.screenOnTripMap, store))
                        Toggle("Dim after 1 minute without touch", isOn: binding(\.dimScreen, store))
                    }
                } header: {
                    Text("Screen")
                } footer: {
                    switch values.screenOn {
                    case .off: Text("The screen turns off after the time set in iOS.")
                    case .duringTrip: Text("Only while a trip records. A paused trip lets the screen turn off.")
                    case .always: Text("While the app shows a selected screen. This uses the most battery.")
                    }
                }

                Section {
                    Button("Open iOS settings for this app") { model.trips.openSettings() }
                    LabeledContent("Version", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                } header: {
                    Text("About")
                } footer: {
                    Text("Location stays on this device. The map loads from Apple Maps, which receives the map area that you look at.")
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }.accessibilityIdentifier("settings_done")
                }
            }
        }
    }

    private func binding(_ keyPath: WritableKeyPath<SettingsValues, Bool>, _ store: SettingsStore) -> Binding<Bool> {
        Binding(get: { store.values[keyPath: keyPath] }, set: { store.set(keyPath, $0) })
    }

    private func binding<T: StoredChoice>(_ keyPath: WritableKeyPath<SettingsValues, T?>, _ store: SettingsStore) -> Binding<T?> {
        Binding(get: { store.values[keyPath: keyPath] }, set: { store.set(keyPath, $0) })
    }
}
