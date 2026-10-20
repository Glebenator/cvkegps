# CVKE GPS

A native Android speedometer built with Kotlin, Jetpack Compose, and Material 3.
This repository is for local development. No remote is configured.

Open [PROGRESS.html](PROGRESS.html) in a browser for the current status, screenshots,
code excerpts, verification results, and required and optional future work.
Update the report with each completed change, as specified in `AGENTS.md`.

## Current scope

- Show current GPS speed in km/h.
- Show GPS altitude, horizontal accuracy, and maximum speed since app launch.
- Request precise location through the Android permission dialog.
- Pause and resume GPS.
- Handle denied permission, disabled GPS, missing hardware, and loss of signal.
- Use system light or dark mode and dynamic colour on Android 12 and later.

The app does not record routes, save trips, detect activities, or transmit location.
GPS stops when the activity leaves the resumed state, including when the screen turns off.
The app does not keep the screen on.

The local design export in `GPS tracker app prototype` is excluded from Git.
Design 2a supplies the main concept: a large speed display, altitude, and maximum speed.
The implementation uses native Material components and system typography.
It does not include controls for features that are not implemented.

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

## GPS and battery rules

The source uses Android's GNSS provider through `LocationManagerCompat`. It does not need
Google Play services. GNSS supplies speed and altitude directly; the app does not estimate
speed from the distance between noisy position fixes.

- Request at most one update per second while the screen is active.
- Cancel the request immediately when the last active screen collector stops.
- Do not request background location, hold a wake lock, or poll for location.
- Use one cancellable freshness deadline after each fix.
- Discard fixes that are at least 10 seconds old or have a future timestamp.
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
2. Display speed, altitude, and maximum speed across activity recreation.
3. Reject a poor fix without increasing the maximum.
4. Clear an old reading and recover when a new fix arrives.
5. Remove GPS requests during pause and in the background; resume correctly.
6. Recover after GPS is disabled and enabled.

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

## Verification on 2026-10-20

- Debug and unsigned release builds passed.
- Debug and release lint reported no issues.
- All six device tests passed on an Android 16 (API 36) emulator.
- Light mode, dark mode, landscape, and 200% text were inspected.
- Real-device GPS accuracy, TalkBack, frame timing, and battery use remain unmeasured.

## Future work

Follow `AGENTS.md` for architecture, design, privacy, and test requirements.
Route recording needs persistent storage and its own lifecycle with a visible foreground
notification. It must not inherit the screen-only request lifetime.

Before publication, select the final application ID and launcher artwork, configure release
signing outside Git, and complete the store and privacy requirements. The current
`com.example.cvkegps` ID is a development ID. The release APK is unsigned.
