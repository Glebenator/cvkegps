import CoreMotion
import CVKEGPSCore
import Foundation

/// Records the accelerometer, the orientation, and the air pressure while a trip records (ADR 0003).
/// Core Motion delivers on a worker queue. About every 2 seconds, the readings go to the recorder as chunks.
///
/// The stored values use the conventions of `SensorKind`, so both apps store the same data:
/// - Core Motion reports acceleration in g with the opposite sign. The recorder stores −(gravity + user acceleration) × g.
/// - The rotation is the attitude in a frame with a vertical z axis and an arbitrary north (`xArbitraryZVertical`). Each
///   reading stores the quaternion, or its inverse, that turns the phone's gravity reading to the vertical axis.
/// - Core Motion reports pressure in kPa. The recorder stores hPa.
@MainActor
final class MotionRecorder: MotionSource {
    private let motion = CMMotionManager()
    private let altimeter = CMAltimeter()
    private let queue: OperationQueue = {
        let queue = OperationQueue()
        queue.name = "Trip sensors"
        queue.maxConcurrentOperationCount = 1
        queue.qualityOfService = .utility
        return queue
    }()
    private var buffer: SensorBuffer?

    func start(onChunks: @escaping @MainActor ([SensorChunk]) -> Void) {
        stop()
        let buffer = SensorBuffer(deliver: onChunks)
        self.buffer = buffer
        if motion.isDeviceMotionAvailable {
            motion.deviceMotionUpdateInterval = 1.0 / 50
            motion.startDeviceMotionUpdates(using: .xArbitraryZVertical, to: queue, withHandler: buffer.motionHandler())
        }
        if CMAltimeter.isRelativeAltitudeAvailable() && CMAltimeter.authorizationStatus() != .denied {
            altimeter.startRelativeAltitudeUpdates(to: queue, withHandler: buffer.pressureHandler())
        }
    }

    func stop() {
        guard let buffer else { return }
        motion.stopDeviceMotionUpdates()
        altimeter.stopRelativeAltitudeUpdates()
        // The readings that are not yet delivered arrive in one more call.
        queue.addOperation { @Sendable in buffer.flush() }
        self.buffer = nil
    }
}

/// The readings since the last delivery. Only the serial sensor queue uses it.
private final class SensorBuffer: @unchecked Sendable {
    private let deliver: @MainActor ([SensorChunk]) -> Void
    private var accelerationTimes: [Int64] = []
    private var acceleration: [Float] = []
    private var rotationTimes: [Int64] = []
    private var rotation: [Float] = []
    private var pressureTimes: [Int64] = []
    private var pressure: [Float] = []
    private var lastFlush: Int64 = 0
    private static let flushNanos: Int64 = 2_000_000_000
    private static let gravity: Double = 9.80665

    init(deliver: @escaping @MainActor ([SensorChunk]) -> Void) {
        self.deliver = deliver
    }

    /// Core Motion times count from boot without sleep. Fix times count with sleep. The difference applies at delivery.
    private func elapsed(_ timestamp: TimeInterval) -> Int64 {
        let offset = MonotonicClock.nanos() - Int64((ProcessInfo.processInfo.systemUptime * 1e9).rounded())
        return Int64((timestamp * 1e9).rounded()) + offset
    }

    func motionHandler() -> CMDeviceMotionHandler {
        { @Sendable [self] motion, _ in
            guard let motion else { return }
            let time = elapsed(motion.timestamp)
            let g = motion.gravity
            let a = motion.userAcceleration
            let x = -(g.x + a.x) * Self.gravity
            let y = -(g.y + a.y) * Self.gravity
            let z = -(g.z + a.z) * Self.gravity
            accelerationTimes.append(time)
            acceleration += [Float(x), Float(y), Float(z)]
            let q = motion.attitude.quaternion
            // The quaternion that turns the measured gravity to +z is the one that turns the phone's axes into the frame.
            let up = Self.verticalPart(qx: q.x, qy: q.y, qz: q.z, qw: q.w, x: x, y: y, z: z)
            let inverseUp = Self.verticalPart(qx: -q.x, qy: -q.y, qz: -q.z, qw: q.w, x: x, y: y, z: z)
            let sign: Double = up >= inverseUp ? 1 : -1
            rotationTimes.append(time)
            rotation += [Float(sign * q.x), Float(sign * q.y), Float(sign * q.z), Float(q.w)]
            flushIfDue(time)
        }
    }

    func pressureHandler() -> CMAltitudeHandler {
        { @Sendable [self] data, _ in
            guard let data else { return }
            let time = elapsed(data.timestamp)
            pressureTimes.append(time)
            pressure.append(Float(data.pressure.doubleValue * 10))
            flushIfDue(time)
        }
    }

    /// The z part of the vector after the rotation of the quaternion, as the replay calculates it.
    private static func verticalPart(qx: Double, qy: Double, qz: Double, qw: Double, x: Double, y: Double, z: Double) -> Double {
        2 * (qx * qz - qy * qw) * x + 2 * (qy * qz + qx * qw) * y + (1 - 2 * (qx * qx + qy * qy)) * z
    }

    private func flushIfDue(_ time: Int64) {
        if lastFlush == 0 { lastFlush = time }
        if time - lastFlush >= Self.flushNanos { flush() }
    }

    func flush() {
        lastFlush = 0
        var chunks: [SensorChunk] = []
        if let chunk = SensorChunk(kind: .acceleration, elapsedNanos: accelerationTimes, values: acceleration) { chunks.append(chunk) }
        if let chunk = SensorChunk(kind: .rotation, elapsedNanos: rotationTimes, values: rotation) { chunks.append(chunk) }
        if let chunk = SensorChunk(kind: .pressure, elapsedNanos: pressureTimes, values: pressure) { chunks.append(chunk) }
        accelerationTimes = []
        acceleration = []
        rotationTimes = []
        rotation = []
        pressureTimes = []
        pressure = []
        guard !chunks.isEmpty else { return }
        let deliver = self.deliver
        Task { @MainActor in deliver(chunks) }
    }
}
