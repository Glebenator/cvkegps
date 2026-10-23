# CVKE GPS

A native Android speedometer and trip recorder built with Kotlin, Jetpack Compose, and Material 3.
This work remains local. The existing Git remote was not changed or used for a push.

Open [PROGRESS.html](PROGRESS.html) in a browser for the current status, screenshots,
code excerpts, verification results, and required and optional future work.
Update the report with each completed change, as specified in `AGENTS.md`.

## Trip recording

1. Open the app. Allow precise location when requested. Speed starts without a GPS button.
2. Select **Start map tracking**. Read the recording and map notice. Allow notifications.
3. A recording bar shows above the navigation bar. Use its **Pause trip**, **Resume trip**, or **Stop and save** buttons.
   Select the bar text to open **Current trip** with the live map. Use the back arrow or Android Back to return.
   A map gesture stops following. Select the **Follow route** button to follow the latest position again.
4. In **Current trip**, select the information button, then select **Change** to correct the activity estimate.
   The correction applies to the whole trip.
5. Select the **Trips** tab, then select a trip. Pan or pinch its map, use the zoom buttons, or select **Fit route**.
   The information button opens trip details. The menu button contains **Change activity**, **Share as GPX**, and **Delete trip**.

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
Offline maps, specialist layers, automatic pause, sensor-based activity classification, GPX import, and elevation totals are not implemented.

For the external process-loss and notification check, use a dedicated API 36 emulator with the debug APK installed:

```powershell
python scripts/check-interrupted-trip.py --adb <path-to-adb> --serial emulator-5554
```

This check clears emulator app data. It uses Android test locations, restores location and mock-location settings, and removes its test provider.
Keep the output in the ignored `artifacts/` folder. Use `TrackingDeviceTest` for recording, activity correction, and tracker layout checks.

## Navigation

The app has two tabs: **Speed** and **Trips**. A navigation bar shows them in portrait. A navigation rail shows them in landscape.
The tabs show only on their main screens. GPS details, the current trip, and saved trips use a top app bar with a back arrow.
Android Back from the Trips tab returns to Speed. Select the current tab again to return to its main screen.
The recording notification opens the current trip. Navigation uses Navigation 3 with an app-owned back stack for each tab.

## App icon

The launcher icon uses the confirmed Signal dial mark: an open speed ring, a short needle, and one satellite point.
It is an adaptive vector icon with a monochrome layer for themed icons. `PROGRESS.html` uses the same mark as its favicon.

## Review fixes on 23 October 2026

The user approved eight fixes from a UX and code review. Each fix is a separate local commit.
See [the fix list](PROGRESS.html#ux-review) for status and evidence.

- The activity estimate uses an explicit null check instead of the `!!` operator. Behaviour does not change.
- `ui/DisplayFormat.kt` formats speed, distance, and time from SI values in the user's locale. Screens and the notification use it.
- Each trip screen has its own file. `TrackingController` is a plain Kotlin class for recording commands, access checks, and settings actions.

## GPX export on 23 October 2026

Select **Share as GPX** in the saved trip menu. The app writes a GPX 1.1 file and opens the Android share sheet.
The app does not upload the file; the app that you select receives it. Each route line is a separate track segment,
so gaps are not joined. Points have latitude, longitude, and UTC time. The file does not include altitude, because
Android reports height above the WGS84 ellipsoid. Only the latest export stays in the private cache. Trip delete removes it.

Debug build, Android lint, 15 calculation tests, and all 11 `TrackingDeviceTest` tests passed on the API 36 emulator.
Opening the file in another app and a physical device remain unchecked. See [the report](PROGRESS.html#gpx-export).

## Screen transitions on 23 October 2026

Screen changes use 300 ms Material motion instead of the 700 ms Navigation 3 fade. GPS details, Current trip,
and saved trips use a short horizontal shift with a fade through. Back reverses the direction. Tabs use a fade through.
The app shell paints the theme background, so no dark background shows during a fade. Trip list entries fade in.

A device test uses the Compose test clock to check that each screen change ends within 350 ms. It failed with the
Navigation 3 default, as expected. All 25 emulator device tests passed; one flaky setup wait passed on rerun.
Motion on a physical device, the predictive Back gesture by hand, and right-to-left languages remain unchecked.
See [the report](PROGRESS.html#transitions).

## Trip polish on 23 October 2026

Times below one minute show seconds only. Values and units do not break across lines at large text.
Saved trip titles and list entries show the date and time. An unknown activity shows **Activity unknown**.
The empty Trips tab tells you how to record a trip. Current trip shows the recorded distance.
Saved trips show the maximum speed. The notification title shows **Recording trip** or **Trip paused**.
While recording, the Android chronometer in the notification shows the time without extra notification updates.

Debug build, Android lint, 13 calculation tests, and all 9 `TrackingDeviceTest` tests passed on the API 36 emulator.
Captures use simulated emulator locations. The notification on a physical device, TalkBack, other languages, and a
release build remain unchecked. See [the report](PROGRESS.html#trip-polish).

## UI polish on 22 October 2026

Recording continues while the main GPS screen or Trips is open. The GPS card reduces to its bar
when a fresh position has an accuracy estimate of 5 m or better. Poor or old readings expand the card.
Select the GPS card for the full accuracy scale and satellite reports.

Saved trips open on a large map. Map gestures and trip-detail scrolling are separate.
The camera stays in place when details open and after activity recreation. Short routes use an initial
zoom no greater than 17. In landscape, the summary sits beside the map. The trip list keeps Back visible.
Distances below 1 km use metres. Durations show minutes and seconds, or hours and minutes.

Debug build, test build, Android lint, 13 calculation tests, and 12 API 36 emulator checks passed.
Checks cover recording, screen lock, notification controls, GPS recovery, map gestures, camera restoration,
light and dark themes, rotation, and 200% text. These checks use simulated locations. Physical-device
accuracy, battery use, TalkBack, and long trips remain unverified. See [the report](PROGRESS.html#ui-polish).

## Current scope

- Show current GPS speed in km/h.
- Show GPS altitude, horizontal accuracy, and maximum speed since app launch.
- Request precise location through the Android permission dialog.
- Start map tracking from home. Pause and resume the trip.
- Handle denied permission, disabled GPS, missing hardware, and loss of signal.
- Use system light or dark mode and dynamic colour on Android 12 and later.
- Open GPS details for reading quality, receiver events, and satellite signals.
- Show an abstract satellite ring around the speed. Satellites are in their reported directions and turn with the phone.
  A small N marks north, and brighter markers have stronger signals.
- Show a position accuracy bar that fills at 1 m or better, with a 5 m reference notch.

The app saves trips locally and estimates the activity from GPS speed. It does not upload trip records.
GPS stops when the activity leaves the resumed state unless a trip is recording.
The app does not keep the screen on.

## GPS details

Select the GPS status card on the Speed tab to open **GPS details**.
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

## Satellite ring and sky map

The home screen shows an abstract satellite ring around the speed. **GPS details** shows a
full sky map. Both use the direction and elevation reported by Android for each satellite.
The top follows the direction of the screen's upper edge. Hold the phone approximately flat.
This is a flat sky projection, not a view through the camera.

The home ring has two faint rings. The outer ring is the horizon and the inner ring is
directly overhead, so the speed cannot hide a satellite. Radius changes in direct proportion
to elevation. A small N marks north and turns with the satellites. The ring has no other
direction text, ticks, or elevation guides. Short links join satellites of one system; they
have no measurement meaning. The ring reports the phone direction to screen readers. It needs
a dial of at least 300 dp in both directions. A smaller dial, such as landscape on a typical
phone, shows the speed without the ring.

The details map puts the horizon at the edge and overhead at the centre. Dotted rings mark 30
and 60 degrees of elevation. Ticks and direction labels turn with the satellites. A fixed index
at the top marks the phone direction, and text states the direction in degrees.

Brighter markers have stronger signals. Brightness and glow follow C/N0 on a linear display
scale: dim at 15 dB-Hz or less and full at 45 dB-Hz or more. A missing value is dim. Stronger
markers are drawn on top. These references are display limits, not receiver limits.

Only satellites reported by the receiver are shown. The map does not predict all satellites
in orbit. Invalid angles and satellites below the horizon are omitted. The text below the
map shows how many reported satellites could be plotted. Reports expire after 10 seconds.
An empty report and a missing report have different states. The map shares the existing
GPS request and satellite callback. It adds no location or network request.

The compass uses Android's rotation vector sensor, with the geomagnetic rotation vector
as a fallback. It requests readings every 100 ms only while the details map or a home ring
with satellites is composed and the activity is resumed. Paused GPS, a hidden ring, leaving
the map, or putting the app in the background removes the sensor listener. Compass readings
expire after two seconds. Unreliable readings and a nearly vertical upper edge cannot set the
direction. A new listener has one second to report before the display states that the compass
is unavailable. Heading changes below one degree do not redraw the display. Larger changes
turn it with a short spring that stops when the heading stops changing.

Android compass readings use magnetic north. The existing GPS fix supplies a magnetic
correction from Android's geomagnetic model. No coordinates are added to the display
model or stored for this correction. The correction uses a current position with an
accuracy estimate of 50 m or better. The last correction stays in memory while GPS
searches, because it changes slowly with position. It is not stored. The displays state when
the compass or correction is unavailable; they then show north at the top. They do not use
direction of travel as a phone heading. Physical-device calibration, interference, and power
use need checks.

The ring and the map have one marker per satellite. Multiple frequency bands do not add
markers or increase the count. The strongest band supplies the angles. A filled marker means
that Android used the satellite in the latest position. An open marker means that Android
reported it but did not use it. GPS uses circles, GLONASS uses diamonds, Galileo uses
triangles, and BeiDou uses squares. QZSS uses pentagons, NavIC uses hexagons, and SBAS uses
crosses. Unknown systems use circles with a centre mark. The signal list gives the system,
ID, angles, and use in text. Colours follow the Material theme.

The home ring keeps the style of the earlier decorative pattern, but positions now use the
reported angles and the phone heading. Markers can overlap when satellites have the same
direction and elevation. Text inside the dial can overlap markers near overhead. The accuracy bar still has a finite 650 ms transition. There
is no continuous animation.

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
    AndroidHeadingSource.kt      Compass registration and display axes
  ui/
    AppNavigation.kt             Destinations and a back stack for each tab
    AppShell.kt                  Navigation bar or rail, recording bar, and screens
    SpeedometerViewModel.kt      Immutable state, freshness, and session maximum
    CompassViewModel.kt          Heading states, freshness, correction, and screen rotation
    SpeedometerRoute.kt          Permission and lifecycle integration
    SpeedometerScreen.kt         Stateless Material screen
    GpsDetailsScreen.kt          Reading quality and satellite status
    PhoneSkyPlot.kt              Compass collection and the details sky map
    SatelliteConstellation.kt    Sky ring and map drawing and system markers
    PositionAccuracy.kt          Accuracy scale, notch, and labels
    theme/Theme.kt               Dynamic colour and fallback palettes
    DisplayFormat.kt             Locale-aware speed, distance, and time text from SI values
  tracking/
    TripModels.kt                Samples, route summary, and distance rules
    TripDatabase.kt              Room entities, queries, and migrations
    TripRecorder.kt              One command queue for trip changes and location demand
    RecordingService.kt          Location foreground service and notification
    TrackingViewModel.kt         Trip list, route details, and GPX export
    TrackingController.kt        Recording commands, access checks, and settings actions
    TrackingHost.kt              Trip state for the screens and the trip dialogs
    TripsScreen.kt               Trips tab
    LiveTripScreen.kt            Current trip with the live map
    SavedTripScreen.kt           Saved trip with its map and details
    TripComponents.kt            Shared trip controls, values, and labels
    RouteMap.kt                  MapLibre route map
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
12. Use controlled satellite and compass reports on the details sky map. Check angles,
    heading text, true-north correction, compass expiry, background cleanup, themes,
    200% text, and landscape.
13. Use controlled position, satellite, and compass reports on the home satellite ring.
    Check the reported phone direction, the kept correction, compass expiry, background
    cleanup, GPS details, paused GPS, 200% text, and landscape. The compass listener must
    exist only while the ring shows satellites and the activity is resumed.

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

## Verification on 2026-10-22

### Home satellite ring

- Satellites around the speed use reported direction and elevation and turn with the phone
  heading. The ring keeps the earlier abstract style and adds a small N. Brighter markers have
  stronger signals. GPS details keeps the full sky map. The last true-north correction stays in
  memory while GPS searches.
- A first version on this date put a compass bezel and direction text on home. After review,
  home returned to the abstract style. The bezel remains only in GPS details.
- Baseline: `5303e43` plus the sky map, phone heading, and home satellite ring. Debug app, test
  APK, and release builds passed. Debug lint found no errors or warnings; one existing optional
  state-boxing hint remains. Release lint was not repeated.
- Thirteen calculation tests passed. Eight API 36 emulator tests passed on the final APKs: the
  new home ring test, the details sky map, compass transforms, satellite display, home layout
  with production location delivery, and both GPS details tests.
- Selected captures use simulated position, satellite, and compass data with a true heading of
  90 degrees. Landscape on the test phone shows speed without the ring. Hardware compass
  accuracy, calibration, interference, TalkBack, and battery use remain untested.
- Debug APK SHA-256: `a8c15a930b153e3da1e439b28ab5c89f30b1568e4cbbf337a4de73f204dd3934`.
  Local evidence: `artifacts/sky-ring-test-results.json` and `artifacts/sky-ring-captures/`.

## Verification on 2026-10-21

### Phone heading

- The top of the sky map now follows the upper edge of the screen. Markers and direction
  labels turn together. The existing GPS fix supplies the magnetic correction. A missing
  compass or correction produces a stated fallback with north at the top.
- Baseline: `5303e43` plus the sky map and phone heading. Debug app and test APK builds,
  debug lint, ten calculation tests, and four API 36 emulator tests passed. One existing
  optional state-boxing lint hint remains; there are no lint errors or warnings.
- Android coordinate checks cover all display rotations, a vertical edge, and the GPS
  correction. Controlled map checks cover heading changes, two-second expiry, missing
  correction, background cleanup, return, and disposal. Production GPS delivery and
  listener cleanup passed. The details layout passed in both themes, landscape, and 200% text.
- Selected captures use simulated position, satellite, and compass data. They show a
  true heading of 90 degrees. Hardware compass accuracy, calibration, magnetic interference,
  TalkBack, and battery use remain untested. Release checks were not repeated.
- Debug APK SHA-256: `704f1f5c66db317086bfbf496cd2aea60d529f9df6ea3e64ff987a51d502af5b`.
  Local evidence: `artifacts/phone-heading-test-results.json` and `artifacts/phone-heading-build.log`.

### Earlier map with north at the top

- Baseline: `5303e43` plus this change. Debug app and test APK builds and debug lint passed.
  Lint reported no errors or warnings and one existing optional state-boxing hint.
- Eight unit tests and five focused API 36 emulator tests passed. Three new unit tests cover
  direction, elevation, and invalid angles. Device checks cover the map, grouping, accuracy,
  report expiry and recovery, home layout, and details layout. The map was checked in both
  themes, with 200% text, and in landscape. The full map fits inside the visible list.
- The first regression run found a text encoding error. The final build fixes it.
  Visual review found an oversized map in landscape. The final map uses window height.
- Debug APK SHA-256: `d319a9ef3bd2b786a41b02badc31268070d48fa9925d54c5eb4fadbe38ff2bc7`.
  Selected captures use controlled satellite reports. Local results are in
  `artifacts/sky-map-test-results.json`. Device settings were restored and GPS test providers
  were removed. Hardware satellite accuracy, TalkBack, and battery use remain untested.
  Release build and release lint were not repeated for this change.

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
