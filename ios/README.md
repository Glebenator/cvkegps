# CVKE GPS for iOS

The native iOS app: a speedometer and trip recorder with SwiftUI, Apple Maps, and the Liquid Glass design of iOS 26.
It has the same measurement rules and product behaviour as the Android app. See
[ADR 0007](../docs/adr/0007-build-the-ios-app-with-apple-maps-and-a-shared-swift-core.md) for the decisions and the
platform differences, and [PROGRESS.html](../PROGRESS.html#ios-app) for the test evidence.

## Open and run

1. Use a Mac with Xcode 26 or later.
2. Install XcodeGen: `brew install xcodegen`.
3. In this folder, run `xcodegen generate`. It makes `CVKEGPS.xcodeproj`, which is not in Git.
4. Open `CVKEGPS.xcodeproj`. Select the `CVKEGPS` scheme and an iPhone simulator or a phone.
5. For a phone, select your team in Signing & Capabilities for the `CVKEGPS` and `CVKEGPSWidgets` targets. A personal
   team can need another bundle ID than the development ID `com.example.cvkegps`.
6. Run. Select **Allow precise location**, then **Allow While Using App**.

The app needs no API key. Apple Maps receives the map area that you look at. Trips stay on the device.

## Tests

- Measurement rules, storage, and the recorder: in `CVKEGPSCore`, run `swift test`. This also works on Linux with the
  Swift toolchain and `libsqlite3-dev`.
- The UI flow: `xcodebuild test -project CVKEGPS.xcodeproj -scheme CVKEGPS -destination "platform=iOS Simulator,name=<iPhone>"`.
  It uses the production Core Location path with simulated locations from XCTest. It records a trip along a street,
  stops and saves it, replays it, and opens Settings. Each step attaches a screenshot.
- The workflow `.github/workflows/ios.yml` does both on a macOS runner. It runs the screens again in dark mode and with
  the largest text size, and uploads the screenshots as the `ios-captures` artifact.

Simulator results are functional evidence only. GPS accuracy, the fix source rule, recording with the screen locked,
the Live Activity, the motion sensors, and battery use need a real iPhone.

## Structure

```text
ios/
  project.yml                     XcodeGen project: the app, the widget extension, and the UI tests
  CVKEGPSCore/                    Swift package, Foundation only
    TripModels.swift, Measurements.swift, Units.swift    Models, the usability rules, and units
    RouteMath.swift               Distance, gaps, route lines, and the map frame
    ActivityEstimate.swift        Provisional activity from GPS speed
    SpeedColours.swift            Speed smoothing, the trip's speed range, and colour runs
    TripReplay.swift              Replay clock, values at the playhead, barometric height, acceleration, moments, chart data
    SensorRecord.swift            Sensor chunks in the Android storage format
    DisplayFormat.swift           Locale-aware text from SI values
    Settings.swift                Stored choices in user defaults
    TripStore.swift               SQLite store with the Room schema
    TripRecorder.swift            One ordered command queue for trip changes, location demand, and sensors
  CVKEGPS/
    App/                          App entry, app model, tabs and the recording accessory, keep screen on
    Location/                     Core Location service and the Core Motion recorder
    Recording/                    Trip commands and access, trip list, Live Activity, Speed tab state
    Screens/                      Speed, GPS details, Trips, current trip, saved trip, Settings, recording notice
    Map/                          Apple Maps route map, follow modes, markers, and speed colours
    Replay/                       Replay model, panel, speed profile, and Swift Charts
    Design/                       Colour tokens, glass components, and visible text
    Resources/                    App icon, accent colour, string catalog, and privacy manifest
  CVKEGPSWidgets/                 Live Activity, Dynamic Island, and the Control Center Start trip button
  Shared/                         Live Activity attributes and the trip intents, in the app and the extension
  CVKEGPSUITests/                 The UI flow
```
