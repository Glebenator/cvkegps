import Foundation

/// The receiver that measured a fix. Only `gps` fixes give speed, direction, and height. See GLOSSARY.md.
public enum FixSource: String, Hashable, Sendable {
    case gps = "Gps"
    case network = "Network"
}

/// The usability limits. See GLOSSARY.md.
public enum UsabilityLimits {
    public static let maxHorizontalErrorMeters = 50.0
    public static let maxSpeedErrorMetersPerSecond = 3.0
}

/// One fix as a trip stores it. Linear measurements use SI units. Angular measurements use radians.
/// `altitudeMeters` is the GPS height above the WGS 84 ellipsoid.
public struct TrackSample: Hashable, Sendable {
    public var latitudeRadians: Double
    public var longitudeRadians: Double
    public var timeMillis: Int64
    public var elapsedNanos: Int64
    public var receivedElapsedNanos: Int64
    public var accuracyMeters: Double?
    public var speedMetersPerSecond: Double?
    public var speedAccuracyMetersPerSecond: Double?
    public var altitudeMeters: Double?
    public var verticalAccuracyMeters: Double?
    public var bearingRadians: Double?
    public var bearingAccuracyRadians: Double?
    public var isMock: Bool
    public var source: FixSource
    /// The number of satellites in the fix. Core Location does not report it, so it is nil on iOS.
    public var satellitesUsed: Int?

    public init(
        latitudeRadians: Double, longitudeRadians: Double, timeMillis: Int64, elapsedNanos: Int64, receivedElapsedNanos: Int64,
        accuracyMeters: Double? = nil, speedMetersPerSecond: Double? = nil, speedAccuracyMetersPerSecond: Double? = nil,
        altitudeMeters: Double? = nil, verticalAccuracyMeters: Double? = nil, bearingRadians: Double? = nil,
        bearingAccuracyRadians: Double? = nil, isMock: Bool = false, source: FixSource = .gps, satellitesUsed: Int? = nil
    ) {
        self.latitudeRadians = latitudeRadians
        self.longitudeRadians = longitudeRadians
        self.timeMillis = timeMillis
        self.elapsedNanos = elapsedNanos
        self.receivedElapsedNanos = receivedElapsedNanos
        self.accuracyMeters = accuracyMeters
        self.speedMetersPerSecond = speedMetersPerSecond
        self.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
        self.altitudeMeters = altitudeMeters
        self.verticalAccuracyMeters = verticalAccuracyMeters
        self.bearingRadians = bearingRadians
        self.bearingAccuracyRadians = bearingAccuracyRadians
        self.isMock = isMock
        self.source = source
        self.satellitesUsed = satellitesUsed
    }

    public var isValid: Bool {
        latitudeRadians.isFinite && longitudeRadians.isFinite &&
            (-Double.pi / 2...Double.pi / 2).contains(latitudeRadians) && (-Double.pi...Double.pi).contains(longitudeRadians) &&
            timeMillis > 0 && elapsedNanos > 0 && elapsedNanos <= receivedElapsedNanos
    }

    /// A fix with a known horizontal accuracy of 50 m or better.
    public var usablePosition: Bool {
        guard let accuracy = accuracyMeters else { return false }
        return accuracy.isFinite && accuracy >= 0 && accuracy <= UsabilityLimits.maxHorizontalErrorMeters
    }

    /// A finite, non-negative speed from a GPS fix with a usable position, and a speed accuracy of 3 m/s or better when reported.
    public var usableSpeed: Double? {
        guard let speed = speedMetersPerSecond, source == .gps, usablePosition, speed.isFinite, speed >= 0 else { return nil }
        if let error = speedAccuracyMetersPerSecond, !(error >= 0 && error <= UsabilityLimits.maxSpeedErrorMetersPerSecond) { return nil }
        return speed
    }

    /// Latitude and longitude in degrees, for the map.
    public var latitudeDegrees: Double { latitudeRadians * 180 / .pi }
    public var longitudeDegrees: Double { longitudeRadians * 180 / .pi }

    /// Direction of travel, only from a usable speed of at least 1 m/s. A slow or stopped fix has no reliable direction.
    public var courseRadians: Double? {
        guard let bearing = bearingRadians, bearing.isFinite, (usableSpeed ?? 0) >= 1 else { return nil }
        return bearing
    }
}

/// The reason that a fix has no usable speed.
public enum SpeedIssue: Hashable, Sendable {
    case missing, positionAccuracy, speedAccuracy, invalid
}

/// The values of the newest fix that the app shows as current. A reading has no coordinates. A missing value is not zero.
public struct GpsReading: Hashable, Sendable {
    public var speedMetersPerSecond: Double?
    /// The GPS height above the WGS 84 ellipsoid.
    public var altitudeMeters: Double?
    public var accuracyMeters: Double?
    public var elapsedNanos: Int64
    public var speedAccuracyMetersPerSecond: Double?
    public var verticalAccuracyMeters: Double?
    public var bearingRadians: Double?
    public var bearingAccuracyRadians: Double?
    public var isMock: Bool
    public var speedIssue: SpeedIssue?
    /// Height above mean sea level.
    public var seaLevelAltitudeMeters: Double?

    public init(
        speedMetersPerSecond: Double? = nil, altitudeMeters: Double? = nil, accuracyMeters: Double? = nil, elapsedNanos: Int64,
        speedAccuracyMetersPerSecond: Double? = nil, verticalAccuracyMeters: Double? = nil, bearingRadians: Double? = nil,
        bearingAccuracyRadians: Double? = nil, isMock: Bool = false, speedIssue: SpeedIssue? = nil, seaLevelAltitudeMeters: Double? = nil
    ) {
        self.speedMetersPerSecond = speedMetersPerSecond
        self.altitudeMeters = altitudeMeters
        self.accuracyMeters = accuracyMeters
        self.elapsedNanos = elapsedNanos
        self.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
        self.verticalAccuracyMeters = verticalAccuracyMeters
        self.bearingRadians = bearingRadians
        self.bearingAccuracyRadians = bearingAccuracyRadians
        self.isMock = isMock
        self.speedIssue = speedIssue
        self.seaLevelAltitudeMeters = seaLevelAltitudeMeters
    }

    /// The altitude above the chosen reference. Nil when the reading has none.
    public func altitude(_ reference: AltitudeReference) -> Double? {
        reference == .seaLevel ? seaLevelAltitudeMeters : altitudeMeters
    }
}

/// Location states for the Speed tab and the recorder.
public enum GpsEvent: Hashable, Sendable {
    case permissionRequired
    /// Precise location is off. Core Location gives only approximate positions, which have no speed.
    case reducedAccuracy
    case disabled
    case unavailable
    case searching
    case paused
    case error
    case fix(GpsReading, receivedCount: Int)
}

/// Receiver values before the usability rules. Nil means not reported. A reported value can be NaN or negative.
public struct RawFix: Sendable {
    public var latitudeRadians: Double
    public var longitudeRadians: Double
    public var timeMillis: Int64
    public var elapsedNanos: Int64
    public var receivedElapsedNanos: Int64
    public var accuracyMeters: Double?
    public var speedMetersPerSecond: Double?
    public var speedAccuracyMetersPerSecond: Double?
    public var altitudeMeters: Double?
    public var verticalAccuracyMeters: Double?
    public var bearingRadians: Double?
    public var bearingAccuracyRadians: Double?
    public var isMock: Bool
    public var source: FixSource
    public var satellitesUsed: Int?
    /// Height above mean sea level of the same fix, when the receiver reports it.
    public var seaLevelAltitudeMeters: Double?

    public init(
        latitudeRadians: Double, longitudeRadians: Double, timeMillis: Int64, elapsedNanos: Int64, receivedElapsedNanos: Int64,
        accuracyMeters: Double? = nil, speedMetersPerSecond: Double? = nil, speedAccuracyMetersPerSecond: Double? = nil,
        altitudeMeters: Double? = nil, verticalAccuracyMeters: Double? = nil, bearingRadians: Double? = nil,
        bearingAccuracyRadians: Double? = nil, isMock: Bool = false, source: FixSource = .gps, satellitesUsed: Int? = nil,
        seaLevelAltitudeMeters: Double? = nil
    ) {
        self.latitudeRadians = latitudeRadians
        self.longitudeRadians = longitudeRadians
        self.timeMillis = timeMillis
        self.elapsedNanos = elapsedNanos
        self.receivedElapsedNanos = receivedElapsedNanos
        self.accuracyMeters = accuracyMeters
        self.speedMetersPerSecond = speedMetersPerSecond
        self.speedAccuracyMetersPerSecond = speedAccuracyMetersPerSecond
        self.altitudeMeters = altitudeMeters
        self.verticalAccuracyMeters = verticalAccuracyMeters
        self.bearingRadians = bearingRadians
        self.bearingAccuracyRadians = bearingAccuracyRadians
        self.isMock = isMock
        self.source = source
        self.satellitesUsed = satellitesUsed
        self.seaLevelAltitudeMeters = seaLevelAltitudeMeters
    }
}

/// The stored sample and the displayed reading of one fix.
public struct InterpretedFix: Sendable {
    public var sample: TrackSample
    public var reading: GpsReading
}

/// Applies the usability rules to each fix once, so the Speed tab and the trip use the same speed.
public enum FixInterpreter {
    public static func interpret(_ raw: RawFix) -> InterpretedFix {
        let speedAccuracy = nonNegative(raw.speedAccuracyMetersPerSecond)
        // A stored sample cannot show that the accuracy was reported. The speed is therefore stored as missing (ADR 0001).
        let speedAccuracyInvalid = raw.speedAccuracyMetersPerSecond != nil && speedAccuracy == nil
        // A network position has no measured motion or height, so those values are not stored.
        let gps = raw.source == .gps
        let sample = TrackSample(
            latitudeRadians: raw.latitudeRadians, longitudeRadians: raw.longitudeRadians, timeMillis: raw.timeMillis,
            elapsedNanos: raw.elapsedNanos, receivedElapsedNanos: raw.receivedElapsedNanos,
            accuracyMeters: nonNegative(raw.accuracyMeters),
            speedMetersPerSecond: gps && !speedAccuracyInvalid ? nonNegative(raw.speedMetersPerSecond) : nil,
            speedAccuracyMetersPerSecond: gps ? speedAccuracy : nil,
            altitudeMeters: gps ? raw.altitudeMeters.flatMap { $0.isFinite ? $0 : nil } : nil,
            verticalAccuracyMeters: gps ? nonNegative(raw.verticalAccuracyMeters) : nil,
            bearingRadians: gps ? raw.bearingRadians.flatMap { $0.isFinite ? $0 : nil } : nil,
            bearingAccuracyRadians: gps ? raw.bearingAccuracyRadians.flatMap { $0.isFinite && $0 >= 0 ? $0 : nil } : nil,
            isMock: raw.isMock, source: raw.source,
            satellitesUsed: raw.satellitesUsed.flatMap { $0 >= 0 ? $0 : nil }
        )
        let speed = sample.usableSpeed
        let rawSpeed = gps ? raw.speedMetersPerSecond : nil
        let issue: SpeedIssue?
        if !sample.usablePosition { issue = .positionAccuracy }
        else if let rawSpeed {
            if !rawSpeed.isFinite || rawSpeed < 0 { issue = .invalid }
            else if speed == nil { issue = .speedAccuracy }
            else { issue = nil }
        } else { issue = .missing }
        let altitude = sample.usablePosition ? sample.altitudeMeters : nil
        // The reading shows the sea-level altitude only when it also shows the GPS height.
        let seaLevel = altitude == nil ? nil : raw.seaLevelAltitudeMeters.flatMap { $0.isFinite ? $0 : nil }
        let reading = GpsReading(
            speedMetersPerSecond: speed, altitudeMeters: altitude, accuracyMeters: sample.accuracyMeters,
            elapsedNanos: sample.elapsedNanos, speedAccuracyMetersPerSecond: sample.speedAccuracyMetersPerSecond,
            verticalAccuracyMeters: sample.verticalAccuracyMeters, bearingRadians: sample.bearingRadians,
            bearingAccuracyRadians: sample.bearingAccuracyRadians, isMock: sample.isMock, speedIssue: issue,
            seaLevelAltitudeMeters: seaLevel
        )
        return InterpretedFix(sample: sample, reading: reading)
    }

    private static func nonNegative(_ value: Double?) -> Double? {
        guard let value, value.isFinite, value >= 0 else { return nil }
        return value
    }
}

/// The display references of the accuracy bar. They are not receiver limits.
public enum PositionAccuracyScale {
    public static let emptyMeters = 50.0
    public static let referenceMeters = 5.0
    public static let fullMeters = 1.0

    /// A logarithmic display scale: 0 at 50 m or more, 1 at 1 m or less. Nil for a missing or invalid accuracy.
    public static func progress(_ accuracyMeters: Double?) -> Double? {
        guard let accuracy = accuracyMeters, accuracy.isFinite, accuracy >= 0 else { return nil }
        let bounded = min(max(accuracy, fullMeters), emptyMeters)
        return log(emptyMeters / bounded) / log(emptyMeters / fullMeters)
    }
}
