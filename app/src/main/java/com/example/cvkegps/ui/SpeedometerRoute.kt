package com.example.cvkegps.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SpeedometerRoute(
    viewModel: SpeedometerViewModel,
    trackerControls: @Composable () -> Unit = {},
    onTrips: (() -> Unit)? = null,
) {
    val activity = LocalActivity.current ?: return
    val state by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val gnss by viewModel.gnssState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    var permissionNeedsSettings by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }

    fun refreshAccess() {
        // Permission results can change the recovery action without changing GPS status.
        permissionNeedsSettings = permissionRequested &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        viewModel.refreshAccess()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRequested = true
        refreshAccess()
    }

    LifecycleResumeEffect(activity, viewModel) {
        refreshAccess()
        onPauseOrDispose { }
    }

    val onAction: () -> Unit = {
            when (state.status) {
                GpsStatus.PermissionRequired -> {
                    if (permissionNeedsSettings) {
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
                GpsStatus.Paused -> Unit
                GpsStatus.Error -> viewModel.refreshAccess()
                GpsStatus.Unavailable -> Unit
                else -> Unit
            }
    }
    BackHandler(enabled = showDetails) { showDetails = false }
    if (showDetails) {
        GpsDetailsRoute(state, gnss, permissionNeedsSettings, onAction, onBack = { showDetails = false })
    } else {
        SpeedometerScreen(
            state = state,
            permissionNeedsSettings = permissionNeedsSettings,
            onAction = onAction,
            onDetails = { showDetails = true },
            gnss = gnss,
            trackerControls = trackerControls,
            onTrips = onTrips,
        )
    }
}
