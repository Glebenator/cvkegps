import Foundation

/// The speeds at the slow and the fast end of the route colours. Slower and faster speeds get the end colours.
public struct SpeedScale: Hashable, Sendable {
    public var slowMetersPerSecond: Double
    public var fastMetersPerSecond: Double

    public init(slowMetersPerSecond: Double, fastMetersPerSecond: Double) {
        self.slowMetersPerSecond = slowMetersPerSecond
        self.fastMetersPerSecond = fastMetersPerSecond
    }
}

/// A run of `segments` route segments between fixes. The colour goes from the `from` position on the colour scale to the
/// `to` position, from 0 (slow) to 1 (fast). A run with nil positions has no usable speed.
public struct SpeedRun: Hashable, Sendable {
    public var segments: Int
    public var from: Double?
    public var to: Double?

    public init(segments: Int, from: Double?, to: Double?) {
        self.segments = segments
        self.from = from
        self.to = to
    }
}

/// Colours a saved route by speed. The colours use the range of this trip, so a bike ride and a drive both use all colours.
/// A legend shows the speeds at the two ends. See ADR 0005.
public enum SpeedColours {
    /// Speeds are averaged over this time, so one noisy fix does not change the colour.
    public static let smoothingNanos: Int64 = 5_000_000_000
    /// Slower speeds are stops. They do not set the slow end of the scale.
    public static let movingMetersPerSecond = 0.5
    /// The smallest range of the scale, so a trip at an even speed does not show noise as large colour changes.
    public static let minRangeMetersPerSecond = 2.0
    /// Colour positions are rounded to this many steps, so runs at an even speed can share one colour.
    public static let steps = 32

    /// For each fix, the mean usable speed of the fixes within half of `smoothingNanos`. Nil when no fix in that time has one.
    public static func smoothedSpeeds(_ line: [TrackSample]) -> [Double?] {
        let half = smoothingNanos / 2
        var start = 0
        var end = 0
        var sum = 0.0
        var count = 0
        return line.map { fix in
            while end < line.count && line[end].elapsedNanos <= fix.elapsedNanos + half {
                if let speed = line[end].usableSpeed { sum += speed; count += 1 }
                end += 1
            }
            while line[start].elapsedNanos < fix.elapsedNanos - half {
                if let speed = line[start].usableSpeed { sum -= speed; count -= 1 }
                start += 1
            }
            return count > 0 ? max(sum / Double(count), 0) : nil
        }
    }

    /// The 5th and 95th percentiles of the moving speeds in all lines, so a few extreme fixes do not set the ends.
    public static func scale(_ lines: [[Double?]]) -> SpeedScale? {
        let moving = lines.flatMap { $0 }.compactMap { $0 }.filter { $0 >= movingMetersPerSecond }.sorted()
        guard moving.count >= 2 else { return nil }
        let slow = moving[Int((Double(moving.count - 1) * 0.05).rounded())]
        let fast = moving[Int((Double(moving.count - 1) * 0.95).rounded())]
        if fast - slow >= minRangeMetersPerSecond { return SpeedScale(slowMetersPerSecond: slow, fastMetersPerSecond: fast) }
        // A trip at an even speed gets the middle colours, not the slow end.
        let low = max((slow + fast) / 2 - minRangeMetersPerSecond / 2, 0)
        return SpeedScale(slowMetersPerSecond: low, fastMetersPerSecond: low + minRangeMetersPerSecond)
    }

    /// The position of `speed` on the scale, from 0 to 1, rounded to `steps`.
    public static func position(_ speed: Double?, _ scale: SpeedScale) -> Double? {
        guard let speed else { return nil }
        let fraction = min(max((speed - scale.slowMetersPerSecond) / (scale.fastMetersPerSecond - scale.slowMetersPerSecond), 0), 1)
        return (fraction * Double(steps)).rounded() / Double(steps)
    }

    /// The runs for a line with these colour positions. Next segments with the same colour join into one run.
    /// A segment with a missing speed at either end has no colour.
    public static func runs(_ positions: [Double?]) -> [SpeedRun] {
        var runs: [SpeedRun] = []
        for index in positions.indices.dropFirst() {
            let from = positions[index - 1]
            let to = positions[index]
            let segment = (from == nil || to == nil) ? SpeedRun(segments: 1, from: nil, to: nil) : SpeedRun(segments: 1, from: from, to: to)
            if let last = runs.last, last.from == last.to, segment.from == segment.to, last.to == segment.from {
                runs[runs.count - 1].segments += 1
            } else {
                runs.append(segment)
            }
        }
        return runs
    }
}

/// Data colours for a route coloured by speed, from slow to fast. They are the viridis scale: the lightness rises in order,
/// so the order also shows without colour vision. They stay the same in both themes. Values are 0xRRGGBB.
public enum SpeedRamp {
    public static let stops: [UInt32] = [0x440154, 0x443983, 0x31688E, 0x21918C, 0x35B779, 0x90D743, 0xFDE725]
    /// A route part without a usable speed.
    public static let noSpeed: UInt32 = 0x8A8F87
    /// The outline under a speed-coloured route on a light map, and on a dark or satellite map.
    public static let outlineOnLight: UInt32 = 0x161A1F
    public static let outlineOnDark: UInt32 = 0xFFFFFF

    /// The colour at `position`, from 0 (slow) to 1 (fast), as red, green, and blue from 0 to 1.
    public static func at(_ position: Double) -> (red: Double, green: Double, blue: Double) {
        let x = min(max(position, 0), 1) * Double(stops.count - 1)
        let index = min(Int(x), stops.count - 2)
        let part = x - Double(index)
        let a = components(stops[index])
        let b = components(stops[index + 1])
        return (a.red + (b.red - a.red) * part, a.green + (b.green - a.green) * part, a.blue + (b.blue - a.blue) * part)
    }

    public static func components(_ rgb: UInt32) -> (red: Double, green: Double, blue: Double) {
        (Double((rgb >> 16) & 0xFF) / 255, Double((rgb >> 8) & 0xFF) / 255, Double(rgb & 0xFF) / 255)
    }
}
