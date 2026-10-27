package com.example.cvkegps.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

const val PACKAGE = "com.example.cvkegps"

// The tab labels in English. Run the journey on a device with an English locale.
private const val SPEED_TAB = "Speed"
private const val TRIPS_TAB = "Trips"

/** Cold start to the Speed tab. Location access is not granted, so the journey does not need GPS. */
fun MacrobenchmarkScope.startApp() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.text(TRIPS_TAB)), 5_000)
}

/** The tabs that most users open after start: Trips, then Speed again. */
fun MacrobenchmarkScope.openTabs() {
    device.findObject(By.text(TRIPS_TAB))?.click()
    device.waitForIdle()
    device.findObject(By.text(SPEED_TAB))?.click()
    device.waitForIdle()
}
