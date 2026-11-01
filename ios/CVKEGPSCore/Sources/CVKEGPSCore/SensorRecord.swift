import Foundation

/// The phone sensors that a trip records. `values` is the number of values in one reading.
/// `acceleration` is in m/s² along the phone's axes and includes gravity, with the sign that Android uses: a phone flat
/// on a table reads +9.81 on its z axis. `rotation` is the phone's orientation as a unit quaternion (x, y, z, w) that turns
/// the phone's axes into a frame with a vertical z axis and no magnetic north. `pressure` is the air pressure in hPa.
/// The iOS motion recorder converts Core Motion readings to these conventions, so both apps store the same data.
public enum SensorKind: String, CaseIterable, Hashable, Sendable {
    case acceleration = "Acceleration"
    case rotation = "Rotation"
    case pressure = "Pressure"

    public var values: Int {
        switch self {
        case .acceleration: 3
        case .rotation: 4
        case .pressure: 1
        }
    }

    var bytesPerReading: Int { 8 + values * 4 }
}

/// Readings of one sensor in time order. `elapsedNanos` uses the same clock as a fix, so the two can be compared.
/// `values` holds `kind.values` numbers for each reading.
public struct SensorChunk: Hashable, Sendable {
    public let kind: SensorKind
    public let elapsedNanos: [Int64]
    public let values: [Float]

    public init?(kind: SensorKind, elapsedNanos: [Int64], values: [Float]) {
        guard !elapsedNanos.isEmpty, values.count == elapsedNanos.count * kind.values else { return nil }
        self.kind = kind
        self.elapsedNanos = elapsedNanos
        self.values = values
    }

    public var count: Int { elapsedNanos.count }

    /// Little-endian: for each reading, the time as a 64-bit integer, then its values as 32-bit floats.
    public func encode() -> Data {
        var data = Data(capacity: count * kind.bytesPerReading)
        for index in 0..<count {
            withUnsafeBytes(of: elapsedNanos[index].littleEndian) { data.append(contentsOf: $0) }
            for value in 0..<kind.values {
                withUnsafeBytes(of: values[index * kind.values + value].bitPattern.littleEndian) { data.append(contentsOf: $0) }
            }
        }
        return data
    }

    /// Reads data from `encode`. Returns nil for data that does not have a whole number of readings.
    public static func decode(_ kind: SensorKind, _ data: Data) -> SensorChunk? {
        let size = kind.bytesPerReading
        guard !data.isEmpty, data.count % size == 0 else { return nil }
        let count = data.count / size
        var times = [Int64](repeating: 0, count: count)
        var values = [Float](repeating: 0, count: count * kind.values)
        data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            var offset = 0
            for index in 0..<count {
                times[index] = Int64(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: Int64.self))
                offset += 8
                for value in 0..<kind.values {
                    let bits = UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
                    values[index * kind.values + value] = Float(bitPattern: bits)
                    offset += 4
                }
            }
        }
        return SensorChunk(kind: kind, elapsedNanos: times, values: values)
    }
}

/// Records phone sensors during a trip. Platform objects stay in the implementation.
@MainActor
public protocol MotionSource: AnyObject {
    /// Starts recording. `onChunks` runs on the main actor, about every 2 seconds, with the readings since the last call.
    func start(onChunks: @escaping @MainActor ([SensorChunk]) -> Void)

    /// Stops recording. The readings that are not yet delivered arrive in one more call.
    func stop()
}
