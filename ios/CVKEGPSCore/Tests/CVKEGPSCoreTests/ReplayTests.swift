import Foundation
import Testing
@testable import CVKEGPSCore

/// The same cases as the Android TripReplayTest and ReplayChartDataTest.
struct ReplayTests {
    let second: Int64 = 1_000_000_000

    /// A fix moving east at `speed` m/s, `east` metres from the start.
    func fix(_ seconds: Double, _ speed: Double?, east: Double = 0, altitude: Double? = 100, accuracy: Double = 3,
             source: FixSource = .gps, bearing: Double = .pi / 2) -> TrackSample {
        TrackSample(latitudeRadians: 0, longitudeRadians: east / 6_371_008.8, timeMillis: 1_000 + Int64(seconds * 1_000),
                    elapsedNanos: Int64(seconds * 1e9), receivedElapsedNanos: Int64(seconds * 1e9), accuracyMeters: accuracy,
                    speedMetersPerSecond: speed, speedAccuracyMetersPerSecond: 0.3, altitudeMeters: altitude, verticalAccuracyMeters: 3,
                    bearingRadians: bearing, bearingAccuracyRadians: 0.05, source: source)
    }

    @Test func theReplayClockJoinsSegmentsAndAnOldFixIsNotCurrent() throws {
        let first = ReplaySegment(startedElapsedNanos: 10 * second, endedElapsedNanos: 40 * second, samples: [fix(10, 5), fix(11, 6)], sensors: [])
        let next = ReplaySegment(startedElapsedNanos: 100 * second, endedElapsedNanos: 130 * second, samples: [fix(100, 7), fix(101, 8)], sensors: [])
        let replay = ReplayAnalysis.analyze([first, next])
        #expect(replay.durationNanos == 60 * second)
        #expect(replay.segmentStarts == [30 * second])
        #expect(replay.valuesAt(1 * second).speedMetersPerSecond == 6)
        #expect(replay.valuesAt(11 * second - 1).speedMetersPerSecond == 6)
        #expect(replay.valuesAt(11 * second).speedMetersPerSecond == nil)
        #expect(replay.valuesAt(11 * second).latitudeRadians == nil)
        #expect(replay.valuesAt(30 * second).speedMetersPerSecond == 7)
        #expect(replay.gpsGaps == [(11 * second)...(30 * second), (41 * second)...(60 * second)])
        let fastest = try #require(replay.moments.first { $0.kind == .fastestSpeed })
        #expect(fastest.value == 8)
        #expect(fastest.timeNanos == 31 * second)
    }

    @Test func theMapKnowsThePlayedPartOfEachRouteLine() {
        let first = ReplaySegment(startedElapsedNanos: 10 * second, endedElapsedNanos: 20 * second,
                                  samples: [fix(10, 10, east: 0), fix(11, 10, east: 10), fix(12, 10, east: 20)], sensors: [])
        let next = ReplaySegment(startedElapsedNanos: 100 * second, endedElapsedNanos: 110 * second,
                                 samples: [fix(100, 10, east: 30), fix(101, 10, east: 40)], sensors: [])
        let replay = ReplayAnalysis.analyze([first, next])
        #expect(replay.lines.count == 2)
        #expect(replay.lineTimes[0] == [0, second, 2 * second])
        #expect(replay.playedPoints(line: 0, at: second) == 2)
        #expect(replay.playedPoints(line: 0, at: 15 * second) == 3)
        #expect(replay.playedPoints(line: 1, at: 10 * second - 1) == 0)
        #expect(replay.playedPoints(line: 1, at: 10 * second) == 1)
        #expect(replay.playedPoints(line: 2, at: 10 * second) == 0)
    }

    @Test func aPositionIsInterpolatedOnlyWithinOneLine() throws {
        let samples = [fix(1, 10, east: 0), fix(2, 10, east: 10), fix(16, 10, east: 150), fix(17, 10, east: 160)]
        let replay = ReplayAnalysis.analyze([ReplaySegment(startedElapsedNanos: second, endedElapsedNanos: 21 * second, samples: samples, sensors: [])])
        #expect(abs(try #require(replay.valuesAt(second / 2).longitudeRadians) * 6_371_008.8 - 5) < 0.01)
        #expect(abs(try #require(replay.valuesAt(5 * second).longitudeRadians) * 6_371_008.8 - 10) < 0.01)
        #expect(replay.valuesAt(12 * second).longitudeRadians == nil)
    }

    @Test func theBearingTurnsTheShorterWayBetweenFixes() throws {
        let samples = [fix(1, 10, east: 0, bearing: 350 * .pi / 180), fix(2, 10, east: 10, bearing: 10 * .pi / 180)]
        let replay = ReplayAnalysis.analyze([ReplaySegment(startedElapsedNanos: second, endedElapsedNanos: 3 * second, samples: samples, sensors: [])])
        let north = try #require(replay.valuesAt(second / 2).bearingRadians) * 180 / .pi
        #expect(abs(north > 180 ? north - 360 : north) < 0.01)
        #expect(abs(try #require(replay.valuesAt(second / 4).bearingRadians) * 180 / .pi - 355) < 0.01)
    }

    @Test func aNetworkPositionGivesAnApproximateRangeWithoutSpeed() throws {
        let samples = [fix(0, 10), fix(12, nil, source: .network), fix(17, nil, source: .network), fix(20, 10)]
        let replay = ReplayAnalysis.analyze([ReplaySegment(startedElapsedNanos: 0, endedElapsedNanos: 21 * second, samples: samples, sensors: [])])
        #expect(replay.approximate == [(12 * second)...(20 * second)])
        let values = replay.valuesAt(13 * second)
        #expect(try #require(values.fix).source == .network)
        #expect(values.speedMetersPerSecond == nil)
        #expect(values.latitudeRadians != nil)
    }

    @Test func theBarometerGivesTheShapeAndTheGpsHeightGivesTheLevel() throws {
        func heightToPressure(_ meters: Double) -> Double { 1_013.25 * pow(1 - meters / 44_330, 5.255) }
        let samples = (0...30).map { t in fix(Double(t), 10, altitude: 200 + Double(t) + (t % 2 == 0 ? 4 : -4)) }
        let times = (0...300).map { Int64($0) * second / 10 }
        let pressure = try #require(SensorChunk(kind: .pressure, elapsedNanos: times,
                                                values: (0...300).map { Float(heightToPressure(1_000 + Double($0) / 10)) }))
        let segment = ReplaySegment(startedElapsedNanos: 0, endedElapsedNanos: 30 * second, samples: samples, sensors: [pressure])
        let replay = ReplayAnalysis.analyze([segment])
        #expect(replay.heightSource == .barometer)
        #expect(abs(try #require(replay.valuesAt(15 * second + second / 2).heightMeters) - 215) <= 4.5)
        let shorter = try #require(SensorChunk(kind: .pressure, elapsedNanos: Array(times.prefix(300)), values: Array(pressure.values.prefix(300))))
        let early = ReplayAnalysis.analyze([ReplaySegment(startedElapsedNanos: 0, endedElapsedNanos: 30 * second, samples: samples, sensors: [shorter])])
        #expect(early.valuesAt(early.durationNanos).heightMeters != nil)
        let highest = try #require(replay.moments.first { $0.kind == .highestPoint })
        #expect(abs(highest.value - 230) <= 4.5)
        #expect(ReplayAnalysis.analyze([segment], gpsHeightOnly: true).heightSource == .gps)
    }

    @Test func theAccelerometerGivesAccelerationAndBrakingAlongTheTrack() throws {
        func speedAt(_ t: Double) -> Double {
            t < 5 ? 0 : t < 15 ? 2 * (t - 5) : t < 25 ? 20 : t < 31 ? 20 - 3 * (t - 25) : 2
        }
        func accelerationAt(_ t: Double) -> Double {
            (5...15).contains(t) ? 2 : (25...31).contains(t) ? -3 : 0
        }
        let samples = (0...34).map { fix(Double($0), speedAt(Double($0))) }
        // The rotation frame is turned 40 degrees from east and north. The phone lies flat in that frame.
        let yaw = 40 * Double.pi / 180
        let times = (0..<(35 * 50)).map { Int64($0) * second / 50 }
        var acceleration: [Float] = []
        for time in times {
            let a = accelerationAt(Double(time) / 1e9)
            acceleration += [Float(a * cos(yaw)), Float(-a * sin(yaw)), 9.80665]
        }
        let rotation = (0..<(times.count * 4)).map { $0 % 4 == 3 ? Float(1) : 0 }
        let segment = ReplaySegment(startedElapsedNanos: 0, endedElapsedNanos: 35 * second, samples: samples, sensors: [
            try #require(SensorChunk(kind: .acceleration, elapsedNanos: times, values: acceleration)),
            try #require(SensorChunk(kind: .rotation, elapsedNanos: times, values: rotation)),
        ])
        let replay = ReplayAnalysis.analyze([segment])
        #expect(replay.accelerationFromSensors)
        #expect(abs(try #require(replay.valuesAt(10 * second + second / 2).accelerationMetersPerSecondSquared) - 2) < 0.2)
        let accelerating = try #require(replay.moments.first { $0.kind == .acceleration })
        let braking = try #require(replay.moments.first { $0.kind == .braking })
        #expect(abs(accelerating.value - 2) < 0.2)
        #expect(abs(braking.value + 3) < 0.3)
        #expect((5 * second...15 * second).contains(accelerating.timeNanos))
        #expect((25 * second...31 * second).contains(braking.timeNanos))
        #expect(replay.moments.sorted { $0.timeNanos < $1.timeNanos }.map(\.number) == Array(1...replay.moments.count))
        let gpsOnly = ReplayAnalysis.analyze([ReplaySegment(startedElapsedNanos: 0, endedElapsedNanos: 35 * second, samples: samples, sensors: [])])
        #expect(!gpsOnly.accelerationFromSensors)
        #expect(abs(try #require(gpsOnly.moments.first { $0.kind == .acceleration }).value - 2) < 0.01)
        #expect(abs(try #require(gpsOnly.moments.first { $0.kind == .braking }).value + 3) < 0.01)
    }

    @Test func aQuaternionTurnsTheAccelerationAndRemovesGravity() {
        let half = Float(sqrt(0.5))
        let world = ReplayAnalysis.rotate([0, 0, half, half], [1, 0, 9.80665])
        #expect(abs(world[0]) < 1e-6)
        #expect(abs(world[1] - 1) < 1e-6)
        #expect(abs(world[2]) < 1e-5)
    }

    @Test func aSteadyTripHasNoAccelerationOrHeightMoments() {
        let samples = (0...20).map { fix(Double($0), 10) }
        let replay = ReplayAnalysis.analyze([ReplaySegment(startedElapsedNanos: 0, endedElapsedNanos: 20 * second, samples: samples, sensors: [])])
        #expect(replay.moments.map(\.kind) == [.fastestSpeed])
    }

    @Test func eachColumnKeepsItsPeaksAndRunsStaySeparate() {
        let times = (0..<100).map { Int64($0) * second / 10 }
        let values = (0..<100).map { $0 == 33 ? 50.0 : 10 }
        let runs = [ReplayRun(times: times, values: values), ReplayRun(times: [15 * second, 16 * second], values: [5, 6])]
        let columns = ReplayChartData.columns(runs, durationNanos: 20 * second, columns: 20)
        #expect(columns.count == 2)
        #expect(columns[0].count == 10)
        #expect(columns[0][3] == ChartColumn(index: 3, minimum: 10, maximum: 50))
        #expect(columns[1] == [ChartColumn(index: 15, minimum: 5, maximum: 5), ChartColumn(index: 16, minimum: 6, maximum: 6)])
    }

    @Test func timesThatNoRunCoversAreGaps() {
        let runs = [ReplayRun(times: [2 * second, 5 * second], values: [1, 2]),
                    ReplayRun(times: [9 * second, 10 * second], values: [1, 2], reach: second / 2)]
        #expect(ReplayChartData.uncovered(runs, durationNanos: 10 * second) == [0...(2 * second), (5 * second)...(8 * second + second / 2)])
    }
}
