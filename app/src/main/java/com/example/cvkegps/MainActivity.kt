package com.example.cvkegps

import android.os.Bundle
import android.os.SystemClock
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cvkegps.tracking.TrackingApplication
import com.example.cvkegps.tracking.RecordingService
import com.example.cvkegps.location.AndroidGnssSource
import com.example.cvkegps.location.AndroidHeadingSource
import com.example.cvkegps.ui.CompassViewModel
import com.example.cvkegps.ui.AppShell
import com.example.cvkegps.ui.SpeedometerViewModel
import com.example.cvkegps.ui.theme.CvkeGpsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainActivity : ComponentActivity() {
    private val compassViewModel: CompassViewModel by viewModels {
        viewModelFactory { initializer { CompassViewModel(AndroidHeadingSource(applicationContext)) } }
    }
    private val tripsRequest = mutableIntStateOf(0)
    // The uptime of the last touch or key press. A screen that the app keeps on dims after a time without one.
    private val lastTouch = MutableStateFlow(SystemClock.uptimeMillis())
    private val touches = lastTouch.asStateFlow()
    private val tracking get() = application as TrackingApplication
    private val viewModel: SpeedometerViewModel by viewModels {
        viewModelFactory {
            initializer { SpeedometerViewModel(tracking.location, AndroidGnssSource(applicationContext)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity keeps its saved destination. Only a new launch follows the notification.
        if (savedInstanceState == null && intent.getBooleanExtra(RecordingService.OPEN_TRIPS, false)) tripsRequest.intValue++
        enableEdgeToEdge()
        setContent {
            val settings by tracking.settings.values.collectAsStateWithLifecycle()
            CvkeGpsTheme(colours = settings.colours) {
                AppShell(tracking, viewModel, compassViewModel, tripsRequest.intValue, touches)
            }
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        lastTouch.value = SystemClock.uptimeMillis()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(RecordingService.OPEN_TRIPS, false)) tripsRequest.intValue++
    }
}
