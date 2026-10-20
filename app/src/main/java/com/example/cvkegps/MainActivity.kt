package com.example.cvkegps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.cvkegps.location.AndroidGpsSource
import com.example.cvkegps.ui.SpeedometerRoute
import com.example.cvkegps.ui.SpeedometerViewModel
import com.example.cvkegps.ui.theme.CvkeGpsTheme

class MainActivity : ComponentActivity() {
    private val viewModel: SpeedometerViewModel by viewModels {
        viewModelFactory {
            initializer { SpeedometerViewModel(AndroidGpsSource(applicationContext)) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CvkeGpsTheme {
                SpeedometerRoute(viewModel)
            }
        }
    }
}
