package com.example.cvkegps.tracking

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The phone sensors that a trip records. [values] is the number of values in one reading.
 * [Acceleration] is in m/s² along the phone's axes and includes gravity. [Rotation] is the phone's orientation
 * as a unit quaternion (x, y, z, w) without a magnetic north. [Pressure] is the air pressure in hPa.
 */
enum class SensorKind(val values: Int) { Acceleration(3), Rotation(4), Pressure(1) }

/**
 * Readings of one sensor in time order. [elapsedNanos] uses the same clock as a fix, so the two can be compared.
 * [values] holds [SensorKind.values] numbers for each reading.
 */
class SensorChunk(val kind: SensorKind, val elapsedNanos: LongArray, val values: FloatArray) {
    init {
        require(elapsedNanos.isNotEmpty() && values.size == elapsedNanos.size * kind.values)
    }

    val count: Int get() = elapsedNanos.size

    /** Little-endian: for each reading, the time as a 64-bit integer, then its values as 32-bit floats. */
    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(count * bytesPerReading(kind)).order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until count) {
            buffer.putLong(elapsedNanos[index])
            for (value in 0 until kind.values) buffer.putFloat(values[index * kind.values + value])
        }
        return buffer.array()
    }

    companion object {
        private fun bytesPerReading(kind: SensorKind) = Long.SIZE_BYTES + kind.values * Float.SIZE_BYTES

        /** Reads data from [encode]. Returns null for data that does not have a whole number of readings. */
        fun decode(kind: SensorKind, data: ByteArray): SensorChunk? {
            val size = bytesPerReading(kind)
            if (data.isEmpty() || data.size % size != 0) return null
            val count = data.size / size
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val times = LongArray(count)
            val values = FloatArray(count * kind.values)
            for (index in 0 until count) {
                times[index] = buffer.getLong()
                for (value in 0 until kind.values) values[index * kind.values + value] = buffer.getFloat()
            }
            return SensorChunk(kind, times, values)
        }
    }
}

/** Records phone sensors during a trip. Android objects stay in the implementation. */
interface MotionSource {
    /** Starts recording. [onChunks] runs on the main thread, about every 2 seconds, with the readings since the last call. */
    fun start(onChunks: (List<SensorChunk>) -> Unit)

    /** Stops recording. The readings that are not yet delivered arrive in one more call. */
    fun stop()
}
