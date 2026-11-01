import Foundation
import Testing
@testable import CVKEGPSCore

/// The same cases as the Android RouteMathTest, SpeedColoursTest, GpxTest, and SensorRecordTest.
struct RouteMathTests {
    func sample(_ seconds: Int64, longitude: Double = 0, speed: Double = 10, accuracy: Double = 2, source: FixSource = .gps) -> TrackSample {
        TrackSample(latitudeRadians: 0, longitudeRadians: longitude * .pi / 180, timeMillis: 1_000 + seconds * 1_000,
                    elapsedNanos: seconds * 1_000_000_000, receivedElapsedNanos: seconds * 1_000_000_000, accuracyMeters: accuracy,
                    speedMetersPerSecond: speed, speedAccuracyMetersPerSecond: 0.1, source: source)
    }

    @Test func networkPositionsFormTheirOwnLineWithoutSpeedAndWithoutDistanceAcrossTheChange() {
        let result = RouteMath.summarize([[sample(1), sample(2, longitude: 0.001), sample(15, longitude: 0.002, source: .network),
                                           sample(20, longitude: 0.003, source: .network), sample(25, longitude: 0.004),
                                           sample(26, longitude: 0.005)]])
        #expect(result.lines.map { $0[0].source } == [.gps, .network, .gps])
        #expect(result.lines.map(\.count) == [2, 2, 2])
        #expect(abs(result.distanceMeters - 3 * 111.195) < 0.1)
        #expect(result.gapCount == 1)
        #expect(result.gapNanos == 13_000_000_000)
        #expect(result.maximumSpeedMetersPerSecond == 10)
        #expect(RouteMath.summarize([[sample(1, speed: 90, source: .network)]]).maximumSpeedMetersPerSecond == nil)
    }

    @Test func distanceHandlesTheDateLineAndStationaryNoise() {
        #expect(abs(RouteMath.distance(sample(1, longitude: 179.999), sample(2, longitude: -179.999)) - 222.39) < 0.1)
        let stopped = RouteMath.summarize([[sample(1, speed: 0), sample(2, longitude: 0.0001, speed: 0)]])
        #expect(stopped.distanceMeters == 0)
    }

    @Test func lateAndDuplicatePointsDoNotBridgePausesPoorFixesOrSignalGaps() {
        let a = sample(1)
        let b = sample(2, longitude: 0.001)
        let poor = sample(3, longitude: 1, accuracy: 200)
        let c = sample(4, longitude: 0.002)
        let late = sample(20, longitude: 0.003)
        let result = RouteMath.summarize([[b, a, b, poor, c, late], [sample(30, longitude: 4)]])
        #expect(abs(result.distanceMeters - 111.195) < 0.1)
        #expect(result.gapCount == 2)
        #expect(result.lines.count == 4)
        #expect(result.gapNanos == 16_000_000_000)
    }

    @Test func liveRouteAddsPointsWithTheSavedRouteRules() {
        let first = [sample(1), sample(2, longitude: 0.001), sample(3, longitude: 1, accuracy: 200), sample(4, longitude: 0.002),
                     sample(20, longitude: 0.003)]
        let second = [sample(30, longitude: 4), sample(31, longitude: 4.001, speed: 25)]
        let live = LiveRoute(tripId: "trip")
        var shown: [RouteSummary] = []
        for point in first.map({ TripPoint(tripId: "trip", segmentId: "a", sample: $0) }) +
            second.map({ TripPoint(tripId: "trip", segmentId: "b", sample: $0) }) {
            #expect(live.add(point))
            shown.append(live.summary())
        }
        #expect(live.summary() == RouteMath.summarize([first, second]))
        // A summary that a screen holds does not change when later points arrive.
        #expect(shown[1] == RouteMath.summarize([Array(first.prefix(2))]))
        // A repeated point changes nothing. An older point needs a new load.
        #expect(live.add(TripPoint(tripId: "trip", segmentId: "b", sample: second[1])))
        #expect(!live.add(TripPoint(tripId: "trip", segmentId: "b", sample: sample(30, longitude: 4))))
        #expect(!live.add(TripPoint(tripId: "trip", segmentId: "a", sample: sample(21, longitude: 0.004))))
    }

    @Test func frameFitsTheRouteTakesTheShortWayAcrossTheDateLineAndKeepsAStreetScale() throws {
        let wide = try #require(RouteMath.frame([sample(1, longitude: 0), sample(2, longitude: 1)], width: 360, height: 2_000))
        #expect(abs(wide.zoom - log2(360.0 * 360 / 256)) < 0.001)
        #expect(abs(wide.longitudeRadians - 0.5 * .pi / 180) < 1e-9)
        let dateLine = try #require(RouteMath.frame([sample(1, longitude: 179.5), sample(2, longitude: -179.5)], width: 360, height: 2_000))
        #expect(abs(dateLine.zoom - wide.zoom) < 0.001)
        #expect(abs(abs(dateLine.longitudeRadians) - .pi) < 1e-9)
        #expect(RouteMath.frame([sample(1, longitude: 0), sample(2, longitude: 0.00001)], width: 360, height: 640)?.zoom == 17)
        #expect(RouteMath.frame([sample(1, longitude: 0.2), sample(2, longitude: 0.2)], width: 360, height: 640)?.zoom == 14)
        #expect(RouteMath.frame([], width: 360, height: 640) == nil)
    }

    @Test func averageSpeedUsesRecordingTimeAndNeedsAKnownDistance() {
        #expect(averageSpeed(distanceMeters: 300, durationNanos: 60_000_000_000) == 5)
        #expect(averageSpeed(distanceMeters: 0, durationNanos: 60_000_000_000) == 0)
        #expect(averageSpeed(distanceMeters: nil, durationNanos: 60_000_000_000) == nil)
        #expect(averageSpeed(distanceMeters: 10, durationNanos: 999_999_999) == nil)
    }

    @Test func invalidTimesAndCoordinatesAreRejectedWithoutCappingPlaneSpeed() {
        let plane = sample(1, speed: 250)
        #expect(plane.usableSpeed == 250)
        var bad = plane
        bad.latitudeRadians = .nan
        #expect(!bad.isValid)
        bad = plane
        bad.elapsedNanos = plane.receivedElapsedNanos + 1
        #expect(!bad.isValid)
        bad = plane
        bad.timeMillis = 0
        #expect(!bad.isValid)
    }
}

struct SpeedColourTests {
    let second: Int64 = 1_000_000_000

    func fix(_ seconds: Double, _ speed: Double?, speedAccuracy: Double = 0.3) -> TrackSample {
        TrackSample(latitudeRadians: 0, longitudeRadians: 0, timeMillis: 1_000 + Int64(seconds * 1_000), elapsedNanos: Int64(seconds * 1e9),
                    receivedElapsedNanos: Int64(seconds * 1e9), accuracyMeters: 3, speedMetersPerSecond: speed,
                    speedAccuracyMetersPerSecond: speedAccuracy, altitudeMeters: 100, verticalAccuracyMeters: 3)
    }

    @Test func smoothingAveragesFiveSecondsAndSkipsSpeedsThatAreNotUsable() {
        let line = [fix(0, 10), fix(1, 12), fix(2, 14), fix(3, 40, speedAccuracy: 5), fix(10, nil), fix(11, 20)]
        let speeds = SpeedColours.smoothedSpeeds(line)
        #expect(abs(speeds[0]! - 12) < 0.001)
        #expect(abs(speeds[1]! - 12) < 0.001)
        #expect(abs(speeds[3]! - 13) < 0.001)
        #expect(abs(speeds[4]! - 20) < 0.001)
        #expect(SpeedColours.smoothedSpeeds([fix(0, nil)]) == [nil])
    }

    @Test func theScaleIgnoresStopsAndExtremesAndHasAMinimumRange() throws {
        let moving: [Double?] = (0..<98).map { index -> Double? in 5 + Double(index) * 20 / 97 }
        let speeds: [Double?] = [0, 0, 0.2] + moving + [90]
        let scale = try #require(SpeedColours.scale([speeds]))
        #expect(abs(scale.slowMetersPerSecond - 6) < 0.5)
        #expect(abs(scale.fastMetersPerSecond - 24.2) < 0.5)
        #expect(SpeedColours.scale([[10, 10, 10]]) == SpeedScale(slowMetersPerSecond: 9, fastMetersPerSecond: 11))
        let low = try #require(SpeedColours.scale([[0.6, 0.6]]))
        #expect(low.slowMetersPerSecond == 0 && abs(low.fastMetersPerSecond - 2) < 1e-9)
        #expect(SpeedColours.scale([[0, nil, 0.1]]) == nil)
    }

    @Test func runsJoinEvenColoursAndKeepMissingSpeedsApart() {
        let scale = SpeedScale(slowMetersPerSecond: 10, fastMetersPerSecond: 20)
        #expect(SpeedColours.position(2, scale) == 0)
        #expect(SpeedColours.position(30, scale) == 1)
        #expect(SpeedColours.position(15.1, scale) == 0.5)
        let positions: [Double?] = [0.5, 0.5, 0.5, 0.75, nil, nil, 1, 1]
        #expect(SpeedColours.runs(positions) == [SpeedRun(segments: 2, from: 0.5, to: 0.5), SpeedRun(segments: 1, from: 0.5, to: 0.75),
                                                 SpeedRun(segments: 3, from: nil, to: nil), SpeedRun(segments: 1, from: 1, to: 1)])
        #expect(SpeedColours.runs(positions).map(\.segments).reduce(0, +) == positions.count - 1)
    }

    @Test func theRampEndsAtViridisDarkAndYellow() {
        let slow = SpeedRamp.at(0)
        let fast = SpeedRamp.at(1)
        #expect(abs(slow.red - 0x44 / 255.0) < 1e-9 && abs(slow.blue - 0x54 / 255.0) < 1e-9)
        #expect(abs(fast.red - 0xFD / 255.0) < 1e-9 && abs(fast.green - 0xE7 / 255.0) < 1e-9)
    }
}

struct GpxTests {
    func sample(_ latitude: Double, _ longitude: Double, _ time: Int64, source: FixSource = .gps) -> TrackSample {
        TrackSample(latitudeRadians: latitude * .pi / 180, longitudeRadians: longitude * .pi / 180, timeMillis: time,
                    elapsedNanos: time * 1_000_000, receivedElapsedNanos: time * 1_000_000, accuracyMeters: 3, speedMetersPerSecond: 10,
                    speedAccuracyMetersPerSecond: 0.2, altitudeMeters: 100, verticalAccuracyMeters: 4, source: source)
    }

    @Test func gapsStaySeparateAndNumbersUseADecimalPoint() {
        let gpx = Gpx.write(name: "Trip <1> & \"A\"", lines: [
            [sample(49.2827, -123.1207, 1_000), sample(49.2828, -123.1206, 2_000)],
            [sample(49.29, -123.11, 20_000)],
        ])
        #expect(gpx.components(separatedBy: "<trkseg>").count - 1 == 2)
        #expect(gpx.components(separatedBy: "<trkpt ").count - 1 == 3)
        #expect(gpx.contains("<trkpt lat=\"49.2827000\" lon=\"-123.1207000\"><time>1970-01-01T00:00:01Z</time></trkpt>"))
        #expect(gpx.contains("<name>Trip &lt;1&gt; &amp; &quot;A&quot;</name>"))
        // The recorded altitude is ellipsoidal, so it is not written as a GPX elevation.
        #expect(!gpx.contains("<ele>"))
        #expect(!gpx.contains("<src>"))
    }

    @Test func aNetworkPositionNamesItsSourceAndMillisecondsShow() {
        let gpx = Gpx.write(name: "Trip", lines: [[sample(49.2827, -123.1207, 1_500, source: .network)]])
        #expect(gpx.contains("<time>1970-01-01T00:00:01.500Z</time><src>network</src></trkpt>"))
        #expect(Gpx.utc(1_792_764_000_000) == "2026-10-23T14:00:00Z")
        #expect(Gpx.utc(951_782_400_000) == "2000-02-29T00:00:00Z")
    }

    @Test func fileNameUsesTheLocalStartTime() {
        #expect(Gpx.fileName(startedMillis: 1_792_764_000_000, timeZone: TimeZone(identifier: "UTC")!) == "cvke-trip-2026-10-23-1400.gpx")
    }
}

struct SensorRecordTests {
    @Test func storedReadingsReadBackWithTheirExactTimesAndValues() throws {
        let chunk = try #require(SensorChunk(kind: .rotation, elapsedNanos: [1, .max], values: [0.1, -0.2, 0.3, 0.9, .nan, 0, -0.0, 1]))
        let read = try #require(SensorChunk.decode(.rotation, chunk.encode()))
        #expect(read.count == 2)
        #expect(read.elapsedNanos == chunk.elapsedNanos)
        #expect(read.values.map(\.bitPattern) == chunk.values.map(\.bitPattern))
    }

    @Test func dataWithoutWholeReadingsIsNotRead() throws {
        let pressure = try #require(SensorChunk(kind: .pressure, elapsedNanos: [5], values: [1013.25])).encode()
        #expect(SensorChunk.decode(.pressure, pressure.dropLast()) == nil)
        #expect(SensorChunk.decode(.pressure, Data()) == nil)
        // The data of one sensor kind does not read as another kind.
        #expect(SensorChunk.decode(.acceleration, pressure) == nil)
        #expect(SensorChunk(kind: .pressure, elapsedNanos: [1], values: [1, 2]) == nil)
    }
}
