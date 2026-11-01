import Foundation

/// A choice that the settings store. `key` is the stored value, so a rename in code does not change it.
public protocol StoredChoice: CaseIterable, Hashable, Sendable {
    var key: String { get }
}

extension StoredChoice {
    /// The choice with this stored key, or nil for an unknown key, for example from a later app version.
    public static func choice(forKey key: String?) -> Self? { allCases.first { $0.key == key } }
}

public let metresPerMile = 1_609.344
public let metresPerNauticalMile = 1_852.0

/// Speed units for display. `perMetrePerSecond` converts 1 m/s to this unit.
public enum SpeedUnit: String, StoredChoice {
    case kilometresPerHour = "kmh"
    case milesPerHour = "mph"
    case knots = "kn"

    public var key: String { rawValue }

    public var perMetrePerSecond: Double {
        switch self {
        case .kilometresPerHour: 3.6
        case .milesPerHour: 3_600 / metresPerMile
        case .knots: 3_600 / metresPerNauticalMile
        }
    }
}

/// Distance units for display. `metres` is the length of one unit.
public enum DistanceUnit: String, StoredChoice {
    case kilometres = "km"
    case miles = "mi"
    case nauticalMiles = "nmi"

    public var key: String { rawValue }

    public var metres: Double {
        switch self {
        case .kilometres: 1_000
        case .miles: metresPerMile
        case .nauticalMiles: metresPerNauticalMile
        }
    }
}

/// Altitude units for display. `metres` is the length of one unit.
public enum AltitudeUnit: String, StoredChoice {
    case metres = "m"
    case feet = "ft"

    public var key: String { rawValue }

    public var metres: Double {
        switch self {
        case .metres: 1
        case .feet: 0.3048
        }
    }
}

/// The units that the screens and the Live Activity use. Measurements stay in SI units until display.
public struct Units: Hashable, Sendable {
    public var speed: SpeedUnit
    public var distance: DistanceUnit
    public var altitude: AltitudeUnit

    public init(speed: SpeedUnit, distance: DistanceUnit, altitude: AltitudeUnit) {
        self.speed = speed
        self.distance = distance
        self.altitude = altitude
    }

    public static let metric = Units(speed: .kilometresPerHour, distance: .kilometres, altitude: .metres)

    /// The usual road units of a region, from its ISO 3166 code. The United Kingdom uses miles and metres.
    /// The United States, Liberia, and Myanmar use miles and feet. All other regions use metric units.
    public static func forCountry(_ country: String) -> Units {
        switch country.uppercased() {
        case "US", "LR", "MM": Units(speed: .milesPerHour, distance: .miles, altitude: .feet)
        case "GB": Units(speed: .milesPerHour, distance: .miles, altitude: .metres)
        default: .metric
        }
    }
}

/// The zero height of a displayed altitude.
public enum AltitudeReference: String, StoredChoice {
    /// Mean sea level. Core Location reports it with each fix.
    case seaLevel = "sea_level"
    /// The WGS 84 ellipsoid that GPS receivers report. It can differ from sea level by up to about 100 m.
    case ellipsoid

    public var key: String { rawValue }
}

/// When the app keeps the screen on. The settings for each screen limit where.
public enum ScreenOn: String, StoredChoice {
    case off
    /// Only while a trip records. A paused trip lets the screen turn off.
    case duringTrip = "trip"
    case always

    public var key: String { rawValue }
}
