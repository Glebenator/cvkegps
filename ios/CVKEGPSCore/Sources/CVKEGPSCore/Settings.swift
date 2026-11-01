import Foundation
import Observation

/// The map types that the trip maps offer. Apple Maps has no terrain map, so iOS offers a muted map instead.
public enum MapLayer: String, StoredChoice {
    case standard = "default"
    /// The standard map with fewer colours, so the route stands out.
    case muted
    case satellite

    public var key: String { rawValue }
}

/// How the current trip and replay maps start to follow the position. The follow button changes it while the map shows.
public enum MapStart: String, StoredChoice {
    case north
    /// The direction of travel at the top.
    case course

    public var key: String { rawValue }
}

/// The route colour on saved trip and replay maps.
public enum RouteColour: String, StoredChoice {
    case single
    /// Colours from slow to fast across the speed range of the trip.
    case speed

    public var key: String { rawValue }
}

/// How a trip uses the location receiver. `high` uses more battery.
public enum RecordingAccuracy: String, StoredChoice {
    /// The best accuracy for one position each second.
    case standard
    /// The best accuracy for navigation, with more sensor data in each position.
    case high

    public var key: String { rawValue }
}

/// User choices that stay after the app closes.
public struct SettingsValues: Hashable, Sendable {
    /// When the app keeps the screen on. Off by default, because it uses more battery.
    public var screenOn: ScreenOn = .off
    /// Where the screen stays on: the Speed tab and the current trip map.
    public var screenOnSpeed = true
    public var screenOnTripMap = true
    /// Dim a screen that the app keeps on after a time without touch.
    public var dimScreen = false
    /// Nil units follow the region of the device.
    public var speedUnit: SpeedUnit?
    public var distanceUnit: DistanceUnit?
    public var altitudeUnit: AltitudeUnit?
    public var altitudeReference: AltitudeReference = .seaLevel
    /// The user has read the trip recording explanation once.
    public var tripAccessExplained = false
    public var mapLayer: MapLayer = .standard
    /// Live traffic on the current trip map. Off by default.
    public var mapTraffic = false
    /// The route colour on saved trip and replay maps. The current trip map always uses one colour.
    public var routeColour: RouteColour = .speed
    public var mapStart: MapStart = .north
    /// The map tilts while the direction of travel is up.
    public var mapTilt = true
    /// The current trip map zooms out at higher speeds until the zoom buttons are used.
    public var mapZoomWithSpeed = true
    /// 3D buildings and terrain elevation on the trip maps.
    public var map3dBuildings = true
    /// Road and place names on the satellite map.
    public var mapSatelliteLabels = true
    /// The accuracy circle around the position on the current trip and replay maps.
    public var mapAccuracyCircle = true
    public var recordingAccuracy: RecordingAccuracy = .standard
    /// A recording trip stores approximate positions while it has no GPS signal.
    public var networkFallback = true

    public init() {}

    /// The chosen units, with the region's units where the user made no choice.
    public func units(country: String) -> Units {
        let region = Units.forCountry(country)
        return Units(speed: speedUnit ?? region.speed, distance: distanceUnit ?? region.distance, altitude: altitudeUnit ?? region.altitude)
    }

    public var recordingOptions: RecordingOptions {
        RecordingOptions(highAccuracy: recordingAccuracy == .high, networkFallback: networkFallback)
    }
}

/// Local settings in the app's user defaults. Each change shows at once and writes only the changed value.
/// An unknown stored value, for example from a later app version, reads as the default.
@MainActor
@Observable
public final class SettingsStore {
    public private(set) var values: SettingsValues
    @ObservationIgnored private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        values = Self.read(defaults)
    }

    public func set<T: StoredChoice>(_ keyPath: WritableKeyPath<SettingsValues, T>, _ value: T) {
        values[keyPath: keyPath] = value
        if let key = Self.keys[keyPath] { defaults.set(value.key, forKey: key) }
    }

    /// Nil returns to the default, for example the units of the region.
    public func set<T: StoredChoice>(_ keyPath: WritableKeyPath<SettingsValues, T?>, _ value: T?) {
        values[keyPath: keyPath] = value
        guard let key = Self.keys[keyPath] else { return }
        if let value { defaults.set(value.key, forKey: key) } else { defaults.removeObject(forKey: key) }
    }

    public func set(_ keyPath: WritableKeyPath<SettingsValues, Bool>, _ value: Bool) {
        values[keyPath: keyPath] = value
        if let key = Self.keys[keyPath] { defaults.set(value, forKey: key) }
    }

    static let keys: [PartialKeyPath<SettingsValues>: String] = [
        \.screenOn: "screen_on", \.screenOnSpeed: "screen_on_speed", \.screenOnTripMap: "screen_on_trip_map",
        \.dimScreen: "dim_screen", \.speedUnit: "speed_unit", \.distanceUnit: "distance_unit", \.altitudeUnit: "altitude_unit",
        \.altitudeReference: "altitude_reference", \.tripAccessExplained: "trip_access_explained", \.mapLayer: "map_layer",
        \.mapTraffic: "map_traffic", \.routeColour: "route_colour", \.mapStart: "map_start", \.mapTilt: "map_tilt",
        \.mapZoomWithSpeed: "map_zoom_with_speed", \.map3dBuildings: "map_3d_buildings",
        \.mapSatelliteLabels: "map_satellite_labels", \.mapAccuracyCircle: "map_accuracy_circle",
        \.recordingAccuracy: "recording_accuracy", \.networkFallback: "network_fallback",
    ]

    static func read(_ defaults: UserDefaults) -> SettingsValues {
        func flag(_ key: String, _ fallback: Bool) -> Bool { defaults.object(forKey: key) as? Bool ?? fallback }
        func choice<T: StoredChoice>(_ key: String) -> T? { T.choice(forKey: defaults.string(forKey: key)) }
        var values = SettingsValues()
        values.screenOn = choice("screen_on") ?? .off
        values.screenOnSpeed = flag("screen_on_speed", true)
        values.screenOnTripMap = flag("screen_on_trip_map", true)
        values.dimScreen = flag("dim_screen", false)
        values.speedUnit = choice("speed_unit")
        values.distanceUnit = choice("distance_unit")
        values.altitudeUnit = choice("altitude_unit")
        values.altitudeReference = choice("altitude_reference") ?? .seaLevel
        values.tripAccessExplained = flag("trip_access_explained", false)
        values.mapLayer = choice("map_layer") ?? .standard
        values.mapTraffic = flag("map_traffic", false)
        values.routeColour = choice("route_colour") ?? .speed
        values.mapStart = choice("map_start") ?? .north
        values.mapTilt = flag("map_tilt", true)
        values.mapZoomWithSpeed = flag("map_zoom_with_speed", true)
        values.map3dBuildings = flag("map_3d_buildings", true)
        values.mapSatelliteLabels = flag("map_satellite_labels", true)
        values.mapAccuracyCircle = flag("map_accuracy_circle", true)
        values.recordingAccuracy = choice("recording_accuracy") ?? .standard
        values.networkFallback = flag("network_fallback", true)
        return values
    }
}
