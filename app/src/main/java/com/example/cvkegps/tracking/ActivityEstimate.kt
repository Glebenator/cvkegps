package com.example.cvkegps.tracking

data class ActivityEstimate(val mode: TripMode = TripMode.Unknown, val elapsedNanos: Long = 0) {
    fun fresh(now: Long): TripMode = mode.takeIf { now - elapsedNanos in 0 until 10_000_000_000L } ?: TripMode.Unknown
}

/** Provisional GPS categories, not a validated activity classifier. No Android objects or stored coordinates. */
class ActivityEstimator {
    private data class Evidence(val time: Long, val mode: TripMode)
    private val window = ArrayDeque<Evidence>()
    private var lastTime = 0L

    fun reset() { window.clear(); lastTime = 0 }

    fun accept(sample: TrackSample): ActivityEstimate? {
        // Late history must not change the current estimate. A bad current fix clears the evidence.
        if (!sample.valid() || sample.elapsedNanos <= lastTime) return null
        if (sample.receivedElapsedNanos - sample.elapsedNanos >= 10_000_000_000L) return null
        if (sample.elapsedNanos - lastTime >= 10_000_000_000L) window.clear()
        lastTime = sample.elapsedNanos
        val speed = sample.usableSpeed
        if (speed == null || sample.accuracyMeters!! > 20f ||
            sample.speedAccuracyMetersPerSecond?.let { it <= 1f } != true || speed < 2f) {
            window.clear()
            return ActivityEstimate(elapsedNanos = lastTime)
        }
        val mode = when {
            speed >= 55f -> TripMode.Plane
            speed >= 12f -> TripMode.CarOrTransit
            else -> TripMode.BikeOrSki
        }
        window.addLast(Evidence(lastTime, mode))
        while (window.size > 60 || lastTime - window.first().time > 30_000_000_000L) window.removeFirst()
        val supported = window.count { it.mode == mode }
        val sustained = window.size >= 6 && lastTime - window.first().time >= 15_000_000_000L
        return ActivityEstimate(if (sustained && supported * 5 >= window.size * 4) mode else TripMode.Unknown, lastTime)
    }
}
