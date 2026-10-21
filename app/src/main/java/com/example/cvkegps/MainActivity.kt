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
import com.example.cvkegps.location.AndroidGnssSource
import com.example.cvkegps.ui.SpeedometerRoute
import com.example.cvkegps.ui.SpeedometerViewModel
import com.example.cvkegps.ui.theme.CvkeGpsTheme

class MainActivity : ComponentActivity() {
    private val tripsRequest = mutableIntStateOf(0)
    private val tracking get() = application as TrackingApplication
    private val viewModel: SpeedometerViewModel by viewModels {
        viewModelFactory {
            initializer { SpeedometerViewModel(tracking.location, AndroidGnssSource(applicationContext)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(RecordingService.OPEN_TRIPS, false)) tripsRequest.intValue++
        enableEdgeToEdge()
        setContent {
            CvkeGpsTheme {
                TrackingApp(tracking, tripsRequest.intValue) { controls, onTrips ->
                    SpeedometerRoute(viewModel, trackerControls = controls, onTrips = onTrips)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(RecordingService.OPEN_TRIPS, false)) tripsRequest.intValue++
    }
}
