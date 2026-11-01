import CoreLocation
import CVKEGPSCore
import MapKit
import Observation
import SwiftUI

/// One drawn route line. `approximate` is a line of network positions. `positions` are the speed colour positions of
/// the points when the route shows speed colours. `fractions` are the distances of the points along the line, from 0 to 1,
/// because MapKit places gradient colours by distance along a polyline.
struct DrawnLine: Identifiable, Sendable {
    var id: Int
    var coordinates: [CLLocationCoordinate2D]
    var approximate: Bool
    var positions: [Double?]?
    var cumulative: [Double]

    /// The colours of points `from...to`, with `alpha`. Each run of one colour gets two stops, so a colour change between
    /// a known and a missing speed has a hard edge, as on Android.
    func gradient(from: Int, to: Int, alpha: Double) -> Gradient? {
        guard let positions, to > from else { return nil }
        let start = cumulative[from]
        let length = cumulative[to] - start
        func location(_ index: Int) -> Double {
            length > 0 ? (cumulative[index] - start) / length : Double(index - from) / Double(to - from)
        }
        var stops: [Gradient.Stop] = []
        var index = from
        for run in SpeedColours.runs(Array(positions[from...to])) {
            let end = index + run.segments
            stops.append(Gradient.Stop(color: Palette.speed(run.from).opacity(alpha), location: location(index)))
            stops.append(Gradient.Stop(color: Palette.speed(run.to).opacity(alpha), location: location(end)))
            index = end
        }
        return stops.isEmpty ? nil : Gradient(stops: stops)
    }
}

/// The drawn route of a map, built off the main thread. A live route builds at most every 5 seconds, so a long trip does
/// not rebuild the line for each fix. The position marker still moves with each fix.
@MainActor
@Observable
final class MapRoute {
    private(set) var lines: [DrawnLine] = []
    private(set) var scale: SpeedScale?
    /// Changes after each build, so the map can fit the route once.
    private(set) var builds = 0

    @ObservationIgnored private var pending: Task<Void, Never>?
    @ObservationIgnored private var lastBuild = Date.distantPast
    @ObservationIgnored private var latest: (lines: [[TrackSample]], bySpeed: Bool) = ([], false)
    static let liveIntervalSeconds = 5.0

    func set(_ lines: [[TrackSample]], bySpeed: Bool, throttled: Bool) {
        let changed = latest.bySpeed != bySpeed
        latest = (lines, bySpeed)
        if changed { pending?.cancel(); pending = nil }
        guard pending == nil else { return }
        let wait = throttled ? max(0, Self.liveIntervalSeconds - Date.now.timeIntervalSince(lastBuild)) : 0
        pending = Task {
            if wait > 0 { try? await Task.sleep(for: .seconds(wait)) }
            guard !Task.isCancelled else { return }
            let (lines, bySpeed) = latest
            let built = await Self.build(lines, bySpeed: bySpeed)
            guard !Task.isCancelled else { return }
            self.lines = built.lines
            scale = built.scale
            builds += 1
            lastBuild = .now
            pending = nil
            // Points that arrived during the build show at the next build.
            if latest.lines != lines { set(latest.lines, bySpeed: latest.bySpeed, throttled: throttled) }
        }
    }

    @concurrent private static func build(_ lines: [[TrackSample]], bySpeed: Bool) async -> (lines: [DrawnLine], scale: SpeedScale?) {
        // One scale for all lines of the trip, so a colour means the same speed on each line.
        let speeds = bySpeed ? lines.map(SpeedColours.smoothedSpeeds) : nil
        let scale = speeds.flatMap(SpeedColours.scale)
        var drawn: [DrawnLine] = []
        for (index, line) in lines.enumerated() where line.count > 1 {
            let coordinates = line.map { CLLocationCoordinate2D(latitude: $0.latitudeDegrees, longitude: $0.longitudeDegrees) }
            var cumulative = [0.0]
            cumulative.reserveCapacity(coordinates.count)
            for i in coordinates.indices.dropFirst() {
                let a = MKMapPoint(coordinates[i - 1])
                let b = MKMapPoint(coordinates[i])
                cumulative.append(cumulative[i - 1] + hypot(b.x - a.x, b.y - a.y))
            }
            let approximate = line[0].source == .network
            let positions: [Double?]? = if let scale, let speeds, !approximate { speeds[index].map { SpeedColours.position($0, scale) } } else { nil }
            drawn.append(DrawnLine(id: index, coordinates: coordinates, approximate: approximate, positions: positions, cumulative: cumulative))
        }
        return (drawn, scale)
    }
}
