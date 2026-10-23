package com.example.cvkegps.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class AndroidHeadingSource(context: Context) : HeadingSource {
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)

    override fun updates(displayRotation: Int): Flow<HeadingReading?> = callbackFlow {
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (manager == null || sensor == null) {
            trySend(null)
            close()
            return@callbackFlow
        }
        val matrix = FloatArray(9)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                    trySend(null)
                    return
                }
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                val azimuth = screenHeading(matrix, displayRotation)
                trySend(azimuth?.let { HeadingReading(it, event.timestamp) })
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) trySend(null)
            }
        }
        trySend(null)
        if (!manager.registerListener(listener, sensor, 100_000)) {
            manager.unregisterListener(listener)
            close()
            return@callbackFlow
        }
        awaitClose { manager.unregisterListener(listener) }
    }.conflate()
}

/** Project the upper screen edge onto the horizontal plane. A vertical edge has no heading. */
internal fun screenHeading(matrix: FloatArray, displayRotation: Int): Double? {
    val (x, y) = when (displayRotation) {
        Surface.ROTATION_0 -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
        Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
        Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
        else -> return null
    }
    val screenMatrix = FloatArray(9)
    if (!SensorManager.remapCoordinateSystem(matrix, x, y, screenMatrix)) return null
    val east = screenMatrix[1].toDouble()
    val north = screenMatrix[4].toDouble()
    if (!east.isFinite() || !north.isFinite() || hypot(east, north) < 0.1) return null
    return normalizedAzimuth(atan2(east, north))
}
