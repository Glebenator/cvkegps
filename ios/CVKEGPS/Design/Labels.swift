import CVKEGPSCore
import SwiftUI

/// Visible text for the shared models. All text is in the String Catalog, so a translation changes it in one place.
/// A no-break space keeps each number with its unit, so large text never puts the unit on the next line.
extension DisplayText {
    static let app = DisplayText(
        valueWithUnit: { number, unit in String(localized: "\(number)\u{00A0}\(unit)", comment: "A number and its unit, for example 12.3 km/h, with a no-break space") },
        speedUnit: { unit in
            switch unit {
            case .kilometresPerHour: String(localized: "km/h")
            case .milesPerHour: String(localized: "mph")
            case .knots: String(localized: "kn")
            }
        },
        spokenSpeedUnit: { unit in
            switch unit {
            case .kilometresPerHour: String(localized: "kilometres per hour")
            case .milesPerHour: String(localized: "miles per hour")
            case .knots: String(localized: "knots")
            }
        },
        distanceUnit: { unit in
            switch unit {
            case .kilometres: String(localized: "km")
            case .miles: String(localized: "mi")
            case .nauticalMiles: String(localized: "nmi")
            }
        },
        altitudeUnit: { unit in
            switch unit {
            case .metres: String(localized: "m", comment: "Metres")
            case .feet: String(localized: "ft")
            }
        },
        spokenSpeed: { number, unit in String(localized: "Current speed: \(number) \(unit)") },
        hoursMinutes: { hours, minutes in String(localized: "\(hours)\u{00A0}h \(minutes)\u{00A0}min") },
        minutesSeconds: { minutes, seconds in String(localized: "\(minutes)\u{00A0}min \(seconds)\u{00A0}s") },
        seconds: { seconds in String(localized: "\(seconds)\u{00A0}s", comment: "A time in seconds, with a no-break space") },
        acceleration: { number in String(localized: "\(number)\u{00A0}m/s²") }
    )
}

extension TripMode {
    var title: String {
        switch self {
        case .unknown: String(localized: "Activity unknown")
        case .carOrTransit: String(localized: "Car or transit")
        case .bikeOrSki: String(localized: "Bike or ski")
        case .car: String(localized: "Car")
        case .transit: String(localized: "Transit")
        case .plane: String(localized: "Plane")
        case .bike: String(localized: "Bike")
        case .ski: String(localized: "Ski")
        }
    }

    /// The activity icon, as on Android: combined activities use the icon of the first one, and an unknown activity has
    /// the route icon.
    var symbol: String {
        switch self {
        case .car, .carOrTransit, .transit: "car"
        case .bike, .bikeOrSki, .ski: "bicycle"
        case .plane: "airplane"
        case .unknown: "point.topleft.down.to.point.bottomright.curvepath"
        }
    }
}

extension TripRecord {
    /// The activity, with "Estimate" for an automatic estimate.
    var activityText: String {
        automaticMode && mode != .unknown ? String(localized: "Estimate: \(mode.title)") : mode.title
    }
}

extension TripMode {
    /// The full activity choice for VoiceOver, where a chip shows only the short name.
    static func spokenChoice(selected: TripMode?, estimate: TripMode) -> String {
        if let selected { return String(localized: "Activity: \(selected.title)") }
        if estimate != .unknown { return String(localized: "Activity: \(estimate.title) (estimate)") }
        return String(localized: "Activity: automatic")
    }
}

extension TripStatus {
    var title: String {
        switch self {
        case .recording: String(localized: "Recording")
        case .paused: String(localized: "Paused")
        case .interrupted: String(localized: "Interrupted")
        case .finished: String(localized: "Saved")
        }
    }
}

extension RecordingError {
    var message: String {
        switch self {
        case .access: String(localized: "Allow precise location to record a trip.")
        case .storage: String(localized: "Recording stopped because the trip could not be saved. Make free space on the phone. The trip is saved up to that point.")
        case .queue: String(localized: "Recording stopped because the phone could not save the trip fast enough. The trip is saved up to that point.")
        case .service: String(localized: "iOS stopped the recording. Open the trip to resume it.")
        case .refused: String(localized: "This trip cannot continue. A saved trip can continue only for 2 minutes after Stop and save.")
        }
    }
}

extension MomentKind {
    var title: String {
        switch self {
        case .acceleration: String(localized: "Strongest acceleration")
        case .braking: String(localized: "Strongest braking")
        case .fastestSpeed: String(localized: "Fastest speed")
        case .highestPoint: String(localized: "Highest point")
        }
    }
}

/// Date and time of a trip, with the year only when it is not the current year.
func tripTime(_ millis: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(millis) / 1000)
    let sameYear = Calendar.current.isDate(date, equalTo: .now, toGranularity: .year)
    return date.formatted(sameYear ? .dateTime.day().month(.abbreviated).hour().minute() :
        .dateTime.day().month(.abbreviated).year().hour().minute())
}

/// The display format for the chosen units and the user's locale.
struct DisplayFormatKey: EnvironmentKey {
    static let defaultValue = DisplayFormat(locale: .current, units: .forCountry(Locale.current.region?.identifier ?? ""), text: .app)
}

extension EnvironmentValues {
    var displayFormat: DisplayFormat {
        get { self[DisplayFormatKey.self] }
        set { self[DisplayFormatKey.self] = newValue }
    }
}
