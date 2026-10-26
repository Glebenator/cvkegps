package com.example.cvkegps.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Location access recovery. Speed and GPS details share it, so it does not start GPS by itself.
 * Navigation keeps screen content between compositions, so the settings state is read when used.
 */
class GpsAccess(private val settings: State<Boolean>, val act: (GpsStatus) -> Unit) {
    val needsSettings get() = settings.value
}

@Composable
fun rememberGpsAccess(viewModel: SpeedometerViewModel): GpsAccess? {
    val activity = LocalActivity.current ?: return null
    var permissionRequested by rememberSaveable { mutableStateOf(false) }
    val settings = rememberSaveable { mutableStateOf(false) }
    var permissionNeedsSettings by settings

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

    return remember(viewModel) { GpsAccess(settings) { status ->
        when (status) {
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
            GpsStatus.Error -> viewModel.refreshAccess()
            else -> Unit
        }
    } }
}

@Composable
fun SpeedometerRoute(
    viewModel: SpeedometerViewModel,
    access: GpsAccess,
    onDetails: () -> Unit,
    trackerControls: @Composable (canStartTrip: Boolean, landscape: Boolean) -> Unit = { _, _ -> },
    compassViewModel: CompassViewModel? = null,
    onSettings: (() -> Unit)? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val gnss by viewModel.gnssState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    SpeedometerScreen(
        state = state,
        permissionNeedsSettings = access.needsSettings,
        onAction = { access.act(state.status) },
        onDetails = onDetails,
        gnss = gnss,
        trackerControls = trackerControls,
        onSettings = onSettings,
        heading = {
            compassViewModel?.let { collectPhoneHeading(it, state.reading?.magneticDeclinationRadians) }
                ?: PhoneHeading.Unavailable
        },
    )
}

@Composable
fun GpsDetailsDestination(
    viewModel: SpeedometerViewModel,
    access: GpsAccess,
    onBack: () -> Unit,
    compassViewModel: CompassViewModel? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    val gnss by viewModel.gnssState.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.RESUMED)
    GpsDetailsRoute(state, gnss, access.needsSettings, { access.act(state.status) }, onBack, compassViewModel = compassViewModel)
}
