import Foundation

/// The words that `DisplayFormat` puts around numbers. The app fills them from its String Catalog. Tests use English.
public struct DisplayText: Sendable {
    public var valueWithUnit: @Sendable (_ number: String, _ unit: String) -> String
    public var speedUnit: @Sendable (SpeedUnit) -> String
    public var spokenSpeedUnit: @Sendable (SpeedUnit) -> String
    public var distanceUnit: @Sendable (DistanceUnit) -> String
    public var altitudeUnit: @Sendable (AltitudeUnit) -> String
    public var spokenSpeed: @Sendable (_ number: String, _ unit: String) -> String
    public var hoursMinutes: @Sendable (_ hours: String, _ minutes: String) -> String
    public var minutesSeconds: @Sendable (_ minutes: String, _ seconds: String) -> String
    public var seconds: @Sendable (_ seconds: String) -> String
    public var acceleration: @Sendable (_ number: String) -> String

    public init(
        valueWithUnit: @escaping @Sendable (String, String) -> String,
        speedUnit: @escaping @Sendable (SpeedUnit) -> String,
        spokenSpeedUnit: @escaping @Sendable (SpeedUnit) -> String,
        distanceUnit: @escaping @Sendable (DistanceUnit) -> String,
        altitudeUnit: @escaping @Sendable (AltitudeUnit) -> String,
        spokenSpeed: @escaping @Sendable (String, String) -> String,
        hoursMinutes: @escaping @Sendable (String, String) -> String,
        minutesSeconds: @escaping @Sendable (String, String) -> String,
        seconds: @escaping @Sendable (String) -> String,
        acceleration: @escaping @Sendable (String) -> String
    ) {
        self.valueWithUnit = valueWithUnit
        self.speedUnit = speedUnit
        self.spokenSpeedUnit = spokenSpeedUnit
        self.distanceUnit = distanceUnit
        self.altitudeUnit = altitudeUnit
        self.spokenSpeed = spokenSpeed
        self.hoursMinutes = hoursMinutes
        self.minutesSeconds = minutesSeconds
        self.seconds = seconds
        self.acceleration = acceleration
    }

    /// English words, for tests and previews.
    public static let english = DisplayText(
        valueWithUnit: { "\($0) \($1)" },
        speedUnit: { [.kilometresPerHour: "km/h", .milesPerHour: "mph", .knots: "kn"][$0]! },
        spokenSpeedUnit: { [.kilometresPerHour: "kilometres per hour", .milesPerHour: "miles per hour", .knots: "knots"][$0]! },
        distanceUnit: { [.kilometres: "km", .miles: "mi", .nauticalMiles: "nmi"][$0]! },
        altitudeUnit: { [.metres: "m", .feet: "ft"][$0]! },
        spokenSpeed: { "Current speed: \($0) \($1)" },
        hoursMinutes: { "\($0) h \($1) min" },
        minutesSeconds: { "\($0) min \($1) s" },
        seconds: { "\($0) s" },
        acceleration: { "\($0) m/s²" }
    )
}

/// Formats SI measurements for display in the user's locale and `units`. Measurements stay in SI units until this point.
/// It does not use SwiftUI, so the Live Activity uses the same text as the screens.
public struct DisplayFormat: Sendable {
    /// A number in display units, with the unit text and the number of decimals that the number shows.
    public struct Amount: Hashable, Sendable {
        public var value: Double
        public var unit: String
        public var decimals: Int
    }

    public let units: Units
    private let locale: Locale
    private let text: DisplayText

    public init(locale: Locale, units: Units, text: DisplayText) {
        self.locale = locale
        self.units = units
        self.text = text
    }

    private func formatter(maximumDecimals: Int, grouping: Bool = true, minimumIntegerDigits: Int = 1) -> NumberFormatter {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.maximumFractionDigits = maximumDecimals
        formatter.minimumFractionDigits = 0
        formatter.usesGroupingSeparator = grouping
        formatter.minimumIntegerDigits = minimumIntegerDigits
        formatter.roundingMode = .halfEven
        return formatter
    }

    private func string(_ value: Double, _ formatter: NumberFormatter) -> String {
        formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// The speed number without a unit, for screens that show `speedUnit` separately. A speed has at most five digits,
    /// so it has no grouping separator.
    public func speedNumber(_ metersPerSecond: Double) -> String {
        string(metersPerSecond * units.speed.perMetrePerSecond, formatter(maximumDecimals: 1, grouping: false))
    }

    public var speedUnit: String { text.speedUnit(units.speed) }

    public func speed(_ metersPerSecond: Double) -> String { text.valueWithUnit(speedNumber(metersPerSecond), speedUnit) }

    /// The speed for VoiceOver, with the unit name in full.
    public func spokenSpeed(_ metersPerSecond: Double) -> String {
        text.spokenSpeed(speedNumber(metersPerSecond), text.spokenSpeedUnit(units.speed))
    }

    public func speedAmount(_ metersPerSecond: Double) -> Amount {
        Amount(value: metersPerSecond * units.speed.perMetrePerSecond, unit: speedUnit, decimals: 1)
    }

    /// Kilometres show metres below 1 km. Miles show feet below 0.1 mi. Nautical miles always show nautical miles.
    public func distanceAmount(_ meters: Double) -> Amount {
        if units.distance == .kilometres && meters < 1_000 {
            return Amount(value: meters, unit: text.altitudeUnit(.metres), decimals: 0)
        }
        if units.distance == .miles && meters < DistanceUnit.miles.metres / 10 {
            return Amount(value: meters / AltitudeUnit.feet.metres, unit: text.altitudeUnit(.feet), decimals: 0)
        }
        return Amount(value: meters / units.distance.metres, unit: text.distanceUnit(units.distance), decimals: 2)
    }

    public func distance(_ meters: Double) -> String {
        let amount = distanceAmount(meters)
        return text.valueWithUnit(string(amount.value, formatter(maximumDecimals: amount.decimals)), amount.unit)
    }

    /// The altitude number in whole units, without a unit. GPS altitude is not accurate to less than a metre.
    public func altitudeNumber(_ meters: Double) -> String {
        string(meters / units.altitude.metres, formatter(maximumDecimals: 0, grouping: false))
    }

    public var altitudeUnit: String { text.altitudeUnit(units.altitude) }

    public func altitude(_ meters: Double) -> String { text.valueWithUnit(altitudeNumber(meters), altitudeUnit) }

    /// Seconds below one minute, minutes and seconds below one hour, then hours and minutes.
    public func duration(_ nanos: Int64) -> String {
        let seconds = max(nanos / 1_000_000_000, 0)
        let whole = formatter(maximumDecimals: 0)
        if seconds >= 3_600 {
            return text.hoursMinutes(string(Double(seconds / 3_600), whole), string(Double(seconds / 60 % 60), whole))
        }
        if seconds >= 60 { return text.minutesSeconds(string(Double(seconds / 60), whole), string(Double(seconds % 60), whole)) }
        return text.seconds(string(Double(seconds), whole))
    }

    /// A short clock time for narrow places, for example 12:04 or 1:02:04.
    public func clock(_ nanos: Int64) -> String {
        let seconds = max(nanos / 1_000_000_000, 0)
        let whole = formatter(maximumDecimals: 0, grouping: false)
        let twoDigits = formatter(maximumDecimals: 0, grouping: false, minimumIntegerDigits: 2)
        let minutes = string(Double(seconds / 60 % 60), seconds >= 3_600 ? twoDigits : whole)
        let rest = "\(minutes):\(string(Double(seconds % 60), twoDigits))"
        return seconds >= 3_600 ? "\(string(Double(seconds / 3_600), whole)):\(rest)" : rest
    }

    /// An acceleration in m/s² with one decimal. The app has no other unit for it.
    public func acceleration(_ metersPerSecondSquared: Double) -> String {
        text.acceleration(decimal(metersPerSecondSquared))
    }

    /// A plain number with one decimal, for metres and degrees.
    public func decimal(_ value: Double) -> String { string(value, formatter(maximumDecimals: 1)) }

    public func whole(_ value: Int) -> String { string(Double(value), formatter(maximumDecimals: 0)) }
}
