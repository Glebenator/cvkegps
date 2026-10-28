package com.example.cvkegps.tracking

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Records the accelerometer, the game rotation vector, and the barometer while a trip records.
 * Sensor events arrive on a separate thread, so the main thread does not handle 100 events each second.
 * A sensor that the phone does not have is not recorded. Android can lose readings while the screen is off
 * if the sensor's queue fills before the processor wakes. The reading times show any such gap.
 */
class AndroidMotionSource(context: Context) : MotionSource {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var recording: Recording? = null

    override fun start(onChunks: (List<SensorChunk>) -> Unit) {
        if (recording != null || manager == null) return
        val sensors = listOfNotNull(
            manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { Triple(it, SensorKind.Acceleration, MOTION_PERIOD_MICROS) },
            manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)?.let { Triple(it, SensorKind.Rotation, MOTION_PERIOD_MICROS) },
            manager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { Triple(it, SensorKind.Pressure, PRESSURE_PERIOD_MICROS) },
        )
        if (sensors.isEmpty()) return
        val worker = HandlerThread("trip-sensors").also { it.start() }
        val handler = Handler(worker.looper)
        val next = Recording(handler, sensors.associate { it.first.type to Buffer(it.second) }, onChunks)
        for ((sensor, _, period) in sensors) manager.registerListener(next, sensor, period, MAX_LATENCY_MICROS, handler)
        handler.postDelayed(next.flush, FLUSH_MILLIS)
        thread = worker
        recording = next
    }

    override fun stop() {
        val current = recording ?: return
        recording = null
        manager?.unregisterListener(current)
        val worker = thread
        thread = null
        // The worker delivers the last readings, then ends. Events that were queued before it run first.
        current.handler.removeCallbacks(current.flush)
        current.handler.post {
            current.deliver()
            worker?.quitSafely()
        }
    }

    /** One readings list for each sensor. Only the worker thread uses it. */
    private class Buffer(val kind: SensorKind) {
        var times = LongArray(128)
        var values = FloatArray(128 * kind.values)
        var count = 0

        fun add(event: SensorEvent) {
            if (count == times.size) {
                times = times.copyOf(count * 2)
                values = values.copyOf(count * 2 * kind.values)
            }
            times[count] = event.timestamp
            // Some phones add more values to the rotation vector. Only the quaternion is stored.
            for (value in 0 until kind.values) values[count * kind.values + value] = event.values.getOrElse(value) { Float.NaN }
            count++
        }

        fun take(): SensorChunk? {
            if (count == 0) return null
            val chunk = SensorChunk(kind, times.copyOf(count), values.copyOf(count * kind.values))
            count = 0
            return chunk
        }
    }

    private inner class Recording(
        val handler: Handler,
        private val buffers: Map<Int, Buffer>,
        private val onChunks: (List<SensorChunk>) -> Unit,
    ) : SensorEventListener {
        val flush: Runnable = object : Runnable {
            override fun run() {
                deliver()
                handler.postDelayed(this, FLUSH_MILLIS)
            }
        }

        override fun onSensorChanged(event: SensorEvent) {
            buffers[event.sensor.type]?.add(event)
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

        fun deliver() {
            val chunks = buffers.values.mapNotNull { it.take() }
            if (chunks.isNotEmpty()) main.post { onChunks(chunks) }
        }
    }

    private companion object {
        /** 50 readings each second. Android 12 and later allow up to 200 each second without a special permission. */
        const val MOTION_PERIOD_MICROS = 20_000
        const val PRESSURE_PERIOD_MICROS = 100_000
        /** The sensor hub can collect readings for this time before it delivers them, so the processor can sleep. */
        const val MAX_LATENCY_MICROS = 1_000_000
        const val FLUSH_MILLIS = 2_000L
    }
}
