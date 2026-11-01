import Foundation

/// Writes GPX 1.1. Each route line becomes one track segment, so gaps stay unconnected, as on the map.
/// Altitude is not written: trips store the height above the WGS 84 ellipsoid, and GPX readers expect height above sea level.
/// A network position has the GPX source "network", so a reader can tell it from a GPS fix.
public enum Gpx {
    public static let mimeType = "application/gpx+xml"

    /// For example `cvke-trip-2026-10-23-1400.gpx`, in local time.
    public static func fileName(startedMillis: Int64, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute],
                                            from: Date(timeIntervalSince1970: Double(startedMillis) / 1000))
        return "cvke-trip-" + pad(parts.year!, 4) + "-" + pad(parts.month!, 2) + "-" + pad(parts.day!, 2) + "-" +
            pad(parts.hour!, 2) + pad(parts.minute!, 2) + ".gpx"
    }

    public static func write(name: String, lines: [[TrackSample]]) -> String {
        var out = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        out += "<gpx version=\"1.1\" creator=\"CVKE GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
        out += "  <trk>\n    <name>\(escape(name))</name>\n"
        for line in lines {
            out += "    <trkseg>\n"
            for sample in line {
                // A fixed format keeps a decimal point in every language.
                let lat = decimal(sample.latitudeDegrees)
                let lon = decimal(sample.longitudeDegrees)
                let source = sample.source == .network ? "<src>network</src>" : ""
                out += "      <trkpt lat=\"\(lat)\" lon=\"\(lon)\"><time>\(utc(sample.timeMillis))</time>\(source)</trkpt>\n"
            }
            out += "    </trkseg>\n"
        }
        out += "  </trk>\n</gpx>\n"
        return out
    }

    /// Seven decimals, with a point, for any locale.
    static func decimal(_ value: Double) -> String {
        let scaled = (abs(value) * 10_000_000).rounded()
        let whole = Int64(scaled) / 10_000_000
        let fraction = Int64(scaled) % 10_000_000
        return (value < 0 && scaled > 0 ? "-" : "") + String(whole) + "." + pad(Int(fraction), 7)
    }

    /// An ISO 8601 UTC time. Milliseconds show only when they are not zero.
    static func utc(_ millis: Int64) -> String {
        let seconds = millis >= 0 ? millis / 1000 : (millis - 999) / 1000
        let rest = Int(millis - seconds * 1000)
        var days = seconds >= 0 ? seconds / 86_400 : (seconds - 86_399) / 86_400
        let daySeconds = Int(seconds - days * 86_400)
        // Civil date from days since 1970-01-01.
        days += 719_468
        let era = (days >= 0 ? days : days - 146_096) / 146_097
        let dayOfEra = days - era * 146_097
        let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let monthIndex = (5 * dayOfYear + 2) / 153
        let day = dayOfYear - (153 * monthIndex + 2) / 5 + 1
        let month = monthIndex < 10 ? monthIndex + 3 : monthIndex - 9
        let year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
        var text = pad(Int(year), 4) + "-" + pad(Int(month), 2) + "-" + pad(Int(day), 2) + "T" + pad(daySeconds / 3600, 2) + ":" +
            pad(daySeconds / 60 % 60, 2) + ":" + pad(daySeconds % 60, 2)
        if rest != 0 { text += "." + pad(rest, 3) }
        return text + "Z"
    }

    private static func pad(_ value: Int, _ width: Int) -> String {
        let digits = String(value)
        return String(repeating: "0", count: max(0, width - digits.count)) + digits
    }

    private static func escape(_ text: String) -> String {
        text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
            .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
    }
}
