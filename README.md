# CVKE GPS

A native Android speedometer and trip recorder built with Kotlin, Jetpack Compose, and Material 3.
This work remains local. The existing Git remote was not changed or used for a push.

Open [PROGRESS.html](PROGRESS.html) in a browser for the current status, screenshots,
code excerpts, verification results, and required and optional future work.
Update the report with each completed change, as specified in `AGENTS.md`.

## Trip recording

1. Open **Trips**. Select Car, Transit, Plane, Bike, or Ski.
2. Allow precise location and recording notifications. Turn on location if required.
3. Select **Start trip**. Recording continues after Home or screen lock.
4. Use **Pause trip**, **Resume trip**, or **Stop and save** in the app or notification.
5. Open a saved trip to review its measurements and online map. Confirm deletion to remove it.

One mode applies to the whole trip. After process loss, open the interrupted trip and select Resume or Finish.
Resume starts a new segment. The app does not silently restart recording. The speed screen's Pause control also pauses an active trip.

Trips use local Room storage. One GPS request supplies both the recorder and live display. Historical samples are saved before display
updates can discard intermediate readings. Manual pauses and interruptions form separate segments. Missing GPS and poor positions do not
form connected route lines. Distance is an estimate. Plane mode reports ground speed and GPS altitude relative to WGS84.

Saved and interrupted trips use MapLibre with standard online OpenStreetMap tiles. Tile requests disclose the viewed area to OpenStreetMap.
The app does not upload trip records. Maps have visible attribution, an app User-Agent, and no prefetch or offline-download controls.
See the [tile policy](https://operations.osmfoundation.org/policies/tiles/). Map data can be unavailable; local trip measurements remain available.

The [implementation plan](docs/tracking-implementation-plan.html) records scope and the commit sequence.
The [HTML tracker](PROGRESS.html#tracking-verification) records builds, tests, native screenshots, and unverified cases.
Physical-device accuracy, battery use, flight reception, long trips, Doze, Battery Saver, and API 29/37 device flows remain open.
Offline maps, specialist layers, automatic pause, activity detection, export, and elevation totals are not implemented.

For the external process-loss and notification check, use a dedicated API 36 emulator with the debug APK installed:

```powershell
python scripts/check-interrupted-trip.py --adb <path-to-adb> --serial emulator-5554
```

This check clears emulator app data. It uses Android test locations, restores location and mock-location settings, and removes its test provider.
Keep the output in the ignored `artifacts/` folder. Use the three `TrackingDeviceTest` cases for the main recording flows.

## Current scope

- Show current GPS speed in km/h.
- Show GPS altitude, horizontal accuracy, and maximum speed since app launch.
- Request precise location through the Android permission dialog.
- Pause and resume GPS.
- Handle denied permission, disabled GPS, missing hardware, and loss of signal.
- Use system light or dark mode and dynamic colour on Android 12 and later.

The app records and saves trips locally. It does not detect activities or upload trip records.
GPS stops when the activity leaves the resumed state unless a trip is recording.
The app does not keep the screen on.

The local design export in `GPS tracker app prototype` is excluded from Git.
Design 2a supplies the main concept: a large speed display, altitude, and maximum speed.
The implementation uses native Material components and system typography.
It does not include controls for features that are not implemented.

The home screen does not scroll. The readings and GPS controls use their required
height. The speed dial uses the space that remains inside the system bars.
Landscape mode puts the readings beside the GPS controls. The app name and screen
heading are not repeated above the dial. Short windows show the speed and unit in
one row. Small dials omit the visible speed label. The screen-reader description
still identifies current speed and its unit. Measurement values fit on one line.

## Open and run

1. Open this folder in Android Studio.
2. Use JDK 25 for Gradle. The wrapper uses Gradle 9.6.0 and Android Gradle Plugin 9.4.1.
3. Install Android SDK platform 37. The minimum supported API is 29.
4. Let Android Studio create `local.properties` with your SDK path.
5. Run the `app` configuration on a device or emulator.
6. Select **Allow precise location**, then allow location while using the app.

From a terminal with `JAVA_HOME` set to the Gradle JDK:

```powershell
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

On macOS or Linux, use `./gradlew`.

## Structure

```text
app/src/main/java/com/example/cvkegps/
  MainActivity.kt                 Activity and dependency creation
  location/
    GpsSource.kt                 Flow contract and SI measurement models
    AndroidGpsSource.kt          Android GPS registration and cleanup
  ui/
    SpeedometerViewModel.kt      Immutable state, freshness, and session maximum
    SpeedometerRoute.kt          Permission and lifecycle integration
    SpeedometerScreen.kt         Stateless Material screen
    theme/Theme.kt               Dynamic colour and fallback palettes
```

State flows from the location source through the ViewModel to the screen.
User actions flow back through explicit callbacks. Constructor injection keeps the first
feature small. A dependency injection framework and extra modules are not needed yet.

The measurement contract contains no Android location objects. Use the same units and
freshness rules for the future iOS app, with native location and UI implementations.
Do not add shared platform code until both apps have a clear need.

## Tracking verification on 2026-10-21

Debug and unsigned release builds, debug and release lint, three calculation tests, and eight API 36 emulator tests passed.
The emulator run used the working-tree APK, which includes the earlier GPS display changes. A separate selected-tree build
passed without those display changes. The external check confirmed notification-permission recovery and saved points after
force-stop. Screenshots use the tested APK. See [the tracking evidence](PROGRESS.html#tracking-verification) for its SHA-256,
test scope, resolved failures, and untested cases. Field accuracy, power use, and long trips still need physical devices.

## GPS and battery rules

The source uses Android's GNSS provider through `LocationManagerCompat`. It does not need
Google Play services. GNSS supplies speed and altitude directly; the app does not estimate
speed from the distance between noisy position fixes.

- Request one update per second while a visible display or an active trip needs GPS.
- Cancel the request when the last display collector stops, unless a trip is recording. Trip Pause cancels both demands.
- Do not request background location, hold a wake lock, or poll for location.
- Use one cancellable freshness deadline after each fix.
- Hide live fixes that are at least 10 seconds old or have a future timestamp. Keep valid historical points in trip storage.
- Hide speed and altitude when horizontal accuracy exceeds 50 m.
- Hide speed when its reported uncertainty exceeds 3 m/s.
- Keep unavailable values distinct from zero.

These are initial quality thresholds. Validate them on real hardware before release.
The maximum uses accepted readings only. It survives activity recreation and time in the
background. It resets when the activity session ends, such as after Back or process termination.
Altitude is the GPS altitude, not a calibrated elevation above sea level. The first version
uses metric units.

The request lifetime follows
[Android location battery guidance](https://developer.android.com/develop/sensors-and-location/location/battery).
Foreground permission follows
[Android location permission guidance](https://developer.android.com/develop/sensors-and-location/location/permissions).
Compose dependencies use the
[Compose BOM](https://developer.android.com/develop/ui/compose/bom).

## Device tests

Use a dedicated emulator with an English locale. The suite changes the GPS test provider
and restores it after each test. Android Test Orchestrator clears this app's data between
tests to isolate permission state. Do not run it against app data that you need.

Start an emulator from Android Studio, then run:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

The tests cover:

1. Deny permission, request it again, and receive GPS through the system dialog.
2. Deny permission twice, retain the settings action across activity recreation,
   open app settings, and receive GPS after Android's test API grants permission.
3. Display speed, altitude, and maximum speed across activity recreation.
4. Reject a poor fix without increasing the maximum.
5. Clear an old reading and recover when a new fix arrives.
6. With no active trip, remove GPS requests during pause and in the background; resume correctly.
7. Recover after GPS is disabled and enabled.
8. Keep readings, GPS controls, and text inside the home screen at 100%, 110%, and 200%
   text size in portrait and landscape. Check permission, live, paused, and
   disabled-location states, and check dark mode. Restore the display settings after
   the check.

The tests inject locations into the Android location service. They do not replace the
production source or ViewModel. Listener checks inspect current Android registrations.
These tests prove functional behaviour with controlled input. They do not prove real
satellite accuracy, battery use, or performance on a physical device.

Before a release, also run:

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease
```

Inspect light mode, dark mode, landscape, and 200% text. Check TalkBack on a physical
device. Measure cold start and frame timing with a release build. Measure GPS battery
use during a representative outdoor session, with a matching idle baseline.

Lint treats warnings as errors. Dependency update notices are excluded from this gate.
Review dependency updates separately so a new remote release cannot break an unchanged
build. The Compose compiler version matches the Kotlin version supplied by AGP.

## Earlier verification on 2026-10-20

- The final home-layout debug app and test APK builds passed. Debug lint reported no issues.
- An unsigned release APK built before the final home-layout changes. Repeat the release
  build before publication.
- The latest release lint run was stopped because the host was low on memory.
  Release lint passed for the initial baseline; repeat it before publication.
- Seven GPS and permission tests passed on an Android 16 (API 36) emulator.
  Each test used a separate AndroidJUnitRunner invocation with app data cleared first.
  This run did not use Orchestrator and did not include the separate layout test.
- The repeated-denial test opens Android App info, grants permission through
  `UiAutomation`, and checks GPS after return. A separate direct device check
  passed through the actual Settings permission controls.
- The seven core tests used the APK before the final short-window correction.
  The separate home-layout test passed on the final APK. It checked 100%, 110%, and 200%
  text in portrait and landscape, permission and live states, pause and resume,
  location-off recovery, and dark mode.
- The layout emulator used 1080 × 2340 pixels at 420 dpi. This gives the same logical
  size as the connected Pixel 7 Pro at 1440 × 3120 pixels and 560 dpi: about 411 × 891 dp.
  The test found no scroll area, clipped text, or controls outside the home content.
- Light mode, dark mode, and large text were inspected on the final APK.
  The selected captures are in `docs/progress/images/`. Location data is simulated.
  The emulator settings were restored and the GPS test provider was removed.
- The final debug APK has SHA-256
  `1a12d63fdb144626fb5e75c607990a7d840ce4b934f94844d258f7c02334877d`.
  Local build, layout-test, and hash records are in `artifacts/layout-final-build.log`,
  `artifacts/layout-device-final.log`, and `artifacts/layout-final-evidence.json`.
- The update was not installed on the physical Pixel 7 Pro. Other window sizes,
  display-size settings, and languages still need checks.
- Real-device GPS accuracy, TalkBack, frame timing, and battery use remain unmeasured.

See [the progress report](PROGRESS.html#verification) for the dated evidence and
[the location power research](docs/location-power-research.md) for the recording plan.

## Future work

Follow `AGENTS.md` for architecture, design, privacy, and test requirements.
Route recording now uses local storage and a foreground service. Field validation and the publication requirements remain open.

Before publication, select the final application ID and launcher artwork, configure release
signing outside Git, and complete the store and privacy requirements. The current
`com.example.cvkegps` ID is a development ID. The release APK is unsigned.
