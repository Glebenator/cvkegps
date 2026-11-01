import Foundation

/// Distance, gaps, maximum speed, and the route lines of a trip. Lines never join two segments or a gap.
public struct RouteSummary: Hashable, Sendable {
    public var distanceMeters: Double = 0
    public var maximumSpeedMetersPerSecond: Double?
    public var gapCount: Int = 0
    public var gapNanos: Int64 = 0
    public var lines: [[TrackSample]] = []

    public init(distanceMeters: Double = 0, maximumSpeedMetersPerSecond: Double? = nil, gapCount: Int = 0, gapNanos: Int64 = 0,
                lines: [[TrackSample]] = []) {
        self.distanceMeters = distanceMeters
        self.maximumSpeedMetersPerSecond = maximumSpeedMetersPerSecond
        self.gapCount = gapCount
        self.gapNanos = gapNanos
        self.lines = lines
    }
}

/// A map camera that shows a whole route: the centre in radians and a Web Mercator zoom level.
public struct RouteFrame: Hashable, Sendable {
    public var latitudeRadians: Double
    public var longitudeRadians: Double
    public var zoom: Double
}

public enum RouteMath {
    public static let signalGapNanos: Int64 = 10_000_000_000

    /// A position is current for `signalGapNanos` after its own fix time. A later fix of another sample does not extend it.
    public static func positionCurrent(fixNanos: Int64, nowNanos: Int64) -> Bool {
        let age = nowNanos - fixNanos
        return age >= 0 && age < signalGapNanos
    }

    public static func distance(_ a: TrackSample, _ b: TrackSample) -> Double {
        let lat = sin((b.latitudeRadians - a.latitudeRadians) / 2)
        let lon = sin((b.longitudeRadians - a.longitudeRadians) / 2)
        let h = lat * lat + cos(a.latitudeRadians) * cos(b.latitudeRadians) * lon * lon
        return 2 * 6_371_008.8 * asin(sqrt(min(max(h, 0), 1)))
    }

    static let streetZoom = 17.0
    static let pointZoom = 14.0
    static let minZoom = 3.0
    static let maxLatitude = 1.4844222297453324

    /// The camera that shows all `points` in a visible map area of `width` by `height` points. At zoom 0 the world is
    /// 256 points wide. A route across the date line uses the shorter way round. A short route stops at a street scale,
    /// and one point uses a town scale.
    public static func frame(_ points: [TrackSample], width: Double, height: Double) -> RouteFrame? {
        guard !points.isEmpty else { return nil }
        let south = mercator(points.map(\.latitudeRadians).min()!)
        let north = mercator(points.map(\.latitudeRadians).max()!)
        // On the longitude circle, the largest gap between two neighbouring points is the part that the route does not cover.
        let longitudes = points.map(\.longitudeRadians).sorted()
        var gap = longitudes.first! + 2 * .pi - longitudes.last!
        var west = longitudes.first!
        for index in longitudes.indices.dropFirst() {
            let step = longitudes[index] - longitudes[index - 1]
            if step > gap { gap = step; west = longitudes[index] }
        }
        let span = 2 * .pi - gap
        let east = west + span
        let longitude = (west + east) / 2
        let latitude = 2 * atan(exp((south + north) / 2)) - .pi / 2
        let centre = longitude - 2 * .pi * floor((longitude + .pi) / (2 * .pi))
        if span <= 0 && north <= south { return RouteFrame(latitudeRadians: latitude, longitudeRadians: centre, zoom: pointZoom) }
        let zoom = min(zoomFor(span, width), zoomFor(north - south, height))
        return RouteFrame(latitudeRadians: latitude, longitudeRadians: centre, zoom: min(max(zoom, minZoom), streetZoom))
    }

    /// Web Mercator y. The map ends at about 85 degrees north and south.
    static func mercator(_ latitude: Double) -> Double {
        log(tan(.pi / 4 + min(max(latitude, -maxLatitude), maxLatitude) / 2))
    }

    static func zoomFor(_ span: Double, _ size: Double) -> Double {
        span <= 0 ? .infinity : log2(size * 2 * .pi / (256 * span))
    }

    /// Valid samples of one segment, one for each fix time, in time order.
    public static func ordered(_ segment: [TrackSample]) -> [TrackSample] {
        var seen = Set<Int64>()
        // The first sample of a repeated time stays, as in the saved route.
        return segment.filter { $0.isValid && seen.insert($0.elapsedNanos).inserted }.sorted { $0.elapsedNanos < $1.elapsedNanos }
    }

    /// Each supplied segment is a separate recording interval. Never connect two segments.
    public static func summarize(_ segments: [[TrackSample]]) -> RouteSummary {
        let route = RouteBuilder()
        for segment in segments {
            route.newSegment()
            for sample in ordered(segment) { route.add(sample) }
        }
        return route.summary()
    }
}

/// Builds a `RouteSummary` sample by sample. `RouteMath.summarize` uses it, so a live route and a saved route use the same rules.
/// Samples must be valid and in time order within each segment.
public final class RouteBuilder {
    private var closedDistance = 0.0
    private var maximum: Double?
    private var gaps = 0
    private var missingNanos: Int64 = 0
    private var closedLines: [[TrackSample]] = []
    private var track: SegmentTrack?
    private var line: [TrackSample] = []

    public init() {}

    /// Time of the newest sample in the open segment. Nil before its first sample.
    public var lastElapsedNanos: Int64? { track?.lastElapsedNanos }

    /// Starts a new recording interval. Its first sample starts a new line.
    public func newSegment() {
        if let track { closedDistance += track.distanceMeters }
        closeLine()
        track = SegmentTrack()
    }

    public func add(_ sample: TrackSample) {
        let open = track ?? SegmentTrack()
        track = open
        if let speed = sample.usableSpeed { maximum = max(maximum ?? speed, speed) }
        let step = open.add(sample)
        if step.gapNanos > 0 {
            gaps += 1
            missingNanos += step.gapNanos
        }
        if step.poorGap { gaps += 1 }
        if step.gapNanos > 0 || !step.joined || step.sourceChanged { closeLine() }
        if step.joined { line.append(sample) }
    }

    /// A copy. Later samples do not change a summary that a screen already shows.
    public func summary() -> RouteSummary {
        RouteSummary(distanceMeters: closedDistance + (track?.distanceMeters ?? 0), maximumSpeedMetersPerSecond: maximum,
                     gapCount: gaps, gapNanos: missingNanos, lines: line.isEmpty ? closedLines : closedLines + [line])
    }

    private func closeLine() {
        if !line.isEmpty { closedLines.append(line) }
        line = []
    }
}

/// The route of the recording trip, added point by point while a screen shows it.
public final class LiveRoute {
    public let tripId: String
    private let route = RouteBuilder()
    private var segments = Set<String>()
    private var segmentId: String?

    public init(tripId: String) { self.tripId = tripId }

    /// Adds one saved point. A repeated point changes nothing. Returns false for a point that is older than the route,
    /// or that belongs to an earlier segment: the caller must then load the route again.
    @discardableResult
    public func add(_ point: TripPoint) -> Bool {
        if point.segmentId != segmentId {
            guard segments.insert(point.segmentId).inserted else { return false }
            segmentId = point.segmentId
            route.newSegment()
        }
        if let last = route.lastElapsedNanos, point.sample.elapsedNanos <= last { return point.sample.elapsedNanos == last }
        if point.sample.isValid { route.add(point.sample) }
        return true
    }

    public func summary() -> RouteSummary { route.summary() }
}

/// One recording segment, sample by sample in time order. Trip summaries and the recorder use the same rules,
/// so the distance in the trip list agrees with the trip details.
/// A line contains fixes of one `FixSource`. The step between two sources adds no distance.
public final class SegmentTrack {
    /// One sample's effect: the signal gap before it, the start of a poor-position gap, whether it joins the line,
    /// and whether it starts a line of another source.
    public struct Step: Hashable {
        public var gapNanos: Int64
        public var poorGap: Bool
        public var joined: Bool
        public var sourceChanged = false
    }

    public private(set) var distanceMeters = 0.0
    public private(set) var lastElapsedNanos: Int64?
    private var previous: TrackSample?
    private var poorFix = false

    public init() {}

    public func add(_ sample: TrackSample) -> Step {
        var gapNanos: Int64 = 0
        if let last = lastElapsedNanos, sample.elapsedNanos - last >= RouteMath.signalGapNanos { gapNanos = sample.elapsedNanos - last }
        lastElapsedNanos = sample.elapsedNanos
        if gapNanos > 0 { previous = nil }
        if !sample.usablePosition {
            let poorGap = !poorFix
            poorFix = true
            previous = nil
            return Step(gapNanos: gapNanos, poorGap: poorGap, joined: false)
        }
        poorFix = false
        let sourceChanged = previous.map { $0.source != sample.source } ?? false
        if let last = previous, !sourceChanged {
            // Do not accumulate position noise while reported speed is zero.
            if sample.usableSpeed != 0 || last.usableSpeed != 0 { distanceMeters += RouteMath.distance(last, sample) }
        }
        previous = sample
        return Step(gapNanos: gapNanos, poorGap: false, joined: true, sourceChanged: sourceChanged)
    }
}

/// Distance divided by recording time. Recording time includes stops, because the app does not measure moving time.
public func averageSpeed(distanceMeters: Double?, durationNanos: Int64) -> Double? {
    guard let distance = distanceMeters, durationNanos >= 1_000_000_000 else { return nil }
    return distance / (Double(durationNanos) / 1e9)
}
