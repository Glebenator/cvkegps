package com.example.cvkegps

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cvkegps.tracking.TrackingApplication
import com.example.cvkegps.tracking.TrackingApp
import com.example.cvkegps.tracking.RecordingService
import com.example.cvkegps.ui.SpeedometerRoute
import com.example.cvkegps.ui.SpeedometerViewModel
import com.example.cvkegps.ui.theme.CvkeGpsTheme

class MainActivity : ComponentActivity() {
    private val tripsRequest = mutableIntStateOf(0)
    private val tracking get() = application as TrackingApplication
    private val viewModel: SpeedometerViewModel by viewModels {
        viewModelFactory {
            initializer { SpeedometerViewModel(tracking.location) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(RecordingService.OPEN_TRIPS, false)) tripsRequest.intValue++
        enableEdgeToEdge()
        setContent {
            CvkeGpsTheme {
                val recording by tracking.recorder.state.collectAsStateWithLifecycle()
                LaunchedEffect(recording.trip?.id) { if (recording.trip != null) viewModel.setPaused(false) }
                TrackingApp(tracking, tripsRequest.intValue) {
                    SpeedometerRoute(viewModel, onPause = { paused ->
                        val trip = tracking.recorder.state.value.trip
                        if (trip == null) viewModel.setPaused(paused)
                        else startService(Intent(this, RecordingService::class.java)
                            .setAction(if (paused) RecordingService.PAUSE else RecordingService.RESUME)
                            .putExtra(RecordingService.TRIP, trip.id))
                    })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(RecordingService.OPEN_TRIPS, false)) tripsRequest.intValue++
    }
}
