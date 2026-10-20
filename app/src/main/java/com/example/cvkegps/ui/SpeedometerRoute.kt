package com.example.cvkegps.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SpeedometerRoute(viewModel: SpeedometerViewModel) {
    val activity = LocalActivity.current ?: return
    val state by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRequested = true
        viewModel.refreshAccess()
    }

    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    val openAppSettings = permissionRequested &&
        !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)

    SpeedometerScreen(
        state = state,
        permissionNeedsSettings = openAppSettings,
        onAction = {
            when (state.status) {
                GpsStatus.PermissionRequired -> {
                    if (openAppSettings) {
                        activity.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", activity.packageName, null)),
                        )
                    } else {
                        launcher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        )
                    }
                }
                GpsStatus.Disabled -> activity.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                GpsStatus.Paused -> viewModel.setPaused(false)
                GpsStatus.Error -> viewModel.refreshAccess()
                GpsStatus.Unavailable -> Unit
                else -> viewModel.setPaused(true)
            }
        },
    )
}
