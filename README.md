# CVKE GPS

A native Android speedometer and trip recorder built with Kotlin, Jetpack Compose, and Material 3.
This work remains local. The existing Git remote was not changed or used for a push.

Open [PROGRESS.html](PROGRESS.html) in a browser for the current status, screenshots,
code excerpts, verification results, and required and optional future work.
Update the report with each completed change, as specified in `AGENTS.md`.

## Trip recording

1. Open the app. Allow precise location when requested. Speed starts without a GPS button.
2. Select **Start map tracking**. Read the recording and map notice. Allow notifications.
3. View the live route and measurements. Use **Pause trip**, **Resume trip**, or **Stop and save**.
   A map gesture stops following. Select **Follow route** to follow the latest position again.
4. Select the activity label to correct the estimate. The correction applies to the whole trip.
5. Select **Trips** to review saved routes. Confirm deletion to remove a trip.

Activity estimation uses recent GPS speed. The initial categories are **Bike or ski**, **Car or transit**,
and a possible **Plane** trip. The app shows **Activity unknown** until it has enough valid evidence.
These categories are provisional. GPS speed cannot reliably separate all five activities.
A manual correction stays in effect after Pause, Resume, and process recovery.

After process loss, open the interrupted trip and select Resume or Finish. Resume starts a new segment.
The app does not start or restart recording automatically. GPS display stops when the tracker is inactive,
unless a trip is recording. Trip Pause stops the recording request.

Trips use local Room storage. One GPS request supplies both the recorder and live display. Historical samples are saved before display
updates can discard intermediate readings. Manual pauses and interruptions form separate segments. Missing GPS and poor positions do not
form connected route lines. Distance is an estimate. Plane mode reports ground speed and GPS altitude relative to WGS84.

Saved and interrupted trips use MapLibre with standard online OpenStreetMap tiles. Tile requests disclose the viewed area to OpenStreetMap.
The app does not upload trip records. Maps have visible attribution, an app User-Agent, and no prefetch or offline-download controls.
See the [tile policy](https://operations.osmfoundation.org/policies/tiles/). Map data can be unavailable; local trip measurements remain available.

The [implementation plan](docs/tracking-implementation-plan.html) records scope and the commit sequence.
The [HTML tracker](PROGRESS.html#tracking-verification) records builds, tests, native screenshots, and unverified cases.
Physical-device accuracy, battery use, flight reception, long trips, Doze, Battery Saver, and API 29/37 device flows remain open.
Offline maps, specialist layers, automatic pause, sensor-based activity classification, export, and elevation totals are not implemented.

For the external process-loss and notification check, use a dedicated API 36 emulator with the debug APK installed:

```powershell
python scripts/check-interrupted-trip.py --adb <path-to-adb> --serial emulator-5554
```

This check clears emulator app data. It uses Android test locations, restores location and mock-location settings, and removes its test provider.
Keep the output in the ignored `artifacts/` folder. Use `TrackingDeviceTest` for recording, activity correction, and tracker layout checks.

## Current scope

- Show current GPS speed in km/h.
- Show GPS altitude, horizontal accuracy, and maximum speed since app launch.
- Request precise location through the Android permission dialog.
- Start map tracking from home. Pause and resume the trip.
- Handle denied permission, disabled GPS, missing hardware, and loss of signal.
- Use system light or dark mode and dynamic colour on Android 12 and later.
- Open GPS details for reading quality, receiver events, and satellite signals.
- Show a constellation behind the speed display, with live satellite counts and system markers.
- Show a position accuracy bar that fills at 1 m or better, with a 5 m reference notch.

The app saves trips locally and estimates the activity from GPS speed. It does not upload trip records.
GPS stops when the activity leaves the resumed state unless a trip is recording.
The app does not keep the screen on.

## GPS details

Select the information button beside the GPS status to open **GPS details**.
Use the back arrow or Android Back to return.
This screen shares the speedometer's location request. It shows:

- The source, requested interval, reading count, and age of the last reading.
- Why speed was accepted or hidden, plus horizontal, vertical, speed, and direction uncertainty.
- Direction of travel from true north and the Android test-location flag.
- The last receiver start or stop event and Android's time to first position, when reported.
- Satellite systems and IDs, signal level (C/N0), elevation, azimuth, carrier frequency,
  and use in the latest position calculation.

Satellite status comes from a separate Android GNSS callback. This callback does not
create a second location request. Home and details share one callback while the app is
resumed and GPS is active. Trip Pause, disabled location, and app inactivity remove it.
Back from details keeps the callback for the home display. The age display updates
once per second only while details is resumed.

An unavailable satellite report is not a report with zero satellites. A satellite can
have more than one signal; the summary counts distinct system and satellite IDs.
Signal rows and counts clear when their report is 10 seconds old. Reading quality values
also clear when the location reading is old. The reading count resets with each location
request and counts callbacks before display conflation. It includes rejected readings.
Receiver events describe the shared Android receiver and can include use by other apps.
First-position time can be unavailable when its event preceded callback registration.

## Satellite display experiment

The home display has one marker per satellite. Multiple frequency bands do not add
markers or increase the count. A bright, filled marker means that Android used the
satellite in the latest position. A dim, open marker means that Android reported it
but did not use it. The count shows both used and reported satellites.

GPS uses circles, GLONASS uses diamonds, Galileo uses triangles, and BeiDou uses squares.
QZSS uses pentagons, NavIC uses hexagons, and SBAS uses crosses. Unknown systems use
circles with a centre mark. GPS details shows these shapes beside the system names.
Colours follow the Material theme. Shape and text also identify each system.

The pattern is decorative. Azimuth sets each marker's direction. Elevation sets its
distance within an outer band that keeps the speed clear. Short lines join markers
from the same system. This is not a sky map. Invalid angles use a stable decorative
position. Marker changes and the accuracy bar animate for 650 ms, then stop. Compose
uses the Android animation duration setting. There is no continuous animation.

The position bar uses Android's horizontal accuracy estimate. It is empty at 50 m or
more and full at 1 m or less. A notch marks 5 m. The scale is logarithmic, so it gives
more space to improvements below 5 m. At 5 m, the bar is about 59% full. At 2 m, it is
about 82% full. These percentages describe the display, not a probability of accuracy.
The text shows decimal values, including estimates below 1 m. A positive estimate below
0.1 m is shown as "below 0.1 m" instead of zero.

Android does not report a fixed best possible accuracy for the receiver. The endpoints
and notch are display references, not receiver limits or a measured device capability.
A full bar does not guarantee position or speed accuracy. Satellite count does not
control it. Validate these display references on physical devices.
Missing, invalid, and old readings do not fill the bar. Satellite counts and markers
clear after 10 seconds without a fresh report. Missing reports are not zero counts.

Accuracy values are estimates, not guaranteed error limits. Direction of travel is not
the phone compass. This screen does not record coordinates, raw measurements, or NMEA
messages. Support for optional fields depends on Android and the receiver.
See the Android references for [location readings](https://developer.android.com/reference/android/location/Location),
[satellite status](https://developer.android.com/reference/android/location/GnssStatus), and
[receiver events](https://developer.android.com/reference/android/location/GnssStatus.Callback).

The local design export in `GPS tracker app prototype` is excluded from Git.
Design 2a supplies the main concept: a large speed display, altitude, and maximum speed.
The implementation uses native Material components and system typography.
It does not include controls for features that are not implemented.

The home screen does not scroll. The readings and tracker controls use their required
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
    GnssSource.kt                Satellite and receiver models
    AndroidGnssSource.kt         Satellite status registration and cleanup
  ui/
    SpeedometerViewModel.kt      Immutable state, freshness, and session maximum
    SpeedometerRoute.kt          Permission and lifecycle integration
    SpeedometerScreen.kt         Stateless Material screen
    GpsDetailsScreen.kt          Reading quality and satellite status
    SatelliteConstellation.kt    Satellite markers and finite transitions
    PositionAccuracy.kt          Accuracy scale, notch, and labels
    theme/Theme.kt               Dynamic colour and fallback palettes
```

State flows from the location source through the ViewModel to the screen.
User actions flow back through explicit callbacks. Constructor injection keeps the first
feature small. A dependency injection framework and extra modules are not needed yet.

The measurement contract contains no Android location objects. Use the same units and
freshness rules for the future iOS app, with native location and UI implementations.
Do not add shared platform code until both apps have a clear need.

## Tracker correction on 2026-10-21

App code baseline: `dd886b5`. The tested working app also includes the earlier GPS display work.
Debug and unsigned release builds, both lint checks, five calculation tests, and ten distinct emulator
checks passed during this correction. Four relevant checks passed again after the final map change.
Migration preserved the saved trip, two segments, and six positions. External checks confirmed
notification recovery and saved points after force-stop. See the HTML report for captures and limits.
Activity classification, GPS accuracy, battery use, and long trips still need physical-device tests.

## Earlier tracking verification on 2026-10-21

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
6. With no active trip, remove GPS requests in Trips and in the background; resume the display correctly.
7. Recover after GPS is disabled and enabled.
8. Keep readings, GPS controls, and text inside the home screen at 100%, 110%, and 200%
   text size in portrait and landscape. Check permission, live, and
   disabled-location states, and check dark mode. Restore the display settings after
   the check.
9. Open GPS details, grant permission, and verify one shared location request. Check
   reading rejection reasons and freshness. Verify satellite callback cleanup after
   location-off and app inactivity, with recovery and activity recreation.
   Back from details keeps one shared callback on home.
10. Check details at 100% and 200% text in portrait and landscape, plus dark mode.
    Verify scrolling and text wrapping. Restore settings after the check.
11. Use controlled GNSS reports to check multiple bands, zero and missing reports,
    expiry, recovery, invalid accuracy, and pause. Capture native UI in both themes
    and with large text. These reports are supplied only by the test source.

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

## Verification on 2026-10-21

### Accuracy scale update

- Changed the full point from 5 m to 1 m and added a 5 m notch. The scale gives more
  space to small estimates. Accuracy text now preserves one decimal place.
- Debug app and test APK builds passed. Debug lint found no issues. All four focused
  emulator tests passed: scale and grouping rules, controlled GNSS display, home layout,
  and details layout. Layout checks covered both themes, rotation, and large text.
  The earlier 12-test result below applies to the previous APK.
- Debug APK SHA-256: `5eff9c130433a74b88a136b6c451bac704772a2163e3d6e917260b7f5b140e77`.
- Captures are in `docs/progress/images/2026-10-21-accuracy-scale-*.png`. They use
  simulated location and satellite reports. Test records are in
  `artifacts/accuracy-scale-test-results.json`. Settings were restored and test providers
  were removed. Physical-device accuracy and battery checks remain open.

### Earlier constellation experiment

- The constellation experiment builds on `8b5ea06` and the existing GPS details work.
  Debug app and test APK builds passed. Debug lint found no issues.
- All 12 tests passed on the dedicated API 36 emulator. Each test ran in a separate
  AndroidJUnitRunner invocation, with app data cleared first. This run did not use Orchestrator.
- The tests cover production location delivery and callback cleanup, permission recovery,
  signal loss, rotation, light and dark modes, and 100%, 110%, and 200% text.
  Controlled GNSS tests cover grouping, empty and missing reports, expiry, future timestamps,
  recovery, invalid accuracy, and pause. They do not test receiver hardware.
- Large-text testing found a clipped pause label in landscape. Removing the repeated
  lifecycle note from the live home card fixed it. The permission helper now activates
  the actual Android accessibility control after a coordinate tap failed.
- Native light, dark, and large-text captures are in `docs/progress/images/`, dated
  2026-10-21. They use test location and satellite reports. The emulator settings were
  restored and the GPS test provider was removed.
- Debug APK SHA-256: `5c60bf1013a9bbd711bfdc6b9b1dd2238476e1f67b8507465ffef1a67e362a2b`.
  Build and test records are in `artifacts/constellation-build.log` and
  `artifacts/constellation-test-results.json`.
- No physical-device check was done for this change. Live satellite accuracy, battery use,
  motion smoothness, and TalkBack remain unmeasured. Repeat release checks before publication.

## Earlier verification on 2026-10-20

### GPS details update

- Added GPS details on source baseline `8b5ea06`. The installed debug APK has SHA-256
  `1bd783992c4d7746df3b29a40c980fed82e951ab492d9660c08343683a03260c`.
- The debug app and test APK built, and debug lint passed before the last test-helper edit.
- The user ran the installed app on the Pixel 7 Pro and reported that it worked correctly.
  This is a user-reported manual check. No satellite report was retrieved from the phone.
- The final home layout test passed. Earlier GPS-details layout and lifecycle runs passed,
  as did the seven core tests, before the home information-button change.
- The final automated rerun is incomplete. It passed the home layout test, then failed
  inside UI Automator while waiting for the permission dialog. The last test-helper change
  removes that failing call. It has not been built or run.
- Further tests and the pending build were stopped at the user's request.
  This milestone does not claim a complete final regression pass.
- Selected details captures show the earlier feature build (`5d46104d` APK prefix), before
  the home button changed. They use simulated locations. They show light mode, dark mode,
  and 200% text. Real satellite accuracy and battery use remain unmeasured.

### Earlier home-layout milestone

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
- A later indoor check found the app installed and active on the physical Pixel 7 Pro.
  Precise permission was granted, location was enabled, and the one-second GPS request
  was active. Android reported zero locations delivered to the app. The user reported
  that Google Maps showed a location. Maps can use other sources; this app requests
  satellite GPS only. Poor indoor reception is a possible cause, not a confirmed diagnosis.
  Outdoor acquisition remains unverified. Source baseline: `8b5ea06`. The installed
  app reports version 1.0; its exact build was not confirmed. No coordinates were saved.
  Other window sizes, display-size settings, and languages still need checks.
- Real-device GPS accuracy, TalkBack, frame timing, and battery use remain unmeasured.

See [the progress report](PROGRESS.html#verification) for the dated evidence and
[the location power research](docs/location-power-research.md) for the recording plan.

## Future work

Follow `AGENTS.md` for architecture, design, privacy, and test requirements.
Route recording now uses local storage and a foreground service. Field validation and the publication requirements remain open.

Before publication, select the final application ID and launcher artwork, configure release
signing outside Git, and complete the store and privacy requirements. The current
`com.example.cvkegps` ID is a development ID. The release APK is unsigned.
