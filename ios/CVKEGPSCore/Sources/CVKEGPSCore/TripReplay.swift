import Foundation

/// The notable moments that a replay finds.
public enum MomentKind: String, CaseIterable, Hashable, Sendable {
    case acceleration, braking, fastestSpeed, highestPoint
}

/// Where the replay height comes from. `barometer` is the air pressure, set to the GPS height level.
public enum HeightSource: Hashable, Sendable {
    case barometer, gps, none
}

/// Values in time order on the replay clock. A run has no gap, so a chart or profile can join its values.
/// `reach` is how far before the first value and after the last value the end values still apply.
/// A 1 second mean applies for half a second on each side of its centre time.
public struct ReplayRun: Hashable, Sendable {
    public let times: [Int64]
    public let values: [Double]
    public let reach: Int64

    public init(times: [Int64], values: [Double], reach: Int64 = 0) {
        self.times = times
        self.values = values
        self.reach = reach
    }

    /// The value at `time`, interpolated between two values. Nil outside the run and its reach.
    public func at(_ time: Int64) -> Double? {
        guard let first = times.first, let last = times.last, time >= first - reach, time <= last + reach else { return nil }
        if time <= first { return values[0] }
        if time >= last { return values[values.count - 1] }
        let index = lowerBound(times, time)
        if times[index] == time { return values[index] }
        let before = index - 1
        let part = Double(time - times[before]) / Double(times[index] - times[before])
        return values[before] + (values[index] - values[before]) * part
    }
}

/// The first index whose value is not less than `value`.
func lowerBound(_ array: [Int64], _ value: Int64) -> Int {
    var low = 0
    var high = array.count
    while low < high {
        let middle = (low + high) / 2
        if array[middle] < value { low = middle + 1 } else { high = middle }
    }
    return low
}

/// A notable moment. `number` is its order on the replay clock, from 1. A moment in a GPS gap has no position.
public struct ReplayMoment: Hashable, Sendable, Identifiable {
    public var number: Int
    public var kind: MomentKind
    public var timeNanos: Int64
    /// m/s for speed, metres for height, m/s² for acceleration and braking (braking is negative).
    public var value: Double
    public var latitudeRadians: Double?
    public var longitudeRadians: Double?

    public var id: Int { number }
}

/// One stored fix on the replay clock.
public struct ReplayFix: Hashable, Sendable {
    public var timeNanos: Int64
    public var segment: Int
    public var sample: TrackSample
}

/// The values at one replay time. Nil means unavailable.
public struct ReplayValues: Hashable, Sendable {
    /// The newest fix at or before the time, while it is current. Nil in a GPS gap.
    public var fix: TrackSample?
    /// The position on the route line at the time. Nil in a gap.
    public var latitudeRadians: Double?
    public var longitudeRadians: Double?
    /// The direction of travel at the time, turned evenly from one fix to the next. Nil without a position or a bearing.
    public var bearingRadians: Double?
    public var speedMetersPerSecond: Double?
    public var heightMeters: Double?
    public var accelerationMetersPerSecondSquared: Double?
}

/// One recording segment and its data, as the database stores them.
public struct ReplaySegment: Sendable {
    public var startedElapsedNanos: Int64
    public var endedElapsedNanos: Int64?
    public var samples: [TrackSample]
    public var sensors: [SensorChunk]

    public init(startedElapsedNanos: Int64, endedElapsedNanos: Int64?, samples: [TrackSample], sensors: [SensorChunk]) {
        self.startedElapsedNanos = startedElapsedNanos
        self.endedElapsedNanos = endedElapsedNanos
        self.samples = samples
        self.sensors = sensors
    }
}

/// A saved trip, ready to replay. The replay clock joins the segments without the pauses between them.
/// Each value comes from stored measurements. See GLOSSARY.md.
public struct TripReplay: Sendable {
    public let durationNanos: Int64
    /// Replay times where a segment after the first starts. A pause or an interruption was there.
    public let segmentStarts: [Int64]
    public let fixes: [ReplayFix]
    public let lines: [[TrackSample]]
    /// The replay time of each point of `lines`, in the same order.
    public let lineTimes: [[Int64]]
    public let speed: [ReplayRun]
    public let height: [ReplayRun]
    public let heightSource: HeightSource
    public let acceleration: [ReplayRun]
    /// True when the acceleration comes from the phone sensors. False when it comes from the GPS speed.
    public let accelerationFromSensors: Bool
    /// Replay time ranges without a current GPS fix.
    public let gpsGaps: [ClosedRange<Int64>]
    /// Replay time ranges with network positions only.
    public let approximate: [ClosedRange<Int64>]
    public internal(set) var moments: [ReplayMoment]

    /// The values at `time`. The newest fix shows only for `RouteMath.signalGapNanos`, as on the live screen.
    public func valuesAt(_ time: Int64) -> ReplayValues {
        let index = lastFixAt(time)
        let fix: ReplayFix? = index >= 0 && time - fixes[index].timeNanos < RouteMath.signalGapNanos ? fixes[index] : nil
        let position = (fix?.sample.usablePosition ?? false) ? interpolate(index, time) : nil
        return ReplayValues(
            fix: fix?.sample, latitudeRadians: position?.latitude, longitudeRadians: position?.longitude,
            bearingRadians: position?.bearing, speedMetersPerSecond: fix?.sample.usableSpeed,
            heightMeters: height.lazy.compactMap { $0.at(time) }.first,
            accelerationMetersPerSecondSquared: acceleration.lazy.compactMap { $0.at(time) }.first
        )
    }

    private func lastFixAt(_ time: Int64) -> Int {
        var low = 0
        var high = fixes.count - 1
        var found = -1
        while low <= high {
            let middle = (low + high) / 2
            if fixes[middle].timeNanos <= time { found = middle; low = middle + 1 } else { high = middle - 1 }
        }
        return found
    }

    private struct Position {
        var latitude: Double
        var longitude: Double
        var bearing: Double?
    }

    /// A position between two fixes of the same line. A gap, a poor fix, or a change of source is not crossed.
    /// The bearing turns the shorter way from the first fix's bearing to the next one's.
    private func interpolate(_ index: Int, _ time: Int64) -> Position {
        let a = fixes[index]
        let first = a.sample.bearingRadians.flatMap { $0.isFinite ? $0 : nil }
        guard index + 1 < fixes.count else { return Position(latitude: a.sample.latitudeRadians, longitude: a.sample.longitudeRadians, bearing: first) }
        let b = fixes[index + 1]
        if b.segment != a.segment || !b.sample.usablePosition || b.sample.source != a.sample.source ||
            b.timeNanos - a.timeNanos >= RouteMath.signalGapNanos || b.timeNanos == a.timeNanos {
            return Position(latitude: a.sample.latitudeRadians, longitude: a.sample.longitudeRadians, bearing: first)
        }
        let part = Double(time - a.timeNanos) / Double(b.timeNanos - a.timeNanos)
        var longitude = a.sample.longitudeRadians + Self.shorter(b.sample.longitudeRadians - a.sample.longitudeRadians) * part
        if longitude > .pi { longitude -= 2 * .pi } else if longitude < -.pi { longitude += 2 * .pi }
        let next = b.sample.bearingRadians.flatMap { $0.isFinite ? $0 : nil }
        var bearing = first
        if let first, let next {
            let turned = first + Self.shorter(next - first) * part
            bearing = turned < 0 ? turned + 2 * .pi : turned >= 2 * .pi ? turned - 2 * .pi : turned
        }
        return Position(latitude: a.sample.latitudeRadians + (b.sample.latitudeRadians - a.sample.latitudeRadians) * part,
                        longitude: longitude, bearing: bearing)
    }

    /// An angle step the shorter way round, from -π to π. It also crosses the date line the shorter way.
    static func shorter(_ step: Double) -> Double {
        step > .pi ? step - 2 * .pi : step < -.pi ? step + 2 * .pi : step
    }

    /// How many points of route line `line` are at or before `time`. The map draws them as the played part.
    public func playedPoints(line: Int, at time: Int64) -> Int {
        guard line >= 0 && line < lineTimes.count else { return 0 }
        let times = lineTimes[line]
        var low = 0
        var high = times.count
        while low < high {
            let middle = (low + high) / 2
            if times[middle] <= time { low = middle + 1 } else { high = middle }
        }
        return low
    }

    /// The first moment after `time`, or nil.
    public func nextMoment(after time: Int64) -> ReplayMoment? { moments.first { $0.timeNanos > time } }
    /// The last moment before `time`, or nil.
    public func previousMoment(before time: Int64) -> ReplayMoment? { moments.last { $0.timeNanos < time } }
}

public enum ReplayAnalysis {
    /// Accelerations smaller than this are not moments. A car that starts gently reaches about 1 m/s².
    public static let minMomentAcceleration = 0.5
    /// The height must change by at least this much before the highest point is a moment.
    public static let minHeightRangeMeters = 1.0
    static let binNanos: Int64 = 1_000_000_000
    static let yawWindowNanos: Int64 = 30_000_000_000
    static let sensorGapNanos: Int64 = 2_000_000_000
    static let gravity = 9.80665
    static let goodVerticalAccuracyMeters = 10.0
    /// Minimum GPS change of velocity in a window, so the alignment angle is reliable.
    static let minAlignmentSignal = 3.0

    /// `gpsHeightOnly` is for Plane trips: cabin pressure is not the outside pressure.
    public static func analyze(_ segments: [ReplaySegment], gpsHeightOnly: Bool = false) -> TripReplay {
        var fixes: [ReplayFix] = []
        var segmentStarts: [Int64] = []
        var speed: [ReplayRun] = []
        var gpsHeight: [ReplayRun] = []
        var pressureHeight: [ReplayRun] = []
        var sensorAcceleration: [ReplayRun] = []
        var gpsAcceleration: [ReplayRun] = []
        var gaps: [ClosedRange<Int64>] = []
        var approximate: [ClosedRange<Int64>] = []
        var lines: [[TrackSample]] = []
        var lineTimes: [[Int64]] = []
        var offset: Int64 = 0
        for (index, segment) in segments.enumerated() {
            let samples = RouteMath.ordered(segment.samples)
            let start = segment.startedElapsedNanos
            let lastData = [samples.last?.elapsedNanos, segment.sensors.compactMap { $0.elapsedNanos.last }.max()].compactMap { $0 }.max()
            let end = max(segment.endedElapsedNanos ?? lastData ?? start, start)
            let base = offset
            let clock = { (elapsed: Int64) -> Int64 in base + (elapsed - start) }
            if index > 0 { segmentStarts.append(base) }
            // The route lines of one segment never join another segment, so each segment gives its own lines and times.
            for line in RouteMath.summarize([segment.samples]).lines {
                lines.append(line)
                lineTimes.append(line.map { min(max(clock($0.elapsedNanos), base), base + end - start) })
            }
            let inSegment = samples.filter { $0.elapsedNanos >= start && $0.elapsedNanos <= end }
            inSegment.forEach { fixes.append(ReplayFix(timeNanos: clock($0.elapsedNanos), segment: index, sample: $0)) }
            let gps = inSegment.filter { $0.source == .gps }
            speed += runs(gps, clock) { $0.usableSpeed }
            gpsHeight += runs(gps, clock) { $0.usablePosition ? $0.altitudeMeters : nil }
            gaps += gpsGaps(inSegment, start, end, clock)
            approximate += networkRanges(inSegment, end, clock)
            let chunks = segment.sensors.filter { ($0.elapsedNanos.last ?? .min) >= start && ($0.elapsedNanos.first ?? .max) <= end }
            if !gpsHeightOnly { pressureHeight += pressureRuns(chunks, start, end, clock) }
            sensorAcceleration += sensorAlongTrack(chunks, inSegment, start, end, clock)
            gpsAcceleration += gpsAlongTrack(inSegment, start, clock)
            offset += end - start
        }
        // The barometer gives the shape. The GPS height gives the level: the median difference over fixes with a good
        // vertical accuracy.
        let height: [ReplayRun]
        let heightSource: HeightSource
        if let level = barometerLevel(pressureHeight, fixes) {
            height = pressureHeight.map { ReplayRun(times: $0.times, values: $0.values.map { $0 + level }, reach: $0.reach) }
            heightSource = .barometer
        } else if !gpsHeight.isEmpty {
            height = gpsHeight
            heightSource = .gps
        } else {
            height = []
            heightSource = .none
        }
        let fromSensors = !sensorAcceleration.isEmpty
        var replay = TripReplay(
            durationNanos: offset, segmentStarts: segmentStarts, fixes: fixes, lines: lines, lineTimes: lineTimes, speed: speed,
            height: height, heightSource: heightSource, acceleration: fromSensors ? sensorAcceleration : gpsAcceleration,
            accelerationFromSensors: fromSensors, gpsGaps: gaps, approximate: approximate, moments: []
        )
        replay.moments = moments(replay)
        return replay
    }

    /// Splits `samples` into runs of consecutive values. A nil value or a gap of 10 seconds ends a run.
    static func runs(_ samples: [TrackSample], _ clock: (Int64) -> Int64, _ value: (TrackSample) -> Double?) -> [ReplayRun] {
        var result: [ReplayRun] = []
        var times: [Int64] = []
        var values: [Double] = []
        func close() {
            if !times.isEmpty { result.append(ReplayRun(times: times, values: values)) }
            times = []
            values = []
        }
        for sample in samples {
            let v = value(sample)
            let time = clock(sample.elapsedNanos)
            let finite = v.map { $0.isFinite } ?? false
            if !finite || (times.last.map { time - $0 >= RouteMath.signalGapNanos } ?? false) { close() }
            if let v, finite {
                times.append(time)
                values.append(v)
            }
        }
        close()
        return result
    }

    /// Times without a current, usable GPS fix: from 10 seconds after a fix until the next one.
    static func gpsGaps(_ samples: [TrackSample], _ start: Int64, _ end: Int64, _ clock: (Int64) -> Int64) -> [ClosedRange<Int64>] {
        let gps = samples.filter { $0.source == .gps && $0.usablePosition }.map(\.elapsedNanos)
        var result: [ClosedRange<Int64>] = []
        var current = start
        for time in gps + [end] {
            let lostAt = current + RouteMath.signalGapNanos
            if time > lostAt { result.append(clock(lostAt)...clock(time)) }
            current = time
        }
        return result
    }

    /// Times when a network position is the current position: from each network position until the next fix, for at most
    /// 10 seconds.
    static func networkRanges(_ samples: [TrackSample], _ end: Int64, _ clock: (Int64) -> Int64) -> [ClosedRange<Int64>] {
        var result: [ClosedRange<Int64>] = []
        for (index, sample) in samples.enumerated() where sample.source == .network && sample.usablePosition {
            let next = index + 1 < samples.count ? samples[index + 1].elapsedNanos : end
            let until = max(min(sample.elapsedNanos + RouteMath.signalGapNanos, next, end), sample.elapsedNanos)
            let range = clock(sample.elapsedNanos)...clock(until)
            if let last = result.last, last.upperBound >= range.lowerBound {
                result[result.count - 1] = last.lowerBound...max(last.upperBound, range.upperBound)
            } else {
                result.append(range)
            }
        }
        return result
    }

    /// Height from air pressure with the standard atmosphere, in 1 second means, smoothed over 3 seconds.
    static func pressureRuns(_ chunks: [SensorChunk], _ start: Int64, _ end: Int64, _ clock: (Int64) -> Int64) -> [ReplayRun] {
        var readings: [(Int64, [Double])] = []
        for chunk in chunks where chunk.kind == .pressure {
            for index in 0..<chunk.count {
                let time = chunk.elapsedNanos[index]
                let value = chunk.values[index]
                if time >= start && time <= end && value.isFinite && value > 0 {
                    readings.append((time, [pressureHeight(Double(value))]))
                }
            }
        }
        readings.sort { $0.0 < $1.0 }
        return binRuns(bin(readings, start), start, clock) { $0[0] }.map(smooth)
    }

    /// Height in metres for a pressure in hPa, in the standard atmosphere. Only differences are reliable.
    public static func pressureHeight(_ hectopascals: Double) -> Double { 44_330 * (1 - pow(hectopascals / 1_013.25, 1 / 5.255)) }

    static func smooth(_ run: ReplayRun) -> ReplayRun {
        let values = run.values.indices.map { index -> Double in
            let range = max(index - 1, 0)...min(index + 1, run.values.count - 1)
            return range.map { run.values[$0] }.reduce(0, +) / Double(range.count)
        }
        return ReplayRun(times: run.times, values: values, reach: run.reach)
    }

    static func barometerLevel(_ runs: [ReplayRun], _ fixes: [ReplayFix]) -> Double? {
        guard !runs.isEmpty else { return nil }
        let differences = fixes.compactMap { fix -> Double? in
            let sample = fix.sample
            guard sample.source == .gps, sample.usablePosition, let gps = sample.altitudeMeters,
                  (sample.verticalAccuracyMeters ?? .greatestFiniteMagnitude) <= goodVerticalAccuracyMeters,
                  let pressure = runs.lazy.compactMap({ $0.at(fix.timeNanos) }).first else { return nil }
            return gps - pressure
        }.sorted()
        guard !differences.isEmpty else { return nil }
        return differences[differences.count / 2]
    }

    /// Acceleration along the direction of travel, from the accelerometer. The rotation turns each reading into a frame
    /// with a vertical axis but no north. In each 30 second window, the angle that best fits the GPS change of velocity
    /// turns that frame to east and north. The GPS direction of travel then gives the part along the track.
    static func sensorAlongTrack(_ chunks: [SensorChunk], _ samples: [TrackSample], _ start: Int64, _ end: Int64,
                                 _ clock: (Int64) -> Int64) -> [ReplayRun] {
        let rotation = readings(chunks, .rotation, start, end)
        let acceleration = readings(chunks, .acceleration, start, end)
        guard !rotation.isEmpty, !acceleration.isEmpty else { return [] }
        // Horizontal acceleration in the rotation frame, gravity removed.
        var horizontal: [(Int64, [Double])] = []
        var r = 0
        for (time, a) in acceleration {
            while r + 1 < rotation.count && rotation[r + 1].0 <= time { r += 1 }
            var nearest = rotation[r]
            if r + 1 < rotation.count && abs(rotation[r + 1].0 - time) < abs(nearest.0 - time) { nearest = rotation[r + 1] }
            if abs(nearest.0 - time) > 100_000_000 { continue }
            let world = rotate(nearest.1, a)
            horizontal.append((time, [world[0], world[1]]))
        }
        let sensorBins = bin(horizontal, start)
        let gpsBins = gpsAccelerationBins(samples, start)
        guard let angles = alignmentAngles(sensorBins, gpsBins, end - start) else { return [] }
        let moving = samples.filter { $0.source == .gps && ($0.usableSpeed ?? 0) >= 1 && $0.bearingRadians != nil }
        let movingTimes = moving.map(\.elapsedNanos)
        var along: [Int64: [Double]] = [:]
        for (bin, s) in sensorBins {
            guard let direction = travelDirection(moving, movingTimes, start + bin * binNanos + binNanos / 2) else { continue }
            let window = min(max(Int(bin * binNanos / yawWindowNanos), 0), angles.count - 1)
            let angle = angles[window]
            let east = s[0] * cos(angle) - s[1] * sin(angle)
            let north = s[0] * sin(angle) + s[1] * cos(angle)
            along[bin] = [east * direction.0 + north * direction.1]
        }
        return binRuns(along, start, clock) { $0[0] }
    }

    static func readings(_ chunks: [SensorChunk], _ kind: SensorKind, _ start: Int64, _ end: Int64) -> [(Int64, [Float])] {
        var result: [(Int64, [Float])] = []
        for chunk in chunks where chunk.kind == kind {
            for index in 0..<chunk.count {
                let time = chunk.elapsedNanos[index]
                let values = Array(chunk.values[(index * kind.values)..<((index + 1) * kind.values)])
                if time >= start && time <= end && values.prefix(3).allSatisfy({ $0.isFinite }) { result.append((time, values)) }
            }
        }
        // A stable order keeps the first of two readings at the same time first.
        return result.enumerated().sorted { ($0.element.0, $0.offset) < ($1.element.0, $1.offset) }.map(\.element)
    }

    /// Turns `vector` from the phone's axes into the rotation frame with the unit quaternion (x, y, z, w). Removes gravity.
    public static func rotate(_ quaternion: [Float], _ vector: [Float]) -> [Double] {
        let x = Double(quaternion[0])
        let y = Double(quaternion[1])
        let z = Double(quaternion[2])
        let w = quaternion.count > 3 && quaternion[3].isFinite ? Double(quaternion[3]) : sqrt(max(1 - x * x - y * y - z * z, 0))
        let vx = Double(vector[0])
        let vy = Double(vector[1])
        let vz = Double(vector[2])
        // The rotation matrix of a unit quaternion, as Android's getRotationMatrixFromVector.
        return [
            (1 - 2 * (y * y + z * z)) * vx + 2 * (x * y - z * w) * vy + 2 * (x * z + y * w) * vz,
            2 * (x * y + z * w) * vx + (1 - 2 * (x * x + z * z)) * vy + 2 * (y * z - x * w) * vz,
            2 * (x * z - y * w) * vx + 2 * (y * z + x * w) * vy + (1 - 2 * (x * x + y * y)) * vz - gravity,
        ]
    }

    /// GPS change of velocity, east and north, in 1 second bins.
    static func gpsAccelerationBins(_ samples: [TrackSample], _ start: Int64) -> [Int64: [Double]] {
        let velocities: [(Int64, [Double])] = samples.filter { $0.source == .gps }.compactMap { sample in
            guard let speed = sample.usableSpeed else { return nil }
            if speed < 0.5 { return (sample.elapsedNanos, [0, 0]) }
            guard let bearing = sample.bearingRadians else { return nil }
            return (sample.elapsedNanos, [speed * sin(bearing), speed * cos(bearing)])
        }
        var steps: [(Int64, [Double])] = []
        for index in velocities.indices.dropFirst() {
            let a = velocities[index - 1]
            let b = velocities[index]
            let seconds = Double(b.0 - a.0) / 1e9
            if seconds <= 0 || seconds > 3 { continue }
            steps.append(((a.0 + b.0) / 2, [(b.1[0] - a.1[0]) / seconds, (b.1[1] - a.1[1]) / seconds]))
        }
        return bin(steps, start)
    }

    /// The angle for each 30 second window that turns the rotation frame to east and north. A window with too little GPS
    /// change of velocity uses the nearest window that has enough. Nil when no window has enough.
    static func alignmentAngles(_ sensor: [Int64: [Double]], _ gps: [Int64: [Double]], _ durationNanos: Int64) -> [Double]? {
        let windows = Int(durationNanos / yawWindowNanos + 1)
        var angles = [Double?](repeating: nil, count: windows)
        var sums = [(cross: Double, dot: Double, signal: Double)](repeating: (0, 0, 0), count: windows)
        for bin in gps.keys.sorted() {
            let window = Int(bin * binNanos / yawWindowNanos)
            guard window >= 0 && window < windows, let s = sensor[bin], let g = gps[bin] else { continue }
            sums[window].cross += s[0] * g[1] - s[1] * g[0]
            sums[window].dot += s[0] * g[0] + s[1] * g[1]
            sums[window].signal += hypot(g[0], g[1])
        }
        for window in 0..<windows where sums[window].signal >= minAlignmentSignal {
            angles[window] = atan2(sums[window].cross, sums[window].dot)
        }
        let known = angles.indices.filter { angles[$0] != nil }
        guard !known.isEmpty else { return nil }
        return (0..<windows).map { window in
            angles[window] ?? angles[known.min { abs($0 - window) < abs($1 - window) }!]!
        }
    }

    /// The unit direction of travel (east, north) from the nearest moving GPS fix within 5 seconds.
    static func travelDirection(_ moving: [TrackSample], _ times: [Int64], _ time: Int64) -> (Double, Double)? {
        guard !times.isEmpty else { return nil }
        let insert = lowerBound(times, time)
        var candidates: [Int] = []
        if insert - 1 >= 0 { candidates.append(insert - 1) }
        if insert < times.count { candidates.append(insert) }
        let nearest = candidates.min { abs(times[$0] - time) < abs(times[$1] - time) }!
        guard abs(times[nearest] - time) <= 5_000_000_000, let bearing = moving[nearest].bearingRadians else { return nil }
        return (sin(bearing), cos(bearing))
    }

    /// Acceleration from the change of GPS speed, for trips without the motion sensors.
    static func gpsAlongTrack(_ samples: [TrackSample], _ start: Int64, _ clock: (Int64) -> Int64) -> [ReplayRun] {
        let speeds: [(Int64, Double)] = samples.filter { $0.source == .gps }.compactMap { s in s.usableSpeed.map { (s.elapsedNanos, $0) } }
        var steps: [(Int64, [Double])] = []
        for index in speeds.indices.dropFirst() {
            let a = speeds[index - 1]
            let b = speeds[index]
            let seconds = Double(b.0 - a.0) / 1e9
            if seconds <= 0 || seconds > 3 { continue }
            steps.append(((a.0 + b.0) / 2, [(b.1 - a.1) / seconds]))
        }
        return binRuns(bin(steps, start), start, clock) { $0[0] }
    }

    /// Means in 1 second bins after `start`. The key is the bin number.
    static func bin(_ values: [(Int64, [Double])], _ start: Int64) -> [Int64: [Double]] {
        var sums: [Int64: ([Double], Int)] = [:]
        for (time, value) in values {
            let offset = time - start
            let key = offset >= 0 ? offset / binNanos : (offset - binNanos + 1) / binNanos
            var entry = sums[key] ?? ([Double](repeating: 0, count: value.count), 0)
            for index in value.indices { entry.0[index] += value[index] }
            entry.1 += 1
            sums[key] = entry
        }
        return sums.mapValues { entry in entry.0.map { $0 / Double(entry.1) } }
    }

    /// Runs of bins at their centre times. `origin` is the fix time of bin 0. A missing bin for more than 2 seconds ends a run.
    static func binRuns(_ bins: [Int64: [Double]], _ origin: Int64, _ clock: (Int64) -> Int64, _ value: ([Double]) -> Double) -> [ReplayRun] {
        guard !bins.isEmpty else { return [] }
        var result: [ReplayRun] = []
        var times: [Int64] = []
        var values: [Double] = []
        var previous: Int64?
        for bin in bins.keys.sorted() {
            if let previous, (bin - previous) * binNanos > sensorGapNanos {
                result.append(ReplayRun(times: times, values: values, reach: binNanos / 2))
                times = []
                values = []
            }
            times.append(clock(origin + bin * binNanos + binNanos / 2))
            values.append(value(bins[bin]!))
            previous = bin
        }
        result.append(ReplayRun(times: times, values: values, reach: binNanos / 2))
        return result
    }

    static func moments(_ replay: TripReplay) -> [ReplayMoment] {
        func extreme(_ runs: [ReplayRun], highest: Bool) -> (Int64, Double)? {
            let points = runs.flatMap { run in run.times.indices.map { (run.times[$0], run.values[$0]) } }
            return highest ? points.max { $0.1 < $1.1 } : points.min { $0.1 < $1.1 }
        }
        var found: [(MomentKind, (Int64, Double))] = []
        if let high = extreme(replay.acceleration, highest: true), high.1 >= minMomentAcceleration { found.append((.acceleration, high)) }
        if let low = extreme(replay.acceleration, highest: false), low.1 <= -minMomentAcceleration { found.append((.braking, low)) }
        if let fastest = extreme(replay.speed, highest: true), fastest.1 > 0 { found.append((.fastestSpeed, fastest)) }
        if let highest = extreme(replay.height, highest: true), let lowest = extreme(replay.height, highest: false),
           highest.1 - lowest.1 >= minHeightRangeMeters {
            found.append((.highestPoint, highest))
        }
        let ordered = found.enumerated().sorted { ($0.element.1.0, $0.offset) < ($1.element.1.0, $1.offset) }.map(\.element)
        return ordered.enumerated().map { index, entry in
            let values = replay.valuesAt(entry.1.0)
            return ReplayMoment(number: index + 1, kind: entry.0, timeNanos: entry.1.0, value: entry.1.1,
                                latitudeRadians: values.latitudeRadians, longitudeRadians: values.longitudeRadians)
        }
    }
}

/// The lowest and highest value of one chart column.
public struct ChartColumn: Hashable, Sendable {
    public var index: Int
    public var minimum: Double
    public var maximum: Double

    public init(index: Int, minimum: Double, maximum: Double) {
        self.index = index
        self.minimum = minimum
        self.maximum = maximum
    }
}

/// Chart values reduced to one minimum and one maximum for each column, so a long trip with many fixes draws as fast as a
/// short one, and a short peak stays visible. Each run stays a separate line, so a gap is not joined.
public enum ReplayChartData {
    public static func columns(_ runs: [ReplayRun], durationNanos: Int64, columns: Int) -> [[ChartColumn]] {
        guard columns > 0, durationNanos > 0 else { return [] }
        return runs.compactMap { run in
            var result: [ChartColumn] = []
            var index = -1
            var minimum = 0.0
            var maximum = 0.0
            for i in run.times.indices {
                let value = run.values[i]
                if !value.isFinite { continue }
                let column = min(max(Int(Double(run.times[i]) / Double(durationNanos) * Double(columns)), 0), columns - 1)
                if column != index {
                    if index >= 0 { result.append(ChartColumn(index: index, minimum: minimum, maximum: maximum)) }
                    index = column
                    minimum = value
                    maximum = value
                } else {
                    minimum = min(minimum, value)
                    maximum = max(maximum, value)
                }
            }
            if index >= 0 { result.append(ChartColumn(index: index, minimum: minimum, maximum: maximum)) }
            return result.isEmpty ? nil : result
        }
    }

    /// Replay time ranges inside `0...durationNanos` that no run covers, for example a gap in the sensor record.
    public static func uncovered(_ runs: [ReplayRun], durationNanos: Int64) -> [ClosedRange<Int64>] {
        let covered = runs.compactMap { run -> ClosedRange<Int64>? in
            guard let first = run.times.first, let last = run.times.last else { return nil }
            let lower = max(first - run.reach, 0)
            let upper = min(last + run.reach, durationNanos)
            return lower <= upper ? lower...upper : nil
        }.sorted { $0.lowerBound < $1.lowerBound }
        var result: [ClosedRange<Int64>] = []
        var from: Int64 = 0
        for range in covered {
            if range.lowerBound > from { result.append(from...range.lowerBound) }
            from = max(from, range.upperBound)
        }
        if from < durationNanos { result.append(from...durationNanos) }
        return result
    }
}
