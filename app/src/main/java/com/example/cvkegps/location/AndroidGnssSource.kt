package com.example.cvkegps.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

class AndroidGnssSource(context: Context) : GnssSource {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission") // Check precise permission before registration.
    override fun updates(): Flow<GnssSnapshot> = callbackFlow {
        if (manager == null ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            trySend(GnssSnapshot(observation = GnssObservation.Unavailable))
            close()
            return@callbackFlow
        }
        var snapshot = GnssSnapshot(observation = GnssObservation.Waiting)
        val callback = object : GnssStatusCompat.Callback() {
            override fun onStarted() {
                snapshot = GnssSnapshot(GnssObservation.Waiting, ReceiverEvent.Started)
                trySend(snapshot)
            }

            override fun onStopped() {
                snapshot = GnssSnapshot(GnssObservation.Waiting, ReceiverEvent.Stopped)
                trySend(snapshot)
            }

            override fun onFirstFix(ttffMillis: Int) {
                snapshot = snapshot.copy(firstFixSeconds = ttffMillis.takeIf { it >= 0 }?.div(1000.0))
                trySend(snapshot)
            }

            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                val signals = List(status.satelliteCount) { index ->
                    SatelliteSignal(
                        system = when (status.getConstellationType(index)) {
                            GnssStatusCompat.CONSTELLATION_GPS -> SatelliteSystem.Gps
                            GnssStatusCompat.CONSTELLATION_SBAS -> SatelliteSystem.Sbas
                            GnssStatusCompat.CONSTELLATION_GLONASS -> SatelliteSystem.Glonass
                            GnssStatusCompat.CONSTELLATION_QZSS -> SatelliteSystem.Qzss
                            GnssStatusCompat.CONSTELLATION_BEIDOU -> SatelliteSystem.Beidou
                            GnssStatusCompat.CONSTELLATION_GALILEO -> SatelliteSystem.Galileo
                            GnssStatusCompat.CONSTELLATION_IRNSS -> SatelliteSystem.Irnss
                            else -> SatelliteSystem.Unknown
                        },
                        id = status.getSvid(index),
                        cn0DbHz = status.getCn0DbHz(index),
                        elevationRadians = Math.toRadians(status.getElevationDegrees(index).toDouble()),
                        azimuthRadians = Math.toRadians(status.getAzimuthDegrees(index).toDouble()),
                        carrierFrequencyHz = if (status.hasCarrierFrequencyHz(index)) status.getCarrierFrequencyHz(index) else null,
                        usedInFix = status.usedInFix(index),
                    )
                }.sortedWith(compareBy({ it.system.ordinal }, { it.id }, { it.carrierFrequencyHz }))
                snapshot = snapshot.copy(signals = signals, reportedAtNanos = SystemClock.elapsedRealtimeNanos())
                trySend(snapshot)
            }
        }
        trySend(snapshot)
        try {
            if (!LocationManagerCompat.registerGnssStatusCallback(manager, ContextCompat.getMainExecutor(appContext), callback)) {
                trySend(GnssSnapshot(observation = GnssObservation.Unavailable))
            }
        } catch (_: SecurityException) {
            trySend(GnssSnapshot(observation = GnssObservation.Unavailable))
        } catch (_: UnsupportedOperationException) {
            trySend(GnssSnapshot(observation = GnssObservation.Unavailable))
        }
        awaitClose { LocationManagerCompat.unregisterGnssStatusCallback(manager, callback) }
    }.conflate()
}
