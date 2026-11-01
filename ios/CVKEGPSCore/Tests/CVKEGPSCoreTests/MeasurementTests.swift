import Foundation
import Testing
@testable import CVKEGPSCore

/// The same cases as the Android FixInterpreterTest, RecordingStateTest, and ActivityEstimatorTest.
struct FixInterpreterTests {
    func fix(accuracy: Double? = 4, speed: Double? = 10, speedAccuracy: Double? = 0.5, latitude: Double = 0.86) -> RawFix {
        RawFix(latitudeRadians: latitude, longitudeRadians: -2.15, timeMillis: 1_000, elapsedNanos: 1_000_000_000,
               receivedElapsedNanos: 1_000_000_000, accuracyMeters: accuracy, speedMetersPerSecond: speed,
               speedAccuracyMetersPerSecond: speedAccuracy, altitudeMeters: 451, seaLevelAltitudeMeters: 400)
    }

    /// The Speed tab shows the reading and the trip stores the sample. Both must give the same speed.
    func interpret(_ raw: RawFix) -> InterpretedFix {
        let result = FixInterpreter.interpret(raw)
        #expect(result.sample.usableSpeed == result.reading.speedMetersPerSecond)
        return result
    }

    @Test func networkFixKeepsItsPositionButNoMotionOrHeight() {
        var raw = fix()
        raw.source = .network
        raw.bearingRadians = 1
        raw.verticalAccuracyMeters = 3
        let result = interpret(raw)
        #expect(result.sample.source == .network)
        #expect(result.sample.accuracyMeters == 4)
        #expect(result.sample.speedMetersPerSecond == nil)
        #expect(result.sample.altitudeMeters == nil)
        #expect(result.sample.bearingRadians == nil)
        #expect(result.sample.verticalAccuracyMeters == nil)
        #expect(result.reading.speedMetersPerSecond == nil)
        #expect(result.reading.seaLevelAltitudeMeters == nil)
        #expect(result.reading.speedIssue == .missing)
    }

    @Test func usableFixGivesSpeedAndBothAltitudes() {
        let reading = interpret(fix()).reading
        #expect(reading.speedMetersPerSecond == 10)
        #expect(reading.speedIssue == nil)
        #expect(reading.altitude(.ellipsoid) == 451)
        #expect(reading.altitude(.seaLevel) == 400)
    }

    @Test func positionWorseThan50MetresRejectsSpeedAndAltitude() {
        #expect(interpret(fix(accuracy: 50)).reading.speedIssue == nil)
        for accuracy in [50.01, nil, Double.nan, -1] {
            let reading = interpret(fix(accuracy: accuracy)).reading
            #expect(reading.speedIssue == .positionAccuracy)
            #expect(reading.altitudeMeters == nil)
            #expect(reading.seaLevelAltitudeMeters == nil)
        }
    }

    @Test func speedAccuracyWorseThan3MetresPerSecondMakesTheSpeedUncertain() {
        #expect(interpret(fix(speedAccuracy: 3)).reading.speedMetersPerSecond == 10)
        #expect(interpret(fix(speedAccuracy: nil)).reading.speedMetersPerSecond == 10)
        #expect(interpret(fix(speedAccuracy: 3.01)).reading.speedIssue == .speedAccuracy)
    }

    @Test func missingAndInvalidSpeedsHaveTheirOwnIssue() {
        #expect(interpret(fix(speed: nil)).reading.speedIssue == .missing)
        #expect(interpret(fix(speed: .nan)).reading.speedIssue == .invalid)
        #expect(interpret(fix(speed: -1)).reading.speedIssue == .invalid)
    }

    /// A stored sample cannot show that the accuracy was reported. It must still give no usable speed (ADR 0001).
    @Test func invalidSpeedAccuracyRejectsTheSpeedAlsoInTheStoredSample() {
        for speedAccuracy in [Double.nan, -1] {
            let result = interpret(fix(speedAccuracy: speedAccuracy))
            #expect(result.reading.speedIssue == .speedAccuracy)
            #expect(result.sample.usableSpeed == nil)
        }
    }

    @Test func accuracyBarIsLogarithmicBetween50And1Metre() {
        #expect(PositionAccuracyScale.progress(50) == 0)
        #expect(PositionAccuracyScale.progress(1) == 1)
        #expect(abs(PositionAccuracyScale.progress(5)! - 0.5886) < 0.001)
        #expect(PositionAccuracyScale.progress(nil) == nil)
        #expect(PositionAccuracyScale.progress(-1) == nil)
    }
}

struct RecordingStateTests {
    let trip = TripRecord(id: "trip", mode: .unknown, status: .recording, startedMillis: 1)
    let second: Int64 = 1_000_000_000

    @Test func noSignalStartsTenSecondsAfterTheNewestFixOrTheSegmentStart() {
        let fresh = RecordingState(trip: trip, segmentStartNanos: 100 * second)
        #expect(fresh.signalLostAtNanos == 110 * second)
        #expect(!fresh.signalLost(at: 109 * second))
        #expect(fresh.signalLost(at: 110 * second))
        var fixed = fresh
        fixed.lastFixNanos = 105 * second
        #expect(fixed.signalLostAtNanos == 115 * second)
        // A fix from before the segment, for example after a resume, does not shorten the delay.
        fixed.lastFixNanos = 90 * second
        #expect(fixed.signalLostAtNanos == 110 * second)
    }

    @Test func aNetworkPositionIsApproximateOnlyWithoutGpsAndForTenSeconds() {
        var state = RecordingState(trip: trip, segmentStartNanos: 100 * second, lastFixNanos: 100 * second,
                                   lastNetworkFixNanos: 112 * second)
        #expect(state.approximateUntilNanos == 122 * second)
        #expect(!state.approximate(at: 109 * second))
        #expect(state.approximate(at: 112 * second))
        #expect(!state.approximate(at: 122 * second))
        state.lastFixNanos = 113 * second
        #expect(!state.approximate(at: 114 * second))
        var paused = trip
        paused.status = .paused
        #expect(RecordingState(trip: paused, lastNetworkFixNanos: 112 * second).approximateUntilNanos == nil)
    }

    @Test func pausedAndEndedTripsDoNotReportNoSignal() {
        var paused = trip
        paused.status = .paused
        #expect(RecordingState(trip: paused, segmentStartNanos: second).signalLostAtNanos == nil)
        #expect(RecordingState(segmentStartNanos: second).signalLostAtNanos == nil)
        #expect(!RecordingState(trip: paused).signalLost(at: .max))
    }

    @Test func aShownPositionIsCurrentForTenSecondsAfterItsOwnFix() {
        #expect(RouteMath.positionCurrent(fixNanos: 100 * second, nowNanos: 100 * second))
        #expect(RouteMath.positionCurrent(fixNanos: 100 * second, nowNanos: 110 * second - 1))
        #expect(!RouteMath.positionCurrent(fixNanos: 100 * second, nowNanos: 110 * second))
        #expect(!RouteMath.positionCurrent(fixNanos: 100 * second, nowNanos: 100 * second - 1))
    }
}

struct ActivityEstimatorTests {
    func sample(_ second: Int, _ speed: Double, accuracy: Double = 3) -> TrackSample {
        TrackSample(latitudeRadians: 0, longitudeRadians: 0, timeMillis: 1_000 + Int64(second) * 1_000,
                    elapsedNanos: Int64(second + 1) * 1_000_000_000, receivedElapsedNanos: Int64(second + 1) * 1_000_000_000,
                    accuracyMeters: accuracy, speedMetersPerSecond: speed, speedAccuracyMetersPerSecond: 0.3, isMock: true)
    }

    @Test func sustainedMovementProducesTentativeCategoriesNotFalsePrecision() {
        for (speed, expected) in [(0.0, TripMode.unknown), (6, .bikeOrSki), (20, .carOrTransit), (70, .plane)] {
            let detector = ActivityEstimator()
            #expect(detector.accept(sample(0, speed))?.mode == .unknown)
            let estimate = (1...16).map { detector.accept(sample($0, speed)) }.last!!
            #expect(estimate.mode == expected)
            #expect(estimate.fresh(at: estimate.elapsedNanos + 10_000_000_000) == .unknown)
        }
    }

    @Test func tenFixesEachSecondGiveTheSameEstimateAsOne() {
        let detector = ActivityEstimator()
        var estimate: ActivityEstimate?
        for tenth in 0...160 {
            var fix = sample(0, 20)
            fix.timeMillis = 1_000 + Int64(tenth) * 100
            fix.elapsedNanos = 1_000_000_000 + Int64(tenth) * 100_000_000
            fix.receivedElapsedNanos = fix.elapsedNanos
            estimate = detector.accept(fix)
        }
        #expect(estimate?.mode == .carOrTransit)
    }

    @Test func noiseGapsAndBadReadingsCannotSupplySustainedEvidence() {
        let detector = ActivityEstimator()
        (0...16).forEach { _ = detector.accept(sample($0, 20)) }
        #expect(detector.accept(sample(17, 90))?.mode == .unknown)
        #expect(detector.accept(sample(16, 90)) == nil)
        #expect(detector.accept(sample(18, 20, accuracy: 100))?.mode == .unknown)
        #expect(detector.accept(sample(19, 20))?.mode == .unknown)
        (20...35).forEach { _ = detector.accept(sample($0, 20)) }
        #expect(detector.accept(sample(50, 20))?.mode == .unknown)
        var late = sample(51, 20)
        late.receivedElapsedNanos = late.elapsedNanos + 11_000_000_000
        #expect(detector.accept(late) == nil)
        var noAccuracy = sample(52, 20)
        noAccuracy.speedAccuracyMetersPerSecond = nil
        #expect(detector.accept(noAccuracy)?.mode == .unknown)
        detector.reset()
        #expect(detector.accept(sample(0, 70))?.mode == .unknown)
    }
}
