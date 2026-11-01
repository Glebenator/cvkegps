import CoreLocation
import CVKEGPSCore
import Foundation
import Observation

/// Core Location access. The Speed tab and a recording trip share one location request. The request uses the strictest need
/// of the active features. Location runs only while the app is visible or a trip records. A paused trip stops it.
///
/// Core Location does not say which receiver measured a fix. A fix without a valid speed or course accuracy has no measured
/// motion. When its accuracy is also worse than 20 m, the app treats it as a network position from Wi-Fi and mobile networks. Network positions go only to a recording
/// trip, only after 10 seconds without a GPS fix, and only when the user allows them. The Speed tab never shows them.
@MainActor
@Observable
final class LocationService: NSObject, RecordingLocationSource {
    /// The state for the Speed tab: an access problem, searching, or the newest fix.
    private(set) var event: GpsEvent = .searching
    /// GPS fixes in the current request, including rejected ones.
    private(set) var receivedCount = 0
    private(set) var authorization: CLAuthorizationStatus
    private(set) var preciseAccess: Bool
    /// False when Location Services are off for the whole phone.
    private(set) var servicesEnabled = true
    private(set) var activityEstimate = ActivityEstimate()
    /// True while a location request runs.
    private(set) var running = false
    /// The requested accuracy, for GPS details.
    private(set) var highAccuracy = false

    @ObservationIgnored private let manager = CLLocationManager()
    @ObservationIgnored private let estimator = ActivityEstimator()
    @ObservationIgnored private var displayCount = 0
    @ObservationIgnored private var foreground = false
    @ObservationIgnored private var recordingOwned = false
    @ObservationIgnored private var paused = false
    @ObservationIgnored private var options = RecordingOptions()
    @ObservationIgnored private var sink: (@MainActor ([TrackSample], Double?) -> Void)?
    @ObservationIgnored private var accessLost: (@MainActor () -> Void)?
    @ObservationIgnored private var backgroundSession: CLBackgroundActivitySession?
    @ObservationIgnored private var latestNanos: Int64 = 0
    // The GPS signal watch of a recording trip: the newest GPS fix or the start of the watch, and the newest network position.
    @ObservationIgnored private var lastGpsNanos: Int64?
    @ObservationIgnored private var lastNetworkNanos: Int64 = 0

    /// A fix without measured motion is a network position only when its accuracy is worse than this.
    nonisolated static let networkMinimumAccuracyMeters = 20.0

    /// A recording trip stores at most one network position in this time.
    static let networkIntervalNanos: Int64 = 5_000_000_000

    override init() {
        authorization = manager.authorizationStatus
        preciseAccess = manager.accuracyAuthorization == .fullAccuracy
        super.init()
        manager.delegate = self
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .otherNavigation
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
    }

    var permitted: Bool { authorization == .authorizedWhenInUse || authorization == .authorizedAlways }

    // MARK: Demand

    /// True while the app is visible. Location then stays on for all screens, so a change of screen does not restart it.
    func setForeground(_ visible: Bool) {
        foreground = visible
        if visible { checkServices() }
        refresh()
    }

    /// A screen that shows readings. Each call with true needs one call with false.
    func display(_ shown: Bool) {
        displayCount = max(displayCount + (shown ? 1 : -1), 0)
        refresh()
    }

    func requestAccess() {
        manager.requestWhenInUseAuthorization()
    }

    /// Asks for precise location for this app session, when the user allows only approximate location.
    func requestPrecise() async {
        try? await manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: "TripRecording")
        preciseAccess = manager.accuracyAuthorization == .fullAccuracy
        refresh()
    }

    /// Reads the system switch for Location Services off the main thread, because the call can block.
    func checkServices() {
        Task {
            let enabled = await Self.servicesSwitch()
            if enabled != servicesEnabled {
                servicesEnabled = enabled
                refresh()
            }
        }
    }

    @concurrent private static func servicesSwitch() async -> Bool { CLLocationManager.locationServicesEnabled() }

    func recording(options: RecordingOptions, onBatch: @escaping @MainActor ([TrackSample], Double?) -> Void,
                   onAccessLost: @escaping @MainActor () -> Void) {
        recordingOwned = true
        paused = false
        sink = onBatch
        accessLost = onAccessLost
        self.options = options
        lastGpsNanos = MonotonicClock.nanos()
        lastNetworkNanos = 0
        refresh()
    }

    func pauseRecording() {
        recordingOwned = true
        paused = true
        sink = nil
        refresh()
    }

    func releaseRecording() {
        recordingOwned = false
        paused = false
        sink = nil
        accessLost = nil
        options = RecordingOptions()
        refresh()
    }

    private var recordingNow: Bool { recordingOwned && !paused && sink != nil }

    private func refresh() {
        let wanted = !paused && (recordingOwned || displayCount > 0 || foreground)
        updateSession()
        guard wanted, permitted, servicesEnabled else {
            stop()
            if paused { event = .paused }
            else if !servicesEnabled { event = .disabled }
            else if !permitted { event = .permissionRequired }
            else { event = .searching }
            if wanted && (!permitted || !servicesEnabled) { accessLost?() }
            return
        }
        let high = recordingNow && options.highAccuracy
        highAccuracy = high
        manager.desiredAccuracy = high ? kCLLocationAccuracyBestForNavigation : kCLLocationAccuracyBest
        if !running {
            receivedCount = 0
            latestNanos = 0
            event = preciseAccess ? .searching : .reducedAccuracy
            manager.startUpdatingLocation()
            running = true
        } else if !preciseAccess {
            event = .reducedAccuracy
        }
    }

    private func stop() {
        estimator.reset()
        activityEstimate = ActivityEstimate()
        if running {
            manager.stopUpdatingLocation()
            running = false
        }
    }

    /// A trip that records or is paused keeps a background activity session. The status bar then shows that the app uses
    /// location, and the Live Activity can resume a paused trip while the app is not on screen. A paused trip has no
    /// location updates. Background updates need the location background mode.
    private func updateSession() {
        let keep = recordingOwned && permitted
        manager.allowsBackgroundLocationUpdates = keep
        if keep && backgroundSession == nil { backgroundSession = CLBackgroundActivitySession() }
        if !keep, let session = backgroundSession {
            session.invalidate()
            backgroundSession = nil
        }
    }

    // MARK: Fixes

    private func deliver(_ locations: [CLLocation]) {
        guard running else { return }
        let now = MonotonicClock.nanos()
        let wall = Date()
        var gps: [InterpretedFix] = []
        var network: [TrackSample] = []
        var offset: Double?
        for location in locations where location.horizontalAccuracy >= 0 {
            let raw = Self.raw(location, now: now, wall: wall)
            let fix = FixInterpreter.interpret(raw)
            if raw.source == .gps {
                gps.append(fix)
                if let height = fix.reading.altitudeMeters, let seaLevel = fix.reading.seaLevelAltitudeMeters { offset = height - seaLevel }
            } else {
                network.append(fix.sample)
            }
        }
        var stored = gps.map(\.sample)
        if recordingNow && options.networkFallback {
            if let newest = gps.map(\.sample.elapsedNanos).max() { lastGpsNanos = max(lastGpsNanos ?? newest, newest) }
            for sample in network.sorted(by: { $0.elapsedNanos < $1.elapsedNanos }) {
                // Network positions only replace a lost GPS signal.
                let lost = sample.elapsedNanos - (lastGpsNanos ?? sample.elapsedNanos) >= RouteMath.signalGapNanos
                if lost && sample.elapsedNanos - lastNetworkNanos >= Self.networkIntervalNanos {
                    stored.append(sample)
                    lastNetworkNanos = sample.elapsedNanos
                }
            }
        }
        for sample in gps.map(\.sample).sorted(by: { $0.elapsedNanos < $1.elapsedNanos }) {
            if let estimate = estimator.accept(sample) { activityEstimate = estimate }
        }
        if !stored.isEmpty { sink?(stored.sorted { $0.elapsedNanos < $1.elapsedNanos }, offset) }
        receivedCount += gps.count
        // Approximate location has no usable speed. The Speed tab keeps the explanation.
        guard preciseAccess, let newest = gps.max(by: { $0.sample.elapsedNanos < $1.sample.elapsedNanos }),
              newest.sample.elapsedNanos >= latestNanos else { return }
        latestNanos = newest.sample.elapsedNanos
        event = .fix(newest.reading, receivedCount: receivedCount)
    }

    /// Copies a Core Location fix without the usability rules. The interpreter applies them.
    /// Core Location reports a negative value for a measurement that it does not have.
    nonisolated static func raw(_ location: CLLocation, now: Int64, wall: Date) -> RawFix {
        let age = wall.timeIntervalSince(location.timestamp)
        let elapsed = now - Int64((age * 1e9).rounded())
        let vertical = location.verticalAccuracy > 0
        // Wi-Fi and mobile network positions have no measured motion and an accuracy of tens of metres or more.
        let gps = location.speedAccuracy >= 0 || location.courseAccuracy >= 0 || location.horizontalAccuracy <= Self.networkMinimumAccuracyMeters
        return RawFix(
            latitudeRadians: location.coordinate.latitude * .pi / 180,
            longitudeRadians: location.coordinate.longitude * .pi / 180,
            timeMillis: Int64((location.timestamp.timeIntervalSince1970 * 1000).rounded()),
            elapsedNanos: elapsed, receivedElapsedNanos: now,
            accuracyMeters: location.horizontalAccuracy,
            speedMetersPerSecond: location.speed >= 0 ? location.speed : nil,
            // A negative speed accuracy with a speed means that the speed is not valid.
            speedAccuracyMetersPerSecond: location.speed >= 0 ? location.speedAccuracy : nil,
            altitudeMeters: vertical ? location.ellipsoidalAltitude : nil,
            verticalAccuracyMeters: vertical ? location.verticalAccuracy : nil,
            bearingRadians: location.course >= 0 ? location.course * .pi / 180 : nil,
            bearingAccuracyRadians: location.courseAccuracy >= 0 ? location.courseAccuracy * .pi / 180 : nil,
            isMock: location.sourceInformation?.isSimulatedBySoftware ?? false,
            source: gps ? .gps : .network,
            satellitesUsed: nil,
            seaLevelAltitudeMeters: vertical ? location.altitude : nil
        )
    }

    private func authorizationChanged() {
        authorization = manager.authorizationStatus
        preciseAccess = manager.accuracyAuthorization == .fullAccuracy
        checkServices()
        refresh()
    }

    private func failed(_ error: Error) {
        guard let error = error as? CLError else { return }
        switch error.code {
        case .denied:
            authorization = manager.authorizationStatus
            refresh()
        case .locationUnknown:
            // Core Location continues to search.
            break
        default:
            break
        }
    }
}

extension LocationService: CLLocationManagerDelegate {
    // The manager was made on the main thread, so it calls its delegate on the main thread.
    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated { deliver(locations) }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated { authorizationChanged() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated { failed(error) }
    }
}
